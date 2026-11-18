package com.tubu.trailkit

import android.Manifest
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.RectF
import android.util.LruCache
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.GeomagneticField
import android.os.Build
import android.os.BatteryManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.*
import kotlinx.coroutines.delay

private data class MbInfo(val name:String,val format:String,val minZoom:Int,val maxZoom:Int,val centerLon:Double,val centerLat:Double,val centerZoom:Float,val bounds:List<Double>,val tileCount:Int)
private fun readMbInfo(db:SQLiteDatabase):MbInfo {
    val values=mutableMapOf<String,String>()
    db.rawQuery("SELECT name,value FROM metadata",null).use { c -> while(c.moveToNext()) values[c.getString(0)]=c.getString(1) }
    val center=values["center"]?.split(",")?.mapNotNull{it.toDoubleOrNull()}
    val bounds=values["bounds"]?.split(",")?.mapNotNull{it.trim().toDoubleOrNull()}?:emptyList()
    val tileCount=db.rawQuery("SELECT COUNT(*) FROM tiles",null).use{if(it.moveToFirst())it.getInt(0) else 0}
    return MbInfo(values["name"]?:"离线地图",values["format"]?:"jpg",values["minzoom"]?.toIntOrNull()?:0,values["maxzoom"]?.toIntOrNull()?:16,center?.getOrNull(0)?:0.0,center?.getOrNull(1)?:0.0,center?.getOrNull(2)?.toFloat()?:10f,bounds,tileCount)
}
private fun worldX(lon:Double,z:Int):Double=(lon+180.0)/360.0*(256.0*(1 shl z))
private fun worldY(lat:Double,z:Int):Double {
    val r=lat.coerceIn(-85.0511,85.0511)*Math.PI/180.0
    return (1.0-ln(tan(r)+1.0/cos(r))/Math.PI)/2.0*(256.0*(1 shl z))
}
private fun mapDistance(a:Pair<Double,Double>,b:Pair<Double,Double>):Double {
    val r=6371000.0;val p1=Math.toRadians(a.first);val p2=Math.toRadians(b.first)
    val dp=Math.toRadians(b.first-a.first);val dl=Math.toRadians(b.second-a.second)
    val h=sin(dp/2).pow(2)+cos(p1)*cos(p2)*sin(dl/2).pow(2)
    return 2*r*asin(sqrt(h.coerceIn(0.0,1.0)))
}

// Convert WGS84 GPS coordinates to GCJ-02 for Amap's coordinate display in China.
private fun wgs84ToGcj02(latitude:Double,longitude:Double):Pair<Double,Double> {
    if(longitude !in 72.004..137.8347 || latitude !in 0.8293..55.8271) return latitude to longitude
    val a=6378245.0
    val ee=0.00669342162296594323
    fun transformLat(x:Double,y:Double):Double {
        var value=-100.0+2.0*x+3.0*y+0.2*y*y+0.1*x*y+0.2*sqrt(abs(x))
        value+=(20.0*sin(6.0*x*Math.PI)+20.0*sin(2.0*x*Math.PI))*2.0/3.0
        value+=(20.0*sin(y*Math.PI)+40.0*sin(y/3.0*Math.PI))*2.0/3.0
        value+=(160.0*sin(y/12.0*Math.PI)+320.0*sin(y*Math.PI/30.0))*2.0/3.0
        return value
    }
    fun transformLon(x:Double,y:Double):Double {
        var value=300.0+x+2.0*y+0.1*x*x+0.1*x*y+0.1*sqrt(abs(x))
        value+=(20.0*sin(6.0*x*Math.PI)+20.0*sin(2.0*x*Math.PI))*2.0/3.0
        value+=(20.0*sin(x*Math.PI)+40.0*sin(x/3.0*Math.PI))*2.0/3.0
        value+=(150.0*sin(x/12.0*Math.PI)+300.0*sin(x/30.0*Math.PI))*2.0/3.0
        return value
    }
    val dLat=transformLat(longitude-105.0,latitude-35.0)
    val dLon=transformLon(longitude-105.0,latitude-35.0)
    val radLat=Math.toRadians(latitude)
    val magic=1.0-ee*sin(radLat).pow(2)
    val sqrtMagic=sqrt(magic)
    val adjustedLat=dLat*180.0/((a*(1.0-ee))/(magic*sqrtMagic)*Math.PI)
    val adjustedLon=dLon*180.0/(a/sqrtMagic*cos(radLat)*Math.PI)
    return (latitude+adjustedLat) to (longitude+adjustedLon)
}

private fun recoverRecordedGpx(file:File):ByteArray {
    val source=file.readText(Charsets.UTF_8)
    if(source.contains("</gpx>")) return source.toByteArray(Charsets.UTF_8)
    val lastPointEnd=source.lastIndexOf("</trkpt>")
    val safe=if(lastPointEnd>=0)source.substring(0,lastPointEnd+8) else source.substringBefore("<trkpt")
    return (safe.trimEnd()+"\n</trkseg></trk></gpx>").toByteArray(Charsets.UTF_8)
}
private fun projectTrackFile(context:Context,projectId:String)=File(context.filesDir,"trailkit_track_${projectId.hashCode()}.gpx")

private fun loadMbTile(db:SQLiteDatabase,z:Int,x:Int,y:Int,minZoom:Int,cache:LruCache<String,Bitmap>):Bitmap? {
    val key="$z/$x/$y"
    cache.get(key)?.let{return it}
    var direct:Bitmap?=null
    try {
        db.rawQuery("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT 1",arrayOf(z.toString(),x.toString(),((1 shl z)-1-y).toString())).use { cursor ->
            if(cursor.moveToFirst()) {
                val bytes=cursor.getBlob(0)
                direct=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
            }
        }
    } catch(_:Exception) {}
    if(direct!=null) {
        cache.put(key,direct!!)
        return direct
    }
    if(z<=minZoom) return null
    val parent=loadMbTile(db,z-1,x/2,y/2,minZoom,cache) ?: return null
    return try {
        val halfWidth=parent.width/2
        val halfHeight=parent.height/2
        val crop=Bitmap.createBitmap(parent,(x%2)*halfWidth,(y%2)*halfHeight,halfWidth,halfHeight)
        val scaled=Bitmap.createScaledBitmap(crop,256,256,true)
        if(crop!==scaled) crop.recycle()
        scaled.also{cache.put(key,it)}
    } catch(_:Exception) { null }
}

@Composable fun OfflineMapDialog(path:String,encoded:String,projectId:String,encodedWaypoints:String="[]",encodedElevations:String="[]",visible:Boolean=true,onClose:()->Unit) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val db=remember(path) { if(path.isBlank()) null else try { SQLiteDatabase.openDatabase(path,null,SQLiteDatabase.OPEN_READONLY) } catch(_:Exception){null} }
    DisposableEffect(db) { onDispose { try{db?.close()}catch(_:Exception){} } }
    val info=remember(db) { try{db?.let(::readMbInfo)}catch(_:Exception){null} }
    val points=remember(encoded){decodeTrackPoints(encoded)}
    var reverseRoute by remember(projectId){mutableStateOf(context.getSharedPreferences("trailkit_navigation",Context.MODE_PRIVATE).getBoolean("reverse_$projectId",false))}
    val navigationPoints=remember(points,reverseRoute){if(reverseRoute)points.asReversed() else points}
    val preparedRoute=remember(navigationPoints){prepareRoute(navigationPoints)}
    val mapCoverage=remember(info,navigationPoints) {
        val bounds=info?.bounds
        if(bounds?.size==4 && navigationPoints.isNotEmpty()) {
            val west=bounds[0];val south=bounds[1];val east=bounds[2];val north=bounds[3]
            val minLat=navigationPoints.minOf{it.first};val maxLat=navigationPoints.maxOf{it.first}
            val minLon=navigationPoints.minOf{it.second};val maxLon=navigationPoints.maxOf{it.second}
            minLon>=west && maxLon<=east && minLat>=south && maxLat<=north
        } else null
    }
    val waypoints=remember(encodedWaypoints){decodeGpxWaypoints(encodedWaypoints)}
    val customWaypointPrefs=remember(projectId){context.getSharedPreferences("trailkit_navigation",Context.MODE_PRIVATE)}
    val customWaypoints=remember(projectId) {
        mutableStateListOf<GpxWaypoint>().apply {
            try {
                val saved=JSONArray(customWaypointPrefs.getString("custom_waypoints_$projectId","[]"))
                for(i in 0 until saved.length()) {
                    val item=saved.getJSONObject(i)
                    add(GpxWaypoint(item.getDouble("lat"),item.getDouble("lon"),item.optString("name","自定义标记")))
                }
            } catch(_:Exception) {}
        }
    }
    val allWaypoints=waypoints+customWaypoints
    fun persistCustomWaypoints() {
        val saved=JSONArray()
        customWaypoints.forEach{wp->saved.put(JSONObject().put("lat",wp.lat).put("lon",wp.lon).put("name",wp.name))}
        customWaypointPrefs.edit().putString("custom_waypoints_$projectId",saved.toString()).apply()
    }
    fun addCustomWaypoint(lat:Double,lon:Double) {
        val markerName="地图标记 ${customWaypoints.size+1}"
        customWaypoints.add(GpxWaypoint(lat,lon,markerName))
        persistCustomWaypoints()
        android.widget.Toast.makeText(context,"已添加标记：$markerName",android.widget.Toast.LENGTH_LONG).show()
    }
    val elevationProfile=remember(encodedElevations){decodeGpxElevations(encodedElevations)}
    val navigationElevations=remember(elevationProfile,reverseRoute){if(reverseRoute)elevationProfile.asReversed() else elevationProfile}
    // Precompute route distances once; Canvas redraws must not recalculate every GPX segment.
    val elevationCumulative=remember(navigationPoints) {
        DoubleArray(navigationPoints.size).also { cumulative ->
            for(i in 1 until navigationPoints.size) cumulative[i]=cumulative[i-1]+mapDistance(navigationPoints[i-1],navigationPoints[i])
        }
    }
    val elevationStats=remember(navigationElevations){
        val pairs=navigationElevations.zipWithNext().filter{it.first!=null&&it.second!=null}
        Triple(pairs.sumOf{(a,b)->(b!!-a!!).coerceAtLeast(0.0)},pairs.sumOf{(a,b)->(a!!-b!!).coerceAtLeast(0.0)},navigationElevations.filterNotNull())
    }
    val prefs=remember(projectId){context.getSharedPreferences("trailkit_navigation",Context.MODE_PRIVATE)}
    var voiceGuidance by remember(projectId){mutableStateOf(prefs.getBoolean("voice_$projectId",false))}
    val voiceReady=remember{mutableStateOf(false)}
    val voiceInitComplete=remember{mutableStateOf(false)}
    val textToSpeech=remember(context) {
        try {
            lateinit var engine:TextToSpeech
            engine=TextToSpeech(context) { status ->
                if(status==TextToSpeech.SUCCESS) {
                    voiceReady.value=engine.setLanguage(Locale.SIMPLIFIED_CHINESE)>=TextToSpeech.LANG_AVAILABLE
                }
                voiceInitComplete.value=true
                if(status!=TextToSpeech.SUCCESS) voiceReady.value=false
            }
            engine
        } catch(_:Exception) { null }
    }
    LaunchedEffect(textToSpeech) { if(textToSpeech==null) {voiceInitComplete.value=true;voiceReady.value=false} }
    DisposableEffect(textToSpeech) { onDispose { try{textToSpeech?.stop();textToSpeech?.shutdown()}catch(_:Exception){} } }
    fun speakNavigation(message:String,interrupt:Boolean=false) {
        if(voiceGuidance && voiceReady.value) try{textToSpeech?.speak(message,if(interrupt)TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,null,"trailkit_navigation")}catch(_:Exception){}
    }
    var threshold by remember(projectId){mutableFloatStateOf(prefs.getInt("offroute_$projectId",80).toFloat().coerceIn(20f,300f))}
    var active by remember(projectId) { mutableStateOf(false) }
    var current by remember(projectId) { mutableStateOf<Location?>(null) }
    var gpsStatusTick by remember(projectId) { mutableLongStateOf(0L) }

    val gpsFixAgeSeconds=current?.let{((System.currentTimeMillis()-it.time).coerceAtLeast(0L)/1000L)}?.plus(gpsStatusTick)
    var gpsError by remember(projectId) { mutableStateOf("") }
    var gpsStaleWas by remember(projectId) { mutableStateOf(false) }
    var followGps by remember(projectId) { mutableStateOf(true) }
    var mapOrientation by remember(projectId) { mutableStateOf(prefs.getString("orientation_$projectId","north") ?: "north") }
    var mapBearing by remember(projectId) { mutableFloatStateOf(0f) }
    var heading by remember { mutableFloatStateOf(Float.NaN) }
    var compassAccuracy by remember { mutableIntStateOf(SensorManager.SENSOR_STATUS_UNRELIABLE) }
    val progressKey="progress_${projectId}_${encoded.hashCode()}_${if(reverseRoute)"rev" else "fwd"}"
    val segmentKey="segment_${projectId}_${encoded.hashCode()}_${if(reverseRoute)"rev" else "fwd"}"
    var confirmedProgress by remember(projectId,encoded,reverseRoute) { mutableDoubleStateOf(prefs.getFloat(progressKey,0f).toDouble()) }
    var lastMatchedSegment by remember(projectId,encoded,reverseRoute) { mutableIntStateOf(prefs.getInt(segmentKey,0)) }
    var offRouteWas by remember(projectId,encoded,reverseRoute) { mutableStateOf(false) }
    var navigationSessionInitialized by remember(projectId,encoded,reverseRoute) { mutableStateOf(false) }
    fun prepareNavigationStart() {
        if(!navigationSessionInitialized) {
            confirmedProgress=0.0
            lastMatchedSegment=0
            offRouteWas=false
            prefs.edit().putFloat(progressKey,0f).putInt(segmentKey,0).apply()
            navigationSessionInitialized=true
        }
        current=null
        gpsStaleWas=false
        followGps=true
        gpsError="正在等待新的 GPS 定位…"
    }
    var lastVibrationAt by remember(projectId) { mutableLongStateOf(0L) }
    var isRecording by remember(projectId) { mutableStateOf(false) }
    var showWaypointList by remember(projectId) { mutableStateOf(false) }
    var editingCustomWaypoint by remember(projectId) { mutableStateOf<GpxWaypoint?>(null) }
    var waypointNameDraft by remember(projectId) { mutableStateOf("") }
    var measureMode by remember(projectId) { mutableStateOf(false) }
    val measurePoints=remember(projectId){mutableStateListOf<Pair<Double,Double>>()}
    val measureDistance=remember(measurePoints.toList()){measurePoints.zipWithNext().sumOf{mapDistance(it.first,it.second)}}
    val recordedPoints = remember(projectId) { mutableStateListOf<Location>() }
    var recordingClockTick by remember(projectId) { mutableLongStateOf(0L) }
    LaunchedEffect(active) {
        while(active) {
            kotlinx.coroutines.delay(5000L)
            gpsStatusTick++
        }
    }
    LaunchedEffect(isRecording) {
        while(isRecording) {
            kotlinx.coroutines.delay(1000L)
            recordingClockTick++
        }
    }
    var hasSavedRecording by remember(projectId) { mutableStateOf(projectTrackFile(context,projectId).exists()) }
    val tileCache = remember(path) { object : LruCache<String, android.graphics.Bitmap>(24 * 1024) { override fun sizeOf(key:String,value:android.graphics.Bitmap)=value.byteCount/1024 } }
    val exportGpx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        if(uri!=null) try {
            val body=buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"TrailKit\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><name>TrailKit 实时记录</name><trkseg>\n")
                recordedPoints.forEach { p ->
                    append("<trkpt lat=\"${p.latitude}\" lon=\"${p.longitude}\">")
                    if(p.hasAltitude()) append("<ele>${p.altitude}</ele>")
                    append("<time>${java.time.Instant.ofEpochMilli(p.time)}</time></trkpt>\n")
                }
                append("</trkseg></trk></gpx>")
            }
            context.contentResolver.openOutputStream(uri)?.use { output ->
                val file=projectTrackFile(context,projectId)
                if(file.exists() && file.length()>80L) output.write(recoverRecordedGpx(file))
                else output.write(body.toByteArray(Charsets.UTF_8))
            }
        } catch(_:Exception) { gpsError="GPX 导出失败，请检查保存位置" }
    }
    val exportRouteGpx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        if(uri!=null) try {
            fun xml(value:String)=value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;")
            val body=buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"TrailKit\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
                append("<trk><name>TrailKit 导出路线</name><trkseg>\n")
                navigationPoints.forEachIndexed { index,p ->
                    append("<trkpt lat=\"${p.first}\" lon=\"${p.second}\">")
                    navigationElevations.getOrNull(index)?.let{append("<ele>$it</ele>")}
                    append("</trkpt>\n")
                }
                append("</trkseg></trk>\n")
                allWaypoints.forEachIndexed { index,wp -> append("<wpt lat=\"${wp.lat}\" lon=\"${wp.lon}\"><name>${xml(wp.name)}</name></wpt>\n") }
                append("</gpx>")
            }
            context.contentResolver.openOutputStream(uri)?.use{it.write(body.toByteArray(Charsets.UTF_8))}
        } catch(_:Exception) { gpsError="路线 GPX 导出失败，请检查保存位置" }
    }
    val viewPrefs=remember(projectId,path){context.getSharedPreferences("trailkit_map_view",Context.MODE_PRIVATE)}
    var precisionMode by remember(projectId){mutableStateOf(prefs.getBoolean("precision_mode_$projectId",false))}
    val zoomLimit = if(precisionMode) 20f else 18f
    // Use a versioned camera key so stale zoom/center values from the previous
    // map renderer cannot open the new MapLibre navigation view at a world-scale zoom.
    val viewKey="${projectId}_${path.hashCode()}_routefit2"
    val routeBounds=remember(navigationPoints) {
        if(navigationPoints.isNotEmpty()) listOf(
            navigationPoints.minOf{it.first}, navigationPoints.maxOf{it.first},
            navigationPoints.minOf{it.second}, navigationPoints.maxOf{it.second}
        ) else emptyList()
    }
    val initialRouteCenter=remember(routeBounds,info) {
        if(routeBounds.size==4) (routeBounds[0]+routeBounds[1])/2.0 to (routeBounds[2]+routeBounds[3])/2.0
        else (info?.centerLat?:0.0) to (info?.centerLon?:0.0)
    }
    val initialRouteZoom=remember(routeBounds,context,info) {
        if(routeBounds.size==4) {
            val dx=(worldX(routeBounds[3],0)-worldX(routeBounds[2],0)).coerceAtLeast(1.0)
            val dy=(worldY(routeBounds[0],0)-worldY(routeBounds[1],0)).coerceAtLeast(1.0)
            val width=context.resources.displayMetrics.widthPixels*0.78
            val height=310.0*context.resources.displayMetrics.density*0.72
            val fitScale=min((width-48.0)/dx,(height-48.0)/dy).coerceAtLeast(0.25)
            (ln(fitScale)/ln(2.0)).toFloat().coerceIn(2f,20f)
        } else (info?.centerZoom?:12f).coerceIn(2f,18f)
    }
    var zoom by remember(projectId,path){mutableFloatStateOf(viewPrefs.getFloat("zoom_$viewKey",initialRouteZoom).coerceIn(2f,18f))}
    var centerLon by remember(projectId,path){mutableDoubleStateOf(viewPrefs.getString("lon_$viewKey",null)?.toDoubleOrNull() ?: initialRouteCenter.second)}
    var centerLat by remember(projectId,path){mutableDoubleStateOf(viewPrefs.getString("lat_$viewKey",null)?.toDoubleOrNull() ?: initialRouteCenter.first)}
    var mapProjector by remember(projectId,path) { mutableStateOf<((Double,Double)->android.graphics.PointF)?>(null) }
    var mapLayer by remember(projectId) { mutableStateOf("standard") }
    val activeMapStyle = when(mapLayer) {
        "satellite" -> TRAILKIT_SATELLITE_STYLE
        "terrain" -> TRAILKIT_TERRAIN_STYLE
        else -> TRAILKIT_MAP_STYLE
    }
    LaunchedEffect(projectId,path,zoom,centerLon,centerLat) {
        kotlinx.coroutines.delay(450L)
        viewPrefs.edit().putFloat("zoom_$viewKey",zoom).putString("lon_$viewKey",centerLon.toString()).putString("lat_$viewKey",centerLat.toString()).apply()
    }
    val sampledRouteTileCoverage=remember(db,navigationPoints,zoom) {
        if(db==null || navigationPoints.isEmpty()) null
        else try {
            val z=zoom.roundToInt().coerceIn(info?.minZoom?:0,info?.maxZoom?:18)
            val step=max(1,ceil(navigationPoints.size/24.0).toInt())
            val samples=navigationPoints.filterIndexed{index,_->index%step==0 || index==navigationPoints.lastIndex}
            val minZ=info?.minZoom?:0
            var missing=0
            var parentFallback=0
            samples.forEach { point ->
                val x=floor(worldX(point.second,z)/256.0).toInt()
                val y=floor(worldY(point.first,z)/256.0).toInt()
                val tileX=((x%(1 shl z))+(1 shl z))%(1 shl z)
                var level=z
                var foundLevel=-1
                while(level>=minZ && foundLevel<0) {
                    val shift=z-level
                    val parentX=tileX shr shift
                    val parentY=y shr shift
                    val tmsY=(1 shl level)-1-parentY
                    val found=db.rawQuery("SELECT 1 FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT 1",arrayOf(level.toString(),parentX.toString(),tmsY.toString())).use{it.moveToFirst()}
                    if(found)foundLevel=level
                    level--
                }
                if(foundLevel<0)missing++ else if(foundLevel<z)parentFallback++
            }
            Triple(missing,samples.size,parentFallback)
        } catch(_:Exception) { null }
    }
    val permissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if(result[Manifest.permission.ACCESS_FINE_LOCATION]==true || result[Manifest.permission.ACCESS_COARSE_LOCATION]==true) { prepareNavigationStart();active=true;gpsError="正在获取手机定位…" }
        else gpsError="请允许 TrailKit 使用位置信息；仅允许大致位置也可以显示地图位置"
    }
    DisposableEffect(active,projectId) {
        if(active) {
            try { ContextCompat.startForegroundService(context,android.content.Intent(context,TrailNavigationService::class.java)) }
            catch(_:Exception) { gpsError="无法启动后台导航服务，请检查系统限制" }
        }
        onDispose { if(active) try { context.stopService(android.content.Intent(context,TrailNavigationService::class.java)) } catch(_:Exception) {} }
    }
    DisposableEffect(active) {
        if(active) {
            val fine=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
            val coarse=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
            val manager=context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var previousAccepted:Location?=null
            val listener=object:LocationListener {
                override fun onLocationChanged(location:Location) {
                    val age=System.currentTimeMillis()-location.time
                    if(age !in 0L..30000L || location.accuracy>1500f) return
                    val existing=current
                    if(existing!=null) {
                        if(location.time<existing.time) return
                        val existingAge=System.currentTimeMillis()-existing.time
                        if(existingAge in 0L..15000L && existing.accuracy<=75f && location.accuracy>existing.accuracy) return
                    }
                    if(location.accuracy<=75f) {
                        val previous=previousAccepted
                        if(previous!=null) {
                            val elapsed=((location.time-previous.time).coerceAtLeast(0L))/1000.0
                            val jump=mapDistance(previous.latitude to previous.longitude,location.latitude to location.longitude)
                            val oldSpeed=if(previous.hasSpeed())previous.speed else 0f
                            val newSpeed=if(location.hasSpeed())location.speed else 0f
                            val expected=maxOf(oldSpeed,newSpeed)*elapsed
                            val allowed=maxOf(120.0,expected+previous.accuracy+location.accuracy+30.0)
                            if(elapsed in 0.0..8.0 && jump>allowed) { gpsError="GPS 定位出现异常跳变，已忽略本次高精度位置";return }
                        }
                        previousAccepted=Location(location)
                    }
                    current=Location(location)
                    gpsError=if(location.accuracy<=75f) "" else "当前为网络辅助粗略定位（±${location.accuracy.roundToInt()} 米）；导航进度和轨迹记录暂不启用"
                    if(isRecording && location.provider==LocationManager.GPS_PROVIDER &&
                        age in 0L..15000L && location.accuracy<=50f) {
                        val previousRecorded=recordedPoints.lastOrNull()
                        val movedMeters=previousRecorded?.let {
                            mapDistance(it.latitude to it.longitude,location.latitude to location.longitude)
                        } ?: Double.POSITIVE_INFINITY
                        val elapsedSincePrevious=previousRecorded?.let { location.time-it.time } ?: Long.MAX_VALUE
                        if(previousRecorded==null || movedMeters>=3.0 ||
                            (elapsedSincePrevious>=8000L && movedMeters>=1.5)) {
                            recordedPoints.add(Location(location))
                        }
                    }
                    if(followGps) { centerLat=location.latitude;centerLon=location.longitude }
                }
                @Deprecated("Deprecated in Java") override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?) {}
                override fun onProviderEnabled(provider:String) {}
                override fun onProviderDisabled(provider:String) {
                    if(provider==LocationManager.GPS_PROVIDER) gpsError="GPS 卫星定位暂不可用，正在尝试网络辅助定位"
                }
            }
            try {
                if(fine || coarse) {
                    var requested=false
                    if(coarse && manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        try { manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,3000L,5f,listener);requested=true } catch(_:SecurityException) {}
                    }
                    if(fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        try { manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1500L,1f,listener);requested=true } catch(_:SecurityException) {}
                    }
                    gpsError=if(requested) "正在获取 GPS / Wi-Fi / 基站辅助定位…" else "系统定位源暂不可用，请检查手机定位开关和权限"
                } else { active=false;gpsError="请允许 TrailKit 使用位置信息" }
            } catch(e:Exception) { gpsError=e.message?.let{"定位启动失败：$it"}?:"定位启动失败，请检查定位设置";active=false }
            onDispose { try{manager.removeUpdates(listener)}catch(_:Exception){} }
        } else onDispose { }
    }
    // Show system-assisted coarse fixes on the map; route matching stays strict.
    // Keep the last known fix visible even when updates stop; age only disables navigation calculations.
    val cp=current?.takeIf { it.accuracy<=1500f }?.let{it.latitude to it.longitude}

    val utmCoordinate=cp?.let{toUtm(it.first,it.second)}
    val magneticDeclination=current?.let { loc ->
        try { GeomagneticField(loc.latitude.toFloat(),loc.longitude.toFloat(),if(loc.hasAltitude())loc.altitude.toFloat() else 0f,System.currentTimeMillis()).declination }
        catch(_:Exception) { 0f }
    } ?: 0f
    val trueHeading=if(heading.isNaN()) Float.NaN else (heading+magneticDeclination+360f)%360f
    val reliableTrueHeading=if(compassAccuracy==SensorManager.SENSOR_STATUS_UNRELIABLE || compassAccuracy==SensorManager.SENSOR_STATUS_ACCURACY_LOW) Float.NaN else trueHeading
    val gpsCourse=current?.takeIf{it.hasBearing() && it.hasSpeed() && it.speed>=0.8f && (gpsFixAgeSeconds?:999L)<=15L}?.bearing
    val compassHeadingAvailable=!reliableTrueHeading.isNaN()
    val arrowHeading=if(compassHeadingAvailable) reliableTrueHeading else gpsCourse ?: Float.NaN
    val arrowCanDraw=compassHeadingAvailable || gpsCourse!=null

    val routeMatch=cp?.takeIf { current?.accuracy?.let{accuracy->accuracy<=50f}==true && (gpsFixAgeSeconds?:999L)<=15L }
        ?.let { matchRoute(preparedRoute,it,lastMatchedSegment) }
    val progressMatch=routeMatch?.takeIf {
        active && (gpsFixAgeSeconds?:999L)<=15L && (current?.accuracy?:999f)<=50f &&
            it.distanceMeters<=threshold.toDouble()+(current?.accuracy?:0f).toDouble()
    }
    val desiredMapBearing = when(mapOrientation) {
        "heading" -> if(!arrowHeading.isNaN()) arrowHeading else 0f
        "track" -> routeMatch?.bearingDegrees?.toFloat() ?: if(!arrowHeading.isNaN()) arrowHeading else 0f
        else -> 0f
    }
    LaunchedEffect(mapOrientation, desiredMapBearing) {
        var remaining = ((desiredMapBearing - mapBearing + 540f) % 360f) - 180f
        val steps = 8
        repeat(steps) {
            remaining = ((desiredMapBearing - mapBearing + 540f) % 360f) - 180f
            if(kotlin.math.abs(remaining) > 0.7f) {
                mapBearing = (mapBearing + remaining / (steps - it).toFloat() + 360f) % 360f
                kotlinx.coroutines.delay(35L)
            }
        }
        mapBearing = (desiredMapBearing + 360f) % 360f
    }
    val nextTurnInfo=remember(progressMatch,navigationPoints,preparedRoute) {
        progressMatch?.let { match ->
            val i=match.segmentIndex
            if(i+2<navigationPoints.size && (gpsFixAgeSeconds?:999L)<=15L && (current?.accuracy?:999f)<=50f) {
                val nextBearing=bearingToPoint(navigationPoints[i+1],navigationPoints[i+2])
                val delta=signedTurnAngle(match.bearingDegrees,nextBearing)
                val distance=preparedRoute.prefixMeters[i+1]-match.progressMeters
                if(kotlin.math.abs(delta)>=35.0 && distance in 0.0..500.0) Triple(if(delta>0)"右转" else "左转",distance,kotlin.math.abs(delta)) else null
            } else null
        }
    }
    val closestWaypoint=if(cp!=null) allWaypoints.minByOrNull { mapDistance(cp,it.lat to it.lon) } else null
    val announcedWaypoints = remember(projectId,encoded) { mutableStateListOf<Int>() }
    val announcedApproaches = remember(projectId,encoded) { mutableStateListOf<Int>() }
    val announcedTurns = remember(projectId,encoded) { mutableStateListOf<Int>() }
    var lastTurnAnnouncementProgress by remember(projectId,encoded) { mutableDoubleStateOf(-1000.0) }
    LaunchedEffect(active,progressMatch,voiceGuidance,voiceReady.value) {
        val match=progressMatch
        if(active && voiceGuidance && voiceReady.value && match!=null && (gpsFixAgeSeconds?:999L)<=15L && (current?.accuracy?:999f)<=50f) {
            val index=match.segmentIndex
            if(index+2<navigationPoints.size && index !in announcedTurns) {
                val nextBearing=bearingToPoint(navigationPoints[index+1],navigationPoints[index+2])
                val delta=((nextBearing-match.bearingDegrees+540.0)%360.0)-180.0
                val distanceToTurn=preparedRoute.prefixMeters[index+1]-match.progressMeters
                if(kotlin.math.abs(delta)>=35.0 && distanceToTurn in 0.0..100.0 && match.progressMeters-lastTurnAnnouncementProgress>=60.0) {
                    announcedTurns.add(index)
                    lastTurnAnnouncementProgress=match.progressMeters
                    val direction=if(delta>0)"右转" else "左转"
                    speakNavigation("前方约${distanceToTurn.roundToInt()}米，$direction，约${kotlin.math.abs(delta).roundToInt()}度")
                }
            }
        }
    }
    LaunchedEffect(active, closestWaypoint, current?.accuracy, gpsStatusTick, voiceReady.value) {
        if(active && cp!=null && closestWaypoint!=null && (current?.accuracy ?: 999f)<=25f && (gpsFixAgeSeconds?:999L)<=15L) {
            val index=allWaypoints.indexOf(closestWaypoint)
            val distance=mapDistance(cp,closestWaypoint.lat to closestWaypoint.lon)
            if(index>=0 && distance<=100.0 && distance>30.0 && index !in announcedApproaches) {
                announcedApproaches.add(index)
                speakNavigation("前方约一百米，途经点：${closestWaypoint.name}")
            }
            if(index>=0 && distance<=30.0 && index !in announcedWaypoints) {
                announcedWaypoints.add(index)
                try {
                    val vibrator=context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    if(Build.VERSION.SDK_INT>=26) vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0,180,100,180),-1))
                    else vibrator.vibrate(longArrayOf(0,180,100,180),-1)
                } catch(_:Exception) {}
                android.widget.Toast.makeText(context,"已到达途经点：${closestWaypoint.name}",android.widget.Toast.LENGTH_LONG).show()
                speakNavigation("已到达途经点，${closestWaypoint.name}")
            }
        }
    }
    val nearest=progressMatch?.segmentIndex?:0
    val deviation=routeMatch?.distanceMeters
    val total=routeMatch?.totalMeters?:remember(navigationPoints){navigationPoints.zipWithNext().sumOf{mapDistance(it.first,it.second)}}
    val travelled=if(progressMatch!=null) maxOf(confirmedProgress,progressMatch.progressMeters) else confirmedProgress
    val waypointRoutePositions=remember(allWaypoints,navigationPoints) {
        allWaypoints.mapNotNull { wp -> matchRoute(preparedRoute,wp.lat to wp.lon)?.let{wp to it.progressMeters} }
    }
    val nextWaypointMatch=remember(waypointRoutePositions,travelled) {
        waypointRoutePositions.filter{it.second>=travelled-20.0}.minByOrNull{it.second}
    }
    val nextWaypoint=nextWaypointMatch?.first ?: closestWaypoint
    val nextWaypointRouteDistance=nextWaypointMatch?.second?.minus(travelled)?.coerceAtLeast(0.0)
    val remaining=(total-travelled).coerceAtLeast(0.0)

    LaunchedEffect(active,gpsStatusTick,voiceGuidance) {
        val stale=active && current!=null && (gpsFixAgeSeconds?:999L)>15L
        if(stale && !gpsStaleWas) {
            gpsStaleWas=true
            speakNavigation("GPS定位已中断，偏航判断暂停，请检查手机定位",true)
        } else if(!stale && gpsStaleWas) {
            gpsStaleWas=false
            speakNavigation("GPS定位已恢复")
        }
    }
    val batteryLevel=(context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf{it in 0..100}
    val gpsSpeed=current?.takeIf{it.hasSpeed() && it.accuracy<=50f && (gpsFixAgeSeconds?:999L)<=15L}?.speed?.toDouble()?.coerceAtLeast(0.0) ?: 0.0
    val remainingAscent=remember(progressMatch?.segmentIndex,navigationElevations) {
        progressMatch?.let { match ->
            navigationElevations.drop(match.segmentIndex).zipWithNext().sumOf{(a,b)->if(a!=null&&b!=null)(b-a).coerceAtLeast(0.0)else 0.0}
        } ?: 0.0
    }
    val hasRemainingElevation=remember(progressMatch?.segmentIndex,navigationElevations,navigationPoints.size) {
        progressMatch!=null && navigationElevations.size==navigationPoints.size && navigationElevations.drop(progressMatch.segmentIndex).count{it!=null}>=2
    }
    val etaSeconds=if(gpsSpeed>=0.8) (remaining/gpsSpeed).toLong() else (remaining/0.70+(if(hasRemainingElevation)remainingAscent/600.0*3600.0 else 0.0)).toLong()
    val etaText=run {
        val hours=etaSeconds/3600;val minutes=(etaSeconds%3600)/60
        (if(hours>0) "${hours}小时${minutes}分" else "${minutes}分钟")+(if(gpsSpeed>=0.8)" · 当前速度" else if(hasRemainingElevation)" · 含爬升估算" else " · 不含爬升")
    }
    val currentElevation=progressMatch?.let { match ->
        val a=navigationElevations.getOrNull(match.segmentIndex)
        val b=navigationElevations.getOrNull(match.segmentIndex+1)
        if(a!=null&&b!=null) a+(b-a)*match.fraction else current?.takeIf{it.hasAltitude()}?.altitude
    } ?: current?.takeIf{it.hasAltitude()}?.altitude
    val currentElevationSource=if(progressMatch!=null && navigationElevations.getOrNull(progressMatch.segmentIndex)!=null && navigationElevations.getOrNull(progressMatch.segmentIndex+1)!=null)"GPX" else "GPS"
    LaunchedEffect(routeMatch,progressMatch,active,reverseRoute) {
        if(progressMatch!=null) {
            confirmedProgress=maxOf(confirmedProgress,progressMatch.progressMeters)
            lastMatchedSegment=progressMatch.segmentIndex
            prefs.edit().putFloat(progressKey,confirmedProgress.toFloat()).putInt(segmentKey,lastMatchedSegment).apply()
        }
        val off=active && routeMatch!=null && (gpsFixAgeSeconds?:999L)<=15L && (current?.accuracy?:999f)<=50f && routeMatch.distanceMeters>threshold+(current?.accuracy?:0f)
        if(off && !offRouteWas && System.currentTimeMillis()-lastVibrationAt>15000L) {
            try {
                val vibrator=context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if(Build.VERSION.SDK_INT>=26) vibrator.vibrate(VibrationEffect.createOneShot(260L,VibrationEffect.DEFAULT_AMPLITUDE))
                else vibrator.vibrate(260L)
                lastVibrationAt=System.currentTimeMillis()
                speakNavigation("警告，已偏离 GPX 徒步路线，请核对地图和当前位置",true)
            } catch(_:Exception) {}
        }
        if(off) offRouteWas=true
        else if(routeMatch==null || routeMatch.distanceMeters<threshold*0.7) offRouteWas=false
    }
    val recordingSeconds=if(isRecording) recordingClockTick else 0L
    val recordingElapsedText=String.format(Locale.CHINA,"%02d:%02d",recordingSeconds/60,recordingSeconds%60)
    DisposableEffect(context,visible) {
        val manager=context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor=manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        @Suppress("DEPRECATION")
        val fallbackSensor=if(rotationSensor==null) manager.getDefaultSensor(Sensor.TYPE_ORIENTATION) else null
        val sensor=rotationSensor ?: fallbackSensor
        var lastPublishAt=0L
        var lastPublished=Float.NaN
        val listener=object:SensorEventListener {
            override fun onSensorChanged(event:SensorEvent) {
                val raw=if(event.sensor.type==Sensor.TYPE_ROTATION_VECTOR) {
                    val matrix=FloatArray(9);val orientation=FloatArray(3)
                    SensorManager.getRotationMatrixFromVector(matrix,event.values)
                    SensorManager.getOrientation(matrix,orientation)
                    Math.toDegrees(orientation[0].toDouble()).toFloat()
                } else event.values[0]
                val normalized=(raw+360f)%360f
                val now=android.os.SystemClock.elapsedRealtime()
                val difference=if(lastPublished.isNaN()) 360f else kotlin.math.abs((normalized-lastPublished+540f)%360f-180f)
                // Sensor callbacks can be frequent. Avoid recomposing the whole navigation sheet
                // for tiny compass noise; publish meaningful heading changes at most 4 times/sec.
                if(now-lastPublishAt>=250L && (lastPublished.isNaN() || difference>=2f)) {
                    lastPublishAt=now
                    lastPublished=normalized
                    heading=normalized
                }
            }
            override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int) {
                if(compassAccuracy!=accuracy) compassAccuracy=accuracy
            }
        }
        if(visible && sensor!=null) manager.registerListener(listener,sensor,SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { manager.unregisterListener(listener) }
    }
    if(visible) Dialog(onDismissRequest={onClose()}) {
        Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth().heightIn(max=780.dp)) {
            Column(Modifier.padding(14.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if(active)"轨迹跟随导航" else info?.name?:"离线地图导航",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                        Text(if(info!=null)"离线地形 · ${info.format.uppercase()} · ${info.tileCount} 瓦片 · 缩放 ${info.minZoom}–${info.maxZoom}" else "轨迹导航模式 · 尚未导入离线地图",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.secondary)
                    }
                    TextButton(onClick={onClose}){Text("关闭")}
                }
                if(db==null || info==null) {
                    Surface(color=Color(0xFFFFF3DD),shape=RoundedCornerShape(10.dp)) {
                        Text("当前没有可读取的 MBTiles 地图。导入地图包后，这里会显示真实地形；现在仍可使用 GPS + GPX 轨迹进行基础跟随导航。",Modifier.padding(10.dp),fontSize=12.sp,color=Color(0xFF76521D))
                    }
                }
                sampledRouteTileCoverage?.let { (missing,total,parentFallback) ->
                    when {
                        missing>0 -> Surface(color=Color(0xFFFFF3DD),shape=RoundedCornerShape(10.dp)) {
                            Text("当前缩放级别抽样检查：路线附近有 $missing/$total 个瓦片缺失。请更换更完整的离线地图或检查缩放级别。",Modifier.padding(10.dp),fontSize=11.sp,color=Color(0xFF76521D))
                        }
                        parentFallback>0 -> Text("路线瓦片覆盖：$total/$total；其中 $parentFallback 个位置使用低级瓦片放大，局部可能较模糊。",fontSize=10.sp,color=Color(0xFF9A6A25))
                        else -> Text("当前缩放级别路线瓦片抽样完整：$total/$total",fontSize=10.sp,color=Color(0xFF23734D))
                    }
                }
                if(info!=null && info.tileCount==0) Surface(color=Color(0xFFFFE8E4),shape=RoundedCornerShape(10.dp)) {
                    Text("MBTiles 的 tiles 表为空，没有可显示的地图瓦片。请检查地图包是否完整。",Modifier.padding(10.dp),fontSize=12.sp,color=Color(0xFF9B3E32))
                }
                if(info!=null && info.format.lowercase(Locale.ROOT) in listOf("pbf","mvt")) Surface(color=Color(0xFFFFF3DD),shape=RoundedCornerShape(10.dp)) {
                    Text("当前是矢量瓦片格式（${info.format.uppercase()}），此版本仅支持 PNG/JPEG/WebP 栅格 MBTiles。",Modifier.padding(10.dp),fontSize=12.sp,color=Color(0xFF76521D))
                }
                if(mapCoverage==false) Surface(color=Color(0xFFFFE8E4),shape=RoundedCornerShape(10.dp)) {
                    Text("地图覆盖范围可能不完整：GPX 路线范围超出 MBTiles bounds。请补充地图区域，不能把空白区域当作无路地形。",Modifier.padding(10.dp),fontSize=12.sp,color=Color(0xFF9B3E32))
                }
                else if(db!=null && mapCoverage==null) Text("离线地图没有有效 bounds 元数据，无法自动核验是否覆盖整条 GPX 路线。",fontSize=10.sp,color=Color(0xFF9A6A25))
                if (navigationPoints.size >= 2) {
                    RouteOfflineDownloadCard(navigationPoints, projectId, mapLayer, { mapLayer = it })
                } else {
                    Text("导入 GPX 轨迹后，可按路线范围下载离线地图。", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected=precisionMode,onClick={
                        precisionMode=!precisionMode
                        prefs.edit().putBoolean("precision_mode_$projectId",precisionMode).apply()
                        zoom=zoom.coerceAtMost(if(precisionMode)20f else 18f)
                    },label={Text(if(precisionMode)"精确模式·开" else "精确模式")})
                    Text(if(precisionMode)"放大上限 20 级；超出地图原始细节时可能只是放大现有数据" else "普通模式：最高 18 级",fontSize=10.sp,color=MaterialTheme.colorScheme.secondary,modifier=Modifier.align(Alignment.CenterVertically))
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected=mapOrientation=="north",onClick={mapOrientation="north";prefs.edit().putString("orientation_$projectId","north").apply()},label={Text("北向上")})
                    FilterChip(selected=mapOrientation=="heading",onClick={mapOrientation="heading";prefs.edit().putString("orientation_$projectId","heading").apply()},label={Text("航向朝上")})
                    FilterChip(selected=mapOrientation=="track",onClick={mapOrientation="track";prefs.edit().putString("orientation_$projectId","track").apply()},label={Text("轨迹朝上")})
                }
                Text(when(mapOrientation) { "north" -> "北向上：地图始终保持北方朝上" ; "heading" -> if(arrowHeading.isNaN()) "航向朝上：等待可靠电子罗盘或 GPS 行进方向" else "航向朝上：根据手机朝向旋转地图" ; else -> if(routeMatch==null) "轨迹朝上：等待高精度 GPS 匹配路线，暂用罗盘方向" else "轨迹朝上：根据前方路线方向旋转地图" },fontSize=10.sp,color=MaterialTheme.colorScheme.secondary)
                Box(Modifier.fillMaxWidth().height(360.dp)) {
                    MapLibreBaseMap(
                        centerLat = centerLat,
                        centerLon = centerLon,
                        zoom = zoom,
                        mapBearing = mapBearing,
                        styleUrl = activeMapStyle,
                        onCameraChanged = { lat, lon, z ->
                            centerLat = lat
                            centerLon = lon
                            zoom = z.coerceIn(2f, zoomLimit)
                        },
                        onTap = { lat, lon -> if (measureMode) measurePoints.add(lat to lon) },
                        onProjectionChanged = { projector -> mapProjector = projector },
                        onLongPress = { lat, lon ->
                            val coords = String.format(Locale.US, "%.6f, %.6f", lat, lon)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("地图坐标", coords))
                            addCustomWaypoint(lat, lon)
                            android.widget.Toast.makeText(context, "坐标：$coords", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    )
                    Canvas(Modifier.matchParentSize()) {
                    val canvas=drawContext.canvas.nativeCanvas
                    // Compose Canvas is already transparent; clearing its native layer can
                    // expose the underlying MapView/window and cause visible control flicker.
                    val z=if(info!=null)zoom.roundToInt().coerceIn(info.minZoom,info.maxZoom) else 14
                    val scale=2.0.pow((zoom-z).toDouble()).toFloat()
                    val cx=worldX(centerLon,z);val cy=worldY(centerLat,z)
                    val left=cx-size.width/(2.0*scale);val top=cy-size.height/(2.0*scale)
                    fun project(lat:Double,lon:Double):android.graphics.PointF = mapProjector?.invoke(lat,lon) ?: android.graphics.PointF(((worldX(lon,z)-left)*scale).toFloat(),((worldY(lat,z)-top)*scale).toFloat())
                    // Rotate the raster MBTiles and graticule with the map camera.
                    // Route/waypoint overlays below already use MapLibre's rotated projection.
                    canvas.save()
                    canvas.rotate(-mapBearing,size.width/2f,size.height/2f)
                    if(db!=null&&info!=null) {
                        val minX=floor(left/256.0).toInt();val maxX=floor((left+size.width/scale)/256.0).toInt()
                        val minY=floor(top/256.0).toInt();val maxY=floor((top+size.height/scale)/256.0).toInt()
                        val paint=Paint(Paint.FILTER_BITMAP_FLAG)
                        for(tx in minX..maxX)for(ty in minY..maxY){
                            if(ty !in 0 until (1 shl z))continue
                            val wrapped=((tx%(1 shl z))+(1 shl z))%(1 shl z)
                            try {
                                val bitmap=loadMbTile(db,z,wrapped,ty,info.minZoom, tileCache)
                                if(bitmap!=null) {
                                    val x=((tx*256.0-left)*scale).toFloat();val y=((ty*256.0-top)*scale).toFloat()
                                    canvas.drawBitmap(bitmap,null,RectF(x,y,x+256f*scale,y+256f*scale),paint)
                                }
                            } catch(_:Exception) {}
                        }
                    }
                    // WGS84 graticule for compass-and-map coordinate reading.
                    run {
                        val interval=when {
                            zoom>=16f -> 0.002
                            zoom>=14f -> 0.005
                            zoom>=12f -> 0.01
                            zoom>=10f -> 0.05
                            zoom>=8f -> 0.1
                            zoom>=6f -> 0.25
                            zoom>=4f -> 1.0
                            zoom>=2f -> 5.0
                            else -> 10.0
                        }
                        fun inverseY(pixel:Double):Double {
                            val n=Math.PI-2.0*Math.PI*pixel/(256.0*(1 shl z))
                            return Math.toDegrees(atan(sinh(n)))
                        }
                        fun screen(lon:Double,lat:Double)=android.graphics.PointF(((worldX(lon,z)-left)*scale).toFloat(),((worldY(lat,z)-top)*scale).toFloat())
                        val minLon=left/(256.0*(1 shl z))*360.0-180.0
                        val maxLon=(left+size.width/scale)/(256.0*(1 shl z))*360.0-180.0
                        val maxLat=inverseY(top)
                        val minLat=inverseY(top+size.height/scale)
                        val grid=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.argb(95,65,91,78);strokeWidth=1f;style=Paint.Style.STROKE}
                        val label=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(54,76,63);textSize=18f;typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)}
                        val lonStart=floor(minLon/interval).toInt()
                        val lonEnd=ceil(maxLon/interval).toInt()
                        for(k in lonStart..lonEnd) {
                            val lon=k*interval
                            val p1=screen(lon,minLat);val p2=screen(lon,maxLat)
                            canvas.drawLine(p1.x,p1.y,p2.x,p2.y,grid)
                            if(p1.x in 0f..size.width) canvas.drawText(String.format(Locale.US,"%.3f°%s",kotlin.math.abs(lon),if(lon>=0)"E" else "W"),p1.x+3f,size.height-7f,label)
                        }
                        val latStart=floor(minLat/interval).toInt()
                        val latEnd=ceil(maxLat/interval).toInt()
                        for(k in latStart..latEnd) {
                            val lat=k*interval
                            val p1=screen(minLon,lat);val p2=screen(maxLon,lat)
                            canvas.drawLine(p1.x,p1.y,p2.x,p2.y,grid)
                            if(p1.y in 18f..size.height) canvas.drawText(String.format(Locale.US,"%.3f°%s",kotlin.math.abs(lat),if(lat>=0)"N" else "S"),5f,p1.y-4f,label)
                        }
                    }
                    canvas.restore()
                    if(measurePoints.isNotEmpty()) {
                        val measurePaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(36,105,190);strokeWidth=4f;style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND}
                        val measurePath=AndroidPath()
                        measurePoints.forEachIndexed { index,p ->
                            val projected=project(p.first,p.second);val x=projected.x
                            val y=projected.y
                            if(index==0)measurePath.moveTo(x,y) else measurePath.lineTo(x,y)
                            val dot=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(36,105,190);style=Paint.Style.FILL}
                            canvas.drawCircle(x,y,6f,dot)
                            val number=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(35,69,118);textSize=22f;typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)}
                            val halo=Paint(number).apply{color=android.graphics.Color.WHITE;style=Paint.Style.STROKE;strokeWidth=5f}
                            canvas.drawText("${index+1}",x+9f,y-8f,halo);canvas.drawText("${index+1}",x+9f,y-8f,number)
                        }
                        canvas.drawPath(measurePath,measurePaint)
                    }
                    if(navigationPoints.isNotEmpty()) {
                        fun point(p:Pair<Double,Double>)=project(p.first,p.second)
                        val rp=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=5f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
                        val snapped=progressMatch?.let { project(it.snappedLat,it.snappedLon) }
                        val remainingPath=AndroidPath()
                        val firstRemaining=snapped?:point(navigationPoints[nearest])
                        remainingPath.moveTo(firstRemaining.x,firstRemaining.y)
                        for(i in (nearest+1)..navigationPoints.lastIndex) { val q=point(navigationPoints[i]);remainingPath.lineTo(q.x,q.y) }
                        rp.color=android.graphics.Color.rgb(218,145,54)
                        if(nearest<navigationPoints.lastIndex)canvas.drawPath(remainingPath,rp)
                        val passedPath=AndroidPath()
                        navigationPoints.take(nearest+1).forEachIndexed{i,p->val q=point(p);if(i==0)passedPath.moveTo(q.x,q.y)else passedPath.lineTo(q.x,q.y)}
                        if(snapped!=null)passedPath.lineTo(snapped.x,snapped.y)
                        rp.color=android.graphics.Color.rgb(30,125,78)
                        if(nearest>0 || snapped!=null)canvas.drawPath(passedPath,rp)
                        // Direction chevrons are positioned by route distance, not point count.
                        // navigationPoints already follows the selected navigation direction, so
                        // reversing a route automatically reverses every arrow as well.
                        val routeCumulative=DoubleArray(navigationPoints.size)
                        for(i in 1 until navigationPoints.size) {
                            routeCumulative[i]=routeCumulative[i-1]+mapDistance(navigationPoints[i-1],navigationPoints[i])
                        }
                        val routeLength=routeCumulative.lastOrNull()?:0.0
                        if(routeLength>30.0 && navigationPoints.size>=2) {
                            val metersPerPixel=156543.03392*cos(Math.toRadians(centerLat)).coerceAtLeast(0.01)/2.0.pow(zoom.toDouble())
                            val arrowSpacing=minOf((metersPerPixel*115.0).coerceIn(160.0,3000.0),(routeLength/2.0).coerceAtLeast(1.0))
                            val arrowFill=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(24,58,48);style=Paint.Style.FILL}
                            val arrowOutline=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.WHITE;style=Paint.Style.STROKE;strokeWidth=4.5f;strokeJoin=Paint.Join.ROUND;strokeCap=Paint.Cap.ROUND}
                            val arrowShape=AndroidPath().apply {
                                moveTo(0f,-11f)
                                lineTo(-7f,7f)
                                lineTo(0f,3f)
                                lineTo(7f,7f)
                                close()
                            }
                            var segmentIndex=0
                            var targetDistance=arrowSpacing/2.0
                            while(targetDistance<routeLength && segmentIndex<navigationPoints.lastIndex) {
                                while(segmentIndex<navigationPoints.lastIndex-1 && routeCumulative[segmentIndex+1]<targetDistance) segmentIndex++
                                val segmentDistance=routeCumulative[segmentIndex+1]-routeCumulative[segmentIndex]
                                if(segmentDistance>0.1) {
                                    val fraction=((targetDistance-routeCumulative[segmentIndex])/segmentDistance).coerceIn(0.0,1.0)
                                    val a=point(navigationPoints[segmentIndex])
                                    val b=point(navigationPoints[segmentIndex+1])
                                    val x=a.x+(b.x-a.x)*fraction.toFloat()
                                    val y=a.y+(b.y-a.y)*fraction.toFloat()
                                    val dx=b.x-a.x
                                    val dy=b.y-a.y
                                    if(hypot(dx.toDouble(),dy.toDouble())>1.0 && x in -20f..(size.width+20f) && y in -20f..(size.height+20f)) {
                                        val rotation=Math.toDegrees(atan2(dy.toDouble(),dx.toDouble())).toFloat()+90f
                                        canvas.save()
                                        canvas.rotate(rotation,x,y)
                                        canvas.translate(x,y)
                                        canvas.drawPath(arrowShape,arrowOutline)
                                        canvas.drawPath(arrowShape,arrowFill)
                                        canvas.restore()
                                    }
                                }
                                targetDistance+=arrowSpacing
                            }
                        }
                        val start=point(navigationPoints.first());val end=point(navigationPoints.last())
                        val marker=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(30,130,75);style=Paint.Style.FILL}
                        canvas.drawCircle(start.x,start.y,7f,marker);marker.color=android.graphics.Color.rgb(205,78,58);canvas.drawCircle(end.x,end.y,7f,marker)
                        val endpointText=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(45,58,48);textSize=23f;typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)}
                        val endpointHalo=Paint(endpointText).apply{color=android.graphics.Color.WHITE;style=Paint.Style.STROKE;strokeWidth=5f}
                        canvas.drawText("起点",start.x+10f,start.y-10f,endpointHalo);canvas.drawText("起点",start.x+10f,start.y-10f,endpointText)
                        canvas.drawText("终点",end.x+10f,end.y-10f,endpointHalo);canvas.drawText("终点",end.x+10f,end.y-10f,endpointText)
                    }
                    if(allWaypoints.isNotEmpty()) {
                        val marker=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(235,151,47);style=Paint.Style.FILL}
                        val outline=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.WHITE;style=Paint.Style.STROKE;strokeWidth=2f}
                        val labelFill=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(66,55,39);textSize=26f;typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)}
                        val labelStroke=Paint(labelFill).apply{color=android.graphics.Color.WHITE;style=Paint.Style.STROKE;strokeWidth=5f}
                        var lastLabelX=-1000f;var lastLabelY=-1000f
                        allWaypoints.forEachIndexed { index,wp ->
                            marker.color=if(index<waypoints.size)android.graphics.Color.rgb(235,151,47) else android.graphics.Color.rgb(123,87,164)
                            val projected=project(wp.lat,wp.lon);val x=projected.x
                            val y=projected.y
                            if(x in 0f..size.width && y in 0f..size.height) {
                                canvas.drawCircle(x,y,6f,marker);canvas.drawCircle(x,y,6f,outline)
                                if(zoom>=13f && (kotlin.math.abs(x-lastLabelX)>100f || kotlin.math.abs(y-lastLabelY)>65f)) {
                                    val label=wp.name.take(16)
                                    canvas.drawText(label,x+10f,y-9f,labelStroke);canvas.drawText(label,x+10f,y-9f,labelFill)
                                    lastLabelX=x;lastLabelY=y
                                }
                            }
                        }
                    }
                    // Live GPS track: draw the points collected during this recording session
                    // above the imported route so the hiker can see the path already walked.
                    if(recordedPoints.isNotEmpty()) {
                        val livePath=AndroidPath()
                        recordedPoints.forEachIndexed { index,location ->
                            val q=project(location.latitude,location.longitude)
                            if(index==0) livePath.moveTo(q.x,q.y) else livePath.lineTo(q.x,q.y)
                        }
                        if(recordedPoints.size>=2) {
                            val liveOutline=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color=android.graphics.Color.WHITE
                                style=Paint.Style.STROKE
                                strokeWidth=12f
                                strokeCap=Paint.Cap.ROUND
                                strokeJoin=Paint.Join.ROUND
                            }
                            val liveStroke=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color=android.graphics.Color.rgb(238,91,42)
                                style=Paint.Style.STROKE
                                strokeWidth=7f
                                strokeCap=Paint.Cap.ROUND
                                strokeJoin=Paint.Join.ROUND
                            }
                            canvas.drawPath(livePath,liveOutline)
                            canvas.drawPath(livePath,liveStroke)
                        }
                        val firstLive=recordedPoints.first()
                        val lastLive=recordedPoints.last()
                        val startLive=project(firstLive.latitude,firstLive.longitude)
                        val endLive=project(lastLive.latitude,lastLive.longitude)
                        val liveHalo=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color=android.graphics.Color.WHITE
                            style=Paint.Style.FILL
                        }
                        val liveDot=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color=android.graphics.Color.rgb(238,91,42)
                            style=Paint.Style.FILL
                        }
                        canvas.drawCircle(startLive.x,startLive.y,8f,liveHalo)
                        canvas.drawCircle(startLive.x,startLive.y,5f,liveDot)
                        canvas.drawCircle(endLive.x,endLive.y,11f,liveHalo)
                        canvas.drawCircle(endLive.x,endLive.y,7f,liveDot)
                    }
                    cp?.let {
                        val projected=project(it.first,it.second);val x=projected.x;val y=projected.y
                        val accuracyMeters=current?.accuracy?.toDouble()?:0.0
                        val metersPerPixel=(2.0*Math.PI*6378137.0*cos(Math.toRadians(it.first)).coerceAtLeast(0.01))/(256.0*2.0.pow(zoom.toDouble()))
                        val accuracyRadius=(accuracyMeters/metersPerPixel).toFloat().coerceIn(8f,120f)
                        val stale=(gpsFixAgeSeconds?:999L)>15L
                        val markerColor=if(stale) android.graphics.Color.rgb(120,130,140) else android.graphics.Color.rgb(30,105,210)
                        val accuracyHalo=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=if(stale)android.graphics.Color.argb(28,100,110,120) else android.graphics.Color.argb(45,32,115,210);style=Paint.Style.FILL}
                        canvas.drawCircle(x,y,accuracyRadius,accuracyHalo)
                        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.WHITE;style=Paint.Style.FILL}
                        canvas.drawCircle(x,y,13f,p);p.color=markerColor;canvas.drawCircle(x,y,9f,p)
                        p.color=android.graphics.Color.WHITE;p.style=Paint.Style.STROKE;p.strokeWidth=2f;canvas.drawCircle(x,y,13f,p)
                        if(arrowCanDraw) {
                            // The heading is an absolute true-north bearing. Convert it to
                            // screen-space by subtracting the camera bearing, otherwise the
                            // location arrow appears to change direction when the map rotates.
                            val screenArrowHeading = (arrowHeading - mapBearing + 360f) % 360f
                            val angle=Math.toRadians(screenArrowHeading.toDouble())
                            val ux=sin(angle).toFloat()
                            val uy=(-cos(angle)).toFloat()
                            val tipX=x+ux*56f
                            val tipY=y+uy*56f
                            val direction=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(232,112,35);strokeWidth=6f;strokeCap=Paint.Cap.ROUND;style=Paint.Style.STROKE}
                            canvas.drawLine(x,y,tipX,tipY,direction)
                            val arrow=AndroidPath().apply {
                                moveTo(tipX,tipY)
                                lineTo(tipX-ux*17f+cos(angle).toFloat()*9f,tipY-uy*17f+sin(angle).toFloat()*9f)
                                lineTo(tipX-ux*17f-cos(angle).toFloat()*9f,tipY-uy*17f-sin(angle).toFloat()*9f)
                                close()
                            }
                            val arrowFill=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(232,112,35);style=Paint.Style.FILL}
                            canvas.drawPath(arrow,arrowFill)
                        }
                    }
                    // North-up compass marker and a ground-distance scale bar.
                    val overlay=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(35,54,46);style=Paint.Style.FILL;textSize=22f;typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)}
                    val white=Paint(overlay).apply{color=android.graphics.Color.WHITE}
                    val compassX=size.width-38f;val compassY=42f
                    canvas.drawCircle(compassX,compassY,25f,white)
                    overlay.style=Paint.Style.STROKE;overlay.strokeWidth=3f
                    canvas.drawCircle(compassX,compassY,25f,overlay)
                    overlay.style=Paint.Style.FILL
                    val north=AndroidPath().apply{moveTo(compassX,compassY-17f);lineTo(compassX-7f,compassY+7f);lineTo(compassX,compassY+2f);lineTo(compassX+7f,compassY+7f);close()}
                    canvas.drawPath(north,overlay)
                    canvas.drawText("N",compassX-7f,compassY+43f,overlay)
                    val metersPerPixel=156543.03392*cos(Math.toRadians(centerLat))/2.0.pow(zoom.toDouble())
                    val rawMeters=(metersPerPixel*105.0).coerceAtLeast(1.0)
                    val power=10.0.pow(floor(log10(rawMeters)))
                    val normalized=rawMeters/power
                    val nice=when { normalized>=5.0->5.0;normalized>=2.0->2.0;else->1.0 }*power
                    val bar=(nice/metersPerPixel).toFloat().coerceAtMost(size.width*0.45f)
                    val bx=22f;val by=size.height-25f
                    val back=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=0xCCFFFFFF.toInt();style=Paint.Style.FILL}
                    canvas.drawRoundRect(RectF(bx-10f,by-39f,bx+bar+12f,by+12f),9f,9f,back)
                    overlay.strokeWidth=5f;overlay.strokeCap=Paint.Cap.SQUARE
                    canvas.drawLine(bx,by,bx+bar,by,overlay)
                    canvas.drawLine(bx,by-7f,bx,by+5f,overlay);canvas.drawLine(bx+bar,by-7f,bx+bar,by+5f,overlay)
                    canvas.drawText(if(nice>=1000)String.format(Locale.CHINA,"%.1f km",nice/1000.0) else "${nice.roundToInt()} m",bx,by-13f,overlay)
                    }
                }
                Text("绿线：已走   橙线：剩余   蓝点：当前位置/精度范围   橙色箭头：手机朝向   橙点：GPX途经点 · 紫点：自定义标记（${customWaypoints.size}）",fontSize=11.sp,color=MaterialTheme.colorScheme.secondary)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick={measureMode=!measureMode;if(!measureMode)measurePoints.clear()},modifier=Modifier.weight(1f)){Text(if(measureMode)"结束测距" else "地图测距")}
                    OutlinedButton(onClick={measurePoints.clear()},modifier=Modifier.weight(1f),enabled=measurePoints.isNotEmpty()){Text("清除测距点")}
                    MapStat("测量距离",formatNavDistance(measureDistance))
                }
                if(measureMode) Text("测距模式：点击地图依次添加测量点；蓝线为测量路线。",fontSize=10.sp,color=Color(0xFF2469BE))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    MapStat("速度",if(current?.hasSpeed()==true) String.format(Locale.CHINA,"%.1f km/h",gpsSpeed*3.6) else "--")
                    MapStat("${currentElevationSource} 海拔",currentElevation?.let{"${it.roundToInt()} m"}?:"--")
                    MapStat("位置",when { current==null->"等待定位";(gpsFixAgeSeconds?:0L)>15L->"定位过期";else->"实时 · ±${current!!.accuracy.roundToInt()} m" })
                }
                if(navigationPoints.size>=2) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    MapStat("预计剩余",etaText)
                    MapStat("剩余爬升",if(hasRemainingElevation)" +${remainingAscent.roundToInt()} m" else "无海拔数据")
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick={
                            val loc=current
                            if(loc!=null) {
                                val coords=String.format(Locale.US,"%.6f, %.6f",loc.latitude,loc.longitude)
                                val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("GPS 坐标",coords))
                                android.widget.Toast.makeText(context,"已复制坐标：$coords",android.widget.Toast.LENGTH_SHORT).show()
                            } else android.widget.Toast.makeText(context,"尚未获取 GPS 定位",android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier=Modifier.weight(1f)
                    ) { Text("复制当前位置坐标") }
                    OutlinedButton(
                        onClick={
                            val loc=current?.takeIf { (System.currentTimeMillis()-it.time) in 0L..30000L && it.accuracy<=1500f }
                            if(loc!=null) {
                                val amap=wgs84ToGcj02(loc.latitude,loc.longitude)
                                val uri=android.net.Uri.parse("geo:${amap.first},${amap.second}?q=${amap.first},${amap.second}(TrailKit)")
                                val intent=android.content.Intent(android.content.Intent.ACTION_VIEW,uri)
                                try{context.startActivity(intent)}catch(_:Exception){android.widget.Toast.makeText(context,"没有可打开坐标的地图应用",android.widget.Toast.LENGTH_SHORT).show()}
                            } else android.widget.Toast.makeText(context,"当前 GPS 定位已过期或精度不足，请等待重新定位",android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier=Modifier.weight(1f)
                    ) { Text("用地图查看") }
                }
                if(navigationPoints.size>=2) {
                    if(cp!=null) {
                        val off=deviation?:0.0
                        val gpsAccuracy=current?.accuracy?:999f
                        Text(
                            when {
                                (gpsFixAgeSeconds?:999L)>15L -> "GPS 定位已过期，暂不判断是否偏航"
                                gpsAccuracy>50f -> "定位精度不足（±${gpsAccuracy.roundToInt()} m），暂不判断是否偏航"
                                off>threshold+gpsAccuracy -> "偏航警告：偏离轨迹 ${off.roundToInt()} 米（阈值 ${threshold.roundToInt()} 米）"
                                off>threshold -> "注意：接近偏航阈值，建议核对地图"
                                else -> "路线正常：距轨迹 ${off.roundToInt()} 米"
                            },
                            fontWeight=FontWeight.Bold,
                            color=if((gpsFixAgeSeconds?:999L)>15L || gpsAccuracy>50f)Color(0xFF9A6A25) else if(off>threshold+gpsAccuracy)MaterialTheme.colorScheme.error else Color(0xFF23734D)
                        )
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            MapStat("已走",formatNavDistance(travelled))
                            MapStat("剩余",formatNavDistance(remaining))
                            MapStat("总长",formatNavDistance(total))
                        }
                        LinearProgressIndicator(progress={if(total>0)(travelled/total).toFloat().coerceIn(0f,1f)else 0f},modifier=Modifier.fillMaxWidth())
                        if(elevationStats.third.size>=2 && navigationElevations.size==navigationPoints.size) {
                            val valid=elevationStats.third
                            val minEle=valid.minOrNull()?:0.0
                            val maxEle=valid.maxOrNull()?:0.0
                            Text("海拔剖面",fontWeight=FontWeight.Bold,fontSize=14.sp)
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                                Text("爬升 +${elevationStats.first.roundToInt()} m",fontSize=11.sp,color=Color(0xFF23734D))
                                Text("下降 −${elevationStats.second.roundToInt()} m",fontSize=11.sp,color=Color(0xFFB45B32))
                                Text("最高 ${maxEle.roundToInt()} m",fontSize=11.sp,color=MaterialTheme.colorScheme.secondary)
                            }
                            Canvas(Modifier.fillMaxWidth().height(112.dp)) {
                                val canvas=drawContext.canvas.nativeCanvas
                                val bg=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(239,243,236)}
                                canvas.drawRoundRect(RectF(0f,0f,size.width,size.height),12f,12f,bg)
                                val padX=12f;val padY=12f
                                val span=(maxEle-minEle).coerceAtLeast(1.0)
                                val cumulative=elevationCumulative
                                val distanceTotal=cumulative.lastOrNull()?.coerceAtLeast(1.0) ?: 1.0
                                val grid=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(211,220,210);strokeWidth=1f}
                                for(i in 0..3){val y=padY+(size.height-2*padY)*i/3f;canvas.drawLine(padX,y,size.width-padX,y,grid)}
                                val line=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(35,112,77);style=Paint.Style.STROKE;strokeWidth=4f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
                                val path=AndroidPath();var started=false
                                navigationElevations.forEachIndexed { i,value ->
                                    if(value==null) started=false else {
                                        val x=padX+(cumulative[i]/distanceTotal).toFloat()*(size.width-2*padX)
                                        val y=(size.height-padY)-((value-minEle)/span).toFloat()*(size.height-2*padY)
                                        if(!started){path.moveTo(x,y);started=true}else path.lineTo(x,y)
                                    }
                                }
                                canvas.drawPath(path,line)
                                routeMatch?.let { match ->
                                    val a=navigationElevations.getOrNull(match.segmentIndex)
                                    val b=navigationElevations.getOrNull(match.segmentIndex+1)
                                    if(a!=null&&b!=null) {
                                        val distanceAt=cumulative[match.segmentIndex]+(cumulative[match.segmentIndex+1]-cumulative[match.segmentIndex])*match.fraction
                                        val x=padX+(distanceAt/distanceTotal).toFloat()*(size.width-2*padX)
                                        val elevation=a+(b-a)*match.fraction
                                        val y=(size.height-padY)-((elevation-minEle)/span).toFloat()*(size.height-2*padY)
                                        val marker=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(218,91,48);style=Paint.Style.FILL}
                                        val halo=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.WHITE;style=Paint.Style.FILL}
                                        canvas.drawCircle(x,y,8f,halo);canvas.drawCircle(x,y,5f,marker)
                                    }
                                }
                                val label=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(76,91,79);textSize=22f}
                                canvas.drawText("${maxEle.roundToInt()} m",padX+4f,25f,label)
                                canvas.drawText("${minEle.roundToInt()} m",padX+4f,size.height-5f,label)
                            }
                        } else {
                            Text("海拔剖面：当前 GPX 未提供完整的逐点海拔数据",fontSize=11.sp,color=MaterialTheme.colorScheme.secondary)
                        }
                    } else Text(if(active)"正在等待手机定位；室内可先使用 Wi-Fi / 基站估算位置，户外开阔处再等待高精度 GPS。" else "导入 GPX 后即可开始沿轨迹导航。",fontSize=12.sp,color=MaterialTheme.colorScheme.secondary)
                    Text("偏航提醒距离：${threshold.roundToInt()} 米",fontSize=12.sp,fontWeight=FontWeight.SemiBold)
                    Slider(value=threshold,onValueChange={threshold=it},onValueChangeFinished={prefs.edit().putInt("offroute_$projectId",threshold.roundToInt()).apply()},valueRange=20f..300f,steps=27)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("20 米",fontSize=10.sp,color=MaterialTheme.colorScheme.secondary);Text("300 米",fontSize=10.sp,color=MaterialTheme.colorScheme.secondary)}
                } else Text("当前项目没有有效 GPX 轨迹，请先导入 GPX 文件。",fontSize=12.sp,color=MaterialTheme.colorScheme.error)
                if(gpsError.isNotBlank())Text(gpsError,fontSize=12.sp,color=MaterialTheme.colorScheme.error)
                if((gpsFixAgeSeconds?:0L)>15L) Text("定位过期，地图保留灰色最后位置；导航进度和偏航判断已暂停，收到新定位后自动恢复。",fontSize=11.sp,color=Color(0xFF9A6A25))
                if(current!=null) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        MapStat("GPS 精度","±${current!!.accuracy.roundToInt()} m")
                        MapStat("GPS 海拔",if(current!!.hasAltitude())"%.0f m".format(Locale.CHINA,current!!.altitude) else "暂无")
                        MapStat("速度",if(current!!.hasSpeed())"%.1f km/h".format(Locale.CHINA,current!!.speed*3.6f) else "暂无")
                    }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    MapStat("手机电量",batteryLevel?.let{"$it%"}?:"未知")
                    MapStat("记录时长",if(isRecording)recordingElapsedText else "未记录")
                    MapStat("GPS 更新",gpsFixAgeSeconds?.let{if(it<60L)"${it} 秒前" else "${it/60} 分钟前"}?:"无定位")
                }
                if((batteryLevel?:100)<=20) Text("电量不足 20%：建议开启省电模式、降低屏幕亮度，并准备备用电源。",fontSize=11.sp,color=Color(0xFF9A6A25))
                if(nextTurnInfo!=null) {
                    val (direction,distance,angle)=nextTurnInfo
                    Text("前方约 ${distance.roundToInt()} 米 · $direction ${angle.roundToInt()}°",fontWeight=FontWeight.Bold,fontSize=13.sp,color=Color(0xFF8B5A22))
                } else if(routeMatch!=null && (gpsFixAgeSeconds?:999L)>15L) {
                    Text("GPS 定位过期，暂停转弯判断",fontSize=11.sp,color=Color(0xFF9A6A25))
                } else if(routeMatch!=null && (current?.accuracy?:999f)>50f) {
                    Text("GPS 精度不足，暂停转弯判断",fontSize=11.sp,color=Color(0xFF9A6A25))
                } else if(routeMatch!=null) {
                    Text("前方 500 米内无明显转弯",fontSize=11.sp,color=MaterialTheme.colorScheme.secondary)
                } else {
                    Unit
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    MapStat("真北方位",when { heading.isNaN()->"无传感器";compassAccuracy==SensorManager.SENSOR_STATUS_UNRELIABLE->"需校准";compassAccuracy==SensorManager.SENSOR_STATUS_ACCURACY_LOW->"精度较低";else->"${directionLabel(trueHeading)} ${trueHeading.roundToInt()}°" })
                    MapStat("路线方向",routeMatch?.let{"${directionLabel(it.bearingDegrees.toFloat())} ${it.bearingDegrees.roundToInt()}°"}?:"--")
                    MapStat("方向提示",routeMatch?.let{turnGuidance(reliableTrueHeading,it.bearingDegrees.toFloat())}?:"等待定位")
                }
                if(compassAccuracy==SensorManager.SENSOR_STATUS_UNRELIABLE || compassAccuracy==SensorManager.SENSOR_STATUS_ACCURACY_LOW) {
                    Text("罗盘精度不足：远离手机磁吸配件、磁铁和金属物；按手机提示做 8 字形校准，并以纸质指北针复核。",fontSize=10.sp,color=Color(0xFF9A6A25))
                }
                else if(!heading.isNaN()) Text("已按当前位置磁偏角校正为真北 · 磁偏角 ${if(magneticDeclination>=0)"+" else ""}${String.format(Locale.CHINA,"%.1f",magneticDeclination)}°",fontSize=10.sp,color=MaterialTheme.colorScheme.secondary)
                if(current!=null) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        MapStat("当前位置",String.format(Locale.US,"%.5f, %.5f",current!!.latitude,current!!.longitude))
                        OutlinedButton(onClick={
                            val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("GPS 坐标",String.format(Locale.US,"%.6f, %.6f",current!!.latitude,current!!.longitude)))
                            android.widget.Toast.makeText(context,"坐标已复制",android.widget.Toast.LENGTH_SHORT).show()
                        }) { Text("复制坐标") }
                    }
                }
                utmCoordinate?.let { utm ->
                    val utmText=String.format(Locale.US,"%02d%c  E %.0f  N %.0f",utm.zone,utm.hemisphere,utm.easting,utm.northing)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        MapStat("UTM 公制网格",utmText)
                        OutlinedButton(onClick={
                            val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("UTM",utmText))
                            android.widget.Toast.makeText(context,"UTM 坐标已复制",android.widget.Toast.LENGTH_SHORT).show()
                        }) { Text("复制 UTM") }
                    }
                }
                if(current!=null) OutlinedButton(onClick={
                    val loc=current!!
                    val dms="${formatDms(loc.latitude,true)}, ${formatDms(loc.longitude,false)}"
                    val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("DMS 坐标",dms))
                    android.widget.Toast.makeText(context,"已复制度分秒坐标",android.widget.Toast.LENGTH_SHORT).show()
                },modifier=Modifier.fillMaxWidth()) { Text("复制度分秒坐标（DMS）") }
                if(current!=null) OutlinedButton(onClick={
                    val loc=current!!
                    addCustomWaypoint(loc.latitude,loc.longitude)
                },modifier=Modifier.fillMaxWidth()) { Text("将当前位置保存为标记点") }
                if(current!=null) OutlinedButton(onClick={
                    val loc=current!!
                    val coordinates=String.format(Locale.US,"%.6f, %.6f",loc.latitude,loc.longitude)
                    val dms="${formatDms(loc.latitude,true)}, ${formatDms(loc.longitude,false)}"
                    val utm=toUtm(loc.latitude,loc.longitude)?.let{"${it.zone}${it.hemisphere} E ${it.easting.roundToInt()} N ${it.northing.roundToInt()}"}?:"超出 UTM 覆盖范围"
                    val mapUrl="https://www.openstreetmap.org/?mlat=${loc.latitude}&mlon=${loc.longitude}#map=16/${loc.latitude}/${loc.longitude}"
                    val message="我的 TrailKit 徒步位置：$coordinates\nDMS：$dms\nUTM：$utm\nOpenStreetMap：$mapUrl\nGPS 精度：±${loc.accuracy.roundToInt()} 米\n定位时间：${gpsFixAgeSeconds?:0L} 秒前\n请根据现场情况协助确认位置。"
                    val send=android.content.Intent(android.content.Intent.ACTION_SEND).apply{type="text/plain";putExtra(android.content.Intent.EXTRA_SUBJECT,"徒步位置求助");putExtra(android.content.Intent.EXTRA_TEXT,message)}
                    context.startActivity(android.content.Intent.createChooser(send,"分享当前位置"))
                },modifier=Modifier.fillMaxWidth()) { Text("分享当前位置 / 求助坐标") }
                if(nextWaypoint!=null) {
                    val waypoint=nextWaypoint
                    val waypointPoint=waypoint.lat to waypoint.lon
                    val currentPoint=cp
                    if(currentPoint!=null && (current?.accuracy?:999f)<=50f && (gpsFixAgeSeconds?:999L)<=15L) {
                        val waypointDistance=mapDistance(currentPoint,waypointPoint)
                        val waypointBearing=bearingToPoint(currentPoint,waypointPoint)
                        Text(
                            if(waypointDistance<=30.0 && current?.accuracy?.let{it<=25f}==true) "已到达途经点：${waypoint.name}"
                            else "下一途经点：${waypoint.name} · 直线 ${formatNavDistance(waypointDistance)} · 路线 ${nextWaypointRouteDistance?.let(::formatNavDistance)?:"--"} · 方位 ${directionLabel(waypointBearing.toFloat())} ${waypointBearing.roundToInt()}°",
                            fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=Color(0xFF8C602C)
                        )
                        if(waypointDistance>30.0) Text("前往途经点方向：${turnGuidance(reliableTrueHeading,waypointBearing.toFloat())}",fontSize=11.sp,color=MaterialTheme.colorScheme.secondary)
                    } else {
                        Text(if(currentPoint==null) "下一途经点：${waypoint.name} · 等待定位后显示方向" else "下一途经点：${waypoint.name} · 当前是粗略位置，不显示距离和方向，避免误导",fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=Color(0xFF8C602C))
                    }
                }
                if(navigationPoints.size>=2) OutlinedButton(onClick={exportRouteGpx.launch("TrailKit-路线${if(reverseRoute)"-反向" else ""}.gpx")},modifier=Modifier.fillMaxWidth()) { Text("导出当前路线 GPX（含海拔与途经点）") }
                if(allWaypoints.isNotEmpty()) {
                    TextButton(onClick={showWaypointList=!showWaypointList},modifier=Modifier.fillMaxWidth()) {
                        Text(if(showWaypointList)"收起标记列表（${allWaypoints.size}）" else "查看全部标记与途经点（${allWaypoints.size}）")
                    }
                    if(showWaypointList) allWaypoints.forEachIndexed { index,wp ->
                        val coordinateText=String.format(Locale.US,"%.5f, %.5f",wp.lat,wp.lon)
                        val arrived=announcedWaypoints.contains(index)
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick={centerLat=wp.lat;centerLon=wp.lon;followGps=false},modifier=Modifier.weight(1f)) {
                                Text("${if(arrived)"✓ " else ""}${index+1}. ${wp.name}  ·  $coordinateText",fontSize=11.sp)
                            }
                            OutlinedButton(onClick={
                                val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText(wp.name,String.format(Locale.US,"%.6f, %.6f",wp.lat,wp.lon)))
                                android.widget.Toast.makeText(context,"已复制 ${wp.name} 坐标",android.widget.Toast.LENGTH_SHORT).show()
                            }) { Text("复制") }
                            if(index>=waypoints.size) OutlinedButton(onClick={
                                editingCustomWaypoint=wp
                                waypointNameDraft=wp.name
                            }) { Text("改名") }
                            if(index>=waypoints.size) OutlinedButton(onClick={
                                customWaypoints.remove(wp)
                                persistCustomWaypoints()
                                announcedWaypoints.clear()
                            }) { Text("删除") }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick={
                        val nextReverse=!reverseRoute
                        val nextProgressKey="progress_${projectId}_${encoded.hashCode()}_${if(nextReverse)"rev" else "fwd"}"
                        val nextSegmentKey="segment_${projectId}_${encoded.hashCode()}_${if(nextReverse)"rev" else "fwd"}"
                        context.getSharedPreferences("trailkit_navigation",Context.MODE_PRIVATE).edit()
                            .putBoolean("reverse_$projectId",nextReverse).putFloat(nextProgressKey,0f).putInt(nextSegmentKey,0).apply()
                        reverseRoute=nextReverse;confirmedProgress=0.0;lastMatchedSegment=0;offRouteWas=false;announcedWaypoints.clear();announcedApproaches.clear();announcedTurns.clear();lastTurnAnnouncementProgress=-1000.0
                    },modifier=Modifier.weight(1f),enabled=navigationPoints.size>=2){Text(if(reverseRoute)"反向路线：已开" else "反向导航")}
                    OutlinedButton(onClick={followGps=!followGps},modifier=Modifier.weight(1f)){Text(if(followGps)"自动跟随：开" else "自动跟随：关")}
                    OutlinedButton(onClick={if(current!=null){centerLat=current!!.latitude;centerLon=current!!.longitude;followGps=true}else if(navigationPoints.isNotEmpty()){centerLat=navigationPoints.first().first;centerLon=navigationPoints.first().second}} ,modifier=Modifier.weight(1f)){Text("回到当前位置")}
                }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(checked=voiceGuidance,onCheckedChange={
                        voiceGuidance=it
                        prefs.edit().putBoolean("voice_$projectId",it).apply()
                        if(it && voiceReady.value) speakNavigation("语音导航已开启")
                    })
                    Text("语音播报转弯、途经点、偏航和 GPS 异常",fontSize=12.sp,modifier=Modifier.weight(1f))
                    if(voiceGuidance && voiceReady.value) TextButton(onClick={speakNavigation("TrailKit 语音导航测试正常")}){Text("测试语音")}
                    if(voiceGuidance && !voiceReady.value) Text(if(voiceInitComplete.value)"语音不可用" else "语音启动中",fontSize=10.sp,color=Color(0xFF9A6A25))
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick={zoom=(zoom-1f).coerceAtLeast((info?.minZoom?:3).toFloat())},modifier=Modifier.weight(1f)){Text("− 缩小")}
                    OutlinedButton(onClick={zoom=(zoom+1f).coerceAtMost(zoomLimit)},modifier=Modifier.weight(1f)){Text("+ 放大")}
                    OutlinedButton(onClick={confirmedProgress=0.0;lastMatchedSegment=0;offRouteWas=false;announcedWaypoints.clear();announcedApproaches.clear();announcedTurns.clear();lastTurnAnnouncementProgress=-1000.0;prefs.edit().putFloat(progressKey,0f).putInt(segmentKey,0).apply()},modifier=Modifier.weight(1f)){Text("重置进度")}
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick={
                        if(navigationPoints.size>=2) {
                            val minLat=navigationPoints.minOf{it.first};val maxLat=navigationPoints.maxOf{it.first}
                            val minLon=navigationPoints.minOf{it.second};val maxLon=navigationPoints.maxOf{it.second}
                            centerLat=(minLat+maxLat)/2.0;centerLon=(minLon+maxLon)/2.0
                            val dx=(worldX(maxLon,0)-worldX(minLon,0)).coerceAtLeast(1.0)
                            val dy=(worldY(maxLat,0)-worldY(minLat,0)).coerceAtLeast(1.0)
                            val width=context.resources.displayMetrics.widthPixels*0.86
                            val height=310.0*context.resources.displayMetrics.density
                            val fitScale=min((width-80.0)/dx,(height-80.0)/dy).coerceAtLeast(1.0)
                            zoom=(ln(fitScale)/ln(2.0)).toFloat().coerceIn((info?.minZoom?:3).toFloat(),zoomLimit)
                            followGps=false
                        }
                    },modifier=Modifier.weight(1f),enabled=navigationPoints.size>=2){Text("全览整条路线")}
                    OutlinedButton(onClick={
                        if(navigationPoints.isNotEmpty()) {
                            centerLat=navigationPoints.first().first;centerLon=navigationPoints.first().second
                            zoom=(info?.centerZoom?:14f).coerceIn((info?.minZoom?:3).toFloat(),(info?.maxZoom?:18).toFloat())
                            followGps=false
                        }
                    },modifier=Modifier.weight(1f),enabled=navigationPoints.isNotEmpty()){Text("定位路线起点")}
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick={
                        if(isRecording) {
                            isRecording=false
                            hasSavedRecording=true
                            try { context.startService(android.content.Intent(context,TrailNavigationService::class.java).setAction(TrailNavigationService.ACTION_STOP_RECORDING).putExtra(TrailNavigationService.EXTRA_PROJECT_ID,projectId)) } catch(_:Exception) {}
                            if(recordedPoints.size>=2) exportGpx.launch("TrailKit-${System.currentTimeMillis()}.gpx")
                            else gpsError="记录点不足，至少需要两个定位点才能导出 GPX"
                        } else {
                            if(!active) gpsError="请先开启导航定位，再开始记录轨迹"
                            else {
                                recordedPoints.clear();recordingClockTick=0L;isRecording=true;hasSavedRecording=false
                                try { context.startService(android.content.Intent(context,TrailNavigationService::class.java).setAction(TrailNavigationService.ACTION_START_RECORDING).putExtra(TrailNavigationService.EXTRA_PROJECT_ID,projectId)) } catch(_:Exception) { isRecording=false;gpsError="无法启动轨迹记录服务" }
                            }
                        }
                    },modifier=Modifier.weight(1f),enabled=active || isRecording) { Text(if(isRecording)"停止并导出 GPX（${recordedPoints.size}点）" else "开始记录新轨迹") }
                    OutlinedButton(onClick={recordedPoints.clear();hasSavedRecording=false;projectTrackFile(context,projectId).delete()},modifier=Modifier.weight(1f),enabled=!isRecording && (recordedPoints.isNotEmpty() || hasSavedRecording)) { Text("清空记录") }
                }
                if(!isRecording && hasSavedRecording) OutlinedButton(onClick={exportGpx.launch("TrailKit-上次轨迹.gpx")},modifier=Modifier.fillMaxWidth()) { Text("导出上次保存的轨迹 GPX") }
                if(isRecording) Text("正在记录 GPS 轨迹 · 已记录 ${recordedPoints.size} 点 · ${recordingElapsedText} · 请保持 TrailKit 在前台",fontSize=12.sp,fontWeight=FontWeight.Bold,color=Color(0xFFB45B32))
                Button(onClick={
                    if(active) {
                        active=false
                        if(isRecording) { isRecording=false;hasSavedRecording=true }
                    } else {
                        prepareNavigationStart()
                        val fine=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
                        if(fine || ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED)active=true else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                },modifier=Modifier.fillMaxWidth(),enabled=navigationPoints.size>=2){Text(if(active)"暂停导航定位" else "开始导航 / 开启 GPS")}
                Text("地图位置可使用 GPS、Wi-Fi 和基站辅助估算；只有新鲜且精度达标的 GPS 才参与路线进度、偏航判断和轨迹记录。山区请结合指北针、纸质地图和现场判断。",fontSize=10.sp,color=MaterialTheme.colorScheme.secondary)
                editingCustomWaypoint?.let { target ->
                    AlertDialog(
                        onDismissRequest={editingCustomWaypoint=null},
                        title={Text("重命名地图标记")},
                        text={TextField(value=waypointNameDraft,onValueChange={waypointNameDraft=it},singleLine=true,label={Text("标记名称")})},
                        confirmButton={TextButton(onClick={
                            val index=customWaypoints.indexOfFirst{it.lat==target.lat && it.lon==target.lon}
                            val name=waypointNameDraft.trim()
                            if(index>=0 && name.isNotBlank()) {
                                customWaypoints[index]=target.copy(name=name)
                                persistCustomWaypoints()
                            }
                            editingCustomWaypoint=null
                        }){Text("保存")}},
                        dismissButton={TextButton(onClick={editingCustomWaypoint=null}){Text("取消")}}
                    )
                }
            }
        }
    }
}

@Composable private fun RowScope.MapStat(label:String,value:String) {
    Surface(Modifier.weight(1f),shape=RoundedCornerShape(10.dp),color=Color(0xFFE9EEE7)) {
        Column(Modifier.padding(8.dp)) { Text(label,fontSize=10.sp,color=Color(0xFF64736A));Text(value,fontSize=14.sp,fontWeight=FontWeight.Bold,color=Color(0xFF183C32)) }
    }
}
