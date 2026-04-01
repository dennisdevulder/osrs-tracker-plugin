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

import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.EXTVideoEncodeH264.*;
import static org.lwjgl.vulkan.KHRVideoEncodeQueue.*;
import static org.lwjgl.vulkan.KHRVideoQueue.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;

/**
 * Manages Vulkan instance, physical device, logical device, and video encode queue.
 * Headless initialization -- no window or surface required.
 */
@Slf4j
public class VulkanDevice implements AutoCloseable
{
    private VkInstance instance;
    private long debugMessenger = VK_NULL_HANDLE;
    private VkPhysicalDevice physicalDevice;
    private VkDevice device;
    private VkQueue videoEncodeQueue;
    private int videoEncodeQueueFamily = -1;
    private String deviceName = "unknown";
    private boolean closed = false;

    private static final String[] REQUIRED_DEVICE_EXTENSIONS = {
        VK_KHR_VIDEO_QUEUE_EXTENSION_NAME,
        VK_KHR_VIDEO_ENCODE_QUEUE_EXTENSION_NAME,
        VK_EXT_VIDEO_ENCODE_H264_EXTENSION_NAME,
    };

    public void initialize()
    {
        createInstance();
        pickPhysicalDevice();
        createLogicalDevice();
    }

    private void createInstance()
    {
        try (MemoryStack stack = stackPush())
        {
            // Check for validation layer
            IntBuffer pLayerCount = stack.mallocInt(1);
            vkEnumerateInstanceLayerProperties(pLayerCount, null);
            VkLayerProperties.Buffer layers = VkLayerProperties.calloc(pLayerCount.get(0), stack);
            vkEnumerateInstanceLayerProperties(pLayerCount, layers);

            boolean hasValidation = false;
            for (int i = 0; i < layers.capacity(); i++)
            {
                if ("VK_LAYER_KHRONOS_validation".equals(layers.get(i).layerNameString()))
                {
                    hasValidation = true;
                    break;
                }
            }

            // Check for debug utils extension
            IntBuffer pExtCount = stack.mallocInt(1);
            vkEnumerateInstanceExtensionProperties((String) null, pExtCount, null);
            VkExtensionProperties.Buffer exts = VkExtensionProperties.calloc(pExtCount.get(0), stack);
            vkEnumerateInstanceExtensionProperties((String) null, pExtCount, exts);

            boolean hasDebugUtils = false;
            for (int i = 0; i < exts.capacity(); i++)
            {
                if (VK_EXT_DEBUG_UTILS_EXTENSION_NAME.equals(exts.get(i).extensionNameString()))
                {
                    hasDebugUtils = true;
                    break;
                }
            }

            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(stack.UTF8("osrs-tracker"))
                .applicationVersion(VK_MAKE_VERSION(1, 0, 0))
                .pEngineName(stack.UTF8("osrs-tracker"))
                .engineVersion(VK_MAKE_VERSION(1, 0, 0))
                .apiVersion(VK_API_VERSION_1_1);

            // Only enable validation in dev mode
            boolean enableValidation = hasValidation &&
                "true".equalsIgnoreCase(System.getenv("OSRS_TRACKER_DEV"));

            PointerBuffer ppEnabledLayers = null;
            if (enableValidation)
            {
                ppEnabledLayers = stack.pointers(stack.UTF8("VK_LAYER_KHRONOS_validation"));
                log.debug("Vulkan validation layers enabled");
            }

            PointerBuffer ppEnabledExtensions = null;
            if (hasDebugUtils && enableValidation)
            {
                ppEnabledExtensions = stack.pointers(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME));
            }

            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pApplicationInfo(appInfo)
                .ppEnabledLayerNames(ppEnabledLayers)
                .ppEnabledExtensionNames(ppEnabledExtensions);

            PointerBuffer pInstance = stack.mallocPointer(1);
            int result = vkCreateInstance(createInfo, null, pInstance);
            if (result != VK_SUCCESS)
            {
                throw new RuntimeException("vkCreateInstance failed: " + result);
            }
            instance = new VkInstance(pInstance.get(0), createInfo);

            log.debug("Vulkan instance created");
        }
    }

    private void pickPhysicalDevice()
    {
        try (MemoryStack stack = stackPush())
        {
            IntBuffer pDeviceCount = stack.mallocInt(1);
            vkEnumeratePhysicalDevices(instance, pDeviceCount, null);
            int deviceCount = pDeviceCount.get(0);

            if (deviceCount == 0)
            {
                throw new RuntimeException("No Vulkan-capable GPU found");
            }

            PointerBuffer pDevices = stack.mallocPointer(deviceCount);
            vkEnumeratePhysicalDevices(instance, pDeviceCount, pDevices);

            VkPhysicalDevice bestDevice = null;
            int bestScore = -1;
            int bestQueueFamily = -1;
            String bestName = "unknown";

            for (int i = 0; i < deviceCount; i++)
            {
                VkPhysicalDevice candidate = new VkPhysicalDevice(pDevices.get(i), instance);
                VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
                vkGetPhysicalDeviceProperties(candidate, props);

                String name = props.deviceNameString();
                int type = props.deviceType();

                int score = 0;
                if (type == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU)
                {
                    score = 1000;
                }
                else if (type == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU)
                {
                    score = 100;
                }

                int encodeFamily = findVideoEncodeQueueFamily(candidate, stack);
                if (encodeFamily < 0)
                {
                    log.debug("  {} -- no video encode queue, skipping", name);
                    continue;
                }

                if (!hasRequiredExtensions(candidate, stack))
                {
                    log.debug("  {} -- missing required extensions, skipping", name);
                    continue;
                }

                log.debug("  {} -- score={}, encodeQueue={}", name, score, encodeFamily);

                if (score > bestScore)
                {
                    bestScore = score;
                    bestDevice = candidate;
                    bestQueueFamily = encodeFamily;
                    bestName = name;
                }
            }

            if (bestDevice == null)
            {
                throw new RuntimeException("No GPU with Vulkan Video Encode support found");
            }

            physicalDevice = bestDevice;
            videoEncodeQueueFamily = bestQueueFamily;
            deviceName = bestName;

            log.debug("Selected GPU: {}", deviceName);
        }
    }

    private int findVideoEncodeQueueFamily(VkPhysicalDevice device, MemoryStack stack)
    {
        IntBuffer pCount = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(device, pCount, null);
        int count = pCount.get(0);

        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(count, stack);
        vkGetPhysicalDeviceQueueFamilyProperties(device, pCount, families);

        for (int i = 0; i < count; i++)
        {
            if ((families.get(i).queueFlags() & VK_QUEUE_VIDEO_ENCODE_BIT_KHR) != 0)
            {
                return i;
            }
        }
        return -1;
    }

    private boolean hasRequiredExtensions(VkPhysicalDevice device, MemoryStack stack)
    {
        IntBuffer pExtCount = stack.mallocInt(1);
        vkEnumerateDeviceExtensionProperties(device, (String) null, pExtCount, null);
        VkExtensionProperties.Buffer exts = VkExtensionProperties.calloc(pExtCount.get(0), stack);
        vkEnumerateDeviceExtensionProperties(device, (String) null, pExtCount, exts);

        for (String required : REQUIRED_DEVICE_EXTENSIONS)
        {
            boolean found = false;
            for (int i = 0; i < exts.capacity(); i++)
            {
                if (required.equals(exts.get(i).extensionNameString()))
                {
                    found = true;
                    break;
                }
            }
            if (!found)
            {
                return false;
            }
        }
        return true;
    }

    private void createLogicalDevice()
    {
        try (MemoryStack stack = stackPush())
        {
            VkDeviceQueueCreateInfo.Buffer queueCreateInfos = VkDeviceQueueCreateInfo.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                .queueFamilyIndex(videoEncodeQueueFamily)
                .pQueuePriorities(stack.floats(1.0f));

            PointerBuffer ppExtensions = stack.mallocPointer(REQUIRED_DEVICE_EXTENSIONS.length);
            for (String ext : REQUIRED_DEVICE_EXTENSIONS)
            {
                ppExtensions.put(stack.UTF8(ext));
            }
            ppExtensions.flip();

            VkDeviceCreateInfo deviceCreateInfo = VkDeviceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pQueueCreateInfos(queueCreateInfos)
                .ppEnabledExtensionNames(ppExtensions);

            PointerBuffer pDevice = stack.mallocPointer(1);
            int result = vkCreateDevice(physicalDevice, deviceCreateInfo, null, pDevice);
            if (result != VK_SUCCESS)
            {
                throw new RuntimeException("vkCreateDevice failed: " + result);
            }
            device = new VkDevice(pDevice.get(0), physicalDevice, deviceCreateInfo);

            PointerBuffer pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(device, videoEncodeQueueFamily, 0, pQueue);
            videoEncodeQueue = new VkQueue(pQueue.get(0), device);

            log.debug("Vulkan device created, encode queue acquired");
        }
    }

    public VkInstance getInstance() { return instance; }
    public VkPhysicalDevice getPhysicalDevice() { return physicalDevice; }
    public VkDevice getDevice() { return device; }
    public VkQueue getVideoEncodeQueue() { return videoEncodeQueue; }
    public int getVideoEncodeQueueFamily() { return videoEncodeQueueFamily; }
    public String getDeviceName() { return deviceName; }

    @Override
    public void close()
    {
        if (closed)
        {
            return;
        }
        closed = true;

        if (device != null)
        {
            vkDeviceWaitIdle(device);
            vkDestroyDevice(device, null);
        }
        if (debugMessenger != VK_NULL_HANDLE && instance != null)
        {
            vkDestroyDebugUtilsMessengerEXT(instance, debugMessenger, null);
        }
        if (instance != null)
        {
            vkDestroyInstance(instance, null);
        }

        log.debug("Vulkan device closed");
    }
}
