/*
 * Copyright (c) 2025, Dennis De Vulder
 * All rights reserved.
 */
package com.osrstracker.video.encode;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class NalRingTest
{
    private static NalRing.Slot slot(int seg, long ts, boolean idr, int frameNum)
    {
        return new NalRing.Slot(seg, ts, idr, frameNum, false, new byte[] { (byte) frameNum });
    }

    @Test
    public void snapshotReturnsInsertionOrder()
    {
        NalRing ring = new NalRing(8);
        ring.put(slot(0, 100, true, 0));
        ring.put(slot(0, 133, false, 1));
        ring.put(slot(0, 166, false, 2));

        List<NalRing.Slot> out = ring.snapshot();
        assertEquals(3, out.size());
        assertEquals(100, out.get(0).timestampMs);
        assertEquals(133, out.get(1).timestampMs);
        assertEquals(166, out.get(2).timestampMs);
    }

    @Test
    public void wraparoundDropsOldestFrames()
    {
        NalRing ring = new NalRing(4);
        for (int i = 0; i < 7; i++) ring.put(slot(0, i * 10L, i == 0, i));

        List<NalRing.Slot> out = ring.snapshot();
        assertEquals(4, out.size());
        assertEquals(30, out.get(0).timestampMs);
        assertEquals(60, out.get(3).timestampMs);
    }

    @Test
    public void preservesSegmentBoundaries()
    {
        NalRing ring = new NalRing(8);
        ring.put(slot(0, 100, true, 0));
        ring.put(slot(0, 133, false, 1));
        ring.put(slot(1, 166, true, 0));
        ring.put(slot(1, 200, false, 1));

        List<NalRing.Slot> out = ring.snapshot();
        assertEquals(0, out.get(0).segmentId);
        assertEquals(0, out.get(1).segmentId);
        assertEquals(1, out.get(2).segmentId);
        assertEquals(1, out.get(3).segmentId);
        assertTrue(out.get(2).isIdr);
    }

    @Test
    public void preservesIdrAndBlurFlags()
    {
        NalRing ring = new NalRing(4);
        ring.put(new NalRing.Slot(0, 100, true,  0, true,  new byte[]{ 1 }));
        ring.put(new NalRing.Slot(0, 133, false, 1, false, new byte[]{ 2 }));

        List<NalRing.Slot> out = ring.snapshot();
        assertTrue(out.get(0).isIdr);
        assertTrue(out.get(0).needsBlur);
        assertFalse(out.get(1).isIdr);
        assertFalse(out.get(1).needsBlur);
    }

    @Test
    public void resetClearsContents()
    {
        NalRing ring = new NalRing(4);
        ring.put(slot(0, 100, true, 0));
        ring.put(slot(0, 133, false, 1));
        ring.reset();

        assertEquals(0, ring.size());
        assertTrue(ring.snapshot().isEmpty());
    }

    @Test
    public void sizeTracksUpToCapacity()
    {
        NalRing ring = new NalRing(3);
        assertEquals(0, ring.size());
        ring.put(slot(0, 100, true, 0));
        assertEquals(1, ring.size());
        ring.put(slot(0, 133, false, 1));
        ring.put(slot(0, 166, false, 2));
        ring.put(slot(0, 200, false, 3));
        assertEquals(3, ring.size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsZeroCapacity()
    {
        new NalRing(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNullSlot()
    {
        new NalRing(4).put(null);
    }
}
