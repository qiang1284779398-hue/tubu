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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private val categories = listOf("背负系统","露营睡眠","服装系统","鞋袜防护","炊具饮食","电子导航","照明工具","其他")
private val forest = androidx.compose.ui.graphics.Color(0xFF183C32)
private val sand = androidx.compose.ui.graphics.Color(0xFFF3F0E7)
private val muted = androidx.compose.ui.graphics.Color(0xFF78827A)

data class Gear(val id:String,val name:String,val category:String,val price:Double,val quantity:Int,val note:String,val photo:String,val weightGrams:Double=0.0,val packed:Boolean=false,val warrantyUntil:String="",val maintenanceLog:String="")
data class Project(val id:String,val name:String,val place:String,val date:String,val gear:List<Gear>,val route:String="",val lodging:String="",val resupply:String="",val emergency:String="",val trackData:String="",val mapPath:String="",val waypointData:String="[]",val elevationData:String="[]")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val mode = window.windowManager.defaultDisplay.supportedModes.maxByOrNull { it.refreshRate }
                if (mode != null && mode.refreshRate >= 90f) window.attributes = window.attributes.apply { preferredDisplayModeId = mode.modeId }
            } catch (_: Exception) { }
        }
        setContent { TrailKitApp() }
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
fun TrailKitApp() {
    val context= LocalContext.current
    var projects by remember { mutableStateOf(loadWithBundledYubeng(context)) }
    var selected by remember { mutableStateOf<String?>(null) }
    var projectPage by remember(selected) { mutableStateOf("gear") }
    var projectDialog by remember { mutableStateOf(false) }
    var gearDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Gear?>(null) }
    var chosenPhoto by remember { mutableStateOf("") }
    var cameraPhotoUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) cameraPhotoUri?.let { chosenPhoto = it.toString() }
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
    var mapTargetProjectId by remember { mutableStateOf<String?>(null) }
    val gpxImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }.orEmpty()
            if (!displayName.lowercase(Locale.ROOT).endsWith(".gpx")) {
                error("请选择 .gpx 格式的轨迹文件")
            }
            val track = readGpxTrack(context, uri)
            val summary = track.summary
            val target = gpxTargetProjectId
            if (target != null) {
                val updated = projects.map { item ->
                    if (item.id == target) item.copy(route = listOf(item.route, summary).filter { it.isNotBlank() }.joinToString("\n"), trackData=track.encodedPoints,waypointData=track.encodedWaypoints,elevationData=track.encodedElevations) else item
                }
                persist(context, updated)
                projects = updated
                Toast.makeText(context, "GPX 轨迹分析完成，已保存到行程信息", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "GPX 导入失败：" + (e.message ?: "文件格式不正确"), Toast.LENGTH_LONG).show()
        }
    }
    val mbtilesImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val target = mapTargetProjectId
            if (target != null) {
                val dir = File(context.filesDir, "offline_maps")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "map_${target}.mbtiles")
                context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output -> input.copyTo(output) } } ?: error("无法读取地图文件")
                android.database.sqlite.SQLiteDatabase.openDatabase(file.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='tiles'", null).use { c -> if (!c.moveToFirst()) error("不是有效的 MBTiles 文件") }
                    db.rawQuery("SELECT value FROM metadata WHERE name='format'", null).use { c -> if (c.moveToFirst() && c.getString(0).lowercase() in listOf("pbf","mvt")) error("暂不支持矢量 PBF，请导入 PNG/JPG/WebP 栅格 MBTiles") }
                }
                val updated = projects.map { if (it.id == target) it.copy(mapPath = file.absolutePath) else it }
                persist(context, updated); projects = updated
                Toast.makeText(context, "离线地图导入成功", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "地图导入失败：" + (e.message ?: "文件格式不正确"), Toast.LENGTH_LONG).show()
        } finally { mapTargetProjectId = null }
    }
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) try {
            val json=context.getSharedPreferences("trailkit",0).getString("projects","[]") ?: "[]"
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            Toast.makeText(context,"备份文件已导出",Toast.LENGTH_LONG).show()
        } catch(e:Exception) { Toast.makeText(context,"导出失败：${e.message}",Toast.LENGTH_LONG).show() }
    }
    val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val json=context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("无法读取文件")
            val parsed=JSONArray(json)
            context.getSharedPreferences("trailkit",0).edit().putString("projects",parsed.toString()).apply()
            projects=load(context); selected=null
            Toast.makeText(context,"备份恢复成功",Toast.LENGTH_LONG).show()
        } catch(e:Exception) { Toast.makeText(context,"恢复失败：文件格式不正确",Toast.LENGTH_LONG).show() }
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if(uri!=null) chosenPhoto=uri.toString() }
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
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(26.dp)
        )
    ) {
        Scaffold(containerColor=sand, topBar={
            TopAppBar(
                title={ Column { Text(project?.name ?: "徒步装备管家",fontWeight=FontWeight.Bold); Text(if(project==null) "把每一次出发，准备得漂亮一点" else (if(projectPage=="gear") "装备清单" else "户外导航")+" · "+project.place,fontSize=11.sp,color=muted) } },
                navigationIcon={ if(project!=null) IconButton(onClick={selected=null}) { Icon(Icons.Default.ArrowBack,"返回") } },
                actions={
                    if(project!=null) {
                        if(projectPage=="gear") IconButton(onClick={makePoster(context,project)}) { Icon(Icons.Default.Image,"生成海报") }
                        if(projectPage=="nav") IconButton(onClick={itineraryProjectId=project.id}) { Icon(Icons.Default.Landscape,"行程信息") }
                    } else {
                        IconButton(onClick={exportBackup.launch("TrailKit-备份.json")}) { Icon(Icons.Default.Image,"导出备份") }
                        IconButton(onClick={importBackup.launch(arrayOf("application/json","text/*"))}) { Icon(Icons.Default.Add,"恢复备份") }
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
                    if(projects.isEmpty()) item { Empty("还没有徒步项目","点击右下角 + 创建你的第一个计划") }
                    items(projects,key={it.id}) { p ->
                        Card(Modifier.fillMaxWidth().combinedClickable(onClick={ selected=p.id },onLongClick={ manageProjectId=p.id }),shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=androidx.compose.ui.graphics.Color(0xFFFFFEFA)),elevation=CardDefaults.cardElevation(defaultElevation=3.dp)) {
                            Column(Modifier.padding(18.dp)) {
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(androidx.compose.ui.graphics.Color(0xFFE5EBE2)),contentAlignment=Alignment.Center) { Icon(Icons.Default.Landscape,null,tint=forest,modifier=Modifier.size(27.dp)) }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) { Text(p.name,fontSize=17.sp,fontWeight=FontWeight.Bold); Text(listOf(p.place,p.date).filter{it.isNotBlank()}.joinToString(" · "),fontSize=12.sp,color=muted) }
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
                Column(Modifier.fillMaxSize().padding(padding)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp).clip(RoundedCornerShape(18.dp)).background(androidx.compose.ui.graphics.Color(0xFFE4E9E1)).padding(4.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        listOf("gear" to "装备清单","nav" to "户外导航").forEach { (key,label) ->
                            val active=projectPage==key
                            Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if(active) forest else androidx.compose.ui.graphics.Color.Transparent).clickable { projectPage=key }.padding(vertical=12.dp),contentAlignment=Alignment.Center) {
                                Text(label,fontSize=14.sp,fontWeight=FontWeight.Bold,color=if(active) androidx.compose.ui.graphics.Color.White else muted)
                            }
                        }
                    }
                    if(projectPage=="gear") {
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
                            val grams=p.gear.filter{it.category==c}.sumOf{it.weightGrams*it.quantity}
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
                        val filtered=p.gear.filter{it.category==cat && (it.name.contains(searchQuery,true)||it.note.contains(searchQuery,true)) && (!packedOnly || !it.packed)}
                        val list=if(sortByWeight) filtered.sortedByDescending{it.weightGrams*it.quantity} else filtered
                        if(list.isNotEmpty()) {
                            item { Row(Modifier.fillMaxWidth().padding(top=5.dp,bottom=1.dp),verticalAlignment=Alignment.CenterVertically) { Box(Modifier.width(4.dp).height(18.dp).clip(RoundedCornerShape(4.dp)).background(forest)); Spacer(Modifier.width(9.dp)); Text(cat,fontWeight=FontWeight.Bold,color=forest,fontSize=15.sp) } }
                            items(list,key={it.id}) { g ->
                                Card(Modifier.fillMaxWidth().clickable { editing=g;chosenPhoto=g.photo;gearDialog=true },shape=RoundedCornerShape(20.dp),colors=CardDefaults.cardColors(containerColor=androidx.compose.ui.graphics.Color(0xFFFFFEFA)),elevation=CardDefaults.cardElevation(defaultElevation=2.dp)) {
                                    Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                                        val image=remember(g.photo) { try { if(g.photo.isNotBlank()) android.graphics.BitmapFactory.decodeStream(context.contentResolver.openInputStream(Uri.parse(g.photo))) else null } catch(_:Exception){null} }
                                        if(image!=null) Image(image.asImageBitmap(),null,Modifier.size(68.dp).clip(RoundedCornerShape(12.dp)),contentScale=ContentScale.Crop)
                                        else Box(Modifier.size(68.dp).clip(RoundedCornerShape(16.dp)).background(androidx.compose.ui.graphics.Color(0xFFE5EBE2)),contentAlignment=Alignment.Center){Icon(Icons.Default.PhotoCamera,null,tint=forest)}
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) { Text(g.name,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis); Text("${g.category} · 数量 ${g.quantity}",fontSize=11.sp,color=muted); Text(yuan(g.price)+" / 件 · ${g.weightGrams.toInt()} g/件",fontSize=12.sp,color=forest,fontWeight=FontWeight.SemiBold); if(g.warrantyUntil.isNotBlank()) Text("保修至 "+g.warrantyUntil,fontSize=10.sp,color=muted); if(g.maintenanceLog.isNotBlank()) Text("维护记录："+g.maintenanceLog.take(42),fontSize=10.sp,color=muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                                            Row(verticalAlignment=Alignment.CenterVertically) {
                                                Checkbox(checked=g.packed,onCheckedChange={v->save(projects.map{pr->if(pr.id==p.id)pr.copy(gear=pr.gear.map{it2->if(it2.id==g.id)it2.copy(packed=v) else it2}) else pr})},modifier=Modifier.size(30.dp))
                                                Text(if(g.packed)"已打包" else "未打包",fontSize=11.sp,color=muted)
                                            }
                                        }
                                        IconButton(onClick={save(projects.map{if(it.id==p.id)it.copy(gear=it.gear.filterNot{x->x.id==g.id}) else it})}) { Icon(Icons.Default.Delete,null,tint=androidx.compose.ui.graphics.Color(0xFFB36D5D)) }
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
                                        Text(p.name,fontSize=22.sp,fontWeight=FontWeight.ExtraBold,color=androidx.compose.ui.graphics.Color.White)
                                        Text(listOf(p.place,p.date).filter{it.isNotBlank()}.joinToString("  ·  ").ifBlank{"户外路线与环境信息"},fontSize=12.sp,color=androidx.compose.ui.graphics.Color(0xFFD8E2D7))
                                        Spacer(Modifier.height(12.dp))
                                        Text(if(p.trackData.isNotBlank()) "已导入徒步轨迹" else "尚未导入 GPX 轨迹",fontSize=12.sp,color=androidx.compose.ui.graphics.Color.White)
                                        Text(if(p.mapPath.isNotBlank()) "离线地图已就绪" else "可导入 MBTiles 离线地图",fontSize=12.sp,color=androidx.compose.ui.graphics.Color(0xFFD8E2D7))
                                    }
                                }
                            }
                            item {
                                Text("导航与地图",fontWeight=FontWeight.Bold,color=forest,fontSize=16.sp)
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                    Card(Modifier.weight(1f).clickable { mapOpen=true },shape=RoundedCornerShape(18.dp),colors=CardDefaults.cardColors(containerColor=androidx.compose.ui.graphics.Color.White)) {
                                        Column(Modifier.fillMaxWidth().padding(16.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                                            Icon(Icons.Default.Landscape,null,tint=forest,modifier=Modifier.size(28.dp))
                                            Spacer(Modifier.height(8.dp));Text("地图 / 轨迹导航",fontWeight=FontWeight.Bold,fontSize=13.sp,color=forest)
                                            Text(if(p.trackData.isNotBlank()) "进入路线导航" else "打开离线地图",fontSize=10.sp,color=muted)
                                        }
                                    }
                                    Card(Modifier.weight(1f).clickable { if(p.trackData.isNotBlank()) trackOpen=true },shape=RoundedCornerShape(18.dp),colors=CardDefaults.cardColors(containerColor=androidx.compose.ui.graphics.Color.White)) {
                                        Column(Modifier.fillMaxWidth().padding(16.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                                            Icon(Icons.Default.Image,null,tint=forest,modifier=Modifier.size(28.dp))
                                            Spacer(Modifier.height(8.dp));Text("轨迹预览",fontWeight=FontWeight.Bold,fontSize=13.sp,color=forest)
                                            Text(if(p.trackData.isNotBlank()) "查看路线概况" else "尚无轨迹",fontSize=10.sp,color=muted)
                                        }
                                    }
                                }
                            }
                            item {
                                OutlinedButton(onClick={mapTargetProjectId=p.id;mbtilesImporter.launch(arrayOf("*/*"))},modifier=Modifier.fillMaxWidth()) { Text(if(p.mapPath.isBlank()) "＋ 导入离线地图包" else "更换离线地图包") }
                            }
                            item {
                                Card(
                                    Modifier.fillMaxWidth().clickable {
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
                                    OutlinedButton(onClick={weatherOpen=true},modifier=Modifier.weight(1f)) { Text("目的地天气") }
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

    if (weatherOpen && project != null) TrailWeatherDialog(project.place) { weatherOpen=false }
    if (safetyOpen && project != null) TrailSafetyDialog(project) { safetyOpen=false }
    if (trackOpen && project != null) TrailTrackDialog(project.trackData) { trackOpen=false }
    if (project != null) OfflineMapDialog(project.mapPath, project.trackData, project.id, project.waypointData, project.elevationData, visible=mapOpen) { mapOpen=false }

    if (advancedToolsOpen && project != null) {
        TrailKitAdvancedDialog(
            project=project,
            onClose={advancedToolsOpen=false},
            onAddSuggested={ additions ->
                val fresh=additions.filterNot { a -> project.gear.any { it.name.equals(a.name,true) } }
                if(fresh.isNotEmpty()) save(projects.map { if(it.id==project.id) it.copy(gear=it.gear+fresh) else it })
                Toast.makeText(context,if(fresh.isEmpty()) "没有新增建议装备" else "已加入 "+fresh.size+" 件建议装备，请补录实际重量",Toast.LENGTH_LONG).show()
            }
        )
    }

    if(projectDialog) ProjectDialog({projectDialog=false}) { name,place,date ->
        val p=Project(System.currentTimeMillis().toString(),name,place,date,emptyList()); save(projects+p);selected=p.id;projectDialog=false
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
                    projects.firstOrNull { it.id == id }?.let { deleteProjectNavigationData(context,it) }
                    save(projects.filterNot { it.id == id })
                    if (selected == id) selected = null
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
        val updated=if(editing==null) project.copy(gear=project.gear+g) else project.copy(gear=project.gear.map{if(it.id==g.id)g else it})
        save(projects.map{if(it.id==project.id)updated else it});gearDialog=false
    }
}

@Composable private fun Empty(title:String,sub:String) {
    Column(Modifier.fillMaxWidth().padding(vertical=34.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Icon(Icons.Default.Landscape,null,tint=forest,modifier=Modifier.size(38.dp)); Spacer(Modifier.height(10.dp))
        Text(title,fontWeight=FontWeight.Bold,color=forest); Text(sub,fontSize=12.sp,color=muted)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ProjectDialog(close:()->Unit,save:(String,String,String)->Unit) {
    var name by remember{mutableStateOf("")};var place by remember{mutableStateOf("")};var date by remember{mutableStateOf("")}
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
                val bitmap=remember(photo){try{if(photo.isNotBlank())android.graphics.BitmapFactory.decodeStream(ctx.contentResolver.openInputStream(Uri.parse(photo)))else null}catch(_:Exception){null}}
                if(bitmap!=null) Image(bitmap.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
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

private fun makePoster(context:Context,project:Project) {
    var bitmap: Bitmap? = null
    try {
        val width = 1440
        val groups = categories.mapNotNull { c -> project.gear.filter { it.category == c }.takeIf { it.isNotEmpty() }?.let { c to it } }
        val height = (900 + groups.sumOf { 112 + it.second.size * 188 } + 250).coerceAtLeast(1500)
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap!!)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val paper = 0xFFF2F0E8.toInt()
        val ink = 0xFF1D3028.toInt()
        val forest = 0xFF183D31.toInt()
        val sage = 0xFF718477.toInt()
        val white = 0xFFFFFFFF.toInt()
        fun box(r: RectF, radius: Float, color: Int) {
            paint.shader = null; paint.style = Paint.Style.FILL; paint.color = color
            canvas.drawRoundRect(r, radius, radius, paint)
        }
        fun txt(s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false,
                align: Paint.Align = Paint.Align.LEFT) {
            paint.shader = null; paint.style = Paint.Style.FILL; paint.color = color
            paint.textSize = size; paint.textAlign = align
            paint.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            canvas.drawText(s, x, y, paint); paint.textAlign = Paint.Align.LEFT
        }
        fun fit(s: String, x: Float, y: Float, max: Float, size: Float, color: Int) {
            paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            var z = size
            while (z > 42f) { paint.textSize = z; if (paint.measureText(s) <= max) break; z -= 2f }
            txt(s, x, y, z, color, true)
        }
        fun crop(uri: String, r: RectF) {
            val save = canvas.save()
            val path = Path().apply { addRoundRect(r, 20f, 20f, Path.Direction.CW) }
            canvas.clipPath(path)
            var source: Bitmap? = null
            try { if (uri.isNotBlank()) source = BitmapFactory.decodeStream(context.contentResolver.openInputStream(Uri.parse(uri))) } catch (_: Exception) {}
            if (source != null) {
                val b = source!!
                val scale = maxOf(r.width() / b.width, r.height() / b.height)
                val w = b.width * scale; val h = b.height * scale
                canvas.drawBitmap(b, null, RectF(r.centerX()-w/2, r.centerY()-h/2, r.centerX()+w/2, r.centerY()+h/2), paint)
                b.recycle()
            } else {
                paint.color = 0xFFE5EAE2.toInt(); canvas.drawRect(r, paint)
                paint.color = 0xFF9BA99B.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = 5f
                val mountain = Path().apply { moveTo(r.left+25,r.bottom-28); lineTo(r.left+70,r.top+42); lineTo(r.left+110,r.bottom-48); lineTo(r.left+145,r.top+60); lineTo(r.right-20,r.bottom-28) }
                canvas.drawPath(mountain, paint); paint.style = Paint.Style.FILL
            }
            canvas.restoreToCount(save)
        }
        canvas.drawColor(paper)
        paint.shader = LinearGradient(0f,0f,width.toFloat(),620f,intArrayOf(0xFF244B3B.toInt(),0xFF102A23.toInt()),null,Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,width.toFloat(),620f,paint); paint.shader = null
        // Subtle topographic contour motif.
        paint.color = 0x24D8E4D8; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
        for (i in 0..7) {
            val d=i*34f
            val path=Path().apply { moveTo(900f+d,-30f); cubicTo(760f+d,140f,1320f-d,190f,1050f+d,390f); cubicTo(900f+d,500f,1300f-d,520f,1440f,430f+d) }
            canvas.drawPath(path,paint)
        }
        paint.style=Paint.Style.FILL
        txt("TRAILKIT   /   OUTDOOR GEAR JOURNAL",92f,100f,26f,0xFFC8D7C9.toInt(),true)
        txt("装备档案  ·  FIELD EDITION",92f,150f,20f,0xFF9FB5A4.toInt())
        fit(project.name,92f,275f,1240f,84f,white)
        val subtitle=listOf(project.place,project.date).filter{it.isNotBlank()}.joinToString("     /     ")
        txt(if(subtitle.isBlank()) "一份为下一段旅程准备的装备清单" else subtitle,96f,340f,28f,0xFFD7E1D7.toInt())
        txt("PREPARED FOR THE TRAIL",96f,405f,18f,0xFF9FB5A4.toInt(),true)

        // Summary card.
        box(RectF(72f,465f,1368f,735f),30f,white)
        txt("装备总价值  /  TOTAL VALUE",116f,530f,22f,sage,true)
        txt(yuan(project.gear.sumOf{it.price*it.quantity}),112f,635f,80f,forest,true)
        paint.color=0xFFE2E2D8.toInt(); paint.strokeWidth=2f
        canvas.drawLine(900f,515f,900f,685f,paint)
        txt("装备总数量",955f,555f,21f,sage)
        txt(project.gear.sumOf{it.quantity}.toString(),955f,625f,54f,ink,true)
        txt("品类  "+groups.size+"    ·    单品  "+project.gear.size,955f,675f,20f,sage)

        var y=835f
        groups.forEachIndexed { index, pair ->
            val category=pair.first; val items=pair.second
            box(RectF(78f,y-45f,1362f,y+30f),18f,0xFFE6E9E1.toInt())
            txt("%02d".format(index+1),108f,y+5f,24f,forest,true)
            txt(category,180f,y+7f,31f,ink,true)
            txt(items.size.toString()+" ITEMS",1000f,y+5f,18f,sage,true)
            txt(yuan(items.sumOf{it.price*it.quantity}),1320f,y+7f,25f,forest,true,Paint.Align.RIGHT)
            y+=102f
            items.forEach { gear ->
                box(RectF(78f,y,1362f,y+166f),22f,white)
                crop(gear.photo,RectF(96f,y+13f,256f,y+153f))
                fit(gear.name,292f,y+57f,700f,34f,ink)
                txt("数量 × "+gear.quantity+"     /     单价 "+yuan(gear.price),294f,y+96f,20f,sage)
                if(gear.note.isNotBlank()) {
                    val note=gear.note.take(54)
                    txt(note,294f,y+132f,18f,0xFF92998F.toInt())
                }
                txt(yuan(gear.price*gear.quantity),1320f,y+82f,30f,forest,true,Paint.Align.RIGHT)
                y+=188f
            }
            y+=10f
        }
        val foot=height-190f
        box(RectF(78f,foot,1362f,height-58f),24f,0xFF142F27.toInt())
        txt("PACK WELL. WALK FAR.",112f,foot+62f,31f,white,true)
        txt("每一次出发，都值得认真准备。",112f,foot+105f,22f,0xFFD0DCCF.toInt())
        txt("TRAILKIT  ·  PERSONAL GEAR RECORD",1320f,foot+84f,17f,0xFFB5C7B8.toInt(),true,Paint.Align.RIGHT)

        val filename="TrailKit_"+project.name+"_"+System.currentTimeMillis()+".jpg"
        val values=ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,filename)
            put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
            if(Build.VERSION.SDK_INT>=29) put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/TrailKit")
        }
        val uri=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values) ?: error("无法创建图片")
        val ok=context.contentResolver.openOutputStream(uri)?.use { bitmap!!.compress(Bitmap.CompressFormat.JPEG,96,it) } ?: false
        if(!ok) error("图片保存失败")
        Toast.makeText(context,"专业版装备海报已保存到相册 Pictures/TrailKit",Toast.LENGTH_LONG).show()
    } catch(e:Exception) {
        Toast.makeText(context,"生成失败："+(e.message?:"未知错误"),Toast.LENGTH_LONG).show()
    } finally { bitmap?.recycle() }
}
