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
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
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
import com.osrstracker.bingo.BingoSubscriptionManager;
import com.osrstracker.bingo.BingoProgressReporter;
import com.osrstracker.pets.PetTracker;
import com.osrstracker.video.VideoRecorder;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
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
    // Gauntlet boss NPC IDs (multiple forms/states for each)
    private static final int[] CRYSTALLINE_HUNLLEF_IDS = {9021, 9022, 9023, 9024};
    private static final int[] CORRUPTED_HUNLLEF_IDS = {9035, 9036, 9037, 9038};

    // Raid boss NPC IDs (final boss of each raid)
    // CoX - Great Olm head (both phase variants)
    private static final int[] GREAT_OLM_HEAD_IDS = {7551, 7554};
    // ToB - Verzik Vitur phase 3 (both variants)
    private static final int[] VERZIK_VITUR_P3_IDS = {8374, 8375};
    // ToA - Tumeken's Warden (damaged/enraged states)
    private static final int[] TUMEKENS_WARDEN_IDS = {11762, 11764};
    // ToA - Elidinis' Warden (damaged/enraged states)
    private static final int[] ELIDINIS_WARDEN_IDS = {11761, 11763};

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
    private BingoSubscriptionManager bingoSubscriptionManager;

    @Inject
    private BingoProgressReporter bingoProgressReporter;

    @Inject
    private PetTracker petTracker;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private EventBus eventBus;

    @Inject
    private ConfigManager configManager;

    @Inject
    private ItemManager itemManager;

    // Sidebar navigation button and panel for quick capture
    private NavigationButton quickCaptureButton;
    private OsrsTrackerPanel panel;

    // Track if a quick capture is in progress to prevent spam (thread-safe)
    private final AtomicBoolean quickCaptureInProgress = new AtomicBoolean(false);

    // Cooldown timer to prevent rapid captures (5 second cooldown after completion)
    private static final int COOLDOWN_SECONDS = 5;
    private final AtomicLong lastCaptureCompletedTime = new AtomicLong(0);
    private ScheduledExecutorService cooldownExecutor;

    // Track if we're in an active login session to prevent repeated re-initialization
    // GameState.LOGGED_IN fires frequently during gameplay (region loads, interface closes, etc.)
    // We only want to initialize trackers once per actual login, not on every state change
    private volatile boolean sessionActive = false;

    @Override
    protected void startUp() throws Exception
    {
        log.info("OSRS Tracker started!");

        // Register clue scroll tracker with event bus
        eventBus.register(clueScrollTracker);

        // Start video recording
        videoRecorder.startRecording();

        // Initialize trackers when already logged in (plugin enabled mid-session)
        if (client.getGameState() == GameState.LOGGED_IN)
        {
            log.debug("Plugin started while logged in - initializing trackers");
            sessionActive = true;
            skillLevelTracker.initializeSkillLevels();
            questTracker.initializeQuestTracking();
            itemSnitchTracker.initialize();
            bingoSubscriptionManager.initialize();
        }

        // Create the sidebar panel with quick capture button and bingo manager
        panel = new OsrsTrackerPanel(this::triggerQuickCapture, bingoSubscriptionManager);

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

        // Reset session state and all trackers
        sessionActive = false;
        skillLevelTracker.resetSkillTracking();
        questTracker.resetQuestTracking();
        itemSnitchTracker.reset();
        bingoSubscriptionManager.reset();
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
     *
     * IMPORTANT: GameState.LOGGED_IN fires frequently during gameplay (region loads,
     * interface closes, cutscenes, etc.), not just on actual login. We use sessionActive
     * to ensure we only initialize once per actual login session.
     */
    @Subscribe
    public void onGameStateChanged(GameStateChanged gameStateChanged)
    {
        GameState state = gameStateChanged.getGameState();

        if (state == GameState.LOGGED_IN)
        {
            // Only initialize if this is the start of a new session
            if (!sessionActive)
            {
                log.info("New login session detected - initializing trackers");
                sessionActive = true;
                skillLevelTracker.initializeSkillLevels();
                questTracker.initializeQuestTracking();
                itemSnitchTracker.initialize();
                bingoSubscriptionManager.initialize();
                panel.updateBingoSection();
            }
            // else: already in an active session, skip re-initialization
        }
        else if (state == GameState.LOGIN_SCREEN)
        {
            // Full logout - reset session and all trackers
            if (sessionActive)
            {
                log.info("Logout detected - resetting session and trackers");
                sessionActive = false;
                skillLevelTracker.resetSkillTracking();
                questTracker.resetQuestTracking();
                itemSnitchTracker.reset();
                bingoSubscriptionManager.reset();
            }
        }
        else if (state == GameState.HOPPING)
        {
            // World hop - reset session so we re-initialize after hop completes
            // This ensures we capture fresh skill levels on the new world
            if (sessionActive)
            {
                log.debug("World hop detected - will re-initialize after hop");
                sessionActive = false;
            }
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

        Skill skill = statChanged.getSkill();
        int level = statChanged.getLevel();
        int xp = statChanged.getXp();
        log.debug("StatChanged: {} level={} xp={}", skill.getName(), level, xp);

        skillLevelTracker.checkForLevelUp(skill);
    }

    /**
     * Handle chat messages - delegate to collection log, clue scroll, and pet trackers.
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

        // Check for clue scroll completion messages
        if (config.trackClueScrolls())
        {
            clueScrollTracker.processGameMessage(message);
        }

        // Check for pet drops
        if (config.trackPets())
        {
            petTracker.processGameMessage(message);
        }

        // Check for slayer task completion
        checkForSlayerTaskCompletion(message);

        // Check for raid completion (fallback if NPC death detection fails)
        checkForRaidCompletion(message);
    }

    /**
     * Pattern to match raid completion messages.
     * CoX/ToB/ToA all use: "Congratulations - your raid is complete!"
     * Also matches ToA specific: "You have completed the Tombs of Amascut"
     */
    private static final java.util.regex.Pattern RAID_COMPLETE_PATTERN =
        java.util.regex.Pattern.compile("Congratulations - your raid is complete!");
    private static final java.util.regex.Pattern TOA_COMPLETE_PATTERN =
        java.util.regex.Pattern.compile("You have completed the Tombs of Amascut");

    // Track recent raid completions to avoid duplicate reports (NPC death + chat message)
    // Using AtomicLong for thread-safe access from game thread and event handlers
    private final AtomicLong lastRaidCompletionTime = new AtomicLong(0);
    private static final long RAID_COMPLETION_COOLDOWN_MS = 5000; // 5 second cooldown

    /**
     * Check if the message indicates a raid completion.
     * This is a fallback for when NPC death detection doesn't work (e.g., Olm might be an Object).
     */
    private void checkForRaidCompletion(String message)
    {
        // Check cooldown to avoid duplicate reports
        long now = System.currentTimeMillis();
        if (now - lastRaidCompletionTime.get() < RAID_COMPLETION_COOLDOWN_MS)
        {
            return;
        }

        if (RAID_COMPLETE_PATTERN.matcher(message).find())
        {
            // Determine which raid based on region/context
            // For now, we'll detect the raid type based on common patterns
            // The NPC death detection should usually catch this first
            String raidName = detectCurrentRaid();
            if (raidName != null)
            {
                log.info("Raid completion detected via chat message: {}", raidName);
                lastRaidCompletionTime.set(now);
                bingoProgressReporter.reportRaidComplete(raidName, true, 0, 0);
            }
        }
        else if (TOA_COMPLETE_PATTERN.matcher(message).find())
        {
            log.info("ToA completion detected via chat message");
            lastRaidCompletionTime.set(now);
            bingoProgressReporter.reportRaidComplete("Tombs of Amascut", true, 0, 0);
        }
    }

    /**
     * Try to detect which raid the player is currently in based on region ID.
     */
    private String detectCurrentRaid()
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return null;
        }

        int regionId = client.getLocalPlayer() != null ?
            client.getLocalPlayer().getWorldLocation().getRegionID() : 0;

        // CoX regions (Chambers of Xeric)
        if (isInCoxRegion(regionId))
        {
            return "Chambers of Xeric";
        }
        // ToB regions (Theatre of Blood)
        else if (isInTobRegion(regionId))
        {
            return "Theatre of Blood";
        }
        // ToA regions (Tombs of Amascut)
        else if (isInToaRegion(regionId))
        {
            return "Tombs of Amascut";
        }

        return null;
    }

    // CoX region IDs (approximate - Olm room and surrounding areas)
    private boolean isInCoxRegion(int regionId)
    {
        return regionId >= 12889 && regionId <= 13210;
    }

    // ToB region IDs (Theatre of Blood)
    private boolean isInTobRegion(int regionId)
    {
        return regionId >= 12611 && regionId <= 12869;
    }

    // ToA region IDs (Tombs of Amascut)
    private boolean isInToaRegion(int regionId)
    {
        return regionId >= 14160 && regionId <= 15696;
    }

    /**
     * Pattern to match slayer task completion messages.
     * Examples:
     * - "You have completed your task! You killed 150 Abyssal demons."
     * - "You've completed your task! Contact a Slayer master for a new assignment."
     */
    private static final java.util.regex.Pattern SLAYER_TASK_PATTERN =
        java.util.regex.Pattern.compile("You have completed your task! You killed (\\d+) (.+)\\.");

    /**
     * Check if the message indicates a slayer task completion.
     */
    private void checkForSlayerTaskCompletion(String message)
    {
        java.util.regex.Matcher matcher = SLAYER_TASK_PATTERN.matcher(message);
        if (matcher.find())
        {
            int amount = Integer.parseInt(matcher.group(1));
            String taskName = matcher.group(2);
            log.info("Slayer task completed: {} x{}", taskName, amount);
            bingoProgressReporter.reportSlayerTaskComplete(taskName, amount, "Unknown");
        }
        // Also check for the simpler message without kill count
        else if (message.contains("You've completed your task"))
        {
            log.info("Slayer task completed (no details)");
            bingoProgressReporter.reportSlayerTaskComplete("Unknown", 0, "Unknown");
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
     * Handle loot drops - delegate to loot tracker and bingo reporter.
     * This event only fires when YOU receive loot, so it's the reliable signal
     * that you killed the NPC (or got MVP for group content).
     */
    @Subscribe
    public void onServerNpcLoot(ServerNpcLoot event)
    {
        NPCComposition npc = event.getComposition();
        String npcName = (npc != null) ? npc.getName() : "Unknown";
        int npcId = (npc != null) ? npc.getId() : -1;

        // Process for timeline loot tracker (with value threshold)
        if (config.trackLoot())
        {
            lootTracker.processLootDrop(npcName, event.getItems());
        }

        // Report NPC/boss kill to bingo (only counts YOUR kills since loot = you killed it)
        bingoProgressReporter.reportNpcKill(npcId, npcName);
        bingoProgressReporter.reportBossKill(npcId, npcName);

        // Report loot items to bingo (handles loot_item, loot_category, loot_value tiles)
        reportLootToBingo(npcId, npcName, event.getItems());
    }

    /**
     * Converts ItemStack collection to BingoProgressReporter.LootItem list and reports to bingo.
     */
    private void reportLootToBingo(int npcId, String npcName, java.util.Collection<ItemStack> items)
    {
        if (items == null || items.isEmpty())
        {
            return;
        }

        List<BingoProgressReporter.LootItem> lootItems = new ArrayList<>();
        long totalValue = 0;

        for (ItemStack item : items)
        {
            int itemId = item.getId();
            int quantity = item.getQuantity();
            net.runelite.api.ItemComposition composition = itemManager.getItemComposition(itemId);
            String itemName = (composition != null) ? composition.getName() : "Unknown";
            long itemPrice = (long) itemManager.getItemPrice(itemId) * quantity;

            lootItems.add(new BingoProgressReporter.LootItem(itemId, itemName, quantity, itemPrice));
            totalValue += itemPrice;
        }

        bingoProgressReporter.reportLoot(npcId, npcName, lootItems, totalValue);
    }

    /**
     * Handle actor deaths - delegate to death tracker and bingo reporter.
     */
    @Subscribe
    public void onActorDeath(ActorDeath actorDeath)
    {
        Actor actor = actorDeath.getActor();

        // Track player deaths for timeline
        if (config.trackDeaths())
        {
            deathTracker.processActorDeath(actor);
        }

        // Track NPC deaths for bingo (boss kills, NPC kills, raids, gauntlet)
        if (actor instanceof NPC)
        {
            NPC npc = (NPC) actor;
            int npcId = npc.getId();
            String npcName = npc.getName();

            // Check for Gauntlet completion (Hunllef death)
            if (isNpcIdInArray(npcId, CRYSTALLINE_HUNLLEF_IDS))
            {
                log.info("Crystalline Hunllef defeated - Normal Gauntlet complete!");
                bingoProgressReporter.reportGauntletComplete(false, true, 0, 0);
            }
            else if (isNpcIdInArray(npcId, CORRUPTED_HUNLLEF_IDS))
            {
                log.info("Corrupted Hunllef defeated - Corrupted Gauntlet complete!");
                bingoProgressReporter.reportGauntletComplete(true, true, 0, 0);
            }
            // Check for Raid completion (final boss death)
            // Set cooldown to prevent chat message fallback from double-reporting
            else if (isNpcIdInArray(npcId, GREAT_OLM_HEAD_IDS))
            {
                log.info("Great Olm defeated - Chambers of Xeric complete!");
                lastRaidCompletionTime.set(System.currentTimeMillis());
                bingoProgressReporter.reportRaidComplete("Chambers of Xeric", true, 0, 0);
            }
            else if (isNpcIdInArray(npcId, VERZIK_VITUR_P3_IDS))
            {
                log.info("Verzik Vitur defeated - Theatre of Blood complete!");
                lastRaidCompletionTime.set(System.currentTimeMillis());
                bingoProgressReporter.reportRaidComplete("Theatre of Blood", true, 0, 0);
            }
            else if (isNpcIdInArray(npcId, TUMEKENS_WARDEN_IDS) || isNpcIdInArray(npcId, ELIDINIS_WARDEN_IDS))
            {
                log.info("Warden defeated - Tombs of Amascut complete!");
                lastRaidCompletionTime.set(System.currentTimeMillis());
                bingoProgressReporter.reportRaidComplete("Tombs of Amascut", true, 0, 0);
            }
            // Note: NPC/boss kill counting is handled in onServerNpcLoot
            // which only fires when YOU receive loot (you killed it or got MVP)
        }
    }

    /**
     * Helper method to check if an NPC ID is in an array of IDs.
     */
    private boolean isNpcIdInArray(int npcId, int[] ids)
    {
        for (int id : ids)
        {
            if (id == npcId)
            {
                return true;
            }
        }
        return false;
    }

    // Widget group IDs for interface detection
    private static final int BANK_WIDGET_GROUP_ID = 12;
    private static final int GIM_SHARED_STORAGE_WIDGET_GROUP_ID = 725;

    /**
     * Handle widget loaded events - for bank open, shared chest open, and clue scroll reward detection.
     */
    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event)
    {
        // Bank interface group ID is 12
        if (event.getGroupId() == BANK_WIDGET_GROUP_ID)
        {
            itemSnitchTracker.onBankOpen();
            itemSnitchButton.onBankOpen();
        }

        // GIM Shared Storage interface group ID is 725
        if (event.getGroupId() == GIM_SHARED_STORAGE_WIDGET_GROUP_ID)
        {
            itemSnitchTracker.onSharedChestOpen();
        }

        // Check for clue scroll reward widget
        if (config.trackClueScrolls() && event.getGroupId() == clueScrollTracker.getRewardWidgetGroupId())
        {
            clueScrollTracker.onRewardWidgetLoaded();
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
     * Handle widget closed events - for bank close and shared chest close detection.
     */
    @Subscribe
    public void onWidgetClosed(WidgetClosed event)
    {
        // Bank interface group ID is 12
        if (event.getGroupId() == BANK_WIDGET_GROUP_ID)
        {
            itemSnitchTracker.onBankClose();
            itemSnitchButton.onBankClose();
        }

        // GIM Shared Storage interface group ID is 725
        if (event.getGroupId() == GIM_SHARED_STORAGE_WIDGET_GROUP_ID)
        {
            itemSnitchTracker.onSharedChestClose();
        }
    }

    /**
     * Handle item container changes - for bank and shared chest item scanning.
     */
    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        int containerId = event.getContainerId();

        // Bank container ID
        if (containerId == InventoryID.BANK.getId())
        {
            itemSnitchTracker.onBankItemsChanged();
            itemSnitchButton.refresh();
        }

        // GIM Shared Storage container ID
        if (containerId == InventoryID.GROUP_STORAGE.getId())
        {
            itemSnitchTracker.onSharedChestItemsChanged();
        }

        // Inventory changes while shared chest is open (items moved to/from chest)
        if (containerId == InventoryID.INVENTORY.getId() && itemSnitchTracker.isSharedChestOpen())
        {
            itemSnitchTracker.onSharedChestItemsChanged();
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
     * Handle game ticks - check for quality setting changes and bingo refresh.
     */
    @Subscribe
    public void onGameTick(GameTick gameTick)
    {
        // Update video capture rate if quality setting changed
        videoRecorder.updateCaptureRateIfNeeded();

        // Handle delayed quest sync
        questTracker.onGameTick();

        // Check for bingo subscription refresh (polls every 5 min if active event)
        bingoSubscriptionManager.checkForRefresh();
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
