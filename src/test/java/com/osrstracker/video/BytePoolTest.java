/*
 * Copyright (c) 2025, Dennis De Vulder
 * All rights reserved.
 */
package com.osrstracker.video;

import org.junit.Test;

import static org.junit.Assert.*;

public class BytePoolTest
{
    @Test
    public void acquireReturnsFreshBufferWhenEmpty()
    {
        BytePool pool = new BytePool();
        byte[] buf = pool.acquire(1024);
        assertEquals(1024, buf.length);
        assertEquals(0, pool.pooledCount());
    }

    @Test
    public void releaseAndAcquireReusesBuffer()
    {
        BytePool pool = new BytePool();
        byte[] first = pool.acquire(1024);
        pool.release(first);
        byte[] second = pool.acquire(1024);
        assertSame(first, second);
    }

    @Test
    public void releaseDropsWrongSizeOnAcquire()
    {
        BytePool pool = new BytePool();
        byte[] small = new byte[512];
        pool.release(small);
        byte[] big = pool.acquire(1024);
        assertEquals(1024, big.length);
        assertNotSame(small, big);
        assertEquals(0, pool.pooledCount());
    }

    @Test
    public void capCapsPoolAtMax()
    {
        BytePool pool = new BytePool(2);
        pool.release(new byte[1024]);
        pool.release(new byte[1024]);
        pool.release(new byte[1024]);
        assertEquals(2, pool.pooledCount());
    }

    @Test
    public void clearEmptiesPool()
    {
        BytePool pool = new BytePool();
        pool.release(new byte[1024]);
        pool.release(new byte[1024]);
        pool.clear();
        assertEquals(0, pool.pooledCount());
    }

    @Test
    public void releaseNullIsNoop()
    {
        BytePool pool = new BytePool();
        pool.release(null);
        assertEquals(0, pool.pooledCount());
    }
}
