/*
 * Copyright (c) 2025, Dennis De Vulder
 * All rights reserved. See VulkanEncoder.java for full license text.
 */
package com.osrstracker.video.encode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone driver: take an MJPEG file, run it through the plugin's
 * Vulkan H.264 encoder, mux the output to a faststart MP4.
 *
 * Usage: MjpegToMp4Tool <input.mjpeg> <output.mp4> [fps=30]
 *
 * Requires lwjgl-vulkan and its natives on the runtime classpath and a Vulkan
 * driver that advertises VK_KHR_video_encode_h264 (verify with vulkaninfo).
 */
public final class MjpegToMp4Tool
{
    public static void main(String[] args) throws Exception
    {
        if (args.length < 2)
        {
            System.err.println("usage: MjpegToMp4Tool <input.mjpeg> <output.mp4> [fps]");
            System.err.println("  fps is inferred from the filename pattern *_<N>fps.mjpeg");
            System.err.println("  pass explicitly only to override.");
            System.exit(2);
        }
        Path input = Paths.get(args[0]);
        Path output = Paths.get(args[1]);
        int fps = args.length > 2 ? Integer.parseInt(args[2]) : inferFpsFromFilename(input);

        System.out.println("=== MJPEG -> Vulkan H.264 -> MP4 ===");
        System.out.println("input:  " + input.toAbsolutePath() + " (" + Files.size(input) / 1024 + " KiB)");
        System.out.println("output: " + output.toAbsolutePath());
        System.out.println("fps:    " + fps);

        long t0 = System.nanoTime();

        byte[] raw = Files.readAllBytes(input);
        List<byte[]> frames = splitMjpeg(raw);
        if (frames.isEmpty())
        {
            throw new IllegalStateException("No JPEG frames found in MJPEG input");
        }
        System.out.println("split:  " + frames.size() + " JPEG frames");

        // --- Initialize Vulkan ---
        VulkanDevice device = new VulkanDevice();
        device.initialize();
        VulkanCapabilities caps = new VulkanCapabilities(device);
        if (!caps.probe())
        {
            throw new IllegalStateException("Vulkan H.264 encode not supported on this device");
        }
        System.out.printf("vulkan: %s - H.264 encode available (rate-control modes=0x%x, quality levels=%d)%n",
            device.getDeviceName(), caps.getSupportedRateControlModes(), caps.getMaxQualityLevels());

        // --- Run the plugin's burst encoder ---
        byte[] h264;
        byte[] driverSpsPps;
        try (VulkanEncoder enc = new VulkanEncoder(device, caps))
        {
            long te0 = System.nanoTime();
            h264 = enc.burstEncode(frames, fps);
            long teMs = (System.nanoTime() - te0) / 1_000_000;
            if (h264 == null || h264.length == 0)
            {
                throw new IllegalStateException("burstEncode returned empty bitstream");
            }
            driverSpsPps = enc.getDriverSpsPps();
            System.out.printf("encode: %d frames -> %.1f KiB H.264 in %d ms (%.1f fps)%n",
                frames.size(), h264.length / 1024.0, teMs,
                frames.size() * 1000.0 / Math.max(teMs, 1));
        }

        java.awt.image.BufferedImage firstFrame = javax.imageio.ImageIO.read(
            new java.io.ByteArrayInputStream(frames.get(0)));
        LocalMp4Writer.write(h264, driverSpsPps,
            firstFrame.getWidth(), firstFrame.getHeight(), fps, output);

        long totalMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("wrote:  %s (%d KiB) in %d ms%n",
            output, Files.size(output) / 1024, totalMs);
    }

    /**
     * Parses the FPS from the capture filename. The cloud upload pipeline names
     * everything {@code <prefix>_<N>fps.mjpeg} so the encoder downstream can be
     * stateless: fps is in the name, not a side channel.
     *
     * @throws IllegalArgumentException if the filename doesn't carry fps info.
     */
    static int inferFpsFromFilename(Path path)
    {
        String name = path.getFileName().toString();
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("_(\\d+)fps\\.[^.]+$").matcher(name);
        if (!m.find())
        {
            throw new IllegalArgumentException(
                "can't infer fps from filename '" + name
                + "'; expected pattern like 'foo_30fps.mjpeg'. Pass fps explicitly to override.");
        }
        int fps = Integer.parseInt(m.group(1));
        if (fps <= 0 || fps > 240)
        {
            throw new IllegalArgumentException("filename-inferred fps out of sane range: " + fps);
        }
        return fps;
    }

    // --- MJPEG split: scan for JPEG SOI (FFD8) ... EOI (FFD9) pairs ---
    static List<byte[]> splitMjpeg(byte[] data)
    {
        List<byte[]> out = new ArrayList<>();
        int i = 0;
        while (i < data.length - 1)
        {
            if ((data[i] & 0xFF) == 0xFF && (data[i + 1] & 0xFF) == 0xD8)
            {
                int start = i;
                i += 2;
                while (i < data.length - 1)
                {
                    if ((data[i] & 0xFF) == 0xFF && (data[i + 1] & 0xFF) == 0xD9)
                    {
                        int end = i + 2;
                        byte[] frame = new byte[end - start];
                        System.arraycopy(data, start, frame, 0, frame.length);
                        out.add(frame);
                        i = end;
                        break;
                    }
                    i++;
                }
            }
            else
            {
                i++;
            }
        }
        return out;
    }

}
