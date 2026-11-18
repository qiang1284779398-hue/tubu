package com.tubu.trailkit

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.RectF
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import java.util.Locale
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

@Composable fun OfflineMapDialog(path:String,encoded:String,projectId:String,onClose:()->Unit) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val db=remember(path) { if(path.isBlank()) null else try { SQLiteDatabase.openDatabase(path,null,SQLiteDatabase.OPEN_READONLY) } catch(_:Exception){null} }
    DisposableEffect(db) { onDispose { try{db?.close()}catch(_:Exception){} } }
    val info=remember(db) { try{db?.let(::readMbInfo)}catch(_:Exception){null} }
    val points=remember(encoded){decodeTrackPoints(encoded)}
    val prefs=remember(projectId){context.getSharedPreferences("trailkit_navigation",Context.MODE_PRIVATE)}
    var threshold by remember(projectId){mutableFloatStateOf(prefs.getInt("offroute_$projectId",80).toFloat().coerceIn(20f,300f))}
    var active by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf<Location?>(null) }
    var gpsError by remember { mutableStateOf("") }
    var zoom by remember(path){mutableFloatStateOf((info?.centerZoom?:14f).coerceIn((info?.minZoom?:0).toFloat(),(info?.maxZoom?:18).toFloat()))}
    var centerLon by remember(path){mutableDoubleStateOf(if(info?.centerLon==0.0 && points.isNotEmpty()) points.map{it.second}.average() else info?.centerLon?:points.firstOrNull()?.second?:0.0)}
    var centerLat by remember(path){mutableDoubleStateOf(if(info?.centerLat==0.0 && points.isNotEmpty()) points.map{it.first}.average() else info?.centerLat?:points.firstOrNull()?.first?:0.0)}
    val permissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if(result[Manifest.permission.ACCESS_FINE_LOCATION]==true) { active=true;gpsError="" }
        else gpsError="请授予精确位置权限，徒步轨迹导航需要 GPS 精度"
    }
    DisposableEffect(active) {
        if(active) {
            val fine=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
            val manager=context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val listener=object:LocationListener {
                override fun onLocationChanged(location:Location) {
                    current=location
                    centerLat=location.latitude;centerLon=location.longitude
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
    val nearest=if(cp!=null&&points.isNotEmpty()) points.indices.minByOrNull{mapDistance(cp,points[it])}?:0 else 0
    val deviation=if(cp!=null&&points.isNotEmpty()) mapDistance(cp,points[nearest]) else null
    val total=remember(points){points.zipWithNext().sumOf{mapDistance(it.first,it.second)}}
    val progress=remember(points){DoubleArray(points.size).also{a->for(i in 1 until points.size)a[i]=a[i-1]+mapDistance(points[i-1],points[i])}}
    val travelled=if(progress.isNotEmpty())progress[nearest] else 0.0
    val remaining=(total-travelled).coerceAtLeast(0.0)
    Dialog(onDismissRequest={active=false;onClose()}) {
        Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth().heightIn(max=780.dp)) {
            Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
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
                        if(info!=null) {
                            val next=(zoom+ln(zoomChange)/ln(2.0).toFloat()).coerceIn(info.minZoom.toFloat(),info.maxZoom.toFloat())
                            val z=zoom.roundToInt().coerceIn(info.minZoom,info.maxZoom)
                            val scale=2.0.pow((zoom-z).toDouble())
                            val wx=worldX(centerLon,z)-pan.x/scale
                            val wy=worldY(centerLat,z)-pan.y/scale
                            val world=256.0*(1 shl z)
                            centerLon=(wx/world*360.0-180.0).coerceIn(-180.0,180.0)
                            val n=Math.PI-2.0*Math.PI*wy/world
                            centerLat=(atan(sinh(n))*180.0/Math.PI).coerceIn(-85.0,85.0)
                            zoom=next
                        }
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
                            try{db.rawQuery("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",arrayOf(z.toString(),wrapped.toString(),((1 shl z)-1-ty).toString())).use{c->
                                if(c.moveToFirst()){val b=c.getBlob(0);val bitmap=BitmapFactory.decodeByteArray(b,0,b.size);if(bitmap!=null){
                                    val x=((tx*256.0-left)*scale).toFloat();val y=((ty*256.0-top)*scale).toFloat()
                                    canvas.drawBitmap(bitmap,null,RectF(x,y,x+256f*scale,y+256f*scale),paint);bitmap.recycle()
                                }}
                            }}catch(_:Exception){}
                        }
                    }
                    if(points.isNotEmpty()) {
                        fun point(p:Pair<Double,Double>)=android.graphics.PointF(((worldX(p.second,z)-left)*scale).toFloat(),((worldY(p.first,z)-top)*scale).toFloat())
                        val route=AndroidPath()
                        points.forEachIndexed{i,p->val q=point(p);if(i==0)route.moveTo(q.x,q.y)else route.lineTo(q.x,q.y)}
                        val rp=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(30,105,75);style=Paint.Style.STROKE;strokeWidth=5f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
                        canvas.drawPath(route,rp)
                        val start=point(points.first());val end=point(points.last())
                        val marker=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(30,130,75);style=Paint.Style.FILL}
                        canvas.drawCircle(start.x,start.y,7f,marker);marker.color=android.graphics.Color.rgb(205,78,58);canvas.drawCircle(end.x,end.y,7f,marker)
                    }
                    cp?.let {
                        val x=((worldX(it.second,z)-left)*scale).toFloat();val y=((worldY(it.first,z)-top)*scale).toFloat()
                        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.WHITE;style=Paint.Style.FILL}
                        canvas.drawCircle(x,y,13f,p);p.color=android.graphics.Color.rgb(30,105,210);canvas.drawCircle(x,y,9f,p)
                        p.color=android.graphics.Color.WHITE;p.style=Paint.Style.STROKE;p.strokeWidth=2f;canvas.drawCircle(x,y,13f,p)
                    }
                }
                Text("绿线：GPX 计划路线   蓝点：当前位置   起点绿 / 终点红",fontSize=11.sp,color=MaterialTheme.colorScheme.secondary)
                if(points.size>=2) {
                    if(cp!=null) {
                        val off=deviation?:0.0
                        Text(if(off>threshold)"偏航警告：偏离轨迹 ${off.roundToInt()} 米（阈值 ${threshold.roundToInt()} 米）" else "路线正常：距轨迹 ${off.roundToInt()} 米",fontWeight=FontWeight.Bold,color=if(off>threshold)MaterialTheme.colorScheme.error else Color(0xFF23734D))
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            MapStat("已走","%.2f km".format(Locale.CHINA,travelled/1000))
                            MapStat("剩余","%.2f km".format(Locale.CHINA,remaining/1000))
                            MapStat("总长","%.2f km".format(Locale.CHINA,total/1000))
                        }
                        LinearProgressIndicator(progress={if(total>0)(travelled/total).toFloat().coerceIn(0f,1f)else 0f},modifier=Modifier.fillMaxWidth())
                    } else Text(if(active)"正在等待 GPS 定位，请移至开阔处……" else "导入 GPX 后即可开始沿轨迹导航。",fontSize=12.sp,color=MaterialTheme.colorScheme.secondary)
                    Text("偏航提醒距离：${threshold.roundToInt()} 米",fontSize=12.sp,fontWeight=FontWeight.SemiBold)
                    Slider(value=threshold,onValueChange={threshold=it},onValueChangeFinished={prefs.edit().putInt("offroute_$projectId",threshold.roundToInt()).apply()},valueRange=20f..300f,steps=27)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("20 米",fontSize=10.sp,color=MaterialTheme.colorScheme.secondary);Text("300 米",fontSize=10.sp,color=MaterialTheme.colorScheme.secondary)}
                } else Text("当前项目没有有效 GPX 轨迹，请先导入 GPX 文件。",fontSize=12.sp,color=MaterialTheme.colorScheme.error)
                if(gpsError.isNotBlank())Text(gpsError,fontSize=12.sp,color=MaterialTheme.colorScheme.error)
                Button(onClick={
                    if(active)active=false else {
                        val fine=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
                        if(fine)active=true else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                },modifier=Modifier.fillMaxWidth(),enabled=points.size>=2){Text(if(active)"暂停 GPS 跟随" else "开始导航 / 开启 GPS")}
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
