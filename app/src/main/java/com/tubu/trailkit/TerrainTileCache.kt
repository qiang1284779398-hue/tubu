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
    fun load(key: String, executor: ExecutorService, loader: () -> ByteArray?): ByteArray? {
        val future = synchronized(pending) {
            pending[key] ?: CompletableFuture.supplyAsync({ loader() }, executor).also { created ->
                pending[key] = created
                created.whenComplete { _, _ -> synchronized(pending) { pending.remove(key, created) } }
            }
        }
        return try { future.get(14, TimeUnit.SECONDS) } catch (_: Exception) { null }
    }
}

internal class TerrainTileCache(context: Context) {
    private enum class Kind(val folder: String, val limitBytes: Long) {
        DEM("dem", 256L * 1024L * 1024L),
        IMAGERY("imagery", 512L * 1024L * 1024L)
    }
    private val root = File(context.cacheDir, "terrain_tile_cache")
    private val memory = TerrainTileMemoryStore(64L * 1024L * 1024L)
    private val coalescer = TerrainRequestCoalescer()
    private val executor = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "TerrainTileCache").apply { isDaemon = true }
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS).readTimeout(7, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS).retryOnConnectionFailure(true).build()
    private val demHits = AtomicLong()
    private val imageryHits = AtomicLong()
    private val demMisses = AtomicLong()
    private val imageryMisses = AtomicLong()
    private val demBytes = AtomicLong()
    private val imageryBytes = AtomicLong()
    private val appContext = context.applicationContext

    fun intercept(url: String): WebResourceResponse? {
        val kind = kindFor(url) ?: return null
        val key = sha256(url)
        memory.get(key)?.let { recordHit(kind); return response(it, 200, "OK") }
        val diskFile = File(File(root, kind.folder), "$key.tile")
        if (diskFile.isFile && diskFile.length() > 0L) {
            val bytes = runCatching { diskFile.readBytes() }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                diskFile.setLastModified(System.currentTimeMillis())
                memory.put(key, bytes)
                recordHit(kind)
                return response(bytes, 200, "OK")
            }
        }
        recordMiss(kind)
        if (!hasInternet()) return missingResponse()
        val bytes = coalescer.load(url, executor) { download(url, kind, key) } ?: return missingResponse()
        memory.put(key, bytes)
        return response(bytes, 200, "OK")
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
            FileOutputStream(temp).use { stream -> stream.write(bytes); stream.fd.sync() }
            if (!temp.renameTo(target)) {
                temp.delete()
                if (!target.exists()) target.writeBytes(bytes)
            }
            target.setLastModified(System.currentTimeMillis())
            trim(directory, kind.limitBytes)
            if (kind == Kind.DEM) demBytes.addAndGet(bytes.size.toLong()) else imageryBytes.addAndGet(bytes.size.toLong())
        } catch (_: Exception) { temp.delete() }
    }

    private fun trim(directory: File, limit: Long) {
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

    fun statsJson(): String =
        """{"dem":{"hits":${demHits.get()},"misses":${demMisses.get()},"bytesWritten":${demBytes.get()}},"imagery":{"hits":${imageryHits.get()},"misses":${imageryMisses.get()},"bytesWritten":${imageryBytes.get()}},"memoryBytes":${memory.sizeBytes()},"memoryEntries":${memory.sizeEntries()}}"""

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun response(bytes: ByteArray, status: Int, reason: String) = WebResourceResponse(
        "image/png", null, status, reason,
        mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "public, max-age=31536000"),
        ByteArrayInputStream(bytes)
    )

    private fun missingResponse() = WebResourceResponse(
        "image/png", null, 504, "Terrain Tile Missing",
        mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store", "X-Terrain-Cache-Miss" to "true"),
        ByteArrayInputStream(TRANSPARENT_PNG)
    )

    fun close() { executor.shutdownNow() }

    companion object {
        private val TRANSPARENT_PNG = android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p1sAAAAASUVORK5CYII=",
            android.util.Base64.DEFAULT
        )
    }
}
