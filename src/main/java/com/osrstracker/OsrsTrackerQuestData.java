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

import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;

/**
 * Quest data extracted from Quest Helper plugin
 * Uses direct varbit/varplayer reading to bypass RuneLite's broken Quest.getState()
 */
public class OsrsTrackerQuestData
{
    /**
     * Check if a quest is completed using Quest Helper's approach
     * This reads varbits/varplayers directly with exact completion values
     */
    public static boolean isQuestComplete(Client client, String questName)
    {
        try
        {
            // PRIORITY 1: Try RuneLite's Quest enum first
            // This calls client.runScript(ScriptID.QUEST_STATUS_GET) which initializes varbit cache
            for (Quest quest : Quest.values())
            {
                if (quest.getName().equalsIgnoreCase(questName))
                {
                    return quest.getState(client) == QuestState.FINISHED;
                }
            }

            // PRIORITY 2: Try varplayer-based detection
            QuestData questData = QUEST_DATA.get(questName);
            if (questData != null && questData.varplayerId != null)
            {
                int value = client.getVarpValue(questData.varplayerId);
                if (questData.completeValue != null)
                {
                    return value == questData.completeValue;
                }
                // Varplayers typically use 2 for FINISHED
                return value >= 2;
            }

            // PRIORITY 3: Varbit-only quests are skipped for now
            // Varbits aren't initialized until QUEST_STATUS_GET script runs
            // And we can't run that script without a Quest ID from the enum
        }
        catch (Exception e)
        {
            // If we can't read varbits/varplayers (client not ready), silently fail
            return false;
        }

        return false;
    }

    private static class QuestData
    {
        Integer varbitId;
        Integer varplayerId;
        Integer completeValue;

        QuestData(Integer varbitId, Integer varplayerId, Integer completeValue)
        {
            this.varbitId = varbitId;
            this.varplayerId = varplayerId;
            this.completeValue = completeValue;
        }
    }

    // Combined map of quest names to their detection data with EXACT completion values from Quest Helper
    private static final java.util.Map<String, QuestData> QUEST_DATA = new java.util.HashMap<String, QuestData>()
    {{
        // F2P Quests - exact completion values from Quest Helper
        put("Below Ice Mountain", new QuestData(12063, null, 40));
        put("Black Knights' Fortress", new QuestData(null, 130, 3));
        put("Cook's Assistant", new QuestData(null, 29, 1));
        put("Demon Slayer", new QuestData(2561, null, 2));
        put("Doric's Quest", new QuestData(null, 31, 10));
        put("Dragon Slayer I", new QuestData(null, 176, 9));
        put("Ernest the Chicken", new QuestData(null, 32, 2));
        put("Goblin Diplomacy", new QuestData(2378, null, 5));
        put("Imp Catcher", new QuestData(null, 160, 1));
        put("Misthalin Mystery", new QuestData(3468, null, 130));
        put("Pirate's Treasure", new QuestData(null, 71, 3));
        put("Prince Ali Rescue", new QuestData(null, 273, 100));
        put("Romeo & Juliet", new QuestData(null, 144, 60));
        put("Rune Mysteries", new QuestData(null, 63, 5));
        put("Sheep Shearer", new QuestData(null, 179, 0));
        put("The Corsair Curse", new QuestData(6071, null, 55));
        put("The Knight's Sword", new QuestData(null, 122, 6));
        put("The Restless Ghost", new QuestData(null, 107, 4));
        put("Vampyre Slayer", new QuestData(null, 178, 2));
        put("Witch's Potion", new QuestData(null, 67, 2));
        put("X Marks the Spot", new QuestData(8063, null, 7));

        // P2P Quests - exact completion values from Quest Helper
        put("A Kingdom Divided", new QuestData(12296, null, 150));
        put("A Night at the Theatre", new QuestData(12276, null, 86));
        put("A Porcine of Interest", new QuestData(10582, null, 35));
        put("A Soul's Bane", new QuestData(2011, null, 12));
        put("A Tail of Two Cats", new QuestData(1028, null, 65));
        put("A Taste of Hope", new QuestData(6396, null, 160));
        put("Animal Magnetism", new QuestData(3185, null, 230));
        put("Another Slice of H.A.M.", new QuestData(3550, null, 10));
        put("At First Light", new QuestData(9835, null, 11));
        put("Bear Your Soul", new QuestData(5078, null, 2));
        put("Beneath Cursed Sands", new QuestData(13841, null, 106));
        put("Between a Rock...", new QuestData(299, null, 100));
        put("Big Chompy Bird Hunting", new QuestData(null, 293, 60));
        put("Biohazard", new QuestData(null, 68, 15));
        put("Bone Voyage", new QuestData(5795, null, 35));
        put("Cabin Fever", new QuestData(null, 655, 130));
        put("Children of the Sun", new QuestData(9632, null, 22));
        put("Client of Kourend", new QuestData(5619, null, 6));
        put("Clock Tower", new QuestData(null, 10, 7));
        put("Cold War", new QuestData(3293, null, 130));
        put("Contact!", new QuestData(3274, null, 120));
        put("Creature of Fenkenstrain", new QuestData(null, 399, 6));
        put("Darkness of Hallowvale", new QuestData(2573, null, 310));
        put("Death on the Isle", new QuestData(11210, null, 49));
        put("Death Plateau", new QuestData(null, 314, 70));
        put("Death to the Dorgeshuun", new QuestData(2258, null, 12));
        put("Defender of Varrock", new QuestData(9655, null, 54));
        put("Desert Treasure I", new QuestData(358, null, 14));
        put("Desert Treasure II - The Fallen Empire", new QuestData(14862, null, 114));
        put("Devious Minds", new QuestData(1465, null, 70));
        put("Dragon Slayer II", new QuestData(6104, null, 210));
        put("Dream Mentor", new QuestData(3618, null, 26));
        put("Druidic Ritual", new QuestData(null, 80, 3));
        put("Dwarf Cannon", new QuestData(null, 0, 10));
        put("Eadgar's Ruse", new QuestData(null, 335, 100));
        put("Eagles' Peak", new QuestData(2780, null, 35));
        put("Elemental Workshop I", new QuestData(null, 299, 2));
        put("Elemental Workshop II", new QuestData(2639, null, 10));
        put("Enakhra's Lament", new QuestData(1560, null, 60));
        put("Enlightened Journey", new QuestData(2866, null, 100));
        put("Enter the Abyss", new QuestData(null, 492, 3));
        put("Ethically Acquired Antiquities", new QuestData(11193, null, 36));
        put("Fairytale I - Growing Pains", new QuestData(1803, null, 80));
        put("Fairytale II - Cure a Queen", new QuestData(2326, null, 80));
        put("Family Crest", new QuestData(null, 148, 10));
        put("Fight Arena", new QuestData(null, 17, 14));
        put("Fishing Contest", new QuestData(null, 11, 4));
        put("Forgettable Tale...", new QuestData(822, null, 130));
        put("Garden of Tranquillity", new QuestData(961, null, 50));
        put("Gertrude's Cat", new QuestData(null, 180, 5));
        put("Getting Ahead", new QuestData(693, null, 32));
        put("Ghosts Ahoy", new QuestData(217, null, 7));
        put("Grim Tales", new QuestData(2783, null, 50));
        put("Haunted Mine", new QuestData(null, 382, 10));
        put("Hazeel Cult", new QuestData(null, 223, 7));
        put("Heroes' Quest", new QuestData(null, 188, 13));
        put("Holy Grail", new QuestData(null, 5, 9));
        put("Horror from the Deep", new QuestData(34, null, 5));
        put("In Aid of the Myreque", new QuestData(1990, null, 420));
        put("In Search of Knowledge", new QuestData(8403, null, 2));
        put("In Search of the Myreque", new QuestData(null, 387, 105));
        put("Jungle Potion", new QuestData(null, 175, 11));
        put("King's Ransom", new QuestData(3888, null, 85));
        put("Land of the Goblins", new QuestData(13599, null, 52));
        put("Legends' Quest", new QuestData(null, 139, 70));
        put("Lost City", new QuestData(null, 147, 5));
        put("Lunar Diplomacy", new QuestData(2448, null, 180));
        put("Making Friends with My Arm", new QuestData(6528, null, 196));
        put("Making History", new QuestData(1383, null, 3));
        put("Meat and Greet", new QuestData(11182, null, 24));
        put("Merlin's Crystal", new QuestData(null, 14, 6));
        put("Monkey Madness I", new QuestData(null, 365, 7));
        put("Monkey Madness II", new QuestData(5027, null, 190));
        put("Monk's Friend", new QuestData(null, 30, 70));
        put("Mountain Daughter", new QuestData(260, null, 60));
        put("Mourning's End Part I", new QuestData(null, 517, 8));
        put("Mourning's End Part II", new QuestData(1103, null, 50));
        put("Murder Mystery", new QuestData(null, 192, 1));
        put("My Arm's Big Adventure", new QuestData(2790, null, 310));
        put("Nature Spirit", new QuestData(null, 307, 105));
        put("Observatory Quest", new QuestData(null, 112, 6));
        put("Olaf's Quest", new QuestData(3534, null, 70));
        put("One Small Favour", new QuestData(null, 416, 275));
        put("Perilous Moons", new QuestData(9819, null, 31));
        put("Plague City", new QuestData(null, 165, 28));
        put("Priest in Peril", new QuestData(null, 302, 59));
        put("Rag and Bone Man I", new QuestData(null, 714, 3));
        put("Rag and Bone Man II", new QuestData(null, 714, 5));
        put("Ratcatchers", new QuestData(1404, null, 125));
        put("Recipe for Disaster", new QuestData(1850, null, 4));
        put("Recruitment Drive", new QuestData(657, null, 1));
        put("Regicide", new QuestData(null, 328, 14));
        put("Roving Elves", new QuestData(null, 402, 5));
        put("Royal Trouble", new QuestData(2140, null, 20));
        put("Rum Deal", new QuestData(null, 600, 18));
        put("Scorpion Catcher", new QuestData(null, 76, 3));
        put("Scrambled!", new QuestData(16758, null, 28));
        put("Sea Slug", new QuestData(null, 159, 11));
        put("Secrets of the North", new QuestData(14722, null, 88));
        put("Shades of Mort'ton", new QuestData(null, 339, 80));
        put("Shadow of the Storm", new QuestData(1372, null, 124));
        put("Shadows of Custodia", new QuestData(16632, null, 22));
        put("Sheep Herder", new QuestData(null, 60, 2));
        put("Shilo Village", new QuestData(null, 116, 14));
        put("Sins of the Father", new QuestData(7255, null, 136));
        put("Sleeping Giants", new QuestData(13902, null, 25));
        put("Song of the Elves", new QuestData(9016, null, 192));
        put("Spirits of the Elid", new QuestData(1444, null, 55));
        put("Swan Song", new QuestData(2098, null, 190));
        put("Tai Bwo Wannai Trio", new QuestData(null, 320, 5));
        put("Tale of the Righteous", new QuestData(6358, null, 16));
        put("Tears of Guthix", new QuestData(451, null, 1));
        put("Temple of Ikov", new QuestData(null, 26, 70));
        put("Temple of the Eye", new QuestData(13738, null, 125));
        put("The Ascent of Arceuus", new QuestData(7856, null, 13));
        put("The Curse of Arrav", new QuestData(11479, null, 58));
        put("The Depths of Despair", new QuestData(6027, null, 10));
        put("The Dig Site", new QuestData(null, 131, 8));
        put("The Eyes of Glouphrie", new QuestData(2497, null, 50));
        put("The Feud", new QuestData(334, null, 27));
        put("The Final Dawn", new QuestData(16663, null, 67));
        put("The Forsaken Tower", new QuestData(7796, null, 10));
        put("The Fremennik Exiles", new QuestData(9459, null, 125));
        put("The Fremennik Isles", new QuestData(3311, null, 332));
        put("The Fremennik Trials", new QuestData(null, 347, 8));
        put("The Garden of Death", new QuestData(14609, null, 54));
        put("The Giant Dwarf", new QuestData(571, null, 40));
        put("The Golem", new QuestData(346, null, 7));
        put("The Grand Tree", new QuestData(null, 150, 150));
        put("The Great Brain Robbery", new QuestData(null, 980, 120));
        put("The Hand in the Sand", new QuestData(1527, null, 150));
        put("The Heart of Darkness", new QuestData(11117, null, 74));
        put("The Lost Tribe", new QuestData(532, null, 10));
        put("The Queen of Thieves", new QuestData(6037, null, 12));
        put("The Ribbiting Tale of a Lily Pad Labour Dispute", new QuestData(9844, null, 30));
        put("The Slug Menace", new QuestData(2610, null, 12));
        put("The Tourist Trap", new QuestData(null, 197, 29));
        put("Throne of Miscellania", new QuestData(null, 359, 90));
        put("Tower of Life", new QuestData(3337, null, 17));
        put("Tree Gnome Village", new QuestData(null, 111, 8));
        put("Troll Romance", new QuestData(null, 385, 40));
        put("Troll Stronghold", new QuestData(null, 317, 40));
        put("Twilight's Promise", new QuestData(9649, null, 48));
        put("Underground Pass", new QuestData(null, 161, 2));
        put("Wanted!", new QuestData(1051, null, 10));
        put("Watchtower", new QuestData(null, 212, 12));
        put("Waterfall Quest", new QuestData(null, 65, 8));
        put("What Lies Below", new QuestData(3523, null, 140));
        put("While Guthix Sleeps", new QuestData(9653, null, 890));
        put("Witch's House", new QuestData(null, 226, 6));
        put("Zogre Flesh Eaters", new QuestData(487, null, 12));
    }};
}
