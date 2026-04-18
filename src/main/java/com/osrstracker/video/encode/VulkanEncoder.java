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
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * H.264 video encoder backed by {@code VK_KHR_video_encode_h264}.
 *
 * Frames captured on the game thread are buffered as JPEGs in an
 * {@link MjpegEncoder} ring; the GPU encode queue is exercised only during
 * {@link #finalizeClip}, which burst-decodes the ring and re-encodes to an
 * H.264 Annex B bitstream.
 */
@Slf4j
public class VulkanEncoder implements VideoEncoder, AutoCloseable
{
    private final VulkanDevice vulkanDevice;
    private final VulkanCapabilities caps;

    private final MjpegEncoder jpegBuffer = new MjpegEncoder();

    // Destroyed once at shutdown.
    private final Disposables persistent = new Disposables();

    private H264SessionConfig sessionConfig;
    // Per-clip GPU resources; rebuilt on dimension change.
    private EncodeResources resources;
    private FrameEncoder frameEncoder;

    private long commandPool = VK_NULL_HANDLE;
    private final VkCommandBuffer[] commandBuffers = new VkCommandBuffer[EncodeResources.RING_DEPTH];
    private final long[] encodeFences = new long[EncodeResources.RING_DEPTH];
    // Video-encode queue has no TRANSFER bit on AMD/NVIDIA/Intel, so the NV12
    // upload runs on graphics and transfers ownership via QFOT.
    private long gfxCommandPool = VK_NULL_HANDLE;
    private final VkCommandBuffer[] gfxCommandBuffers = new VkCommandBuffer[EncodeResources.RING_DEPTH];
    private final long[] uploadSemaphores = new long[EncodeResources.RING_DEPTH];

    private static final long FENCE_TIMEOUT_NS = 5_000_000_000L;

    private int encodedWidth = 0;
    private int encodedHeight = 0;
    private int sourceWidth = 0;
    private int sourceHeight = 0;
    /** Source FPS from {@link #start(int, float)} or {@link #burstEncode(List, int)}.
     *  Drives IDR period and rate control. {@code -1} if not set yet. */
    private int sourceFps = -1;
    private boolean vulkanInitialized = false;
    private boolean closed = false;

    public VulkanEncoder(VulkanDevice vulkanDevice, VulkanCapabilities caps)
    {
        this.vulkanDevice = vulkanDevice;
        this.caps = caps;
    }

    @Override
    public void start(int fps, float quality)
    {
        if (fps <= 0) throw new IllegalArgumentException("fps must be positive, got " + fps);
        this.sourceFps = fps;
        jpegBuffer.start(fps, quality);
    }

    @Override
    public void stop()
    {
        jpegBuffer.stop();
        destroyVulkanResources();
        vulkanDevice.close();
    }

    @Override
    public void submitFrame(ByteBuffer rgbaPixels, int width, int height, long timestamp, boolean needsBlur)
    {
        jpegBuffer.submitFrame(rgbaPixels, width, height, timestamp, needsBlur);
    }

    @Override
    public ClipData finalizeClip(long startTime, long endTime)
    {
        List<MjpegEncoder.TimestampedFrame> frames = jpegBuffer.snapshot(startTime, endTime);
        if (frames.isEmpty())
        {
            return null;
        }

        List<byte[]> jpegs = new ArrayList<>(frames.size());
        long[] timestamps = new long[frames.size()];
        for (int i = 0; i < frames.size(); i++)
        {
            jpegs.add(frames.get(i).jpeg);
            timestamps[i] = frames.get(i).timestampMs;
        }

        try
        {
            byte[] h264Bitstream = burstEncode(jpegs);
            if (h264Bitstream == null || h264Bitstream.length == 0)
            {
                log.warn("Vulkan burst encode returned empty, falling back to MJPEG");
                return mjpegFallback(jpegs);
            }
            byte[] mp4 = LocalMp4Writer.toBytes(h264Bitstream, getDriverSpsPps(),
                sourceWidth, sourceHeight, sourceFps, timestamps);
            return new ClipData(Collections.singletonList(mp4), "video/mp4", mp4.length);
        }
        catch (Exception e)
        {
            log.error("Vulkan burst encode threw, falling back to MJPEG", e);
            return mjpegFallback(jpegs);
        }
    }

    private ClipData mjpegFallback(List<byte[]> jpegs)
    {
        long total = jpegs.stream().mapToLong(b -> b.length).sum();
        return new ClipData(jpegs, "application/octet-stream", total);
    }

    @Override
    public void reset()
    {
        jpegBuffer.reset();
    }

    @Override
    public String encoderName()
    {
        return "vulkan-h264";
    }

    /**
     * Returns the driver-emitted SPS+PPS NAL bodies from the current session,
     * or {@code null} if no session exists yet. NAL bodies are returned
     * without Annex B start codes; {@link AnnexBWriter#splitNalus} splits them.
     */
    byte[] getDriverSpsPps()
    {
        if (sessionConfig == null) return null;
        return sessionConfig.fetchEncodedSpsPps(0, 0, true, true);
    }

    /**
     * Burst-encode with an explicit source FPS, for callers that bypass
     * {@link #start(int, float)} (e.g. the offline MJPEG-to-MP4 tool).
     */
    byte[] burstEncode(List<byte[]> jpegFrames, int sourceFps)
    {
        if (sourceFps <= 0) throw new IllegalArgumentException("sourceFps must be positive");
        int prev = this.sourceFps;
        this.sourceFps = sourceFps;
        try
        {
            return burstEncode(jpegFrames);
        }
        finally
        {
            this.sourceFps = prev;
        }
    }

    // Package-private so MjpegToMp4Tool can drive the encoder standalone.
    byte[] burstEncode(List<byte[]> jpegFrames)
    {
        java.awt.image.BufferedImage firstFrame = decodeJpeg(jpegFrames.get(0));
        if (firstFrame == null) return null;

        int width = firstFrame.getWidth();
        int height = firstFrame.getHeight();
        sourceWidth = width;
        sourceHeight = height;

        // NV12 + H.264 MB alignment require multiples of 16; SPS frame_cropping
        // hides the padding at decode time.
        int paddedWidth = (width + 15) & ~15;
        int paddedHeight = (height + 15) & ~15;

        if (!vulkanInitialized || paddedWidth != encodedWidth || paddedHeight != encodedHeight)
        {
            destroyEncodeResources();
            initializeVulkan(paddedWidth, paddedHeight, jpegFrames.size());
            encodedWidth = paddedWidth;
            encodedHeight = paddedHeight;
        }

        if (sourceFps <= 0)
        {
            throw new IllegalStateException(
                "burstEncode called without a known source FPS; call start(fps, quality) first "
                + "or use the burstEncode(frames, fps) overload.");
        }
        ByteArrayOutputStream bitstreamOut = new ByteArrayOutputStream(jpegFrames.size() * 50000);
        int frameIndex = 0;
        int fps = sourceFps;

        for (byte[] jpegBytes : jpegFrames)
        {
            java.awt.image.BufferedImage frame = (frameIndex == 0)
                ? firstFrame
                : decodeJpeg(jpegBytes);

            if (frame == null) continue;

            boolean isIdr = (frameIndex % fps) == 0;
            byte[] prev = frameEncoder.encodeFrame(frame, width, height, isIdr, frameIndex);
            writeNalus(bitstreamOut, prev);
            frameIndex++;
        }

        for (byte[] tail : frameEncoder.drainRemaining())
        {
            writeNalus(bitstreamOut, tail);
        }

        return bitstreamOut.toByteArray();
    }

    private void writeNalus(ByteArrayOutputStream out, byte[] nalus)
    {
        if (nalus == null || nalus.length == 0) return;
        try
        {
            out.write(nalus);
        }
        catch (java.io.IOException e)
        {
            log.error("Failed to write NALU data", e);
        }
    }

    private java.awt.image.BufferedImage decodeJpeg(byte[] jpegBytes)
    {
        try
        {
            return javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(jpegBytes));
        }
        catch (java.io.IOException e)
        {
            log.error("Failed to decode JPEG frame", e);
            return null;
        }
    }

    private void initializeVulkan(int width, int height, int frameCount)
    {
        if (!vulkanInitialized)
        {
            createCommandPool();
            createSyncPrimitives();
            vulkanInitialized = true;
        }

        int fps = sourceFps > 0 ? sourceFps : 30;
        sessionConfig = new H264SessionConfig(vulkanDevice, caps, width, height, fps);
        sessionConfig.initialize();

        resources = new EncodeResources(vulkanDevice, caps, width, height);
        allocateCommandBuffer();

        frameEncoder = new FrameEncoder(
            vulkanDevice, caps, sessionConfig, resources,
            commandBuffers, gfxCommandBuffers,
            encodeFences, uploadSemaphores,
            width, height, fps);
    }

    private void createCommandPool()
    {
        VkDevice device = vulkanDevice.getDevice();
        try (MemoryStack stack = stackPush())
        {
            // Encode-queue pool. RESET_COMMAND_BUFFER_BIT so ring slots can
            // be reset individually; pool-wide reset would clobber pending work.
            VkCommandPoolCreateInfo encPool = VkCommandPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT
                    | VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                .queueFamilyIndex(vulkanDevice.getVideoEncodeQueueFamily());
            LongBuffer pPool = stack.mallocLong(1);
            int r = vkCreateCommandPool(device, encPool, null, pPool);
            if (r != VK_SUCCESS) throw new RuntimeException("encode vkCreateCommandPool failed: " + r);
            commandPool = pPool.get(0);
            persistent.add(() -> vkDestroyCommandPool(device, commandPool, null));

            // Graphics-queue pool (for the NV12 upload).
            VkCommandPoolCreateInfo gfxPool = VkCommandPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT
                    | VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                .queueFamilyIndex(vulkanDevice.getGraphicsQueueFamily());
            r = vkCreateCommandPool(device, gfxPool, null, pPool);
            if (r != VK_SUCCESS) throw new RuntimeException("graphics vkCreateCommandPool failed: " + r);
            gfxCommandPool = pPool.get(0);
            persistent.add(() -> vkDestroyCommandPool(device, gfxCommandPool, null));
        }
    }

    private void allocateCommandBuffer()
    {
        VkDevice device = vulkanDevice.getDevice();
        try (MemoryStack stack = stackPush())
        {
            VkCommandBufferAllocateInfo encAlloc = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(commandPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(EncodeResources.RING_DEPTH);
            PointerBuffer pCmdBuf = stack.mallocPointer(EncodeResources.RING_DEPTH);
            int r = vkAllocateCommandBuffers(device, encAlloc, pCmdBuf);
            if (r != VK_SUCCESS) throw new RuntimeException("encode vkAllocateCommandBuffers failed: " + r);
            for (int i = 0; i < EncodeResources.RING_DEPTH; i++)
            {
                commandBuffers[i] = new VkCommandBuffer(pCmdBuf.get(i), device);
            }

            VkCommandBufferAllocateInfo gfxAlloc = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(gfxCommandPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(EncodeResources.RING_DEPTH);
            pCmdBuf.clear();
            r = vkAllocateCommandBuffers(device, gfxAlloc, pCmdBuf);
            if (r != VK_SUCCESS) throw new RuntimeException("graphics vkAllocateCommandBuffers failed: " + r);
            for (int i = 0; i < EncodeResources.RING_DEPTH; i++)
            {
                gfxCommandBuffers[i] = new VkCommandBuffer(pCmdBuf.get(i), device);
            }
        }
    }

    private void createSyncPrimitives()
    {
        VkDevice device = vulkanDevice.getDevice();
        try (MemoryStack stack = stackPush())
        {
            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                .flags(VK_FENCE_CREATE_SIGNALED_BIT);
            VkSemaphoreCreateInfo semInfo = VkSemaphoreCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
            LongBuffer p = stack.mallocLong(1);
            for (int i = 0; i < EncodeResources.RING_DEPTH; i++)
            {
                int r = vkCreateFence(device, fenceInfo, null, p);
                if (r != VK_SUCCESS) throw new RuntimeException("vkCreateFence slot " + i + " failed: " + r);
                encodeFences[i] = p.get(0);
                final int slot = i;
                persistent.add(() -> vkDestroyFence(device, encodeFences[slot], null));

                // Binary semaphore signalled by the graphics submit (upload done),
                // waited by the encode submit (QFOT acquire + encode).
                r = vkCreateSemaphore(device, semInfo, null, p);
                if (r != VK_SUCCESS) throw new RuntimeException("vkCreateSemaphore slot " + i + " failed: " + r);
                uploadSemaphores[i] = p.get(0);
                persistent.add(() -> vkDestroySemaphore(device, uploadSemaphores[slot], null));
            }
        }
    }


    // ---- Cleanup ----

    private void destroyEncodeResources()
    {
        // Drain any in-flight ring slots before tearing down the resources
        // they reference; validation layers fire on destroy-while-pending.
        VkDevice device = vulkanDevice.getDevice();
        for (long fence : encodeFences)
        {
            if (fence != VK_NULL_HANDLE)
            {
                vkWaitForFences(device, fence, true, FENCE_TIMEOUT_NS);
            }
        }

        frameEncoder = null;
        if (resources != null)
        {
            resources.close();
            resources = null;
        }
        if (sessionConfig != null)
        {
            sessionConfig.close();
            sessionConfig = null;
        }
    }

    private void destroyVulkanResources()
    {
        destroyEncodeResources();
        persistent.close();
        for (int i = 0; i < EncodeResources.RING_DEPTH; i++)
        {
            encodeFences[i] = VK_NULL_HANDLE;
            uploadSemaphores[i] = VK_NULL_HANDLE;
        }
        commandPool = VK_NULL_HANDLE;
        gfxCommandPool = VK_NULL_HANDLE;
        vulkanInitialized = false;
    }

    @Override
    public void close()
    {
        if (closed)
        {
            return;
        }
        closed = true;
        stop();
    }
}
