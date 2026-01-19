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
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.widgets.Widget;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.ImageUtil;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.*;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Records gameplay video using an in-memory circular buffer of JPEG frames.
 *
 * Implementation details:
 * - Maintains a rolling 10-second buffer of JPEG-compressed frames in memory
 * - Memory footprint is bounded and predictable (~15-30 MB depending on quality)
 * - On clip request, snapshots the last N frames and streams them to cloud storage
 * - Uses presigned URLs from Rails API to upload directly to Backblaze
 * - Old frames are automatically overwritten (circular buffer)
 * - No disk I/O for normal recording - only memory operations
 *
 * Memory Layout:
 * - MAX_FRAMES = 300 frames (10 seconds at 30 FPS)
 * - Average JPEG size ~50KB = ~15MB total buffer
 * - Only JPEG bytes stored, raw frames discarded immediately
 */
@Slf4j
@Singleton
public class VideoRecorder
{
    private final DrawManager drawManager;
    private final OsrsTrackerConfig config;
    private final Client client;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService asyncWriter;
    private final OkHttpClient httpClient;
    private final OkHttpClient uploadClient; // Separate client with longer timeouts for uploads
    private final Gson gson;

    // Circular buffer configuration
    private static final int MAX_FRAMES = 300; // 10 seconds at 30 FPS
    private static final int CLIP_FRAMES = 300; // Frames to capture for a clip

    // Circular buffer storage
    private final byte[][] jpegBuffer = new byte[MAX_FRAMES][];
    private final long[] timestampBuffer = new long[MAX_FRAMES];
    private final AtomicInteger writeIndex = new AtomicInteger(0);
    private final AtomicInteger frameCount = new AtomicInteger(0);
    private final Object bufferLock = new Object();

    // Recording state
    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private final AtomicBoolean isCapturingPostEvent = new AtomicBoolean(false);
    private final AtomicLong lastFrameTime = new AtomicLong(0);

    // Backpressure: limit concurrent encoding operations
    private final AtomicInteger pendingEncodes = new AtomicInteger(0);
    private static final int MAX_PENDING_ENCODES = 4;

    // Sensitive content detection - track state for async frame processing
    private final AtomicBoolean sensitiveContentVisible = new AtomicBoolean(false);

    private ScheduledFuture<?> captureTask;

    // Track current capture settings to detect quality changes
    private volatile int currentCaptureFps = 0;
    private volatile float currentJpegQuality = 0.5f;

    // Blur kernel for sensitive content protection (15x15 box blur for heavy blur)
    private static final int BLUR_RADIUS = 15;

    @Inject
    public VideoRecorder(DrawManager drawManager, OsrsTrackerConfig config, Client client, OkHttpClient httpClient, Gson gson)
    {
        this.drawManager = drawManager;
        this.config = config;
        this.client = client;
        this.httpClient = httpClient;
        this.gson = gson;

        // Create a separate client with longer timeouts for large video uploads
        this.uploadClient = httpClient.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.MINUTES)  // 5 min for large uploads
            .readTimeout(5, TimeUnit.MINUTES)
            .build();

        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "OSRS-Tracker-Video-Scheduler");
            t.setDaemon(true);
            return t;
        });
        // Use 2 writer threads to limit memory pressure
        this.asyncWriter = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "OSRS-Tracker-Video-Writer");
            t.setDaemon(true);
            return t;
        });

        log.info("Video recorder initialized - using in-memory circular buffer ({} frames max, ~{}MB)",
            MAX_FRAMES, MAX_FRAMES * 30 / 1024);
    }

    /**
     * Checks if any sensitive content is currently visible that should be blurred.
     * This includes:
     * - Login screen (protects username/email input)
     * - Login screen authenticator (protects 2FA codes)
     * - Logging in state (transitional state during login)
     * - Bank PIN entry interface
     * - Bank PIN settings interface
     *
     * @return true if sensitive content is visible
     */
    private boolean isSensitiveContentVisible()
    {
        try
        {
            // Check for login screen states - blur any screen where user might enter credentials
            GameState gameState = client.getGameState();
            if (gameState == GameState.LOGIN_SCREEN ||
                gameState == GameState.LOGIN_SCREEN_AUTHENTICATOR ||
                gameState == GameState.LOGGING_IN)
            {
                return true;
            }

            // Check for Bank PIN interface (InterfaceID.BANKPIN_KEYPAD = 213)
            Widget bankPinWidget = client.getWidget(InterfaceID.BANKPIN_KEYPAD, 0);
            if (bankPinWidget != null && !bankPinWidget.isHidden())
            {
                return true;
            }

            // Check for Bank PIN settings interface (InterfaceID.BANKPIN_SETTINGS = 14)
            Widget bankPinSettingsWidget = client.getWidget(InterfaceID.BANKPIN_SETTINGS, 0);
            if (bankPinSettingsWidget != null && !bankPinSettingsWidget.isHidden())
            {
                return true;
            }
        }
        catch (Exception e)
        {
            // Silently ignore widget access errors
            log.debug("Error checking for sensitive content: {}", e.getMessage());
        }

        return false;
    }

    /**
     * Applies a heavy box blur to the image to obscure sensitive content.
     * Uses multiple passes of a box blur for a strong effect that makes
     * the content unreadable while still showing something happened.
     *
     * @param image The image to blur
     * @return A heavily blurred version of the image
     */
    private BufferedImage applyHeavyBlur(BufferedImage image)
    {
        int width = image.getWidth();
        int height = image.getHeight();

        // Create a copy to work with - use TYPE_INT_RGB for proper color handling
        BufferedImage blurred = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        // Copy original to blurred
        java.awt.Graphics2D g = blurred.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();

        // Apply multiple passes of box blur for heavy blur effect
        for (int pass = 0; pass < 3; pass++)
        {
            blurred = boxBlur(blurred, BLUR_RADIUS);
        }

        // Add a semi-transparent overlay to further obscure
        g = blurred.createGraphics();
        g.setColor(new java.awt.Color(0, 0, 0, 100)); // Semi-transparent black
        g.fillRect(0, 0, width, height);

        // Add warning text
        g.setColor(java.awt.Color.WHITE);
        g.setFont(new java.awt.Font("Arial", java.awt.Font.BOLD, 24));
        String warningText = "SENSITIVE CONTENT HIDDEN";
        java.awt.FontMetrics fm = g.getFontMetrics();
        int textWidth = fm.stringWidth(warningText);
        int textX = (width - textWidth) / 2;
        int textY = height / 2;
        g.drawString(warningText, textX, textY);
        g.dispose();

        return blurred;
    }

    /**
     * Simple box blur implementation.
     */
    private BufferedImage boxBlur(BufferedImage image, int radius)
    {
        int width = image.getWidth();
        int height = image.getHeight();
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        int[] pixels = new int[width * height];
        int[] result = new int[width * height];
        image.getRGB(0, 0, width, height, pixels, 0, width);

        // Horizontal pass
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

        // Vertical pass
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

    /**
     * Starts continuous recording with an in-memory circular buffer.
     */
    public void startRecording()
    {
        if (isRecording.getAndSet(true))
        {
            log.debug("Recording already active");
            return;
        }

        // Clear the buffer
        synchronized (bufferLock)
        {
            for (int i = 0; i < MAX_FRAMES; i++)
            {
                jpegBuffer[i] = null;
                timestampBuffer[i] = 0;
            }
            writeIndex.set(0);
            frameCount.set(0);
        }

        log.info("Video recording started with in-memory circular buffer");

        // Capture at the quality setting's FPS and JPEG quality
        VideoQuality quality = config.videoQuality();
        currentCaptureFps = quality.getFps() > 0 ? quality.getFps() : 30;
        currentJpegQuality = quality.getJpegQuality() > 0 ? quality.getJpegQuality() : 0.5f;
        long frameIntervalMs = 1000L / currentCaptureFps;
        log.info("Video capture started at {} FPS, {}% JPEG quality", currentCaptureFps, (int)(currentJpegQuality * 100));

        captureTask = scheduler.scheduleAtFixedRate(
            this::captureFrame,
            0,
            frameIntervalMs,
            TimeUnit.MILLISECONDS
        );
    }

    /**
     * Stops recording and cleans up resources.
     */
    public void stopRecording()
    {
        if (!isRecording.getAndSet(false))
        {
            return;
        }

        log.info("Stopping video recording");

        if (captureTask != null)
        {
            captureTask.cancel(false);
        }

        // Clear the buffer to free memory
        synchronized (bufferLock)
        {
            for (int i = 0; i < MAX_FRAMES; i++)
            {
                jpegBuffer[i] = null;
                timestampBuffer[i] = 0;
            }
            writeIndex.set(0);
            frameCount.set(0);
        }

        currentCaptureFps = 0;
        log.debug("Cleared in-memory frame buffer");
    }

    /**
     * Updates the capture settings if the quality setting has changed.
     * Call this periodically or when quality settings change.
     */
    public void updateCaptureRateIfNeeded()
    {
        if (!isRecording.get() || isCapturingPostEvent.get())
        {
            return;
        }

        VideoQuality quality = config.videoQuality();
        int targetFps = quality.getFps() > 0 ? quality.getFps() : 30;
        float targetJpegQuality = quality.getJpegQuality() > 0 ? quality.getJpegQuality() : 0.5f;

        // Update JPEG quality if changed (takes effect on next frame)
        if (targetJpegQuality != currentJpegQuality)
        {
            log.info("Quality setting changed, updating JPEG quality from {}% to {}%",
                (int)(currentJpegQuality * 100), (int)(targetJpegQuality * 100));
            currentJpegQuality = targetJpegQuality;
        }

        // Update FPS if changed (requires restarting capture task)
        if (targetFps != currentCaptureFps)
        {
            log.info("Quality setting changed, updating capture rate from {} to {} FPS", currentCaptureFps, targetFps);

            // Cancel current capture task
            if (captureTask != null)
            {
                captureTask.cancel(false);
            }

            // Start new capture task at new rate
            currentCaptureFps = targetFps;
            long frameIntervalMs = 1000L / currentCaptureFps;

            captureTask = scheduler.scheduleAtFixedRate(
                this::captureFrame,
                0,
                frameIntervalMs,
                TimeUnit.MILLISECONDS
            );
        }
    }

    // Default post-event duration in milliseconds
    private static final int DEFAULT_POST_EVENT_MS = 2000;

    /**
     * Triggers a video/screenshot capture for an event based on configured quality settings.
     * Uses default 2-second post-event duration.
     *
     * @param callback Called when capture is complete with base64-encoded screenshot and optionally MP4 video
     */
    public void captureEventVideo(VideoCallback callback)
    {
        captureEventVideo(callback, null, DEFAULT_POST_EVENT_MS);
    }

    /**
     * Triggers a video/screenshot capture for an event based on configured quality settings.
     * Uses default 2-second post-event duration.
     *
     * @param callback Called when capture is complete with base64-encoded screenshot and optionally MP4 video
     * @param onEncodingStart Optional callback fired when encoding begins (after recording stops)
     */
    public void captureEventVideo(VideoCallback callback, Runnable onEncodingStart)
    {
        captureEventVideo(callback, onEncodingStart, DEFAULT_POST_EVENT_MS);
    }

    /**
     * Triggers a video/screenshot capture for an event based on configured quality settings.
     *
     * @param callback Called when capture is complete with base64-encoded screenshot and optionally MP4 video
     * @param onEncodingStart Optional callback fired when encoding begins (after recording stops)
     * @param postEventMs Duration in milliseconds to continue recording after the event (default 2000ms)
     */
    public void captureEventVideo(VideoCallback callback, Runnable onEncodingStart, int postEventMs)
    {
        if (!isRecording.get())
        {
            log.warn("Cannot capture event: recording not active");
            callback.onComplete(null, null);
            return;
        }

        VideoQuality quality = config.videoQuality();

        // Check if screenshot-only mode
        if (quality == VideoQuality.SCREENSHOT_ONLY)
        {
            log.info("Event triggered, capturing screenshot only (Screenshot Only mode)");
            if (onEncodingStart != null)
            {
                onEncodingStart.run();
            }
            captureScreenshotOnly(callback);
            return;
        }

        // Video capture mode
        int durationMs = quality.getDurationMs();
        int bufferMs = durationMs - postEventMs;

        // Ensure buffer is at least 1 second
        if (bufferMs < 1000)
        {
            bufferMs = 1000;
        }

        log.info("Event triggered, capturing {}-second video ({}s buffer + {}s post-event) at {} FPS",
            durationMs / 1000, bufferMs / 1000, postEventMs / 1000, quality.getFps());

        isCapturingPostEvent.set(true);

        // Calculate the time window for the video
        final long captureStartTime = System.currentTimeMillis();
        final long videoStartTime = captureStartTime - bufferMs;
        final long videoEndTime = captureStartTime + postEventMs;
        final int finalPostEventMs = postEventMs;

        // Schedule task to finalize capture after post-event duration
        scheduler.schedule(() -> {
            isCapturingPostEvent.set(false);
            // Notify caller that encoding is about to start
            if (onEncodingStart != null)
            {
                onEncodingStart.run();
            }
            finalizeCapture(callback, videoStartTime, videoEndTime);
        }, finalPostEventMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Captures a single screenshot without video.
     * Useful for events where only a screenshot is needed (e.g., clue scrolls).
     * Applies blur if sensitive content (like bank PIN) is visible.
     *
     * @param callback Called when screenshot capture is complete
     */
    public void captureScreenshotOnly(VideoCallback callback)
    {
        // Check for sensitive content on the client thread
        final boolean shouldBlur = isSensitiveContentVisible();

        drawManager.requestNextFrameListener(image -> {
            asyncWriter.submit(() -> {
                try
                {
                    BufferedImage screenshot = ImageUtil.bufferedImageFromImage(image);

                    // Apply blur if sensitive content was detected
                    if (shouldBlur)
                    {
                        screenshot = applyHeavyBlur(screenshot);
                        log.info("Screenshot blurred due to sensitive content");
                    }

                    String screenshotBase64 = imageToBase64(screenshot);
                    log.info("Screenshot captured successfully");
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

    /**
     * Captures a single frame and stores it in the circular buffer.
     */
    private void captureFrame()
    {
        // Backpressure: skip frame if too many pending encodes
        if (pendingEncodes.get() >= MAX_PENDING_ENCODES)
        {
            return;
        }

        long currentTime = System.currentTimeMillis();
        long lastTime = lastFrameTime.get();

        // Update last frame time immediately to prevent duplicate captures
        if (!lastFrameTime.compareAndSet(lastTime, currentTime))
        {
            return;
        }

        // Check for sensitive content BEFORE requesting frame
        final boolean shouldBlur = isSensitiveContentVisible();

        if (shouldBlur)
        {
            log.debug("Sensitive content detected, frame will be blurred");
        }

        // Increment pending count before requesting frame
        pendingEncodes.incrementAndGet();

        // Request next frame from DrawManager
        drawManager.requestNextFrameListener(image -> {
            try
            {
                // Encode frame to JPEG asynchronously
                asyncWriter.submit(() -> {
                    try
                    {
                        encodeAndStoreFrame(image, currentTime, shouldBlur);
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
            catch (Exception e)
            {
                log.error("Failed to queue frame for encoding", e);
                pendingEncodes.decrementAndGet();
            }
        });
    }

    // Maximum resolution cap (1080p) to normalize upload sizes across different displays
    private static final int MAX_WIDTH = 1920;
    private static final int MAX_HEIGHT = 1080;

    /**
     * Encodes a captured frame to JPEG and stores it in the circular buffer.
     * Uses simple ImageIO.write() to avoid memory leaks from ImageWriter/ImageOutputStream.
     * Caps resolution at 1080p to normalize file sizes across different displays (e.g., Retina).
     *
     * @param image The image to encode
     * @param timestamp The timestamp for the frame
     * @param shouldBlur Whether to apply blur for sensitive content
     */
    private void encodeAndStoreFrame(Image image, long timestamp, boolean shouldBlur)
    {
        try
        {
            if (image == null)
            {
                return;
            }

            // Get dimensions directly from the image
            int sourceWidth = image.getWidth(null);
            int sourceHeight = image.getHeight(null);

            if (sourceWidth <= 0 || sourceHeight <= 0)
            {
                return;
            }

            // Calculate target dimensions (cap at 1080p while maintaining aspect ratio)
            int targetWidth = sourceWidth;
            int targetHeight = sourceHeight;

            if (sourceWidth > MAX_WIDTH || sourceHeight > MAX_HEIGHT)
            {
                double widthRatio = (double) MAX_WIDTH / sourceWidth;
                double heightRatio = (double) MAX_HEIGHT / sourceHeight;
                double scaleFactor = Math.min(widthRatio, heightRatio);

                targetWidth = (int) (sourceWidth * scaleFactor);
                targetHeight = (int) (sourceHeight * scaleFactor);

                log.debug("Scaling frame from {}x{} to {}x{} (factor: {})",
                    sourceWidth, sourceHeight, targetWidth, targetHeight, scaleFactor);
            }

            // Create BufferedImage at target resolution (capped at 1080p)
            BufferedImage bufferedImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = bufferedImage.createGraphics();
            // Use bilinear interpolation for better quality when scaling
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, targetWidth, targetHeight, null);
            g.dispose();

            // Apply blur if sensitive content was detected (rare case)
            if (shouldBlur)
            {
                bufferedImage = applyHeavyBlur(bufferedImage);
            }

            // Encode to JPEG with lower quality for smaller files
            ByteArrayOutputStream baos = new ByteArrayOutputStream(30000);

            javax.imageio.ImageWriter jpegWriter = ImageIO.getImageWritersByFormatName("jpg").next();
            javax.imageio.ImageWriteParam param = jpegWriter.getDefaultWriteParam();
            param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(currentJpegQuality);

            javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(baos);
            jpegWriter.setOutput(ios);
            jpegWriter.write(null, new javax.imageio.IIOImage(bufferedImage, null, null), param);
            jpegWriter.dispose();
            ios.close();

            byte[] jpegBytes = baos.toByteArray();

            // Store in circular buffer
            int currentCount;
            synchronized (bufferLock)
            {
                int idx = writeIndex.getAndIncrement() % MAX_FRAMES;
                jpegBuffer[idx] = jpegBytes;
                timestampBuffer[idx] = timestamp;

                currentCount = frameCount.get();
                if (currentCount < MAX_FRAMES)
                {
                    frameCount.incrementAndGet();
                }
            }

        }
        catch (IOException e)
        {
            log.error("Failed to encode frame at timestamp {}", timestamp, e);
        }
    }

    /**
     * Finalizes the video capture by taking a screenshot and streaming frames to cloud storage.
     *
     * @param callback The callback to invoke when complete
     * @param videoStartTime The start timestamp for the video (frames before this are excluded)
     * @param videoEndTime The end timestamp for the video (frames after this are excluded)
     */
    private void finalizeCapture(VideoCallback callback, long videoStartTime, long videoEndTime)
    {
        log.info("Finalizing video capture for time window: {} to {} ({}ms duration)",
            videoStartTime, videoEndTime, videoEndTime - videoStartTime);

        // Check for sensitive content before capturing final screenshot
        final boolean shouldBlur = isSensitiveContentVisible();

        // Capture one more frame as the screenshot
        drawManager.requestNextFrameListener(image -> {
            asyncWriter.submit(() -> {
                try
                {
                    // Convert screenshot to base64 PNG
                    BufferedImage screenshot = ImageUtil.bufferedImageFromImage(image);

                    if (shouldBlur)
                    {
                        screenshot = applyHeavyBlur(screenshot);
                        log.info("Final screenshot blurred due to sensitive content");
                    }

                    String screenshotBase64 = imageToBase64(screenshot);

                    // Snapshot frames and prepare for upload
                    UploadTask uploadTask = prepareUpload(videoStartTime, videoEndTime);

                    if (uploadTask != null)
                    {
                        log.info("Uploading {} frames to {}", uploadTask.frames.size(), uploadTask.key);

                        // Store screenshot for use after upload
                        final String finalScreenshot = screenshotBase64;

                        // Upload async, but only send callback after upload completes
                        asyncWriter.submit(() -> {
                            boolean success = uploadFramesToBackblaze(uploadTask.uploadUrl, uploadTask.frames, uploadTask.fps);
                            if (success)
                            {
                                log.info("Upload complete - sending callback with key {}", uploadTask.key);
                                callback.onComplete(finalScreenshot, uploadTask.key);
                            }
                            else
                            {
                                log.error("Upload failed - sending callback without video");
                                callback.onComplete(finalScreenshot, null);
                            }
                        });
                    }
                    else
                    {
                        log.warn("Failed to prepare upload - returning screenshot only");
                        callback.onComplete(screenshotBase64, null);
                    }
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
     * Container for upload task data.
     */
    private static class UploadTask
    {
        String uploadUrl;
        String key;
        List<byte[]> frames;
        int fps;
    }

    /**
     * Prepares an upload by snapshotting frames and getting a presigned URL.
     * This is fast - the actual upload happens asynchronously.
     *
     * @param videoStartTime The start timestamp for the video
     * @param videoEndTime The end timestamp for the video
     * @return UploadTask with all data needed for async upload, or null on failure
     */
    private UploadTask prepareUpload(long videoStartTime, long videoEndTime)
    {
        List<byte[]> clipFrames = new ArrayList<>();

        // Snapshot frames from the circular buffer (fast)
        synchronized (bufferLock)
        {
            int count = frameCount.get();
            int currentWriteIdx = writeIndex.get() % MAX_FRAMES;

            for (int i = 0; i < count; i++)
            {
                int idx = (currentWriteIdx - count + i + MAX_FRAMES) % MAX_FRAMES;
                long timestamp = timestampBuffer[idx];
                byte[] frame = jpegBuffer[idx];

                if (timestamp >= videoStartTime && timestamp <= videoEndTime && frame != null)
                {
                    clipFrames.add(frame);
                }
            }
        }

        if (clipFrames.isEmpty())
        {
            log.warn("No frames available for clip in time window {} to {}", videoStartTime, videoEndTime);
            return null;
        }

        log.info("Snapshotted {} frames for clip", clipFrames.size());

        // Get presigned URL (fast API call) - FPS is encoded in the filename
        int fps = config.videoQuality().getFps();
        PresignedUrlResponse presignedUrl = getPresignedUploadUrl(fps);
        if (presignedUrl == null)
        {
            log.error("Failed to get presigned upload URL");
            return null;
        }

        log.info("Got presigned URL for async upload: {}", presignedUrl.key);

        UploadTask task = new UploadTask();
        task.uploadUrl = presignedUrl.uploadUrl;
        task.key = presignedUrl.key;
        task.frames = clipFrames;
        task.fps = fps;

        return task;
    }

    /**
     * Response from the presigned upload URL endpoint.
     */
    private static class PresignedUrlResponse
    {
        String uploadUrl;
        String key;
    }

    /**
     * Gets a presigned upload URL from the Rails API.
     *
     * @param fps The frames per second (encoded in filename for FFmpeg)
     * @return The presigned URL response or null on failure
     */
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
                if (!response.isSuccessful())
                {
                    log.error("Failed to get presigned URL: {} - {}", response.code(), response.message());
                    return null;
                }

                String body = response.body().string();
                JsonObject json = gson.fromJson(body, JsonObject.class);

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

    /**
     * Uploads JPEG frames directly to Backblaze as MJPEG format.
     * MJPEG is simply concatenated JPEG frames - FFmpeg reads this natively.
     *
     * @param uploadUrl The presigned Backblaze upload URL
     * @param frames The list of JPEG frame bytes
     * @param fps The frames per second for the video (encoded in filename by Rails)
     * @return true if upload was successful
     */
    private boolean uploadFramesToBackblaze(String uploadUrl, List<byte[]> frames, int fps)
    {
        try
        {
            // MJPEG format: just concatenate all JPEG frames
            // FFmpeg can read this directly with -f mjpeg
            ByteArrayOutputStream mjpegBuffer = new ByteArrayOutputStream();

            for (byte[] frame : frames)
            {
                mjpegBuffer.write(frame);
            }

            byte[] mjpegBytes = mjpegBuffer.toByteArray();
            log.info("Created MJPEG stream: {} bytes ({} frames)", mjpegBytes.length, frames.size());

            // Upload directly to Backblaze using presigned URL
            log.info("Starting direct upload to Backblaze ({} bytes)...", mjpegBytes.length);

            RequestBody body = RequestBody.create(MediaType.parse("application/octet-stream"), mjpegBytes);

            // Presigned URL already contains auth - just need matching Content-Type
            Request request = new Request.Builder()
                .url(uploadUrl)
                .addHeader("Content-Type", "application/octet-stream")
                .put(body)
                .build();

            // Use uploadClient with longer timeouts for large uploads
            Response response = uploadClient.newCall(request).execute();
            try
            {
                String responseBody = response.body() != null ? response.body().string() : "null";
                if (response.isSuccessful())
                {
                    log.info("Successfully uploaded {} bytes directly to Backblaze", mjpegBytes.length);
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
            log.error("Error uploading frames to Backblaze", e);
            return false;
        }
    }

    /**
     * Converts a BufferedImage to a base64-encoded PNG string.
     */
    private String imageToBase64(BufferedImage image) throws IOException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", baos);
        byte[] imageBytes = baos.toByteArray();
        return Base64.getEncoder().encodeToString(imageBytes);
    }

    /**
     * Callback interface for video capture completion.
     */
    public interface VideoCallback
    {
        /**
         * Called when video capture is complete.
         *
         * @param screenshotBase64 Base64-encoded PNG screenshot
         * @param videoKey The storage key for the uploaded video frames, or null if not available
         */
        void onComplete(String screenshotBase64, String videoKey);
    }
}
