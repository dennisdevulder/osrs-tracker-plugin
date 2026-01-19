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
package com.osrstracker.death;

import com.google.gson.JsonObject;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Player;
import com.osrstracker.OsrsTrackerConfig;
import com.osrstracker.api.ApiClient;
import com.osrstracker.video.VideoRecorder;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;

/**
 * Tracks player death events by monitoring ActorDeath events.
 *
 * When the local player dies, this tracker captures the event and reports it
 * to the API with a video recording of the death.
 */
@Slf4j
@Singleton
public class DeathTracker
{
    private final Client client;
    private final ApiClient apiClient;
    private final OsrsTrackerConfig config;
    private final VideoRecorder videoRecorder;

    @Inject
    public DeathTracker(Client client, ApiClient apiClient, OsrsTrackerConfig config, VideoRecorder videoRecorder)
    {
        this.client = client;
        this.apiClient = apiClient;
        this.config = config;
        this.videoRecorder = videoRecorder;
    }

    /**
     * Processes an ActorDeath event to check if the local player died.
     *
     * @param actor The actor that died
     */
    public void processActorDeath(Actor actor)
    {
        // Check if death tracking is enabled
        if (!config.trackDeaths())
        {
            return;
        }

        // Only track if the dead actor is the local player
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null || actor != localPlayer)
        {
            return;
        }

        log.info("Player death detected");
        sendDeathEventToApi();
    }

    // Death events need longer post-event capture to show the full death animation
    private static final int DEATH_POST_EVENT_MS = 5000; // 5 seconds

    /**
     * Sends a death event to the API with video capture.
     * Uses 5-second post-event duration to capture the full death animation.
     */
    private void sendDeathEventToApi()
    {
        // Capture video with the death event - use 5 seconds post-event for death animation
        videoRecorder.captureEventVideo(
            (screenshotBase64, videoBase64) -> {
                JsonObject payload = new JsonObject();
                payload.addProperty("timestamp", Instant.now().toString());

                // Get player location if available
                Player localPlayer = client.getLocalPlayer();
                if (localPlayer != null)
                {
                    payload.addProperty("x", localPlayer.getWorldLocation().getX());
                    payload.addProperty("y", localPlayer.getWorldLocation().getY());
                    payload.addProperty("plane", localPlayer.getWorldLocation().getPlane());
                }

                // Send with video
                apiClient.sendEventToApi(
                    "/api/webhooks/death",
                    payload.toString(),
                    "death",
                    screenshotBase64,
                    videoBase64
                );
            },
            null, // No encoding start callback needed
            DEATH_POST_EVENT_MS
        );
    }
}
