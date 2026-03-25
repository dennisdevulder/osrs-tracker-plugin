# OSRS Tracker Plugin

## Project
RuneLite plugin for osrs-tracker.com. Tracks gameplay events (drops, levels, quests, deaths, clues, collection log, item snitch) and sends them to the Rails backend with optional video replay clips.

## Tech Stack
- Java 11+, Gradle, Lombok
- RuneLite Plugin API
- Published via [runelite/plugin-hub](https://github.com/runelite/plugin-hub)

## Architecture Boundaries
- **NEVER** modify Rails files — the web app is maintained in `~/osrs-tracker/`
- Plugin source lives here; build/run via `rldev` alias
- Package `com.osrstracker` is transformed to `net.runelite.client.plugins.osrstracker` at build time

## Plugin-Hub Release Strategy

### Size Label System
The `runelite-github-app` bot auto-labels PRs by comparing the OLD and NEW commit hashes in the plugin's **source repo** (not the plugin-hub diff). It sums `additions + deletions` across ALL files.

| Label | Total Changes | Median Merge | Merge Rate |
|-------|--------------|--------------|------------|
| size-xs | 1–25 | 4 hours | 98% |
| size-s | 26–250 | 7 hours | 95% |
| size-m | 251–1,000 | 1.9 days | 98% |
| size-l | 1,001–4,000 | 2.6 days | 100% |
| size-xl | 4,001+ | 16.8 days | 80% |

### Release Rules
1. **Target size-s or smaller** (≤250 LOC total changes) — merges in hours
2. **Hard ceiling: size-m** (≤1,000) — still under 2 days
3. **Never submit size-xl** — 25-day avg first-response, 17+ day merge
4. **Split large features into multiple releases** — 5 size-s PRs merge faster than 1 size-xl
5. **Always calculate before submitting**: `git diff --shortstat <hub-commit>..HEAD` — additions + deletions = total changes

### Submission Timing
- **Best days to submit**: Thursday/Friday (catches Sunday batch) or Sunday/Monday (catches Wednesday batch)
- **Peak merge hours**: 14:00 UTC (9-10 AM US Eastern)
- **Busiest merge days**: Sunday (50 merges/sample), Wednesday (44)
- **Top reviewers**: iProdigy (30%), tylerwgrass (24%), riktenx (20%), pajlada (18%)

### Known Review Pitfalls
- `log.info` on anything frequent → must be `log.debug` (flagged on both our PRs)
- Never rewrite commit history after opening a PR — push additive commits
- Multi-plugin PRs sum all plugins' changes (avoid bundling)

### Pre-Submission Checklist
1. `grep -r "log\.info" src/ --include="*.java"` — must return nothing for frequent paths
2. `git diff --shortstat <current-hub-commit>..HEAD` — verify size bucket
3. If over 250 LOC: consider splitting into separate commits/releases
4. CHANGELOG.md updated
5. Test with `rldev` before submitting

## Conventions
- Singleton trackers with `@Inject` constructor
- `@Subscribe` for RuneLite events
- `ApiClient.sendEventToApi()` for async HTTP POST
- Config options in `OsrsTrackerConfig.java` with `@ConfigSection` / `@ConfigItem`
- Register new trackers in `OsrsTrackerPlugin.java` (inject, initialize, reset)
- Conventional commits, NO Co-Authored-By lines

## Modules

| Module | Purpose |
|--------|---------|
| `api/` | ApiClient for HTTP communication with Rails backend |
| `video/` | VideoRecorder, VideoQuality for replay captures |
| `skills/` | SkillLevelTracker for level-up events |
| `quest/` | QuestTracker for quest completions |
| `loot/` | LootTracker for valuable drops |
| `collectionlog/` | CollectionLogTracker for collection log updates |
| `death/` | DeathTracker for death events |
| `clue/` | ClueScrollTracker for clue scroll rewards |
| `itemsnitch/` | ItemSnitchTracker for shared clan item tracking |
| `pets/` | PetTracker for pet drop detection |
| `bingo/` | BingoProgressReporter + BingoSubscriptionManager |

## Useful Commands

```bash
rldev                          # Build and run plugin in dev mode
git diff --shortstat <hash>..HEAD  # Check release size
grep -r "log\.info" src/ --include="*.java"  # Audit log levels
```
