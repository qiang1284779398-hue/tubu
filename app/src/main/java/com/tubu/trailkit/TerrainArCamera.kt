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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Locale
import kotlin.math.roundToInt

private val ArLime = Color(0xFFB8D5A8)

private data class CameraTurn(val angleDegrees: Double, val distanceMeters: Double) {
    val isLeft: Boolean get() = angleDegrees < 0.0
}

private fun findUpcomingCameraTurn(route: PreparedRoute, match: RouteMatch): CameraTurn? {
    val points = route.points
    if (points.size < 5) return null
    val first = (match.segmentIndex + 1).coerceAtLeast(2)
    val last = points.lastIndex - 2
    if (first > last) return null
    for (i in first..last) {
        val distance = route.prefixMeters[i] - match.progressMeters
        if (distance < 2.0) continue
        if (distance > 180.0) break
        // Use a wider baseline to suppress tiny GPS/GPX zigzags and calculate the actual turn angle.
        val incoming = bearingToPoint(points[i - 2], points[i])
        val outgoing = bearingToPoint(points[i], points[i + 2])
        val angle = signedTurnAngle(incoming, outgoing)
        if (kotlin.math.abs(angle) >= 28.0) return CameraTurn(angle, distance)
    }
    return null
}

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
    val upcomingTurn = remember(routeMatch, route) {
        if (routeMatch != null && route != null) findUpcomingCameraTurn(route, routeMatch) else null
    }

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

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // A Compose Dialog owns a separate Window. Apply immersive mode to that Window,
        // not the underlying Activity Window, otherwise the Activity's title/status UI
        // can bleed through at the top when the dialog is shown.
        val dialogView = androidx.compose.ui.platform.LocalView.current
        val dialogWindow = (dialogView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        DisposableEffect(dialogWindow) {
            if (dialogWindow != null) {
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(dialogWindow, false)
                val controller = androidx.core.view.WindowCompat.getInsetsController(dialogWindow, dialogWindow.decorView)
                controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
            onDispose {
                if (dialogWindow != null) {
                    androidx.core.view.WindowCompat.getInsetsController(dialogWindow, dialogWindow.decorView)
                        .show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
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
                    val horizonY = size.height * .34f + (pitch * size.height / 110f).coerceIn(-size.height * .22f, size.height * .22f)
                    val focalLength = size.height * .92f
                    drawLine(ArLime.copy(alpha = .32f), androidx.compose.ui.geometry.Offset(0f, horizonY), androidx.compose.ui.geometry.Offset(size.width, horizonY), 1.2f)
                    val fix = location
                    val match = routeMatch
                    if (fix != null && match != null && routePoints.size > 1) {
                        var previous: androidx.compose.ui.geometry.Offset? = null
                        var previousDistance = 0.0
                        val from = match.segmentIndex
                        val to = (from + 120).coerceAtMost(routePoints.lastIndex)
                        for (i in from..to) {
                            val p = routePoints[i]
                            val distance = haversineForAr(p.first, p.second, fix.latitude, fix.longitude)
                            if (distance > 450.0) break
                            val bearing = bearingToPoint(fix.latitude to fix.longitude, p)
                            val delta = Math.toRadians(((bearing - heading + 540.0) % 360.0) - 180.0)
                            val forward = distance * kotlin.math.cos(delta)
                            val lateral = distance * kotlin.math.sin(delta)
                            if (forward <= 1.0) { previous = null; continue }
                            // Ground-plane perspective: distant route points converge toward the horizon.
                            val x = cx + (focalLength * lateral / forward).toFloat()
                            val y = horizonY + (focalLength * 1.55 / forward).toFloat()
                            val point = androidx.compose.ui.geometry.Offset(x, y)
                            if (previous != null && x in -size.width * .15f..size.width * 1.15f && y in horizonY..size.height * 1.08f) {
                                val distanceForStyle = (distance + previousDistance) * .5
                                val width = (13.0 * 5.0 / (distanceForStyle + 5.0)).toFloat().coerceIn(1.4f, 10f)
                                val alpha = (1.0 - distanceForStyle / 520.0).toFloat().coerceIn(.16f, .96f)
                                drawLine(Color(0xFFFFC857).copy(alpha = alpha), previous!!, point, strokeWidth = width,
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round)
                            }
                            previous = if (x in -size.width * .15f..size.width * 1.15f && y in horizonY..size.height * 1.08f) point else null
                            previousDistance = distance
                        }
                        drawCircle(Color(0xFFFFC857), 7f, androidx.compose.ui.geometry.Offset(cx, size.height * .5f))
                    }
                    drawCircle(ArLime.copy(alpha = .95f), 22f, androidx.compose.ui.geometry.Offset(cx, size.height * .5f), style = Stroke(2.5f))
                    drawLine(ArLime, androidx.compose.ui.geometry.Offset(cx - 20f, size.height * .5f), androidx.compose.ui.geometry.Offset(cx + 20f, size.height * .5f), 2f)
                    drawLine(ArLime, androidx.compose.ui.geometry.Offset(cx, size.height * .5f - 20f), androidx.compose.ui.geometry.Offset(cx, size.height * .5f + 20f), 2f)
                }
                if (cameraError.isNotBlank()) {
                    Text(cameraError, Modifier.align(Alignment.Center).padding(16.dp), color = Color.White)
                }
                Column(
                    Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp)
                        .background(Color.Black.copy(alpha = .58f), RoundedCornerShape(12.dp)).padding(12.dp)
                ) {
                    Text("方位 ${heading.roundToInt()}°  ${compassDirection(heading)}", color = Color.White, fontSize = 14.sp)
                    Text("俯仰 ${pitch.roundToInt()}° · 横滚 ${roll.roundToInt()}°", color = ArLime, fontSize = 11.sp)
                }
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp),
                    color = Color.Black.copy(alpha = .58f), shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (sensorReady) "传感器正常" else "读取传感器…", Modifier.padding(12.dp), color = ArLime, fontSize = 11.sp)
                }
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .navigationBarsPadding().imePadding()
                        .background(Color.Black.copy(alpha = .92f)).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("实景路线导航", color = ArLime, fontSize = 12.sp, maxLines = 1)
                            val match = routeMatch
                            Text(
                                if (match != null) "距路线 ${match.distanceMeters.roundToInt()} m  ·  剩余 ${formatNavDistance(match.remainingMeters)}"
                                else if (routePoints.size >= 2) "正在定位 GPX 路线…" else "当前项目没有可用 GPX 轨迹",
                                color = Color.White, fontSize = 14.sp
                            )
                            if (match != null) {
                                val turn = upcomingTurn
                                if (turn != null && turn.distanceMeters <= 85.0) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                        Text("↑", modifier = Modifier.rotate(turn.angleDegrees.toFloat()), color = Color(0xFFFFC857), fontSize = 26.sp)
                                        Text((if (turn.isLeft) "左转 " else "右转 ") + kotlin.math.abs(turn.angleDegrees).roundToInt() + "° · " + turn.distanceMeters.roundToInt() + " m 后", modifier = Modifier.weight(1f), color = Color(0xFFFFC857), fontSize = 13.sp, maxLines = 3)
                                    }
                                } else {
                                    Text(turnGuidance(heading, match.bearingDegrees.toFloat()), color = Color(0xFFFFC857), fontSize = 12.sp, maxLines = 2)
                                }
                            } else {
                                Text("等待 GPS 定位", color = Color(0xFFFFC857), fontSize = 12.sp)
                            }
                        }
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.ArrowBack, "关闭导航", tint = Color.White)
                        }
                    }
                    Text(
                        if (location != null) "GPS 已定位 · 精度约 ±${location!!.accuracy.roundToInt()} m"
                        else "等待 GPS · 黄色轨迹为屏幕投影，请同时核对离线地图",
                        color = Color(0xFFD5DED7), fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    if (!locationGranted) {
                        TextButton(onClick = {
                            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                        }) { Text("允许定位", color = ArLime) }
                    }
                }
            } else {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Icon(Icons.Default.Tune, null, tint = ArLime, modifier = Modifier.size(48.dp))
                    Text("需要相机权限才能启动实景画面", color = Color.White)
                    Button(onClick = {
                        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))
                    }) { Text("授权并打开相机") }
                    TextButton(onClick = onClose) { Text("返回", color = ArLime) }
                }
            }
        }
    }
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


private tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
