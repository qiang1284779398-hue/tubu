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
        // Regression guard: the DEM placeholder served on a cache miss must decode
        // (Terrarium: R*256 + G + B/256 - 32768) to 0 m at full tile size, 256x256.
        // A transparent placeholder pixel (RGB 0,0,0) decodes to -32768 m, and the
        // earlier RGB(1,134,160) pixel - chosen with the Mapbox Terrain-RGB formula
        // by mistake - decodes to -32377 m; either turns uncached tiles into pits.
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
        assertEquals(256, width)
        assertEquals(256, height)
        val rowBytes = 1 + 256 * 3
        val raw = ByteArray(rowBytes * 256)
        val inflater = java.util.zip.Inflater()
        inflater.setInput(idat.toByteArray())
        val produced = inflater.inflate(raw)
        inflater.end()
        assertEquals(rowBytes * 256, produced)
        // Every row: filter byte 0, then 256 pixels of RGB(128, 0, 0).
        for (row in 0 until 256) {
            val base = row * rowBytes
            assertEquals(0, raw[base].toInt())
            for (col in intArrayOf(0, 128, 255)) {
                val p = base + 1 + col * 3
                assertEquals(128, raw[p].toInt() and 0xff)
                assertEquals(0, raw[p + 1].toInt() and 0xff)
                assertEquals(0, raw[p + 2].toInt() and 0xff)
            }
        }
        assertEquals(0.0, TerrainTilePlaceholders.terrariumElevationMeters(128, 0, 0), 0.001)
        assertEquals(-32768.0, TerrainTilePlaceholders.terrariumElevationMeters(0, 0, 0), 0.001)
    }
}
