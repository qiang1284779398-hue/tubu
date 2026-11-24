package com.tubu.trailkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class TerrainTileCacheTest {
    @Test fun memoryStoreEvictsLeastRecentlyUsedEntriesByByteBudget() {
        val store = TerrainTileMemoryStore(5)
        store.put("a", byteArrayOf(1, 2))
        store.put("b", byteArrayOf(3, 4))
        assertNotNull(store.get("a"))
        store.put("c", byteArrayOf(5, 6))
        assertEquals(null, store.get("b"))
        assertNotNull(store.get("a"))
        assertNotNull(store.get("c"))
        assertEquals(4L, store.sizeBytes())
    }

    @Test fun sameUrlRequestsShareOneInFlightDownload() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val coalescer = TerrainRequestCoalescer()
            val count = AtomicInteger()
            val results = (0 until 8).map {
                executor.submit<ByteArray?> {
                    coalescer.load("same-tile", executor) {
                        count.incrementAndGet()
                        Thread.sleep(80)
                        byteArrayOf(1, 2, 3)
                    }
                }
            }.map { it.get() }
            assertEquals(1, count.get())
            assertEquals(8, results.size)
            assertEquals(3, results.first()?.size)
        } finally {
            executor.shutdownNow()
        }
    }
}
