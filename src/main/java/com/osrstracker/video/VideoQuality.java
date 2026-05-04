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

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Video quality presets. {@code jpegQuality} drives the MJPEG fallback path;
 * {@code averageBitrateBps}/{@code peakBitrateBps} drive the streaming H.264
 * encoder. Higher fps presets get a higher bitrate so per-frame quality stays
 * roughly comparable.
 */
@Getter
@RequiredArgsConstructor
public enum VideoQuality
{
	SCREENSHOT_ONLY(
		"Screenshot Only",
		0, 0, 0.0f, 0, 0
	),

	PERFORMANCE(
		"Video: Performance (20 FPS)",
		10000, 20, 0.80f,
		4_000_000, 6_000_000
	),

	QUALITY(
		"Video: Quality (30 FPS)",
		10000, 30, 0.80f,
		8_000_000, 12_000_000
	),

	SMOOTH(
		"Experimental: 60 FPS",
		10000, 60, 0.80f,
		12_000_000, 18_000_000
	);

	private final String displayName;
	private final int durationMs;
	private final int fps;
	private final float jpegQuality;
	private final int averageBitrateBps;
	private final int peakBitrateBps;

	public boolean requiresVideo()
	{
		return this != SCREENSHOT_ONLY;
	}

	public static VideoQuality getDefault()
	{
		return SCREENSHOT_ONLY;
	}

	@Override
	public String toString()
	{
		return displayName;
	}
}
