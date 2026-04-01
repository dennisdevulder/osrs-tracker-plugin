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
package com.osrstracker.video;

import lombok.extern.slf4j.Slf4j;
import com.osrstracker.OsrsTrackerConfig;
import com.osrstracker.video.encode.MjpegEncoder;
import com.osrstracker.video.encode.VideoEncoder;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.widgets.Widget;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.ImageUtil;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.*;
import okio.BufferedSink;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Orchestrates video capture, encoding, and upload for gameplay events.
 *
 * Delegates frame encoding and buffering to a {@link VideoEncoder} implementation.
 * Currently uses {@link MjpegEncoder} (JPEG circular buffer + MJPEG upload).
 * Future: Vulkan H.264 encoder for on-device encoding.
 *
 * This class manages:
 * - AsyncFrameCapture coordination (PBO readback from GPU)
 * - Quality/config management
 * - Sensitive content detection
 * - Presigned URL fetching and upload orchestration
 * - Screenshot capture
 */
@Slf4j
@Singleton
public class VideoRecorder
{
    private final DrawManager drawManager;
    private final OsrsTrackerConfig config;
    private final Client client;
    private final ChatMessageManager chatMessageManager;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService asyncWriter;
    private final ExecutorService apiExecutor;
    private final OkHttpClient httpClient;
    private final OkHttpClient uploadClient;
    private final Gson gson;

    // The active video encoder (MJPEG for now, Vulkan H.264 later)
    private final VideoEncoder encoder;

    // Recording state
    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private final AtomicBoolean isCapturingPostEvent = new AtomicBoolean(false);

    // Backpressure: limit concurrent encoding operations
    private final AtomicInteger pendingEncodes = new AtomicInteger(0);
    private static final int MAX_PENDING_ENCODES = 4;

    // Sensitive content detection - cached to avoid expensive widget lookups every frame
    private final AtomicBoolean sensitiveContentVisible = new AtomicBoolean(false);
    private final AtomicLong lastSensitiveCheck = new AtomicLong(0);
    private static final long SENSITIVE_CHECK_INTERVAL_MS = 500;

    private AsyncFrameCapture asyncFrameCapture;

    // Track current capture settings to detect quality changes
    private volatile int currentCaptureFps = 0;
    private volatile float currentJpegQuality = 0.5f;

    // Set when GPU plugin is detected as unavailable
    private volatile boolean gpuUnavailable = false;
    private volatile boolean gpuWarningShown = false;

    // Default post-event duration in milliseconds
    private static final int DEFAULT_POST_EVENT_MS = 4000;

    @Inject
    public VideoRecorder(DrawManager drawManager, OsrsTrackerConfig config, Client client, ChatMessageManager chatMessageManager, OkHttpClient httpClient, Gson gson)
    {
        this.drawManager = drawManager;
        this.config = config;
        this.client = client;
        this.chatMessageManager = chatMessageManager;
        this.gson = gson;

        this.httpClient = httpClient.newBuilder()
            .cache(null)
            .connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES))
            .build();

        this.uploadClient = httpClient.newBuilder()
            .cache(null)
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.MINUTES)
            .readTimeout(5, TimeUnit.MINUTES)
            .build();

        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "OSRS-Tracker-Video-Scheduler");
            t.setDaemon(true);
            return t;
        });
        this.asyncWriter = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "OSRS-Tracker-Video-Writer");
            t.setDaemon(true);
            return t;
        });
        this.apiExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "OSRS-Tracker-API");
            t.setDaemon(true);
            return t;
        });

        // Initialize the encoder (MJPEG for now)
        this.encoder = new MjpegEncoder();

        log.debug("Video recorder initialized (encoder={})", encoder.encoderName());
    }

    // ---- Package-private API for AsyncFrameCapture ----

    boolean isCurrentlyRecording()
    {
        return isRecording.get();
    }

    int getCaptureFps()
    {
        return currentCaptureFps;
    }

    boolean canAcceptFrame()
    {
        return pendingEncodes.get() < MAX_PENDING_ENCODES;
    }

    void submitCapturedFrame(ByteBuffer rgbaPixels, int width, int height)
    {
        long currentTimeMs = System.currentTimeMillis();
        boolean shouldBlur = getCachedSensitiveContentVisible(currentTimeMs);

        pendingEncodes.incrementAndGet();
        asyncWriter.submit(() -> {
            try
            {
                encoder.submitFrame(rgbaPixels, width, height, currentTimeMs, shouldBlur);
            }
            catch (Exception e)
            {
                log.error("Failed to encode frame", e);
            }
            finally
            {
                pendingEncodes.decrementAndGet();
            }
        });
    }

    // ---- Sensitive content detection ----

    private boolean isSensitiveContentVisible()
    {
        try
        {
            GameState gameState = client.getGameState();
            if (gameState == GameState.LOGIN_SCREEN ||
                gameState == GameState.LOGIN_SCREEN_AUTHENTICATOR ||
                gameState == GameState.LOGGING_IN)
            {
                return true;
            }

            Widget bankPinWidget = client.getWidget(InterfaceID.BANKPIN_KEYPAD, 0);
            if (bankPinWidget != null && !bankPinWidget.isHidden())
            {
                return true;
            }

            Widget bankPinSettingsWidget = client.getWidget(InterfaceID.BANKPIN_SETTINGS, 0);
            if (bankPinSettingsWidget != null && !bankPinSettingsWidget.isHidden())
            {
                return true;
            }
        }
        catch (Exception e)
        {
            log.debug("Error checking for sensitive content: {}", e.getMessage());
        }

        return false;
    }

    private boolean getCachedSensitiveContentVisible(long currentTime)
    {
        long lastCheck = lastSensitiveCheck.get();

        if (currentTime - lastCheck >= SENSITIVE_CHECK_INTERVAL_MS)
        {
            if (lastSensitiveCheck.compareAndSet(lastCheck, currentTime))
            {
                boolean isVisible = isSensitiveContentVisible();
                sensitiveContentVisible.set(isVisible);
                return isVisible;
            }
        }

        return sensitiveContentVisible.get();
    }

    // ---- Recording lifecycle ----

    public void startRecording()
    {
        if (isRecording.getAndSet(true))
        {
            return;
        }

        gpuUnavailable = false;
        gpuWarningShown = false;

        VideoQuality quality = config.videoQuality();
        currentCaptureFps = quality.getFps() > 0 ? quality.getFps() : 30;
        currentJpegQuality = quality.getJpegQuality() > 0 ? quality.getJpegQuality() : 0.5f;

        encoder.start(currentCaptureFps, currentJpegQuality);
        log.debug("Video capture started at {} FPS (encoder={})", currentCaptureFps, encoder.encoderName());

        asyncFrameCapture = new AsyncFrameCapture(drawManager, this, this::onGpuUnavailable);
        asyncFrameCapture.start();
    }

    public void stopRecording()
    {
        if (!isRecording.getAndSet(false))
        {
            return;
        }

        if (asyncFrameCapture != null)
        {
            asyncFrameCapture.stop();
            asyncFrameCapture = null;
        }

        encoder.stop();
        currentCaptureFps = 0;
    }

    private void onGpuUnavailable()
    {
        log.debug("GPU plugin not active, falling back to screenshot-only");

        if (asyncFrameCapture != null)
        {
            asyncFrameCapture.stop();
            asyncFrameCapture = null;
        }

        isRecording.set(false);
        currentCaptureFps = 0;
        gpuUnavailable = true;
    }

    public void updateCaptureRateIfNeeded()
    {
        if (isCapturingPostEvent.get())
        {
            return;
        }

        VideoQuality quality = config.videoQuality();

        if (quality == VideoQuality.SCREENSHOT_ONLY)
        {
            if (currentCaptureFps != 0)
            {
                log.debug("Switching to Screenshot Only mode");

                if (asyncFrameCapture != null)
                {
                    asyncFrameCapture.stop();
                    asyncFrameCapture = null;
                }

                encoder.stop();
                currentCaptureFps = 0;
                currentJpegQuality = 0;
            }
            return;
        }

        if (gpuUnavailable)
        {
            return;
        }

        int targetFps = quality.getFps();
        float targetJpegQuality = quality.getJpegQuality();

        if (targetJpegQuality != currentJpegQuality)
        {
            currentJpegQuality = targetJpegQuality;
        }

        if (targetFps != currentCaptureFps)
        {
            log.debug("Capture rate changed from {} to {} FPS", currentCaptureFps, targetFps);

            encoder.reset();
            currentCaptureFps = targetFps;

            if (asyncFrameCapture == null)
            {
                asyncFrameCapture = new AsyncFrameCapture(drawManager, this, this::onGpuUnavailable);
                asyncFrameCapture.start();
            }

            isRecording.set(true);
        }
    }

    // ---- Event capture ----

    public void captureEventVideo(VideoCallback callback)
    {
        captureEventVideo(callback, null, DEFAULT_POST_EVENT_MS);
    }

    public void captureEventVideo(VideoCallback callback, Runnable onEncodingStart)
    {
        captureEventVideo(callback, onEncodingStart, DEFAULT_POST_EVENT_MS);
    }

    public void captureEventVideo(VideoCallback callback, Runnable onEncodingStart, int postEventMs)
    {
        if (!isRecording.get())
        {
            if (gpuUnavailable && !gpuWarningShown)
            {
                gpuWarningShown = true;
                if (chatMessageManager != null)
                {
                    chatMessageManager.queue(QueuedMessage.builder()
                        .type(ChatMessageType.GAMEMESSAGE)
                        .runeLiteFormattedMessage("<col=ff9040>[OSRS Tracker] GPU plugin required for video. Using screenshot-only mode.</col>")
                        .build());
                }
            }

            if (onEncodingStart != null)
            {
                onEncodingStart.run();
            }
            captureScreenshotOnly(callback);
            return;
        }

        if (isCapturingPostEvent.get())
        {
            if (onEncodingStart != null)
            {
                onEncodingStart.run();
            }
            captureScreenshotOnly(callback);
            return;
        }

        VideoQuality quality = config.videoQuality();

        if (quality == VideoQuality.SCREENSHOT_ONLY)
        {
            if (onEncodingStart != null)
            {
                onEncodingStart.run();
            }
            captureScreenshotOnly(callback);
            return;
        }

        int durationMs = quality.getDurationMs();
        int bufferMs = durationMs - postEventMs;
        if (bufferMs < 1000)
        {
            bufferMs = 1000;
        }

        isCapturingPostEvent.set(true);

        final long captureStartTime = System.currentTimeMillis();
        final long videoStartTime = captureStartTime - bufferMs;
        final long videoEndTime = captureStartTime + postEventMs;
        final int finalPostEventMs = postEventMs;

        scheduler.schedule(() -> {
            isCapturingPostEvent.set(false);
            if (onEncodingStart != null)
            {
                onEncodingStart.run();
            }
            finalizeCapture(callback, videoStartTime, videoEndTime);
        }, finalPostEventMs, TimeUnit.MILLISECONDS);
    }

    public void captureScreenshotOnly(VideoCallback callback)
    {
        final boolean shouldBlur = isSensitiveContentVisible();

        drawManager.requestNextFrameListener(image -> {
            asyncWriter.submit(() -> {
                try
                {
                    BufferedImage screenshot = ImageUtil.bufferedImageFromImage(image);

                    if (shouldBlur)
                    {
                        screenshot = applyScreenshotBlur(screenshot);
                    }

                    String screenshotBase64 = imageToBase64(screenshot);
                    callback.onComplete(screenshotBase64, null);
                }
                catch (Exception e)
                {
                    log.error("Failed to capture screenshot", e);
                    callback.onComplete(null, null);
                }
            });
        });
    }

    // ---- Finalize and upload ----

    private void finalizeCapture(VideoCallback callback, long videoStartTime, long videoEndTime)
    {
        final boolean shouldBlur = isSensitiveContentVisible();

        drawManager.requestNextFrameListener(image -> {
            asyncWriter.submit(() -> {
                try
                {
                    BufferedImage screenshot = ImageUtil.bufferedImageFromImage(image);

                    if (shouldBlur)
                    {
                        screenshot = applyScreenshotBlur(screenshot);
                    }

                    String screenshotBase64 = imageToBase64(screenshot);

                    final int fps = config.videoQuality().getFps();

                    // Delegate clip extraction to the encoder
                    VideoEncoder.ClipData clipData = encoder.finalizeClip(videoStartTime, videoEndTime);

                    if (clipData == null || clipData.getFrames().isEmpty())
                    {
                        callback.onComplete(screenshotBase64, null);
                        return;
                    }

                    apiExecutor.submit(() -> {
                        try
                        {
                            PresignedUrlResponse presignedUrl = getPresignedUploadUrl(fps);

                            if (presignedUrl == null)
                            {
                                log.error("Failed to get presigned URL - returning screenshot only");
                                callback.onComplete(screenshotBase64, null);
                                return;
                            }

                            if (presignedUrl.quotaExceeded)
                            {
                                showQuotaExceededMessage(presignedUrl.message);
                                callback.onComplete(screenshotBase64, null);
                                return;
                            }

                            asyncWriter.submit(() -> {
                                boolean success = uploadClipToBackblaze(presignedUrl.uploadUrl, clipData);
                                if (success)
                                {
                                    callback.onComplete(screenshotBase64, presignedUrl.key);
                                }
                                else
                                {
                                    callback.onComplete(screenshotBase64, null);
                                }
                            });
                        }
                        catch (Exception e)
                        {
                            log.error("Failed to get presigned URL", e);
                            callback.onComplete(screenshotBase64, null);
                        }
                    });
                }
                catch (Exception e)
                {
                    log.error("Failed to finalize capture", e);
                    callback.onComplete(null, null);
                }
            });
        });
    }

    /**
     * Uploads clip data to Backblaze using streaming to avoid memory spikes.
     */
    private boolean uploadClipToBackblaze(String uploadUrl, VideoEncoder.ClipData clipData)
    {
        try
        {
            RequestBody streamingBody = new RequestBody()
            {
                @Override
                public MediaType contentType()
                {
                    return MediaType.parse(clipData.getContentType());
                }

                @Override
                public long contentLength()
                {
                    return clipData.getTotalSize();
                }

                @Override
                public void writeTo(BufferedSink sink) throws IOException
                {
                    for (byte[] frame : clipData.getFrames())
                    {
                        sink.write(frame);
                    }
                    sink.flush();
                }
            };

            Request request = new Request.Builder()
                .url(uploadUrl)
                .addHeader("Content-Type", clipData.getContentType())
                .addHeader("Content-Length", String.valueOf(clipData.getTotalSize()))
                .put(streamingBody)
                .build();

            Response response = uploadClient.newCall(request).execute();
            try
            {
                String responseBody = response.body() != null ? response.body().string() : "null";
                if (response.isSuccessful())
                {
                    return true;
                }
                else
                {
                    log.error("Failed to upload to Backblaze: {} - {} - Body: {}",
                        response.code(), response.message(), responseBody);
                    return false;
                }
            }
            finally
            {
                response.close();
            }
        }
        catch (IOException e)
        {
            log.error("Error uploading to Backblaze", e);
            return false;
        }
    }

    // ---- Presigned URL ----

    private static class PresignedUrlResponse
    {
        String uploadUrl;
        String key;
        boolean quotaExceeded = false;
        boolean screenshotOnly = false;
        String message;
    }

    private PresignedUrlResponse getPresignedUploadUrl(int fps)
    {
        String apiUrl = OsrsTrackerConfig.getEffectiveApiUrl();
        String apiToken = config.apiToken();

        if (apiToken.isEmpty())
        {
            log.warn("API token not configured");
            return null;
        }

        String endpoint = apiUrl + "/events/presigned_upload_url?fps=" + fps;

        Request request = new Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer " + apiToken)
            .addHeader("Content-Type", "application/json")
            .get()
            .build();

        try
        {
            Response response = httpClient.newCall(request).execute();
            try
            {
                String body = response.body().string();
                JsonObject json = gson.fromJson(body, JsonObject.class);

                if (response.code() == 402)
                {
                    PresignedUrlResponse result = new PresignedUrlResponse();
                    result.quotaExceeded = true;
                    result.screenshotOnly = json.has("screenshot_only") && json.get("screenshot_only").getAsBoolean();
                    result.message = json.has("message") ? json.get("message").getAsString() : "Daily video limit reached";
                    return result;
                }

                if (!response.isSuccessful())
                {
                    log.error("Failed to get presigned URL: {} - {}", response.code(), response.message());
                    return null;
                }

                PresignedUrlResponse result = new PresignedUrlResponse();
                result.uploadUrl = json.get("upload_url").getAsString();
                result.key = json.get("key").getAsString();
                return result;
            }
            finally
            {
                response.close();
            }
        }
        catch (IOException e)
        {
            log.error("Error getting presigned URL", e);
            return null;
        }
    }

    // ---- Screenshot blur (kept here since it's only for screenshots, not video frames) ----

    private BufferedImage applyScreenshotBlur(BufferedImage image)
    {
        int width = image.getWidth();
        int height = image.getHeight();

        BufferedImage blurred = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = blurred.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();

        for (int pass = 0; pass < 3; pass++)
        {
            blurred = boxBlur(blurred, 15);
        }

        g = blurred.createGraphics();
        g.setColor(new java.awt.Color(0, 0, 0, 100));
        g.fillRect(0, 0, width, height);
        g.setColor(java.awt.Color.WHITE);
        g.setFont(new java.awt.Font("Arial", java.awt.Font.BOLD, 24));
        java.awt.FontMetrics fm = g.getFontMetrics();
        String warningText = "SENSITIVE CONTENT HIDDEN";
        int textWidth = fm.stringWidth(warningText);
        g.drawString(warningText, (width - textWidth) / 2, height / 2);
        g.dispose();

        return blurred;
    }

    private BufferedImage boxBlur(BufferedImage image, int radius)
    {
        int width = image.getWidth();
        int height = image.getHeight();
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        int[] pixels = new int[width * height];
        int[] result = new int[width * height];
        image.getRGB(0, 0, width, height, pixels, 0, width);

        for (int y = 0; y < height; y++)
        {
            for (int x = 0; x < width; x++)
            {
                int r = 0, g = 0, b = 0, count = 0;
                for (int kx = -radius; kx <= radius; kx++)
                {
                    int px = Math.max(0, Math.min(width - 1, x + kx));
                    int pixel = pixels[y * width + px];
                    r += (pixel >> 16) & 0xFF;
                    g += (pixel >> 8) & 0xFF;
                    b += pixel & 0xFF;
                    count++;
                }
                result[y * width + x] = (0xFF << 24) | ((r / count) << 16) | ((g / count) << 8) | (b / count);
            }
        }

        for (int y = 0; y < height; y++)
        {
            for (int x = 0; x < width; x++)
            {
                int r = 0, g = 0, b = 0, count = 0;
                for (int ky = -radius; ky <= radius; ky++)
                {
                    int py = Math.max(0, Math.min(height - 1, y + ky));
                    int pixel = result[py * width + x];
                    r += (pixel >> 16) & 0xFF;
                    g += (pixel >> 8) & 0xFF;
                    b += pixel & 0xFF;
                    count++;
                }
                pixels[y * width + x] = (0xFF << 24) | ((r / count) << 16) | ((g / count) << 8) | (b / count);
            }
        }

        output.setRGB(0, 0, width, height, pixels, 0, width);
        return output;
    }

    // ---- Utilities ----

    private String imageToBase64(BufferedImage image) throws IOException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", baos);
        byte[] imageBytes = baos.toByteArray();
        return Base64.getEncoder().encodeToString(imageBytes);
    }

    private void showQuotaExceededMessage(String message)
    {
        if (chatMessageManager == null)
        {
            return;
        }

        String displayMessage = message != null ? message : "Daily video limit reached";
        chatMessageManager.queue(QueuedMessage.builder()
            .type(ChatMessageType.GAMEMESSAGE)
            .runeLiteFormattedMessage("<col=ff9040>[OSRS Tracker] " + displayMessage + "</col>")
            .build());
    }

    /**
     * Callback interface for video capture completion.
     */
    public interface VideoCallback
    {
        void onComplete(String screenshotBase64, String videoKey);
    }
}
