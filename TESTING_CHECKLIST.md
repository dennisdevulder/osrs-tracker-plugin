# OSRS Tracker Plugin - Testing Checklist

This checklist covers all the fixes and improvements made in v0.1.1 and the unreleased performance updates.

## Prerequisites

- [ ] Build the plugin fresh: `./run-with-plugin.sh` (or rebuild in IDE)
- [ ] Have RuneLite developer tools enabled (Settings → RuneLite → Developer Tools)
- [ ] Have the RuneLite log visible (View → Show Logs, or check `~/.runelite/logs/`)
- [ ] Deploy latest backend changes (sentry-rails)

---

## 1. Session Management (sessionActive flag)

**Goal:** Verify trackers only initialize once per login, not on every game state change.

### Test Steps:
- [ ] Log into the game and check logs for: `"New login session detected - initializing trackers"`
- [ ] Play normally for 2-3 minutes (walk between regions, open/close interfaces)
- [ ] **Verify:** You should NOT see repeated `"Skill level tracking enabled"` messages
- [ ] **Verify:** You should NOT see repeated `"New login session detected"` messages
- [ ] World hop and verify: `"World hop detected"` then `"New login session detected"` on new world
- [ ] Log out and verify: `"Logout detected - resetting session and trackers"`

### Expected Log Pattern:
```
[INFO] New login session detected - initializing trackers
[INFO] Skill level tracking enabled - 24 skills tracked
[INFO] Quest tracking initialized
... (no repeated init messages during gameplay)
```

---

## 2. Skill Level-Up Tracking

**Goal:** Verify level-ups are captured correctly and not missed.

### Test Steps:
- [ ] Train a skill that's close to leveling up
- [ ] Level up and verify the event is captured
- [ ] Check logs for: `"Level up detected!"` with correct skill and level
- [ ] **Verify:** Screenshot/video is uploaded to the backend
- [ ] **Verify:** Event appears in the web dashboard

### Edge Case - Level-up During Init Window:
- [ ] If you level up within the first 2 seconds after login, verify it's still captured
- [ ] Check logs for: `"Queueing level-up during init window"` (if it happens)

---

## 3. Clue Scroll Tracking

**Goal:** Verify clue scroll rewards are tracked.

### Test Steps:
- [ ] Complete a clue scroll (any tier)
- [ ] Open the reward casket
- [ ] Check logs for clue scroll detection messages
- [ ] **Verify:** Screenshot is captured when reward interface opens
- [ ] **Verify:** Event is sent to backend (check network or server logs)

### Backend Verification:
- [ ] Check Glitchtip/Sentry for any 500 errors from `/api/webhooks/clue_scroll`
- [ ] If errors occur, they should now be captured with full stack trace

---

## 4. Video Recording - Quality Switching

**Goal:** Verify quality settings work correctly and Screenshot Only actually stops capture.

### Test Steps:

#### Screenshot Only Mode:
- [ ] Set quality to any video mode (Low/Medium/High)
- [ ] Note your current FPS (should drop ~20-40 FPS from baseline)
- [ ] Switch to "Screenshot Only"
- [ ] **Verify:** FPS returns to normal within 1-2 seconds
- [ ] Check logs for: `"Switching to Screenshot Only mode - stopping frame capture"`
- [ ] **Verify:** No more frame capture activity in logs

#### Switching Between Video Modes:
- [ ] Switch from Screenshot Only to Low (15 FPS)
- [ ] Check logs for: `"Quality setting changed, updating capture rate from 0 to 15 FPS"`
- [ ] Switch from Low to Medium (30 FPS)
- [ ] **Verify:** Logs show FPS update
- [ ] Switch from Medium to High (30 FPS, 80% quality)
- [ ] **Verify:** Only JPEG quality changes, not FPS

---

## 5. Video Recording - FPS Impact

**Goal:** Verify the performance optimizations reduce FPS drop.

### Test Steps:
- [ ] Set quality to High (30 FPS)
- [ ] Note your FPS drop (should be ~10-20 FPS less than before the fix)
- [ ] The sensitive content check is now cached (500ms interval)
- [ ] **Before fix:** ~35-45 FPS drop
- [ ] **After fix:** ~10-20 FPS drop (improvement varies by system)

### Sensitive Content Caching:
- [ ] Open bank PIN interface
- [ ] **Verify:** Frames are blurred (sensitive content detection still works)
- [ ] The check now runs every 500ms instead of every frame

---

## 6. Video Recording - Overlapping Events

**Goal:** Verify rapid events don't cause mixed/corrupted video.

### Test Steps:
- [ ] Trigger two events rapidly (e.g., two collection log items in quick succession)
- [ ] Check logs for: `"Already capturing post-event video, falling back to screenshot"`
- [ ] **Verify:** First event gets video, second event gets screenshot (graceful fallback)
- [ ] **Verify:** No errors or corrupted uploads

---

## 7. Quest Tracking

**Goal:** Verify quests sync once per login, not continuously.

### Test Steps:
- [ ] Log in and check logs for quest sync activity
- [ ] **Verify:** Quest sync happens once after login (with 5-tick delay)
- [ ] Play for several minutes
- [ ] **Verify:** No repeated quest sync messages
- [ ] Complete a quest
- [ ] **Verify:** Quest completion is detected and synced

---

## 8. Collection Log Events

**Goal:** Verify collection log items trigger video capture correctly.

### Test Steps:
- [ ] Get a new collection log item
- [ ] **Verify:** Video capture triggers (check logs)
- [ ] **Verify:** Video uploads to Backblaze
- [ ] **Verify:** Event appears in dashboard with video

---

## 9. Backend - Sentry/Glitchtip

**Goal:** Verify Rails exceptions are now captured.

### Test Steps:
- [ ] Trigger a server-side error (e.g., malformed API request)
- [ ] Check Glitchtip for the exception
- [ ] **Verify:** Full stack trace is captured
- [ ] **Verify:** Request context is included

---

## 10. Item Snitch Button (Bank Filter)

**Goal:** Verify the filter button doesn't duplicate when opening/closing bank.

### Test Steps:
- [ ] Open the bank
- [ ] **Verify:** Item Snitch button appears (if you have shared items configured)
- [ ] Close the bank
- [ ] Open the bank again
- [ ] Right-click the Item Snitch button area
- [ ] **Verify:** Only ONE "Filter shared items" option appears (not multiple)
- [ ] Repeat open/close 5+ times
- [ ] **Verify:** Still only one button, no duplicates in right-click menu
- [ ] Check logs for: `"Cleaned up X existing Item Snitch button(s)"` (should show cleanup happening)

---

## 11. Bingo Event Tracking

**Goal:** Verify bingo subscriptions are fetched and progress is reported.

### Test Steps (with active bingo event):
- [ ] Log in while participating in an active bingo event
- [ ] Check sidebar panel → should show event name and "● Tracking" in green
- [ ] Check logs for: `"Bingo tracking active: [Event Name] (tracking X bosses, Y NPCs, Z items)"`
- [ ] Kill a boss that's in your tile requirements
- [ ] **Verify:** Log shows `"Reporting boss kill to bingo: [Boss Name]"`
- [ ] Check the web dashboard → verify progress incremented

### Test Steps (no active event):
- [ ] Log in without an active bingo event
- [ ] Check sidebar panel → should show "None" and "● Not Tracking" in gray
- [ ] Check logs for: `"No active bingo event for user"`
- [ ] **Verify:** No bingo-related API calls during gameplay

### Smart Polling:
- [ ] With active event: verify logs show subscription refresh every ~5 minutes
- [ ] Without active event: verify no polling (subscription fetch only on login)

---

## Regression Tests

These should still work as before:

- [ ] Loot drops are tracked
- [ ] Deaths are tracked
- [ ] ItemSnitch bank highlighting works
- [ ] Quick capture sidebar panel works
- [ ] Login screen is blurred in recordings
- [ ] Bank PIN interface is blurred in recordings

---

## Log Messages Reference

### Good (Expected):
```
[INFO] New login session detected - initializing trackers
[INFO] Skill level tracking enabled - 24 skills tracked
[INFO] Quest tracking initialized
[INFO] Bingo tracking active: January Bingo (tracking 5 bosses, 0 NPCs, 12 items)
[INFO] Switching to Screenshot Only mode - stopping frame capture
[INFO] Quality setting changed, updating capture rate from 0 to 30 FPS
[DEBUG] Level up detected! Skill: FARMING, New Level: 86
[DEBUG] Reporting boss kill to bingo: Zulrah (2042)
[DEBUG] Bingo progress sent successfully: boss_kill
```

### Bad (Problems):
```
[INFO] Skill level tracking enabled - 24 skills tracked  (repeated multiple times)
[INFO] New login session detected  (repeated during normal gameplay)
[ERROR] Failed to get presigned URL
[ERROR] Failed to upload to Backblaze
```

---

## Quick Smoke Test (5 minutes)

If you just want a quick verification:

1. [ ] Log in → verify single init message
2. [ ] Set to High quality → verify FPS drops ~20-30
3. [ ] Switch to Screenshot Only → verify FPS returns to normal
4. [ ] Play 2 min → verify no repeated init messages
5. [ ] Trigger any trackable event → verify it's captured
