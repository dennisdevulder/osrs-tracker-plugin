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
package com.osrstracker.itemsnitch;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.osrstracker.OsrsTrackerConfig;
import com.osrstracker.api.ApiClient;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.game.ItemManager;
import okhttp3.*;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks shared clan items in the player's bank and alerts when they have items
 * that should be returned to shared storage.
 *
 * Features:
 * - Fetches list of shared items from API on login
 * - Scans bank when opened for shared items
 * - Shows chat warning when bank closes if shared items are present
 * - Reports item sightings to the API for tracking
 */
@Slf4j
@Singleton
public class ItemSnitchTracker
{
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final Client client;
    private final ClientThread clientThread;
    private final OsrsTrackerConfig config;
    private final ApiClient apiClient;
    private final ItemManager itemManager;
    private final ChatMessageManager chatMessageManager;
    private final OkHttpClient httpClient;
    private final Gson gson;

    // Set of ALL item IDs to track, including variants (for quick lookup during bank scan)
    private final Set<Integer> sharedItemIds = ConcurrentHashMap.newKeySet();

    // Full shared item data by primary ID (for display names, etc.)
    private final Map<Integer, SharedItem> sharedItemsMap = new ConcurrentHashMap<>();

    // Reverse lookup: variant item ID -> SharedItem (for matching degraded items to their parent)
    private final Map<Integer, SharedItem> variantToSharedItemMap = new ConcurrentHashMap<>();

    // Items found in current bank session
    private final Map<Integer, BankItemSighting> currentBankSharedItems = new ConcurrentHashMap<>();

    // Track if bank is currently open
    private volatile boolean bankOpen = false;

    // Track if we've already sent a report this login session
    private volatile boolean reportSentThisSession = false;

    // Track initialization state
    private volatile boolean initialized = false;

    // Prefix used in Item Snitch chat messages (for deduplication)
    private static final String ITEM_SNITCH_PREFIX = "[Item Snitch]";

    // Number of recent chat messages to check for deduplication
    private static final int RECENT_MESSAGE_CHECK_COUNT = 8;

    @Inject
    public ItemSnitchTracker(
        Client client,
        ClientThread clientThread,
        OsrsTrackerConfig config,
        ApiClient apiClient,
        ItemManager itemManager,
        ChatMessageManager chatMessageManager,
        OkHttpClient httpClient,
        Gson gson)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.config = config;
        this.apiClient = apiClient;
        this.itemManager = itemManager;
        this.chatMessageManager = chatMessageManager;
        this.httpClient = httpClient;
        this.gson = gson;
    }

    /**
     * Initialize the tracker by fetching shared items from the API.
     * Called when the player logs in.
     */
    public void initialize()
    {
        if (!config.trackItemSnitch())
        {
            log.debug("Item Snitch tracking disabled");
            return;
        }

        if (!apiClient.isConfigurationValid())
        {
            log.debug("API not configured, skipping Item Snitch initialization");
            return;
        }

        log.debug("Initializing Item Snitch tracker...");
        fetchSharedItemsFromApi();
    }

    /**
     * Reset the tracker state. Called on logout.
     */
    public void reset()
    {
        sharedItemIds.clear();
        sharedItemsMap.clear();
        variantToSharedItemMap.clear();
        currentBankSharedItems.clear();
        bankOpen = false;
        reportSentThisSession = false;
        initialized = false;
    }

    /**
     * Called when the bank widget is loaded (bank opened).
     */
    public void onBankOpen()
    {
        if (!config.trackItemSnitch() || !initialized)
        {
            return;
        }

        bankOpen = true;
        reportSentThisSession = false;
        currentBankSharedItems.clear();
        log.debug("Bank opened, scanning for shared items");

        // Scan bank on next game tick to ensure items are loaded
        clientThread.invokeLater(this::scanBankForSharedItems);
    }

    /**
     * Called when the bank widget is closed.
     */
    public void onBankClose()
    {
        if (!config.trackItemSnitch() || !bankOpen)
        {
            return;
        }

        bankOpen = false;
        log.debug("Bank closed, found {} shared items", currentBankSharedItems.size());

        // Show warning if shared items were found (skip if already visible in recent chat)
        if (!currentBankSharedItems.isEmpty() && config.itemSnitchWarnings() && !isItemSnitchMessageInRecentChat())
        {
            showSharedItemsWarning();
        }

        // Report sightings to API if we haven't already
        if (!currentBankSharedItems.isEmpty() && !reportSentThisSession && config.itemSnitchReportSightings())
        {
            reportItemSightingsToApi();
        }
    }

    /**
     * Called when bank items change (ItemContainerChanged event for bank).
     */
    public void onBankItemsChanged()
    {
        if (!config.trackItemSnitch() || !bankOpen || !initialized)
        {
            return;
        }

        scanBankForSharedItems();
    }

    /**
     * Scan the bank container for shared items.
     * Checks against all variant IDs (degraded Barrows, charged weapons, etc.)
     */
    private void scanBankForSharedItems()
    {
        if (sharedItemIds.isEmpty())
        {
            log.debug("No shared items configured to track");
            return;
        }

        ItemContainer bankContainer = client.getItemContainer(InventoryID.BANK);
        if (bankContainer == null)
        {
            log.debug("Bank container not available");
            return;
        }

        currentBankSharedItems.clear();

        for (Item item : bankContainer.getItems())
        {
            if (item.getId() == -1)
            {
                continue;
            }

            // Check if this item ID (or any variant) matches a shared item
            if (sharedItemIds.contains(item.getId()))
            {
                // Look up the parent SharedItem for this variant
                SharedItem sharedItem = variantToSharedItemMap.get(item.getId());

                ItemComposition itemComp = itemManager.getItemComposition(item.getId());
                String itemName = itemComp != null ? itemComp.getName() : "Unknown";

                // Use the shared item's display name for consistency in reports
                String displayName = (sharedItem != null) ? sharedItem.getItemName() : itemName;

                BankItemSighting sighting = new BankItemSighting(
                    item.getId(),
                    itemName,  // Actual item name (may show degradation state)
                    item.getQuantity()
                );

                // Store with a note about which shared item this matches
                if (sharedItem != null && item.getId() != sharedItem.getItemId())
                {
                    log.debug("Found variant {} of shared item: {} (primary: {})",
                        item.getId(), itemName, sharedItem.getItemName());
                }
                else
                {
                    log.debug("Found shared item: {} x{}", itemName, item.getQuantity());
                }

                currentBankSharedItems.put(item.getId(), sighting);
            }
        }

        log.debug("Bank scan complete: found {} shared items", currentBankSharedItems.size());
    }

    /**
     * Check if an Item Snitch message is already visible in recent chat messages.
     * This prevents spamming the same warning when rapidly opening/closing bank,
     * but allows it to show again once pushed off screen by other messages.
     *
     * @return true if an Item Snitch message is in recent chat
     */
    private boolean isItemSnitchMessageInRecentChat()
    {
        IterableHashTable<MessageNode> messages = client.getMessages();
        if (messages == null)
        {
            return false;
        }

        // Collect all messages into a list so we can check from the end (most recent)
        List<MessageNode> messageList = new ArrayList<>();
        for (MessageNode messageNode : messages)
        {
            messageList.add(messageNode);
        }

        // Check the most recent messages
        int startIndex = Math.max(0, messageList.size() - RECENT_MESSAGE_CHECK_COUNT);
        for (int i = messageList.size() - 1; i >= startIndex; i--)
        {
            String msg = messageList.get(i).getValue();
            if (msg != null && msg.contains(ITEM_SNITCH_PREFIX))
            {
                log.debug("Item Snitch message found in last {} messages, skipping", RECENT_MESSAGE_CHECK_COUNT);
                return true;
            }
        }

        return false;
    }

    /**
     * Show a chat warning about shared items in the bank.
     * Uses verbosity setting to determine message detail level.
     */
    private void showSharedItemsWarning()
    {
        if (currentBankSharedItems.isEmpty())
        {
            return;
        }

        int itemCount = currentBankSharedItems.size();
        String coloredMessage;

        if (config.itemSnitchVerbosity() == ItemSnitchVerbosity.MINIMAL)
        {
            // Minimal: just show the count
            coloredMessage = "<col=aa00ff>[Item Snitch] " +
                "You have " + itemCount + " shared item" + (itemCount != 1 ? "s" : "") +
                " in your bank.</col>";
        }
        else
        {
            // Verbose: show the full list
            List<String> itemNames = new ArrayList<>();
            for (BankItemSighting sighting : currentBankSharedItems.values())
            {
                if (sighting.getQuantity() > 1)
                {
                    itemNames.add(sighting.getQuantity() + "x " + sighting.getName());
                }
                else
                {
                    itemNames.add(sighting.getName());
                }
            }

            String itemList = String.join(", ", itemNames);
            coloredMessage = "<col=aa00ff>[Item Snitch] " +
                "You have the following shared items in your bank: " + itemList + "</col>";
        }

        chatMessageManager.queue(QueuedMessage.builder()
            .type(ChatMessageType.GAMEMESSAGE)
            .runeLiteFormattedMessage(coloredMessage)
            .build());

        log.debug("Warned player about {} shared items in bank", currentBankSharedItems.size());
    }

    /**
     * Check if an item ID should be shown when Item Snitch filter is active.
     * Used by ScriptCallbackEvent handler for bank filtering.
     *
     * @param itemId The item ID to check
     * @return true if the item should be shown (is a shared item), false otherwise
     */
    public boolean shouldShowItemInFilter(int itemId)
    {
        return sharedItemIds.contains(itemId);
    }

    /**
     * Update last_seen stats on the API for shared items found in bank.
     * This only updates last_seen_by and last_seen_at - no events are created.
     */
    private void reportItemSightingsToApi()
    {
        if (currentBankSharedItems.isEmpty() || reportSentThisSession)
        {
            return;
        }

        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null || localPlayer.getName() == null)
        {
            log.warn("Cannot update last_seen: player name not available");
            return;
        }

        String playerName = localPlayer.getName();

        // Build JSON payload
        JsonObject payload = new JsonObject();
        payload.addProperty("player_name", playerName);

        JsonArray itemsArray = new JsonArray();
        for (BankItemSighting sighting : currentBankSharedItems.values())
        {
            JsonObject itemJson = new JsonObject();
            itemJson.addProperty("item_id", sighting.getItemId());
            itemJson.addProperty("name", sighting.getName());
            itemJson.addProperty("quantity", sighting.getQuantity());
            itemsArray.add(itemJson);
        }
        payload.add("items", itemsArray);

        // Send to API - this only updates last_seen fields, no events created
        apiClient.sendEventToApi(
            "/api/webhooks/item_sighting",
            payload.toString(),
            "last seen update"
        );

        reportSentThisSession = true;
        log.debug("Updated last_seen for {} shared items", currentBankSharedItems.size());
    }

    /**
     * Fetch the list of shared items from the API.
     */
    private void fetchSharedItemsFromApi()
    {
        String apiUrl = OsrsTrackerConfig.getEffectiveApiUrl();
        String apiToken = config.apiToken();

        if (apiToken == null || apiToken.isEmpty())
        {
            log.debug("API token not configured, cannot fetch shared items");
            return;
        }

        Request request = new Request.Builder()
            .url(apiUrl + "/api/webhooks/shared_items")
            .addHeader("Authorization", "Bearer " + apiToken)
            .get()
            .build();

        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.error("Failed to fetch shared items from API: {}", e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                try
                {
                    if (!response.isSuccessful())
                    {
                        log.warn("Failed to fetch shared items: {} - {}", response.code(), response.message());
                        return;
                    }

                    String responseBody = response.body() != null ? response.body().string() : "";
                    parseSharedItemsResponse(responseBody);
                }
                catch (Exception e)
                {
                    log.error("Error parsing shared items response", e);
                }
                finally
                {
                    response.close();
                }
            }
        });
    }

    /**
     * Parse the shared items API response and populate the tracking sets.
     * Only processes items from ironman groups (group_ironman or hardcore_group_ironman).
     */
    private void parseSharedItemsResponse(String responseBody)
    {
        try
        {
            JsonObject json = gson.fromJson(responseBody, JsonObject.class);

            // Check if user has any ironman groups
            boolean hasIronmanGroup = json.has("has_ironman_group") && json.get("has_ironman_group").getAsBoolean();
            if (!hasIronmanGroup)
            {
                log.debug("Item Snitch disabled: No Group Ironman or Hardcore Group Ironman groups found");
                initialized = false;
                return;
            }

            if (!json.has("shared_items"))
            {
                log.debug("No shared_items in API response");
                return;
            }

            JsonArray itemsArray = json.getAsJsonArray("shared_items");

            sharedItemIds.clear();
            sharedItemsMap.clear();
            variantToSharedItemMap.clear();

            int totalVariants = 0;

            for (int i = 0; i < itemsArray.size(); i++)
            {
                JsonObject itemJson = itemsArray.get(i).getAsJsonObject();

                SharedItem sharedItem = new SharedItem();
                sharedItem.setItemId(itemJson.get("item_id").getAsInt());
                sharedItem.setItemName(itemJson.get("item_name").getAsString());
                sharedItem.setRequiredQuantity(itemJson.has("required_quantity")
                    ? itemJson.get("required_quantity").getAsInt() : 1);

                if (itemJson.has("group_name"))
                {
                    sharedItem.setGroupName(itemJson.get("group_name").getAsString());
                }
                if (itemJson.has("group_id"))
                {
                    sharedItem.setGroupId(itemJson.get("group_id").getAsInt());
                }
                if (itemJson.has("last_seen_by") && !itemJson.get("last_seen_by").isJsonNull())
                {
                    sharedItem.setLastSeenBy(itemJson.get("last_seen_by").getAsString());
                }
                if (itemJson.has("last_seen_at") && !itemJson.get("last_seen_at").isJsonNull())
                {
                    sharedItem.setLastSeenAt(itemJson.get("last_seen_at").getAsString());
                }

                // Parse variant_ids for degradeable items (Barrows, charged weapons, etc.)
                if (itemJson.has("variant_ids") && !itemJson.get("variant_ids").isJsonNull())
                {
                    JsonArray variantArray = itemJson.getAsJsonArray("variant_ids");
                    for (int j = 0; j < variantArray.size(); j++)
                    {
                        int variantId = variantArray.get(j).getAsInt();
                        sharedItem.addVariantId(variantId);
                        sharedItemIds.add(variantId);
                        variantToSharedItemMap.put(variantId, sharedItem);
                        totalVariants++;
                    }
                }
                else
                {
                    // No variants - just track the primary ID
                    sharedItem.addVariantId(sharedItem.getItemId());
                    sharedItemIds.add(sharedItem.getItemId());
                    variantToSharedItemMap.put(sharedItem.getItemId(), sharedItem);
                    totalVariants++;
                }

                sharedItemsMap.put(sharedItem.getItemId(), sharedItem);
            }

            log.debug("Loaded {} shared items ({} total IDs including variants) to track from API",
                sharedItemsMap.size(), totalVariants);

            // Mark as initialized AFTER we have the data
            initialized = true;

            // If bank is already open, trigger a scan and button refresh
            if (bankOpen)
            {
                log.debug("Bank already open, triggering scan after initialization");
                clientThread.invokeLater(() -> {
                    scanBankForSharedItems();
                    // Notify any listeners that initialization is complete
                    onInitializationComplete();
                });
            }
        }
        catch (Exception e)
        {
            log.error("Failed to parse shared items response", e);
        }
    }

    // Callback for when shared items are loaded - can be used to refresh UI
    private Runnable onInitializedCallback;

    /**
     * Set a callback to be invoked when shared items are loaded from API.
     */
    public void setOnInitializedCallback(Runnable callback)
    {
        this.onInitializedCallback = callback;
    }

    private void onInitializationComplete()
    {
        if (onInitializedCallback != null)
        {
            onInitializedCallback.run();
        }
    }

    /**
     * Get the set of shared item IDs for filtering.
     */
    public Set<Integer> getSharedItemIds()
    {
        return Collections.unmodifiableSet(sharedItemIds);
    }

    /**
     * Get the map of shared items by ID.
     */
    public Map<Integer, SharedItem> getSharedItemsMap()
    {
        return Collections.unmodifiableMap(sharedItemsMap);
    }

    /**
     * Get items currently found in the bank that are shared items.
     */
    public Map<Integer, BankItemSighting> getCurrentBankSharedItems()
    {
        return Collections.unmodifiableMap(currentBankSharedItems);
    }

    /**
     * Check if the bank is currently open.
     */
    public boolean isBankOpen()
    {
        return bankOpen;
    }

    /**
     * Check if the tracker is initialized.
     */
    public boolean isInitialized()
    {
        return initialized;
    }
}
