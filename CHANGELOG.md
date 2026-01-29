# Changelog

All notable changes to the OSRS Tracker RuneLite plugin will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
