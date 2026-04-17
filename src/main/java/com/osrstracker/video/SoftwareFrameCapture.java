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

import net.runelite.api.BufferProvider;
import net.runelite.api.Client;
import net.runelite.client.ui.DrawManager;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CPU-rasterizer fallback for {@link AsyncFrameCapture}. Reads ARGB pixels
 * directly from {@link Client#getBufferProvider()} when no GL context is
 * available (e.g. the GPU plugin is disabled). Flips Y on conversion so the
 * encoder sees the same bottom-up RGBA layout as the PBO path.
 */
public class SoftwareFrameCapture
{
    private final DrawManager drawManager;
    private final Client client;
    private final VideoRecorder recorder;

    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
    private Runnable everyFrameListener;
    private long nextCaptureTimeNs = 0;

    public SoftwareFrameCapture(DrawManager drawManager, Client client, VideoRecorder recorder)
    {
        this.drawManager = drawManager;
        this.client = client;
        this.recorder = recorder;
    }

    public void start()
    {
        if (everyFrameListener != null)
        {
            return;
        }
        shutdownRequested.set(false);
        nextCaptureTimeNs = 0;
        everyFrameListener = this::onFrame;
        drawManager.registerEveryFrameListener(everyFrameListener);
    }

    public void stop()
    {
        shutdownRequested.set(true);
        if (everyFrameListener != null)
        {
            drawManager.unregisterEveryFrameListener(everyFrameListener);
            everyFrameListener = null;
        }
    }

    private void onFrame()
    {
        if (shutdownRequested.get() || !recorder.isCurrentlyRecording())
        {
            return;
        }

        int captureFps = recorder.getCaptureFps();
        if (captureFps <= 0)
        {
            return;
        }

        long nowNs = System.nanoTime();
        long frameIntervalNs = 1_000_000_000L / captureFps;
        if (nextCaptureTimeNs == 0)
        {
            nextCaptureTimeNs = nowNs;
        }
        if (nowNs < nextCaptureTimeNs)
        {
            return;
        }
        nextCaptureTimeNs += frameIntervalNs;
        if (nowNs - nextCaptureTimeNs > frameIntervalNs)
        {
            nextCaptureTimeNs = nowNs + frameIntervalNs;
        }

        if (!recorder.canAcceptFrame())
        {
            return;
        }

        BufferProvider bp = client.getBufferProvider();
        int[] argb = bp.getPixels();
        int width = bp.getWidth();
        int height = bp.getHeight();
        if (argb == null || width <= 0 || height <= 0)
        {
            return;
        }

        byte[] rgba = new byte[width * height * 4];
        for (int y = 0; y < height; y++)
        {
            int srcRow = y * width;
            int dstRow = (height - 1 - y) * width * 4;
            for (int x = 0; x < width; x++)
            {
                int pixel = argb[srcRow + x];
                int dst = dstRow + (x << 2);
                rgba[dst]     = (byte) ((pixel >> 16) & 0xFF);
                rgba[dst + 1] = (byte) ((pixel >> 8) & 0xFF);
                rgba[dst + 2] = (byte) (pixel & 0xFF);
                rgba[dst + 3] = (byte) 0xFF;
            }
        }

        recorder.submitCapturedFrame(ByteBuffer.wrap(rgba), width, height);
    }
}
