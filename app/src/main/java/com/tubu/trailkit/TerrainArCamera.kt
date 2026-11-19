package com.tubu.trailkit

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Locale
import kotlin.math.roundToInt

private val ArLime = Color(0xFFB8D5A8)

@Composable
internal fun TerrainArCameraDialog(projectName: String, encodedTrack: String, visible: Boolean, onClose: () -> Unit) {
    if (!visible) return
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var cameraGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var locationGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    var heading by remember { mutableFloatStateOf(0f) }
    var pitch by remember { mutableFloatStateOf(0f) }
    var roll by remember { mutableFloatStateOf(0f) }
    var location by remember { mutableStateOf<Location?>(null) }
    var sensorReady by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf("") }
    val routePoints = remember(encodedTrack) { decodeTrackPoints(encodedTrack) }
    val route = remember(routePoints) { if (routePoints.size >= 2) prepareRoute(routePoints) else null }
    val routeMatch = remember(location, route) { location?.let { fix -> route?.let { matchRoute(it, fix.latitude to fix.longitude) } } }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        cameraGranted = result[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        locationGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    DisposableEffect(context) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val listener = object : SensorEventListener {
            private val rotation = FloatArray(9)
            private val orientation = FloatArray(3)
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                SensorManager.getOrientation(rotation, orientation)
                heading = ((Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f)
                pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
                roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
                sensorReady = true
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (sensor != null) manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { manager.unregisterListener(listener) }
    }

    DisposableEffect(context, locationGranted) {
        if (!locationGranted) {
            onDispose { }
        } else {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val listener = object : LocationListener {
                override fun onLocationChanged(value: Location) { location = value }
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                @Deprecated("Deprecated in Android")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            }
            try {
                val last = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
                    ?: runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
                if (last != null) location = last
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1500L, 1f, listener)
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 5f, listener)
                }
            } catch (_: SecurityException) { }
            onDispose { runCatching { lm.removeUpdates(listener) } }
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.fillMaxWidth(),
        containerColor = Color(0xFF101713),
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("实景 AR 山体对准", color = Color.White, fontSize = 18.sp)
                    Text("$projectName · CAMERA / SENSOR", color = ArLime, fontSize = 10.sp)
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, "关闭", tint = Color.White) }
            }
        },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth().height(470.dp).background(Color(0xFF202A24), RoundedCornerShape(18.dp))) {
                    if (cameraGranted) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                val previewView = PreviewView(ctx).apply {
                                    scaleType = PreviewView.ScaleType.FILL_CENTER
                                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                }
                                val future = ProcessCameraProvider.getInstance(ctx)
                                future.addListener({
                                    try {
                                        val provider = future.get()
                                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                                        provider.unbindAll()
                                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                                        cameraError = ""
                                    } catch (e: Exception) {
                                        cameraError = e.localizedMessage ?: "无法启动摄像头"
                                    }
                                }, ContextCompat.getMainExecutor(ctx))
                                previewView
                            }
                        )
                        Canvas(Modifier.fillMaxSize()) {
                            val cx = size.width / 2f
                            val cy = size.height * .62f
                            val horizonY = size.height * .34f + (pitch * size.height / 110f).coerceIn(-size.height * .22f, size.height * .22f)
                            drawLine(ArLime.copy(alpha = .48f), androidx.compose.ui.geometry.Offset(0f, horizonY), androidx.compose.ui.geometry.Offset(size.width, horizonY), 1.5f)
                            // 将 GPX 后续路段按 GPS 距离和罗盘夹角投影到相机画面，作为实景导航引导线。
                            val fix = location
                            val match = routeMatch
                            if (fix != null && match != null && routePoints.size > 1) {
                                val path = Path()
                                var started = false
                                val from = match.segmentIndex
                                val to = (from + 90).coerceAtMost(routePoints.lastIndex)
                                for (i in from..to) {
                                    val p = routePoints[i]
                                    val distance = haversineForAr(p.first, p.second, fix.latitude, fix.longitude)
                                    if (distance > 350.0) break
                                    val bearing = bearingToPoint(fix.latitude to fix.longitude, p)
                                    val delta = Math.toRadians(((bearing - heading + 540.0) % 360.0) - 180.0)
                                    val x = cx + (kotlin.math.sin(delta) * distance * 0.72).toFloat()
                                    val y = cy - (kotlin.math.cos(delta) * distance * 0.72).toFloat()
                                    if (x >= -size.width * .2f && x <= size.width * 1.2f && y >= horizonY && y <= size.height * 1.15f) {
                                        if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                                    } else started = false
                                }
                                if (started) drawPath(path, Color(0xFFFFC857), style = Stroke(width = 8f))
                                // 当前位置中心标记
                                drawCircle(Color(0xFFFFC857), 8f, androidx.compose.ui.geometry.Offset(cx, cy))
                            }
                            drawCircle(ArLime.copy(alpha = .95f), 24f, androidx.compose.ui.geometry.Offset(cx, cy), style = Stroke(2.5f))
                            drawLine(ArLime, androidx.compose.ui.geometry.Offset(cx - 22f, cy), androidx.compose.ui.geometry.Offset(cx + 22f, cy), 2f)
                            drawLine(ArLime, androidx.compose.ui.geometry.Offset(cx, cy - 22f), androidx.compose.ui.geometry.Offset(cx, cy + 22f), 2f)
                        }
                        Column(
                            Modifier.align(Alignment.TopStart).padding(12.dp)
                                .background(Color.Black.copy(alpha = .55f), RoundedCornerShape(10.dp)).padding(10.dp)
                        ) {
                            Text("方位 ${heading.roundToInt()}°  ${compassDirection(heading)}", color = Color.White, fontSize = 13.sp)
                            Text("俯仰 ${pitch.roundToInt()}°  ·  横滚 ${roll.roundToInt()}°", color = ArLime, fontSize = 11.sp)
                        }
                        if (cameraError.isNotBlank()) Text(cameraError, Modifier.align(Alignment.Center).padding(16.dp), color = Color.White)
                    } else {
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Tune, null, tint = ArLime, modifier = Modifier.size(42.dp))
                            Spacer(Modifier.height(10.dp))
                            Text("需要相机权限才能启动实景画面", color = Color.White)
                            Button(onClick = {
                                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))
                            }) { Text("授权并打开相机") }
                        }
                    }
                    if (cameraGranted) {
                        Row(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                .background(Color.Black.copy(alpha = .62f)).padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(if (sensorReady) "姿态传感器已连接" else "正在读取姿态传感器…", color = ArLime, fontSize = 11.sp)
                            Text(if (location != null) "GPS 已定位" else "等待 GPS", color = Color.White, fontSize = 11.sp)
                        }
                    }
                }
                Surface(color = Color(0xFF17251E), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        val match = routeMatch
                        val guidance = if (match != null) turnGuidance(heading, match.bearingDegrees.toFloat()) else "等待 GPS 与路线"
                        Text(if (match != null) "路线引导  ·  $guidance" else if (routePoints.size >= 2) "正在定位路线…" else "当前项目没有可用 GPX 轨迹", color = ArLime, fontSize = 14.sp)
                        if (match != null) {
                            Text("距路线 ${match.distanceMeters.roundToInt()} m    ·    剩余 ${formatNavDistance(match.remainingMeters)}", color = Color.White, fontSize = 12.sp)
                            Text(if (match.distanceMeters > 35.0) "偏离轨迹，请结合地图确认方向" else "沿黄色引导线前进；请留意脚下路况", color = if (match.distanceMeters > 35.0) Color(0xFFFFB36B) else Color(0xFFD5DED7), fontSize = 11.sp)
                        } else Text("黄色线：GPX 计划路线投影（GPS / 罗盘误差会影响贴合）", color = Color(0xFFD5DED7), fontSize = 11.sp)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArReadout("航向", "${heading.roundToInt()}°", Modifier.weight(1f))
                    ArReadout("俯仰", "${pitch.roundToInt()}°", Modifier.weight(1f))
                    ArReadout("横滚", "${roll.roundToInt()}°", Modifier.weight(1f))
                }
                val loc = location
                Text(
                    if (loc != null) String.format(Locale.US, "GPS  %.5f, %.5f  ·  精度 ±%.0f m", loc.latitude, loc.longitude, loc.accuracy)
                    else "GPS 尚未获得定位；请在室外等待卫星定位。",
                    color = Color(0xFFD5DED7), fontSize = 11.sp
                )
                Surface(color = Color(0xFF29372E), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("实景徒步导航 HUD", color = ArLime, fontSize = 12.sp)
                        Text(
                            "摄像头画面叠加当前 GPX 路线方向与偏航提示，类似车载 AR 导航。黄色路线是基于 GPS / 罗盘的屏幕投影，不是山体表面精准贴合；山区 GPS 和电子罗盘可能有偏差，请同时核对离线地图。",
                            color = Color(0xFFE0E5E0), fontSize = 11.sp, lineHeight = 16.sp
                        )
                    }
                }
                if (!cameraGranted) {
                    TextButton(onClick = {
                        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))
                    }) { Text("请求相机和定位权限") }
                } else if (!locationGranted) {
                    TextButton(onClick = {
                        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                    }) { Text("允许定位以读取当前位置") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭", color = Color.White) } }
    )
}

@Composable
private fun ArReadout(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = Color(0xFF26352C), shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(vertical = 9.dp, horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = Color(0xFFB8C7B9), fontSize = 10.sp)
            Text(value, color = Color.White, fontSize = 16.sp)
        }
    }
}

private fun compassDirection(degrees: Float): String {
    val names = listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
    return names[((degrees + 22.5f) / 45f).toInt().mod(8)]
}


private fun haversineForAr(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dp = Math.toRadians(lat2 - lat1)
    val dl = Math.toRadians(lon2 - lon1)
    val h = kotlin.math.sin(dp / 2).let { it * it } +
        kotlin.math.cos(p1) * kotlin.math.cos(p2) * kotlin.math.sin(dl / 2).let { it * it }
    return 2 * r * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
}
