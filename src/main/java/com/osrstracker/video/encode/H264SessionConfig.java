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
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.lwjgl.vulkan.video.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.EXTVideoEncodeH264.*;
import static org.lwjgl.vulkan.KHRVideoEncodeQueue.*;
import static org.lwjgl.vulkan.KHRVideoQueue.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.video.STDVulkanVideoCodecH264.*;

/**
 * Creates and manages a Vulkan Video Session with H.264 Baseline SPS/PPS.
 *
 * Ported from pyroenc patterns, adapted for LWJGL 3.3.2 EXT bindings.
 * Baseline profile: CAVLC only, no B-frames, no weighted prediction.
 */
@Slf4j
public class H264SessionConfig implements AutoCloseable
{
    private final VulkanDevice vulkanDevice;
    private final VulkanCapabilities caps;
    private final int width;
    private final int height;
    private final int fps;

    private long videoSession = VK_NULL_HANDLE;
    private long sessionParameters = VK_NULL_HANDLE;

    // Session memory allocations (must be freed on close)
    private long[] sessionMemoryAllocations;

    // Heap-allocated structs that live for the session lifetime
    private StdVideoH264SequenceParameterSet sps;
    private StdVideoH264PictureParameterSet pps;
    private StdVideoH264SpsFlags spsFlags;
    private StdVideoH264PpsFlags ppsFlags;

    private boolean closed = false;

    public H264SessionConfig(VulkanDevice vulkanDevice, VulkanCapabilities caps, int width, int height, int fps)
    {
        this.vulkanDevice = vulkanDevice;
        this.caps = caps;
        this.width = width;
        this.height = height;
        this.fps = fps;
    }

    /**
     * Creates the video session and session parameters (SPS + PPS).
     */
    public void initialize()
    {
        createVideoSession();
        bindSessionMemory();
        createSessionParameters();
        log.debug("H.264 session created: {}x{} @ {} FPS, Baseline", width, height, fps);
    }

    private void createVideoSession()
    {
        try (MemoryStack stack = stackPush())
        {
            VkVideoProfileInfoKHR videoProfile = caps.buildVideoProfile(stack);

            // VkExtensionProperties is read-only in LWJGL 3.3.2, write fields directly.
            // Layout: extensionName (256 bytes) + specVersion (4 bytes)
            VkExtensionProperties stdHeader = VkExtensionProperties.calloc(stack);
            ByteBuffer nameBytes = stack.UTF8(VK_STD_VULKAN_VIDEO_CODEC_H264_ENCODE_EXTENSION_NAME);
            MemoryUtil.memCopy(MemoryUtil.memAddress(nameBytes), MemoryUtil.memAddress(stdHeader.extensionName()),
                Math.min(nameBytes.remaining(), stdHeader.extensionName().remaining()));
            // specVersion is at offset 256 (after extensionName[256])
            MemoryUtil.memPutInt(stdHeader.address() + 256, VK_STD_VULKAN_VIDEO_CODEC_H264_ENCODE_SPEC_VERSION);

            VkVideoSessionCreateInfoKHR createInfo = VkVideoSessionCreateInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_VIDEO_SESSION_CREATE_INFO_KHR)
                .queueFamilyIndex(vulkanDevice.getVideoEncodeQueueFamily())
                .pVideoProfile(videoProfile)
                .pictureFormat(caps.getPictureFormat())
                .maxCodedExtent(e -> e.width(width).height(height))
                .referencePictureFormat(caps.getPictureFormat())
                .maxDpbSlots(2) // I+P ping-pong
                .maxActiveReferencePictures(1) // P-frame refs 1 prior frame
                .pStdHeaderVersion(stdHeader);

            LongBuffer pSession = stack.mallocLong(1);
            int result = vkCreateVideoSessionKHR(vulkanDevice.getDevice(), createInfo, null, pSession);
            if (result != VK_SUCCESS)
            {
                throw new RuntimeException("vkCreateVideoSessionKHR failed: " + result);
            }
            videoSession = pSession.get(0);
        }
    }

    private void bindSessionMemory()
    {
        VkDevice device = vulkanDevice.getDevice();

        try (MemoryStack stack = stackPush())
        {
            // Query how many memory bindings the session needs
            IntBuffer pCount = stack.mallocInt(1);
            vkGetVideoSessionMemoryRequirementsKHR(device, videoSession, pCount, null);
            int count = pCount.get(0);

            if (count == 0)
            {
                return;
            }

            VkVideoSessionMemoryRequirementsKHR.Buffer memReqs =
                VkVideoSessionMemoryRequirementsKHR.calloc(count, stack);
            for (int i = 0; i < count; i++)
            {
                memReqs.get(i).sType(VK_STRUCTURE_TYPE_VIDEO_SESSION_MEMORY_REQUIREMENTS_KHR);
            }
            vkGetVideoSessionMemoryRequirementsKHR(device, videoSession, pCount, memReqs);

            // Allocate and bind memory for each requirement
            sessionMemoryAllocations = new long[count];
            VkBindVideoSessionMemoryInfoKHR.Buffer bindInfos =
                VkBindVideoSessionMemoryInfoKHR.calloc(count, stack);

            for (int i = 0; i < count; i++)
            {
                VkMemoryRequirements memReq = memReqs.get(i).memoryRequirements();

                VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReq.size())
                    .memoryTypeIndex(findMemoryType(
                        memReq.memoryTypeBits(),
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                        stack));

                LongBuffer pMemory = stack.mallocLong(1);
                int result = vkAllocateMemory(device, allocInfo, null, pMemory);
                if (result != VK_SUCCESS)
                {
                    throw new RuntimeException("vkAllocateMemory for video session failed: " + result);
                }

                sessionMemoryAllocations[i] = pMemory.get(0);

                bindInfos.get(i)
                    .sType(VK_STRUCTURE_TYPE_BIND_VIDEO_SESSION_MEMORY_INFO_KHR)
                    .memoryBindIndex(memReqs.get(i).memoryBindIndex())
                    .memory(pMemory.get(0))
                    .memoryOffset(0)
                    .memorySize(memReq.size());
            }

            int result = vkBindVideoSessionMemoryKHR(device, videoSession, bindInfos);
            if (result != VK_SUCCESS)
            {
                throw new RuntimeException("vkBindVideoSessionMemoryKHR failed: " + result);
            }
        }
    }

    private void createSessionParameters()
    {
        // These structs must survive beyond the stack scope (session lifetime)
        spsFlags = StdVideoH264SpsFlags.calloc()
            .frame_mbs_only_flag(true)
            .direct_8x8_inference_flag(true);

        sps = StdVideoH264SequenceParameterSet.calloc()
            .flags(spsFlags)
            .profile_idc(STD_VIDEO_H264_PROFILE_IDC_BASELINE)
            .level_idc(STD_VIDEO_H264_LEVEL_IDC_4_0)
            .chroma_format_idc(STD_VIDEO_H264_CHROMA_FORMAT_IDC_420)
            .seq_parameter_set_id((byte) 0)
            .bit_depth_luma_minus8((byte) 0)
            .bit_depth_chroma_minus8((byte) 0)
            .log2_max_frame_num_minus4((byte) 0) // max_frame_num = 16
            .pic_order_cnt_type(STD_VIDEO_H264_POC_TYPE_2) // No POC signaling needed for I+P
            .max_num_ref_frames((byte) 1) // 1 reference frame for P-frames
            .pic_width_in_mbs_minus1((width + 15) / 16 - 1)
            .pic_height_in_map_units_minus1((height + 15) / 16 - 1);

        // Apply frame cropping if dimensions aren't multiples of 16
        int mbWidth = ((width + 15) / 16) * 16;
        int mbHeight = ((height + 15) / 16) * 16;
        if (mbWidth != width || mbHeight != height)
        {
            spsFlags.frame_cropping_flag(true);
            sps.frame_crop_right_offset((mbWidth - width) / 2);
            sps.frame_crop_bottom_offset((mbHeight - height) / 2);
        }

        ppsFlags = StdVideoH264PpsFlags.calloc()
            .entropy_coding_mode_flag(false); // false = CAVLC (required for Baseline)

        pps = StdVideoH264PictureParameterSet.calloc()
            .flags(ppsFlags)
            .seq_parameter_set_id((byte) 0)
            .pic_parameter_set_id((byte) 0)
            .num_ref_idx_l0_default_active_minus1((byte) 0) // 1 L0 reference
            .num_ref_idx_l1_default_active_minus1((byte) 0)
            .pic_init_qp_minus26((byte) 0); // QP 26 default

        try (MemoryStack stack = stackPush())
        {
            StdVideoH264SequenceParameterSet.Buffer spsBuffer =
                StdVideoH264SequenceParameterSet.calloc(1, stack);
            spsBuffer.put(0, sps);

            StdVideoH264PictureParameterSet.Buffer ppsBuffer =
                StdVideoH264PictureParameterSet.calloc(1, stack);
            ppsBuffer.put(0, pps);

            VkVideoEncodeH264SessionParametersAddInfoEXT addInfo =
                VkVideoEncodeH264SessionParametersAddInfoEXT.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_VIDEO_ENCODE_H264_SESSION_PARAMETERS_ADD_INFO_EXT)
                    .pStdSPSs(spsBuffer)
                    .pStdPPSs(ppsBuffer);

            VkVideoEncodeH264SessionParametersCreateInfoEXT h264ParamsInfo =
                VkVideoEncodeH264SessionParametersCreateInfoEXT.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_VIDEO_ENCODE_H264_SESSION_PARAMETERS_CREATE_INFO_EXT)
                    .maxStdSPSCount(1)
                    .maxStdPPSCount(1)
                    .pParametersAddInfo(addInfo);

            VkVideoSessionParametersCreateInfoKHR paramsCreateInfo =
                VkVideoSessionParametersCreateInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_VIDEO_SESSION_PARAMETERS_CREATE_INFO_KHR)
                    .pNext(h264ParamsInfo.address())
                    .videoSession(videoSession);

            LongBuffer pParams = stack.mallocLong(1);
            int result = vkCreateVideoSessionParametersKHR(
                vulkanDevice.getDevice(), paramsCreateInfo, null, pParams);
            if (result != VK_SUCCESS)
            {
                throw new RuntimeException("vkCreateVideoSessionParametersKHR failed: " + result);
            }
            sessionParameters = pParams.get(0);
        }
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

        // Fallback: accept any matching type filter
        for (int i = 0; i < memProps.memoryTypeCount(); i++)
        {
            if ((typeFilter & (1 << i)) != 0)
            {
                return i;
            }
        }

        throw new RuntimeException("Failed to find suitable memory type");
    }

    public long getVideoSession() { return videoSession; }
    public long getSessionParameters() { return sessionParameters; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }

    @Override
    public void close()
    {
        if (closed)
        {
            return;
        }
        closed = true;

        VkDevice device = vulkanDevice.getDevice();

        if (sessionParameters != VK_NULL_HANDLE)
        {
            vkDestroyVideoSessionParametersKHR(device, sessionParameters, null);
        }
        if (videoSession != VK_NULL_HANDLE)
        {
            vkDestroyVideoSessionKHR(device, videoSession, null);
        }

        // Free session memory allocations
        if (sessionMemoryAllocations != null)
        {
            for (long mem : sessionMemoryAllocations)
            {
                if (mem != VK_NULL_HANDLE)
                {
                    vkFreeMemory(device, mem, null);
                }
            }
            sessionMemoryAllocations = null;
        }

        // Free heap-allocated std structs
        if (pps != null) pps.free();
        if (ppsFlags != null) ppsFlags.free();
        if (sps != null) sps.free();
        if (spsFlags != null) spsFlags.free();

        log.debug("H.264 session closed");
    }
}
