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

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Strategy interface for video frame encoding and storage.
 * Implementations handle frame encoding (JPEG, H.264, etc.) and buffering.
 * VideoRecorder delegates frame storage and clip extraction to this interface.
 */
public interface VideoEncoder
{
    /**
     * Initializes the encoder for recording at the given settings.
     *
     * @param fps target frames per second
     * @param quality encoding quality (0.0-1.0, interpretation is encoder-specific)
     */
    void start(int fps, float quality);

    /**
     * Stops recording and releases resources. Buffer contents are cleared.
     */
    void stop();

    /**
     * Submits a raw RGBA frame for encoding and storage in the buffer.
     * Called from a background thread (asyncWriter). Must be thread-safe.
     *
     * @param rgbaPixels raw RGBA pixel data (bottom-up, OpenGL convention)
     * @param width frame width in pixels
     * @param height frame height in pixels
     * @param timestamp capture timestamp in milliseconds
     * @param needsBlur whether this frame contains sensitive content requiring blur
     */
    void submitFrame(ByteBuffer rgbaPixels, int width, int height, long timestamp, boolean needsBlur);

    /**
     * Extracts frames from the buffer for the given time window and prepares
     * them for upload. Returns null if no frames are available.
     *
     * @param startTime start of clip window (milliseconds)
     * @param endTime end of clip window (milliseconds)
     * @return clip data ready for upload, or null if no frames available
     */
    ClipData finalizeClip(long startTime, long endTime);

    /**
     * Clears the buffer without stopping recording. Used when switching quality settings.
     */
    void reset();

    /**
     * Returns a human-readable name for this encoder (e.g., "mjpeg", "vulkan-h264").
     */
    String encoderName();

    /**
     * Encapsulates clip data ready for upload.
     */
    class ClipData
    {
        private final List<byte[]> frames;
        private final String contentType;
        private final long totalSize;

        public ClipData(List<byte[]> frames, String contentType, long totalSize)
        {
            this.frames = frames;
            this.contentType = contentType;
            this.totalSize = totalSize;
        }

        /** Individual encoded frames (JPEG for MJPEG, or single MP4 byte array for H.264) */
        public List<byte[]> getFrames() { return frames; }

        /** MIME type for upload (application/octet-stream for MJPEG, video/mp4 for H.264) */
        public String getContentType() { return contentType; }

        /** Total byte size of all frames combined */
        public long getTotalSize() { return totalSize; }
    }
}
