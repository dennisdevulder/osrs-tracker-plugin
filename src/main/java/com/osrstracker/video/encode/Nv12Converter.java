/*
 * Copyright (c) 2025, Dennis De Vulder
 * All rights reserved. See VulkanEncoder.java for full license text.
 */
package com.osrstracker.video.encode;

import java.nio.ByteBuffer;

/**
 * RGB to NV12 (YCbCr 4:2:0, biplanar) colour conversion.
 *
 * NV12 layout for a W x H image:
 *   - Plane 0 (Y, luma): W*H bytes, one per pixel.
 *   - Plane 1 (UV, chroma): (W/2)*(H/2) interleaved pairs = (W/2)*(H/2)*2 bytes.
 *     Each pair covers a 2x2 pixel block; order is [U, V].
 *
 * Conversion uses BT.601 full-range (JFIF). Full dynamic range is signalled
 * end-to-end via the SPS VUI (video_full_range_flag=1, matrix=6) and backed
 * up by the MP4 colr atom, matching what libx264 produces for -pix_fmt
 * yuvj420p. Decoders read the SPS VUI first, so any compliant player renders
 * 1:1 with the source RGB regardless of container metadata.
 *
 * BT.601 JFIF matrix:
 *   Y  =  0.299 R + 0.587 G + 0.114 B
 *   Cb = 128 - 0.168736 R - 0.331264 G + 0.5 B
 *   Cr = 128 + 0.5 R - 0.418688 G - 0.081312 B
 *
 * Width and height must be even. Callers pad the frame up to multiples of 2
 * (or 16 for H.264 macroblocks) before calling this.
 */
public final class Nv12Converter
{
    private Nv12Converter() {}

    /**
     * Converts ARGB pixels (as returned by {@code BufferedImage.getRGB}) to NV12.
     *
     * @param argb      WxH ARGB int pixels, row-major.
     * @param width     Must be even.
     * @param height    Must be even.
     * @param yOut      Target buffer for the Y plane. Must hold at least {@code width*height} bytes.
     * @param yOffset   Offset into {@code yOut} to start writing the Y plane.
     * @param uvOut     Target buffer for the interleaved UV plane. Must hold at least
     *                  {@code width*height/2} bytes.
     * @param uvOffset  Offset into {@code uvOut} to start writing the UV plane.
     */
    public static void argbToNv12(int[] argb, int width, int height,
                                  byte[] yOut, int yOffset,
                                  byte[] uvOut, int uvOffset)
    {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("non-positive dimensions");
        if ((width & 1) != 0 || (height & 1) != 0)
        {
            throw new IllegalArgumentException("width and height must be even for NV12 (got "
                + width + "x" + height + ")");
        }
        int pixels = width * height;
        if (argb.length < pixels) throw new IllegalArgumentException("argb too small");
        if (yOut.length - yOffset < pixels)
            throw new IllegalArgumentException("yOut too small");
        if (uvOut.length - uvOffset < pixels / 2)
            throw new IllegalArgumentException("uvOut too small");

        // Walk 2x2 blocks so we can compute Y for every pixel and one averaged
        // Cb/Cr per block.
        for (int y = 0; y < height; y += 2)
        {
            int rowBase0 = y * width;
            int rowBase1 = (y + 1) * width;
            for (int x = 0; x < width; x += 2)
            {
                int p00 = argb[rowBase0 + x];
                int p01 = argb[rowBase0 + x + 1];
                int p10 = argb[rowBase1 + x];
                int p11 = argb[rowBase1 + x + 1];

                // Y for each of the four pixels.
                int y00 = rgbToY(p00);
                int y01 = rgbToY(p01);
                int y10 = rgbToY(p10);
                int y11 = rgbToY(p11);

                yOut[yOffset + rowBase0 + x]     = (byte) y00;
                yOut[yOffset + rowBase0 + x + 1] = (byte) y01;
                yOut[yOffset + rowBase1 + x]     = (byte) y10;
                yOut[yOffset + rowBase1 + x + 1] = (byte) y11;

                // Box-filter the 2x2 block for the chroma sample. Hardware encoders
                // apply their own internal filter on top.
                int r = ((p00 >> 16) & 0xFF) + ((p01 >> 16) & 0xFF) + ((p10 >> 16) & 0xFF) + ((p11 >> 16) & 0xFF);
                int g = ((p00 >>  8) & 0xFF) + ((p01 >>  8) & 0xFF) + ((p10 >>  8) & 0xFF) + ((p11 >>  8) & 0xFF);
                int b = ( p00        & 0xFF) + ( p01        & 0xFF) + ( p10        & 0xFF) + ( p11        & 0xFF);
                r >>= 2; g >>= 2; b >>= 2;

                int cb = clamp((-43 * r - 85 * g + 128 * b + 32768) >> 8, 0, 255);
                int cr = clamp(( 128 * r - 107 * g - 21 * b + 32768) >> 8, 0, 255);

                int uvIndex = uvOffset + (y / 2) * width + x;
                uvOut[uvIndex]     = (byte) cb;
                uvOut[uvIndex + 1] = (byte) cr;
            }
        }
    }

    /**
     * Writes Y then interleaved UV contiguously into {@code out} at its current
     * position, advancing it by {@code width*height*3/2}. Used on the encode
     * hot path to write directly into mapped GPU staging memory.
     */
    public static void argbToNv12(int[] argb, int width, int height, ByteBuffer out)
    {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("non-positive dimensions");
        if ((width & 1) != 0 || (height & 1) != 0)
        {
            throw new IllegalArgumentException("width and height must be even for NV12 (got "
                + width + "x" + height + ")");
        }
        int pixels = width * height;
        if (argb.length < pixels) throw new IllegalArgumentException("argb too small");
        int nv12Bytes = pixels + pixels / 2;
        int yBase = out.position();
        int uvBase = yBase + pixels;
        if (out.limit() - yBase < nv12Bytes)
            throw new IllegalArgumentException("out too small: need " + nv12Bytes
                + " bytes, have " + (out.limit() - yBase));

        for (int y = 0; y < height; y += 2)
        {
            int rowBase0 = y * width;
            int rowBase1 = (y + 1) * width;
            for (int x = 0; x < width; x += 2)
            {
                int p00 = argb[rowBase0 + x];
                int p01 = argb[rowBase0 + x + 1];
                int p10 = argb[rowBase1 + x];
                int p11 = argb[rowBase1 + x + 1];

                out.put(yBase + rowBase0 + x,     (byte) rgbToY(p00));
                out.put(yBase + rowBase0 + x + 1, (byte) rgbToY(p01));
                out.put(yBase + rowBase1 + x,     (byte) rgbToY(p10));
                out.put(yBase + rowBase1 + x + 1, (byte) rgbToY(p11));

                int r = ((p00 >> 16) & 0xFF) + ((p01 >> 16) & 0xFF) + ((p10 >> 16) & 0xFF) + ((p11 >> 16) & 0xFF);
                int g = ((p00 >>  8) & 0xFF) + ((p01 >>  8) & 0xFF) + ((p10 >>  8) & 0xFF) + ((p11 >>  8) & 0xFF);
                int b = ( p00        & 0xFF) + ( p01        & 0xFF) + ( p10        & 0xFF) + ( p11        & 0xFF);
                r >>= 2; g >>= 2; b >>= 2;

                int cb = clamp((-43 * r - 85 * g + 128 * b + 32768) >> 8, 0, 255);
                int cr = clamp(( 128 * r - 107 * g - 21 * b + 32768) >> 8, 0, 255);

                int uvIndex = uvBase + (y / 2) * width + x;
                out.put(uvIndex,     (byte) cb);
                out.put(uvIndex + 1, (byte) cr);
            }
        }
        out.position(yBase + nv12Bytes);
    }

    /**
     * Convenience: allocates the output buffers and returns them as a pair.
     * {@link #argbToNv12} is preferred on hot paths (no allocation).
     */
    public static Nv12Frame argbToNv12(int[] argb, int width, int height)
    {
        byte[] y = new byte[width * height];
        byte[] uv = new byte[width * height / 2];
        argbToNv12(argb, width, height, y, 0, uv, 0);
        return new Nv12Frame(y, uv, width, height);
    }

    /** Container for an NV12-converted frame. */
    public static final class Nv12Frame
    {
        public final byte[] y;
        public final byte[] uv;
        public final int width;
        public final int height;

        public Nv12Frame(byte[] y, byte[] uv, int width, int height)
        {
            this.y = y;
            this.uv = uv;
            this.width = width;
            this.height = height;
        }
    }

    // --- Per-pixel BT.601 full-range (JFIF), integer-math, avoid doubles on hot path.

    private static int rgbToY(int argb)
    {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >>  8) & 0xFF;
        int b =  argb        & 0xFF;
        // Y = 0.299 R + 0.587 G + 0.114 B. Fixed-point *256 -> {77, 150, 29} (sum 256).
        return clamp((77 * r + 150 * g + 29 * b + 128) >> 8, 0, 255);
    }

    private static int clamp(int v, int lo, int hi)
    {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
