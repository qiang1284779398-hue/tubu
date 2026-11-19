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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
internal fun TerrainArCameraDialog(projectName: String, encodedTrack: String, visible: Boolean, onClose: () -> Unit, onJumpToMap: () -> Unit) {
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
    val routeMatchCandidate = remember(location, route) {
        location?.let { fix -> route?.let { matchRoute(it, fix.latitude to fix.longitude) } }
    }
    // A fix must be fresh, accurate, and geographically close to this GPX route.
    val gpsAgeMillis = location?.let { (System.currentTimeMillis() - it.time).coerceAtLeast(0L) } ?: Long.MAX_VALUE
    val gpsQualityOk = location != null && location!!.hasAccuracy() &&
        location!!.accuracy <= 35f && gpsAgeMillis <= 15_000L
    val routeSnapLimitMeters = location?.let { (it.accuracy * 2.5f).toDouble().coerceIn(45.0, 80.0) } ?: 45.0
    val routeMatch = routeMatchCandidate?.takeIf { gpsQualityOk && it.distanceMeters <= routeSnapLimitMeters }
    val offRoute = gpsQualityOk && routeMatchCandidate != null && routeMatchCandidate.distanceMeters > routeSnapLimitMeters
    val gpsReliable = gpsQualityOk && routeMatch != null
    val upcomingTurn = remember(routeMatch, route) {
        if (routeMatch != null && route != null) findUpcomingCameraTurn(route, routeMatch) else null
    }
    val displayProjectName = remember(projectName) {
        if (projectName.matches(Regex("""\d{4}-\d{2}-\d{2}.*\d{10,}.*"""))) "当前徒步项目" else projectName
    }

    var startMapPromptVisible by remember { mutableStateOf(false) }
    var startMapPromptHandled by remember { mutableStateOf(false) }
    var distanceToRouteStart by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(location, routePoints) {
        val fix = location
        if (!startMapPromptHandled && fix != null && routePoints.size >= 2 &&
            fix.hasAccuracy() && fix.accuracy <= 35f &&
            (System.currentTimeMillis() - fix.time).coerceIn(0L, Long.MAX_VALUE) <= 15_000L
        ) {
            val start = routePoints.first()
            val distance = haversineForAr(fix.latitude, fix.longitude, start.first, start.second)
            distanceToRouteStart = distance
            if (distance <= 300.0) startMapPromptVisible = true
            startMapPromptHandled = true
        }
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
                // Compose Dialog may keep a wrap-content window even when its content requests fillMaxSize().
                // Force the actual Android dialog Window to occupy the complete display; otherwise the
                // camera preview is clipped to a short band and the underlying project page remains visible.
                dialogWindow.setBackgroundDrawableResource(android.R.color.black)
                dialogWindow.addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                )
                dialogWindow.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                dialogWindow.setDimAmount(0f)
                dialogWindow.setLayout(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.MATCH_PARENT
                )
                dialogWindow.attributes = dialogWindow.attributes.apply {
                    width = android.view.WindowManager.LayoutParams.MATCH_PARENT
                    height = android.view.WindowManager.LayoutParams.MATCH_PARENT
                    gravity = android.view.Gravity.TOP or android.view.Gravity.START
                }
                dialogWindow.setGravity(android.view.Gravity.TOP or android.view.Gravity.START)
                dialogWindow.decorView.setPadding(0, 0, 0, 0)
                dialogWindow.decorView.setBackgroundColor(android.graphics.Color.BLACK)
                dialogWindow.decorView.fitsSystemWindows = false
                dialogWindow.statusBarColor = android.graphics.Color.TRANSPARENT
                dialogWindow.navigationBarColor = android.graphics.Color.TRANSPARENT
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    dialogWindow.isStatusBarContrastEnforced = false
                    dialogWindow.isNavigationBarContrastEnforced = false
                }
                dialogWindow.decorView.systemUiVisibility =
                    (android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(dialogWindow, false)
                val controller = androidx.core.view.WindowCompat.getInsetsController(dialogWindow, dialogWindow.decorView)
                controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                // Re-apply full-screen sizing after Compose/Android updates the dialog window.
                val forceImmersiveWindow = {
                    dialogWindow.setLayout(
                        android.view.WindowManager.LayoutParams.MATCH_PARENT,
                        android.view.WindowManager.LayoutParams.MATCH_PARENT
                    )
                    dialogWindow.attributes = dialogWindow.attributes.apply {
                        width = android.view.WindowManager.LayoutParams.MATCH_PARENT
                        height = android.view.WindowManager.LayoutParams.MATCH_PARENT
                        gravity = android.view.Gravity.TOP or android.view.Gravity.START
                        x = 0
                        y = 0
                    }
                    dialogWindow.decorView.requestLayout()
                    controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                }
                dialogWindow.decorView.post(forceImmersiveWindow)
                dialogWindow.decorView.setOnSystemUiVisibilityChangeListener {
                    forceImmersiveWindow()
                }
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
                    if (fix != null && match != null && gpsReliable && routePoints.size > 1) {
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
                    // Larger field reticle with four clear cardinal ticks.
                    drawCircle(ArLime.copy(alpha = .96f), 30f, androidx.compose.ui.geometry.Offset(cx, size.height * .5f), style = Stroke(3f))
                    drawCircle(Color.White.copy(alpha = .9f), 4f, androidx.compose.ui.geometry.Offset(cx, size.height * .5f))
                    drawLine(ArLime, androidx.compose.ui.geometry.Offset(cx - 42f, size.height * .5f), androidx.compose.ui.geometry.Offset(cx - 25f, size.height * .5f), 3f)
                    drawLine(ArLime, androidx.compose.ui.geometry.Offset(cx + 25f, size.height * .5f), androidx.compose.ui.geometry.Offset(cx + 42f, size.height * .5f), 3f)
                    drawLine(ArLime, androidx.compose.ui.geometry.Offset(cx, size.height * .5f - 42f), androidx.compose.ui.geometry.Offset(cx, size.height * .5f - 25f), 3f)
                    drawLine(ArLime, androidx.compose.ui.geometry.Offset(cx, size.height * .5f + 25f), androidx.compose.ui.geometry.Offset(cx, size.height * .5f + 42f), 3f)
                }
                if (cameraError.isNotBlank()) {
                    Text(cameraError, Modifier.align(Alignment.Center).padding(16.dp), color = Color.White)
                }
                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        Modifier
                            .widthIn(max = 230.dp)
                            .wrapContentWidth(Alignment.Start)
                            .background(Color.Black.copy(alpha = .68f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text("方位 ${heading.roundToInt()}°  ${compassDirection(heading)}", color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text("俯仰 ${pitch.roundToInt()}° · 横滚 ${roll.roundToInt()}°", color = ArLime, fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    Surface(
                        color = Color.Black.copy(alpha = .68f), shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (sensorReady) "传感器正常" else "读取传感器…", Modifier.padding(horizontal = 10.dp, vertical = 9.dp), color = ArLime, fontSize = 11.sp, maxLines = 1)
                    }
                }
                BoxWithConstraints(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    Column(
                    Modifier.fillMaxWidth()
                        .heightIn(max = maxHeight * 0.48f)
                        .background(Color(0xF20A100D))
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(displayProjectName, color = ArLime, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text("实景路线导航", color = Color.White, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        }
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.ArrowBack, "关闭导航", tint = Color.White)
                        }
                    }
                    val match = routeMatch
                    if (match != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(Modifier.weight(1f), color = Color(0xFF202B25), shape = RoundedCornerShape(10.dp)) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Text("偏离路线", color = Color(0xFFB9C8BE), fontSize = 11.sp)
                                    Text(if (gpsReliable) "${match.distanceMeters.roundToInt()} m" else "暂不可用", color = Color.White, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                }
                            }
                            Surface(Modifier.weight(1f), color = Color(0xFF202B25), shape = RoundedCornerShape(10.dp)) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Text("距 GPX 终点", color = Color(0xFFB9C8BE), fontSize = 11.sp)
                                    Text(if (gpsReliable) formatNavDistance(match.remainingMeters) else "暂不可用", color = Color.White, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                }
                            }
                        }
                    } else if (!offRoute) {
                        Text(if (routePoints.size >= 2) "正在获取定位并匹配 GPX 路线…" else "当前项目没有可用 GPX 轨迹",
                            color = Color(0xFFFFE082), fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    }
                    if (offRoute) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF4A211B),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF8A65))
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("当前位置远离 GPX 轨迹，已暂停导航", color = Color(0xFFFFAB91), fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                Text("距轨迹约 ${routeMatchCandidate!!.distanceMeters.roundToInt()} m；请检查定位、项目或重新靠近路线", color = Color(0xFFFFCCBC), fontSize = 12.sp)
                            }
                        }
                    } else if (match != null && !gpsReliable) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF3A2418),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFB74D).copy(alpha = .8f))
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("GPS 精度不足，暂停精确转向", color = Color(0xFFFFCC80), fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                Text("当前精度 ±${location?.accuracy?.roundToInt() ?: 0} m · 等待更稳定的定位（建议 ≤35 m）",
                                    color = Color(0xFFFFE0B2), fontSize = 12.sp)
                            }
                        }
                    } else if (match != null) {
                        val turn = upcomingTurn
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF332600),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFD54F).copy(alpha = .85f))
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(if (turn != null && turn.distanceMeters <= 85.0) { if (turn.isLeft) "↶" else "↷" } else "◎",
                                    color = Color(0xFFFFE082), fontSize = 28.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                Text(
                                    if (turn != null && turn.distanceMeters <= 85.0)
                                        (if (turn.isLeft) "左转 " else "右转 ") + kotlin.math.abs(turn.angleDegrees).roundToInt() + "° · " + turn.distanceMeters.roundToInt() + " m 后"
                                    else turnGuidance(heading, match.bearingDegrees.toFloat()),
                                    modifier = Modifier.weight(1f),
                                    color = Color(0xFFFFE082),
                                    fontSize = 18.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    maxLines = 2
                                )
                            }
                        }
                    } else if (!offRoute) {
                        Text("等待 GPS 定位", color = Color(0xFFFFE082), fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            when {
                                location == null -> "等待 GPS 信号"
                                gpsReliable -> "GPS 稳定 · ±${location!!.accuracy.roundToInt()} m"
                                offRoute -> "已偏离 GPX 轨迹 · 导航已暂停"
                                else -> "定位不稳定 · ±${location!!.accuracy.roundToInt()} m"
                            },
                            color = if (gpsReliable) Color(0xFFB9D8A8) else Color(0xFFFFCC80),
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        if (!locationGranted) {
                            TextButton(onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)) }) {
                                Text("允许定位", color = ArLime)
                            }
                        }
                    }
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
        if (startMapPromptVisible) {
            AlertDialog(
                onDismissRequest = { startMapPromptVisible = false },
                title = { Text("已接近 GPX 起点") },
                text = {
                    Text("你距离这条轨迹的起点约 ${distanceToRouteStart?.roundToInt() ?: 0} 米。要切换到二维地图页面开始导航吗？")
                },
                confirmButton = {
                    TextButton(onClick = {
                        startMapPromptVisible = false
                        onJumpToMap()
                    }) { Text("切换到二维地图") }
                },
                dismissButton = {
                    TextButton(onClick = { startMapPromptVisible = false }) { Text("继续实景导航") }
                }
            )
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
