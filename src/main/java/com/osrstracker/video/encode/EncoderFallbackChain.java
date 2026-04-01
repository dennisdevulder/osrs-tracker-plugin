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

/**
 * Selects the best available video encoder at startup.
 * Tries Vulkan H.264 first, falls back to MJPEG if unavailable.
 *
 * Each check in the chain is guarded so a failure at any step
 * produces a clear log message and falls through to MJPEG.
 */
@Slf4j
public class EncoderFallbackChain
{
    private String fallbackReason;

    /**
     * Probes for the best available encoder and returns it.
     * Always succeeds -- worst case returns MjpegEncoder.
     */
    public VideoEncoder selectEncoder()
    {
        // macOS: MoltenVK will never support Vulkan Video Encode
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac"))
        {
            fallbackReason = "macOS (MoltenVK does not support Vulkan Video)";
            log.debug("Encoder fallback: {}", fallbackReason);
            return new MjpegEncoder();
        }

        // Check if LWJGL Vulkan bindings are on the classpath
        try
        {
            Class.forName("org.lwjgl.vulkan.VK10");
        }
        catch (ClassNotFoundException e)
        {
            fallbackReason = "LWJGL Vulkan not on classpath";
            log.debug("Encoder fallback: {}", fallbackReason);
            return new MjpegEncoder();
        }

        // Try to initialize Vulkan and probe for encode support
        VulkanDevice vulkanDevice = null;
        try
        {
            vulkanDevice = new VulkanDevice();
            vulkanDevice.initialize();

            VulkanCapabilities caps = new VulkanCapabilities(vulkanDevice);
            boolean supported = caps.probe();

            if (!supported)
            {
                vulkanDevice.close();
                fallbackReason = "GPU does not support H.264 Vulkan Video Encode";
                log.debug("Encoder fallback: {}", fallbackReason);
                return new MjpegEncoder();
            }

            log.info("Vulkan H.264 encode available: {} ({}x{}, {} DPB slots)",
                vulkanDevice.getDeviceName(), caps.getMaxWidth(), caps.getMaxHeight(),
                caps.getMaxDpbSlots());

            // TODO: In Chunk 3, return VulkanEncoder(vulkanDevice, caps) here.
            // For now, we've confirmed Vulkan encode is available but still use MJPEG.
            // The VulkanDevice stays open for future use.
            vulkanDevice.close();
            fallbackReason = "Vulkan encode detected but encoder not yet implemented";
            log.debug("Encoder fallback: {}", fallbackReason);
            return new MjpegEncoder();
        }
        catch (Exception e)
        {
            if (vulkanDevice != null)
            {
                try
                {
                    vulkanDevice.close();
                }
                catch (Exception ignored) {}
            }
            fallbackReason = "Vulkan init failed: " + e.getMessage();
            log.debug("Encoder fallback: {}", fallbackReason);
            return new MjpegEncoder();
        }
    }

    /**
     * Returns a human-readable reason for why MJPEG was selected (or null if Vulkan was used).
     */
    public String getFallbackReason()
    {
        return fallbackReason;
    }
}
