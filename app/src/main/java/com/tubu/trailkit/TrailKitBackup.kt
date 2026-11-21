package com.tubu.trailkit

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Validate and install a raster MBTiles file without risking the existing map.
 * The candidate is copied to a temporary sibling, validated, then atomically
 * swapped into place. A failed copy/validation leaves the previous file intact.
 */
internal fun installMbtilesSafely(context: Context, source: Uri, projectId: String): String {
    val directory = File(context.filesDir, "offline_maps").apply { if (!exists() && !mkdirs()) error("无法创建离线地图目录") }
    val destination = File(directory, "map_${projectId}.mbtiles")
    val temp = File(directory, ".map_${projectId}_${UUID.randomUUID()}.tmp")
    val previous = File(directory, ".map_${projectId}_previous.tmp")
    try {
        context.contentResolver.openInputStream(source)?.use { input ->
            copyLimited(input, temp, 1536L * 1024L * 1024L)
        } ?: error("无法读取所选地图文件")
        require(temp.length() >= 1024L) { "地图文件为空或体积异常" }
        validateRasterMbtiles(temp)

        if (previous.exists()) {
            // Recover an interrupted previous swap before starting another import.
            if (!destination.exists()) {
                if (!previous.renameTo(destination)) error("检测到上次地图替换中断，无法恢复原地图")
            } else if (!previous.delete()) {
                error("无法清理上次地图替换的临时文件")
            }
        }
        if (destination.exists() && !destination.renameTo(previous)) error("无法安全保留原地图，已取消替换")
        if (!temp.renameTo(destination)) {
            if (previous.exists()) previous.renameTo(destination)
            error("新地图安装失败，原地图已恢复")
        }
        previous.delete()
        return destination.absolutePath
    } finally {
        temp.delete()
    }
}

/** Restore a previous MBTiles file if Android terminated an atomic replacement mid-flight. */
internal fun recoverInterruptedMbtilesReplacements(context: Context) {
    val directory=File(context.filesDir,"offline_maps")
    directory.listFiles()?.filter { it.name.startsWith(".map_") && it.name.endsWith("_previous.tmp") }?.forEach { previous ->
        val projectId=previous.name.removePrefix(".map_").removeSuffix("_previous.tmp")
        val destination=File(directory,"map_${projectId}.mbtiles")
        if(!destination.exists()) previous.renameTo(destination) else previous.delete()
    }
}

private fun validateRasterMbtiles(file: File) {
    SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        val tables = mutableSetOf<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
            while (cursor.moveToNext()) tables.add(cursor.getString(0))
        }
        require("tiles" in tables) { "不是有效的 MBTiles：缺少 tiles 表" }
        require("metadata" in tables) { "MBTiles 缺少 metadata 表，无法核对地图信息" }

        val metadata = mutableMapOf<String, String>()
        db.rawQuery("SELECT name, value FROM metadata", null).use { cursor ->
            while (cursor.moveToNext()) metadata[cursor.getString(0)] = cursor.getString(1)
        }
        val format = metadata["format"]?.lowercase()?.trim().orEmpty()
        require(format in setOf("png", "jpg", "jpeg", "webp")) {
            "暂不支持此地图格式（${format.ifBlank { "未知" }}），请使用 PNG/JPG/WebP 栅格 MBTiles"
        }
        val count = db.rawQuery("SELECT COUNT(*) FROM tiles", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
        require(count > 0L) { "地图包的 tiles 表为空，没有可显示的瓦片" }
        db.rawQuery("SELECT length(tile_data) FROM tiles LIMIT 1", null).use { cursor ->
            require(cursor.moveToFirst() && cursor.getLong(0) > 0L) { "地图包中的瓦片数据为空" }
        }
        val minZoom = metadata["minzoom"]?.toIntOrNull()
        val maxZoom = metadata["maxzoom"]?.toIntOrNull()
        if (minZoom != null && maxZoom != null) require(minZoom in 0..22 && maxZoom in minZoom..22) {
            "地图缩放级别元数据异常"
        }
        metadata["bounds"]?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }?.let { bounds ->
            if (bounds.size == 4) require(
                bounds.all { it.isFinite() } && bounds[0] >= -180 && bounds[2] <= 180 &&
                    bounds[1] >= -90 && bounds[3] <= 90 && bounds[0] <= bounds[2] && bounds[1] <= bounds[3]
            ) { "地图覆盖范围元数据异常" }
        }
    }
}

/** Copy picked/camera images into app-owned storage so their URI survives restarts. */
internal suspend fun storeGearPhoto(context: Context, source: Uri): String = withContext(Dispatchers.IO) {
    val directory = File(context.filesDir, "gear_photos").apply { if (!exists() && !mkdirs()) error("无法创建装备照片目录") }
    val extension = source.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
        ?.takeIf { it.matches(Regex("[a-z0-9]{2,5}")) } ?: "jpg"
    val destination = File(directory, "gear_${UUID.randomUUID()}.$extension")
    try {
        context.contentResolver.openInputStream(source)?.use { input ->
            copyLimited(input, destination, 80L * 1024L * 1024L)
        } ?: error("无法读取照片")
        require(destination.length() > 0L) { "照片文件为空" }
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", destination).toString()
    } catch (e: Exception) {
        destination.delete()
        throw e
    }
}

/**
 * ZIP backup contains project JSON, imported MBTiles files and all readable gear
 * photos. MapLibre's separately-managed download database is intentionally not
 * copied; its regions must be downloaded again on a new installation.
 */
internal fun writeTrailKitBackup(context: Context, destination: Uri, serializedProjects: String) {
    val projects = JSONArray(serializedProjects)
    context.contentResolver.openOutputStream(destination)?.use { raw ->
        ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
            val usedNames = mutableSetOf<String>()
            fun uniqueName(prefix: String, id: String, suffix: String): String {
                val safe = id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48).ifBlank { "item" }
                var name = "$prefix${safe}_$suffix"
                var n = 1
                while (!usedNames.add(name)) name = "$prefix${safe}_${n++}_$suffix"
                return name
            }
            for (i in 0 until projects.length()) {
                val project = projects.getJSONObject(i)
                val id = project.optString("id", "project_$i")
                val mapPath = project.optString("mapPath")
                if (mapPath.isNotBlank()) {
                    val map = File(mapPath)
                    require(map.isFile) { "项目「${project.optString("name")}」的离线地图文件不存在，完整备份已中止" }
                    val entryName = uniqueName("media/maps/", id, map.name.substringAfterLast('.', "mbtiles"))
                    zip.putNextEntry(ZipEntry(entryName))
                    map.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                    project.put("mapPath", "trailkit-backup://$entryName")
                }
                val gear = project.optJSONArray("gear") ?: continue
                for (g in 0 until gear.length()) {
                    val item = gear.getJSONObject(g)
                    val photo = item.optString("photo")
                    if (photo.isBlank()) continue
                    val input = openMedia(context, photo)
                        ?: error("无法读取装备照片「${item.optString("name")}」，完整备份已中止；请重新选择照片后重试")
                    input.use {
                        val ext = photo.substringBefore('?').substringAfterLast('.', "jpg")
                            .lowercase().takeIf { value -> value.matches(Regex("[a-z0-9]{2,5}")) } ?: "jpg"
                        val entryName = uniqueName("media/photos/", "${id}_${item.optString("id", g.toString())}", ext)
                        zip.putNextEntry(ZipEntry(entryName))
                        it.copyTo(zip)
                        zip.closeEntry()
                        item.put("photo", "trailkit-backup://$entryName")
                    }
                }
            }
            zip.putNextEntry(ZipEntry("projects.json"))
            zip.write(projects.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    } ?: error("无法创建备份文件")
}

internal fun readTrailKitBackup(context: Context, source: Uri): String {
    val raw = context.contentResolver.openInputStream(source) ?: error("无法读取备份文件")
    raw.use { stream ->
        val buffered = BufferedInputStream(stream)
        buffered.mark(8)
        val first = buffered.read()
        val second = buffered.read()
        buffered.reset()
        if (first != 0x50 || second != 0x4b) {
            val json = buffered.reader(Charsets.UTF_8).use { it.readText() }
            require(json.length <= 20 * 1024 * 1024) { "备份 JSON 过大" }
            JSONArray(json)
            return json
        }

        val mapFiles = mutableMapOf<String, File>()
        val photoFiles = mutableMapOf<String, File>()
        var projectJson: String? = null
        val created = mutableListOf<File>()
        try {
            ZipInputStream(buffered).use { zip ->
                var entryCount = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    require(entryCount <= 2000) { "备份包含过多文件" }
                    val name = entry.name
                    when {
                        name == "projects.json" -> {
                            val bytes = readLimitedBytes(zip, 20L * 1024L * 1024L)
                            projectJson = String(bytes, Charsets.UTF_8)
                        }
                        name.startsWith("media/maps/") || name.startsWith("media/photos/") -> {
                            val base = name.substringAfterLast('/')
                            require(base.isNotBlank() && base.matches(Regex("[A-Za-z0-9._-]{1,180}"))) { "备份媒体文件名异常" }
                            val isMap = name.startsWith("media/maps/")
                            val directory = File(context.filesDir, if (isMap) "offline_maps" else "gear_photos")
                            if (!directory.exists() && !directory.mkdirs()) error("无法创建媒体恢复目录")
                            val file = File(directory, "restored_${UUID.randomUUID()}_$base")
                            val maxBytes = if (isMap) 1024L * 1024L * 1024L else 80L * 1024L * 1024L
                            copyLimited(zip, file, maxBytes)
                            created.add(file)
                            val marker = "trailkit-backup://$name"
                            if (isMap) {
                                validateRasterMbtiles(file)
                                mapFiles[marker] = file
                            } else {
                                require(file.length() > 0L) { "备份中的照片为空" }
                                photoFiles[marker] = file
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val json = projectJson ?: error("备份中缺少 projects.json")
            val projects = JSONArray(json)
            for (i in 0 until projects.length()) {
                val project = projects.getJSONObject(i)
                val oldMap = project.optString("mapPath")
                if (oldMap.startsWith("trailkit-backup://")) {
                    val file = mapFiles[oldMap] ?: error("备份中缺少离线地图文件：$oldMap")
                    project.put("mapPath", file.absolutePath)
                }
                val gear = project.optJSONArray("gear") ?: continue
                for (g in 0 until gear.length()) {
                    val item = gear.getJSONObject(g)
                    val oldPhoto = item.optString("photo")
                    if (oldPhoto.startsWith("trailkit-backup://")) {
                        val file = photoFiles[oldPhoto] ?: error("备份中缺少装备照片：$oldPhoto")
                        item.put("photo", FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file).toString())
                    }
                }
            }
            return projects.toString()
        } catch (e: Exception) {
            created.forEach { it.delete() }
            throw e
        }
    }
}

private fun openMedia(context: Context, value: String): InputStream? {
    return try {
        val uri = Uri.parse(value)
        if (uri.scheme == "content" || uri.scheme == "file") context.contentResolver.openInputStream(uri)
        else File(value).takeIf { it.isFile }?.inputStream()
    } catch (_: Exception) { null }
}


/** Remove only app-owned photo files that no remaining gear item references. */
internal fun deleteUnreferencedProjectPhotos(context: Context, removed: Project, remaining: List<Project>) {
    val stillUsed = remaining.flatMap { it.gear }.map { it.photo }.filter { it.isNotBlank() }.toSet()
    val directory = File(context.filesDir, "gear_photos")
    removed.gear.map { it.photo }.filter { it.isNotBlank() && it !in stillUsed }.forEach { value ->
        runCatching {
            val uri = Uri.parse(value)
            if (uri.authority == context.packageName + ".fileprovider" &&
                uri.path.orEmpty().contains("/gear_photos/")) {
                val name = uri.lastPathSegment ?: return@runCatching
                val file = File(directory, name)
                if (file.canonicalFile.parentFile == directory.canonicalFile) file.delete()
            }
        }
    }
}

private fun readLimitedBytes(input: InputStream, limit: Long): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(32 * 1024)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= limit) { "备份项目数据过大" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun copyLimited(input: InputStream, destination: File, limit: Long) {
    var copied = 0L
    try {
        destination.outputStream().buffered().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                copied += count
                require(copied <= limit) { "备份媒体文件超过允许大小" }
                output.write(buffer, 0, count)
            }
        }
    } catch (e: Exception) {
        destination.delete()
        throw e
    }
}
