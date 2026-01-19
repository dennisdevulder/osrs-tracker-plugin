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
package com.osrstracker.collectionlog;

import com.google.gson.JsonObject;
import com.osrstracker.OsrsTrackerConfig;
import com.osrstracker.api.ApiClient;
import com.osrstracker.video.VideoRecorder;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks collection log updates by monitoring game chat messages.
 *
 * When a player receives a new collection log item, the game displays a message
 * in the format: "New item added to your collection log: [item name]"
 * This tracker detects that message and reports the item to the API.
 */
@Slf4j
@Singleton
public class CollectionLogTracker
{
    // Regex pattern to match collection log messages
    // Example: "New item added to your collection log: Twisted bow"
    private static final Pattern COLLECTION_LOG_MESSAGE_PATTERN = Pattern.compile(
        "New item added to your collection log: (.*)"
    );

    private final ApiClient apiClient;
    private final OsrsTrackerConfig config;
    private final VideoRecorder videoRecorder;

    @Inject
    public CollectionLogTracker(ApiClient apiClient, OsrsTrackerConfig config, VideoRecorder videoRecorder)
    {
        this.apiClient = apiClient;
        this.config = config;
        this.videoRecorder = videoRecorder;
    }

    /**
     * Processes a game chat message to check for collection log updates.
     *
     * If the message matches the collection log pattern and tracking is enabled,
     * extracts the item name and sends it to the API.
     *
     * @param chatMessage The chat message text (should already have HTML tags removed)
     * @return true if a collection log item was detected and processed, false otherwise
     */
    public boolean processGameMessage(String chatMessage)
    {
        // Check if collection log tracking is enabled
        if (!config.trackCollectionLog())
        {
            return false;
        }

        // Try to match the collection log message pattern
        Matcher matcher = COLLECTION_LOG_MESSAGE_PATTERN.matcher(chatMessage);

        if (matcher.find())
        {
            // Extract the item name from the matched group
            String itemName = matcher.group(1);

            log.info("Collection log item detected: {}", itemName);
            sendCollectionLogUpdateToApi(itemName);

            return true;
        }

        return false;
    }

    /**
     * Sends a collection log update event to the API with video capture.
     *
     * @param itemName The name of the item added to the collection log
     */
    private void sendCollectionLogUpdateToApi(String itemName)
    {
        // Capture video with the collection log event
        videoRecorder.captureEventVideo((screenshotBase64, videoBase64) -> {
            JsonObject payload = new JsonObject();
            payload.addProperty("item_name", itemName);
            payload.addProperty("obtained_at", Instant.now().toString());

            apiClient.sendEventToApi(
                "/api/webhooks/collection_log",
                payload.toString(),
                "collection log: " + itemName,
                screenshotBase64,
                videoBase64
            );
        });
    }
}
