# OSRS Tracker RuneLite Plugin

A RuneLite plugin that integrates with [OSRS Tracker](https://osrs-tracker.com) to track clan shared items and record in-game achievements with video replay support.

## Current Version: 1.1.0

See [CHANGELOG.md](CHANGELOG.md) for full release notes.

## Version Comparison

| Feature | v1.0.0 | v1.1.0 |
|---------|--------|--------|
| **Item Snitch** | Bank scanning only | Bank, shared chest, equipment, inventory |
| **Item Locations** | No location tracking | Reports where items are stored |
| **Pet Tracking** | ❌ | ✅ Detects all pet drop types |
| **Bingo System** | ❌ | ✅ Full event tracking & progress |
| **Raid Detection** | ❌ | ✅ CoX, ToB, ToA completions |
| **Gauntlet Detection** | ❌ | ✅ Normal & Corrupted |
| **Slayer Tasks** | ❌ | ✅ Task completion tracking |
| **Quota Notifications** | ❌ | ✅ In-game alerts when limits reached |
| **Level-up Spam** | 24 fake events on login | Fixed - only real level-ups |

## Features

### Item Snitch

Monitor your clan's shared storage and get notified when tracked items are spotted.

- **Multi-Location Tracking** - Scans bank, shared chest, equipment, and inventory
- **Location Reporting** - API receives where each item is stored
- **Variant Support** - Detects all variants of degradeable items (Barrows armor at any %, charged weapons, etc.)
- **Real-time Alerts** - Get notified when tracked items appear in shared storage
- **Smart Availability Status** - Shows if items are available, accounted for, partial, or missing
- **Automatic Sync** - Item list syncs automatically with your group's tracked items

### Pet Drop Tracking

Never miss recording a pet drop again!

- **All Drop Types** - Detects follower pets, backpack pets, and duplicates
- **Pet Identification** - Identifies pet by checking your follower NPC
- **Video Capture** - Automatically captures the moment with video and screenshot

### Bingo Event Tracking

Full integration with OSRS Tracker bingo events.

- **Automatic Subscription** - Fetches your active bingo subscriptions on login
- **Progress Reporting** - Reports kills, loot, clues, raids, and more
- **Proof Capture** - Screenshots at milestone intervals for verification
- **Sidebar Status** - Shows active event and tile progress

### Achievement Tracking

- **Level-up Tracking** - Automatically records skill level-ups
- **Quest Completion Tracking** - Records completed quests with quest point rewards
- **Loot Drop Tracking** - Tracks valuable loot from bosses and NPCs (configurable minimum value)
- **Collection Log Updates** - Sends notifications for new collection log items
- **Clue Scroll Rewards** - Tracks clue scroll completions with reward details
- **Death Tracking** - Records deaths with location information
- **Raid Completions** - Detects CoX, ToB, and ToA completions
- **Gauntlet Completions** - Tracks Normal and Corrupted Gauntlet
- **Slayer Tasks** - Reports slayer task completions

### Video Replays

- **Automatic Capture** - Records short video clips of your achievements
- **Configurable Quality** - Choose between Screenshot only, Low, Medium, High, or Ultra
- **Login Protection** - Automatically blurs login screens to protect credentials
- **Screenshot Fallback** - Falls back to screenshots when video quota exceeded

## Installation

### From Plugin Hub

Search for **OSRS Tracker** in the RuneLite Plugin Hub.

### Building from Source

```bash
git clone https://github.com/dennisdevulder/osrs-tracker-plugin.git
cd osrs-tracker-plugin
./gradlew build
```

The JAR will be in `build/libs/`.

## Getting Started

### 1. Create an Account

1. Go to [osrs-tracker.com](https://osrs-tracker.com) and create an account
2. Join or create a group for your clan
3. Navigate to **Settings → API Tokens** and generate a token

### 2. Configure the Plugin

In RuneLite, go to **Configuration** (wrench icon) → search for **OSRS Tracker**:

1. Enter your **API Token** from osrs-tracker.com
2. Tracking will start automatically once configured

### 3. Item Snitch Setup

1. Your group owner adds items to track on osrs-tracker.com
2. The plugin automatically syncs the tracked item list
3. Open your bank or shared chest - the plugin scans for tracked items
4. Get notified when items are detected
5. View item locations on the web dashboard

### 4. Achievement Tracking Options

- **Track Level-ups** - Send skill level-ups
- **Track Quests** - Send quest completions
- **Track Loot Drops** - Send valuable loot (configurable minimum GP value)
- **Track Collection Log** - Send new collection log entries
- **Track Clue Scrolls** - Send clue scroll rewards
- **Track Deaths** - Send death events
- **Track Pets** - Send pet drop events

## Sidebar Panel

The plugin adds an **OSRS Tracker** panel to your RuneLite sidebar with:

- **Quick Capture** button - Manually capture video clips
- **Status indicator** - Shows recording/uploading progress
- **Bingo Status** - Shows active event and tracking progress

## Privacy & Security

- **Tracking disabled by default** - No network requests until you configure your API token
- **Token stored locally** - API token is stored in your local RuneLite configuration
- **Login screen protection** - Video recording automatically blurs login screens

## Troubleshooting

### Item Snitch Not Working

1. Make sure you've joined a group on osrs-tracker.com
2. Check that your group has items added to track
3. Open your bank or shared chest to trigger a scan
4. Check RuneLite logs: Help → Open Logs Folder

### Events Not Sending

1. Check that your **API Token** is valid
2. Make sure you're logged into the game
3. Check RuneLite logs for errors

### Collection Log Not Detected

Enable in OSRS Settings:
- **Notifications** → **Collection log**: ON
- **Chat** → **Game messages**: ON

## Support

- **Issues**: [GitHub Issues](https://github.com/dennisdevulder/osrs-tracker-plugin/issues)
- **Website**: [osrs-tracker.com](https://osrs-tracker.com)

## License

BSD 2-Clause License - See [LICENSE](LICENSE) for details.

## Credits

- [RuneLite](https://runelite.net/) - Open source OSRS client
