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
        // The PNG is decoded by hand with java.util.zip only: javax.imageio is not
        // visible on the Android unit-test compile classpath (android.jar shadows it).
        val bytes = java.util.Base64.getDecoder()
            .decode(TerrainTilePlaceholders.DEM_ZERO_ELEVATION_PNG_BASE64)
        val pngSignature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        assertTrue(bytes.size > 8)
        assertTrue(bytes.copyOfRange(0, 8).contentEquals(pngSignature))
        fun u32(offset: Int): Int =
            ((bytes[offset].toInt() and 0xff) shl 24) or
                ((bytes[offset + 1].toInt() and 0xff) shl 16) or
                ((bytes[offset + 2].toInt() and 0xff) shl 8) or
                (bytes[offset + 3].toInt() and 0xff)
        var width = -1
        var height = -1
        val idat = java.io.ByteArrayOutputStream()
        var offset = 8
        var sawEnd = false
        while (!sawEnd && offset + 8 <= bytes.size) {
            val length = u32(offset)
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            when (type) {
                "IHDR" -> {
                    width = u32(offset + 8)
                    height = u32(offset + 12)
                }
                "IDAT" -> idat.write(bytes, offset + 8, length)
                "IEND" -> sawEnd = true
            }
            offset += 12 + length
        }
        assertEquals(1, width)
        assertEquals(1, height)
        val inflater = java.util.zip.Inflater()
        inflater.setInput(idat.toByteArray())
        val raw = ByteArray(4)
        val produced = inflater.inflate(raw)
        inflater.end()
        assertEquals(4, produced)
        assertEquals(0, raw[0].toInt())
        val red = raw[1].toInt() and 0xff
        val green = raw[2].toInt() and 0xff
        val blue = raw[3].toInt() and 0xff
        assertEquals(1, red)
        assertEquals(134, green)
        assertEquals(160, blue)
        assertEquals(0.0, TerrainTilePlaceholders.terrariumElevationMeters(red, green, blue), 0.001)
    }
}
