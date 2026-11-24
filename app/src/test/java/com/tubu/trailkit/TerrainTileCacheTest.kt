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

    @Test fun sameUrlRequestsShareOneAsyncDownloadWithoutBlockingCallers() {
        val loaderExecutor = Executors.newFixedThreadPool(4)
        try {
            val coalescer = TerrainRequestCoalescer()
            val count = AtomicInteger()
            val callbacks = CountDownLatch(1)
            coalescer.loadAsync("same-tile", loaderExecutor, {
                count.incrementAndGet()
                Thread.sleep(150)
                byteArrayOf(1, 2, 3)
            }) { bytes, error ->
                assertEquals(null, error)
                assertEquals(3, bytes?.size)
                callbacks.countDown()
            }
            coalescer.loadAsync("same-tile", loaderExecutor, {
                count.incrementAndGet()
                byteArrayOf(9)
            }) { _, _ -> }
            assertTrue(callbacks.await(2, TimeUnit.SECONDS))
            assertEquals(1, count.get())
        } finally {
            loaderExecutor.shutdownNow()
        }
    }
}
