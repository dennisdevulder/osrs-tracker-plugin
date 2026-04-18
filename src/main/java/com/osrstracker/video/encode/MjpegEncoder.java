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
package com.osrstracker.video.encode;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MJPEG encoder: stores JPEG-compressed frames in a circular buffer.
 * On finalize, extracts frames for a time window and returns concatenated JPEG data.
 * This is the original encoding path extracted from VideoRecorder.
 */
@Slf4j
public class MjpegEncoder implements VideoEncoder
{
    // Circular buffer configuration
    private static final int MAX_FRAMES = 300;

    // Maximum resolution cap (720p). Trades fullscreen sharpness for a
    // ~40% drop in per-frame JPEG encode time, which keeps the async
    // writer inside the 33ms/frame budget on dynamic 30fps content.
    private static final int MAX_WIDTH = 1280;
    private static final int MAX_HEIGHT = 720;

    // Blur kernel radius
    private static final int BLUR_RADIUS = 15;

    // Circular buffer storage
    private final byte[][] jpegBuffer = new byte[MAX_FRAMES][];
    private final long[] timestampBuffer = new long[MAX_FRAMES];
    private final boolean[] needsBlurBuffer = new boolean[MAX_FRAMES];
    private final AtomicInteger writeIndex = new AtomicInteger(0);
    private final AtomicInteger frameCount = new AtomicInteger(0);
    private final Object bufferLock = new Object();

    private volatile float currentJpegQuality = 0.5f;

    @Override
    public void start(int fps, float quality)
    {
        currentJpegQuality = quality > 0 ? quality : 0.5f;
        reset();
    }

    @Override
    public void stop()
    {
        reset();
        currentJpegQuality = 0;
    }

    @Override
    public void submitFrame(ByteBuffer rgbaPixels, int width, int height, long timestamp, boolean needsBlur)
    {
        try
        {
            if (rgbaPixels == null || width <= 0 || height <= 0)
            {
                return;
            }

            BufferedImage bufferedImage = convertRgbaToBufferedImage(rgbaPixels, width, height);
            bufferedImage = scaleToMaxResolution(bufferedImage, width, height);
            byte[] jpegBytes = encodeToJpeg(bufferedImage);
            storeInBuffer(jpegBytes, timestamp, needsBlur);
        }
        catch (IOException e)
        {
            log.error("Failed to encode frame at timestamp {}", timestamp, e);
        }
    }

    @Override
    public ClipData finalizeClip(long startTime, long endTime)
    {
        List<TimestampedFrame> frames = snapshot(startTime, endTime);
        if (frames.isEmpty())
        {
            return null;
        }
        List<byte[]> clipFrames = new ArrayList<>(frames.size());
        long totalSize = 0;
        for (TimestampedFrame f : frames)
        {
            clipFrames.add(f.jpeg);
            totalSize += f.jpeg.length;
        }
        return new ClipData(clipFrames, "application/octet-stream", totalSize);
    }

    public List<TimestampedFrame> snapshot(long startTime, long endTime)
    {
        List<TimestampedFrame> framesToProcess = new ArrayList<>();

        synchronized (bufferLock)
        {
            int count = frameCount.get();
            int currentWriteIdx = writeIndex.get() % MAX_FRAMES;

            for (int i = 0; i < count; i++)
            {
                int idx = (currentWriteIdx - count + i + MAX_FRAMES) % MAX_FRAMES;
                long timestamp = timestampBuffer[idx];
                byte[] frame = jpegBuffer[idx];
                boolean blur = needsBlurBuffer[idx];

                if (timestamp >= startTime && timestamp <= endTime && frame != null)
                {
                    framesToProcess.add(new TimestampedFrame(frame, timestamp, blur));
                }
            }
        }

        List<TimestampedFrame> out = new ArrayList<>(framesToProcess.size());
        for (TimestampedFrame tf : framesToProcess)
        {
            byte[] frame = tf.jpeg;
            if (tf.needsBlur)
            {
                try
                {
                    BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(frame));
                    if (decoded != null)
                    {
                        BufferedImage blurred = applyHeavyBlur(decoded);
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        ImageIO.write(blurred, "jpg", baos);
                        frame = baos.toByteArray();
                    }
                }
                catch (IOException e)
                {
                    log.error("Failed to apply deferred blur to frame", e);
                }
            }
            out.add(new TimestampedFrame(frame, tf.timestampMs, false));
        }
        return out;
    }

    public static final class TimestampedFrame
    {
        public final byte[] jpeg;
        public final long timestampMs;
        public final boolean needsBlur;

        public TimestampedFrame(byte[] jpeg, long timestampMs, boolean needsBlur)
        {
            this.jpeg = jpeg;
            this.timestampMs = timestampMs;
            this.needsBlur = needsBlur;
        }
    }

    @Override
    public void reset()
    {
        synchronized (bufferLock)
        {
            for (int i = 0; i < MAX_FRAMES; i++)
            {
                jpegBuffer[i] = null;
                timestampBuffer[i] = 0;
                needsBlurBuffer[i] = false;
            }
            writeIndex.set(0);
            frameCount.set(0);
        }
    }

    @Override
    public String encoderName()
    {
        return "mjpeg";
    }

    // ---- Private encoding methods (extracted from VideoRecorder) ----

    private BufferedImage convertRgbaToBufferedImage(ByteBuffer rgbaPixels, int width, int height)
    {
        int[] argb = new int[width * height];
        int rowBytes = width * 4;

        for (int y = 0; y < height; y++)
        {
            int srcRow = (height - 1 - y) * rowBytes;
            int dstRow = y * width;
            for (int x = 0; x < width; x++)
            {
                int srcIdx = srcRow + (x << 2);
                int r = rgbaPixels.get(srcIdx) & 0xFF;
                int g = rgbaPixels.get(srcIdx + 1) & 0xFF;
                int b = rgbaPixels.get(srcIdx + 2) & 0xFF;
                argb[dstRow + x] = (r << 16) | (g << 8) | b;
            }
        }

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, width, height, argb, 0, width);
        return image;
    }

    private BufferedImage scaleToMaxResolution(BufferedImage source, int sourceWidth, int sourceHeight)
    {
        int targetWidth = sourceWidth;
        int targetHeight = sourceHeight;

        if (sourceWidth > MAX_WIDTH || sourceHeight > MAX_HEIGHT)
        {
            double scaleFactor = Math.min(
                (double) MAX_WIDTH / sourceWidth,
                (double) MAX_HEIGHT / sourceHeight
            );
            targetWidth = (int) (sourceWidth * scaleFactor);
            targetHeight = (int) (sourceHeight * scaleFactor);
        }
        else if (source.getWidth() == sourceWidth && source.getHeight() == sourceHeight)
        {
            return source;
        }

        BufferedImage result = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        g.dispose();
        return result;
    }

    private byte[] encodeToJpeg(BufferedImage image) throws IOException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(30000);

        javax.imageio.ImageWriter jpegWriter = ImageIO.getImageWritersByFormatName("jpg").next();
        javax.imageio.ImageWriteParam param = jpegWriter.getDefaultWriteParam();
        param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(currentJpegQuality);

        javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(baos);
        jpegWriter.setOutput(ios);
        jpegWriter.write(null, new javax.imageio.IIOImage(image, null, null), param);
        jpegWriter.dispose();
        ios.close();

        return baos.toByteArray();
    }

    private void storeInBuffer(byte[] jpegBytes, long timestamp, boolean shouldBlur)
    {
        synchronized (bufferLock)
        {
            int idx = writeIndex.getAndIncrement() % MAX_FRAMES;
            jpegBuffer[idx] = jpegBytes;
            timestampBuffer[idx] = timestamp;
            needsBlurBuffer[idx] = shouldBlur;

            if (frameCount.get() < MAX_FRAMES)
            {
                frameCount.incrementAndGet();
            }
        }
    }

    // ---- Blur methods (extracted from VideoRecorder) ----

    private BufferedImage applyHeavyBlur(BufferedImage image)
    {
        int width = image.getWidth();
        int height = image.getHeight();

        BufferedImage blurred = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = blurred.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();

        for (int pass = 0; pass < 3; pass++)
        {
            blurred = boxBlur(blurred, BLUR_RADIUS);
        }

        g = blurred.createGraphics();
        g.setColor(new Color(0, 0, 0, 100));
        g.fillRect(0, 0, width, height);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 24));
        FontMetrics fm = g.getFontMetrics();
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

}
