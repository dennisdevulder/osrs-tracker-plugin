# Bingo Plugin Implementation Plan

## Overview

The backend already has a complete bingo subscription system. The plugin just needs to:
1. **Fetch subscriptions** on login - API tells us exactly what to track
2. **Filter events** - Only watch specified NPC IDs, item IDs, clue tiers, etc.
3. **Report progress** - Send matching events to `/api/bingo/progress` (silent, no timeline spam)
4. **Capture proof** - Take screenshot/video when at a proof milestone

## Backend API Endpoints

### GET /api/bingo/subscriptions
Returns what the plugin should track for the user's active bingo event.

**Response:**
```json
{
  "has_active_event": true,
  "event": {
    "id": 123,
    "name": "January Bingo",
    "starts_at": "2025-01-01T00:00:00Z",
    "ends_at": "2025-01-31T23:59:59Z"
  },
  "subscriptions": {
    "npc_ids": [2042, 2043, 2044, 8059],
    "boss_ids": [2042, 2043, 2044],
    "item_ids": [12921, 12922, 21992],
    "min_loot_value": 1000000,
    "clue_tiers": ["hard", "elite", "master"],
    "track_raids": true,
    "track_gauntlet": true,
    "track_slayer": true
  },
  "tile_progress": [
    {
      "tile_id": 1,
      "trigger_type": "boss_kill_count",
      "description": "Kill 50 Zulrah",
      "current_count": 23,
      "required_count": 50,
      "proof_interval": 10,
      "next_proof_milestone": 30,
      "config": { "boss_ids": [2042, 2043, 2044] }
    }
  ]
}
```

### POST /api/bingo/progress
Silent progress update - counts events without creating timeline entries.

**Request:**
```json
{
  "event_type": "boss_kill",
  "event_data": {
    "npc_id": 2042,
    "npc_name": "Zulrah"
  },
  "screenshot": "base64...",  // Optional - include at proof milestones
  "replay_gif": "uploads/abc123"  // Optional video key
}
```

**Response:**
```json
{
  "success": true,
  "bingo": {
    "progress": [
      {
        "tile_id": 1,
        "current_count": 24,
        "required_count": 50,
        "requires_proof": false,
        "next_proof_milestone": 30,
        "completed": false
      }
    ]
  }
}
```

---

## Plugin Implementation

### New Files to Create

```
src/main/java/com/osrstracker/bingo/
├── BingoSubscriptionManager.java   # Fetches & stores subscriptions
├── BingoProgressReporter.java      # Reports progress to API
├── BingoSubscription.java          # Data class for subscriptions
└── BingoTileProgress.java          # Data class for tile progress
```

### 1. BingoSubscription.java (Data Class)

```java
@Data
public class BingoSubscription {
    private boolean hasActiveEvent;
    private BingoEventInfo event;
    private SubscriptionConfig subscriptions;
    private List<TileProgress> tileProgress;

    @Data
    public static class BingoEventInfo {
        private int id;
        private String name;
        private String startsAt;
        private String endsAt;
    }

    @Data
    public static class SubscriptionConfig {
        private Set<Integer> npcIds = new HashSet<>();
        private Set<Integer> bossIds = new HashSet<>();
        private Set<Integer> itemIds = new HashSet<>();
        private Integer minLootValue;
        private Set<String> clueTiers = new HashSet<>();
        private boolean trackRaids;
        private boolean trackGauntlet;
        private boolean trackSlayer;
    }

    @Data
    public static class TileProgress {
        private int tileId;
        private String triggerType;
        private String description;
        private int currentCount;
        private int requiredCount;
        private Integer proofInterval;
        private Integer nextProofMilestone;
    }
}
```

### 2. BingoSubscriptionManager.java

```java
@Singleton
public class BingoSubscriptionManager {
    private final OkHttpClient httpClient;
    private final OsrsTrackerConfig config;
    private final Gson gson;

    private volatile BingoSubscription subscription;
    private volatile long lastFetchTime = 0;
    private static final long REFRESH_INTERVAL_MS = 60000; // Refresh every minute

    // Called on login
    public void fetchSubscriptions() {
        // GET /api/bingo/subscriptions
        // Store result in subscription field
    }

    // Check if we should track this NPC kill
    public boolean shouldTrackNpcKill(int npcId) {
        if (subscription == null || !subscription.isHasActiveEvent()) return false;
        return subscription.getSubscriptions().getNpcIds().contains(npcId);
    }

    // Check if we should track this boss kill
    public boolean shouldTrackBossKill(int npcId) {
        if (subscription == null || !subscription.isHasActiveEvent()) return false;
        return subscription.getSubscriptions().getBossIds().contains(npcId);
    }

    // Check if we should track this item drop
    public boolean shouldTrackItem(int itemId) {
        if (subscription == null || !subscription.isHasActiveEvent()) return false;
        return subscription.getSubscriptions().getItemIds().contains(itemId);
    }

    // Check if loot value meets threshold
    public boolean shouldTrackLootValue(long totalValue) {
        if (subscription == null || !subscription.isHasActiveEvent()) return false;
        Integer minValue = subscription.getSubscriptions().getMinLootValue();
        return minValue != null && totalValue >= minValue;
    }

    // Check if we should track this clue tier
    public boolean shouldTrackClue(String tier) {
        if (subscription == null || !subscription.isHasActiveEvent()) return false;
        return subscription.getSubscriptions().getClueTiers().contains(tier.toLowerCase());
    }

    // Check if at proof milestone for a tile
    public boolean isAtProofMilestone(int tileId, int currentCount) {
        // Find tile in tileProgress, check if currentCount matches nextProofMilestone
    }

    // Update local progress after API response
    public void updateTileProgress(List<ProgressUpdate> updates) {
        // Update tileProgress with new counts and next milestones
    }
}
```

### 3. BingoProgressReporter.java

```java
@Singleton
public class BingoProgressReporter {
    private final OkHttpClient httpClient;
    private final OsrsTrackerConfig config;
    private final BingoSubscriptionManager subscriptionManager;
    private final VideoRecorder videoRecorder;

    /**
     * Report a boss kill to bingo progress.
     * Called from the main plugin when ActorDeath fires for a boss.
     */
    public void reportBossKill(int npcId, String npcName) {
        if (!subscriptionManager.shouldTrackBossKill(npcId)) {
            return;
        }

        boolean needsProof = subscriptionManager.needsProofForBossKill(npcId);

        if (needsProof) {
            // Capture screenshot/video, then send
            videoRecorder.captureScreenshotOnly(screenshot -> {
                sendProgress("boss_kill", Map.of("npc_id", npcId, "npc_name", npcName), screenshot, null);
            });
        } else {
            // Send without proof
            sendProgress("boss_kill", Map.of("npc_id", npcId, "npc_name", npcName), null, null);
        }
    }

    /**
     * Report loot items to bingo progress.
     */
    public void reportLoot(int npcId, String npcName, List<LootItem> items, long totalValue) {
        // Check if any item matches subscribed item_ids
        // Or if totalValue >= min_loot_value
        // Then send to /api/bingo/progress
    }

    /**
     * Report clue scroll completion.
     */
    public void reportClueComplete(String tier, List<LootItem> rewards) {
        if (!subscriptionManager.shouldTrackClue(tier)) {
            return;
        }
        // Send to /api/bingo/progress
    }

    private void sendProgress(String eventType, Map<String, Object> eventData,
                              String screenshot, String videoKey) {
        // POST to /api/bingo/progress
        // Parse response and update subscription manager with new progress
    }
}
```

### 4. Integration Points in OsrsTrackerPlugin.java

```java
// On login (in onGameStateChanged when sessionActive becomes true):
bingoSubscriptionManager.fetchSubscriptions();

// On ActorDeath event:
@Subscribe
public void onActorDeath(ActorDeath event) {
    Actor actor = event.getActor();
    if (actor instanceof NPC) {
        NPC npc = (NPC) actor;
        int npcId = npc.getId();
        String npcName = npc.getName();

        // Report to bingo (this checks subscriptions internally)
        bingoProgressReporter.reportBossKill(npcId, npcName);
    }
}

// In LootTracker.processLootDrop (modify existing):
// After processing normal loot tracking, also:
bingoProgressReporter.reportLoot(npcId, npcName, items, totalValue);

// In ClueScrollTracker (modify existing):
// After detecting clue completion:
bingoProgressReporter.reportClueComplete(tier, rewards);
```

---

## What's Simple About This

1. **Subscriptions tell us everything** - No tile logic needed in plugin
2. **Silent progress** - `/api/bingo/progress` doesn't create timeline events
3. **Minimal changes to existing code** - Just add hook calls to existing trackers
4. **Proof is optional** - Only capture at milestones, server tells us when

## What We DON'T Need to Implement (Yet)

Based on BINGO_REQUIREMENTS.md, these are lower priority:

- **RaidTracker** - Complex state machine, not in subscriptions yet
- **GauntletTracker** - Similar complexity
- **SlayerTaskTracker** - Varbit tracking needed
- **BossKillTracker** - We can use ActorDeath for basic tracking

For MVP, we just need:
1. Boss/NPC kills via ActorDeath
2. Item drops via existing LootTracker
3. Clue completions via existing ClueScrollTracker

---

## Implementation Order

### Phase 1: Core Infrastructure
1. Create `BingoSubscription.java` data class
2. Create `BingoSubscriptionManager.java` - fetch and store subscriptions
3. Add subscription fetch on login in OsrsTrackerPlugin

### Phase 2: Progress Reporting
4. Create `BingoProgressReporter.java` - report progress to API
5. Hook into ActorDeath for boss kills
6. Hook into LootTracker for item drops
7. Hook into ClueScrollTracker for clue completions

### Phase 3: Proof Capture
8. Add proof milestone checking to subscription manager
9. Capture screenshot/video when at proof milestones
10. Include proof in progress reports

### Phase 4: Polish
11. Periodic subscription refresh (in case tiles complete externally)
12. Handle API errors gracefully
13. Add config option to enable/disable bingo tracking

---

## Testing

1. Create a test bingo event with simple tiles (e.g., "Kill 5 goblins")
2. Configure a user to participate
3. Verify subscriptions endpoint returns correct data
4. Kill goblins, verify progress updates
5. Check proof is captured at milestones
