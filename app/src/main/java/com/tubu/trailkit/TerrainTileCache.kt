package com.tubu.trailkit

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.webkit.WebResourceResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal class TerrainTileMemoryStore(private val maxBytes: Long) {
    private val entries = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var usedBytes = 0L
    @Synchronized fun get(key: String): ByteArray? = entries[key]
    @Synchronized fun put(key: String, value: ByteArray) {
        entries.remove(key)?.let { usedBytes -= it.size }
        if (value.size.toLong() > maxBytes) return
        entries[key] = value
        usedBytes += value.size
        val iterator = entries.entries.iterator()
        while (usedBytes > maxBytes && iterator.hasNext()) {
            val entry = iterator.next()
            usedBytes -= entry.value.size
            iterator.remove()
        }
    }
    @Synchronized fun sizeBytes(): Long = usedBytes
    @Synchronized fun sizeEntries(): Int = entries.size
}

internal class TerrainRequestCoalescer {
    private val pending = HashMap<String, CompletableFuture<ByteArray?>>()
    fun loadAsync(
        key: String,
        executor: ExecutorService,
        loader: () -> ByteArray?,
        onComplete: (ByteArray?, Throwable?) -> Unit
    ) {
        synchronized(pending) {
            if (pending.containsKey(key)) return
            val created = CompletableFuture.supplyAsync({ loader() }, executor)
            pending[key] = created
            created.whenComplete { bytes, error ->
                synchronized(pending) { pending.remove(key, created) }
                onComplete(bytes, error)
            }
        }
    }

}

internal object TerrainTilePlaceholders {
    /**
     * 256x256 PNG, every pixel RGB(128, 0, 0).
     *
     * Terrarium DEM decoding is elevation = R*256 + G + B/256 - 32768, so
     * RGB(128, 0, 0) decodes to exactly 0 m. (The -10000 + (R*65536 + G*256 + B) * 0.1
     * formula sometimes quoted for this is Mapbox Terrain-RGB, NOT Terrarium: under
     * Terrarium a pixel chosen with that formula lands near -32,377 m, and a
     * transparent pixel (RGB 0,0,0) lands at -32,768 m. Either one turns every
     * uncached DEM tile into a ~32 km pit next to real terrain: the mesh cliffs,
     * terrain picking under the finger reads the pit, and contour generation sees
     * hundreds of phantom levels.) The placeholder is 256x256, the same size as a
     * real tile, because MapLibre's DEM border backfill errors out when neighbour
     * tile dimensions differ.
     */
    const val DEM_ZERO_ELEVATION_PNG_BASE64 =
        "iVBORw0KGgoAAAANSUhEUgAAAQAAAAEACAIAAADTED8xAAAB+0lEQVR42u3TQQkAAAjAwEU3un8reHAJBmsK3pIAA4ABwABgADAAGAAMAAYAA4ABwABgADAAGAAMAAYAA4ABwABgADAAGAAMAAYAA4ABwABgADAAGAAMAAYAA4ABwABgADAAGAAMAAYAA4ABwABgADAAGAAMAAYAA4ABwAAYAAwABgADgAHAAGAAMAAYAAwABgADgAHAAGAAMAAYAAwABgADgAHAAGAAMAAYAAwABgADgAHAAGAAMAAYAAwABgADgAHAAGAAMAAYAAwABgADgAHAAGAAMAAYAAwABgAJMAAYAAwABgADgAHAAGAAMAAYAAwABgADgAHAAGAAMAAYAAwABgADgAHAAGAAMAAYAAwABgADgAHAAGAAMAAYAAwABgADgAHAAGAAMAAYAAwABgADgAHAAGAAMAAYAAyAASTAAGAAMAAYAAwABgADgAHAAGAAMAAYAAwABgADgAHAAGAAMAAYAAwABgADgAHAAGAAMAAYAAwABgADgAHAAGAAMAAYAAwABgADgAHAAGAAMAAYAAwABgADgAHAAGAAMAAGAAOAAcAAYAAwABgADAAGAAOAAcAAYAAwABgADAAGAAOAAcAAYAAwABgADAAGAAOAAcAAYAAwABgADAAGAAOAAcAAYAAwABgADAAGAAOAAcAAYAAwABgADADHAnGpB4FDLHHvAAAAAElFTkSuQmCC"

    fun terrariumElevationMeters(red: Int, green: Int, blue: Int): Double =
        red * 256.0 + green + blue / 256.0 - 32768.0
}

internal class TerrainTileCache(context: Context) {
    companion object {
        private val PLACEHOLDER_PNG = android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNgAAIAAAUAAen63NgAAAAASUVORK5CYII=",
            android.util.Base64.DEFAULT
        )
        private val DEM_PLACEHOLDER_PNG = android.util.Base64.decode(
            TerrainTilePlaceholders.DEM_ZERO_ELEVATION_PNG_BASE64,
            android.util.Base64.DEFAULT
        )
    }
    private enum class Kind(val folder: String, val limitBytes: Long) {
        DEM("dem", 256L * 1024L * 1024L),
        IMAGERY("imagery", 512L * 1024L * 1024L)
    }
    private val root = File(context.filesDir, "terrain_tile_cache")
    private val memory = TerrainTileMemoryStore(64L * 1024L * 1024L)
    private val coalescer = TerrainRequestCoalescer()
    private val executor = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "TerrainTileCache").apply { isDaemon = true }
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS).retryOnConnectionFailure(true).build()
    private val demHits = AtomicLong()
    private val imageryHits = AtomicLong()
    private val demMisses = AtomicLong()
    private val imageryMisses = AtomicLong()
    private val demBytes = AtomicLong()
    private val imageryBytes = AtomicLong()
    private val demOfflineMissing = AtomicLong()
    private val imageryOfflineMissing = AtomicLong()
    private val demFailures = AtomicLong()
    private val imageryFailures = AtomicLong()
    private val demFallbacks = AtomicLong()
    private val imageryFallbacks = AtomicLong()
    private val demDownloads = AtomicLong()
    private val imageryDownloads = AtomicLong()
    private val appContext = context.applicationContext
    @Volatile var onTileCached: ((String) -> Unit)? = null

    fun intercept(url: String): WebResourceResponse? {
        val kind = kindFor(url) ?: return null
        val key = sha256(url)
        val diskFile = File(File(root, kind.folder), "$key.tile")
        memory.get(key)?.let {
            touchIfStale(diskFile)
            recordHit(kind)
            return response(it, 200, "OK")
        }
        if (diskFile.isFile && diskFile.length() > 0L) {
            val bytes = runCatching { diskFile.readBytes() }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                touchIfStale(diskFile)
                memory.put(key, bytes)
                recordHit(kind)
                return response(bytes, 200, "OK")
            }
        }
        recordMiss(kind)
        recordFallback(kind)
        // Do not block Chromium's tile request thread on cache I/O.
        // null delegates the actual tile request to WebView's native network stack.
        // A separate bounded worker opportunistically persists a second fetch.
        try {
            coalescer.loadAsync(url, executor, { download(url, kind, key) }) { bytes, error ->
                if (bytes != null && bytes.isNotEmpty()) {
                    memory.put(key, bytes)
                    runCatching { onTileCached?.invoke(kind.name) }
                } else {
                    recordFailure(kind)
                    if (!hasInternet()) recordOfflineMissing(kind)
                }
            }
        } catch (_: Exception) {
            recordFailure(kind)
        }
        // Return a successful, non-cacheable placeholder immediately; the source is reloaded
        // when the background fetch has persisted the real tile. Never poison GL JS with 504.
        // DEM placeholders must encode 0 m elevation (see TerrainTilePlaceholders), otherwise
        // the terrain scene sinks 10 km below the camera and renders black.
        return placeholderResponse(kind)
    }

    private fun kindFor(url: String): Kind? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path.orEmpty()
        return when {
            host == "s3.amazonaws.com" && path.startsWith("/elevation-tiles-prod/terrarium/") -> Kind.DEM
            host == "services.arcgisonline.com" && path.startsWith("/ArcGIS/rest/services/World_Imagery/MapServer/tile/", true) -> Kind.IMAGERY
            else -> null
        }
    }

    private fun hasInternet(): Boolean {
        return try {
            val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) { false }
    }

    private fun download(url: String, kind: Kind, key: String): ByteArray? {
        val request = Request.Builder().url(url).header("User-Agent", "TrailKit/3D-Terrain").build()
        return try {
            client.newCall(request).execute().use { result ->
                if (!result.isSuccessful) return null
                val bytes = result.body?.bytes() ?: return null
                if (bytes.isEmpty() || bytes.size > 4 * 1024 * 1024) return null
                writeDisk(kind, key, bytes)
                recordDownload(kind)
                bytes
            }
        } catch (_: Exception) { null }
    }

    private fun writeDisk(kind: Kind, key: String, bytes: ByteArray) {
        val directory = File(root, kind.folder)
        if (!directory.exists()) directory.mkdirs()
        val target = File(directory, "$key.tile")
        val temp = File(directory, "$key.tmp")
        try {
            FileOutputStream(temp).use { stream -> stream.write(bytes) }
            if (!temp.renameTo(target)) {
                temp.delete()
                if (!target.exists()) target.writeBytes(bytes)
            }
            target.setLastModified(System.currentTimeMillis())
            trim(directory, kind.limitBytes)
            if (kind == Kind.DEM) demBytes.addAndGet(bytes.size.toLong()) else imageryBytes.addAndGet(bytes.size.toLong())
        } catch (_: Exception) { temp.delete() }
    }

    private val lastTrimAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // Refresh a cached tile's LRU timestamp at most every 10 minutes: doing it on
    // every hit is one stat+utime syscall pair per tile on the interceptor thread.
    private fun touchIfStale(file: File) {
        val now = System.currentTimeMillis()
        if (now - file.lastModified() > 10 * 60 * 1000L) file.setLastModified(now)
    }

    private fun trim(directory: File, limit: Long) {
        // Throttled to once a minute per directory: listing + sorting by
        // lastModified stats every cached file, and this used to run after EVERY
        // downloaded tile.
        val now = System.currentTimeMillis()
        val trimKey = directory.absolutePath
        if (now - (lastTrimAt[trimKey] ?: 0L) < 60_000L) return
        lastTrimAt[trimKey] = now
        val files = directory.listFiles()?.filter { it.isFile && it.extension == "tile" }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= limit) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }
    private fun recordHit(kind: Kind) { if (kind == Kind.DEM) demHits.incrementAndGet() else imageryHits.incrementAndGet() }
    private fun recordMiss(kind: Kind) { if (kind == Kind.DEM) demMisses.incrementAndGet() else imageryMisses.incrementAndGet() }
    private fun recordOfflineMissing(kind: Kind) {
        if (kind == Kind.DEM) demOfflineMissing.incrementAndGet() else imageryOfflineMissing.incrementAndGet()
    }
    private fun recordFailure(kind: Kind) {
        if (kind == Kind.DEM) demFailures.incrementAndGet() else imageryFailures.incrementAndGet()
    }
    private fun recordFallback(kind: Kind) {
        if (kind == Kind.DEM) demFallbacks.incrementAndGet() else imageryFallbacks.incrementAndGet()
    }
    private fun recordDownload(kind: Kind) {
        if (kind == Kind.DEM) demDownloads.incrementAndGet() else imageryDownloads.incrementAndGet()
    }

    fun statsJson(): String =
        """{"dem":{"hits":${demHits.get()},"misses":${demMisses.get()},"fallbacks":${demFallbacks.get()},"failures":${demFailures.get()},"offlineMissing":${demOfflineMissing.get()},"downloads":${demDownloads.get()},"bytesWritten":${demBytes.get()}},"imagery":{"hits":${imageryHits.get()},"misses":${imageryMisses.get()},"fallbacks":${imageryFallbacks.get()},"failures":${imageryFailures.get()},"offlineMissing":${imageryOfflineMissing.get()},"downloads":${imageryDownloads.get()},"bytesWritten":${imageryBytes.get()}},"memoryBytes":${memory.sizeBytes()},"memoryEntries":${memory.sizeEntries()}}"""

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun response(bytes: ByteArray, status: Int, reason: String) = WebResourceResponse(
        mimeType(bytes), null, status, reason,
        mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "public, max-age=31536000"),
        ByteArrayInputStream(bytes)
    )

    private fun placeholderResponse(kind: Kind) = WebResourceResponse(
        "image/png", null, 200, "OK",
        mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store", "X-Terrain-Placeholder" to "true"),
        ByteArrayInputStream(if (kind == Kind.DEM) DEM_PLACEHOLDER_PNG else PLACEHOLDER_PNG)
    )

    private fun mimeType(bytes: ByteArray): String =
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) "image/jpeg" else "image/png"

    fun close() { executor.shutdownNow() }
}
