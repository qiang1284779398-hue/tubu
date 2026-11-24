package com.tubu.trailkit

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import android.widget.Toast
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private val categories = listOf("背负系统","帐篷与营地系统","睡眠系统","衣物系统","鞋靴系统","炊事与饮水系统","行进系统","电子与导航系统","急救与卫生系统")
private val forest = androidx.compose.ui.graphics.Color(0xFF183C32)
private val sand = androidx.compose.ui.graphics.Color(0xFFF3F0E7)
private val muted = androidx.compose.ui.graphics.Color(0xFF78827A)

fun normalizeGearCategory(raw:String):String {
    val s=raw.trim().lowercase(Locale.ROOT)
    return when {
        s.contains("背负") || s.contains("背包") -> "背负系统"
        s.contains("帐篷") || s.contains("营地") || s.contains("地布") || s.contains("露营") -> "帐篷与营地系统"
        s.contains("睡眠") || s.contains("睡袋") || s.contains("气垫") || s.contains("蛋巢") || s.contains("枕") -> "睡眠系统"
        // 收纳袋、毛巾等按用户现有装备体系归入衣物系统；避免“防水”中的“水”触发炊事分类
        s.contains("衣物") || s.contains("服装") || s.contains("冲锋") || s.contains("软壳") || s.contains("抓绒") || s.contains("速干") || s.contains("羽绒") || s.contains("袜") || s.contains("帽") || s.contains("面巾") || s.contains("雨衣") || s.contains("毛巾") || s.contains("收纳袋") || s.contains("收纳包") -> "衣物系统"
        s.contains("鞋") || s.contains("靴") || s.contains("雪套") || s.contains("护腿") -> "鞋靴系统"
        s.contains("炊") || s.contains("炉") || s.contains("锅") || s.contains("餐具") || s.contains("饮水") || s.contains("水壶") || s.contains("水袋") || s.contains("净水") || s.contains("滤水") -> "炊事与饮水系统"
        s.contains("行进") || s.contains("登山杖") || s.contains("徒步") || s.contains("冰爪") -> "行进系统"
        s.contains("电子") || s.contains("导航") || s.contains("手表") || s.contains("电池") || s.contains("充电") || s.contains("gps") || s.contains("头灯") || s.contains("照明") -> "电子与导航系统"
        s.contains("急救") || s.contains("卫生") || s.contains("药") || s.contains("防晒") || s.contains("护理") || s.contains("保温毯") || s.contains("哨子") || s == "其他" -> "急救与卫生系统"
        else -> "急救与卫生系统"
    }
}

data class Gear(val id:String,val name:String,val category:String,val price:Double,val quantity:Int,val note:String,val photo:String,val weightGrams:Double=0.0,val packed:Boolean=false,val warrantyUntil:String="",val maintenanceLog:String="")
data class Project(val id:String,val name:String,val place:String,val date:String,val gear:List<Gear>,val route:String="",val lodging:String="",val resupply:String="",val emergency:String="",val trackData:String="",val mapPath:String="",val waypointData:String="[]",val elevationData:String="[]")

class MainActivity : ComponentActivity() {
    var incomingSharedGpx by mutableStateOf<Uri?>(null)
        private set

    private fun sharedGpxFrom(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                 else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                    ?: intent.data
                    ?: intent.getStringExtra(Intent.EXTRA_TEXT)?.let { value ->
                        runCatching { Uri.parse(value) }.getOrNull()?.takeIf { it.scheme == "content" || it.scheme == "file" }
                    }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val streams = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                streams?.firstOrNull()
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingSharedGpx = sharedGpxFrom(intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val mode = window.windowManager.defaultDisplay.supportedModes.maxByOrNull { it.refreshRate }
                if (mode != null && mode.refreshRate >= 90f) window.attributes = window.attributes.apply { preferredDisplayModeId = mode.modeId }
            } catch (_: Exception) { }
        }
        setContent {
            TrailKitApp(
                sharedGpxUri = incomingSharedGpx,
                autoOpen3D = intent.getBooleanExtra("trailkit_test_3d", false),
                onSharedGpxHandled = { incomingSharedGpx = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingSharedGpx = sharedGpxFrom(intent)
    }
}

private fun load(context:Context): List<Project> = try {
    val a=JSONArray(context.getSharedPreferences("trailkit",0).getString("projects","[]"))
    List(a.length()) { i ->
        val p=a.getJSONObject(i); val ga=p.optJSONArray("gear") ?: JSONArray()
        val gs=List(ga.length()) { j -> val g=ga.getJSONObject(j); Gear(g.optString("id"),g.optString("name"),g.optString("category"),g.optDouble("price"),g.optInt("quantity",1),g.optString("note"),g.optString("photo"),g.optDouble("weightGrams",0.0),g.optBoolean("packed",false),g.optString("warrantyUntil"),g.optString("maintenanceLog")) }
        Project(p.optString("id"),p.optString("name"),p.optString("place"),p.optString("date"),gs,p.optString("route"),p.optString("lodging"),p.optString("resupply"),p.optString("emergency"),p.optString("trackData"),p.optString("mapPath"),p.optString("waypointData","[]"),p.optString("elevationData","[]"))
    }
} catch (_:Exception) { emptyList() }

private fun persist(context:Context, projects:List<Project>) {
    val a=JSONArray()
    projects.forEach { p ->
        val ga=JSONArray()
        p.gear.forEach { g -> ga.put(JSONObject().put("id",g.id).put("name",g.name).put("category",g.category).put("price",g.price).put("quantity",g.quantity).put("note",g.note).put("photo",g.photo).put("weightGrams",g.weightGrams).put("packed",g.packed).put("warrantyUntil",g.warrantyUntil).put("maintenanceLog",g.maintenanceLog)) }
        a.put(JSONObject().put("id",p.id).put("name",p.name).put("place",p.place).put("date",p.date).put("gear",ga).put("route",p.route).put("lodging",p.lodging).put("resupply",p.resupply).put("emergency",p.emergency).put("trackData",p.trackData).put("mapPath",p.mapPath).put("waypointData",p.waypointData).put("elevationData",p.elevationData))
    }
    context.getSharedPreferences("trailkit",0).edit().putString("projects",a.toString()).apply()
}

private fun loadWithBundledYubeng(context:Context):List<Project> {
    val current=load(context)
    val prefs=context.getSharedPreferences("trailkit",Context.MODE_PRIVATE)
    if(prefs.getBoolean("bundled_yubeng_seeded",false) && current.any { it.id=="bundled-yubeng" }) return current
    if(current.any { it.id=="bundled-yubeng" }) {
        prefs.edit().putBoolean("bundled_yubeng_seeded",true).apply()
        return current
    }
    return try {
        val encoded=buildString {
            for(part in 1..5) append(context.assets.open("yubeng_route_v2_part${part}.dat").bufferedReader(Charsets.UTF_8).use { it.readText().trim() })
        }
        val compressed=android.util.Base64.decode(encoded,android.util.Base64.DEFAULT)
        val lines=java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(compressed)).bufferedReader(Charsets.UTF_8).use { it.readLines() }
        val track=lines.getOrNull(0).orEmpty()
        val elevations=lines.getOrNull(1).orEmpty()
        val waypoints=lines.getOrNull(2).orEmpty()
        require(track.startsWith("P5") && elevations.startsWith("E1") && waypoints.startsWith("W1")) { "内置雨崩路线数据不完整" }
        val bundled=Project(
            id="bundled-yubeng",
            name="雨崩",
            place="云南·迪庆·雨崩",
            date="内置测试路线",
            gear=emptyList(),
            route="内置 GPX 测试路线：原始 27,373 个轨迹点，按约 2 米误差简化为 3,647 个导航点；轨迹约 72.65 km；182 个途经点。当前内置数据不含高程剖面。",
            trackData=track,
            waypointData=waypoints,
            elevationData=elevations
        )
        (current+bundled).also {
            persist(context,it)
            prefs.edit().putBoolean("bundled_yubeng_seeded",true).apply()
        }
    } catch(e:Exception) {
        android.widget.Toast.makeText(context,"内置雨崩测试路线加载失败：${e.message ?: "数据读取错误"}",android.widget.Toast.LENGTH_LONG).show()
        current
    }
}
private suspend fun uploadTrailPhotoToGithub(context: Context, uri: Uri, token: String): String = withContext(Dispatchers.IO) {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if(c.moveToFirst()) c.getString(0) else null } ?: "photo.jpg"
    val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "photo.jpg" }
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("读取照片失败")
    if(bytes.size > 20 * 1024 * 1024) error("${safeName} 超过20MB，暂不上传")
    val remoteName = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}_${safeName}"
    val endpoint = URL("https://api.github.com/repos/qiang1284779398-hue/tubu/contents/test/${remoteName}")
    val connection = (endpoint.openConnection() as HttpURLConnection).apply {
        requestMethod = "PUT"; connectTimeout = 20000; readTimeout = 30000; doOutput = true
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("Authorization", "Bearer ${token}")
        setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
    }
    val payload = JSONObject().put("message", "Upload photo from TrailKit").put("content", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)).toString()
    connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
    val code = connection.responseCode
    val response = (if(code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
    connection.disconnect()
    if(code !in 200..299) {
        val message = try { JSONObject(response).optString("message") } catch(_:Exception) { response.take(160) }
        error("GitHub上传失败 (${code})：${message}")
    }
    remoteName
}

private fun yuan(v:Double)="¥"+String.format(Locale.CHINA,"%.2f",v)

private fun deleteProjectNavigationData(context:Context,project:Project) {
    File(context.filesDir,"trailkit_track_${project.id.hashCode()}.gpx").delete()
    val navigation=context.getSharedPreferences("trailkit_navigation",Context.MODE_PRIVATE)
    val routeHash=project.trackData.hashCode()
    navigation.edit()
        .remove("custom_waypoints_${project.id}")
        .remove("reverse_${project.id}")
        .remove("voice_${project.id}")
        .remove("offroute_${project.id}")
        .remove("progress_${project.id}_${routeHash}_fwd")
        .remove("progress_${project.id}_${routeHash}_rev")
        .remove("segment_${project.id}_${routeHash}_fwd")
        .remove("segment_${project.id}_${routeHash}_rev")
        .apply()
    val view=context.getSharedPreferences("trailkit_map_view",Context.MODE_PRIVATE)
    val viewKey="${project.id}_${project.mapPath.hashCode()}"
    view.edit().remove("zoom_$viewKey").remove("lon_$viewKey").remove("lat_$viewKey").apply()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TrailKitApp(sharedGpxUri: Uri? = null, autoOpen3D: Boolean = false, onSharedGpxHandled: () -> Unit = {}) {
    val context= LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(context) {
        withContext(Dispatchers.IO) { recoverInterruptedMbtilesReplacements(context) }
    }
    var projects by remember { mutableStateOf(loadWithBundledYubeng(context)) }
    var selected by remember { mutableStateOf<String?>(if (autoOpen3D) "bundled-yubeng" else null) }
    var projectPage by remember(selected) { mutableStateOf("gear") }
    var projectDialog by remember { mutableStateOf(false) }
    var pendingSharedGpx by remember { mutableStateOf<Uri?>(null) }
    var sharedGpxSuggestedName by remember { mutableStateOf("") }
    var gearDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Gear?>(null) }
    var chosenPhoto by remember { mutableStateOf("") }
    var cameraPhotoUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) cameraPhotoUri?.let { captured ->
            coroutineScope.launch {
                try { chosenPhoto = storeGearPhoto(context, captured) }
                catch (e: Exception) { Toast.makeText(context, "照片保存失败：${e.message ?: "请重试"}", Toast.LENGTH_LONG).show() }
            }
        }
    }
    var manageProjectId by remember { mutableStateOf<String?>(null) }
    var editProjectField by remember { mutableStateOf<String?>(null) }
    var editingProjectId by remember { mutableStateOf<String?>(null) }
    var editProjectValue by remember { mutableStateOf("") }
    var deleteProjectId by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var packedOnly by remember { mutableStateOf(false) }
    var sortByWeight by remember { mutableStateOf(false) }
    var itineraryProjectId by remember { mutableStateOf<String?>(null) }
    var libraryOpen by remember { mutableStateOf(false) }
    var libraryTargetProjectId by remember { mutableStateOf<String?>(null) }
    var advancedToolsOpen by remember { mutableStateOf(false) }
    var weatherOpen by remember { mutableStateOf(false) }
    var safetyOpen by remember { mutableStateOf(false) }
    var trackOpen by remember { mutableStateOf(false) }
    var gpxTargetProjectId by remember { mutableStateOf<String?>(null) }
    var mapOpen by remember { mutableStateOf(false) }
    var terrain3dOpen by remember { mutableStateOf(autoOpen3D) }
    var arCameraOpen by remember { mutableStateOf(false) }
    var mapOpenedFromCamera by remember { mutableStateOf(false) }
    var mapTargetProjectId by remember { mutableStateOf<String?>(null) }
    var githubPhotoDialog by remember { mutableStateOf(false) }
    var githubToken by remember { mutableStateOf("") }
    var githubPhotoUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var githubUploading by remember { mutableStateOf(false) }
    var githubPublicAcknowledged by remember { mutableStateOf(false) }
    var githubUploadStatus by remember { mutableStateOf("") }

    LaunchedEffect(sharedGpxUri) {
        if (sharedGpxUri != null) {
            pendingSharedGpx = sharedGpxUri
            val sharedName = try {
                context.contentResolver.query(sharedGpxUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }.orEmpty()
            } catch (_: Exception) { "" }
            if (sharedName.isNotBlank() && !sharedName.lowercase(Locale.ROOT).endsWith(".gpx")) {
                Toast.makeText(context, "分享的文件不是 GPX 格式", Toast.LENGTH_LONG).show()
                pendingSharedGpx = null
                onSharedGpxHandled()
            } else {
                sharedGpxSuggestedName = sharedName.substringBeforeLast('.', sharedName).ifBlank { "新建徒步项目" }
                projectDialog = true
            }
        }
    }

    val gpxImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val target = gpxTargetProjectId
            coroutineScope.launch {
                try {
                    val (displayName, track) = withContext(Dispatchers.IO) {
                        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        }.orEmpty()
                        if (!name.lowercase(Locale.ROOT).endsWith(".gpx")) error("请选择 .gpx 格式的轨迹文件")
                        name to readGpxTrack(context, uri)
                    }
                    if (target != null) {
                        val previousRoute=projects.firstOrNull { it.id==target }?.trackData.orEmpty()
                        if(previousRoute.isNotBlank() && previousRoute!=track.encodedPoints) {
                            // Offline corridors belong to the old GPX; discard them when replacing that route.
                            deleteOfflineRegionsForProject(context,target)
                        }
                        val updated = projects.map { item ->
                            if (item.id == target) item.copy(route = listOf(item.route, track.summary).filter { it.isNotBlank() }.joinToString("\n"), trackData=track.encodedPoints, waypointData=track.encodedWaypoints, elevationData=track.encodedElevations) else item
                        }
                        persist(context, updated)
                        projects = updated
                        Toast.makeText(context, "GPX 轨迹分析完成，已保存到行程信息", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "GPX 导入失败：" + (e.message ?: "文件格式不正确"), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    val mbtilesImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val target = mapTargetProjectId
            coroutineScope.launch {
                try {
                    if (target != null) {
                        val installedPath = withContext(Dispatchers.IO) { installMbtilesSafely(context, uri, target) }
                        val updated = projects.map { if (it.id == target) it.copy(mapPath = installedPath) else it }
                        persist(context, updated)
                        projects = updated
                        Toast.makeText(context, "离线地图验证并导入成功，原地图已安全保留", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "地图导入失败，原地图未被替换：" + (e.message ?: "文件格式不正确"), Toast.LENGTH_LONG).show()
                } finally { mapTargetProjectId = null }
            }
        } else { mapTargetProjectId = null }
    }
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) coroutineScope.launch {
            try {
                val json = context.getSharedPreferences("trailkit",0).getString("projects","[]") ?: "[]"
                withContext(Dispatchers.IO) { writeTrailKitBackup(context, uri, json) }
                Toast.makeText(context,"项目备份 ZIP 已导出（项目、装备照片和导入的 MBTiles；MapLibre 下载区不包含）",Toast.LENGTH_LONG).show()
            } catch(e:Exception) { Toast.makeText(context,"备份导出失败：${e.message ?: "未知错误"}",Toast.LENGTH_LONG).show() }
        }
    }
    val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) coroutineScope.launch {
            try {
                val json = withContext(Dispatchers.IO) { readTrailKitBackup(context, uri) }
                val parsed = JSONArray(json)
                context.getSharedPreferences("trailkit",0).edit().putString("projects",parsed.toString()).apply()
                projects=load(context); selected=null
                Toast.makeText(context,"备份恢复成功：项目、照片和随包 MBTiles 已恢复",Toast.LENGTH_LONG).show()
            } catch(e:Exception) { Toast.makeText(context,"恢复失败：${e.message ?: "备份文件无效"}",Toast.LENGTH_LONG).show() }
        }
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) coroutineScope.launch {
            try { chosenPhoto = storeGearPhoto(context, uri) }
            catch (e: Exception) { Toast.makeText(context, "照片导入失败：${e.message ?: "请重试"}", Toast.LENGTH_LONG).show() }
        }
    }
    val githubPhotoPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        githubPhotoUris=uris
        githubUploadStatus=if(uris.isEmpty()) "" else "已选择 ${uris.size} 张照片"
    }
    fun takeGearPhoto() {
        try {
            val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES) ?: context.cacheDir
            if (!dir.exists()) dir.mkdirs()
            val file = File.createTempFile("trailkit_gear_", ".jpg", dir)
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            cameraPhotoUri = uri
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开相机：${e.message ?: "请检查相机应用"}", Toast.LENGTH_LONG).show()
        }
    }
    fun save(list:List<Project>) { projects=list; persist(context,list) }
    val project=projects.firstOrNull { it.id==selected }
    // Cache category filtering and weight aggregation so opening/recomposing a large project
    // does not repeatedly scan the full gear list for every category.
    val categoryWeights = remember(project?.id, project?.gear) {
        categories.associateWith { category ->
            project?.gear.orEmpty().asSequence()
                .filter { normalizeGearCategory(it.category) == category }
                .sumOf { it.weightGrams * it.quantity }
        }
    }
    val categoryGroups = remember(project?.id, project?.gear, searchQuery, packedOnly, sortByWeight) {
        categories.associateWith { category ->
            val filtered = project?.gear.orEmpty().filter {
                normalizeGearCategory(it.category) == category &&
                    (it.name.contains(searchQuery, true) || it.note.contains(searchQuery, true)) &&
                    (!packedOnly || !it.packed)
            }
            if (sortByWeight) filtered.sortedByDescending { it.weightGrams * it.quantity } else filtered
        }
    }

    // Android system back / edge-swipe: return from a project detail page to the project list instead of closing the app.
    BackHandler(enabled = project != null) {
        selected = null
        projectPage = "gear"
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = forest,
            onPrimary = androidx.compose.ui.graphics.Color.White,
            primaryContainer = androidx.compose.ui.graphics.Color(0xFFDCE7DC),
            onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF183C32),
            secondary = androidx.compose.ui.graphics.Color(0xFF718477),
            onSecondary = androidx.compose.ui.graphics.Color.White,
            secondaryContainer = androidx.compose.ui.graphics.Color(0xFFE5EBE2),
            background = sand,
            surface = androidx.compose.ui.graphics.Color(0xFFFFFEFA),
            surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE9EDE6),
            onSurface = androidx.compose.ui.graphics.Color(0xFF202D27),
            onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF68756C),
            outline = androidx.compose.ui.graphics.Color(0xFFD5DCD3),
            error = androidx.compose.ui.graphics.Color(0xFFB65346)
        ),
        shapes = Shapes(
            small = RoundedCornerShape(16.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(26.dp)
        )
    ) {
        Scaffold(containerColor=sand, topBar={
            TopAppBar(
                title={ Column { Text(project?.name ?: "徒步装备管家",fontWeight=FontWeight.Bold); Text(if(project==null) "把每一次出发，准备得漂亮一点" else (if(projectPage=="gear") "装备清单" else "户外导航")+" · "+project.place,fontSize=11.sp,color=muted) } },
                navigationIcon={ if(project!=null) IconButton(onClick={selected=null;projectPage="gear"}) { Icon(Icons.Default.ArrowBack,"返回") } },
                actions={
                    if(project!=null) {
                        if(projectPage=="gear") IconButton(onClick={makePoster(context,project)}) { Icon(Icons.Default.Image,"生成海报") }
                        if(projectPage=="nav") IconButton(onClick={itineraryProjectId=project.id}) { Icon(Icons.Default.Landscape,"行程信息") }
                    } else {
                        IconButton(onClick={exportBackup.launch("TrailKit-项目备份.zip")}) { Icon(Icons.Default.Image,"导出备份") }
                        IconButton(onClick={importBackup.launch(arrayOf("application/zip","application/json","application/octet-stream","text/*"))}) { Icon(Icons.Default.Add,"恢复备份") }
                    }
                }
            )
        }, floatingActionButton={
            if(project==null || projectPage=="gear") FloatingActionButton(onClick={ if(project==null) projectDialog=true else {editing=null;chosenPhoto="";gearDialog=true} },containerColor=forest,contentColor=androidx.compose.ui.graphics.Color.White) { Icon(Icons.Default.Add,"新增") }
        }) { padding ->
            if(project==null) {
                LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal=18.dp),verticalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(top=18.dp,bottom=90.dp)) {
                    item {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color(0xFF244B3D), androidx.compose.ui.graphics.Color(0xFF102A23)))).padding(24.dp)) {
                            Text("TRAIL KIT  /  FIELD NOTES",color=androidx.compose.ui.graphics.Color(0xFFB9C9B5),fontSize=11.sp,fontWeight=FontWeight.Bold)
                            Spacer(Modifier.height(14.dp))
                            Text("每件装备，\n都有它的价值。",color=androidx.compose.ui.graphics.Color.White,fontSize=28.sp,fontWeight=FontWeight.ExtraBold,lineHeight=36.sp)
                            Spacer(Modifier.height(10.dp))
                            Text("创建徒步项目 · 记录装备照片与价格 · 生成专属海报",color=androidx.compose.ui.graphics.Color(0xFFD8E2D7),fontSize=12.sp)
                            Spacer(Modifier.height(20.dp))
                            Text("我的徒步项目  /  ${projects.size}",color=androidx.compose.ui.graphics.Color.White,fontWeight=FontWeight.SemiBold)
                            Spacer(Modifier.height(10.dp))
                            Text("数据备份：右上角导出 / 恢复",color=androidx.compose.ui.graphics.Color(0xFFD8E2D7),fontSize=11.sp)
                        }
                    }
                    item {
                        Card(Modifier.clip(RoundedCornerShape(22.dp)).fillMaxWidth().clickable { githubPhotoDialog=true },shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=androidx.compose.ui.graphics.Color(0xFFFFFEFA))) {
                            Row(Modifier.fillMaxWidth().padding(18.dp),verticalAlignment=Alignment.CenterVertically) {
                                Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(androidx.compose.ui.graphics.Color(0xFFE5EBE2)),contentAlignment=Alignment.Center) { Icon(Icons.Default.Image,null,tint=forest,modifier=Modifier.size(28.dp)) }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("照片云上传",fontWeight=FontWeight.Bold,fontSize=16.sp,color=forest)
                                    Text("备份手机照片到 GitHub · test/",fontSize=12.sp,color=muted)
                                }
                                Icon(Icons.Default.Add,null,tint=forest)
                            }
                        }
                    }
                    if(projects.isEmpty()) item { Empty("还没有徒步项目","点击右下角 + 创建你的第一个计划") }
                    items(projects,key={it.id}) { p ->
                        Card(Modifier.clip(RoundedCornerShape(22.dp)).fillMaxWidth().combinedClickable(onClick={ selected=p.id },onLongClick={ manageProjectId=p.id }),shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=androidx.compose.ui.graphics.Color(0xFFFFFEFA)),elevation=CardDefaults.cardElevation(defaultElevation=3.dp)) {
                            Column(Modifier.padding(18.dp)) {
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(androidx.compose.ui.graphics.Color(0xFFE5EBE2)),contentAlignment=Alignment.Center) { Icon(Icons.Default.Landscape,null,tint=forest,modifier=Modifier.size(27.dp)) }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f), verticalArrangement=Arrangement.Center) { Text(p.name,fontSize=17.sp,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis,lineHeight=21.sp); Text(listOf(p.place,p.date).filter{it.isNotBlank()}.joinToString(" · "),fontSize=12.sp,color=muted,maxLines=2,overflow=TextOverflow.Ellipsis,lineHeight=16.sp) }
                                    IconButton(onClick={ manageProjectId=p.id }) { Icon(Icons.Default.MoreVert,"项目管理",tint=muted) }
                                }
                                Spacer(Modifier.height(12.dp)); HorizontalDivider(color=sand); Spacer(Modifier.height(10.dp))
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("${p.gear.size} 件装备",color=muted,fontSize=12.sp); Text(yuan(p.gear.sumOf{it.price*it.quantity}),color=forest,fontWeight=FontWeight.Bold) }
                            }
                        }
                    }
                }
            } else {
                val p=project
                Column(Modifier.fillMaxSize().padding(padding).pointerInput(project?.id, projectPage) {
                    var dragOffset = 0f
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            dragOffset += dragAmount
                        },
                        onDragEnd = {
                            if (dragOffset < -72f) projectPage = "nav"
                            else if (dragOffset > 72f) projectPage = "gear"
                            dragOffset = 0f
                        },
                        onDragCancel = { dragOffset = 0f }
                    )
                }) {
                    Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp).clip(RoundedCornerShape(18.dp)).background(androidx.compose.ui.graphics.Color(0xFFE4E9E1)).padding(4.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        listOf("gear" to "装备清单","nav" to "户外导航").forEach { (key,label) ->
                            val active=projectPage==key
                            Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if(active) forest else androidx.compose.ui.graphics.Color.Transparent).clickable { projectPage=key }.padding(vertical=12.dp),contentAlignment=Alignment.Center) {
                                Text(label,fontSize=14.sp,fontWeight=FontWeight.Bold,color=if(active) androidx.compose.ui.graphics.Color.White else muted)
                            }
                        }
                    }
                    AnimatedContent(
                        targetState = projectPage,
                        transitionSpec = {
                            val direction = if (targetState == "nav") 1 else -1
                            (slideInHorizontally(animationSpec = tween(280)) { width -> direction * width } +
                                fadeIn(animationSpec = tween(180))) togetherWith
                                (slideOutHorizontally(animationSpec = tween(280)) { width -> -direction * width / 3 } +
                                    fadeOut(animationSpec = tween(160)))
                        },
                        label = "gear_navigation_transition"
                    ) { page ->
                    if(page=="gear") {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(11.dp),contentPadding=PaddingValues(top=14.dp,bottom=90.dp)) {
                    item {
                        Card(shape=RoundedCornerShape(26.dp),colors=CardDefaults.cardColors(containerColor=forest),elevation=CardDefaults.cardElevation(defaultElevation=5.dp)) {
                            Row(Modifier.fillMaxWidth().padding(20.dp),verticalAlignment=Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("装备总价值",color=androidx.compose.ui.graphics.Color(0xFFD8E2D7),fontSize=12.sp)
                                    Text(yuan(p.gear.sumOf{it.price*it.quantity}),color=androidx.compose.ui.graphics.Color.White,fontSize=26.sp,fontWeight=FontWeight.ExtraBold)
                                    Spacer(Modifier.height(5.dp))
                                    Text("背包重量  ${String.format(Locale.CHINA,"%.2f",p.gear.sumOf{it.weightGrams*it.quantity}/1000.0)} kg",color=androidx.compose.ui.graphics.Color.White,fontSize=13.sp)
                                }
                                Column(horizontalAlignment=Alignment.End) {
                                    Text("${p.gear.size}",color=androidx.compose.ui.graphics.Color.White,fontSize=25.sp,fontWeight=FontWeight.Bold)
                                    Text("件装备",color=androidx.compose.ui.graphics.Color(0xFFD8E2D7),fontSize=11.sp)
                                    Text("已打包 ${p.gear.count{it.packed}}/${p.gear.size}",color=androidx.compose.ui.graphics.Color.White,fontSize=11.sp)
                                }
                            }
                        }
                    }
                    item {
                        OutlinedTextField(value=searchQuery,onValueChange={searchQuery=it},modifier=Modifier.fillMaxWidth(),singleLine=true,shape=RoundedCornerShape(16.dp),leadingIcon={Icon(Icons.Default.Image,null,tint=muted)},label={Text("搜索装备名称 / 备注")},colors=OutlinedTextFieldDefaults.colors(unfocusedBorderColor=androidx.compose.ui.graphics.Color(0xFFD9DED6),focusedBorderColor=forest,focusedContainerColor=androidx.compose.ui.graphics.Color.White,unfocusedContainerColor=androidx.compose.ui.graphics.Color(0xFFFFFEFA)))
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            FilterChip(selected=packedOnly,onClick={packedOnly=!packedOnly},label={Text("只看未打包")})
                            Spacer(Modifier.width(8.dp))
                            FilterChip(selected=sortByWeight,onClick={sortByWeight=!sortByWeight},label={Text(if(sortByWeight)"按重量排序" else "默认排序")})
                            Spacer(Modifier.weight(1f))
                            Text("${p.gear.count{it.packed}}/${p.gear.size} 已打包",fontSize=11.sp,color=muted)
                        }
                        LinearProgressIndicator(progress={if(p.gear.isEmpty())0f else p.gear.count{it.packed}.toFloat()/p.gear.size},modifier=Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)),color=forest,trackColor=androidx.compose.ui.graphics.Color(0xFFDDE4DA))
                        Text("分类重量统计",fontWeight=FontWeight.Bold,color=forest)
                        categories.forEach { c ->
                            val grams=categoryWeights[c] ?: 0.0
                            if(grams>0) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                                Text(c,fontSize=11.sp,color=muted); Text("${String.format(Locale.CHINA,"%.0f",grams)} g",fontSize=11.sp,color=forest)
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick={libraryTargetProjectId=p.id;libraryOpen=true},modifier=Modifier.weight(1f)) { Text("从装备库复用") }
                            OutlinedButton(onClick={advancedToolsOpen=true},modifier=Modifier.weight(1f)) { Text("智能分析") }
                            OutlinedButton(onClick={
                                val text=buildString {
                                    append("徒步清单：").append(p.name).append("\\n")
                                    append("目的地：").append(p.place).append("\\n")
                                    append("装备总价值：").append(yuan(p.gear.sumOf{it.price*it.quantity})).append("\\n")
                                    append("装备总重量：").append(String.format(Locale.CHINA,"%.2f kg",p.gear.sumOf{it.weightGrams*it.quantity}/1000.0)).append("\\n")
                                    p.gear.forEach { append(if(it.packed)"[已打包] " else "[未打包] ").append(it.name).append(" ×").append(it.quantity).append(" · ").append(it.weightGrams.toInt()).append("g\\n") }
                                }
                                val intent=Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text)
                                context.startActivity(Intent.createChooser(intent,"分享徒步装备清单"))
                            },modifier=Modifier.weight(1f)) { Text("分享清单") }
                        }
                    }
                    if(p.gear.isEmpty()) item { Empty("装备清单还是空的","点击右下角 + 添加第一件装备") }
                    categories.forEach { cat ->
                        val list=categoryGroups[cat].orEmpty()
                        if(list.isNotEmpty()) {
                            item { Row(Modifier.fillMaxWidth().padding(top=5.dp,bottom=1.dp),verticalAlignment=Alignment.CenterVertically) { Box(Modifier.width(4.dp).height(18.dp).clip(RoundedCornerShape(4.dp)).background(forest)); Spacer(Modifier.width(9.dp)); Text(cat,fontWeight=FontWeight.Bold,color=forest,fontSize=15.sp) } }
                            items(list,key={it.id}) { g ->
                                Card(Modifier.clip(RoundedCornerShape(20.dp)).fillMaxWidth().clickable { editing=g;chosenPhoto=g.photo;gearDialog=true },shape=RoundedCornerShape(20.dp),colors=CardDefaults.cardColors(containerColor=androidx.compose.ui.graphics.Color(0xFFFFFEFA)),elevation=CardDefaults.cardElevation(defaultElevation=2.dp)) {
                                    Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                                        val image by produceState<Bitmap?>(initialValue=null,key1=g.photo) {
                                            value=withContext(Dispatchers.IO){decodeGearPhoto(context,g.photo,360)}
                                        }
                                        val thumbnail=image
                                        if(thumbnail!=null) Image(thumbnail.asImageBitmap(),null,Modifier.size(68.dp).clip(RoundedCornerShape(12.dp)),contentScale=ContentScale.Crop)
                                        else Box(Modifier.size(68.dp).clip(RoundedCornerShape(16.dp)).background(androidx.compose.ui.graphics.Color(0xFFE5EBE2)),contentAlignment=Alignment.Center){Icon(Icons.Default.PhotoCamera,null,tint=forest)}
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) { Text(g.name,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis); Text("${g.category} · 数量 ${g.quantity}",fontSize=11.sp,color=muted); Text(yuan(g.price)+" / 件 · ${g.weightGrams.toInt()} g/件",fontSize=12.sp,color=forest,fontWeight=FontWeight.SemiBold); if(g.warrantyUntil.isNotBlank()) Text("保修至 "+g.warrantyUntil,fontSize=10.sp,color=muted); if(g.maintenanceLog.isNotBlank()) Text("维护记录："+g.maintenanceLog.take(42),fontSize=10.sp,color=muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                                            Row(verticalAlignment=Alignment.CenterVertically) {
                                                Checkbox(checked=g.packed,onCheckedChange={v->save(projects.map{pr->if(pr.id==p.id)pr.copy(gear=pr.gear.map{it2->if(it2.id==g.id)it2.copy(packed=v) else it2}) else pr})},modifier=Modifier.size(30.dp))
                                                Text(if(g.packed)"已打包" else "未打包",fontSize=11.sp,color=muted)
                                            }
                                        }
                                        IconButton(onClick={
                                            val remaining=projects.map{if(it.id==p.id)it.copy(gear=it.gear.filterNot{x->x.id==g.id}) else it}
                                            val removed=projects.firstOrNull{it.id==p.id}?.copy(gear=listOf(g))
                                            removed?.let{deleteUnreferencedProjectPhotos(context,it,remaining)}
                                            save(remaining)
                                        }) { Icon(Icons.Default.Delete,null,tint=androidx.compose.ui.graphics.Color(0xFFB36D5D)) }
                                    }
                                }
                            }
                        }
                    }
                }
                    } else {
                        LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(top=8.dp,bottom=28.dp)) {
                            item {
                                Card(shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=forest)) {
                                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                                        Text("FIELD NAVIGATION",fontSize=10.sp,fontWeight=FontWeight.Bold,color=androidx.compose.ui.graphics.Color(0xFFB9C9B5))
                                        Spacer(Modifier.height(8.dp))
                                        Text(p.name,fontSize=22.sp,fontWeight=FontWeight.ExtraBold,color=androidx.compose.ui.graphics.Color.White,maxLines=2,overflow=TextOverflow.Ellipsis,lineHeight=27.sp)
                                        Text(listOf(p.place,p.date).filter{it.isNotBlank()}.joinToString("  ·  ").ifBlank{"户外路线与环境信息"},fontSize=12.sp,color=androidx.compose.ui.graphics.Color(0xFFD8E2D7),maxLines=2,overflow=TextOverflow.Ellipsis,lineHeight=16.sp)
                                        Spacer(Modifier.height(12.dp))
                                        Text(if(p.trackData.isNotBlank()) "已导入徒步轨迹" else "尚未导入 GPX 轨迹",fontSize=12.sp,color=androidx.compose.ui.graphics.Color.White)
                                        Text(if(p.mapPath.isNotBlank()) "离线地图已就绪" else "可导入 MBTiles 离线地图",fontSize=12.sp,color=androidx.compose.ui.graphics.Color(0xFFD8E2D7))
                                    }
                                }
                            }
                            item {
                                Text("导航与地图",fontWeight=FontWeight.Bold,color=forest,fontSize=16.sp)
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                    Card(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).clickable { mapOpen=true },shape=RoundedCornerShape(18.dp),colors=CardDefaults.cardColors(containerColor=androidx.compose.ui.graphics.Color.White)) {
                                        Column(Modifier.fillMaxWidth().padding(16.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                                            Icon(Icons.Default.Landscape,null,tint=forest,modifier=Modifier.size(28.dp))
                                            Spacer(Modifier.height(8.dp));Text("地图 / 轨迹导航",fontWeight=FontWeight.Bold,fontSize=13.sp,color=forest)
                                            Text(if(p.trackData.isNotBlank()) "进入路线导航" else "打开离线地图",fontSize=10.sp,color=muted)
                                        }
                                    }
                                    Card(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).clickable { if(p.trackData.isNotBlank()) trackOpen=true },shape=RoundedCornerShape(18.dp),colors=CardDefaults.cardColors(containerColor=androidx.compose.ui.graphics.Color.White)) {
                                        Column(Modifier.fillMaxWidth().padding(16.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                                            Icon(Icons.Default.Image,null,tint=forest,modifier=Modifier.size(28.dp))
                                            Spacer(Modifier.height(8.dp));Text("轨迹预览",fontWeight=FontWeight.Bold,fontSize=13.sp,color=forest)
                                            Text(if(p.trackData.isNotBlank()) "查看路线概况" else "尚无轨迹",fontSize=10.sp,color=muted)
                                        }
                                    }
                                }
                            }
                            item {
                                Card(Modifier.clip(RoundedCornerShape(18.dp)).fillMaxWidth().clickable { arCameraOpen=true },shape=RoundedCornerShape(18.dp),colors=CardDefaults.cardColors(containerColor=androidx.compose.ui.graphics.Color(0xFF173B32))) {
                                    Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                                        Icon(Icons.Default.PhotoCamera,null,tint=androidx.compose.ui.graphics.Color.White,modifier=Modifier.size(30.dp))
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text("实景路线导航",fontWeight=FontWeight.Bold,fontSize=14.sp,color=androidx.compose.ui.graphics.Color.White)
                                            Text("摄像头画面叠加 GPX 轨迹 · 实时方向引导",fontSize=11.sp,color=androidx.compose.ui.graphics.Color(0xFFD1DDD3))
                                        }
                                        Text("开启  ›",fontSize=12.sp,color=androidx.compose.ui.graphics.Color.White,fontWeight=FontWeight.Bold)
                                    }
                                }
                            }
                            item {
                                Card(Modifier.clip(RoundedCornerShape(18.dp)).fillMaxWidth().clickable { terrain3dOpen=true },shape=RoundedCornerShape(18.dp),colors=CardDefaults.cardColors(containerColor=androidx.compose.ui.graphics.Color(0xFFE8EEE7))) {
                                    Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                                        Icon(Icons.Default.Landscape,null,tint=forest,modifier=Modifier.size(30.dp))
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text("3D 地形实验室",fontWeight=FontWeight.Bold,fontSize=14.sp,color=forest)
                                            Text("旋转山体 · 三维 GPX 轨迹 · 模型导出（开发中）",fontSize=11.sp,color=muted)
                                        }
                                        Text("进入  ›",fontSize=12.sp,color=forest,fontWeight=FontWeight.Bold)
                                    }
                                }
                            }
                            item {
                                OutlinedButton(onClick={mapTargetProjectId=p.id;mbtilesImporter.launch(arrayOf("*/*"))},modifier=Modifier.fillMaxWidth()) { Text(if(p.mapPath.isBlank()) "＋ 导入离线地图包" else "更换离线地图包") }
                            }
                            item {
                                Card(
                                    Modifier.clip(RoundedCornerShape(20.dp)).fillMaxWidth().clickable {
                                        gpxTargetProjectId=p.id
                                        gpxImporter.launch(arrayOf("*/*"))
                                    },
                                    shape=RoundedCornerShape(20.dp),
                                    colors=CardDefaults.cardColors(containerColor=androidx.compose.ui.graphics.Color.White),
                                    elevation=CardDefaults.cardElevation(defaultElevation=2.dp)
                                ) {
                                    Row(Modifier.fillMaxWidth().padding(18.dp),verticalAlignment=Alignment.CenterVertically) {
                                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(androidx.compose.ui.graphics.Color(0xFFE5EBE2)),contentAlignment=Alignment.Center) {
                                            Icon(Icons.Default.Landscape,null,tint=forest,modifier=Modifier.size(26.dp))
                                        }
                                        Spacer(Modifier.width(13.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(if(p.trackData.isBlank()) "导入 GPX 轨迹" else "更换 GPX 轨迹",fontWeight=FontWeight.Bold,color=forest,fontSize=15.sp)
                                            Text(if(p.trackData.isBlank()) "选择手机中的 .gpx 文件，导入本项目" else "已有关联轨迹，点击可重新导入或替换",fontSize=11.sp,color=muted)
                                        }
                                        Icon(Icons.Default.Add,null,tint=forest)
                                    }
                                }
                                Text("GPX 文件用于路线预览与户外导航；导入后会更新当前项目的轨迹数据。",fontSize=11.sp,color=muted,modifier=Modifier.padding(horizontal=4.dp))
                            }
                            item {
                                Text("天气与安全",fontWeight=FontWeight.Bold,color=forest,fontSize=16.sp)
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(onClick={weatherOpen=true},modifier=Modifier.weight(1f)) { Text("最高点 Windy 天气") }
                                    OutlinedButton(onClick={safetyOpen=true},modifier=Modifier.weight(1f)) { Text("安全中心") }
                                }
                            }
                            item {
                                Card(shape=RoundedCornerShape(18.dp),colors=CardDefaults.cardColors(containerColor=androidx.compose.ui.graphics.Color.White)) {
                                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                        Text("行程资料",fontWeight=FontWeight.Bold,color=forest)
                                        Spacer(Modifier.height(5.dp))
                                        Text("路线、住宿营地、补给点和紧急联系人",fontSize=11.sp,color=muted)
                                        Spacer(Modifier.height(10.dp))
                                        OutlinedButton(onClick={itineraryProjectId=p.id},modifier=Modifier.fillMaxWidth()) { Text("编辑行程与安全信息") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    }

    if (weatherOpen && project != null) TrailWeatherDialog(project) { weatherOpen=false }
    if (safetyOpen && project != null) TrailSafetyDialog(project) { safetyOpen=false }
    if (trackOpen && project != null) TrailTrackDialog(project.trackData) { trackOpen=false }
    if (project != null) OfflineMapDialog(project.mapPath, project.trackData, project.id, project.waypointData, project.elevationData, visible=mapOpen, onClose={ mapOpen=false; mapOpenedFromCamera=false }, fromCamera=mapOpenedFromCamera, onReturnToCamera={ mapOpen=false; mapOpenedFromCamera=false; arCameraOpen=true })
    val terrainTrackCoordinates = remember(project?.id, project?.trackData) {
        project?.let { decodeTrackPoints(it.trackData) } ?: emptyList()
    }
    val terrainElevations = remember(project?.id, project?.elevationData) {
        project?.let { decodeGpxElevations(it.elevationData) } ?: emptyList()
    }
    val terrainWaypoints = remember(project?.id, project?.waypointData) {
        project?.let { buildTerrainWaypointSnapshot(it.waypointData) } ?: emptyList()
    }
    if (project != null) Terrain3DDialog(
        projectId = project.id,
        projectName = project.name,
        trackCoordinates = terrainTrackCoordinates,
        elevations = terrainElevations,
        waypoints = terrainWaypoints,
        visible = terrain3dOpen
    ) { terrain3dOpen = false }
    if (project != null) TerrainArCameraDialog(project.id, project.name, project.trackData, project.elevationData, visible=arCameraOpen, onClose={ arCameraOpen=false }, onJumpToMap={ arCameraOpen=false; mapOpenedFromCamera=true; mapOpen=true })

    if (advancedToolsOpen && project != null) {
        TrailKitAdvancedDialog(
            project=project,
            onClose={advancedToolsOpen=false},
            onAddSuggested={ additions ->
                val latest=projects.firstOrNull { it.id==project.id }
                val fresh=additions.filterNot { a -> latest?.gear?.any { it.name.equals(a.name,true) }==true }
                    .map { it.copy(category=normalizeGearCategory(it.category)) }
                if(fresh.isNotEmpty()) save(projects.map { if(it.id==project.id) it.copy(gear=it.gear+fresh) else it })
                Toast.makeText(context,if(fresh.isEmpty()) "这些建议装备已在清单中" else "已加入 "+fresh.size+" 件建议装备，请返回装备清单查看",Toast.LENGTH_LONG).show()
            }
        )
    }

    if(projectDialog) ProjectDialog(
        close = {
            projectDialog = false
            if (pendingSharedGpx != null) {
                pendingSharedGpx = null
                onSharedGpxHandled()
            }
        },
        initialName = sharedGpxSuggestedName
    ) { name,place,date ->
        val p=Project(System.currentTimeMillis().toString(),name,place,date,emptyList())
        val sharedUri = pendingSharedGpx
        projectDialog = false
        if (sharedUri != null) {
            coroutineScope.launch {
                try {
                    val imported = withContext(Dispatchers.IO) {
                        val displayName = context.contentResolver.query(sharedUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        }.orEmpty()
                        if (displayName.isNotBlank() && !displayName.lowercase(Locale.ROOT).endsWith(".gpx")) error("分享的文件不是 GPX 格式")
                        readGpxTrack(context, sharedUri)
                    }
                    val importedProject = p.copy(
                        route = imported.summary,
                        trackData = imported.encodedPoints,
                        waypointData = imported.encodedWaypoints,
                        elevationData = imported.encodedElevations
                    )
                    save(projects + importedProject)
                    selected = importedProject.id
                    Toast.makeText(context, "项目创建成功，GPX 轨迹已自动导入", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    save(projects + p)
                    selected = p.id
                    Toast.makeText(context, "项目已创建，但 GPX 导入失败：" + (e.message ?: "文件读取失败") + "。可在项目中重新导入。", Toast.LENGTH_LONG).show()
                } finally {
                    pendingSharedGpx = null
                    onSharedGpxHandled()
                }
            }
        } else {
            save(projects+p)
            selected=p.id
        }
    }
    val managedProject = projects.firstOrNull { it.id == manageProjectId }
    if (manageProjectId != null && managedProject != null) {
        AlertDialog(
            onDismissRequest = { manageProjectId = null },
            title = { Text(managedProject.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("长按项目可管理项目资料", color = muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { editingProjectId = managedProject.id; editProjectField = "name"; editProjectValue = managedProject.name; manageProjectId = null }, modifier = Modifier.fillMaxWidth()) { Text("重命名项目", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                    TextButton(onClick = { editingProjectId = managedProject.id; editProjectField = "place"; editProjectValue = managedProject.place; manageProjectId = null }, modifier = Modifier.fillMaxWidth()) { Text("修改终点 / 目的地", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                    TextButton(onClick = { editingProjectId = managedProject.id; editProjectField = "date"; editProjectValue = managedProject.date; manageProjectId = null }, modifier = Modifier.fillMaxWidth()) { Text("修改时间", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                    TextButton(onClick = { deleteProjectId = managedProject.id; manageProjectId = null }, modifier = Modifier.fillMaxWidth()) { Text("删除项目", color = androidx.compose.ui.graphics.Color(0xFFB34D43), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                }
            },
            confirmButton = { TextButton(onClick = { manageProjectId = null }) { Text("完成") } }
        )
    }
    if (editProjectField != null) {
        val field = editProjectField!!
        AlertDialog(
            onDismissRequest = { editProjectField = null },
            title = { Text(when(field) { "name" -> "重命名项目"; "place" -> "修改终点 / 目的地"; else -> "修改出行时间" }) },
            text = {
                OutlinedTextField(
                    value = editProjectValue,
                    onValueChange = { editProjectValue = it },
                    label = { Text(when(field) { "name" -> "项目名称"; "place" -> "终点 / 目的地"; else -> "出行时间，例如 2026-11-20" }) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = editingProjectId
                    if (id != null && (field != "name" || editProjectValue.isNotBlank())) {
                        save(projects.map { p ->
                            if (p.id != id) p else when(field) {
                                "name" -> p.copy(name = editProjectValue.trim())
                                "place" -> p.copy(place = editProjectValue.trim())
                                else -> p.copy(date = editProjectValue.trim())
                            }
                        })
                    }
                    editProjectField = null
                    editingProjectId = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editProjectField = null; editingProjectId = null }) { Text("取消") } }
        )
    }
    if (deleteProjectId != null) {
        val target = projects.firstOrNull { it.id == deleteProjectId }
        AlertDialog(
            onDismissRequest = { deleteProjectId = null },
            title = { Text("删除徒步项目？") },
            text = { Text("确定删除「${target?.name ?: ""}」及其全部装备记录吗？此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    val id = deleteProjectId
                    if(id!=null) {
                        projects.firstOrNull { it.id == id }?.let { projectToDelete ->
                            deleteProjectNavigationData(context, projectToDelete)
                            projectToDelete.mapPath.takeIf { it.isNotBlank() }?.let { path -> runCatching { File(path).delete() } }
                            deleteUnreferencedProjectPhotos(context, projectToDelete, projects.filterNot { it.id == id })
                            deleteOfflineRegionsForProject(context, id)
                        }
                        save(projects.filterNot { it.id == id })
                        if (selected == id) selected = null
                    }
                    deleteProjectId = null
                }) { Text("确认删除", color = androidx.compose.ui.graphics.Color(0xFFB34D43)) }
            },
            dismissButton = { TextButton(onClick = { deleteProjectId = null }) { Text("取消") } }
        )
    }



    if (libraryOpen && libraryTargetProjectId != null) {
        val target=projects.firstOrNull{it.id==libraryTargetProjectId}
        val library=projects.flatMap{it.gear}.distinctBy{it.name+"|"+it.category}
        AlertDialog(
            onDismissRequest={libraryOpen=false;libraryTargetProjectId=null},
            title={Text("个人装备库")},
            text={
                if(library.isEmpty()) Text("还没有可复用的装备。先在任意徒步项目中添加装备。")
                else Column(Modifier.heightIn(max=480.dp)) {
                    Text("选择一件装备，复制到当前项目。",fontSize=12.sp,color=muted)
                    LazyColumn {
                        items(library,key={it.id}) { g ->
                            Row(Modifier.fillMaxWidth().clickable {
                                val t=target
                                if(t!=null) {
                                    val copy=g.copy(id=System.nanoTime().toString(),packed=false)
                                    save(projects.map{if(it.id==t.id)it.copy(gear=it.gear+copy) else it})
                                }
                                libraryOpen=false;libraryTargetProjectId=null
                            }.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(g.name,fontWeight=FontWeight.SemiBold)
                                    Text("${g.category} · ${g.weightGrams.toInt()} g · ${yuan(g.price)}",fontSize=11.sp,color=muted)
                                }
                                Icon(Icons.Default.Add,null,tint=forest)
                            }
                            HorizontalDivider(color=sand)
                        }
                    }
                }
            },
            confirmButton={TextButton(onClick={libraryOpen=false;libraryTargetProjectId=null}){Text("关闭")}}
        )
    }

    val itineraryProject = projects.firstOrNull { it.id == itineraryProjectId }
    if (itineraryProject != null) {
        var route by remember(itineraryProject.id) { mutableStateOf(itineraryProject.route) }
        var lodging by remember(itineraryProject.id) { mutableStateOf(itineraryProject.lodging) }
        var resupply by remember(itineraryProject.id) { mutableStateOf(itineraryProject.resupply) }
        var emergency by remember(itineraryProject.id) { mutableStateOf(itineraryProject.emergency) }
        AlertDialog(
            onDismissRequest={itineraryProjectId=null},
            title={Text("徒步行程与安全信息")},
            text={
                Column(Modifier.heightIn(max=520.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(route,{route=it},label={Text("路线 / 途经点")},modifier=Modifier.fillMaxWidth(),minLines=2)
                    OutlinedTextField(lodging,{lodging=it},label={Text("住宿 / 营地")},modifier=Modifier.fillMaxWidth(),minLines=2)
                    OutlinedTextField(resupply,{resupply=it},label={Text("补给点 / 水源")},modifier=Modifier.fillMaxWidth(),minLines=2)
                    OutlinedTextField(emergency,{emergency=it},label={Text("紧急联系人 / 电话")},modifier=Modifier.fillMaxWidth(),minLines=2)
                }
            },
            confirmButton={TextButton(onClick={
                save(projects.map{if(it.id==itineraryProject.id)it.copy(route=route,lodging=lodging,resupply=resupply,emergency=emergency) else it})
                itineraryProjectId=null
            }){Text("保存行程")}},
            dismissButton={TextButton(onClick={itineraryProjectId=null}){Text("取消")}}
        )
    }

    if(gearDialog && project!=null) GearDialog(editing,chosenPhoto,{picker.launch("image/*")},{takeGearPhoto()},{gearDialog=false}) { g ->
        val editingGear=editing
        val updated=if(editingGear==null) project.copy(gear=project.gear+g) else project.copy(gear=project.gear.map{if(it.id==g.id)g else it})
        val allUpdated=projects.map{if(it.id==project.id)updated else it}
        if(editingGear!=null && editingGear.photo.isNotBlank() && editingGear.photo!=g.photo) {
            deleteUnreferencedProjectPhotos(context,project.copy(gear=listOf(editingGear)),allUpdated)
        }
        save(allUpdated);gearDialog=false
    }
    if(githubPhotoDialog) {
        AlertDialog(
            onDismissRequest={ if(!githubUploading) { githubPhotoDialog=false; githubPublicAcknowledged=false } },
            title={ Text("上传照片到 GitHub") },
            text={
                Column(Modifier.heightIn(max=440.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("照片将保存到公开仓库：tubu / test/",fontSize=13.sp,color=forest,fontWeight=FontWeight.Bold)
                    Surface(color=androidx.compose.ui.graphics.Color(0xFFFFE8E4),shape=RoundedCornerShape(12.dp)) {
                        Column(Modifier.padding(11.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                            Text("隐私警告：此 GitHub 仓库是公开的。上传后，照片和文件名可能被任何人查看、下载和长期保存；删除仓库文件也不保证清除他人副本。",fontSize=12.sp,color=androidx.compose.ui.graphics.Color(0xFF8F3028),fontWeight=FontWeight.SemiBold)
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Checkbox(checked=githubPublicAcknowledged,onCheckedChange={githubPublicAcknowledged=it},enabled=!githubUploading)
                                Text("我已了解照片会公开，并确认仍要上传",fontSize=11.sp,color=androidx.compose.ui.graphics.Color(0xFF8F3028))
                            }
                        }
                    }
                    OutlinedTextField(
                        value=githubToken,onValueChange={githubToken=it.trim()},
                        label={Text("GitHub Token (需 Contents: write 权限)")},
                        placeholder={Text("粘贴 ghp_ 或 github_pat_ Token")},
                        modifier=Modifier.fillMaxWidth(),singleLine=true,
                        visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation()
                    )
                    OutlinedButton(onClick={githubPhotoPicker.launch(arrayOf("image/*"))},modifier=Modifier.fillMaxWidth(),enabled=!githubUploading) {
                        Icon(Icons.Default.Image,null); Spacer(Modifier.width(8.dp)); Text(if(githubPhotoUris.isEmpty()) "从手机相册选择照片" else "重新选择照片 (${githubPhotoUris.size})")
                    }
                    if(githubPhotoUris.isNotEmpty()) Text("已选 ${githubPhotoUris.size} 张，逐张上传到 test/ 文件夹",fontSize=12.sp,color=muted)
                    if(githubUploadStatus.isNotBlank()) Text(githubUploadStatus,fontSize=12.sp,color=forest)
                    Text("Token 只在当前运行期间使用，不保存到本地。建议使用仅限此仓库的细粒度 Token，并授予 Contents 写入权限。",fontSize=11.sp,color=muted)
                }
            },
            confirmButton={
                TextButton(enabled=!githubUploading && githubToken.isNotBlank() && githubPhotoUris.isNotEmpty() && githubPublicAcknowledged,onClick={
                    githubUploading=true
                    githubUploadStatus="上传准备中…"
                    coroutineScope.launch {
                        var success=0
                        var failed=""
                        for(uri in githubPhotoUris) {
                            try {
                                val uploaded=uploadTrailPhotoToGithub(context,uri,githubToken)
                                success++
                                githubUploadStatus="上传中：${success}/${githubPhotoUris.size} · ${uploaded}"
                            } catch(e:Exception) { failed=e.message ?: "未知错误"; break }
                        }
                        githubUploading=false
                        githubUploadStatus=if(failed.isBlank()) "完成！已上传 ${success} 张照片到 test/" else "已成功上传 ${success} 张；失败：${failed}"
                        Toast.makeText(context,githubUploadStatus,Toast.LENGTH_LONG).show()
                    }
                }) { Text(if(githubUploading) "正在上传…" else "开始上传") }
            },
            dismissButton={TextButton(onClick={if(!githubUploading){githubPhotoDialog=false;githubPublicAcknowledged=false}}){Text("关闭")}}
        )
    }

}

@Composable private fun Empty(title:String,sub:String) {
    Column(Modifier.fillMaxWidth().padding(vertical=34.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Icon(Icons.Default.Landscape,null,tint=forest,modifier=Modifier.size(38.dp)); Spacer(Modifier.height(10.dp))
        Text(title,fontWeight=FontWeight.Bold,color=forest); Text(sub,fontSize=12.sp,color=muted)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ProjectDialog(close:()->Unit,initialName:String="",save:(String,String,String)->Unit) {
    var name by remember(initialName){mutableStateOf(initialName)};var place by remember{mutableStateOf("")};var date by remember{mutableStateOf("")}
    AlertDialog(onDismissRequest=close,title={Text("创建徒步项目")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name,{name=it},label={Text("项目名称 *")},singleLine=true)
            OutlinedTextField(place,{place=it},label={Text("目的地")},singleLine=true)
            OutlinedTextField(date,{date=it},label={Text("出行时间")},singleLine=true)
        }
    },confirmButton={TextButton(onClick={if(name.isNotBlank())save(name.trim(),place.trim(),date.trim())}){Text("创建")}},dismissButton={TextButton(onClick=close){Text("取消")}})
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun GearDialog(initial:Gear?,photo0:String,pick:()->Unit,takePhoto:()->Unit,close:()->Unit,save:(Gear)->Unit) {
    var name by remember{mutableStateOf(initial?.name?:"")};var cat by remember{mutableStateOf(initial?.category?:categories[0])}
    var price by remember{mutableStateOf(initial?.price?.toString()?:"")};var qty by remember{mutableStateOf(initial?.quantity?.toString()?: "1")}
    var note by remember{mutableStateOf(initial?.note?:"")};var weight by remember{mutableStateOf(initial?.weightGrams?.toString()?:"")};var warranty by remember{mutableStateOf(initial?.warrantyUntil?:"")};var maintenance by remember{mutableStateOf(initial?.maintenanceLog?:"")};var photo by remember(photo0){mutableStateOf(photo0)}
    AlertDialog(onDismissRequest=close,title={Text(if(initial==null)"添加装备" else "编辑装备")},text={
        Column(Modifier.heightIn(max=520.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
            Box(Modifier.fillMaxWidth().height(145.dp).clip(RoundedCornerShape(14.dp)).background(androidx.compose.ui.graphics.Color(0xFFE9EEE8)),contentAlignment=Alignment.Center) {
                val ctx=LocalContext.current
                val bitmap by produceState<Bitmap?>(initialValue=null,key1=photo) {
                    value=withContext(Dispatchers.IO){decodeGearPhoto(ctx,photo,800)}
                }
                val previewBitmap=bitmap
                if(previewBitmap!=null) Image(previewBitmap.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
                else Column(horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Default.PhotoCamera,null,tint=forest);Text("还没有装备照片",fontSize=12.sp,color=forest)}
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick=takePhoto, modifier=Modifier.weight(1f)) { Icon(Icons.Default.PhotoCamera, null); Spacer(Modifier.width(6.dp)); Text("实时拍照") }
                OutlinedButton(onClick=pick, modifier=Modifier.weight(1f)) { Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp)); Text("从相册选择") }
            }
            OutlinedTextField(name,{name=it},label={Text("装备名称 *")},singleLine=true,modifier=Modifier.fillMaxWidth())
            var expanded by remember{mutableStateOf(false)}
            ExposedDropdownMenuBox(expanded=expanded,onExpandedChange={expanded=it}) {
                OutlinedTextField(cat,{},Modifier.menuAnchor().fillMaxWidth(),readOnly=true,label={Text("装备分类")},trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded)})
                ExposedDropdownMenu(expanded,{expanded=false}) { categories.forEach { DropdownMenuItem(text={Text(it)},onClick={cat=it;expanded=false}) } }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(price,{price=it},Modifier.weight(1f),label={Text("单价（元）")},singleLine=true)
                OutlinedTextField(qty,{qty=it.filter{c->c.isDigit()}},Modifier.weight(1f),label={Text("数量")},singleLine=true)
            }
            OutlinedTextField(weight,{weight=it.filter{c->c.isDigit()||c=='.'}},label={Text("单件重量（克）")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            OutlinedTextField(note,{note=it},label={Text("备注")},modifier=Modifier.fillMaxWidth(),maxLines=2)
            OutlinedTextField(warranty,{warranty=it},label={Text("保修截止日期（如 2027-06-30）")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            OutlinedTextField(maintenance,{maintenance=it},label={Text("维修 / 保养记录")},modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=3)
        }
    },confirmButton={TextButton(onClick={if(name.isNotBlank())save(Gear(initial?.id?:System.nanoTime().toString(),name.trim(),cat,price.toDoubleOrNull()?:0.0,(qty.toIntOrNull()?:1).coerceAtLeast(1),note.trim(),photo,weight.toDoubleOrNull()?:0.0,initial?.packed?:false,warranty.trim(),maintenance.trim()))}){Text("保存")}},dismissButton={TextButton(onClick=close){Text("取消")}})
}

// Small LRU cache for decoded gear thumbnails/previews. The source path and target
// dimension form the key, preventing repeated disk reads and bitmap decoding on re-entry.
private val gearPhotoBitmapCache = object : LruCache<String, Bitmap>(12 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = (value.allocationByteCount / 1024).coerceAtLeast(1)
}

private fun decodeGearPhoto(context:Context,value:String,maxDimension:Int):Bitmap? {
    if(value.isBlank()) return null
    val cacheKey = "$maxDimension|$value"
    gearPhotoBitmapCache.get(cacheKey)?.let { return it }
    fun open() : java.io.InputStream? {
        val uri=runCatching{Uri.parse(value)}.getOrNull() ?: return null
        return try {
            if(uri.scheme=="content" || uri.scheme=="file") context.contentResolver.openInputStream(uri)
            else File(value).takeIf{it.isFile}?.inputStream()
        } catch(_:Exception){null}
    }
    return try {
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        open()?.use{BitmapFactory.decodeStream(it,null,bounds)}
        if(bounds.outWidth<=0 || bounds.outHeight<=0) return null
        val target=maxDimension.coerceAtLeast(128)
        var sample=1
        while(bounds.outWidth/sample>target*2 || bounds.outHeight/sample>target*2) sample*=2
        val options=BitmapFactory.Options().apply{
            inJustDecodeBounds=false
            inSampleSize=sample
            inPreferredConfig=Bitmap.Config.RGB_565
        }
        open()?.use{BitmapFactory.decodeStream(it,null,options)}?.also { decoded ->
            gearPhotoBitmapCache.put(cacheKey, decoded)
        }
    } catch(_:Exception){null}
}

private fun makePoster(context:Context,project:Project) {
    var bitmap:Bitmap?=null
    try {
        val width=1440
        val names=listOf("背负系统","帐篷与营地系统","睡眠系统","衣物系统","鞋靴系统","炊事与饮水系统","行进系统","电子与导航系统","急救与卫生系统")
        val colors=listOf(0xFF3989E8.toInt(),0xFF58A85E.toInt(),0xFF8B5BC7.toInt(),0xFF78A63C.toInt(),0xFFEE8B32.toInt(),0xFF149CB4.toInt(),0xFF32A66A.toInt(),0xFF3979D8.toInt(),0xFFE65353.toInt(),0xFF8995A5.toInt())
        fun cat(s:String)=normalizeGearCategory(s)
        val groups=names.mapNotNull{n->project.gear.filter{cat(it.category)==n}.takeIf{it.isNotEmpty()}?.let{n to it}}
        val totalWeight=project.gear.sumOf{it.weightGrams*it.quantity}
        val totalPrice=project.gear.sumOf{it.price*it.quantity}
        val totalCount=project.gear.sumOf{it.quantity}
        val unknown=project.gear.count{it.weightGrams<=0.0}
        val grid=groups.chunked(3)
        val heights=grid.map{maxOf(262f,it.maxOf{g->g.second.size}*52f+210f)}
        val bottom=405f+heights.sum()+grid.size*24f
        val height=(bottom+440f).toInt()
        bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap!!)
        val p=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val navy=0xFF102D57.toInt();val blue=0xFF2877D5.toInt();val green=0xFF21834D.toInt()
        val ink=0xFF182B4C.toInt();val muted=0xFF71809A.toInt();val white=0xFFFFFFFF.toInt()
        fun box(r:RectF,color:Int,rad:Float=22f,shadow:Boolean=false){p.shader=null;p.style=Paint.Style.FILL;p.color=color;if(shadow)p.setShadowLayer(8f,0f,4f,0x220C315F);canvas.drawRoundRect(r,rad,rad,p);p.clearShadowLayer()}
        fun txt(s:String,x:Float,y:Float,size:Float,color:Int,bold:Boolean=false,align:Paint.Align=Paint.Align.LEFT){p.shader=null;p.style=Paint.Style.FILL;p.color=color;p.textSize=size;p.textAlign=align;p.typeface=Typeface.create("sans-serif",if(bold)Typeface.BOLD else Typeface.NORMAL);canvas.drawText(s,x,y,p);p.textAlign=Paint.Align.LEFT}
        fun fit(s:String,x:Float,y:Float,max:Float,size:Float,color:Int){var z=size;p.typeface=Typeface.DEFAULT_BOLD;while(z>17f){p.textSize=z;if(p.measureText(s)<=max)break;z-=2f};txt(s,x,y,z,color,true)}
        fun money(v:Double)="¥"+String.format(Locale.CHINA,"%,.2f",v)

        fun systemIcon(cx:Float,cy:Float,index:Int,color:Int) {
            val saved=canvas.save()
            box(RectF(cx-34f,cy-34f,cx+34f,cy+34f),Color.argb(28,Color.red(color),Color.green(color),Color.blue(color)),22f)
            p.color=color;p.style=Paint.Style.STROKE;p.strokeWidth=5f;p.strokeCap=Paint.Cap.ROUND;p.strokeJoin=Paint.Join.ROUND
            when(index) {
                0 -> { canvas.drawRoundRect(RectF(cx-19f,cy-20f,cx+19f,cy+25f),9f,9f,p);canvas.drawArc(RectF(cx-12f,cy-30f,cx+12f,cy-8f),180f,180f,false,p);canvas.drawLine(cx-25f,cy-9f,cx-19f,cy+16f,p);canvas.drawLine(cx+25f,cy-9f,cx+19f,cy+16f,p);canvas.drawLine(cx-8f,cy-5f,cx+8f,cy-5f,p) }
                1 -> { val q=Path().apply{moveTo(cx-28f,cy+24f);lineTo(cx,cy-25f);lineTo(cx+29f,cy+24f);close()};canvas.drawPath(q,p);canvas.drawLine(cx,cy-25f,cx,cy+24f,p);canvas.drawLine(cx,cy+24f,cx+14f,cy+24f,p) }
                2 -> { canvas.drawRoundRect(RectF(cx-18f,cy-29f,cx+18f,cy+29f),17f,17f,p);canvas.drawLine(cx-18f,cy-9f,cx+18f,cy-9f,p);canvas.drawLine(cx-18f,cy+8f,cx+18f,cy+8f,p) }
                3 -> { val q=Path().apply{moveTo(cx-12f,cy-27f);lineTo(cx-29f,cy-12f);lineTo(cx-20f,cy+1f);lineTo(cx-14f,cy-4f);lineTo(cx-14f,cy+27f);lineTo(cx+14f,cy+27f);lineTo(cx+14f,cy-4f);lineTo(cx+20f,cy+1f);lineTo(cx+29f,cy-12f);lineTo(cx+12f,cy-27f);lineTo(cx+5f,cy-18f);lineTo(cx-5f,cy-18f);close()};canvas.drawPath(q,p);canvas.drawLine(cx,cy-17f,cx,cy+25f,p) }
                4 -> { val q=Path().apply{moveTo(cx-23f,cy-22f);lineTo(cx-8f,cy-22f);lineTo(cx-4f,cy+4f);lineTo(cx+24f,cy+12f);lineTo(cx+27f,cy+25f);lineTo(cx-25f,cy+25f);close()};canvas.drawPath(q,p);canvas.drawLine(cx-8f,cy-10f,cx+6f,cy-10f,p) }
                5 -> { canvas.drawRoundRect(RectF(cx-23f,cy-10f,cx+23f,cy+22f),6f,6f,p);canvas.drawLine(cx-29f,cy-12f,cx+29f,cy-12f,p);canvas.drawLine(cx-13f,cy-20f,cx+13f,cy-20f,p);canvas.drawLine(cx,cy-29f,cx,cy-20f,p);canvas.drawLine(cx+23f,cy-2f,cx+31f,cy-2f,p) }
                6 -> { canvas.drawCircle(cx,cy-22f,8f,p);canvas.drawLine(cx,cy-13f,cx-7f,cy+7f,p);canvas.drawLine(cx-4f,cy-4f,cx+15f,cy+3f,p);canvas.drawLine(cx-7f,cy+7f,cx-20f,cy+25f,p);canvas.drawLine(cx-7f,cy+7f,cx+12f,cy+25f,p);canvas.drawLine(cx+13f,cy+3f,cx+26f,cy-22f,p) }
                7 -> { canvas.drawRoundRect(RectF(cx-17f,cy-18f,cx+17f,cy+19f),8f,8f,p);canvas.drawRoundRect(RectF(cx-8f,cy-31f,cx+8f,cy-17f),3f,3f,p);canvas.drawRoundRect(RectF(cx-8f,cy+18f,cx+8f,cy+31f),3f,3f,p);canvas.drawCircle(cx,cy,7f,p) }
                else -> { canvas.drawRoundRect(RectF(cx-25f,cy-25f,cx+25f,cy+25f),9f,9f,p);p.style=Paint.Style.FILL;canvas.drawRect(cx-5f,cy-17f,cx+5f,cy+17f,p);canvas.drawRect(cx-17f,cy-5f,cx+17f,cy+5f,p) }
            }
            p.style=Paint.Style.FILL
            canvas.restoreToCount(saved)
        }
        canvas.drawColor(0xFFF0F5FC.toInt())
        p.shader=LinearGradient(0f,0f,width.toFloat(),230f,intArrayOf(0xFFE5F1FF.toInt(),0xFF83B5E7.toInt(),0xFF244C79.toInt()),null,Shader.TileMode.CLAMP);canvas.drawRect(0f,0f,width.toFloat(),230f,p);p.shader=null
        listOf(floatArrayOf(400f,225f,570f,42f,740f,225f),floatArrayOf(620f,225f,890f,8f,1110f,225f),floatArrayOf(920f,225f,1190f,42f,1435f,225f)).forEachIndexed{i,a->val path=Path().apply{moveTo(a[0],a[1]);lineTo(a[2],a[3]);lineTo(a[4],a[5]);close()};p.color=if(i%2==0)0xFF416D9B.toInt() else 0xFF315A86.toInt();canvas.drawPath(path,p)}
        txt("▲  TRAILKIT",58f,62f,27f,blue,true);fit(project.name+" 装备清单",58f,132f,950f,64f,navy)
        txt(listOf(project.place,project.date).filter{it.isNotBlank()}.joinToString("  ·  ").ifBlank{"专业装备 · 精准轻量 · 安全出行"},62f,180f,23f,0xFF355477.toInt())
        box(RectF(22f,205f,1418f,365f),white,28f,true)
        val sx=floatArrayOf(205f,560f,925f,1250f)
        txt(totalCount.toString(),sx[0],280f,62f,navy,true,Paint.Align.CENTER);txt("件装备",sx[0],326f,22f,muted,false,Paint.Align.CENTER)
        txt(if(totalWeight>0)String.format(Locale.CHINA,"%.2f kg",totalWeight/1000.0) else "待完善",sx[1],280f,48f,blue,true,Paint.Align.CENTER);txt(if(unknown>0)"已录重量 · "+unknown+" 件缺重" else "装备总重量（含数量）",sx[1],326f,19f,muted,false,Paint.Align.CENTER)
        txt(money(totalPrice),sx[2],280f,45f,green,true,Paint.Align.CENTER);txt("总支出",sx[2],326f,22f,muted,false,Paint.Align.CENTER)
        txt(groups.size.toString(),sx[3],280f,62f,navy,true,Paint.Align.CENTER);txt("个系统",sx[3],326f,22f,muted,false,Paint.Align.CENTER)
        listOf(382f,742f,1102f).forEach{p.color=0xFFD9E3F0.toInt();p.strokeWidth=2f;canvas.drawLine(it,235f,it,338f,p)}
        var y=405f
        grid.forEachIndexed{ri,row->
            val h=heights[ri]
            row.forEachIndexed{ci,pair->
                val idx=names.indexOf(pair.first).coerceAtLeast(0);val x=22f+ci*474f
                box(RectF(x,y,x+454f,y+h),white,24f,true);box(RectF(x,y,x+454f,y+9f),colors[idx],6f)
                systemIcon(x+57f,y+52f,idx,colors[idx]);txt(pair.first,x+105f,y+48f,24f,colors[idx],true);txt(money(pair.second.sumOf{it.price*it.quantity}),x+430f,y+91f,27f,ink,true,Paint.Align.RIGHT)
                txt("装备名称",x+22f,y+130f,17f,muted,true);txt("重量",x+326f,y+130f,17f,muted,true,Paint.Align.RIGHT);txt("价格",x+430f,y+130f,17f,muted,true,Paint.Align.RIGHT)
                var iy=y+168f
                pair.second.forEach{g->
                    fit(if(g.quantity>1)g.name+" ×"+g.quantity else g.name,x+22f,iy,245f,19f,ink)
                    txt(if(g.weightGrams>0)String.format(Locale.CHINA,"%.2f kg",g.weightGrams*g.quantity/1000.0) else "—",x+326f,iy,17f,ink,false,Paint.Align.RIGHT)
                    txt(money(g.price*g.quantity),x+430f,iy,17f,ink,false,Paint.Align.RIGHT)
                    iy+=52f;p.color=0xFFE9EEF5.toInt();p.strokeWidth=1f;canvas.drawLine(x+18f,iy-24f,x+436f,iy-24f,p)
                }
                box(RectF(x+12f,y+h-52f,x+442f,y+h-10f),0xFFF0F5FC.toInt(),12f)
                txt("小计",x+24f,y+h-24f,17f,navy,true);val w=pair.second.sumOf{it.weightGrams*it.quantity}
                txt(if(w>0)String.format(Locale.CHINA,"%.2f kg",w/1000.0) else "—",x+320f,y+h-24f,17f,navy,true,Paint.Align.RIGHT)
                txt(money(pair.second.sumOf{it.price*it.quantity}),x+430f,y+h-24f,17f,navy,true,Paint.Align.RIGHT)
            };y+=h+24f
        }
        val top=y+5f
        box(RectF(22f,top,970f,top+300f),white,24f,true);txt("各系统重量占比",55f,top+48f,29f,navy,true)
        val weighted=groups.map{it.first to it.second.sumOf{g->g.weightGrams*g.quantity}}.filter{it.second>0}
        if(weighted.isNotEmpty()&&totalWeight>0){var angle=-90f;weighted.forEach{item->val sweep=(item.second/totalWeight*360).toFloat();p.color=colors[names.indexOf(item.first).coerceAtLeast(0)];canvas.drawArc(RectF(58f,top+85f,270f,top+275f),angle,sweep,true,p);angle+=sweep}}else{p.color=0xFFE2EAF4.toInt();canvas.drawCircle(164f,top+180f,94f,p)}
        weighted.take(9).forEachIndexed{i,item->val xx=320f+(i/5)*310f;val yy=top+100f+(i%5)*39f;p.color=colors[names.indexOf(item.first).coerceAtLeast(0)];canvas.drawCircle(xx,yy-7f,8f,p);txt(item.first,xx+20f,yy,17f,ink);txt(String.format(Locale.CHINA,"%.1f%%",item.second/totalWeight*100),xx+275f,yy,17f,navy,true,Paint.Align.RIGHT)}
        box(RectF(995f,top,1418f,top+300f),navy,24f,true);txt("装备清单总结",1206f,top+62f,28f,white,true,Paint.Align.CENTER)
        txt(if(totalWeight>0)String.format(Locale.CHINA,"约 %.2f kg",totalWeight/1000.0) else "重量待完善",1206f,top+142f,39f,white,true,Paint.Align.CENTER)
        txt("总支出："+money(totalPrice),1206f,top+205f,22f,0xFFD6E8FF.toInt(),true,Paint.Align.CENTER)
        txt(totalCount.toString()+" 件装备 · "+groups.size+" 个系统",1206f,top+250f,18f,0xFFD6E8FF.toInt(),false,Paint.Align.CENTER)
        txt("注：重量按单件重量 × 数量计算；缺少重量的装备不计入总重。食品、水、燃料等消耗品需另行录入。",42f,top+342f,16f,muted)
        txt("TRAILKIT  ·  PACK SMART. WALK FAR.",1398f,top+382f,17f,navy,true,Paint.Align.RIGHT)
        val filename="TrailKit_装备清单_"+project.name+"_"+System.currentTimeMillis()+".jpg"
        val values=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,filename);put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");if(Build.VERSION.SDK_INT>=29)put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/TrailKit")}
        val uri=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)?:error("无法创建图片")
        val ok=context.contentResolver.openOutputStream(uri)?.use{bitmap!!.compress(Bitmap.CompressFormat.JPEG,95,it)}?:false
        if(!ok)error("图片保存失败")
        Toast.makeText(context,"装备清单信息图已保存到相册 Pictures/TrailKit",Toast.LENGTH_LONG).show()
    }catch(e:Exception){Toast.makeText(context,"生成失败："+(e.message?:"未知错误"),Toast.LENGTH_LONG).show()}finally{bitmap?.recycle()}
}