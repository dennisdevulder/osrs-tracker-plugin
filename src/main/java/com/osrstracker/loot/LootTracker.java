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
package com.osrstracker.loot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.osrstracker.OsrsTrackerConfig;
import com.osrstracker.api.ApiClient;
import com.osrstracker.video.VideoRecorder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Collection;

/**
 * Tracks loot drops from NPCs and reports valuable drops to the API.
 *
 * This tracker processes loot drops from ServerNpcLoot events, calculates the total
 * value of the drop, and sends it to the API if it meets the configured minimum value threshold.
 */
@Slf4j
@Singleton
public class LootTracker
{
    private final ItemManager itemManager;
    private final ApiClient apiClient;
    private final OsrsTrackerConfig config;
    private final VideoRecorder videoRecorder;

    @Inject
    public LootTracker(ItemManager itemManager, ApiClient apiClient, OsrsTrackerConfig config, VideoRecorder videoRecorder)
    {
        this.itemManager = itemManager;
        this.apiClient = apiClient;
        this.config = config;
        this.videoRecorder = videoRecorder;
    }

    /**
     * Processes a loot drop from an NPC and sends it to the API if it meets value requirements.
     *
     * The loot drop is only sent if:
     * 1. Loot tracking is enabled in the config
     * 2. The total value meets or exceeds the configured minimum loot value
     * 3. The items collection is not empty
     *
     * @param npcName The name of the NPC that dropped the loot
     * @param items Collection of items that were dropped
     */
    public void processLootDrop(String npcName, Collection<ItemStack> items)
    {
        // Check if loot tracking is enabled
        if (!config.trackLoot())
        {
            return;
        }

        // Ignore empty loot drops
        if (items == null || items.isEmpty())
        {
            return;
        }

        // Use a default name if the NPC name is null
        String sourceName = (npcName != null) ? npcName : "Unknown";

        log.info("Loot received from {}: {} items", sourceName, items.size());

        // Calculate total loot value and build item list
        LootDropData lootData = calculateLootValue(items);

        // Check if the loot meets the minimum value threshold (100k minimum enforced)
        int minValue = OsrsTrackerConfig.getMinimumLootValue(config);
        if (lootData.totalValue >= minValue)
        {
            sendLootDropToApi(sourceName, lootData);
        }
        else
        {
            log.debug("Loot value ({} gp) below minimum threshold ({} gp), not sending",
                lootData.totalValue, minValue);
        }
    }

    /**
     * Calculates the total value of a loot drop and builds a JSON representation of the items.
     *
     * For each item in the drop:
     * - Retrieves item composition (name, properties)
     * - Gets current Grand Exchange price
     * - Calculates total value (price × quantity)
     *
     * @param items Collection of items in the loot drop
     * @return LootDropData containing the total value and JSON array of items
     */
    private LootDropData calculateLootValue(Collection<ItemStack> items)
    {
        JsonArray itemsArray = new JsonArray();
        long totalValue = 0;

        for (ItemStack itemStack : items)
        {
            // Get item information from the item manager
            ItemComposition itemComposition = itemManager.getItemComposition(itemStack.getId());
            long itemPrice = itemManager.getItemPrice(itemStack.getId());
            long itemTotalValue = itemPrice * itemStack.getQuantity();

            totalValue += itemTotalValue;

            // Build JSON object for this item
            JsonObject itemJson = new JsonObject();
            itemJson.addProperty("name", itemComposition.getName());
            itemJson.addProperty("quantity", itemStack.getQuantity());
            itemJson.addProperty("value", itemTotalValue);

            itemsArray.add(itemJson);
        }

        return new LootDropData(totalValue, itemsArray);
    }

    /**
     * Sends a loot drop event to the API with video capture.
     *
     * @param sourceName The name of the NPC that dropped the loot
     * @param lootData The loot drop data containing items and total value
     */
    private void sendLootDropToApi(String sourceName, LootDropData lootData)
    {
        // Capture video with the loot drop event
        videoRecorder.captureEventVideo((screenshotBase64, videoBase64) -> {
            JsonObject payload = new JsonObject();
            payload.addProperty("source", sourceName);
            payload.add("items", lootData.items);
            payload.addProperty("total_value", lootData.totalValue);

            apiClient.sendEventToApi(
                "/api/webhooks/loot_drop",
                payload.toString(),
                "loot drop from " + sourceName,
                screenshotBase64,
                videoBase64
            );
        });
    }

    /**
     * Simple data class to hold loot drop information.
     */
    private static class LootDropData
    {
        final long totalValue;
        final JsonArray items;

        LootDropData(long totalValue, JsonArray items)
        {
            this.totalValue = totalValue;
            this.items = items;
        }
    }
}
