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
package com.osrstracker;

import com.google.gson.JsonObject;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import com.osrstracker.api.ApiClient;
import com.osrstracker.skills.SkillLevelTracker;
import com.osrstracker.quest.QuestTracker;
import com.osrstracker.loot.LootTracker;
import com.osrstracker.collectionlog.CollectionLogTracker;
import com.osrstracker.death.DeathTracker;
import com.osrstracker.clue.ClueScrollTracker;
import com.osrstracker.itemsnitch.ItemSnitchTracker;
import com.osrstracker.itemsnitch.ItemSnitchBankOverlay;
import com.osrstracker.itemsnitch.ItemSnitchButton;
import com.osrstracker.video.VideoRecorder;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.image.BufferedImage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OSRS Tracker plugin - Automatically tracks gameplay events and sends them to your OSRS Tracker.
 *
 * This plugin uses a modular architecture where each type of tracking (skills, quests, loot, etc.)
 * is handled by a dedicated tracker class. The main plugin coordinates these trackers and manages
 * the video recording system.
 *
 * Features:
 * - Level-ups with 10-second video replays
 * - Quest completions with video
 * - Loot drops with video (configurable minimum value)
 * - Clue scroll rewards with video and item details
 * - Collection log updates with video
 * - Death events with video replays
 * - RSN verification to prevent alt account data syncing
 */
@Slf4j
@PluginDescriptor(
    name = "OSRS Tracker",
    description = "Automatically sends level-ups, quest completions, loot drops, clue scrolls, and deaths to your OSRS Tracker",
    tags = {"tracker", "levels", "quests", "loot", "collection log", "deaths", "clue", "treasure trails"}
)
public class OsrsTrackerPlugin extends Plugin
{
    @Inject
    private Client client;

    @Inject
    private OsrsTrackerConfig config;

    @Inject
    private ClientThread clientThread;

    // Modular trackers
    @Inject
    private ApiClient apiClient;

    @Inject
    private SkillLevelTracker skillLevelTracker;

    @Inject
    private QuestTracker questTracker;

    @Inject
    private LootTracker lootTracker;

    @Inject
    private CollectionLogTracker collectionLogTracker;

    @Inject
    private DeathTracker deathTracker;

    @Inject
    private ClueScrollTracker clueScrollTracker;

    @Inject
    private ItemSnitchTracker itemSnitchTracker;

    @Inject
    private ItemSnitchBankOverlay itemSnitchBankOverlay;

    @Inject
    private ItemSnitchButton itemSnitchButton;

    @Inject
    private VideoRecorder videoRecorder;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private EventBus eventBus;

    @Inject
    private ConfigManager configManager;

    // Sidebar navigation button and panel for quick capture
    private NavigationButton quickCaptureButton;
    private OsrsTrackerPanel panel;

    // Track if a quick capture is in progress to prevent spam (thread-safe)
    private final AtomicBoolean quickCaptureInProgress = new AtomicBoolean(false);

    // Cooldown timer to prevent rapid captures (5 second cooldown after completion)
    private static final int COOLDOWN_SECONDS = 5;
    private final AtomicLong lastCaptureCompletedTime = new AtomicLong(0);
    private ScheduledExecutorService cooldownExecutor;

    @Override
    protected void startUp() throws Exception
    {
        log.info("OSRS Tracker started!");

        // Register clue scroll tracker with event bus
        eventBus.register(clueScrollTracker);

        // Start video recording
        videoRecorder.startRecording();

        // Initialize trackers when already logged in
        if (client.getGameState() == GameState.LOGGED_IN)
        {
            skillLevelTracker.initializeSkillLevels();
            questTracker.initializeQuestTracking();
            itemSnitchTracker.initialize();
        }

        // Create the sidebar panel with quick capture button
        panel = new OsrsTrackerPanel(this::triggerQuickCapture);

        // Load icon for sidebar
        BufferedImage icon = ImageUtil.loadImageResource(getClass(), "quick_capture_icon.png");

        if (icon == null)
        {
            log.warn("Could not load quick_capture_icon.png, creating fallback icon");
            // Create a simple fallback icon (16x16 blue square with camera shape)
            icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = icon.createGraphics();
            g.setColor(new java.awt.Color(93, 173, 226)); // Light blue
            g.fillRect(2, 4, 12, 8);
            g.setColor(new java.awt.Color(26, 82, 118)); // Dark blue
            g.fillOval(5, 5, 6, 6);
            g.setColor(java.awt.Color.WHITE);
            g.fillOval(7, 7, 2, 2);
            g.dispose();
        }
        else
        {
            log.debug("Successfully loaded quick_capture_icon.png");
        }

        // Create navigation button with panel (required for sidebar placement)
        quickCaptureButton = NavigationButton.builder()
            .tooltip("OSRS Tracker - Quick Capture")
            .icon(icon)
            .priority(10)
            .panel(panel)
            .build();

        clientToolbar.addNavigation(quickCaptureButton);
        log.info("Quick capture button added to sidebar");

        // Register Item Snitch bank overlay and load sprites
        overlayManager.add(itemSnitchBankOverlay);
        itemSnitchButton.loadSprites();
    }

    @Override
    protected void shutDown() throws Exception
    {
        log.info("OSRS Tracker stopped!");

        // Unregister clue scroll tracker from event bus
        eventBus.unregister(clueScrollTracker);

        // Remove Item Snitch bank overlay
        overlayManager.remove(itemSnitchBankOverlay);

        // Shutdown cooldown executor
        if (cooldownExecutor != null && !cooldownExecutor.isShutdown())
        {
            cooldownExecutor.shutdownNow();
        }

        // Remove sidebar button
        if (quickCaptureButton != null)
        {
            clientToolbar.removeNavigation(quickCaptureButton);
        }

        // Stop video recording
        videoRecorder.stopRecording();

        // Reset all trackers
        skillLevelTracker.resetSkillTracking();
        questTracker.resetQuestTracking();
        itemSnitchTracker.reset();
    }

    /**
     * Triggers a quick capture when the sidebar button is clicked.
     * Captures 8 seconds of buffered video + 2 seconds after the click.
     * Includes cooldown protection to prevent spam.
     */
    private void triggerQuickCapture()
    {
        // Prevent spam clicking - atomically check and set capture in progress
        if (!quickCaptureInProgress.compareAndSet(false, true))
        {
            log.debug("Quick capture already in progress, ignoring");
            return;
        }

        // Check cooldown
        long now = System.currentTimeMillis();
        long timeSinceLastCapture = now - lastCaptureCompletedTime.get();
        int remainingCooldown = (int) ((COOLDOWN_SECONDS * 1000 - timeSinceLastCapture) / 1000);

        if (remainingCooldown > 0)
        {
            log.debug("Quick capture on cooldown, {} seconds remaining", remainingCooldown);
            panel.setCooldownState(remainingCooldown);
            quickCaptureInProgress.set(false); // Reset since we're not actually capturing
            return;
        }

        if (!apiClient.isConfigurationValid())
        {
            log.error("Cannot quick capture: API URL or token not configured");
            panel.setErrorState("Configure API first!");
            clientThread.invokeLater(() ->
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "OSRS Tracker: Please configure API URL and token first!", null)
            );
            quickCaptureInProgress.set(false); // Reset since we're not actually capturing
            return;
        }

        // quickCaptureInProgress is already set to true by compareAndSet above

        // Update UI to recording state
        panel.setRecordingState();

        log.info("Quick capture triggered!");
        clientThread.invokeLater(() ->
            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "OSRS Tracker: Quick capture started! Recording 2 more seconds...", null)
        );

        // Capture video (8s buffer + 2s post-click)
        // Use the overloaded method with encoding callback to update UI when encoding starts
        videoRecorder.captureEventVideo(
            // Completion callback - called when encoding is done
            (screenshotBase64, videoBase64) -> {
                // Update UI to uploading state (purple)
                panel.setUploadingState();

                JsonObject json = new JsonObject();
                json.addProperty("event_type", "quick_capture");

                // Get player RSN if available
                Player localPlayer = client.getLocalPlayer();
                if (localPlayer != null && localPlayer.getName() != null)
                {
                    json.addProperty("rsn", localPlayer.getName());
                }

                // Send to quick capture endpoint
                apiClient.sendEventToApi("/api/webhooks/quick_capture", json.toString(), "quick capture", screenshotBase64, videoBase64);

                // Show feedback in game and update panel
                clientThread.invokeLater(() -> {
                    if (screenshotBase64 != null && videoBase64 != null)
                    {
                        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                            "OSRS Tracker: Quick capture sent! Check your newsfeed.", null);
                        panel.setSuccessState();
                    }
                    else if (screenshotBase64 != null)
                    {
                        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                            "OSRS Tracker: Screenshot captured! Check your newsfeed.", null);
                        panel.setSuccessState();
                    }
                    else
                    {
                        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                            "OSRS Tracker: Capture failed. Please try again.", null);
                        panel.setErrorState("Capture failed!");
                    }
                });

                // Mark capture as complete and start cooldown
                quickCaptureInProgress.set(false);
                lastCaptureCompletedTime.set(System.currentTimeMillis());

                // Start cooldown countdown in UI
                startCooldownTimer();
            },
            // Encoding start callback - called when recording stops and encoding begins
            () -> panel.setEncodingState()
        );
    }

    /**
     * Starts the cooldown timer that updates the panel UI.
     */
    private void startCooldownTimer()
    {
        if (cooldownExecutor != null && !cooldownExecutor.isShutdown())
        {
            cooldownExecutor.shutdownNow();
        }

        cooldownExecutor = Executors.newSingleThreadScheduledExecutor();

        // Update countdown every second
        cooldownExecutor.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            long timeSinceCapture = now - lastCaptureCompletedTime.get();
            int remainingSeconds = (int) Math.ceil((COOLDOWN_SECONDS * 1000 - timeSinceCapture) / 1000.0);

            if (remainingSeconds <= 0)
            {
                panel.setReadyState();
                cooldownExecutor.shutdown();
            }
            else
            {
                panel.setCooldownState(remainingSeconds);
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Handle login events - initialize all trackers.
     */
    @Subscribe
    public void onGameStateChanged(GameStateChanged gameStateChanged)
    {
        if (gameStateChanged.getGameState() == GameState.LOGGED_IN)
        {
            skillLevelTracker.initializeSkillLevels();
            questTracker.initializeQuestTracking();
            itemSnitchTracker.initialize();
        }
        else if (gameStateChanged.getGameState() == GameState.LOGIN_SCREEN ||
                 gameStateChanged.getGameState() == GameState.HOPPING)
        {
            // Reset trackers on logout
            questTracker.resetQuestTracking();
            itemSnitchTracker.reset();
        }
    }

    /**
     * Handle stat changes - delegate to skill level tracker.
     */
    @Subscribe
    public void onStatChanged(StatChanged statChanged)
    {
        if (!config.trackLevelUps())
        {
            return;
        }

        skillLevelTracker.checkForLevelUp(statChanged.getSkill());
    }

    /**
     * Handle chat messages - delegate to collection log tracker.
     */
    @Subscribe
    public void onChatMessage(ChatMessage chatMessage)
    {
        if (chatMessage.getType() != ChatMessageType.GAMEMESSAGE)
        {
            return;
        }

        String message = Text.removeTags(chatMessage.getMessage());

        // Check for collection log updates
        if (config.trackCollectionLog())
        {
            collectionLogTracker.processGameMessage(message);
        }
    }

    /**
     * Handle varbit changes - delegate to quest tracker for quest point tracking.
     */
    @Subscribe
    public void onVarbitChanged(VarbitChanged varbitChanged)
    {
        if (!config.trackQuests())
        {
            return;
        }

        questTracker.checkForQuestCompletion();
    }

    /**
     * Handle loot drops - delegate to loot tracker.
     */
    @Subscribe
    public void onServerNpcLoot(ServerNpcLoot event)
    {
        if (!config.trackLoot())
        {
            return;
        }

        NPCComposition npc = event.getComposition();
        String npcName = (npc != null) ? npc.getName() : null;
        lootTracker.processLootDrop(npcName, event.getItems());
    }

    /**
     * Handle actor deaths - delegate to death tracker.
     */
    @Subscribe
    public void onActorDeath(ActorDeath actorDeath)
    {
        if (!config.trackDeaths())
        {
            return;
        }

        deathTracker.processActorDeath(actorDeath.getActor());
    }

    /**
     * Handle widget loaded events - for bank open detection.
     */
    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event)
    {
        // Bank interface group ID is 12
        if (event.getGroupId() == 12)
        {
            itemSnitchTracker.onBankOpen();
            itemSnitchButton.onBankOpen();
        }
    }

    /**
     * Handle script post fired events - for bank finished building detection.
     * This is when we create the Item Snitch button, after the bank UI is fully built.
     */
    @Subscribe
    public void onScriptPostFired(ScriptPostFired event)
    {
        // ScriptID.BANKMAIN_FINISHBUILDING = 505
        if (event.getScriptId() == ScriptID.BANKMAIN_FINISHBUILDING)
        {
            itemSnitchButton.onBankFinishedBuilding();
        }
    }

    /**
     * Handle widget closed events - for bank close detection.
     */
    @Subscribe
    public void onWidgetClosed(WidgetClosed event)
    {
        // Bank interface group ID is 12
        if (event.getGroupId() == 12)
        {
            itemSnitchTracker.onBankClose();
            itemSnitchButton.onBankClose();
        }
    }

    /**
     * Handle item container changes - for bank item scanning.
     */
    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        // Bank container ID
        if (event.getContainerId() == InventoryID.BANK.getId())
        {
            itemSnitchTracker.onBankItemsChanged();
            itemSnitchButton.refresh();
        }
    }

    /**
     * Handle script callback events - for bank item filtering.
     */
    @Subscribe
    public void onScriptCallbackEvent(ScriptCallbackEvent event)
    {
        // Handle bank search filter for Item Snitch
        if ("bankSearchFilter".equals(event.getEventName()) && itemSnitchButton.isFilterActive())
        {
            int[] intStack = client.getIntStack();
            int intStackSize = client.getIntStackSize();

            int itemId = intStack[intStackSize - 1];

            // If filter is active and item is a shared item, show it
            // Otherwise hide it (set result to 0)
            if (itemSnitchTracker.shouldShowItemInFilter(itemId))
            {
                intStack[intStackSize - 2] = 1; // Show item
            }
            else
            {
                intStack[intStackSize - 2] = 0; // Hide item
            }
        }
    }

    /**
     * Handle game ticks - check for quality setting changes.
     */
    @Subscribe
    public void onGameTick(GameTick gameTick)
    {
        // Update video capture rate if quality setting changed
        videoRecorder.updateCaptureRateIfNeeded();

        // Handle delayed quest sync
        questTracker.onGameTick();
    }

    /**
     * Handle config changes - validate minimum loot value.
     */
    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (!"osrstracker".equals(event.getGroup()))
        {
            return;
        }

        // Validate minimum loot value - enforce 100k minimum
        if ("minimumLootValue".equals(event.getKey()))
        {
            String newValue = event.getNewValue();
            int parsedValue = OsrsTrackerConfig.parseGpValue(newValue);

            if (parsedValue < OsrsTrackerConfig.MINIMUM_LOOT_VALUE)
            {
                // Value is below minimum, reset to 100k
                log.info("Minimum loot value {} ({} GP) is below 100k minimum, resetting to 100k", newValue, parsedValue);
                configManager.setConfiguration("osrstracker", "minimumLootValue", "100k");

                // Notify user
                clientThread.invokeLater(() ->
                    client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                        "OSRS Tracker: Minimum loot value must be at least 100k GP", null)
                );
            }
        }
    }

    @Provides
    OsrsTrackerConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(OsrsTrackerConfig.class);
    }
}
