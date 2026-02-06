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
package com.osrstracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import com.osrstracker.itemsnitch.ItemSnitchVerbosity;
import com.osrstracker.video.VideoQuality;

@ConfigGroup("osrstracker")
public interface OsrsTrackerConfig extends Config
{
    // Production API URL - used when apiUrl config is empty
    String PRODUCTION_API_URL = "https://osrs-tracker.com";

    // Minimum loot value (100k GP)
    int MINIMUM_LOOT_VALUE = 100000;

    // Environment variable to enable dev mode (shows API URL config)
    // Set OSRS_TRACKER_DEV=true to enable
    static boolean isDevMode()
    {
        return "true".equalsIgnoreCase(System.getenv("OSRS_TRACKER_DEV"));
    }

    /**
     * Parses a GP value string that supports k (thousands) and m (millions) suffixes.
     * Examples: "100k" = 100000, "1.5m" = 1500000, "50000" = 50000
     *
     * @param value The string value to parse
     * @return The parsed integer value, or 0 if parsing fails
     */
    static int parseGpValue(String value)
    {
        if (value == null || value.trim().isEmpty())
        {
            return 0;
        }

        String cleaned = value.trim().toLowerCase().replace(",", "");

        try
        {
            double multiplier = 1;

            if (cleaned.endsWith("m"))
            {
                multiplier = 1_000_000;
                cleaned = cleaned.substring(0, cleaned.length() - 1);
            }
            else if (cleaned.endsWith("k"))
            {
                multiplier = 1_000;
                cleaned = cleaned.substring(0, cleaned.length() - 1);
            }

            double parsed = Double.parseDouble(cleaned);
            return (int) (parsed * multiplier);
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
    }
    @ConfigSection(
        name = "API Settings",
        description = "Configure your OSRS Tracker API connection",
        position = 0
    )
    String apiSection = "api";

    @ConfigSection(
        name = "Tracking Options",
        description = "Choose what to track",
        position = 1
    )
    String trackingSection = "tracking";

    @ConfigSection(
        name = "Loot Settings",
        description = "Configure loot tracking options",
        position = 2
    )
    String lootSection = "loot";

    @ConfigSection(
        name = "Clue Scrolls",
        description = "Configure clue scroll tracking options",
        position = 3
    )
    String clueSection = "clue";

    @ConfigSection(
        name = "Item Snitch",
        description = "Track shared clan items in your bank",
        position = 4
    )
    String itemSnitchSection = "itemsnitch";

    @ConfigSection(
        name = "Video Settings",
        description = "Configure video recording quality (based on available heap memory)",
        position = 5
    )
    String videoSection = "video";

    // ===== API Settings =====

    /**
     * Returns the effective API URL to use.
     * In dev mode (OSRS_TRACKER_DEV=true), checks OSRS_TRACKER_API_URL env var.
     * Otherwise uses production URL.
     *
     * Note: This is static to avoid RuneLite treating it as a config item.
     */
    static String getEffectiveApiUrl()
    {
        if (isDevMode())
        {
            String devUrl = System.getenv("OSRS_TRACKER_API_URL");
            if (devUrl != null && !devUrl.isEmpty())
            {
                return devUrl;
            }
        }
        return PRODUCTION_API_URL;
    }

    @ConfigItem(
        keyName = "apiToken",
        name = "API Token",
        description = "Your API token from osrs-tracker.com (Settings → API Tokens). Tracking is disabled until configured.",
        section = apiSection,
        position = 1,
        secret = true
    )
    default String apiToken()
    {
        return "";
    }

    // ===== Tracking Options =====

    @ConfigItem(
        keyName = "trackLevelUps",
        name = "Track Level-ups",
        description = "Automatically send level-ups to your tracker",
        section = trackingSection,
        position = 0
    )
    default boolean trackLevelUps()
    {
        return true;
    }

    @ConfigItem(
        keyName = "trackQuests",
        name = "Track Quests",
        description = "Automatically send quest completions to your tracker",
        section = trackingSection,
        position = 1
    )
    default boolean trackQuests()
    {
        return true;
    }

    @ConfigItem(
        keyName = "trackLoot",
        name = "Track Loot Drops",
        description = "Automatically send loot drops to your tracker",
        section = trackingSection,
        position = 2
    )
    default boolean trackLoot()
    {
        return true;
    }

    @ConfigItem(
        keyName = "trackCollectionLog",
        name = "Track Collection Log",
        description = "Automatically send collection log updates to your tracker",
        section = trackingSection,
        position = 3
    )
    default boolean trackCollectionLog()
    {
        return true;
    }

    @ConfigItem(
        keyName = "trackDeaths",
        name = "Track Deaths",
        description = "Automatically send death events to your tracker",
        section = trackingSection,
        position = 4
    )
    default boolean trackDeaths()
    {
        return true;
    }

    @ConfigItem(
        keyName = "trackPets",
        name = "Track Pet Drops",
        description = "Automatically send pet drop events to your tracker with extended 20-second video capture",
        section = trackingSection,
        position = 5
    )
    default boolean trackPets()
    {
        return true;
    }

    // ===== Loot Settings =====

    @ConfigItem(
        keyName = "minimumLootValue",
        name = "Minimum Loot Value",
        description = "Only send loot drops worth at least this amount. Use k for thousands, m for millions (e.g., 100k, 1.5m). Minimum: 100k",
        section = lootSection,
        position = 0
    )
    default String minimumLootValue()
    {
        return "100k";
    }

    /**
     * Gets the minimum loot value as an integer, enforcing the 100k minimum.
     * Parses k/m suffixes (e.g., "100k" = 100000, "1.5m" = 1500000).
     *
     * @return The minimum loot value in GP, at least 100k
     */
    static int getMinimumLootValue(OsrsTrackerConfig config)
    {
        int value = parseGpValue(config.minimumLootValue());
        return Math.max(value, MINIMUM_LOOT_VALUE);
    }

    // ===== Clue Scroll Settings =====

    @ConfigItem(
        keyName = "trackClueScrolls",
        name = "Track Clue Scrolls",
        description = "Automatically send clue scroll rewards to your tracker",
        section = clueSection,
        position = 0
    )
    default boolean trackClueScrolls()
    {
        return true;
    }

    @ConfigItem(
        keyName = "clueScreenshot",
        name = "Include Screenshot/Video",
        description = "Capture a screenshot and video of the clue reward interface",
        section = clueSection,
        position = 1
    )
    default boolean clueScreenshot()
    {
        return true;
    }

    // ===== Item Snitch Settings =====

    @ConfigItem(
        keyName = "trackItemSnitch",
        name = "Enable Item Snitch",
        description = "Track shared clan items in your bank and alert when closing bank",
        section = itemSnitchSection,
        position = 0
    )
    default boolean trackItemSnitch()
    {
        return true;
    }

    @ConfigItem(
        keyName = "itemSnitchWarnings",
        name = "Show Chat Warnings",
        description = "Show a chat message when closing your bank with shared items inside",
        section = itemSnitchSection,
        position = 1
    )
    default boolean itemSnitchWarnings()
    {
        return true;
    }

    @ConfigItem(
        keyName = "itemSnitchVerbosity",
        name = "Warning Verbosity",
        description = "How detailed should the chat warning be?",
        section = itemSnitchSection,
        position = 2
    )
    default ItemSnitchVerbosity itemSnitchVerbosity()
    {
        return ItemSnitchVerbosity.MINIMAL;
    }

    @ConfigItem(
        keyName = "itemSnitchReportSightings",
        name = "Report Sightings",
        description = "Automatically report shared item sightings to your group for tracking",
        section = itemSnitchSection,
        position = 3
    )
    default boolean itemSnitchReportSightings()
    {
        return true;
    }

    @ConfigItem(
        keyName = "itemSnitchHighlight",
        name = "Highlight Items in Bank",
        description = "Draw a highlight border around shared items when viewing your bank",
        section = itemSnitchSection,
        position = 4
    )
    default boolean itemSnitchHighlight()
    {
        return true;
    }

    // ===== Video Settings =====

    @ConfigItem(
        keyName = "videoQuality",
        name = "Recording Quality",
        description = "Choose video recording quality. Higher quality means larger uploads.",
        section = videoSection,
        position = 0
    )
    default VideoQuality videoQuality()
    {
        return VideoQuality.getDefault();
    }
}
