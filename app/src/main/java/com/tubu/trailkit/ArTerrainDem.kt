package com.tubu.trailkit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** Terrarium DEM sampler. Returns terrain surface elevation, not GPS-device altitude. */
internal object ArTerrainDem {
    private const val ZOOM = 14
    private const val TILE_SIZE = 256
    private const val MAX_CACHED_TILES = 48
    private val tileCache = LinkedHashMap<Long, Bitmap>(16, 0.75f, true)

    fun elevations(points: List<Pair<Double, Double>>): List<Double?> =
        points.map { (latitude, longitude) -> elevation(latitude, longitude) }

    fun elevation(latitude: Double, longitude: Double): Double? {
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -85.05112878..85.05112878 || longitude !in -180.0..180.0
        ) return null
        return runCatching {
            val worldSize = TILE_SIZE * (1 shl ZOOM).toDouble()
            val x = (longitude + 180.0) / 360.0 * worldSize
            val latRad = Math.toRadians(latitude)
            val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) * 0.5 * worldSize
            val tileX = floor(x / TILE_SIZE).toInt().coerceIn(0, (1 shl ZOOM) - 1)
            val tileY = floor(y / TILE_SIZE).toInt().coerceIn(0, (1 shl ZOOM) - 1)
            val pixelX = floor(x).toInt().mod(TILE_SIZE)
            val pixelY = floor(y).toInt().mod(TILE_SIZE)
            val bitmap = getTile(tileX, tileY) ?: return null
            val pixel = bitmap.getPixel(pixelX.coerceIn(0, bitmap.width - 1), pixelY.coerceIn(0, bitmap.height - 1))
            val red = (pixel shr 16) and 0xff
            val green = (pixel shr 8) and 0xff
            val blue = pixel and 0xff
            red * 256.0 + green + blue / 256.0 - 32768.0
        }.getOrNull()
    }

    @Synchronized
    private fun getTile(x: Int, y: Int): Bitmap? {
        val key = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)
        tileCache[key]?.let { return it }
        val connection = URL("https://s3.amazonaws.com/elevation-tiles-prod/terrarium/$ZOOM/$x/$y.png")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 10000
        connection.setRequestProperty("User-Agent", "TrailKit Outdoor App")
        return try {
            val bitmap = connection.inputStream.use { BitmapFactory.decodeStream(it) } ?: return null
            tileCache[key] = bitmap
            while (tileCache.size > MAX_CACHED_TILES) {
                val iterator = tileCache.entries.iterator()
                if (!iterator.hasNext()) break
                val entry = iterator.next()
                iterator.remove()
                if (!entry.value.isRecycled) entry.value.recycle()
            }
            bitmap
        } finally {
            connection.disconnect()
        }
    }
}
