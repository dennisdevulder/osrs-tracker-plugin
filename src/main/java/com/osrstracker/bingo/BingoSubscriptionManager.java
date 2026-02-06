/*
 * Copyright (c) 2025, Dennis De Vulder
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.osrstracker.bingo;

import com.google.gson.Gson;
import com.osrstracker.OsrsTrackerConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Manages bingo subscriptions - what the plugin should track for the user's active bingo event.
 *
 * Smart polling logic:
 * - On login: Always fetch subscriptions
 * - If active event OR event starting within 6 hours → poll every 5 minutes
 * - Otherwise → don't poll until next login (6 hour forced logout handles refresh)
 *
 * This minimizes API calls while ensuring we catch events that start during a session.
 */
@Slf4j
@Singleton
public class BingoSubscriptionManager
{
    private static final long SIX_HOURS_MS = TimeUnit.HOURS.toMillis(6);
    private static final long POLL_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5);

    private final OkHttpClient httpClient;
    private final OsrsTrackerConfig config;
    private final Gson gson;

    @Getter
    private volatile BingoSubscription subscription;

    private volatile long lastFetchTime = 0;
    private volatile boolean shouldPoll = false;

    // Listeners for subscription changes
    private Runnable onSubscriptionChanged;

    @Inject
    public BingoSubscriptionManager(OkHttpClient httpClient, OsrsTrackerConfig config, Gson gson)
    {
        // Create our own client without disk cache
        this.httpClient = httpClient.newBuilder()
            .cache(null)
            .build();
        this.config = config;
        this.gson = gson;
        this.subscription = BingoSubscription.empty();
    }

    /**
     * Set a callback to be invoked when subscriptions change.
     * Used to update the UI when a new event starts.
     */
    public void setOnSubscriptionChanged(Runnable callback)
    {
        this.onSubscriptionChanged = callback;
    }

    /**
     * Initialize on login - fetches subscriptions and determines polling strategy.
     */
    public void initialize()
    {
        log.debug("Initializing bingo subscription manager");
        fetchSubscriptions();
    }

    /**
     * Reset on logout - clears subscriptions and stops polling.
     */
    public void reset()
    {
        log.debug("Resetting bingo subscription manager");
        subscription = BingoSubscription.empty();
        lastFetchTime = 0;
        shouldPoll = false;
        notifySubscriptionChanged();
    }

    /**
     * Check if we should refresh subscriptions (called on game tick).
     * Only polls if we determined we should based on event timing.
     */
    public void checkForRefresh()
    {
        if (!shouldPoll)
        {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastFetchTime >= POLL_INTERVAL_MS)
        {
            log.debug("Polling for bingo subscription updates");
            fetchSubscriptions();
        }
    }

    /**
     * Fetch subscriptions from the API.
     */
    public void fetchSubscriptions()
    {
        String apiUrl = OsrsTrackerConfig.getEffectiveApiUrl();
        String apiToken = config.apiToken();

        if (apiToken == null || apiToken.isEmpty())
        {
            log.debug("API token not configured, bingo tracking disabled");
            return;
        }

        Request request = new Request.Builder()
            .url(apiUrl + "/api/bingo/subscriptions")
            .addHeader("Authorization", "Bearer " + apiToken)
            .get()
            .build();

        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.error("Failed to fetch bingo subscriptions: {}", e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                try
                {
                    if (!response.isSuccessful())
                    {
                        log.warn("Failed to fetch bingo subscriptions: {} - {}",
                            response.code(), response.message());
                        return;
                    }

                    String body = response.body() != null ? response.body().string() : "";
                    BingoSubscription newSubscription = gson.fromJson(body, BingoSubscription.class);

                    if (newSubscription != null)
                    {
                        boolean wasActive = subscription.isHasActiveEvent();
                        subscription = newSubscription;
                        lastFetchTime = System.currentTimeMillis();

                        // Determine if we should poll
                        updatePollingStrategy();

                        // Log the result
                        if (subscription.isHasActiveEvent())
                        {
                            BingoSubscription.BingoEventInfo event = subscription.getEvent();
                            log.debug("Bingo tracking active: {} (tracking {} bosses, {} NPCs, {} items)",
                                event != null ? event.getName() : "Unknown",
                                subscription.getSubscriptions().getBossIds().size(),
                                subscription.getSubscriptions().getNpcIds().size(),
                                subscription.getSubscriptions().getItemIds().size());

                            // Log tile progress with proof milestones
                            for (BingoSubscription.TileProgress tile : subscription.getTileProgress())
                            {
                                log.debug("  Tile {}: {} - count={}/{}, nextProofMilestone={}, proofInterval={}",
                                    tile.getTileId(),
                                    tile.getDescription(),
                                    tile.getCurrentCount(),
                                    tile.getRequiredCount(),
                                    tile.getNextProofMilestone(),
                                    tile.getProofInterval());
                            }
                        }
                        else
                        {
                            log.debug("No active bingo event for user");
                        }

                        // Notify listeners if state changed
                        if (wasActive != subscription.isHasActiveEvent())
                        {
                            notifySubscriptionChanged();
                        }
                    }
                }
                catch (Exception e)
                {
                    log.error("Error parsing bingo subscriptions", e);
                }
                finally
                {
                    response.close();
                }
            }
        });
    }

    /**
     * Update polling strategy based on current subscription.
     * Poll every 5 minutes if:
     * - User has an active event
     * - An event is starting within 6 hours
     */
    private void updatePollingStrategy()
    {
        if (subscription.isHasActiveEvent())
        {
            // Active event - poll frequently
            shouldPoll = true;
            log.debug("Bingo polling enabled: active event");
        }
        else if (subscription.getNextEventStartsInMs() != null &&
                 subscription.getNextEventStartsInMs() <= SIX_HOURS_MS)
        {
            // Event starting soon - poll frequently
            shouldPoll = true;
            log.debug("Bingo polling enabled: event starting in {}ms", subscription.getNextEventStartsInMs());
        }
        else
        {
            // No active event and nothing starting soon - don't poll
            shouldPoll = false;
            log.debug("Bingo polling disabled: no events within 6 hours");
        }
    }

    private void notifySubscriptionChanged()
    {
        if (onSubscriptionChanged != null)
        {
            onSubscriptionChanged.run();
        }
    }

    // ============== Query Methods ==============

    /**
     * Check if we have an active bingo event.
     */
    public boolean hasActiveEvent()
    {
        if (subscription == null)
        {
            return false;
        }
        return subscription.isHasActiveEvent();
    }

    /**
     * Get the active event name (or null if none).
     */
    public String getActiveEventName()
    {
        if (!hasActiveEvent() || subscription.getEvent() == null)
        {
            return null;
        }
        return subscription.getEvent().getName();
    }

    /**
     * Check if we should track this NPC kill.
     */
    public boolean shouldTrackNpcKill(int npcId)
    {
        if (!hasActiveEvent())
        {
            return false;
        }
        return subscription.getSubscriptions().getNpcIds().contains(npcId);
    }

    /**
     * Check if we should track this boss kill.
     */
    public boolean shouldTrackBossKill(int npcId)
    {
        if (!hasActiveEvent())
        {
            return false;
        }
        return subscription.getSubscriptions().getBossIds().contains(npcId);
    }

    /**
     * Check if we should track this item drop.
     */
    public boolean shouldTrackItem(int itemId)
    {
        if (!hasActiveEvent())
        {
            return false;
        }
        return subscription.getSubscriptions().getItemIds().contains(itemId);
    }

    /**
     * Check if loot value meets the tracking threshold.
     */
    public boolean shouldTrackLootValue(long totalValue)
    {
        if (!hasActiveEvent())
        {
            return false;
        }
        Long minValue = subscription.getSubscriptions().getMinLootValue();
        return minValue != null && totalValue >= minValue;
    }

    /**
     * Check if we should track this clue tier.
     */
    public boolean shouldTrackClue(String tier)
    {
        if (!hasActiveEvent() || tier == null)
        {
            return false;
        }
        return subscription.getSubscriptions().getClueTiers().contains(tier.toLowerCase());
    }

    /**
     * Check if we should track raids.
     */
    public boolean shouldTrackRaids()
    {
        return hasActiveEvent() && subscription.getSubscriptions().isTrackRaids();
    }

    /**
     * Check if we should track gauntlet.
     */
    public boolean shouldTrackGauntlet()
    {
        return hasActiveEvent() && subscription.getSubscriptions().isTrackGauntlet();
    }

    /**
     * Check if we should track slayer tasks.
     */
    public boolean shouldTrackSlayer()
    {
        return hasActiveEvent() && subscription.getSubscriptions().isTrackSlayer();
    }

    /**
     * Find tiles that match a boss kill and check if any need proof.
     */
    public boolean needsProofForBossKill(int npcId)
    {
        if (!hasActiveEvent())
        {
            return false;
        }

        for (BingoSubscription.TileProgress tile : subscription.getTileProgress())
        {
            if (!"boss_kill_count".equals(tile.getTriggerType()))
            {
                continue;
            }

            // Check if this tile tracks this boss
            Object bossIds = tile.getConfig().get("boss_ids");
            if (bossIds instanceof List)
            {
                @SuppressWarnings("unchecked")
                List<Number> ids = (List<Number>) bossIds;
                boolean matches = ids.stream().anyMatch(id -> id.intValue() == npcId);

                if (matches && tile.needsProofAtCount(tile.getCurrentCount() + 1))
                {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Find tiles that match an NPC kill and check if any need proof.
     */
    public boolean needsProofForNpcKill(int npcId)
    {
        if (!hasActiveEvent())
        {
            log.debug("needsProofForNpcKill: No active event");
            return false;
        }

        for (BingoSubscription.TileProgress tile : subscription.getTileProgress())
        {
            if (!"npc_kill_count".equals(tile.getTriggerType()))
            {
                continue;
            }

            Object npcIds = tile.getConfig().get("npc_ids");
            if (npcIds instanceof List)
            {
                @SuppressWarnings("unchecked")
                List<Number> ids = (List<Number>) npcIds;
                boolean matches = ids.stream().anyMatch(id -> id.intValue() == npcId);

                if (matches)
                {
                    int currentCount = tile.getCurrentCount();
                    Integer nextMilestone = tile.getNextProofMilestone();
                    boolean needsProof = tile.needsProofAtCount(currentCount + 1);
                    log.debug("Tile {} ({}): currentCount={}, nextMilestone={}, checkCount={}, needsProof={}",
                        tile.getTileId(), tile.getDescription(), currentCount, nextMilestone, currentCount + 1, needsProof);

                    if (needsProof)
                    {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Update local tile progress after receiving response from API.
     * This keeps our local state in sync without needing to refetch.
     */
    public void updateTileProgress(int tileId, int newCount, Integer nextProofMilestone)
    {
        if (subscription == null || subscription.getTileProgress() == null)
        {
            log.warn("Cannot update tile progress: subscription or tileProgress is null");
            return;
        }

        for (BingoSubscription.TileProgress tile : subscription.getTileProgress())
        {
            if (tile.getTileId() == tileId)
            {
                int oldCount = tile.getCurrentCount();
                Integer oldMilestone = tile.getNextProofMilestone();
                tile.setCurrentCount(newCount);
                tile.setNextProofMilestone(nextProofMilestone);
                log.debug("Updated tile {} ({}): count {} -> {}, nextMilestone {} -> {}",
                    tileId, tile.getDescription(), oldCount, newCount, oldMilestone, nextProofMilestone);
                break;
            }
        }

        notifySubscriptionChanged();
    }

    /**
     * Get the number of tiles being tracked.
     */
    public int getTrackedTileCount()
    {
        if (subscription == null || subscription.getTileProgress() == null)
        {
            return 0;
        }
        return subscription.getTileProgress().size();
    }

    /**
     * Get the number of completed tiles (for display).
     */
    public int getCompletedTileCount()
    {
        if (subscription == null || subscription.getTileProgress() == null)
        {
            return 0;
        }
        return (int) subscription.getTileProgress().stream()
            .filter(t -> t.getCurrentCount() >= t.getRequiredCount())
            .count();
    }
}
