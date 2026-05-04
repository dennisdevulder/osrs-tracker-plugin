/*
 * Copyright (c) 2025, Dennis De Vulder
 * All rights reserved.
 */
package com.osrstracker.video.encode;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.osrstracker.video.encode.StreamingVulkanEncoder.collectFromIdr;
import static com.osrstracker.video.encode.StreamingVulkanEncoder.findIdrIndex;
import static com.osrstracker.video.encode.StreamingVulkanEncoder.pickLatestSegment;
import static org.junit.Assert.*;

public class StreamingFinalizeTest
{
    private static NalRing.Slot frame(int seg, long ts, boolean idr)
    {
        return new NalRing.Slot(seg, ts, idr, 0, false, new byte[]{ 0 });
    }

    @Test
    public void pickLatestSegmentReturnsHighestIdInWindow()
    {
        List<NalRing.Slot> slots = Arrays.asList(
            frame(0, 100, true),  // in window
            frame(0, 200, false), // in window
            frame(1, 300, true),  // in window — latest segment
            frame(1, 400, false)  // in window
        );
        assertEquals(1, pickLatestSegment(slots, 50, 500));
    }

    @Test
    public void pickLatestSegmentIgnoresOutOfWindowSegments()
    {
        List<NalRing.Slot> slots = Arrays.asList(
            frame(0, 100, true),  // in window
            frame(1, 999, true)   // segment 1 is OUTSIDE window
        );
        assertEquals(0, pickLatestSegment(slots, 50, 500));
    }

    @Test
    public void pickLatestSegmentEmptyReturnsNegativeOne()
    {
        assertEquals(-1, pickLatestSegment(Collections.emptyList(), 0, 100));
        assertEquals(-1, pickLatestSegment(Arrays.asList(frame(0, 999, true)), 0, 100));
    }

    @Test
    public void findIdrPrefersLatestBeforeStartTime()
    {
        // IDRs at 100 and 250; window starts at 200. Latest IDR ≤ start is 100.
        // The IDR at 250 is after startTime, so it shouldn't be picked.
        List<NalRing.Slot> slots = Arrays.asList(
            frame(0, 100, true),   // <-- want this
            frame(0, 150, false),
            frame(0, 200, false),
            frame(0, 250, true),
            frame(0, 300, false)
        );
        assertEquals(0, findIdrIndex(slots, 0, 200, 400));
    }

    @Test
    public void findIdrFallsBackToFirstAfterStartTimeWhenNonePreceded()
    {
        // No IDR before startTime=200. Fall back to first IDR after.
        List<NalRing.Slot> slots = Arrays.asList(
            frame(0, 100, false),
            frame(0, 150, false),
            frame(0, 250, true),   // <-- fallback target
            frame(0, 300, true)
        );
        assertEquals(2, findIdrIndex(slots, 0, 200, 400));
    }

    @Test
    public void findIdrReturnsNegativeWhenNoneInSegment()
    {
        List<NalRing.Slot> slots = Arrays.asList(
            frame(0, 100, true),  // wrong segment
            frame(1, 200, false)  // right segment but not IDR
        );
        assertEquals(-1, findIdrIndex(slots, 1, 50, 300));
    }

    @Test
    public void findIdrSkipsForeignSegments()
    {
        // Segment 0 has an IDR but we're looking in segment 1.
        List<NalRing.Slot> slots = Arrays.asList(
            frame(0, 100, true),
            frame(1, 200, false),
            frame(1, 250, true),  // <-- want this
            frame(1, 300, false)
        );
        assertEquals(2, findIdrIndex(slots, 1, 200, 400));
    }

    @Test
    public void collectStopsAtSegmentChange()
    {
        List<NalRing.Slot> slots = Arrays.asList(
            frame(0, 100, true),   // start here
            frame(0, 150, false),
            frame(0, 200, false),
            frame(1, 250, true)    // segment change — stop before this
        );
        List<NalRing.Slot> chosen = collectFromIdr(slots, 0, 0, 999);
        assertEquals(3, chosen.size());
        assertEquals(0, chosen.get(0).segmentId);
        assertEquals(200, chosen.get(2).timestampMs);
    }

    @Test
    public void collectStopsAtEndTime()
    {
        List<NalRing.Slot> slots = Arrays.asList(
            frame(0, 100, true),
            frame(0, 150, false),
            frame(0, 250, false),  // beyond endTime=200 — stop here
            frame(0, 300, false)
        );
        List<NalRing.Slot> chosen = collectFromIdr(slots, 0, 0, 200);
        assertEquals(2, chosen.size());
    }

    @Test
    public void collectStartsAtStartIdx()
    {
        List<NalRing.Slot> slots = Arrays.asList(
            frame(0, 100, true),
            frame(0, 150, false),
            frame(0, 200, true),
            frame(0, 250, false)
        );
        List<NalRing.Slot> chosen = collectFromIdr(slots, 2, 0, 999);
        assertEquals(2, chosen.size());
        assertEquals(200, chosen.get(0).timestampMs);
    }
}
