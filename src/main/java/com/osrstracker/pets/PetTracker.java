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

import com.google.gson.JsonObject;
import com.osrstracker.OsrsTrackerConfig;
import com.osrstracker.api.ApiClient;
import com.osrstracker.video.VideoRecorder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.client.callback.ClientThread;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.regex.Pattern;

/**
 * Tracks pet drops and reports them to the API with extended video capture.
 *
 * Pet drops are detected via chat messages:
 * - "You have a funny feeling like you're being followed." - Normal pet drop (follows player)
 * - "You feel something weird sneaking into your backpack." - Pet to inventory (already have follower)
 * - "You have a funny feeling like you would have been followed" - Duplicate pet (already owned)
 *
 * Captures video (6 seconds buffer + 4 seconds post-event) when a pet drop is detected.
 */
@Slf4j
@Singleton
public class PetTracker
{
    // Pet drop chat message patterns
    private static final Pattern PET_FOLLOWER_PATTERN =
        Pattern.compile("You have a funny feeling like you're being followed\\.");
    private static final Pattern PET_BACKPACK_PATTERN =
        Pattern.compile("You feel something weird sneaking into your backpack\\.");
    private static final Pattern PET_DUPLICATE_PATTERN =
        Pattern.compile("You have a funny feeling like you would have been followed");

    private final Client client;
    private final ClientThread clientThread;
    private final ApiClient apiClient;
    private final OsrsTrackerConfig config;
    private final VideoRecorder videoRecorder;

    @Inject
    public PetTracker(
            Client client,
            ClientThread clientThread,
            ApiClient apiClient,
            OsrsTrackerConfig config,
            VideoRecorder videoRecorder)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.apiClient = apiClient;
        this.config = config;
        this.videoRecorder = videoRecorder;
    }

    /**
     * Process a game chat message to detect pet drops.
     * Called by the main plugin's onChatMessage handler.
     *
     * @param message The chat message text (should already have HTML tags removed)
     */
    public void processGameMessage(String message)
    {
        if (!config.trackPets())
        {
            return;
        }

        if (PET_FOLLOWER_PATTERN.matcher(message).find())
        {
            handlePetDrop(false, false);
        }
        else if (PET_BACKPACK_PATTERN.matcher(message).find())
        {
            handlePetDrop(true, false);
        }
        else if (PET_DUPLICATE_PATTERN.matcher(message).find())
        {
            handlePetDrop(false, true);
        }
    }

    /**
     * Handle a detected pet drop event.
     *
     * @param toBackpack Whether the pet went to the player's inventory
     * @param isDuplicate Whether this is a duplicate pet (player already has it)
     */
    private void handlePetDrop(boolean toBackpack, boolean isDuplicate)
    {
        log.info("Pet drop detected! Backpack: {}, Duplicate: {}", toBackpack, isDuplicate);

        // Try to identify the pet from the player's follower
        // Use invokeLater to ensure we're on the client thread and follower has spawned
        clientThread.invokeLater(() -> {
            String petName = identifyPet(toBackpack);
            int petNpcId = getPetNpcId();

            log.info("Pet identified: {} (NPC ID: {})", petName, petNpcId);

            // Capture extended video and send to API
            sendPetDropToApi(petName, petNpcId, toBackpack, isDuplicate);
        });
    }

    /**
     * Attempt to identify the pet that was just obtained.
     * For normal drops, checks the player's follower NPC.
     * For backpack drops, we can't easily identify without checking inventory changes.
     *
     * @param toBackpack Whether the pet went to backpack
     * @return The pet name if identified, or "Unknown Pet" if not
     */
    private String identifyPet(boolean toBackpack)
    {
        if (toBackpack)
        {
            // Pet went to inventory - harder to identify without tracking inventory changes
            // For now, return unknown - could be enhanced to check recent inventory additions
            return "Unknown Pet";
        }

        // Check the player's current follower
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null)
        {
            return "Unknown Pet";
        }

        // The follower is stored as an interacting NPC or can be found nearby
        // Check all NPCs for one that's following the player
        NPC follower = findPlayerFollower(localPlayer);
        if (follower != null)
        {
            String name = follower.getName();
            if (name != null && !name.isEmpty())
            {
                return name;
            }
        }

        return "Unknown Pet";
    }

    /**
     * Find the NPC that is following the local player (the pet).
     *
     * @param localPlayer The local player
     * @return The follower NPC, or null if not found
     */
    private NPC findPlayerFollower(Player localPlayer)
    {
        // Pets are typically very close to the player and interacting with them
        for (NPC npc : client.getNpcs())
        {
            if (npc == null)
            {
                continue;
            }

            // Check if this NPC is interacting with (following) the player
            if (npc.getInteracting() == localPlayer)
            {
                // Additional check: pets are typically within 1 tile of player
                int distance = localPlayer.getWorldLocation().distanceTo(npc.getWorldLocation());
                if (distance <= 1)
                {
                    return npc;
                }
            }
        }

        // Fallback: find any NPC very close to player that could be a pet
        // Pets typically have small models and are right next to the player
        for (NPC npc : client.getNpcs())
        {
            if (npc == null)
            {
                continue;
            }

            int distance = localPlayer.getWorldLocation().distanceTo(npc.getWorldLocation());
            if (distance == 0)
            {
                // NPC on same tile as player - likely a follower/pet
                return npc;
            }
        }

        return null;
    }

    /**
     * Get the NPC ID of the player's current pet follower.
     *
     * @return The NPC ID, or -1 if not found
     */
    private int getPetNpcId()
    {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null)
        {
            return -1;
        }

        NPC follower = findPlayerFollower(localPlayer);
        if (follower != null)
        {
            return follower.getId();
        }

        return -1;
    }

    /**
     * Send the pet drop event to the API with extended video capture.
     *
     * @param petName The name of the pet
     * @param npcId The NPC ID of the pet
     * @param toBackpack Whether the pet went to inventory
     * @param isDuplicate Whether this is a duplicate pet
     */
    private void sendPetDropToApi(String petName, int npcId, boolean toBackpack, boolean isDuplicate)
    {
        // Build the payload
        JsonObject payload = new JsonObject();
        payload.addProperty("pet_name", petName);
        payload.addProperty("npc_id", npcId);
        payload.addProperty("to_backpack", toBackpack);
        payload.addProperty("is_duplicate", isDuplicate);
        payload.addProperty("timestamp", System.currentTimeMillis());

        // Add player name if available
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer != null && localPlayer.getName() != null)
        {
            payload.addProperty("player_name", localPlayer.getName());
        }

        // Capture video with standard duration (6s buffer + 4s post)
        videoRecorder.captureEventVideo((screenshotBase64, videoBase64) -> {
            String eventDescription = isDuplicate
                ? "Duplicate pet: " + petName
                : "Pet drop: " + petName;

            apiClient.sendEventToApi(
                "/api/webhooks/pet_drop",
                payload.toString(),
                eventDescription,
                screenshotBase64,
                videoBase64
            );

            log.info("Pet drop event sent to API: {}", eventDescription);
        });
    }
}
