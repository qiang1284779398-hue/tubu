package com.tubu.trailkit

import android.Manifest
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.RectF
import android.util.LruCache
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import java.io.File
import kotlin.math.*

private data class MbInfo(val name:String,val format:String,val minZoom:Int,val maxZoom:Int,val centerLon:Double,val centerLat:Double,val centerZoom:Float)
private fun readMbInfo(db:SQLiteDatabase):MbInfo {
    val values=mutableMapOf<String,String>()
    db.rawQuery("SELECT name,value FROM metadata",null).use { c -> while(c.moveToNext()) values[c.getString(0)]=c.getString(1) }
    val center=values["center"]?.split(",")?.mapNotNull{it.toDoubleOrNull()}
    return MbInfo(values["name"]?:"离线地图",values["format"]?:"jpg",values["minzoom"]?.toIntOrNull()?:0,values["maxzoom"]?.toIntOrNull()?:16,center?.getOrNull(0)?:0.0,center?.getOrNull(1)?:0.0,center?.getOrNull(2)?.toFloat()?:10f)
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

@Composable fun OfflineMapDialog(path:String,encoded:String,projectId:String,encodedWaypoints:String="[]",encodedElevations:String="[]",onClose:()->Unit) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val db=remember(path) { if(path.isBlank()) null else try { SQLiteDatabase.openDatabase(path,null,SQLiteDatabase.OPEN_READONLY) } catch(_:Exception){null} }
    DisposableEffect(db) { onDispose { try{db?.close()}catch(_:Exception){} } }
    val info=remember(db) { try{db?.let(::readMbInfo)}catch(_:Exception){null} }
    val points=remember(encoded){decodeTrackPoints(encoded)}
    val waypoints=remember(encodedWaypoints){decodeGpxWaypoints(encodedWaypoints)}
    val elevationProfile=remember(encodedElevations){decodeGpxElevations(encodedElevations)}
    val elevationStats=remember(elevationProfile){
        val pairs=elevationProfile.zipWithNext().filter{it.first!=null&&it.second!=null}
        Triple(pairs.sumOf{(a,b)->(b!!-a!!).coerceAtLeast(0.0)},pairs.sumOf{(a,b)->(a!!-b!!).coerceAtLeast(0.0)},elevationProfile.filterNotNull())
    }
    val prefs=remember(projectId){context.getSharedPreferences("trailkit_navigation",Context.MODE_PRIVATE)}
    var threshold by remember(projectId){mutableFloatStateOf(prefs.getInt("offroute_$projectId",80).toFloat().coerceIn(20f,300f))}
    var active by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf<Location?>(null) }
    var gpsError by remember { mutableStateOf("") }
    var followGps by remember { mutableStateOf(true) }
    var heading by remember { mutableFloatStateOf(Float.NaN) }
    var confirmedProgress by remember(projectId) { mutableDoubleStateOf(0.0) }
    var lastMatchedSegment by remember(projectId) { mutableIntStateOf(0) }
    var offRouteWas by remember(projectId) { mutableStateOf(false) }
    var lastVibrationAt by remember(projectId) { mutableLongStateOf(0L) }
    var isRecording by remember { mutableStateOf(false) }
    var showWaypointList by remember { mutableStateOf(false) }
    val recordedPoints = remember { mutableStateListOf<Location>() }
    var recordingStartedAt by remember { mutableLongStateOf(0L) }
    var hasSavedRecording by remember { mutableStateOf(File(context.filesDir,"trailkit_active_track.gpx").exists()) }
    val tileCache = remember(path) { object : LruCache<String, android.graphics.Bitmap>(8 * 1024) { override fun sizeOf(key:String,value:android.graphics.Bitmap)=value.byteCount/1024 } }
    val exportGpx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        if(uri!=null) try {
            val body=buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"TrailKit\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><name>TrailKit 实时记录</name><trkseg>\n")
                recordedPoints.forEach { p -> append("<trkpt lat=\"${p.latitude}\" lon=\"${p.longitude}\"><ele>${p.altitude}</ele><time>${java.time.Instant.ofEpochMilli(p.time)}</time></trkpt>\n") }
                append("</trkseg></trk></gpx>")
            }
            context.contentResolver.openOutputStream(uri)?.use { output ->
                val file=File(context.filesDir,"trailkit_active_track.gpx")
                if(file.exists() && file.length()>80L) file.inputStream().use { it.copyTo(output) }
                else output.write(body.toByteArray(Charsets.UTF_8))
            }
        } catch(_:Exception) { gpsError="GPX 导出失败，请检查保存位置" }
    }
    var zoom by remember(path){mutableFloatStateOf((info?.centerZoom?:14f).coerceIn((info?.minZoom?:0).toFloat(),(info?.maxZoom?:18).toFloat()))}
    var centerLon by remember(path){mutableDoubleStateOf(if(info?.centerLon==0.0 && points.isNotEmpty()) points.map{it.second}.average() else info?.centerLon?:points.firstOrNull()?.second?:0.0)}
    var centerLat by remember(path){mutableDoubleStateOf(if(info?.centerLat==0.0 && points.isNotEmpty()) points.map{it.first}.average() else info?.centerLat?:points.firstOrNull()?.first?:0.0)}
    val permissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if(result[Manifest.permission.ACCESS_FINE_LOCATION]==true) { active=true;gpsError="" }
        else gpsError="请授予精确位置权限，徒步轨迹导航需要 GPS 精度"
    }
    DisposableEffect(active) {
        if(active) {
            try { ContextCompat.startForegroundService(context,android.content.Intent(context,TrailNavigationService::class.java)) }
            catch(_:Exception) { gpsError="无法启动后台导航服务，请检查系统限制" }
        }
        onDispose { if(active) try { context.stopService(android.content.Intent(context,TrailNavigationService::class.java)) } catch(_:Exception) {} }
    }
    DisposableEffect(active) {
        if(active) {
            val fine=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
            val manager=context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val listener=object:LocationListener {
                override fun onLocationChanged(location:Location) {
                    current=location
                    if(isRecording && (recordedPoints.isEmpty() || mapDistance(recordedPoints.last().latitude to recordedPoints.last().longitude,location.latitude to location.longitude)>=3.0)) recordedPoints.add(Location(location))
                    if(followGps) { centerLat=location.latitude;centerLon=location.longitude }
                }
                @Deprecated("Deprecated in Java") override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?) {}
                override fun onProviderEnabled(provider:String) {}
                override fun onProviderDisabled(provider:String) { gpsError="GPS 已关闭，请在系统设置中开启定位" }
            }
            try {
                if(fine) {
                    if(!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) gpsError="请开启手机 GPS 定位"
                    manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { current=it;centerLat=it.latitude;centerLon=it.longitude }
                    manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,2000L,3f,listener)
                } else { active=false;gpsError="请授予定位权限" }
            } catch(e:Exception) { gpsError="定位启动失败：${e.message ?: "请检查定位设置"}";active=false }
            onDispose { try{manager.removeUpdates(listener)}catch(_:Exception){} }
        } else onDispose { }
    }
    val cp=current?.let{it.latitude to it.longitude}
    val routeMatch=cp?.let { matchRoute(points,it,lastMatchedSegment) }
    val closestWaypoint=if(cp!=null) waypoints.minByOrNull { mapDistance(cp,it.lat to it.lon) } else null
    val announcedWaypoints = remember(projectId) { mutableStateListOf<Int>() }
    LaunchedEffect(active, closestWaypoint, current?.accuracy) {
        if(active && cp!=null && closestWaypoint!=null && (current?.accuracy ?: 999f)<=25f) {
            val index=waypoints.indexOf(closestWaypoint)
            val distance=mapDistance(cp,closestWaypoint.lat to closestWaypoint.lon)
            if(index>=0 && distance<=30.0 && index !in announcedWaypoints) {
                announcedWaypoints.add(index)
                try {
                    val vibrator=context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    if(Build.VERSION.SDK_INT>=26) vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0,180,100,180),-1))
                    else vibrator.vibrate(longArrayOf(0,180,100,180),-1)
                } catch(_:Exception) {}
                android.widget.Toast.makeText(context,"已到达途经点：${closestWaypoint.name}",android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    val nearest=routeMatch?.segmentIndex?:0
    val deviation=routeMatch?.distanceMeters
    val total=routeMatch?.totalMeters?:remember(points){points.zipWithNext().sumOf{mapDistance(it.first,it.second)}}
    val travelled=if(routeMatch!=null) maxOf(confirmedProgress,routeMatch.progressMeters) else confirmedProgress
    val remaining=(total-travelled).coerceAtLeast(0.0)
    LaunchedEffect(routeMatch,active) {
        if(routeMatch!=null) { confirmedProgress=maxOf(confirmedProgress,routeMatch.progressMeters);lastMatchedSegment=routeMatch.segmentIndex }
        val off=active && routeMatch!=null && (current?.accuracy?:999f)<=50f && routeMatch.distanceMeters>threshold+(current?.accuracy?:0f)
        if(off && !offRouteWas && System.currentTimeMillis()-lastVibrationAt>15000L) {
            try {
                val vibrator=context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if(Build.VERSION.SDK_INT>=26) vibrator.vibrate(VibrationEffect.createOneShot(260L,VibrationEffect.DEFAULT_AMPLITUDE))
                else vibrator.vibrate(260L)
                lastVibrationAt=System.currentTimeMillis()
            } catch(_:Exception) {}
        }
        if(off) offRouteWas=true
        else if(routeMatch==null || routeMatch.distanceMeters<threshold*0.7) offRouteWas=false
    }
    val recordingSeconds=((System.currentTimeMillis()-recordingStartedAt).coerceAtLeast(0L)/1000L)
    val recordingElapsedText=String.format(Locale.CHINA,"%02d:%02d",recordingSeconds/60,recordingSeconds%60)
    DisposableEffect(context) {
        val manager=context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor=manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val listener=object:SensorEventListener {
            override fun onSensorChanged(event:SensorEvent) {
                val matrix=FloatArray(9);val orientation=FloatArray(3)
                SensorManager.getRotationMatrixFromVector(matrix,event.values)
                SensorManager.getOrientation(matrix,orientation)
                heading=((Math.toDegrees(orientation[0].toDouble()).toFloat()+360f)%360f)
            }
            override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int) {}
        }
        if(sensor!=null) manager.registerListener(listener,sensor,SensorManager.SENSOR_DELAY_UI)
        onDispose { manager.unregisterListener(listener) }
    }
    Dialog(onDismissRequest={active=false;onClose()}) {
        Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth().heightIn(max=780.dp)) {
            Column(Modifier.padding(14.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if(active)"轨迹跟随导航" else info?.name?:"离线地图导航",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                        Text(if(info!=null)"离线地形 · ${info.format.uppercase()} · 缩放 ${info.minZoom}–${info.maxZoom}" else "轨迹导航模式 · 尚未导入离线地图",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.secondary)
                    }
                    TextButton(onClick={active=false;onClose()}){Text("关闭")}
                }
                if(db==null || info==null) {
                    Surface(color=Color(0xFFFFF3DD),shape=RoundedCornerShape(10.dp)) {
                        Text("当前没有可读取的 MBTiles 地图。导入地图包后，这里会显示真实地形；现在仍可使用 GPS + GPX 轨迹进行基础跟随导航。",Modifier.padding(10.dp),fontSize=12.sp,color=Color(0xFF76521D))
                    }
                }
                Canvas(Modifier.fillMaxWidth().height(310.dp).pointerInput(active) {
                    detectTransformGestures { _,pan,zoomChange,_ ->
                            val minZ=(info?.minZoom?:3).toFloat();val maxZ=(info?.maxZoom?:18).toFloat()
                            val next=(zoom+ln(zoomChange)/ln(2.0).toFloat()).coerceIn(minZ,maxZ)
                            val z=zoom.roundToInt().coerceIn(minZ.toInt(),maxZ.toInt())
                            val scale=2.0.pow((zoom-z).toDouble())
                            val wx=worldX(centerLon,z)-pan.x/scale
                            val wy=worldY(centerLat,z)-pan.y/scale
                            val world=256.0*(1 shl z)
                            centerLon=(wx/world*360.0-180.0).coerceIn(-180.0,180.0)
                            val n=Math.PI-2.0*Math.PI*wy/world
                            centerLat=(atan(sinh(n))*180.0/Math.PI).coerceIn(-85.0,85.0)
                            zoom=next
                    }
                }) {
                    val canvas=drawContext.canvas.nativeCanvas
                    canvas.drawColor(android.graphics.Color.rgb(239,242,235))
                    val z=if(info!=null)zoom.roundToInt().coerceIn(info.minZoom,info.maxZoom) else 14
                    val scale=2.0.pow((zoom-z).toDouble()).toFloat()
                    val cx=worldX(centerLon,z);val cy=worldY(centerLat,z)
                    val left=cx-size.width/(2.0*scale);val top=cy-size.height/(2.0*scale)
                    if(db!=null&&info!=null) {
                        val minX=floor(left/256.0).toInt();val maxX=floor((left+size.width/scale)/256.0).toInt()
                        val minY=floor(top/256.0).toInt();val maxY=floor((top+size.height/scale)/256.0).toInt()
                        val paint=Paint(Paint.FILTER_BITMAP_FLAG)
                        for(tx in minX..maxX)for(ty in minY..maxY){
                            if(ty !in 0 until (1 shl z))continue
                            val wrapped=((tx%(1 shl z))+(1 shl z))%(1 shl z)
                            try {
                                val key="$z/$wrapped/$ty"
                                var bitmap=tileCache.get(key)
                                if(bitmap==null) {
                                    db.rawQuery("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",arrayOf(z.toString(),wrapped.toString(),((1 shl z)-1-ty).toString())).use { c ->
                                        if(c.moveToFirst()) {
                                            val bytes=c.getBlob(0)
                                            bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
                                            if(bitmap!=null) tileCache.put(key,bitmap!!)
                                        }
                                    }
                                }
                                if(bitmap!=null) {
                                    val x=((tx*256.0-left)*scale).toFloat();val y=((ty*256.0-top)*scale).toFloat()
                                    canvas.drawBitmap(bitmap!!,null,RectF(x,y,x+256f*scale,y+256f*scale),paint)
                                }
                            } catch(_:Exception) {}
                        }
                    }
                    if(points.isNotEmpty()) {
                        fun point(p:Pair<Double,Double>)=android.graphics.PointF(((worldX(p.second,z)-left)*scale).toFloat(),((worldY(p.first,z)-top)*scale).toFloat())
                        val rp=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=5f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
                        val snapped=routeMatch?.let { android.graphics.PointF(((worldX(it.snappedLon,z)-left)*scale).toFloat(),((worldY(it.snappedLat,z)-top)*scale).toFloat()) }
                        val remainingPath=AndroidPath()
                        val firstRemaining=snapped?:point(points[nearest])
                        remainingPath.moveTo(firstRemaining.x,firstRemaining.y)
                        for(i in (nearest+1)..points.lastIndex) { val q=point(points[i]);remainingPath.lineTo(q.x,q.y) }
                        rp.color=android.graphics.Color.rgb(218,145,54)
                        if(nearest<points.lastIndex)canvas.drawPath(remainingPath,rp)
                        val passedPath=AndroidPath()
                        points.take(nearest+1).forEachIndexed{i,p->val q=point(p);if(i==0)passedPath.moveTo(q.x,q.y)else passedPath.lineTo(q.x,q.y)}
                        if(snapped!=null)passedPath.lineTo(snapped.x,snapped.y)
                        rp.color=android.graphics.Color.rgb(30,125,78)
                        if(nearest>0 || snapped!=null)canvas.drawPath(passedPath,rp)
                        val start=point(points.first());val end=point(points.last())
                        val marker=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(30,130,75);style=Paint.Style.FILL}
                        canvas.drawCircle(start.x,start.y,7f,marker);marker.color=android.graphics.Color.rgb(205,78,58);canvas.drawCircle(end.x,end.y,7f,marker)
                    }
                    if(waypoints.isNotEmpty()) {
                        val marker=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(235,151,47);style=Paint.Style.FILL}
                        val outline=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.WHITE;style=Paint.Style.STROKE;strokeWidth=2f}
                        val labelFill=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(66,55,39);textSize=26f;typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)}
                        val labelStroke=Paint(labelFill).apply{color=android.graphics.Color.WHITE;style=Paint.Style.STROKE;strokeWidth=5f}
                        var lastLabelX=-1000f;var lastLabelY=-1000f
                        waypoints.forEach { wp ->
                            val x=((worldX(wp.lon,z)-left)*scale).toFloat()
                            val y=((worldY(wp.lat,z)-top)*scale).toFloat()
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
                    cp?.let {
                        val x=((worldX(it.second,z)-left)*scale).toFloat();val y=((worldY(it.first,z)-top)*scale).toFloat()
                        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.WHITE;style=Paint.Style.FILL}
                        canvas.drawCircle(x,y,13f,p);p.color=android.graphics.Color.rgb(30,105,210);canvas.drawCircle(x,y,9f,p)
                        p.color=android.graphics.Color.WHITE;p.style=Paint.Style.STROKE;p.strokeWidth=2f;canvas.drawCircle(x,y,13f,p)
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
                Text("绿线：已走   橙线：剩余   蓝点：定位   橙色点：GPX 途经点（${waypoints.size}）",fontSize=11.sp,color=MaterialTheme.colorScheme.secondary)
                if(points.size>=2) {
                    if(cp!=null) {
                        val off=deviation?:0.0
                        val gpsAccuracy=current?.accuracy?:999f
                        Text(
                            when {
                                gpsAccuracy>50f -> "定位精度不足（±${gpsAccuracy.roundToInt()} m），暂不判断是否偏航"
                                off>threshold+gpsAccuracy -> "偏航警告：偏离轨迹 ${off.roundToInt()} 米（阈值 ${threshold.roundToInt()} 米）"
                                off>threshold -> "注意：接近偏航阈值，建议核对地图"
                                else -> "路线正常：距轨迹 ${off.roundToInt()} 米"
                            },
                            fontWeight=FontWeight.Bold,
                            color=if(gpsAccuracy>50f)Color(0xFF9A6A25) else if(off>threshold+gpsAccuracy)MaterialTheme.colorScheme.error else Color(0xFF23734D)
                        )
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            MapStat("已走",formatNavDistance(travelled))
                            MapStat("剩余",formatNavDistance(remaining))
                            MapStat("总长",formatNavDistance(total))
                        }
                        LinearProgressIndicator(progress={if(total>0)(travelled/total).toFloat().coerceIn(0f,1f)else 0f},modifier=Modifier.fillMaxWidth())
                        if(elevationStats.third.size>=2 && elevationProfile.size==points.size) {
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
                                val cumulative=DoubleArray(points.size)
                                for(i in 1 until points.size)cumulative[i]=cumulative[i-1]+mapDistance(points[i-1],points[i])
                                val distanceTotal=cumulative.last().coerceAtLeast(1.0)
                                val grid=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(211,220,210);strokeWidth=1f}
                                for(i in 0..3){val y=padY+(size.height-2*padY)*i/3f;canvas.drawLine(padX,y,size.width-padX,y,grid)}
                                val line=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(35,112,77);style=Paint.Style.STROKE;strokeWidth=4f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
                                val path=AndroidPath();var started=false
                                elevationProfile.forEachIndexed { i,value ->
                                    if(value==null) started=false else {
                                        val x=padX+(cumulative[i]/distanceTotal).toFloat()*(size.width-2*padX)
                                        val y=(size.height-padY)-((value-minEle)/span).toFloat()*(size.height-2*padY)
                                        if(!started){path.moveTo(x,y);started=true}else path.lineTo(x,y)
                                    }
                                }
                                canvas.drawPath(path,line)
                                val label=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(76,91,79);textSize=22f}
                                canvas.drawText("${maxEle.roundToInt()} m",padX+4f,25f,label)
                                canvas.drawText("${minEle.roundToInt()} m",padX+4f,size.height-5f,label)
                            }
                        } else {
                            Text("海拔剖面：当前 GPX 未提供完整的逐点海拔数据",fontSize=11.sp,color=MaterialTheme.colorScheme.secondary)
                        }
                    } else Text(if(active)"正在等待 GPS 定位，请移至开阔处……" else "导入 GPX 后即可开始沿轨迹导航。",fontSize=12.sp,color=MaterialTheme.colorScheme.secondary)
                    Text("偏航提醒距离：${threshold.roundToInt()} 米",fontSize=12.sp,fontWeight=FontWeight.SemiBold)
                    Slider(value=threshold,onValueChange={threshold=it},onValueChangeFinished={prefs.edit().putInt("offroute_$projectId",threshold.roundToInt()).apply()},valueRange=20f..300f,steps=27)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("20 米",fontSize=10.sp,color=MaterialTheme.colorScheme.secondary);Text("300 米",fontSize=10.sp,color=MaterialTheme.colorScheme.secondary)}
                } else Text("当前项目没有有效 GPX 轨迹，请先导入 GPX 文件。",fontSize=12.sp,color=MaterialTheme.colorScheme.error)
                if(gpsError.isNotBlank())Text(gpsError,fontSize=12.sp,color=MaterialTheme.colorScheme.error)
                if(current!=null) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        MapStat("GPS 精度","±${current!!.accuracy.roundToInt()} m")
                        MapStat("海拔",if(current!!.hasAltitude())"%.0f m".format(Locale.CHINA,current!!.altitude) else "暂无")
                        MapStat("速度",if(current!!.hasSpeed())"%.1f km/h".format(Locale.CHINA,current!!.speed*3.6f) else "暂无")
                    }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    MapStat("罗盘",if(heading.isNaN())"无传感器" else "${directionLabel(heading)} ${heading.roundToInt()}°")
                    MapStat("路线方向",routeMatch?.let{"${directionLabel(it.bearingDegrees.toFloat())} ${it.bearingDegrees.roundToInt()}°"}?:"--")
                    MapStat("方向提示",routeMatch?.let{turnGuidance(heading,it.bearingDegrees.toFloat())}?:"等待定位")
                }
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
                if(closestWaypoint!=null) {
                    val waypointDistance=mapDistance(cp!!,closestWaypoint.lat to closestWaypoint.lon)
                    Text(
                        if(waypointDistance<=30.0 && current?.accuracy?.let{it<=25f}==true) "已到达途经点：${closestWaypoint.name}"
                        else "最近途经点：${closestWaypoint.name} · ${formatNavDistance(waypointDistance)}",
                        fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=Color(0xFF8C602C)
                    )
                }
                if(waypoints.isNotEmpty()) {
                    TextButton(onClick={showWaypointList=!showWaypointList},modifier=Modifier.fillMaxWidth()) {
                        Text(if(showWaypointList)"收起途经点列表（${waypoints.size}）" else "查看全部途经点（${waypoints.size}）")
                    }
                    if(showWaypointList) waypoints.forEachIndexed { index,wp ->
                        val coordinateText=String.format(Locale.US,"%.5f, %.5f",wp.lat,wp.lon)
                        OutlinedButton(onClick={centerLat=wp.lat;centerLon=wp.lon;followGps=false},modifier=Modifier.fillMaxWidth()) {
                            Text("${index+1}. ${wp.name}  ·  $coordinateText",fontSize=11.sp)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick={followGps=!followGps},modifier=Modifier.weight(1f)){Text(if(followGps)"自动跟随：开" else "自动跟随：关")}
                    OutlinedButton(onClick={if(current!=null){centerLat=current!!.latitude;centerLon=current!!.longitude;followGps=true}else if(points.isNotEmpty()){centerLat=points.first().first;centerLon=points.first().second}} ,modifier=Modifier.weight(1f)){Text("回到当前位置")}
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick={zoom=(zoom-1f).coerceAtLeast((info?.minZoom?:3).toFloat())},modifier=Modifier.weight(1f)){Text("− 缩小")}
                    OutlinedButton(onClick={zoom=(zoom+1f).coerceAtMost((info?.maxZoom?:18).toFloat())},modifier=Modifier.weight(1f)){Text("+ 放大")}
                    OutlinedButton(onClick={confirmedProgress=0.0;lastMatchedSegment=0;offRouteWas=false},modifier=Modifier.weight(1f)){Text("重置进度")}
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick={
                        if(isRecording) {
                            isRecording=false
                            hasSavedRecording=true
                            try { context.startService(android.content.Intent(context,TrailNavigationService::class.java).setAction(TrailNavigationService.ACTION_STOP_RECORDING)) } catch(_:Exception) {}
                            if(recordedPoints.size>=2) exportGpx.launch("TrailKit-${System.currentTimeMillis()}.gpx")
                            else gpsError="记录点不足，至少需要两个定位点才能导出 GPX"
                        } else {
                            if(!active) gpsError="请先开启导航定位，再开始记录轨迹"
                            else {
                                recordedPoints.clear();recordingStartedAt=System.currentTimeMillis();isRecording=true;hasSavedRecording=false
                                try { context.startService(android.content.Intent(context,TrailNavigationService::class.java).setAction(TrailNavigationService.ACTION_START_RECORDING)) } catch(_:Exception) { gpsError="无法启动轨迹记录服务" }
                            }
                        }
                    },modifier=Modifier.weight(1f),enabled=active || isRecording) { Text(if(isRecording)"停止并导出 GPX（${recordedPoints.size}点）" else "开始记录新轨迹") }
                    OutlinedButton(onClick={recordedPoints.clear();isRecording=false;hasSavedRecording=false;try{context.startService(android.content.Intent(context,TrailNavigationService::class.java).setAction(TrailNavigationService.ACTION_STOP_RECORDING));File(context.filesDir,"trailkit_active_track.gpx").delete()}catch(_:Exception){}},modifier=Modifier.weight(1f),enabled=!isRecording && (recordedPoints.isNotEmpty() || hasSavedRecording)) { Text("清空记录") }
                }
                if(!isRecording && hasSavedRecording) OutlinedButton(onClick={exportGpx.launch("TrailKit-上次轨迹.gpx")},modifier=Modifier.fillMaxWidth()) { Text("导出上次保存的轨迹 GPX") }
                if(isRecording) Text("正在记录 GPS 轨迹 · 已记录 ${recordedPoints.size} 点 · ${recordingElapsedText} · 请保持 TrailKit 在前台",fontSize=12.sp,fontWeight=FontWeight.Bold,color=Color(0xFFB45B32))
                Button(onClick={
                    if(active) {
                        active=false
                        if(isRecording) { isRecording=false;hasSavedRecording=true }
                    } else {
                        val fine=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
                        if(fine)active=true else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                },modifier=Modifier.fillMaxWidth(),enabled=points.size>=2){Text(if(active)"暂停导航定位" else "开始导航 / 开启 GPS")}
                Text("导航使用手机 GPS 和导入的 GPX；离线地图数据需要事先导入。山区 GPS 可能漂移，请结合指北针、纸质地图和现场判断。",fontSize=10.sp,color=MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@Composable private fun RowScope.MapStat(label:String,value:String) {
    Surface(Modifier.weight(1f),shape=RoundedCornerShape(10.dp),color=Color(0xFFE9EEE7)) {
        Column(Modifier.padding(8.dp)) { Text(label,fontSize=10.sp,color=Color(0xFF64736A));Text(value,fontSize=14.sp,fontWeight=FontWeight.Bold,color=Color(0xFF183C32)) }
    }
}
