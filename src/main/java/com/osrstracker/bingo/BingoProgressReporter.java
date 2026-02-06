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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.osrstracker.OsrsTrackerConfig;
import com.osrstracker.video.VideoRecorder;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.List;

/**
 * Reports bingo progress events to the API.
 * This is separate from the normal event tracking - bingo progress updates
 * don't create timeline events, they just update bingo tile counts.
 *
 * Usage:
 * - Call report methods when an event occurs that might match a bingo tile
 * - The subscription manager handles filtering (only report if subscribed)
 * - Progress updates are silent (no timeline spam)
 */
@Slf4j
@Singleton
public class BingoProgressReporter
{
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final OsrsTrackerConfig config;
    private final Gson gson;
    private final BingoSubscriptionManager subscriptionManager;
    private final VideoRecorder videoRecorder;

    @Inject
    public BingoProgressReporter(
        OkHttpClient httpClient,
        OsrsTrackerConfig config,
        Gson gson,
        BingoSubscriptionManager subscriptionManager,
        VideoRecorder videoRecorder)
    {
        this.httpClient = httpClient.newBuilder()
            .cache(null)
            .build();
        this.config = config;
        this.gson = gson;
        this.subscriptionManager = subscriptionManager;
        this.videoRecorder = videoRecorder;
    }

    /**
     * Report a boss kill to bingo progress.
     */
    public void reportBossKill(int npcId, String npcName)
    {
        if (!subscriptionManager.shouldTrackBossKill(npcId))
        {
            return;
        }

        log.debug("Reporting boss kill to bingo: {} ({})", npcName, npcId);

        boolean needsProof = subscriptionManager.needsProofForBossKill(npcId);

        JsonObject eventData = new JsonObject();
        eventData.addProperty("npc_id", npcId);
        eventData.addProperty("npc_name", npcName);

        if (needsProof)
        {
            log.debug("Capturing proof for boss kill milestone");
            videoRecorder.captureScreenshotOnly((screenshot, videoKey) -> {
                sendProgress("boss_kill", eventData, screenshot, null);
            });
        }
        else
        {
            sendProgress("boss_kill", eventData, null, null);
        }
    }

    /**
     * Report an NPC kill to bingo progress.
     */
    public void reportNpcKill(int npcId, String npcName)
    {
        if (!subscriptionManager.shouldTrackNpcKill(npcId))
        {
            log.debug("NPC {} ({}) is not tracked for bingo", npcName, npcId);
            return;
        }

        log.debug("Reporting NPC kill to bingo: {} ({})", npcName, npcId);

        boolean needsProof = subscriptionManager.needsProofForNpcKill(npcId);
        log.debug("needsProof={} for NPC kill {} ({})", needsProof, npcName, npcId);

        JsonObject eventData = new JsonObject();
        eventData.addProperty("npc_id", npcId);
        eventData.addProperty("npc_name", npcName);

        if (needsProof)
        {
            log.debug("Capturing video proof for NPC kill milestone: {} ({})", npcName, npcId);
            // Use captureEventVideo to get video based on user's quality settings
            videoRecorder.captureEventVideo((screenshot, videoKey) -> {
                log.debug("Proof captured (screenshot={}, video={}), sending progress",
                    screenshot != null ? "yes" : "no",
                    videoKey != null ? videoKey : "none");
                sendProgress("npc_kill", eventData, screenshot, videoKey);
            }, null);
        }
        else
        {
            log.debug("Sending progress WITHOUT proof");
            sendProgress("npc_kill", eventData, null, null);
        }
    }

    /**
     * Report loot items to bingo progress.
     * Checks both specific item IDs and total value threshold.
     */
    public void reportLoot(int npcId, String npcName, List<LootItem> items, long totalValue)
    {
        if (!subscriptionManager.hasActiveEvent())
        {
            return;
        }

        // Check if any item matches subscribed item_ids
        boolean hasMatchingItem = items.stream()
            .anyMatch(item -> subscriptionManager.shouldTrackItem(item.getItemId()));

        // Check if total value meets threshold
        boolean meetsValueThreshold = subscriptionManager.shouldTrackLootValue(totalValue);

        if (!hasMatchingItem && !meetsValueThreshold)
        {
            return;
        }

        log.debug("Reporting loot to bingo from {} ({}): {} items, {} value",
            npcName, npcId, items.size(), totalValue);

        JsonObject eventData = new JsonObject();
        eventData.addProperty("npc_id", npcId);
        eventData.addProperty("npc_name", npcName);
        eventData.addProperty("total_value", totalValue);

        JsonArray itemsArray = new JsonArray();
        for (LootItem item : items)
        {
            JsonObject itemJson = new JsonObject();
            itemJson.addProperty("item_id", item.getItemId());
            itemJson.addProperty("name", item.getName());
            itemJson.addProperty("quantity", item.getQuantity());
            itemJson.addProperty("value", item.getValue());
            itemsArray.add(itemJson);
        }
        eventData.add("items", itemsArray);

        // For loot, we usually want proof (it's a significant event)
        videoRecorder.captureScreenshotOnly((screenshot, videoKey) -> {
            sendProgress("loot_drop", eventData, screenshot, null);
        });
    }

    /**
     * Report clue scroll completion to bingo progress.
     */
    public void reportClueComplete(String tier, List<LootItem> rewards, long totalValue)
    {
        if (!subscriptionManager.shouldTrackClue(tier))
        {
            return;
        }

        log.debug("Reporting clue completion to bingo: {} tier, {} value", tier, totalValue);

        JsonObject eventData = new JsonObject();
        eventData.addProperty("tier", tier);
        eventData.addProperty("total_value", totalValue);

        JsonArray rewardsArray = new JsonArray();
        for (LootItem item : rewards)
        {
            JsonObject itemJson = new JsonObject();
            itemJson.addProperty("item_id", item.getItemId());
            itemJson.addProperty("name", item.getName());
            itemJson.addProperty("quantity", item.getQuantity());
            itemJson.addProperty("value", item.getValue());
            rewardsArray.add(itemJson);
        }
        eventData.add("rewards", rewardsArray);

        // Clue completions should always have proof
        videoRecorder.captureScreenshotOnly((screenshot, videoKey) -> {
            sendProgress("clue_complete", eventData, screenshot, null);
        });
    }

    /**
     * Report raid completion to bingo progress.
     */
    public void reportRaidComplete(String raidName, boolean completed, int deaths, long durationMs)
    {
        if (!subscriptionManager.shouldTrackRaids())
        {
            return;
        }

        log.debug("Reporting raid completion to bingo: {} (completed: {}, deaths: {})",
            raidName, completed, deaths);

        JsonObject eventData = new JsonObject();
        eventData.addProperty("raid_name", raidName);
        eventData.addProperty("completed", completed);
        eventData.addProperty("deaths", deaths);
        eventData.addProperty("duration_ms", durationMs);

        videoRecorder.captureScreenshotOnly((screenshot, videoKey) -> {
            sendProgress("raid_complete", eventData, screenshot, null);
        });
    }

    /**
     * Report gauntlet completion to bingo progress.
     */
    public void reportGauntletComplete(boolean corrupted, boolean completed, int deaths, long durationMs)
    {
        if (!subscriptionManager.shouldTrackGauntlet())
        {
            return;
        }

        log.debug("Reporting gauntlet completion to bingo: corrupted={}, completed={}, deaths={}",
            corrupted, completed, deaths);

        JsonObject eventData = new JsonObject();
        eventData.addProperty("corrupted", corrupted);
        eventData.addProperty("completed", completed);
        eventData.addProperty("deaths", deaths);
        eventData.addProperty("duration_ms", durationMs);

        videoRecorder.captureScreenshotOnly((screenshot, videoKey) -> {
            sendProgress("gauntlet_complete", eventData, screenshot, null);
        });
    }

    /**
     * Report slayer task completion to bingo progress.
     */
    public void reportSlayerTaskComplete(String taskName, int amount, String slayerMaster)
    {
        if (!subscriptionManager.shouldTrackSlayer())
        {
            return;
        }

        log.debug("Reporting slayer task completion to bingo: {} x{} from {}",
            taskName, amount, slayerMaster);

        JsonObject eventData = new JsonObject();
        eventData.addProperty("task_name", taskName);
        eventData.addProperty("amount", amount);
        eventData.addProperty("slayer_master", slayerMaster);

        videoRecorder.captureScreenshotOnly((screenshot, videoKey) -> {
            sendProgress("slayer_task_complete", eventData, screenshot, null);
        });
    }

    /**
     * Send progress update to the API.
     */
    private void sendProgress(String eventType, JsonObject eventData, String screenshot, String videoKey)
    {
        String apiUrl = OsrsTrackerConfig.getEffectiveApiUrl();
        String apiToken = config.apiToken();

        if (apiToken == null || apiToken.isEmpty())
        {
            log.debug("API token not configured, cannot send bingo progress");
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("event_type", eventType);
        payload.add("event_data", eventData);

        if (screenshot != null)
        {
            payload.addProperty("screenshot", screenshot);
        }
        if (videoKey != null)
        {
            payload.addProperty("replay_gif", videoKey);
        }

        RequestBody body = RequestBody.create(JSON, payload.toString());
        Request request = new Request.Builder()
            .url(apiUrl + "/api/bingo/progress")
            .addHeader("Authorization", "Bearer " + apiToken)
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build();

        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.error("Failed to send bingo progress: {}", e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                try
                {
                if (response.isSuccessful())
                {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    log.debug("Bingo progress sent successfully: {}", eventType);

                        // Parse response and update local state
                        handleProgressResponse(responseBody);
                    }
                else
                {
                    log.warn("Failed to send bingo progress: {} - {}",
                        response.code(), response.message());
                }
                }
                catch (Exception e)
                {
                    log.error("Error processing bingo progress response", e);
                }
                finally
                {
                    response.close();
                }
            }
        });
    }

    /**
     * Handle the API response and update local subscription state.
     */
    private void handleProgressResponse(String responseBody)
    {
        try
        {
            JsonObject response = gson.fromJson(responseBody, JsonObject.class);

            if (response.has("bingo") && response.get("bingo").isJsonObject())
            {
                JsonObject bingo = response.getAsJsonObject("bingo");

                if (bingo.has("progress") && bingo.get("progress").isJsonArray())
                {
                    for (var element : bingo.getAsJsonArray("progress"))
                    {
                        JsonObject progress = element.getAsJsonObject();
                        int tileId = progress.get("tile_id").getAsInt();
                        int currentCount = progress.get("current_count").getAsInt();
                        Integer nextMilestone = progress.has("next_proof_milestone") &&
                            !progress.get("next_proof_milestone").isJsonNull()
                            ? progress.get("next_proof_milestone").getAsInt()
                            : null;

                        subscriptionManager.updateTileProgress(tileId, currentCount, nextMilestone);

                        // Log completion
                        if (progress.has("completed") && progress.get("completed").getAsBoolean())
                        {
                            log.info("Bingo tile completed! ID: {}", tileId);
                        }
                    }
                }
            }
        }
        catch (Exception e)
        {
            log.error("Failed to parse bingo progress response", e);
        }
    }

    /**
     * Simple data class for loot items.
     */
    public static class LootItem
    {
        private final int itemId;
        private final String name;
        private final int quantity;
        private final long value;

        public LootItem(int itemId, String name, int quantity, long value)
        {
            this.itemId = itemId;
            this.name = name;
            this.quantity = quantity;
            this.value = value;
        }

        public int getItemId() { return itemId; }
        public String getName() { return name; }
        public int getQuantity() { return quantity; }
        public long getValue() { return value; }
    }
}
