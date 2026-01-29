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

import com.google.gson.annotations.SerializedName;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Data class representing the bingo subscription response from the API.
 * This tells the plugin what to track for the user's active bingo event.
 */
@Data
public class BingoSubscription
{
    @SerializedName("has_active_event")
    private boolean hasActiveEvent;

    @SerializedName("event")
    private BingoEventInfo event;

    @SerializedName("subscriptions")
    private SubscriptionConfig subscriptions;

    @SerializedName("tile_progress")
    private List<TileProgress> tileProgress = new ArrayList<>();

    /**
     * Milliseconds until the next event starts (if no active event).
     * Used for smart polling: if within 6 hours, poll frequently.
     * Null if user has an active event or no upcoming events.
     */
    @SerializedName("next_event_starts_in_ms")
    private Long nextEventStartsInMs;

    /**
     * Information about the active bingo event.
     */
    @Data
    public static class BingoEventInfo
    {
        private int id;
        private String name;

        @SerializedName("starts_at")
        private String startsAt;

        @SerializedName("ends_at")
        private String endsAt;
    }

    /**
     * Configuration of what the plugin should track.
     * These are aggregated from all incomplete tiles.
     */
    @Data
    public static class SubscriptionConfig
    {
        @SerializedName("npc_ids")
        private Set<Integer> npcIds = new HashSet<>();

        @SerializedName("boss_ids")
        private Set<Integer> bossIds = new HashSet<>();

        @SerializedName("item_ids")
        private Set<Integer> itemIds = new HashSet<>();

        @SerializedName("min_loot_value")
        private Long minLootValue;

        @SerializedName("clue_tiers")
        private Set<String> clueTiers = new HashSet<>();

        @SerializedName("track_raids")
        private boolean trackRaids;

        @SerializedName("track_gauntlet")
        private boolean trackGauntlet;

        @SerializedName("track_slayer")
        private boolean trackSlayer;
    }

    /**
     * Progress information for a single bingo tile.
     * Used to track counts and know when to capture proof.
     */
    @Data
    public static class TileProgress
    {
        @SerializedName("tile_id")
        private int tileId;

        @SerializedName("trigger_type")
        private String triggerType;

        @SerializedName("description")
        private String description;

        @SerializedName("current_count")
        private int currentCount;

        @SerializedName("required_count")
        private int requiredCount;

        @SerializedName("proof_interval")
        private Integer proofInterval;

        @SerializedName("next_proof_milestone")
        private Integer nextProofMilestone;

        @SerializedName("config")
        private Map<String, Object> config;

        /**
         * Check if we need to capture proof at the next count.
         */
        public boolean needsProofAtCount(int count)
        {
            return nextProofMilestone != null && count >= nextProofMilestone;
        }

        /**
         * Get progress as a fraction (0.0 to 1.0).
         */
        public double getProgressFraction()
        {
            if (requiredCount <= 0) return 1.0;
            return Math.min(1.0, (double) currentCount / requiredCount);
        }
    }

    /**
     * Creates an empty subscription (no active event).
     */
    public static BingoSubscription empty()
    {
        BingoSubscription sub = new BingoSubscription();
        sub.setHasActiveEvent(false);
        sub.setSubscriptions(new SubscriptionConfig());
        sub.setTileProgress(new ArrayList<>());
        return sub;
    }
}
