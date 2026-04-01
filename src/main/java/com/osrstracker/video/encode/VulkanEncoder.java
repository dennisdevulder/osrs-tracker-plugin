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
import org.lwjgl.vulkan.video.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.EXTVideoEncodeH264.*;
import static org.lwjgl.vulkan.KHRVideoEncodeQueue.*;
import static org.lwjgl.vulkan.KHRVideoQueue.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;
import static org.lwjgl.vulkan.video.STDVulkanVideoCodecH264.*;

/**
 * Vulkan H.264 video encoder using hardware video encode extensions.
 *
 * Accepts RGBA frames, uploads to GPU, encodes via Vulkan Video Encode,
 * and accumulates H.264 NALUs in a ring buffer.
 *
 * Uses I+P frame pattern with 2 DPB slots (ping-pong).
 * LWJGL 3.3.2 EXT bindings + Synchronization2 (VK 1.3) barriers.
 */
@Slf4j
public class VulkanEncoder implements VideoEncoder, AutoCloseable
{
    private final VulkanDevice vulkanDevice;
    private final VulkanCapabilities caps;

    private H264SessionConfig sessionConfig;

    // Command pool and buffer
    private long commandPool = VK_NULL_HANDLE;
    private VkCommandBuffer commandBuffer;

    // Synchronization
    private long encodeFence = VK_NULL_HANDLE;

    // Encode input image
    private long encodeInputImage = VK_NULL_HANDLE;
    private long encodeInputImageView = VK_NULL_HANDLE;
    private long encodeInputMemory = VK_NULL_HANDLE;

    // DPB images (2 slots)
    private long dpbImage = VK_NULL_HANDLE;
    private final long[] dpbImageViews = new long[2];
    private long dpbMemory = VK_NULL_HANDLE;

    // Bitstream output buffer
    private long bitstreamBuffer = VK_NULL_HANDLE;
    private long bitstreamMemory = VK_NULL_HANDLE;
    private long bitstreamMappedPtr = 0;
    private static final int BITSTREAM_BUFFER_SIZE = 4 * 1024 * 1024; // 4 MB

    // Staging buffer for RGBA upload
    private long stagingBuffer = VK_NULL_HANDLE;
    private long stagingMemory = VK_NULL_HANDLE;
    private long stagingMappedPtr = 0;

    // NALU ring buffer
    private static final int MAX_NALUS = 600;
    private final byte[][] naluBuffer = new byte[MAX_NALUS][];
    private final long[] naluTimestamps = new long[MAX_NALUS];
    private final AtomicInteger naluWriteIndex = new AtomicInteger(0);
    private final AtomicInteger naluCount = new AtomicInteger(0);
    private final Object naluLock = new Object();

    // Frame state
    private int frameIndex = 0;
    private boolean sessionReset = false;
    private int currentWidth;
    private int currentHeight;
    private int currentFps;
    private boolean closed = false;

    // Encode lock -- VulkanEncoder is single-threaded (one command buffer, one fence)
    // but submitFrame may be called from asyncWriter pool (2 threads)
    private final Object encodeLock = new Object();

    // Max fence wait: 5 seconds. Avoids infinite hang on GPU crash.
    private static final long FENCE_TIMEOUT_NS = 5_000_000_000L;

    public VulkanEncoder(VulkanDevice vulkanDevice, VulkanCapabilities caps)
    {
        this.vulkanDevice = vulkanDevice;
        this.caps = caps;
    }

    @Override
    public void start(int fps, float quality)
    {
        currentFps = fps;
        currentWidth = 800;
        currentHeight = 600;

        createCommandPool();
        createSyncPrimitives();
        createSessionAndResources(currentWidth, currentHeight);
        log.debug("VulkanEncoder started at {} FPS", fps);
    }

    @Override
    public void stop()
    {
        synchronized (encodeLock)
        {
            waitIdle();
            destroyResources();
            // Close the VulkanDevice we took ownership of
            vulkanDevice.close();
        }
        reset();
    }

    @Override
    public void submitFrame(ByteBuffer rgbaPixels, int width, int height, long timestamp, boolean needsBlur)
    {
        // Skip frames with sensitive content -- Vulkan encodes immediately,
        // can't defer blur like MJPEG does during finalization
        if (needsBlur)
        {
            return;
        }

        if (rgbaPixels == null || width <= 0 || height <= 0)
        {
            return;
        }

        synchronized (encodeLock)
        {
            if (width != currentWidth || height != currentHeight)
            {
                waitIdle();
                destroySessionResources();
                createSessionAndResources(width, height);
                currentWidth = width;
                currentHeight = height;
            }

            try
            {
                encodeFrame(rgbaPixels, width, height, timestamp);
            }
            catch (Exception e)
            {
                log.error("Failed to encode frame", e);
            }
        }
    }

    @Override
    public ClipData finalizeClip(long startTime, long endTime)
    {
        waitIdle();

        List<byte[]> clipNalus = new ArrayList<>();

        synchronized (naluLock)
        {
            int count = naluCount.get();
            int writeIdx = naluWriteIndex.get() % MAX_NALUS;

            for (int i = 0; i < count; i++)
            {
                int idx = (writeIdx - count + i + MAX_NALUS) % MAX_NALUS;
                long ts = naluTimestamps[idx];
                byte[] nalu = naluBuffer[idx];

                if (ts >= startTime && ts <= endTime && nalu != null)
                {
                    clipNalus.add(nalu);
                }
            }
        }

        if (clipNalus.isEmpty())
        {
            return null;
        }

        // Raw Annex B bitstream for now. TODO: MP4 muxing
        long totalSize = 0;
        for (byte[] nalu : clipNalus)
        {
            totalSize += nalu.length;
        }

        return new ClipData(clipNalus, "application/octet-stream", totalSize);
    }

    @Override
    public void reset()
    {
        synchronized (naluLock)
        {
            for (int i = 0; i < MAX_NALUS; i++)
            {
                naluBuffer[i] = null;
                naluTimestamps[i] = 0;
            }
            naluWriteIndex.set(0);
            naluCount.set(0);
        }
        frameIndex = 0;
        sessionReset = false;
    }

    @Override
    public String encoderName()
    {
        return "vulkan-h264";
    }

    // ---- Core encode pipeline ----

    private void encodeFrame(ByteBuffer rgbaPixels, int width, int height, long timestamp)
    {
        VkDevice device = vulkanDevice.getDevice();

        // Upload RGBA to staging buffer
        int frameSize = width * height * 4;
        ByteBuffer staging = memByteBuffer(stagingMappedPtr, frameSize);
        rgbaPixels.rewind();
        staging.put(rgbaPixels);
        staging.flip();

        try (MemoryStack stack = stackPush())
        {
            vkWaitForFences(device, encodeFence, true, FENCE_TIMEOUT_NS);
            vkResetFences(device, encodeFence);
            vkResetCommandPool(device, commandPool, 0);

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            vkBeginCommandBuffer(commandBuffer, beginInfo);

            // Transition encode input: UNDEFINED -> TRANSFER_DST
            imageBarrier2(stack, encodeInputImage,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                0, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                0);

            // Copy staging -> encode input image
            VkBufferImageCopy.Buffer copyRegion = VkBufferImageCopy.calloc(1, stack)
                .bufferOffset(0)
                .bufferRowLength(0)
                .bufferImageHeight(0)
                .imageSubresource(s -> s
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1))
                .imageOffset(o -> o.set(0, 0, 0))
                .imageExtent(e -> e.set(width, height, 1));

            vkCmdCopyBufferToImage(commandBuffer, stagingBuffer, encodeInputImage,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copyRegion);

            // Transition encode input: TRANSFER_DST -> VIDEO_ENCODE_SRC
            imageBarrier2(stack, encodeInputImage,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_VIDEO_ENCODE_SRC_KHR,
                VK_ACCESS_TRANSFER_WRITE_BIT, 0,
                VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                0,
                VK_ACCESS_2_VIDEO_ENCODE_READ_BIT_KHR,
                VK_PIPELINE_STAGE_2_VIDEO_ENCODE_BIT_KHR);

            // Determine frame type
            boolean isIdr = (frameIndex % (currentFps > 0 ? currentFps : 30)) == 0;
            int dpbSlot = frameIndex % 2;
            int refSlot = (frameIndex + 1) % 2;

            recordEncodeCommand(stack, isIdr, dpbSlot, refSlot);

            // Barrier: bitstream -> host readable (using sync2 for 64-bit access masks)
            bufferBarrier2(stack, bitstreamBuffer,
                VK_ACCESS_2_VIDEO_ENCODE_WRITE_BIT_KHR, VK_ACCESS_HOST_READ_BIT,
                VK_PIPELINE_STAGE_2_VIDEO_ENCODE_BIT_KHR, VK_PIPELINE_STAGE_HOST_BIT);

            vkEndCommandBuffer(commandBuffer);

            // Submit
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(stack.pointers(commandBuffer));

            int result = vkQueueSubmit(vulkanDevice.getVideoEncodeQueue(), submitInfo, encodeFence);
            if (result != VK_SUCCESS)
            {
                log.error("vkQueueSubmit failed: {}", result);
                return;
            }

            vkWaitForFences(device, encodeFence, true, FENCE_TIMEOUT_NS);
            readBackBitstream(timestamp);
            frameIndex++;
        }
    }

    private void recordEncodeCommand(MemoryStack stack, boolean isIdr, int dpbSlot, int refSlot)
    {
        // Transition DPB images on first use
        if (!sessionReset)
        {
            for (int i = 0; i < 2; i++)
            {
                imageBarrier2(stack, dpbImage,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_VIDEO_ENCODE_DPB_KHR,
                    0, 0,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0,
                    i,
                    VK_ACCESS_2_VIDEO_ENCODE_READ_BIT_KHR | VK_ACCESS_2_VIDEO_ENCODE_WRITE_BIT_KHR,
                    VK_PIPELINE_STAGE_2_VIDEO_ENCODE_BIT_KHR);
            }
        }

        // Source picture
        VkVideoPictureResourceInfoKHR srcPicture = VkVideoPictureResourceInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_VIDEO_PICTURE_RESOURCE_INFO_KHR)
            .codedOffset(o -> o.set(0, 0))
            .codedExtent(e -> e.set(currentWidth, currentHeight))
            .baseArrayLayer(0)
            .imageViewBinding(encodeInputImageView);

        // Setup slot (reconstructed picture)
        VkVideoEncodeH264DpbSlotInfoEXT h264DpbSlotInfo = VkVideoEncodeH264DpbSlotInfoEXT.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_VIDEO_ENCODE_H264_DPB_SLOT_INFO_EXT);

        VkVideoPictureResourceInfoKHR setupPicResource = VkVideoPictureResourceInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_VIDEO_PICTURE_RESOURCE_INFO_KHR)
            .codedOffset(o -> o.set(0, 0))
            .codedExtent(e -> e.set(currentWidth, currentHeight))
            .baseArrayLayer(dpbSlot)
            .imageViewBinding(dpbImageViews[dpbSlot]);

        VkVideoReferenceSlotInfoKHR setupSlot = VkVideoReferenceSlotInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_VIDEO_REFERENCE_SLOT_INFO_KHR)
            .pNext(h264DpbSlotInfo.address())
            .slotIndex(dpbSlot)
            .pPictureResource(setupPicResource);

        // Reference slots for P-frames
        VkVideoReferenceSlotInfoKHR.Buffer referenceSlots = null;
        if (!isIdr && frameIndex > 0)
        {
            VkVideoEncodeH264DpbSlotInfoEXT refH264Info = VkVideoEncodeH264DpbSlotInfoEXT.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_VIDEO_ENCODE_H264_DPB_SLOT_INFO_EXT);

            VkVideoPictureResourceInfoKHR refPicResource = VkVideoPictureResourceInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_VIDEO_PICTURE_RESOURCE_INFO_KHR)
                .codedOffset(o -> o.set(0, 0))
                .codedExtent(e -> e.set(currentWidth, currentHeight))
                .baseArrayLayer(refSlot)
                .imageViewBinding(dpbImageViews[refSlot]);

            referenceSlots = VkVideoReferenceSlotInfoKHR.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_VIDEO_REFERENCE_SLOT_INFO_KHR)
                .pNext(refH264Info.address())
                .slotIndex(refSlot)
                .pPictureResource(refPicResource);
        }

        // H.264 picture info
        StdVideoEncodeH264PictureInfoFlags picFlags = StdVideoEncodeH264PictureInfoFlags.calloc(stack)
            .idr_flag(isIdr)
            .is_reference_flag(true);

        StdVideoEncodeH264PictureInfo picInfo = StdVideoEncodeH264PictureInfo.calloc(stack)
            .flags(picFlags)
            .seq_parameter_set_id((byte) 0)
            .pic_parameter_set_id((byte) 0)
            .pictureType(isIdr ? STD_VIDEO_H264_PICTURE_TYPE_IDR : STD_VIDEO_H264_PICTURE_TYPE_P)
            .frame_num(frameIndex % 16); // max_frame_num = 16 (log2_max_frame_num_minus4 = 0)

        // Reference lists
        StdVideoEncodeH264ReferenceListsInfo refLists = StdVideoEncodeH264ReferenceListsInfo.calloc(stack);
        if (!isIdr && frameIndex > 0)
        {
            ByteBuffer l0Entries = stack.bytes((byte) refSlot);
            StdVideoEncodeH264ReferenceListsInfo.nrefPicList0EntryCount(refLists.address(), (byte) 1);
            refLists.pRefPicList0Entries(l0Entries);
        }

        // Slice info
        StdVideoEncodeH264SliceHeaderFlags sliceFlags = StdVideoEncodeH264SliceHeaderFlags.calloc(stack);

        StdVideoEncodeH264SliceHeader sliceHeader = StdVideoEncodeH264SliceHeader.calloc(stack)
            .flags(sliceFlags)
            .slice_type(isIdr ? STD_VIDEO_H264_SLICE_TYPE_I : STD_VIDEO_H264_SLICE_TYPE_P);

        VkVideoEncodeH264NaluSliceInfoEXT.Buffer naluSliceInfo =
            VkVideoEncodeH264NaluSliceInfoEXT.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_VIDEO_ENCODE_H264_NALU_SLICE_INFO_EXT)
                .mbCount(((currentWidth + 15) / 16) * ((currentHeight + 15) / 16))
                .pStdReferenceFinalLists(refLists)
                .pStdSliceHeader(sliceHeader);

        VkVideoEncodeH264VclFrameInfoEXT h264FrameInfo = VkVideoEncodeH264VclFrameInfoEXT.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_VIDEO_ENCODE_H264_VCL_FRAME_INFO_EXT)
            .pStdReferenceFinalLists(refLists)
            .pNaluSliceEntries(naluSliceInfo)
            .pStdPictureInfo(picInfo);

        // Begin video coding
        VkVideoBeginCodingInfoKHR beginCoding = VkVideoBeginCodingInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_VIDEO_BEGIN_CODING_INFO_KHR)
            .videoSession(sessionConfig.getVideoSession())
            .videoSessionParameters(sessionConfig.getSessionParameters());

        VkVideoReferenceSlotInfoKHR.Buffer beginSlots = VkVideoReferenceSlotInfoKHR.calloc(2, stack);
        for (int i = 0; i < 2; i++)
        {
            VkVideoPictureResourceInfoKHR slotPic = VkVideoPictureResourceInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_VIDEO_PICTURE_RESOURCE_INFO_KHR)
                .codedOffset(o -> o.set(0, 0))
                .codedExtent(e -> e.set(currentWidth, currentHeight))
                .baseArrayLayer(i)
                .imageViewBinding(dpbImageViews[i]);

            beginSlots.get(i)
                .sType(VK_STRUCTURE_TYPE_VIDEO_REFERENCE_SLOT_INFO_KHR)
                .slotIndex(i)
                .pPictureResource(slotPic);
        }
        beginCoding.pReferenceSlots(beginSlots);

        vkCmdBeginVideoCodingKHR(commandBuffer, beginCoding);

        // Reset session on first frame
        if (!sessionReset)
        {
            VkVideoCodingControlInfoKHR controlInfo = VkVideoCodingControlInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_VIDEO_CODING_CONTROL_INFO_KHR)
                .flags(VK_VIDEO_CODING_CONTROL_RESET_BIT_KHR);
            vkCmdControlVideoCodingKHR(commandBuffer, controlInfo);
            sessionReset = true;
        }

        // Encode
        VkVideoEncodeInfoKHR encodeInfo = VkVideoEncodeInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_VIDEO_ENCODE_INFO_KHR)
            .pNext(h264FrameInfo.address())
            .dstBuffer(bitstreamBuffer)
            .dstBufferOffset(0)
            .dstBufferRange(BITSTREAM_BUFFER_SIZE)
            .srcPictureResource(srcPicture)
            .pSetupReferenceSlot(setupSlot)
            .pReferenceSlots(referenceSlots);

        vkCmdEncodeVideoKHR(commandBuffer, encodeInfo);

        // End video coding
        VkVideoEndCodingInfoKHR endCoding = VkVideoEndCodingInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_VIDEO_END_CODING_INFO_KHR);
        vkCmdEndVideoCodingKHR(commandBuffer, endCoding);
    }

    private void readBackBitstream(long timestamp)
    {
        // Invalidate CPU cache for HOST_CACHED (non-coherent) bitstream memory
        try (MemoryStack stack = stackPush())
        {
            VkMappedMemoryRange range = VkMappedMemoryRange.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE)
                .memory(bitstreamMemory)
                .offset(0)
                .size(VK_WHOLE_SIZE);
            vkInvalidateMappedMemoryRanges(vulkanDevice.getDevice(), range);
        }

        ByteBuffer bitstream = memByteBuffer(bitstreamMappedPtr, BITSTREAM_BUFFER_SIZE);
        int dataEnd = findBitstreamEnd(bitstream);
        if (dataEnd <= 0)
        {
            return;
        }

        byte[] naluData = new byte[dataEnd];
        bitstream.rewind();
        bitstream.get(naluData, 0, dataEnd);

        synchronized (naluLock)
        {
            int idx = naluWriteIndex.getAndIncrement() % MAX_NALUS;
            naluBuffer[idx] = naluData;
            naluTimestamps[idx] = timestamp;

            if (naluCount.get() < MAX_NALUS)
            {
                naluCount.incrementAndGet();
            }
        }
    }

    private int findBitstreamEnd(ByteBuffer buffer)
    {
        buffer.rewind();
        int lastNonZero = 0;

        for (int i = 0; i < buffer.remaining(); i++)
        {
            if (buffer.get(i) != 0)
            {
                lastNonZero = i + 1;
            }
            if (i - lastNonZero > 64)
            {
                break;
            }
        }

        return lastNonZero;
    }

    // ---- Barrier helpers using Synchronization2 (VK 1.3) ----

    /**
     * Image barrier using vkCmdPipelineBarrier2 for 64-bit access masks.
     * When dstAccess64/dstStage64 are non-zero, they override the int versions
     * (needed for VK_ACCESS_2_VIDEO_ENCODE_* which exceed int range).
     */
    private void imageBarrier2(MemoryStack stack, long image,
                               int oldLayout, int newLayout,
                               int srcAccess, int dstAccess,
                               int srcStage, int dstStage,
                               int arrayLayer)
    {
        imageBarrier2(stack, image, oldLayout, newLayout,
            srcAccess, dstAccess, srcStage, dstStage, arrayLayer, 0, 0);
    }

    private void imageBarrier2(MemoryStack stack, long image,
                               int oldLayout, int newLayout,
                               int srcAccess, int dstAccess,
                               int srcStage, int dstStage,
                               int arrayLayer,
                               long dstAccess64, long dstStage64)
    {
        long finalDstAccess = dstAccess64 != 0 ? dstAccess64 : dstAccess;
        long finalDstStage = dstStage64 != 0 ? dstStage64 : (dstStage != 0 ? dstStage : VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
        long finalSrcStage = srcStage != 0 ? srcStage : VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;

        VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack)
            .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2)
            .srcStageMask(finalSrcStage)
            .srcAccessMask(srcAccess)
            .dstStageMask(finalDstStage)
            .dstAccessMask(finalDstAccess)
            .oldLayout(oldLayout)
            .newLayout(newLayout)
            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .image(image)
            .subresourceRange(r -> r
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(arrayLayer)
                .layerCount(1));

        VkDependencyInfo depInfo = VkDependencyInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
            .pImageMemoryBarriers(barrier);

        vkCmdPipelineBarrier2(commandBuffer, depInfo);
    }

    private void bufferBarrier2(MemoryStack stack, long buffer,
                                long srcAccess, long dstAccess,
                                long srcStage, long dstStage)
    {
        VkBufferMemoryBarrier2.Buffer barrier = VkBufferMemoryBarrier2.calloc(1, stack)
            .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2)
            .srcStageMask(srcStage)
            .srcAccessMask(srcAccess)
            .dstStageMask(dstStage)
            .dstAccessMask(dstAccess)
            .buffer(buffer)
            .offset(0)
            .size(BITSTREAM_BUFFER_SIZE);

        VkDependencyInfo depInfo = VkDependencyInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
            .pBufferMemoryBarriers(barrier);

        vkCmdPipelineBarrier2(commandBuffer, depInfo);
    }

    // ---- Resource creation ----

    private void createSessionAndResources(int width, int height)
    {
        sessionConfig = new H264SessionConfig(vulkanDevice, caps, width, height, currentFps);
        sessionConfig.initialize();

        createEncodeInputImage(width, height);
        createDpbImages(width, height);
        createBitstreamBuffer();
        createStagingBuffer(width, height);
        allocateCommandBuffer();

        frameIndex = 0;
        sessionReset = false;
    }

    private void createCommandPool()
    {
        try (MemoryStack stack = stackPush())
        {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT)
                .queueFamilyIndex(vulkanDevice.getVideoEncodeQueueFamily());

            LongBuffer pPool = stack.mallocLong(1);
            int result = vkCreateCommandPool(vulkanDevice.getDevice(), poolInfo, null, pPool);
            if (result != VK_SUCCESS)
            {
                throw new RuntimeException("vkCreateCommandPool failed: " + result);
            }
            commandPool = pPool.get(0);
        }
    }

    private void allocateCommandBuffer()
    {
        try (MemoryStack stack = stackPush())
        {
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(commandPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(1);

            PointerBuffer pCmdBuf = stack.mallocPointer(1);
            int result = vkAllocateCommandBuffers(vulkanDevice.getDevice(), allocInfo, pCmdBuf);
            if (result != VK_SUCCESS)
            {
                throw new RuntimeException("vkAllocateCommandBuffers failed: " + result);
            }
            commandBuffer = new VkCommandBuffer(pCmdBuf.get(0), vulkanDevice.getDevice());
        }
    }

    private void createSyncPrimitives()
    {
        try (MemoryStack stack = stackPush())
        {
            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                .flags(VK_FENCE_CREATE_SIGNALED_BIT);

            LongBuffer pFence = stack.mallocLong(1);
            int result = vkCreateFence(vulkanDevice.getDevice(), fenceInfo, null, pFence);
            if (result != VK_SUCCESS)
            {
                throw new RuntimeException("vkCreateFence failed: " + result);
            }
            encodeFence = pFence.get(0);
        }
    }

    private void createEncodeInputImage(int width, int height)
    {
        VkDevice device = vulkanDevice.getDevice();

        try (MemoryStack stack = stackPush())
        {
            VkVideoProfileListInfoKHR profileList = VkVideoProfileListInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_VIDEO_PROFILE_LIST_INFO_KHR)
                .pProfiles(VkVideoProfileInfoKHR.calloc(1, stack).put(0, caps.buildVideoProfile(stack)));

            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .pNext(profileList.address())
                .imageType(VK_IMAGE_TYPE_2D)
                .format(caps.getPictureFormat())
                .extent(e -> e.set(width, height, 1))
                .mipLevels(1)
                .arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(VK_IMAGE_USAGE_VIDEO_ENCODE_SRC_BIT_KHR | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

            LongBuffer pImage = stack.mallocLong(1);
            int result = vkCreateImage(device, imageInfo, null, pImage);
            if (result != VK_SUCCESS)
            {
                throw new RuntimeException("vkCreateImage (encode input) failed: " + result);
            }
            encodeInputImage = pImage.get(0);

            encodeInputMemory = allocateAndBindImageMemory(device, encodeInputImage,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, stack);

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(encodeInputImage)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(caps.getPictureFormat())
                .subresourceRange(r -> r
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1));

            LongBuffer pView = stack.mallocLong(1);
            result = vkCreateImageView(device, viewInfo, null, pView);
            if (result != VK_SUCCESS)
            {
                throw new RuntimeException("vkCreateImageView (encode input) failed: " + result);
            }
            encodeInputImageView = pView.get(0);
        }
    }

    private void createDpbImages(int width, int height)
    {
        VkDevice device = vulkanDevice.getDevice();

        try (MemoryStack stack = stackPush())
        {
            VkVideoProfileListInfoKHR profileList = VkVideoProfileListInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_VIDEO_PROFILE_LIST_INFO_KHR)
                .pProfiles(VkVideoProfileInfoKHR.calloc(1, stack).put(0, caps.buildVideoProfile(stack)));

            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .pNext(profileList.address())
                .imageType(VK_IMAGE_TYPE_2D)
                .format(caps.getPictureFormat())
                .extent(e -> e.set(width, height, 1))
                .mipLevels(1)
                .arrayLayers(2)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(VK_IMAGE_USAGE_VIDEO_ENCODE_DPB_BIT_KHR)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

            LongBuffer pImage = stack.mallocLong(1);
            int result = vkCreateImage(device, imageInfo, null, pImage);
            if (result != VK_SUCCESS)
            {
                throw new RuntimeException("vkCreateImage (DPB) failed: " + result);
            }
            dpbImage = pImage.get(0);

            dpbMemory = allocateAndBindImageMemory(device, dpbImage,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, stack);

            for (int i = 0; i < 2; i++)
            {
                final int layer = i;
                VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(dpbImage)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(caps.getPictureFormat())
                    .subresourceRange(r -> r
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0)
                        .levelCount(1)
                        .baseArrayLayer(layer)
                        .layerCount(1));

                LongBuffer pView = stack.mallocLong(1);
                result = vkCreateImageView(device, viewInfo, null, pView);
                if (result != VK_SUCCESS)
                {
                    throw new RuntimeException("vkCreateImageView (DPB " + i + ") failed: " + result);
                }
                dpbImageViews[i] = pView.get(0);
            }
        }
    }

    private void createBitstreamBuffer()
    {
        VkDevice device = vulkanDevice.getDevice();

        try (MemoryStack stack = stackPush())
        {
            VkVideoProfileListInfoKHR profileList = VkVideoProfileListInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_VIDEO_PROFILE_LIST_INFO_KHR)
                .pProfiles(VkVideoProfileInfoKHR.calloc(1, stack).put(0, caps.buildVideoProfile(stack)));

            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .pNext(profileList.address())
                .size(BITSTREAM_BUFFER_SIZE)
                .usage(VK_BUFFER_USAGE_VIDEO_ENCODE_DST_BIT_KHR)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pBuffer = stack.mallocLong(1);
            int result = vkCreateBuffer(device, bufferInfo, null, pBuffer);
            if (result != VK_SUCCESS)
            {
                throw new RuntimeException("vkCreateBuffer (bitstream) failed: " + result);
            }
            bitstreamBuffer = pBuffer.get(0);

            bitstreamMemory = allocateAndBindBufferMemory(device, bitstreamBuffer,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT, stack);

            PointerBuffer pData = stack.mallocPointer(1);
            result = vkMapMemory(device, bitstreamMemory, 0, BITSTREAM_BUFFER_SIZE, 0, pData);
            if (result != VK_SUCCESS)
            {
                throw new RuntimeException("vkMapMemory (bitstream) failed: " + result);
            }
            bitstreamMappedPtr = pData.get(0);
        }
    }

    private void createStagingBuffer(int width, int height)
    {
        VkDevice device = vulkanDevice.getDevice();
        long bufferSize = (long) width * height * 4;

        try (MemoryStack stack = stackPush())
        {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(bufferSize)
                .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pBuffer = stack.mallocLong(1);
            int result = vkCreateBuffer(device, bufferInfo, null, pBuffer);
            if (result != VK_SUCCESS)
            {
                throw new RuntimeException("vkCreateBuffer (staging) failed: " + result);
            }
            stagingBuffer = pBuffer.get(0);

            stagingMemory = allocateAndBindBufferMemory(device, stagingBuffer,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, stack);

            PointerBuffer pData = stack.mallocPointer(1);
            result = vkMapMemory(device, stagingMemory, 0, bufferSize, 0, pData);
            if (result != VK_SUCCESS)
            {
                throw new RuntimeException("vkMapMemory (staging) failed: " + result);
            }
            stagingMappedPtr = pData.get(0);
        }
    }

    // ---- Memory helpers ----

    private long allocateAndBindImageMemory(VkDevice device, long image, int properties, MemoryStack stack)
    {
        VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
        vkGetImageMemoryRequirements(device, image, memReqs);

        VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
            .allocationSize(memReqs.size())
            .memoryTypeIndex(findMemoryType(memReqs.memoryTypeBits(), properties, stack));

        LongBuffer pMemory = stack.mallocLong(1);
        int result = vkAllocateMemory(device, allocInfo, null, pMemory);
        if (result != VK_SUCCESS)
        {
            throw new RuntimeException("vkAllocateMemory failed: " + result);
        }
        long memory = pMemory.get(0);
        vkBindImageMemory(device, image, memory, 0);
        return memory;
    }

    private long allocateAndBindBufferMemory(VkDevice device, long buffer, int properties, MemoryStack stack)
    {
        VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
        vkGetBufferMemoryRequirements(device, buffer, memReqs);

        int memTypeIdx;
        try
        {
            memTypeIdx = findMemoryType(memReqs.memoryTypeBits(), properties, stack);
        }
        catch (RuntimeException e)
        {
            // Fallback: try without HOST_CACHED (some GPUs don't have it)
            memTypeIdx = findMemoryType(memReqs.memoryTypeBits(),
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, stack);
        }

        VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
            .allocationSize(memReqs.size())
            .memoryTypeIndex(memTypeIdx);

        LongBuffer pMemory = stack.mallocLong(1);
        int result = vkAllocateMemory(device, allocInfo, null, pMemory);
        if (result != VK_SUCCESS)
        {
            throw new RuntimeException("vkAllocateMemory failed: " + result);
        }
        long memory = pMemory.get(0);
        vkBindBufferMemory(device, buffer, memory, 0);
        return memory;
    }

    private int findMemoryType(int typeFilter, int properties, MemoryStack stack)
    {
        VkPhysicalDeviceMemoryProperties memProps = VkPhysicalDeviceMemoryProperties.calloc(stack);
        vkGetPhysicalDeviceMemoryProperties(vulkanDevice.getPhysicalDevice(), memProps);

        for (int i = 0; i < memProps.memoryTypeCount(); i++)
        {
            if ((typeFilter & (1 << i)) != 0 &&
                (memProps.memoryTypes(i).propertyFlags() & properties) == properties)
            {
                return i;
            }
        }

        for (int i = 0; i < memProps.memoryTypeCount(); i++)
        {
            if ((typeFilter & (1 << i)) != 0)
            {
                return i;
            }
        }

        throw new RuntimeException("Failed to find suitable memory type (filter=" + typeFilter + ", props=" + properties + ")");
    }

    // ---- Cleanup ----

    private void waitIdle()
    {
        if (encodeFence != VK_NULL_HANDLE)
        {
            vkWaitForFences(vulkanDevice.getDevice(), encodeFence, true, FENCE_TIMEOUT_NS);
        }
    }

    private void destroySessionResources()
    {
        VkDevice device = vulkanDevice.getDevice();
        if (sessionConfig != null)
        {
            sessionConfig.close();
            sessionConfig = null;
        }
        destroyImageResources(device);
        destroyBufferResources(device);
    }

    private void destroyResources()
    {
        VkDevice device = vulkanDevice.getDevice();
        destroySessionResources();

        if (encodeFence != VK_NULL_HANDLE)
        {
            vkDestroyFence(device, encodeFence, null);
            encodeFence = VK_NULL_HANDLE;
        }
        if (commandPool != VK_NULL_HANDLE)
        {
            vkDestroyCommandPool(device, commandPool, null);
            commandPool = VK_NULL_HANDLE;
        }
    }

    private void destroyImageResources(VkDevice device)
    {
        if (encodeInputImageView != VK_NULL_HANDLE)
        {
            vkDestroyImageView(device, encodeInputImageView, null);
            encodeInputImageView = VK_NULL_HANDLE;
        }
        if (encodeInputImage != VK_NULL_HANDLE)
        {
            vkDestroyImage(device, encodeInputImage, null);
            encodeInputImage = VK_NULL_HANDLE;
        }
        if (encodeInputMemory != VK_NULL_HANDLE)
        {
            vkFreeMemory(device, encodeInputMemory, null);
            encodeInputMemory = VK_NULL_HANDLE;
        }

        for (int i = 0; i < 2; i++)
        {
            if (dpbImageViews[i] != VK_NULL_HANDLE)
            {
                vkDestroyImageView(device, dpbImageViews[i], null);
                dpbImageViews[i] = VK_NULL_HANDLE;
            }
        }
        if (dpbImage != VK_NULL_HANDLE)
        {
            vkDestroyImage(device, dpbImage, null);
            dpbImage = VK_NULL_HANDLE;
        }
        if (dpbMemory != VK_NULL_HANDLE)
        {
            vkFreeMemory(device, dpbMemory, null);
            dpbMemory = VK_NULL_HANDLE;
        }
    }

    private void destroyBufferResources(VkDevice device)
    {
        if (bitstreamMappedPtr != 0)
        {
            vkUnmapMemory(device, bitstreamMemory);
            bitstreamMappedPtr = 0;
        }
        if (bitstreamBuffer != VK_NULL_HANDLE)
        {
            vkDestroyBuffer(device, bitstreamBuffer, null);
            bitstreamBuffer = VK_NULL_HANDLE;
        }
        if (bitstreamMemory != VK_NULL_HANDLE)
        {
            vkFreeMemory(device, bitstreamMemory, null);
            bitstreamMemory = VK_NULL_HANDLE;
        }

        if (stagingMappedPtr != 0)
        {
            vkUnmapMemory(device, stagingMemory);
            stagingMappedPtr = 0;
        }
        if (stagingBuffer != VK_NULL_HANDLE)
        {
            vkDestroyBuffer(device, stagingBuffer, null);
            stagingBuffer = VK_NULL_HANDLE;
        }
        if (stagingMemory != VK_NULL_HANDLE)
        {
            vkFreeMemory(device, stagingMemory, null);
            stagingMemory = VK_NULL_HANDLE;
        }
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
