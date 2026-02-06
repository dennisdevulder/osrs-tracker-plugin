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
package com.osrstracker.clue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.osrstracker.OsrsTrackerConfig;
import com.osrstracker.api.ApiClient;
import com.osrstracker.bingo.BingoProgressReporter;
import com.osrstracker.video.VideoRecorder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks clue scroll completions and captures the reward interface.
 *
 * This tracker detects when a player opens a clue casket by:
 * 1. Listening for the clue completion chat message to get the tier and count
 * 2. Listening for the reward widget to load (InterfaceID.TRAIL_REWARDSCREEN)
 * 3. Extracting reward items from the TRAIL_REWARDINV container
 * 4. Capturing a video/screenshot and sending to the API
 *
 * Based on patterns from Dink and Screenshot plugins.
 */
@Slf4j
@Singleton
public class ClueScrollTracker
{
    // Pattern to match: "You have completed 42 easy Treasure Trails."
    private static final Pattern CLUE_COMPLETE_PATTERN =
        Pattern.compile("You have completed (\\d+) (\\w+) Treasure Trails?\\.");

    // Valid clue tiers
    private static final String[] CLUE_TIERS = {"beginner", "easy", "medium", "hard", "elite", "master"};

    private final Client client;
    private final ClientThread clientThread;
    private final ItemManager itemManager;
    private final ApiClient apiClient;
    private final OsrsTrackerConfig config;
    private final VideoRecorder videoRecorder;
    private final BingoProgressReporter bingoProgressReporter;

    // State tracking for matching chat message to widget
    private String pendingClueTier = null;
    private int pendingClueCount = 0;

    @Inject
    public ClueScrollTracker(
            Client client,
            ClientThread clientThread,
            ItemManager itemManager,
            ApiClient apiClient,
            OsrsTrackerConfig config,
            VideoRecorder videoRecorder,
            BingoProgressReporter bingoProgressReporter)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.itemManager = itemManager;
        this.apiClient = apiClient;
        this.config = config;
        this.videoRecorder = videoRecorder;
        this.bingoProgressReporter = bingoProgressReporter;
    }

    /**
     * Process a game chat message to capture clue completion info.
     * This message appears just before the reward widget opens.
     * Called by the main plugin's onChatMessage handler.
     *
     * @param message The chat message text (should already have HTML tags removed)
     */
    public void processGameMessage(String message)
    {
        if (!config.trackClueScrolls())
        {
            return;
        }

        Matcher matcher = CLUE_COMPLETE_PATTERN.matcher(message);

        if (matcher.find())
        {
            pendingClueCount = Integer.parseInt(matcher.group(1));
            String tier = matcher.group(2).toLowerCase();

            // Validate tier
            if (isValidTier(tier))
            {
                pendingClueTier = tier;
                log.debug("Clue completion detected: {} {} clues completed", pendingClueCount, pendingClueTier);
            }
        }
    }

    /**
     * Returns the widget group ID for the clue reward screen.
     * Used by the main plugin to check if this tracker should handle the widget.
     *
     * @return The InterfaceID for TRAIL_REWARDSCREEN
     */
    public int getRewardWidgetGroupId()
    {
        return InterfaceID.TRAIL_REWARDSCREEN;
    }

    /**
     * Handle the clue reward widget being loaded.
     * Called by the main plugin's onWidgetLoaded handler.
     */
    public void onRewardWidgetLoaded()
    {
        if (!config.trackClueScrolls())
        {
            return;
        }

        log.debug("Clue reward widget loaded");

        // Use clientThread.invokeLater to ensure widgets are fully populated
        clientThread.invokeLater(() -> {
            // Small delay to ensure items are loaded in the container
            processClueReward();
        });
    }

    /**
     * Process the clue reward - extract items and send to API.
     */
    private void processClueReward()
    {
        // Get the reward container (TRAIL_REWARDINV = 141 is for treasure trail rewards)
        ItemContainer rewardContainer = client.getItemContainer(InventoryID.TRAIL_REWARDINV);
        if (rewardContainer == null)
        {
            log.warn("Clue reward container is null");
            resetState();
            return;
        }

        // Extract items from the container
        Item[] items = rewardContainer.getItems();
        if (items == null || items.length == 0)
        {
            log.warn("No items found in clue reward container");
            resetState();
            return;
        }

        // Build item list and calculate total value
        JsonArray itemsArray = new JsonArray();
        long totalValue = 0;
        int itemCount = 0;

        for (Item item : items)
        {
            // Skip empty slots
            if (item.getId() == -1 || item.getQuantity() == 0)
            {
                continue;
            }

            ItemComposition comp = itemManager.getItemComposition(item.getId());
            int price = itemManager.getItemPrice(item.getId());
            long itemTotalValue = (long) price * item.getQuantity();

            totalValue += itemTotalValue;
            itemCount++;

            JsonObject itemJson = new JsonObject();
            itemJson.addProperty("item_id", item.getId());
            itemJson.addProperty("name", comp.getName());
            itemJson.addProperty("quantity", item.getQuantity());
            itemJson.addProperty("price", price);
            itemJson.addProperty("total_value", itemTotalValue);
            itemsArray.add(itemJson);
        }

        if (itemCount == 0)
        {
            log.warn("No valid items extracted from clue reward");
            resetState();
            return;
        }

        // Determine tier - use pending if available, otherwise try to detect
        String tier = pendingClueTier != null ? pendingClueTier : "unknown";
        int completionCount = pendingClueCount;

        log.info("Processing {} clue scroll reward: {} items worth {} gp", tier, itemCount, totalValue);

        // Send to API with video capture
        sendClueRewardToApi(tier, completionCount, itemsArray, totalValue);

        // Report to bingo progress (converts JsonArray items to LootItem list)
        reportToBingo(tier, itemsArray, totalValue);

        // Reset state
        resetState();
    }

    /**
     * Send the clue scroll reward to the API with screenshot (no video for clue scrolls).
     */
    private void sendClueRewardToApi(String tier, int completionCount, JsonArray items, long totalValue)
    {
        JsonObject payload = new JsonObject();
        payload.addProperty("tier", tier);
        payload.addProperty("completion_count", completionCount);
        payload.add("items", items);
        payload.addProperty("total_value", totalValue);

        // Capture screenshot only (no video for clue scrolls - the reward screen is static)
        if (config.clueScreenshot())
        {
            videoRecorder.captureScreenshotOnly((screenshotBase64, videoBase64) -> {
                apiClient.sendEventToApi(
                    "/api/webhooks/clue_scroll",
                    payload.toString(),
                    tier + " clue scroll reward",
                    screenshotBase64,
                    null  // No video for clue scrolls
                );
            });
        }
        else
        {
            apiClient.sendEventToApi(
                "/api/webhooks/clue_scroll",
                payload.toString(),
                tier + " clue scroll reward"
            );
        }
    }

    /**
     * Report clue completion to bingo progress tracker.
     */
    private void reportToBingo(String tier, JsonArray items, long totalValue)
    {
        // Convert JsonArray to List<LootItem> for bingo reporter
        List<BingoProgressReporter.LootItem> lootItems = new ArrayList<>();
        for (int i = 0; i < items.size(); i++)
        {
            JsonObject item = items.get(i).getAsJsonObject();
            int itemId = item.get("item_id").getAsInt();
            String name = item.get("name").getAsString();
            int quantity = item.get("quantity").getAsInt();
            long value = item.has("total_value") ? item.get("total_value").getAsLong() : 0;
            lootItems.add(new BingoProgressReporter.LootItem(itemId, name, quantity, value));
        }

        bingoProgressReporter.reportClueComplete(tier, lootItems, totalValue);
    }

    /**
     * Check if a tier string is valid.
     */
    private boolean isValidTier(String tier)
    {
        for (String validTier : CLUE_TIERS)
        {
            if (validTier.equals(tier))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Reset the tracking state.
     */
    private void resetState()
    {
        pendingClueTier = null;
        pendingClueCount = 0;
    }
}
