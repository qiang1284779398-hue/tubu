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

    @Test fun demPlaceholderPngDecodesToZeroElevation() {
        // Regression guard for the black-screen bug: the DEM placeholder served on a
        // cache miss must decode (Terrarium) to 0 m. A transparent placeholder pixel
        // (RGB 0,0,0) decodes to -10000 m and sinks the whole terrain scene.
        val bytes = java.util.Base64.getDecoder()
            .decode(TerrainTilePlaceholders.DEM_ZERO_ELEVATION_PNG_BASE64)
        val image = javax.imageio.ImageIO.read(bytes.inputStream())
        assertNotNull(image)
        assertEquals(1, image.width)
        assertEquals(1, image.height)
        val rgb = image.getRGB(0, 0)
        val red = (rgb shr 16) and 0xff
        val green = (rgb shr 8) and 0xff
        val blue = rgb and 0xff
        assertEquals(1, red)
        assertEquals(134, green)
        assertEquals(160, blue)
        assertEquals(0.0, TerrainTilePlaceholders.terrariumElevationMeters(red, green, blue), 0.001)
    }
}
