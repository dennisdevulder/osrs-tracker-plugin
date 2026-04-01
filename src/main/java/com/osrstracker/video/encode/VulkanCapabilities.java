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

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.EXTVideoEncodeH264.*;
import static org.lwjgl.vulkan.video.STDVulkanVideoCodecH264.*;
import static org.lwjgl.vulkan.KHRVideoEncodeQueue.*;
import static org.lwjgl.vulkan.KHRVideoQueue.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Probes and reports Vulkan Video Encode capabilities for the selected GPU.
 */
@Slf4j
@Getter
public class VulkanCapabilities
{
    private final VulkanDevice vulkanDevice;

    private int maxWidth;
    private int maxHeight;
    private int maxDpbSlots;
    private int maxActiveReferencePictures;
    private int maxQualityLevels;
    private int supportedRateControlModes;
    private int pictureFormat = VK_FORMAT_UNDEFINED;

    public VulkanCapabilities(VulkanDevice vulkanDevice)
    {
        this.vulkanDevice = vulkanDevice;
    }

    /**
     * Builds the H.264 Baseline video profile on the given stack.
     */
    public VkVideoProfileInfoKHR buildVideoProfile(MemoryStack stack)
    {
        VkVideoEncodeH264ProfileInfoEXT h264Profile = VkVideoEncodeH264ProfileInfoEXT.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_VIDEO_ENCODE_H264_PROFILE_INFO_EXT)
            .stdProfileIdc(STD_VIDEO_H264_PROFILE_IDC_BASELINE);

        return VkVideoProfileInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_VIDEO_PROFILE_INFO_KHR)
            .pNext(h264Profile)
            .videoCodecOperation(VK_VIDEO_CODEC_OPERATION_ENCODE_H264_BIT_EXT)
            .chromaSubsampling(VK_VIDEO_CHROMA_SUBSAMPLING_420_BIT_KHR)
            .lumaBitDepth(VK_VIDEO_COMPONENT_BIT_DEPTH_8_BIT_KHR)
            .chromaBitDepth(VK_VIDEO_COMPONENT_BIT_DEPTH_8_BIT_KHR);
    }

    /**
     * Probes the GPU for H.264 encode capabilities.
     * Returns true if encoding is supported, false otherwise.
     */
    public boolean probe()
    {
        try (MemoryStack stack = stackPush())
        {
            VkPhysicalDevice physDevice = vulkanDevice.getPhysicalDevice();
            VkVideoProfileInfoKHR videoProfile = buildVideoProfile(stack);

            VkVideoEncodeH264CapabilitiesEXT h264Caps = VkVideoEncodeH264CapabilitiesEXT.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_VIDEO_ENCODE_H264_CAPABILITIES_EXT);

            VkVideoEncodeCapabilitiesKHR encodeCaps = VkVideoEncodeCapabilitiesKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_VIDEO_ENCODE_CAPABILITIES_KHR)
                .pNext(h264Caps);

            VkVideoCapabilitiesKHR videoCaps = VkVideoCapabilitiesKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_VIDEO_CAPABILITIES_KHR)
                .pNext(encodeCaps);

            int result = vkGetPhysicalDeviceVideoCapabilitiesKHR(physDevice, videoProfile, videoCaps);
            if (result != VK_SUCCESS)
            {
                log.debug("H.264 encode not supported (result={})", result);
                return false;
            }

            maxWidth = videoCaps.maxCodedExtent().width();
            maxHeight = videoCaps.maxCodedExtent().height();
            maxDpbSlots = videoCaps.maxDpbSlots();
            maxActiveReferencePictures = videoCaps.maxActiveReferencePictures();
            maxQualityLevels = encodeCaps.maxQualityLevels();
            supportedRateControlModes = encodeCaps.rateControlModes();
            pictureFormat = querySupportedFormat(physDevice, videoProfile, stack);

            log.debug("Vulkan H.264 encode: {}x{}, {} DPB slots, {} refs, {} quality levels, format={}",
                maxWidth, maxHeight, maxDpbSlots, maxActiveReferencePictures,
                maxQualityLevels, pictureFormat);

            return true;
        }
    }

    private int querySupportedFormat(VkPhysicalDevice physDevice, VkVideoProfileInfoKHR videoProfile, MemoryStack stack)
    {
        VkVideoProfileListInfoKHR profileList = VkVideoProfileListInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_VIDEO_PROFILE_LIST_INFO_KHR)
            .pProfiles(VkVideoProfileInfoKHR.calloc(1, stack).put(0, videoProfile));

        VkPhysicalDeviceVideoFormatInfoKHR formatInfo = VkPhysicalDeviceVideoFormatInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VIDEO_FORMAT_INFO_KHR)
            .pNext(profileList)
            .imageUsage(VK_IMAGE_USAGE_VIDEO_ENCODE_SRC_BIT_KHR);

        IntBuffer pFormatCount = stack.mallocInt(1);
        int result = vkGetPhysicalDeviceVideoFormatPropertiesKHR(physDevice, formatInfo, pFormatCount, null);
        if (result != VK_SUCCESS || pFormatCount.get(0) == 0)
        {
            return VK_FORMAT_UNDEFINED;
        }

        VkVideoFormatPropertiesKHR.Buffer formats = VkVideoFormatPropertiesKHR.calloc(pFormatCount.get(0), stack);
        for (int i = 0; i < formats.capacity(); i++)
        {
            formats.get(i).sType(VK_STRUCTURE_TYPE_VIDEO_FORMAT_PROPERTIES_KHR);
        }
        vkGetPhysicalDeviceVideoFormatPropertiesKHR(physDevice, formatInfo, pFormatCount, formats);

        return formats.get(0).format();
    }
}
