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

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Tracks pet drops by monitoring game chat messages.
 *
 * Pet drop messages appear in these formats:
 * - "You have a funny feeling like you're being followed." (new pet, becomes follower)
 * - "You feel something weird sneaking into your backpack." (pet goes to inventory - had another follower)
 * - "You have a funny feeling like you would have been followed..." (duplicate pet)
 *
 * Pets get extended 20-second video capture to celebrate the moment!
 */
@Slf4j
@Singleton
public class PetTracker
{
    // Pet drop message patterns
    private static final String PET_NEW_FOLLOWER = "You have a funny feeling like you're being followed.";
    private static final String PET_TO_BACKPACK = "You feel something weird sneaking into your backpack.";
    private static final String PET_DUPLICATE = "You have a funny feeling like you would have been followed";

    // Extended video duration for pet drops (20 seconds post-event)
    private static final int PET_VIDEO_DURATION_MS = 20000;

    // Map of pet NPC IDs to pet names
    private static final Map<Integer, String> PET_NAMES = new HashMap<>();

    static
    {
        // Boss pets
        PET_NAMES.put(6555, "Pet snakeling"); // Zulrah
        PET_NAMES.put(8201, "Vorki"); // Vorkath
        PET_NAMES.put(5883, "Hellpuppy"); // Cerberus
        PET_NAMES.put(6639, "Pet kraken"); // Kraken
        PET_NAMES.put(6647, "Smoke devil"); // Thermonuclear smoke devil
        PET_NAMES.put(6637, "Abyssal orphan"); // Abyssal Sire
        PET_NAMES.put(6656, "Pet dark core"); // Corporeal Beast
        PET_NAMES.put(6638, "Skotos"); // Skotizo
        PET_NAMES.put(6636, "Jal-nib-rek"); // TzKal-Zuk
        PET_NAMES.put(5892, "TzRek-Jad"); // TzTok-Jad
        PET_NAMES.put(8619, "Ikkle hydra"); // Alchemical Hydra
        PET_NAMES.put(2130, "Baby mole"); // Giant Mole
        PET_NAMES.put(6641, "Kalphite princess"); // Kalphite Queen
        PET_NAMES.put(6632, "Prince black dragon"); // King Black Dragon
        PET_NAMES.put(5561, "Pet chaos elemental"); // Chaos Elemental

        // GWD pets
        PET_NAMES.put(6633, "Pet zilyana"); // Commander Zilyana
        PET_NAMES.put(6631, "Pet general graardor"); // General Graardor
        PET_NAMES.put(6630, "Pet kree'arra"); // Kree'arra
        PET_NAMES.put(6634, "Pet k'ril tsutsaroth"); // K'ril Tsutsaroth

        // DKS pets
        PET_NAMES.put(6628, "Pet dagannoth prime"); // Dagannoth Prime
        PET_NAMES.put(6627, "Pet dagannoth supreme"); // Dagannoth Supreme
        PET_NAMES.put(6626, "Pet dagannoth rex"); // Dagannoth Rex

        // Wilderness boss pets
        PET_NAMES.put(6623, "Venenatis spiderling"); // Venenatis
        PET_NAMES.put(6624, "Callisto cub"); // Callisto
        PET_NAMES.put(6625, "Vet'ion jr."); // Vet'ion
        PET_NAMES.put(6622, "Scorpia's offspring"); // Scorpia
        PET_NAMES.put(11992, "Artio cub"); // Artio
        PET_NAMES.put(11993, "Calvar'ion jr."); // Calvar'ion
        PET_NAMES.put(11994, "Spindel spiderling"); // Spindel

        // Skilling pets
        PET_NAMES.put(7370, "Rock golem"); // Mining
        PET_NAMES.put(7372, "Heron"); // Fishing
        PET_NAMES.put(7373, "Beaver"); // Woodcutting
        PET_NAMES.put(6758, "Baby chinchompa"); // Hunter
        PET_NAMES.put(7371, "Giant squirrel"); // Agility
        PET_NAMES.put(6716, "Tangleroot"); // Farming
        PET_NAMES.put(6655, "Rocky"); // Thieving
        PET_NAMES.put(7374, "Rift guardian"); // Runecraft

        // Raid pets
        PET_NAMES.put(7520, "Olmlet"); // Chambers of Xeric
        PET_NAMES.put(8337, "Lil' zik"); // Theatre of Blood
        PET_NAMES.put(11848, "Tumeken's guardian"); // Tombs of Amascut

        // Gauntlet pets
        PET_NAMES.put(8738, "Youngllef"); // The Gauntlet

        // Nightmare pets
        PET_NAMES.put(9408, "Little nightmare"); // The Nightmare / Phosani's Nightmare

        // Nex pet
        PET_NAMES.put(11279, "Nexling"); // Nex

        // DT2 boss pets
        PET_NAMES.put(12129, "Muphin"); // Phantom Muspah
        PET_NAMES.put(12128, "Baron"); // Duke Sucellus
        PET_NAMES.put(12125, "Butch"); // The Leviathan
        PET_NAMES.put(12127, "Wisp"); // The Whisperer
        PET_NAMES.put(12126, "Lilviathan"); // Vardorvis (Lilviathan)

        // Slayer pets
        PET_NAMES.put(7920, "Noon"); // Grotesque Guardians
        PET_NAMES.put(7921, "Midnight"); // Grotesque Guardians (alternate)
        PET_NAMES.put(7293, "Pet penance queen"); // Barbarian Assault

        // Other boss pets
        PET_NAMES.put(6649, "Snakeling"); // Zulrah (alternate form)
        PET_NAMES.put(8658, "Sraracha"); // Sarachnis
        PET_NAMES.put(9844, "Little parasite"); // Tempoross
        PET_NAMES.put(11281, "Muttamix"); // Wintertodt phoenix (alternative)
        PET_NAMES.put(8492, "Phoenix"); // Wintertodt
        PET_NAMES.put(6719, "Pet phoenix"); // Wintertodt (alternative ID)
        PET_NAMES.put(5560, "Chompy chick"); // Chompy bird hunting
        PET_NAMES.put(12479, "Smolcano"); // Zalcano
        PET_NAMES.put(8487, "Herbi"); // Herbiboar
        PET_NAMES.put(8583, "Tangleroot"); // Hespori (uses Tangleroot model)
        PET_NAMES.put(7249, "Bloodhound"); // Master clue scroll
        PET_NAMES.put(7248, "Bloodhound"); // Bloodhound (alternate ID)

        // More skilling pets
        PET_NAMES.put(6721, "Pet rock golem"); // Mining (alternate)
        PET_NAMES.put(6717, "Baby chinchompa"); // Hunter (alternate)

        // Tempoross pet
        PET_NAMES.put(10584, "Tiny tempor"); // Tempoross

        // Scurrius
        PET_NAMES.put(7223, "Scurry"); // Scurrius

        // Sol Heredit
        PET_NAMES.put(13015, "Smol heredit"); // Sol Heredit
    }

    private final Client client;
    private final ApiClient apiClient;
    private final OsrsTrackerConfig config;
    private final VideoRecorder videoRecorder;

    @Inject
    public PetTracker(Client client, ApiClient apiClient, OsrsTrackerConfig config, VideoRecorder videoRecorder)
    {
        this.client = client;
        this.apiClient = apiClient;
        this.config = config;
        this.videoRecorder = videoRecorder;
    }

    /**
     * Processes a game chat message to check for pet drop events.
     *
     * @param chatMessage The chat message text (should already have HTML tags removed)
     * @return true if a pet drop was detected and processed, false otherwise
     */
    public boolean processGameMessage(String chatMessage)
    {
        if (!config.trackPets())
        {
            return false;
        }

        boolean isDuplicate = false;
        boolean toBackpack = false;
        boolean isPetDrop = false;

        if (chatMessage.contains(PET_NEW_FOLLOWER))
        {
            isPetDrop = true;
            log.info("Pet drop detected: new follower!");
        }
        else if (chatMessage.contains(PET_TO_BACKPACK))
        {
            isPetDrop = true;
            toBackpack = true;
            log.info("Pet drop detected: went to backpack!");
        }
        else if (chatMessage.contains(PET_DUPLICATE))
        {
            isPetDrop = true;
            isDuplicate = true;
            log.info("Pet drop detected: duplicate!");
        }

        if (isPetDrop)
        {
            sendPetDropToApi(isDuplicate, toBackpack);
            return true;
        }

        return false;
    }

    /**
     * Attempts to identify the pet by looking at the player's current follower.
     *
     * @return The pet name, or "Unknown Pet" if not identifiable
     */
    private String identifyPet()
    {
        NPC follower = client.getFollower();
        if (follower != null)
        {
            int npcId = follower.getId();
            String petName = PET_NAMES.get(npcId);
            if (petName != null)
            {
                log.debug("Identified pet by follower NPC ID {}: {}", npcId, petName);
                return petName;
            }
            // Fallback to NPC name if not in our map
            String npcName = follower.getName();
            if (npcName != null && !npcName.isEmpty())
            {
                log.debug("Pet not in map, using NPC name: {} (ID: {})", npcName, npcId);
                return npcName;
            }
        }
        log.debug("Could not identify pet - no follower or unknown");
        return "Unknown Pet";
    }

    /**
     * Gets the NPC ID of the current follower.
     *
     * @return The follower NPC ID, or -1 if no follower
     */
    private int getFollowerNpcId()
    {
        NPC follower = client.getFollower();
        return follower != null ? follower.getId() : -1;
    }

    /**
     * Gets the player's RuneScape name.
     *
     * @return The player's display name, or "Unknown" if not available
     */
    private String getPlayerName()
    {
        if (client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
        {
            return client.getLocalPlayer().getName();
        }
        return "Unknown";
    }

    /**
     * Sends a pet drop event to the API with extended video capture.
     *
     * @param isDuplicate Whether this is a duplicate pet
     * @param toBackpack Whether the pet went to backpack
     */
    private void sendPetDropToApi(boolean isDuplicate, boolean toBackpack)
    {
        // Capture extended video for pet drops (20 seconds!)
        videoRecorder.captureEventVideo((screenshotBase64, videoKey) -> {
            String petName = identifyPet();
            int npcId = getFollowerNpcId();
            String rsn = getPlayerName();

            PetDropData petData = PetDropData.builder()
                .petName(petName)
                .npcId(npcId)
                .isDuplicate(isDuplicate)
                .toBackpack(toBackpack)
                .timestamp(System.currentTimeMillis())
                .screenshot(screenshotBase64)
                .videoKey(videoKey)
                .rsn(rsn)
                .build();

            log.info("Sending pet drop to API: {} (NPC ID: {}, duplicate: {}, toBackpack: {})",
                petName, npcId, isDuplicate, toBackpack);

            JsonObject payload = new JsonObject();
            payload.addProperty("pet_name", petData.getPetName());
            payload.addProperty("npc_id", petData.getNpcId());
            payload.addProperty("is_duplicate", petData.isDuplicate());
            payload.addProperty("to_backpack", petData.isToBackpack());
            payload.addProperty("timestamp", petData.getTimestamp());
            payload.addProperty("rsn", petData.getRsn());

            apiClient.sendEventToApi(
                "/api/webhooks/pet_drop",
                payload.toString(),
                "pet drop: " + petName,
                screenshotBase64,
                videoKey
            );
        }, null, PET_VIDEO_DURATION_MS);
    }

    /**
     * Gets the pet name for a given NPC ID.
     * Useful for external lookups.
     *
     * @param npcId The NPC ID to look up
     * @return The pet name, or null if not found
     */
    public static String getPetNameByNpcId(int npcId)
    {
        return PET_NAMES.get(npcId);
    }
}
