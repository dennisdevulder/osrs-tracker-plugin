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
import java.util.Map;
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
     * Level-ups are ignored until initialization is complete to prevent false triggers on login.
     *
     * Uses a 2-second delay before enabling tracking to allow the initial burst of
     * StatChanged events to settle after login/plugin enable.
     */
    public void initializeSkillLevels()
    {
        // Mark as not initialized - blocks all level-up detection
        initialized = false;
        previousSkillLevels.clear();

        for (Skill skill : Skill.values())
        {
            // Skip OVERALL as it's not a trainable skill
            if (skill != Skill.OVERALL)
            {
                int currentLevel = client.getRealSkillLevel(skill);
                previousSkillLevels.put(skill, currentLevel);
            }
        }

        log.debug("Captured baseline for {} skills, waiting 2 seconds before enabling tracking...", previousSkillLevels.size());

        // Delay enabling tracking by 2 seconds to let the initial StatChanged burst settle
        initScheduler.schedule(() -> {
            // Re-capture levels after delay to ensure we have the latest values
            for (Skill skill : Skill.values())
            {
                if (skill != Skill.OVERALL)
                {
                    int currentLevel = client.getRealSkillLevel(skill);
                    previousSkillLevels.put(skill, currentLevel);
                }
            }
            initialized = true;
            log.info("Skill level tracking enabled - {} skills tracked", previousSkillLevels.size());
        }, 2, TimeUnit.SECONDS);
    }

    /**
     * Resets all skill tracking state. Should be called when the plugin shuts down.
     */
    public void resetSkillTracking()
    {
        initialized = false;
        previousSkillLevels.clear();
    }

    /**
     * Checks if a skill level-up has occurred for the given skill.
     * Should be called when a StatChanged event is received.
     *
     * A level-up is detected when:
     * 1. Skill tracking has been initialized (prevents false triggers on login)
     * 2. The current real skill level is higher than the previously recorded level
     * 3. The skill is not OVERALL (which we don't track)
     *
     * @param skill The skill that changed
     */
    public void checkForLevelUp(Skill skill)
    {
        // Ignore events until initialization is complete (prevents 23 false level-ups on login)
        if (!initialized)
        {
            return;
        }

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
        Integer previousLevel = previousSkillLevels.get(skill);

        // Skip if we don't have a previous level recorded (shouldn't happen after init)
        if (previousLevel == null)
        {
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
