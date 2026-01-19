# OSRS Tracker RuneLite Plugin

A RuneLite plugin that integrates with [OSRS Tracker](https://osrs-tracker.com) to track clan shared items and record in-game achievements with video replay support.

## Features

### Item Snitch

Monitor your clan's shared storage and get notified when tracked items are spotted.

- **Shared Item Detection** - Automatically scans your bank for items your group is tracking
- **Variant Support** - Detects all variants of degradeable items (Barrows armor at any %, charged weapons, etc.)
- **Real-time Alerts** - Get notified when tracked items appear in shared storage
- **Automatic Sync** - Item list syncs automatically with your group's tracked items

### Achievement Tracking

- **Level-up Tracking** - Automatically records skill level-ups
- **Quest Completion Tracking** - Records completed quests with quest point rewards
- **Loot Drop Tracking** - Tracks valuable loot from bosses and NPCs (configurable minimum value)
- **Collection Log Updates** - Sends notifications for new collection log items
- **Clue Scroll Rewards** - Tracks clue scroll completions with reward details
- **Death Tracking** - Records deaths with location information

### Video Replays

- **Automatic Capture** - Records short video clips of your achievements
- **Configurable Quality** - Choose between Screenshot only, Low, Medium, High, or Ultra
- **Login Protection** - Automatically blurs login screens to protect credentials

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
3. Open your bank - the plugin scans for tracked items
4. Get notified when items are detected

### 4. Achievement Tracking Options

- **Track Level-ups** - Send skill level-ups
- **Track Quests** - Send quest completions
- **Track Loot Drops** - Send valuable loot (configurable minimum GP value)
- **Track Collection Log** - Send new collection log entries
- **Track Clue Scrolls** - Send clue scroll rewards
- **Track Deaths** - Send death events

## Sidebar Panel

The plugin adds an **OSRS Tracker** panel to your RuneLite sidebar with:

- **Quick Capture** button - Manually capture video clips
- **Status indicator** - Shows recording/uploading progress

## Privacy & Security

- **Tracking disabled by default** - No network requests until you configure your API token
- **Token stored locally** - API token is stored in your local RuneLite configuration
- **Login screen protection** - Video recording automatically blurs login screens

## Troubleshooting

### Item Snitch Not Working

1. Make sure you've joined a group on osrs-tracker.com
2. Check that your group has items added to track
3. Open your bank to trigger a scan
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
