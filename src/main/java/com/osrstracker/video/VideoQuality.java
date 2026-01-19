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
 * Video quality presets for recording.
 * Frames are captured as JPEG and uploaded to the server for processing.
 */
@Getter
@RequiredArgsConstructor
public enum VideoQuality
{
	/**
	 * Screenshot only - no video recording.
	 */
	SCREENSHOT_ONLY(
		"Screenshot Only",
		0,     // No video
		0,     // No FPS
		0.0f   // No quality
	),

	/**
	 * Low quality video - smaller uploads.
	 * 10 seconds @ 15 FPS, 50% JPEG quality
	 */
	LOW_QUALITY(
		"Video: 10s @ 15 FPS (Low)",
		10000, // 10 seconds
		15,    // 15 FPS
		0.50f  // 50% JPEG quality
	),

	/**
	 * Medium quality video - balanced quality and upload size.
	 * 10 seconds @ 30 FPS, 50% JPEG quality
	 */
	MEDIUM_QUALITY(
		"Video: 10s @ 30 FPS (Medium)",
		10000, // 10 seconds
		30,    // 30 FPS
		0.50f  // 50% JPEG quality
	),

	/**
	 * High quality video - best quality, larger uploads.
	 * 10 seconds @ 30 FPS, 80% JPEG quality (~40MB at 1080p)
	 */
	HIGH_QUALITY(
		"Video: 10s @ 30 FPS (High)",
		10000, // 10 seconds
		30,    // 30 FPS
		0.80f  // 80% JPEG quality
	);

	private final String displayName;
	private final int durationMs;
	private final int fps;
	private final float jpegQuality;

	/**
	 * Checks if this quality preset requires video recording.
	 *
	 * @return true if this preset records video frames
	 */
	public boolean requiresVideo()
	{
		return this != SCREENSHOT_ONLY;
	}

	/**
	 * Gets the default quality preset.
	 * Default to screenshot-only for minimal bandwidth and storage usage.
	 * Users can opt-in to video recording if desired.
	 *
	 * @return SCREENSHOT_ONLY as the default
	 */
	public static VideoQuality getDefaultForCurrentHeap()
	{
		return SCREENSHOT_ONLY;
	}

	@Override
	public String toString()
	{
		return displayName;
	}
}
