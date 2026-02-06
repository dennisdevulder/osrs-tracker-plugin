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
package com.osrstracker.skills;

import com.google.gson.JsonObject;
import com.osrstracker.OsrsTrackerConfig;
import com.osrstracker.api.ApiClient;
import com.osrstracker.video.VideoRecorder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Skill;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tracks skill level changes and reports level-ups to the API.
 *
 * This tracker monitors all skills except OVERALL (total level) by maintaining
 * a snapshot of previous skill levels and comparing them to current levels.
 */
@Slf4j
@Singleton
public class SkillLevelTracker
{
    private final Client client;
    private final ApiClient apiClient;
    private final OsrsTrackerConfig config;
    private final VideoRecorder videoRecorder;

    // Stores the last known level for each skill to detect level-ups
    private final Map<Skill, Integer> previousSkillLevels = new HashMap<>();

    // Stores the baseline levels captured at login - used to filter login burst events
    private final Map<Skill, Integer> loginBaselineLevels = new HashMap<>();

    // Queue of skills that leveled up during the initialization window
    // These will be processed once initialization completes
    private final Set<Skill> pendingLevelUps = new HashSet<>();

    // Flag to track if initialization is complete - prevents false level-ups on login
    private volatile boolean initialized = false;

    // Scheduler for delayed initialization
    private final ScheduledExecutorService initScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "OSRS-Tracker-Skill-Init");
        t.setDaemon(true);
        return t;
    });

    @Inject
    public SkillLevelTracker(Client client, ApiClient apiClient, OsrsTrackerConfig config, VideoRecorder videoRecorder)
    {
        this.client = client;
        this.apiClient = apiClient;
        this.config = config;
        this.videoRecorder = videoRecorder;
    }

    /**
     * Initializes skill level tracking when the player logs in.
     * Captures the current level for each skill to establish a baseline for comparison.
     *
     * Uses a 2-second delay before enabling tracking to allow the initial burst of
     * StatChanged events to settle after login/plugin enable. During this window,
     * real level-ups are queued and processed once initialization completes.
     */
    public void initializeSkillLevels()
    {
        // Mark as not initialized - queues level-ups instead of processing immediately
        initialized = false;
        previousSkillLevels.clear();
        loginBaselineLevels.clear();
        pendingLevelUps.clear();

        for (Skill skill : Skill.values())
        {
            // Skip OVERALL as it's not a trainable skill
            if (skill != Skill.OVERALL)
            {
                int currentLevel = client.getRealSkillLevel(skill);
                previousSkillLevels.put(skill, currentLevel);
                loginBaselineLevels.put(skill, currentLevel);
            }
        }

        log.debug("Captured baseline for {} skills, waiting 2 seconds before enabling tracking...", previousSkillLevels.size());

        // Delay enabling tracking by 2 seconds to let the initial StatChanged burst settle
        initScheduler.schedule(() -> {
            // Re-capture all skill levels now that stats have loaded
            // This ensures we have the real levels as our baseline, not 0s
            for (Skill skill : Skill.values())
            {
                if (skill != Skill.OVERALL)
                {
                    int currentLevel = client.getRealSkillLevel(skill);
                    previousSkillLevels.put(skill, currentLevel);
                }
            }

            initialized = true;
            log.info("Skill level tracking enabled - {} skills tracked with actual levels captured", previousSkillLevels.size());

            // Process any level-ups that occurred during the initialization window
            // (only if we had valid baselines - not when starting from 0)
            if (!pendingLevelUps.isEmpty())
            {
                int realLevelUps = 0;
                for (Skill skill : pendingLevelUps)
                {
                    int currentLevel = client.getRealSkillLevel(skill);
                    Integer baselineLevel = loginBaselineLevels.get(skill);
                    // Skip if baseline was 0 - that means we captured it before stats loaded
                    // and this is just the initial stat population, not a real level-up
                    if (baselineLevel != null && baselineLevel > 0 && currentLevel > baselineLevel)
                    {
                        log.info("Deferred level up: {} {} -> {}", skill.getName(), baselineLevel, currentLevel);
                        sendLevelUpToApi(skill, baselineLevel, currentLevel);
                        realLevelUps++;
                    }
                }
                if (realLevelUps > 0)
                {
                    log.info("Processed {} real level-ups that occurred during initialization", realLevelUps);
                }
                pendingLevelUps.clear();
            }
        }, 2, TimeUnit.SECONDS);
    }

    /**
     * Resets all skill tracking state. Should be called when the plugin shuts down.
     */
    public void resetSkillTracking()
    {
        initialized = false;
        previousSkillLevels.clear();
        loginBaselineLevels.clear();
        pendingLevelUps.clear();
    }

    /**
     * Shuts down the scheduler. Should be called when the plugin shuts down
     * to prevent queued tasks from running after logout.
     */
    public void shutdown()
    {
        initScheduler.shutdownNow();
    }

    /**
     * Checks if a skill level-up has occurred for the given skill.
     * Should be called when a StatChanged event is received.
     *
     * A level-up is detected when:
     * 1. The current real skill level is higher than the previously recorded level
     * 2. The skill is not OVERALL (which we don't track)
     *
     * During the 2-second initialization window, real level-ups are queued and
     * processed once initialization completes. This prevents losing level-ups
     * that occur immediately after login while still filtering the login burst.
     *
     * @param skill The skill that changed
     */
    public void checkForLevelUp(Skill skill)
    {
        // Ignore OVERALL skill as it's not a trainable skill
        if (skill == Skill.OVERALL)
        {
            return;
        }

        // Check if level-up tracking is enabled in config
        if (!config.trackLevelUps())
        {
            return;
        }

        int currentLevel = client.getRealSkillLevel(skill);
        Integer baselineLevel = loginBaselineLevels.get(skill);

        // During initialization window, queue real level-ups for later processing
        if (!initialized)
        {
            // If level is higher than the baseline captured at login, AND baseline > 0,
            // this is a real level-up (not part of the login burst or initial stat load).
            // Queue it for processing after init completes.
            // Skip if baseline was 0 - that's just the game loading stats, not a real level-up.
            if (baselineLevel != null && baselineLevel > 0 && currentLevel > baselineLevel)
            {
                log.debug("Queueing level-up during init: {} {} -> {}", skill.getName(), baselineLevel, currentLevel);
                pendingLevelUps.add(skill);
            }
            // Otherwise it's part of the login burst or initial stat load - ignore it
            return;
        }

        Integer previousLevel = previousSkillLevels.get(skill);

        // Skip if we don't have a previous level recorded (shouldn't happen after init)
        if (previousLevel == null)
        {
            log.warn("No previous level for {} - initializing to {}", skill.getName(), currentLevel);
            previousSkillLevels.put(skill, currentLevel);
            return;
        }

        // Detect if the level increased
        if (currentLevel > previousLevel)
        {
            log.info("Level up detected: {} {} -> {}", skill.getName(), previousLevel, currentLevel);
            sendLevelUpToApi(skill, previousLevel, currentLevel);

            // Update stored level for future comparisons
            previousSkillLevels.put(skill, currentLevel);
        }
        else if (currentLevel < previousLevel)
        {
            // This shouldn't happen normally - log it for debugging
            log.warn("Level DECREASED for {} from {} to {} - resetting tracking",
                skill.getName(), previousLevel, currentLevel);
            previousSkillLevels.put(skill, currentLevel);
        }
        // else: currentLevel == previousLevel, normal XP gain without level-up
    }

    /**
     * Sends a level-up event to the API with screenshot and video.
     *
     * @param skill The skill that leveled up
     * @param oldLevel The previous skill level
     * @param newLevel The new skill level
     */
    private void sendLevelUpToApi(Skill skill, int oldLevel, int newLevel)
    {
        // Capture screenshot and video for level-ups
        videoRecorder.captureEventVideo((screenshotBase64, videoBase64) -> {
            JsonObject payload = new JsonObject();
            payload.addProperty("skill", skill.getName());
            payload.addProperty("old_level", oldLevel);
            payload.addProperty("new_level", newLevel);

            apiClient.sendEventToApi(
                "/api/webhooks/level_up",
                payload.toString(),
                skill.getName() + " level-up to " + newLevel,
                screenshotBase64,
                videoBase64
            );
        });
    }
}
