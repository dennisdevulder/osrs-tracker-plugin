# Changelog

All notable changes to the OSRS Tracker RuneLite plugin will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Pet Drop Detection**
  - Detects all three pet drop message types:
    - "You have a funny feeling like you're being followed." (new pet becomes follower)
    - "You feel something weird sneaking into your backpack." (pet goes to inventory)
    - "You have a funny feeling like you would have been followed..." (duplicate pet)
  - Extended 20-second video capture to celebrate the moment
  - Identifies pet by NPC ID with 60+ pets mapped (boss pets, skilling pets, raid pets, etc.)
  - Falls back to follower NPC name if pet not in database
  - Configurable via "Track Pet Drops" setting (enabled by default)
- **Bingo Event Tracking System**
  - Automatically fetches subscription data from API on login
  - Smart polling: fetches every 5 min if active event, skips if no event in next 6 hours
  - Sidebar panel now shows active bingo event status (name, tracking status, tile progress)
  - Reports boss kills, NPC kills, and clue completions to bingo progress API
  - Captures proof screenshots/videos at milestone intervals automatically
  - Silent progress updates (doesn't spam timeline, just updates bingo counts)
- **Bingo Loot Item Tracking**
  - Loot drops now report to bingo system for `loot_item` tile tracking
  - Matches items by `item_id` (not name) for accurate tracking
  - Counts item quantities (e.g., looting 2 bones = +2 progress)
  - Independent of timeline loot tracking (no 100k minimum threshold)
- **Raid Completion Detection**
  - Detects Chambers of Xeric (CoX), Theatre of Blood (ToB), and Tombs of Amascut (ToA) completions
  - Primary detection via final boss death (Great Olm, Verzik P3, Wardens)
  - Fallback detection via completion chat message
  - Includes deduplication to prevent double-reporting
- **Gauntlet Completion Detection**
  - Detects both Normal Gauntlet (Crystalline Hunllef) and Corrupted Gauntlet (Corrupted Hunllef)
  - Distinguishes between normal and corrupted for separate bingo tile tracking
- **Slayer Task Completion Detection**
  - Parses slayer task completion messages to extract task name and kill count
  - Reports to bingo system for slayer-related tiles

### Changed
- Video capture timing adjusted from 8s pre + 2s post to 6s pre + 4s post
  - Same 10 second total, but more post-event footage to capture celebrations/interfaces
  - Death captures unchanged (still 5s post-event)
- Reduced log verbosity: moved debugging log.info statements to log.debug

### Fixed
- Screenshot Only mode not stopping frame capture
  - When switching to Screenshot Only, the capture task kept running at the previous FPS
  - Now properly cancels capture task and clears buffer when switching to Screenshot Only
  - FPS should return to normal immediately when selecting Screenshot Only
- Item Snitch button duplicating when opening/closing bank multiple times
  - Old button widgets weren't being removed when bank UI was rebuilt
  - Now properly cleans up existing buttons before creating new ones

### Performance
- Reduced FPS impact from video recording
  - Cached sensitive content detection (widget lookups) - now checked every 500ms instead of every frame
  - At 30 FPS, this reduces widget lookups from 30/sec to 2/sec
  - Should reduce FPS drop from ~35 FPS to ~10-15 FPS during recording

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
