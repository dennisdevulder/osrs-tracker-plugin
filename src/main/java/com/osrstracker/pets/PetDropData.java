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
package com.osrstracker.pets;

import lombok.Builder;
import lombok.Data;

/**
 * Data class representing a pet drop event.
 */
@Data
@Builder
public class PetDropData
{
    /**
     * The name of the pet (e.g., "Vorki", "Noon", "Pet snakeling")
     */
    private final String petName;

    /**
     * The NPC ID of the pet follower, or -1 if unknown
     */
    private final int npcId;

    /**
     * Whether this is a duplicate pet (player already has it)
     */
    private final boolean isDuplicate;

    /**
     * Whether the pet went to backpack (player had another follower out)
     */
    private final boolean toBackpack;

    /**
     * Timestamp when the pet was obtained (epoch millis)
     */
    private final long timestamp;

    /**
     * Base64-encoded screenshot of the moment
     */
    private final String screenshot;

    /**
     * Video storage key (S3 key) for the capture
     */
    private final String videoKey;

    /**
     * The player's RuneScape name
     */
    private final String rsn;
}
