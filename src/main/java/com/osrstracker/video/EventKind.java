/*
 * Copyright (c) 2025, Dennis De Vulder
 * All rights reserved. See VulkanEncoder.java for full license text.
 */
package com.osrstracker.video;

public enum EventKind
{
    QUICK_CAPTURE("quick_captures"),
    LEVEL_UP("level_ups"),
    QUEST("quests"),
    LOOT("loot"),
    DEATH("deaths"),
    PET("pets"),
    COLLECTION_LOG("collection_logs"),
    CLUE_SCROLL("clue_scrolls"),
    BINGO("bingo"),
    GENERIC("");

    private final String subfolder;

    EventKind(String subfolder)
    {
        this.subfolder = subfolder;
    }

    public String subfolder()
    {
        return subfolder;
    }
}
