/*
 * Copyright (c) 2025, Dennis De Vulder
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.osrstracker.video;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lock-free pool of {@code byte[]} buffers for PBO readback. The GL thread
 * allocates ~8 MB per 1080p capture; without pooling that's sustained GC
 * churn during recording. Buffers whose size no longer matches the current
 * capture extent are dropped on release rather than retained.
 */
final class BytePool
{
    static final int DEFAULT_MAX_POOLED = 4;

    private final ConcurrentLinkedDeque<byte[]> available = new ConcurrentLinkedDeque<>();
    private final AtomicInteger pooled = new AtomicInteger();
    private final int maxPooled;

    BytePool()
    {
        this(DEFAULT_MAX_POOLED);
    }

    BytePool(int maxPooled)
    {
        if (maxPooled <= 0) throw new IllegalArgumentException("maxPooled must be positive");
        this.maxPooled = maxPooled;
    }

    byte[] acquire(int size)
    {
        for (;;)
        {
            byte[] buf = available.pollFirst();
            if (buf == null) return new byte[size];
            pooled.decrementAndGet();
            if (buf.length == size) return buf;
            // Wrong-size buffer: drop it (resize happened) and keep looking.
        }
    }

    void release(byte[] buf)
    {
        if (buf == null) return;
        if (pooled.get() >= maxPooled) return;
        pooled.incrementAndGet();
        available.offerFirst(buf);
    }

    void clear()
    {
        available.clear();
        pooled.set(0);
    }

    int pooledCount()
    {
        return pooled.get();
    }
}
