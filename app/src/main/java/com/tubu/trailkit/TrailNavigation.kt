package com.tubu.trailkit

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.*

private fun navDistance(a: Pair<Double,Double>, b: Pair<Double,Double>): Double {
    val r=6371000.0
    val p1=Math.toRadians(a.first); val p2=Math.toRadians(b.first)
    val dp=Math.toRadians(b.first-a.first); val dl=Math.toRadians(b.second-a.second)
    val h=sin(dp/2).pow(2)+cos(p1)*cos(p2)*sin(dl/2).pow(2)
    return 2*r*asin(sqrt(h.coerceIn(0.0,1.0)))
}
private fun navBearing(a: Pair<Double,Double>, b: Pair<Double,Double>): Double {
    val p1=Math.toRadians(a.first); val p2=Math.toRadians(b.first); val dl=Math.toRadians(b.second-a.second)
    return (Math.toDegrees(atan2(sin(dl)*cos(p2),cos(p1)*sin(p2)-sin(p1)*cos(p2)*cos(dl)))+360)%360
}

@Composable
fun TrailNavigationDialog(encoded: String, onClose: () -> Unit) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val points=remember(encoded){decodeTrackPoints(encoded)}
    var active by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf<Location?>(null) }
    var gpsError by remember { mutableStateOf("") }
    val permissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if(result[Manifest.permission.ACCESS_FINE_LOCATION]==true || result[Manifest.permission.ACCESS_COARSE_LOCATION]==true) { active=true; gpsError="" }
        else gpsError="未获得定位权限，无法开始轨迹导航"
    }
    DisposableEffect(active) {
        if(active) {
            val fine=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
            val coarse=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
            val manager=context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val listener=object:LocationListener {
                override fun onLocationChanged(location:Location) { current=location }
                @Deprecated("Deprecated in Java") override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?) {}
                override fun onProviderEnabled(provider:String) {}
                override fun onProviderDisabled(provider:String) { gpsError="GPS 已关闭，请在系统设置中开启定位" }
            }
            try {
                if(fine || coarse) {
                    val provider=if(fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
                    manager.getLastKnownLocation(provider)?.let { current=it }
                    manager.requestLocationUpdates(provider,2000L,3f,listener)
                } else { active=false; gpsError="请授予定位权限" }
            } catch(e:Exception) { gpsError="无法启动定位：${e.message ?: "请检查系统定位设置"}"; active=false }
            onDispose { try { manager.removeUpdates(listener) } catch(_:Exception) {} }
        } else onDispose { }
    }
    val currentPoint=current?.let { it.latitude to it.longitude }
    val preparedRoute=remember(points){prepareRoute(points)}
    var lastMatchedSegment by remember(points){mutableIntStateOf(0)}
    val routeMatch=currentPoint?.let { matchRoute(preparedRoute,it,lastMatchedSegment) }
    LaunchedEffect(routeMatch?.segmentIndex) {
        routeMatch?.let { lastMatchedSegment=it.segmentIndex }
    }
    val offRoute=routeMatch?.distanceMeters
    val total=preparedRoute.totalMeters
    val done=routeMatch?.progressMeters ?: 0.0
    val remaining=(total-done).coerceAtLeast(0.0)
    val bearing=routeMatch?.bearingDegrees
    Dialog(onDismissRequest={active=false;onClose()}) {
        Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth().heightIn(max=720.dp)) {
            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("轨迹跟随导航",fontSize=21.sp,fontWeight=FontWeight.Bold)
                        Text("GPS 实时定位 · 无需移动网络",fontSize=12.sp,color=MaterialTheme.colorScheme.secondary)
                    }
                    TextButton(onClick={active=false;onClose()}) { Text("关闭") }
                }
                if(points.size<2) {
                    Text("当前项目没有有效 GPX 轨迹。请先导入 GPX 文件。")
                } else {
                    Canvas(Modifier.fillMaxWidth().height(210.dp).background(Color(0xFFF0F2EC),RoundedCornerShape(16.dp))) {
                        val minLat=points.minOf{it.first}; val maxLat=points.maxOf{it.first}
                        val minLon=points.minOf{it.second}; val maxLon=points.maxOf{it.second}
                        val dLat=(maxLat-minLat).coerceAtLeast(0.00001); val dLon=(maxLon-minLon).coerceAtLeast(0.00001)
                        fun xy(p:Pair<Double,Double>):androidx.compose.ui.geometry.Offset {
                            val x=36f+((p.second-minLon)/dLon).toFloat()*(size.width-72f)
                            val y=size.height-28f-((p.first-minLat)/dLat).toFloat()*(size.height-56f)
                            return androidx.compose.ui.geometry.Offset(x,y)
                        }
                        val route=Path()
                        points.forEachIndexed{i,p->val q=xy(p);if(i==0)route.moveTo(q.x,q.y) else route.lineTo(q.x,q.y)}
                        drawPath(route,Color(0xFF276B50),style=androidx.compose.ui.graphics.drawscope.Stroke(width=5f,cap=androidx.compose.ui.graphics.StrokeCap.Round,join=androidx.compose.ui.graphics.StrokeJoin.Round))
                        val start=xy(points.first()); val end=xy(points.last())
                        drawCircle(Color(0xFF16834A),8f,start); drawCircle(Color(0xFFD65A43),8f,end)
                        currentPoint?.let { cp ->
                            val q=xy(cp)
                            drawCircle(Color.White,12f,q);drawCircle(Color(0xFF1769D2),8f,q)
                        }
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Text("起点",fontSize=11.sp,color=Color(0xFF16834A))
                        Text("路线示意图（非比例尺地图）",fontSize=11.sp,color=MaterialTheme.colorScheme.secondary)
                        Text("终点",fontSize=11.sp,color=Color(0xFFD65A43))
                    }
                    if(currentPoint==null) {
                        Text(if(active) "正在等待 GPS 定位，请移至开阔处……" else "开始后将请求手机 GPS 定位权限。",color=MaterialTheme.colorScheme.secondary)
                    } else {
                        val off=offRoute?:0.0
                        Text(if(off>80) "偏航警告：你已偏离轨迹约 ${off.roundToInt()} 米，请检查方向！" else "正在轨迹附近 · 距离轨迹约 ${off.roundToInt()} 米",color=if(off>80) MaterialTheme.colorScheme.error else Color(0xFF23734D),fontWeight=FontWeight.Bold)
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            NavStat("已走",String.format(Locale.CHINA,"%.2f km",done/1000))
                            NavStat("剩余",String.format(Locale.CHINA,"%.2f km",remaining/1000))
                            NavStat("总长",String.format(Locale.CHINA,"%.2f km",total/1000))
                        }
                        LinearProgressIndicator(progress={if(total>0)(done/total).toFloat().coerceIn(0f,1f) else 0f},modifier=Modifier.fillMaxWidth())
                        Text("路线进度 ${if(total>0)(done/total*100).roundToInt() else 0}%   ·   ${bearing?.let{ "下一段方向 ${it.roundToInt()}°" } ?: "接近终点"}",fontSize=13.sp)
                        Text("GPS：${String.format(Locale.US,"%.5f",current!!.latitude)}, ${String.format(Locale.US,"%.5f",current!!.longitude)}",fontSize=11.sp,color=MaterialTheme.colorScheme.secondary)
                    }
                    if(gpsError.isNotBlank()) Text(gpsError,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
                    Button(onClick={
                        if(active) active=false
                        else {
                            val fine=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
                            val coarse=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
                            if(fine||coarse) active=true else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))
                        }
                    },modifier=Modifier.fillMaxWidth()) { Text(if(active)"暂停定位" else "开始导航") }
                    Text("偏航阈值暂定 80 米。山谷、峡谷和密林会影响 GPS 精度；本功能只按导入轨迹计算，不会自动判断道路安全，也不能替代纸质地图和指北针。",fontSize=11.sp,color=MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}

@Composable private fun RowScope.NavStat(label:String,value:String) {
    Surface(Modifier.weight(1f),shape=RoundedCornerShape(12.dp),color=Color(0xFFE9EEE7)) {
        Column(Modifier.padding(10.dp)) {
            Text(label,fontSize=11.sp,color=Color(0xFF64736A))
            Text(value,fontSize=15.sp,fontWeight=FontWeight.Bold,color=Color(0xFF183C32))
        }
    }
}
