/*
 * Copyright (c) 2025, Dennis De Vulder
 * All rights reserved.
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
import java.util.concurrent.TimeUnit;

/**
 * Service for uploading screenshots directly to Backblaze B2 storage.
 * This avoids sending base64 data through the Rails API, improving performance
 * and keeping logs clean.
 */
@Slf4j
@Singleton
public class ScreenshotUploadService
{
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType JPEG = MediaType.parse("image/jpeg");

    private final OkHttpClient httpClient;
    private final OsrsTrackerConfig config;
    private final Gson gson;
    private final ChatMessageManager chatMessageManager;

    @Inject
    public ScreenshotUploadService(OkHttpClient httpClient, OsrsTrackerConfig config, Gson gson, ChatMessageManager chatMessageManager)
    {
        this.httpClient = httpClient.newBuilder()
            .cache(null)
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();
        this.config = config;
        this.gson = gson;
        this.chatMessageManager = chatMessageManager;
    }

    /**
     * Uploads a screenshot directly to Backblaze B2 and returns the storage key.
     * This is more efficient than sending base64 through the Rails API.
     *
     * @param screenshotBytes Raw PNG bytes (decoded from base64)
     * @return Storage key (e.g., "screenshots/abc123.png") or null if upload failed
     */
    public String uploadScreenshot(byte[] screenshotBytes)
    {
        if (screenshotBytes == null || screenshotBytes.length == 0)
        {
            log.debug("No screenshot bytes to upload");
            return null;
        }

        String apiUrl = OsrsTrackerConfig.getEffectiveApiUrl();
        String apiToken = config.apiToken();

        if (apiToken.isEmpty())
        {
            log.warn("API token not configured, cannot upload screenshot");
            return null;
        }

        try
        {
            // Step 1: Get presigned URL from Rails API
            String presignedUrl = getPresignedScreenshotUrl(apiUrl, apiToken);
            if (presignedUrl == null)
            {
                log.error("Failed to get presigned URL for screenshot upload");
                return null;
            }

            // Step 2: Upload screenshot bytes directly to Backblaze
            String storageKey = uploadToBackblaze(presignedUrl, screenshotBytes);
            if (storageKey != null)
            {
                log.debug("Screenshot uploaded successfully: {}", storageKey);
            }
            return storageKey;

        }
        catch (Exception e)
            {
            log.error("Failed to upload screenshot", e);
            return null;
        }
    }

    /**
     * Gets a presigned upload URL from the Rails API.
     */
    private String getPresignedScreenshotUrl(String apiUrl, String apiToken) throws IOException
    {
        String endpoint = apiUrl + "/events/presigned_screenshot_url";

        Request request = new Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer " + apiToken)
            .get()
            .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (!response.isSuccessful())
            {
                log.error("Failed to get presigned screenshot URL: {} {}", 
                    response.code(), response.message());
                return null;
            }

            String body = response.body().string();
            JsonObject json = gson.fromJson(body, JsonObject.class);
            
            return json.get("upload_url").getAsString();
        }
    }

    /**
     * Uploads screenshot bytes directly to Backblaze using presigned URL.
     */
    private String uploadToBackblaze(String presignedUrl, byte[] screenshotBytes) throws IOException
    {
        RequestBody body = RequestBody.create(JPEG, screenshotBytes);
        
        Request request = new Request.Builder()
            .url(presignedUrl)
            .put(body)
            .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (response.isSuccessful())
            {
                // Extract storage key from the presigned URL
                // URL format: https://.../screenshots/abc123.png?...
                String url = presignedUrl;
                int keyStart = url.indexOf("screenshots/");
                if (keyStart != -1)
                {
                    int keyEnd = url.indexOf("?", keyStart);
                    if (keyEnd == -1) keyEnd = url.length();
                    return url.substring(keyStart, keyEnd);
                }
                return "screenshots/unknown";
            }
            else
            {
                log.error("Screenshot upload failed: {} {}", response.code(), response.message());
                return null;
            }
        }
    }

    /**
     * Decodes a base64 screenshot string to raw bytes.
     */
    public byte[] decodeBase64Screenshot(String base64Screenshot)
    {
        if (base64Screenshot == null || base64Screenshot.isEmpty())
        {
            return null;
        }

        try
        {
            // Remove data URI prefix if present
            String base64Data = base64Screenshot;
            if (base64Screenshot.contains(","))
            {
                base64Data = base64Screenshot.substring(base64Screenshot.indexOf(",") + 1);
            }
            
            return java.util.Base64.getDecoder().decode(base64Data);
        }
        catch (Exception e)
        {
            log.error("Failed to decode base64 screenshot", e);
            return null;
        }
    }
}
