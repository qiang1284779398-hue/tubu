package com.tubu.trailkit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** Terrarium DEM sampler with a bounded memory cache and persistent disk LRU. */
internal object ArTerrainDem {
    private const val ZOOM = 14
    private const val TILE_SIZE = 256
    private const val MAX_CACHED_TILES = 48
    private const val MAX_DISK_BYTES = 300L * 1024L * 1024L
    private val tileCache = LinkedHashMap<Long, Bitmap>(16, 0.75f, true)
    // Failed fetches are remembered briefly so a dead network does not make
    // every elevation sample retry the same download in sequence.
    private val failedTiles = HashMap<Long, Long>()
    private const val FAILURE_TTL_MS = 60_000L
    @Volatile private var cacheDirectory: File? = null

    @Synchronized
    fun initialize(context: Context) {
        val directory = File(context.applicationContext.cacheDir, "terrain-dem-z14")
        if (cacheDirectory?.absolutePath != directory.absolutePath) {
            cacheDirectory = directory
            directory.mkdirs()
            trimDiskCache()
        }
    }

    fun elevations(points: List<Pair<Double, Double>>): List<Double?> =
        points.map { (latitude, longitude) -> elevation(latitude, longitude) }

    fun elevation(latitude: Double, longitude: Double): Double? {
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -85.05112878..85.05112878 || longitude !in -180.0..180.0
        ) return null
        return runCatching {
            val worldSize = TILE_SIZE * (1 shl ZOOM).toDouble()
            val pixelX = ((longitude + 180.0) / 360.0 * worldSize)
            val latRad = Math.toRadians(latitude)
            val pixelY = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) * 0.5 * worldSize
            // Terrarium samples live at pixel CENTRES: continuous coordinate p
            // corresponds to pixel index p - 0.5. Sampling without the -0.5 shift
            // reads ~half a pixel south-east of the intended point (~4 m at z14).
            val sampleX = pixelX - 0.5
            val sampleY = pixelY - 0.5
            val x0 = floor(sampleX).toInt().coerceIn(0, worldSize.toInt() - 1)
            val y0 = floor(sampleY).toInt().coerceIn(0, worldSize.toInt() - 1)
            val x1 = (x0 + 1).coerceAtMost(worldSize.toInt() - 1)
            val y1 = (y0 + 1).coerceAtMost(worldSize.toInt() - 1)
            val fx = (sampleX - floor(sampleX)).coerceIn(0.0, 1.0)
            val fy = (sampleY - floor(sampleY)).coerceIn(0.0, 1.0)
            val h00 = pixelElevation(x0, y0) ?: return null
            val h10 = pixelElevation(x1, y0) ?: return null
            val h01 = pixelElevation(x0, y1) ?: return null
            val h11 = pixelElevation(x1, y1) ?: return null
            bilinear(h00, h10, h01, h11, fx, fy)
        }.getOrNull()
    }

    internal fun bilinear(h00: Double, h10: Double, h01: Double, h11: Double, fx: Double, fy: Double): Double {
        val top = h00 + (h10 - h00) * fx.coerceIn(0.0, 1.0)
        val bottom = h01 + (h11 - h01) * fx.coerceIn(0.0, 1.0)
        return top + (bottom - top) * fy.coerceIn(0.0, 1.0)
    }

    /**
     * DEM line-of-sight test. Returns 1 for visible/low-confidence, ~0.20 for
     * medium-confidence obstruction, and ~0.05 for repeated high-confidence hits.
     */
    fun occlusionAlpha(
        cameraLatitude: Double,
        cameraLongitude: Double,
        cameraGroundElevationMeters: Double,
        cameraHeightMeters: Double,
        targetLatitude: Double,
        targetLongitude: Double,
        targetElevationMeters: Double
    ): Float {
        val distance = haversine(cameraLatitude, cameraLongitude, targetLatitude, targetLongitude)
        if (distance < 8.0) return 1f
        val resolution = (cos(Math.toRadians(cameraLatitude)).coerceAtLeast(0.1) *
            2.0 * PI * 6378137.0 / (TILE_SIZE * (1 shl ZOOM))).coerceAtLeast(1.0)
        val step = (resolution * 0.85).coerceIn(4.0, 12.0)
        val count = (distance / step).toInt().coerceIn(2, 24)
        var valid = 0
        var mediumHits = 0
        var strongHits = 0
        for (index in 1 until count) {
            val fraction = index.toDouble() / count
            val latitude = cameraLatitude + (targetLatitude - cameraLatitude) * fraction
            val longitude = cameraLongitude + (targetLongitude - cameraLongitude) * fraction
            val terrain = elevation(latitude, longitude) ?: continue
            valid++
            val lineOfSight = cameraGroundElevationMeters + cameraHeightMeters +
                (targetElevationMeters - cameraGroundElevationMeters - cameraHeightMeters) * fraction
            val excess = terrain - lineOfSight
            if (excess > 4.0) strongHits++
            else if (excess > 2.0) mediumHits++
        }
        val expected = (count - 1).coerceAtLeast(1)
        if (valid.toDouble() / expected < 0.65) return 1f
        return when {
            strongHits >= 2 -> 0.05f
            strongHits >= 1 || mediumHits >= 2 -> 0.20f
            else -> 1f
        }
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val h = kotlin.math.sin(dp / 2).let { it * it } +
            cos(p1) * cos(p2) * kotlin.math.sin(dl / 2).let { it * it }
        return 2.0 * 6371000.0 * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
    }

    private fun pixelElevation(globalX: Int, globalY: Int): Double? {
        val tileX = (globalX / TILE_SIZE).coerceIn(0, (1 shl ZOOM) - 1)
        val tileY = (globalY / TILE_SIZE).coerceIn(0, (1 shl ZOOM) - 1)
        val bitmap = getTile(tileX, tileY) ?: return null
        val localX = (globalX % TILE_SIZE).coerceIn(0, bitmap.width - 1)
        val localY = (globalY % TILE_SIZE).coerceIn(0, bitmap.height - 1)
        val pixel = bitmap.getPixel(localX, localY)
        val red = (pixel shr 16) and 0xff
        val green = (pixel shr 8) and 0xff
        val blue = pixel and 0xff
        return red * 256.0 + green + blue / 256.0 - 32768.0
    }

    @Synchronized
    private fun getTile(x: Int, y: Int): Bitmap? {
        val key = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)
        tileCache[key]?.let { return it }
        val failedAt = failedTiles[key]
        if (failedAt != null && System.currentTimeMillis() - failedAt < FAILURE_TTL_MS) return null
        val directory = cacheDirectory
        val file = directory?.let { File(it, "$x-$y.png") }
        if (file != null && file.isFile && file.length() > 0L) {
            val decoded = BitmapFactory.decodeFile(file.absolutePath)
            if (decoded != null && decoded.width == TILE_SIZE && decoded.height == TILE_SIZE) {
                file.setLastModified(System.currentTimeMillis())
                remember(key, decoded)
                return decoded
            }
            file.delete()
        }

        val connection = URL("https://s3.amazonaws.com/elevation-tiles-prod/terrarium/$ZOOM/$x/$y.png")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 10000
        connection.setRequestProperty("User-Agent", "TrailKit Outdoor App")
        val bitmap = try {
            val bytes = connection.inputStream.use { it.readBytes() }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { decoded ->
                if (decoded.width == TILE_SIZE && decoded.height == TILE_SIZE && file != null) {
                    val temporary = File(file.parentFile, file.name + ".tmp")
                    if (temporary.writeBytesSafely(bytes) && temporary.renameTo(file)) {
                        trimDiskCache()
                        writeIndex(directory!!)
                    } else temporary.delete()
                }
            }
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
        if (bitmap == null || bitmap.width != TILE_SIZE || bitmap.height != TILE_SIZE) {
            // Never published to the cache, so recycling here is safe.
            bitmap?.recycle()
            failedTiles[key] = System.currentTimeMillis()
            return null
        }
        remember(key, bitmap)
        return bitmap
    }

    private fun remember(key: Long, bitmap: Bitmap) {
        // Evicted bitmaps are deliberately NOT recycled: pixel reads happen
        // outside the getTile lock, so a bitmap handed out earlier may still be
        // read by a concurrent elevation() call. Recycling it here made those
        // reads throw, which surfaced as random null elevations (flickering
        // spikes). The cache is bounded (48 tiles), so GC reclaims them safely.
        tileCache.put(key, bitmap)
        while (tileCache.size > MAX_CACHED_TILES) {
            val iterator = tileCache.entries.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
    }

    private fun File.writeBytesSafely(bytes: ByteArray): Boolean = runCatching {
        parentFile?.mkdirs()
        outputStream().use { it.write(bytes); it.fd.sync() }
        length() == bytes.size.toLong()
    }.getOrDefault(false)

    @Synchronized
    private fun trimDiskCache() {
        val directory = cacheDirectory ?: return
        val files = directory.listFiles()?.filter { it.isFile && it.extension == "png" } ?: return
        var total = files.sumOf { it.length() }
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= MAX_DISK_BYTES) return@forEach
            total -= file.length()
            file.delete()
        }
        writeIndex(directory)
    }

    private fun writeIndex(directory: File) {
        runCatching {
            val index = File(directory, "index.txt")
            val temporary = File(directory, "index.txt.tmp")
            val rows = directory.listFiles()?.filter { it.isFile && it.extension == "png" }
                ?.sortedByDescending { it.lastModified() }
                ?.joinToString("\n") { "${it.name}\t${it.length()}\t${it.lastModified()}" }.orEmpty()
            temporary.writeText(rows)
            temporary.renameTo(index)
        }
    }
}
