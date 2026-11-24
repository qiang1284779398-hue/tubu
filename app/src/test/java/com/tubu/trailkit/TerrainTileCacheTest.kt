package com.tubu.trailkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TerrainTileCacheTest {
    @Test fun memoryStoreEvictsLeastRecentlyUsedEntriesByByteBudget() {
        val store = TerrainTileMemoryStore(5)
        store.put("a", byteArrayOf(1, 2))
        store.put("b", byteArrayOf(3, 4))
        assertNotNull(store.get("a"))
        store.put("c", byteArrayOf(5, 6))
        assertTrue(store.get("b") == null)
        assertNotNull(store.get("a"))
        assertNotNull(store.get("c"))
        assertEquals(4L, store.sizeBytes())
    }

    @Test fun sameUrlRequestsShareOneInFlightDownload() {
        val loaderExecutor = Executors.newFixedThreadPool(4)
        val callers = Executors.newFixedThreadPool(8)
        try {
            val coalescer = TerrainRequestCoalescer()
            val count = AtomicInteger()
            val ready = CountDownLatch(8)
            val start = CountDownLatch(1)
            val futures = (0 until 8).map {
                callers.submit<ByteArray?> {
                    ready.countDown()
                    start.await(2, TimeUnit.SECONDS)
                    coalescer.load("same-tile", loaderExecutor) {
                        count.incrementAndGet()
                        Thread.sleep(500)
                        byteArrayOf(1, 2, 3)
                    }
                }
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            start.countDown()
            val values = futures.map { it.get() }
            assertEquals(1, count.get())
            assertEquals(8, values.size)
            assertEquals(3, values.first()?.size)
        } finally {
            callers.shutdownNow()
            loaderExecutor.shutdownNow()
        }
    }
}
