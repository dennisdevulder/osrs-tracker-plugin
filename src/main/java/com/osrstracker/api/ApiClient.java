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
package com.osrstracker.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.osrstracker.OsrsTrackerConfig;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import okhttp3.*;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;

/**
 * Handles all HTTP communication with the OSRS Tracker API.
 * This class provides a centralized way to send events to the backend server.
 */
@Slf4j
@Singleton
public class ApiClient
{
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final OsrsTrackerConfig config;
    private final Gson gson;
    private final ChatMessageManager chatMessageManager;

    @Inject
    public ApiClient(OkHttpClient httpClient, OsrsTrackerConfig config, Gson gson, ChatMessageManager chatMessageManager)
    {
        // Create our own client without disk cache to avoid RuneLite cache conflicts
        // The shared RuneLite client uses disk caching which can fail on Windows
        this.httpClient = httpClient.newBuilder()
            .cache(null)  // Disable disk cache for our API calls
            .build();
        this.config = config;
        this.gson = gson;
        this.chatMessageManager = chatMessageManager;
    }

    /**
     * Sends a JSON payload to the specified API endpoint.
     * The request is performed asynchronously to avoid blocking the game client.
     *
     * @param endpoint The API endpoint path (e.g., "/api/webhooks/level_up")
     * @param jsonPayload The JSON string to send in the request body
     * @param eventDescription A human-readable description of the event for logging purposes
     */
    public void sendEventToApi(String endpoint, String jsonPayload, String eventDescription)
    {
        sendEventToApi(endpoint, jsonPayload, eventDescription, null, null);
    }

    /**
     * Sends a JSON payload to the specified API endpoint with optional screenshot and video.
     * The request is performed asynchronously to avoid blocking the game client.
     *
     * @param endpoint The API endpoint path (e.g., "/api/webhooks/level_up")
     * @param jsonPayload The JSON string to send in the request body
     * @param eventDescription A human-readable description of the event for logging purposes
     * @param screenshotBase64 Base64-encoded PNG screenshot (optional, can be null)
     * @param videoBase64 Base64-encoded MP4 video (optional, can be null)
     */
    public void sendEventToApi(String endpoint, String jsonPayload, String eventDescription, String screenshotBase64, String videoBase64)
    {
        String apiUrl = OsrsTrackerConfig.getEffectiveApiUrl();
        String apiToken = config.apiToken();

        log.debug("Sending {} to API URL: {}", eventDescription, apiUrl);

        // Validate configuration before attempting to send
        if (!isConfigurationValid())
        {
            log.warn("API URL or token not configured, cannot send {}", eventDescription);
            return;
        }

        // Parse the JSON payload and add screenshot/video if provided
        String finalPayload = jsonPayload;
        if (screenshotBase64 != null || videoBase64 != null)
        {
            try
            {
                // Use injected Gson to parse and modify JSON
                JsonObject json = gson.fromJson(jsonPayload, JsonObject.class);
                if (screenshotBase64 != null)
                {
                    json.addProperty("screenshot", screenshotBase64);
                }
                if (videoBase64 != null)
                {
                    json.addProperty("replay_gif", videoBase64);
                }
                finalPayload = json.toString();
            }
            catch (Exception e)
            {
                log.error("Failed to add screenshot/video to payload", e);
            }
        }

        // Build the HTTP request
        RequestBody body = RequestBody.create(JSON, finalPayload);
        Request request = new Request.Builder()
            .url(apiUrl + endpoint)
            .addHeader("Authorization", "Bearer " + apiToken)
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build();

        // Execute request asynchronously to avoid blocking the game thread
        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.error("Failed to send {} to OSRS Tracker: {}", eventDescription, e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                try
                {
                    if (response.isSuccessful())
                    {
                        log.debug("Successfully sent {} to OSRS Tracker", eventDescription);
                    }
                    else if (response.code() == 402)
                    {
                        // Payment Required - daily video limit reached
                        log.warn("Daily video limit reached for {}", eventDescription);
                        showChatMessage("OSRS Tracker: Daily video limit reached! Upgrade at osrs-tracker.com/upgrade for unlimited videos.");
                    }
                    else
                    {
                        log.warn("Failed to send {}: {} - {}", eventDescription, response.code(), response.message());
                    }
                }
                finally
                {
                    response.close();
                }
            }
        });
    }

    /**
     * Validates that the API configuration is complete and ready to use.
     * Network requests are disabled by default until the user configures their API token.
     *
     * @return true if API token is configured, false otherwise
     */
    public boolean isConfigurationValid()
    {
        if (config.apiToken().isEmpty())
        {
            log.debug("API Token not configured - tracking disabled");
            return false;
        }

        return true;
    }

    /**
     * Shows a chat message in the game client.
     * Used to notify users of important events like quota limits.
     */
    private void showChatMessage(String message)
    {
        if (chatMessageManager != null)
        {
            chatMessageManager.queue(QueuedMessage.builder()
                .type(ChatMessageType.GAMEMESSAGE)
                .runeLiteFormattedMessage("<col=ff9040>" + message + "</col>")
                .build());
        }
    }
}
