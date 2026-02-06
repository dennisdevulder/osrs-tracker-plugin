# Changelog

All notable changes to the OSRS Tracker RuneLite plugin will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] - 2026-02-02

### Added
- **Pet Drop Tracking**
  - Detects all three pet drop message types:
    - "You have a funny feeling like you're being followed." (new pet becomes follower)
    - "You feel something weird sneaking into your backpack." (pet goes to inventory)
    - "You have a funny feeling like you would have been followed..." (duplicate pet)
  - Identifies pet by checking player's follower NPC
  - Captures video and screenshot, sends to `/api/webhooks/pet_drop`
  - Configurable via "Track Pet Drops" setting

- **Item Snitch Multi-Location Tracking**
  - Now tracks shared items across bank, shared chest, equipment, and inventory
  - Reports item location to API: `bank`, `shared_chest`, `equipment`, or `inventory`
  - Real-time updates when items move between containers
  - Properly clears stale sightings when items are moved
  - **Smart availability status for items with quantity > 1**:
    - Shows "✓ Available" (green) when at least 1 is in shared chest
    - Shows "✓ All Accounted For" (orange) when all found but none in chest
    - Shows "⚠ Partial" when some found but not enough total
    - Shows "✗ Missing" when no sightings yet

- **Bingo Event Tracking System**
  - Automatically fetches subscription data from API on login
  - Smart polling: fetches every 5 min if active event, skips if no event in next 6 hours
  - Sidebar panel shows active bingo event status
  - Reports boss kills, NPC kills, and clue completions to bingo progress API
  - Captures proof screenshots/videos at milestone intervals
  - Loot drops report to bingo system for `loot_item` tile tracking

- **Raid Completion Detection**
  - Detects CoX, ToB, and ToA completions via boss death or chat message
  - Includes deduplication to prevent double-reporting

- **Gauntlet Completion Detection**
  - Detects Normal Gauntlet and Corrupted Gauntlet completions
  - Distinguishes between variants for separate bingo tile tracking

- **Slayer Task Completion Detection**
  - Parses slayer task completion messages for task name and kill count
  - Reports to bingo system for slayer-related tiles

- **Subscription Override System**
  - In-game quota notifications when video/screenshot limits are reached
  - Screenshot fallback when video quota exceeded

### Fixed
- **Bank crash on open** - Removed broken shared chest method calls that didn't exist
- **Skill level spam on login** - Fixed 0→X transitions during initialization flooding API with 24 fake level-ups per login
- **Duplicate Item Snitch buttons** - Fixed button multiplying on each click due to bank layout refresh triggering button recreation
- **Item Snitch quantity accumulation** - Fixed sightings accumulating instead of updating, causing incorrect item counts
- **Stale item sightings** - Now clears old sightings when items move between locations
- **GIM Shared Storage constant** - Fixed incorrect GROUP_STORAGE constant
- **Clue scroll tracking** - Fixed clue completion detection and reward capture
- **Video capture issues** - Fixed FPS issues and capture timing
- **Skill tracker scheduler** - Added proper shutdown to prevent tasks running after logout
- **Log verbosity** - Reduced noisy debug logging in production

### Changed
- Video capture timing: 6s pre-event + 4s post-event (10s total)
- Death captures: 5s post-event

### Performance
- Cached sensitive content detection - checked every 500ms instead of every frame
- Reduced widget lookups from 30/sec to 2/sec during recording

## [0.1.1] - 2025-01-29

### Fixed
- **CRITICAL: Level-ups being missed due to repeated tracker re-initialization**
  - `GameState.LOGGED_IN` fires frequently during gameplay (region loads, cutscenes, etc.)
  - Each re-init overwrote skill baselines, losing any level-ups that occurred since last init
  - Added `sessionActive` flag to ensure initialization only happens once per login session
  - Now properly handles: new login, logout, and world hops
- Clue scroll rewards not being tracked (ClueScrollTracker was not receiving events)
  - Refactored to use delegation pattern consistent with other trackers
  - Main plugin now properly forwards WidgetLoaded and ChatMessage events
- Race condition when multiple collection log items trigger simultaneously
  - Added guard in `captureEventVideo()` to prevent overlapping captures
  - Falls back to screenshot if video capture already in progress
- Level-ups during 2-second initialization window being lost
  - Removed redundant level re-capture that overwrote baseline with current levels
  - Now queues level-ups during init window and processes them after init completes
  - Added debug logging for level decrease edge cases
- Added detailed StatChanged debug logging to help diagnose missed level-ups
- OkHttp disk cache conflicts causing presigned URL failures
  - RuneLite's shared OkHttpClient uses disk caching which can fail on Windows
  - Now using cache-disabled clients for all our API calls (VideoRecorder, ApiClient, ItemSnitchTracker)
  - Prevents fallback to screenshot-only when cache file deletion fails

## [0.1.0] - 2025-01-29

### Added
- Initial plugin release
- Video recording with in-memory circular buffer (10 seconds @ 30 FPS)
- Collection log tracking with video capture
- Skill level-up tracking with video capture
- Clue scroll reward tracking with screenshot capture
- Loot drop tracking
- Death tracking
- Quest completion tracking
- Bank item highlighting and filtering (ItemSnitch)
- Quick capture sidebar panel
- Configurable video quality presets (Low, Medium, High, Screenshot Only)
- Sensitive content protection (blurs login screen, bank PIN)
- Direct upload to cloud storage via presigned URLs
