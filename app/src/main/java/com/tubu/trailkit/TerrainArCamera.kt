package com.tubu.trailkit

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val ArLime = Color(0xFFB8D5A8)

private data class ArTerrainSnapshot(
    val progressMeters: Double,
    val routeElevations: List<Double?>,
    val currentGroundElevationMeters: Double
)
private data class CameraRibbonPoint(
    val center: ArScreenPoint?,
    val leftWorld: ArVector3,
    val rightWorld: ArVector3,
    val progressMeters: Double
)

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

@OptIn(ExperimentalCamera2Interop::class)
@Composable
internal fun TerrainArCameraDialog(projectId: String, projectName: String, encodedTrack: String, encodedElevations: String = "[]", visible: Boolean, onClose: () -> Unit, onJumpToMap: () -> Unit) {
    if (!visible) return
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var cameraGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var locationGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    var location by remember { mutableStateOf<Location?>(null) }
    var sensorReady by remember { mutableStateOf(false) }
    var sensorAccuracy by remember { mutableIntStateOf(SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) }
    var gyroMagnitudeRadPerSec by remember { mutableDoubleStateOf(0.0) }
    var magneticFieldMagnitudeMicroTesla by remember { mutableDoubleStateOf<Double?>(null) }
    var magneticCameraPose by remember { mutableStateOf<ArCameraPose?>(null) }
    val cameraPoseFilter = remember { ArCameraPoseFilter() }
    var cameraIntrinsics by remember { mutableStateOf(ArCameraIntrinsics(4.2, 5.6, 4.2, false)) }
    var cameraError by remember { mutableStateOf("") }
    var locationError by remember { mutableStateOf("") }
    var activeCameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var cameraSessionActive by remember { mutableStateOf(true) }
    val navigationPrefs = remember(projectId) { context.getSharedPreferences("trailkit_navigation", Context.MODE_PRIVATE) }
    var cameraHeightMeters by remember(projectId) {
        mutableFloatStateOf(navigationPrefs.getFloat("ar_camera_height_$projectId", 1.45f).coerceIn(1.1f, 1.9f))
    }
    LaunchedEffect(context) { ArTerrainDem.initialize(context) }
    val reverseRoute = remember(projectId) { navigationPrefs.getBoolean("reverse_$projectId", false) }
    val baseRoutePoints = remember(encodedTrack) { cleanArRoutePoints(decodeTrackPoints(encodedTrack)) }
    val routePoints = remember(baseRoutePoints, reverseRoute) { if (reverseRoute) baseRoutePoints.asReversed() else baseRoutePoints }
    val route = remember(routePoints) { if (routePoints.size >= 2) prepareRoute(routePoints) else null }
    val routeProgressKey = "progress_${projectId}_${encodedTrack.hashCode()}_${if (reverseRoute) "rev" else "fwd"}"
    val routeSegmentKey = "segment_${projectId}_${encodedTrack.hashCode()}_${if (reverseRoute) "rev" else "fwd"}"
    var confirmedProgress by remember(projectId, encodedTrack, reverseRoute) { mutableDoubleStateOf(navigationPrefs.getFloat(routeProgressKey, 0f).toDouble()) }
    var destinationPromptVisible by remember(projectId, encodedTrack, reverseRoute) { mutableStateOf(false) }
    var destinationPromptHandled by remember(projectId, encodedTrack, reverseRoute) { mutableStateOf(false) }
    var navigationFinished by remember(projectId, encodedTrack, reverseRoute) { mutableStateOf(false) }
    val routeElevations = remember(encodedElevations, reverseRoute) { decodeGpxElevations(encodedElevations).let { if (reverseRoute) it.asReversed() else it } }
    var lastMatchedFix by remember(route) { mutableStateOf<Location?>(null) }
    var lastStableRouteMatch by remember(route) { mutableStateOf(route?.let { routeMatchAtProgress(it, confirmedProgress) }) }
    var routeMatchCandidate by remember(route) { mutableStateOf<RouteMatch?>(null) }
    var routeMatch by remember(route) { mutableStateOf<RouteMatch?>(null) }
    var terrainSnapshot by remember(route) { mutableStateOf<ArTerrainSnapshot?>(null) }
    var rejoinGroundElevations by remember(route) { mutableStateOf<Pair<Double?, Double?>?>(null) }

    var ageRefresh by remember { mutableLongStateOf(0L) }
    LaunchedEffect(location) {
        while (true) {
            delay(1_000L)
            ageRefresh += 1L
        }
    }
    val gpsAgeMillis = location?.let {
        ageRefresh
        ((SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos).coerceAtLeast(0L) / 1_000_000L)
    } ?: Long.MAX_VALUE
    val gpsQualityOk = location != null &&
        location!!.provider == android.location.LocationManager.GPS_PROVIDER &&
        location!!.hasAccuracy() && location!!.accuracy <= 20f && gpsAgeMillis <= 15_000L
    val routeSnapLimitMeters = location?.let { (it.accuracy.toDouble() * 1.25).coerceIn(8.0, 28.0) } ?: 12.0
    val offRoute = gpsQualityOk && routeMatchCandidate != null && routeMatch == null
    val gpsReliable = location != null && location!!.hasAccuracy() &&
        location!!.accuracy <= 10f && gpsAgeMillis <= 5_000L && routeMatch != null
    val gpsWeak = location != null && location!!.hasAccuracy() &&
        location!!.accuracy <= 20f && gpsAgeMillis <= 15_000L && routeMatch != null && !gpsReliable
    val gpsArUsable = gpsReliable || gpsWeak
    // Show a nearby route-joining guide even when the loose GPS snap tolerance
    // currently considers the fix "on route".
    val rejoinMinimumMeters = location?.let {
        (it.accuracy.toDouble() * 0.55).coerceIn(4.0, 12.0)
    } ?: 6.0
    val rejoinConnectorVisible = gpsQualityOk && routeMatchCandidate?.let {
        it.distanceMeters >= rejoinMinimumMeters && it.distanceMeters <= 20.0
    } == true

    val magneticDeclination = remember(location) {
        location?.let {
            runCatching {
                android.hardware.GeomagneticField(
                    it.latitude.toFloat(), it.longitude.toFloat(),
                    if (it.hasAltitude()) it.altitude.toFloat() else 0f,
                    System.currentTimeMillis()
                ).declination.toDouble()
            }.getOrDefault(0.0)
        } ?: 0.0
    }
    LaunchedEffect(location) {
        val fix = location
        if (fix != null && fix.hasBearing() && fix.hasSpeed() && fix.speed >= 1.0f) {
            val course = fix.bearing.toDouble()
            val previous = previousGpsCourse
            stableGpsCourseCycles = if (previous != null &&
                kotlin.math.abs(signedTurnAngle(previous, course)) <= 10.0
            ) stableGpsCourseCycles + 1 else 1
            previousGpsCourse = course
        } else {
            stableGpsCourseCycles = 0
            previousGpsCourse = null
        }
    }
    val cameraPose = remember(magneticCameraPose, magneticDeclination, location, stableGpsCourseCycles, gyroMagnitudeRadPerSec) {
        magneticCameraPose?.let { magneticPose ->
            val truePose = applyArMagneticDeclination(magneticPose, magneticDeclination)
            val fix = location
            if (fix != null && fix.hasBearing() && fix.hasSpeed()) {
                val aligned = kotlin.math.abs(signedTurnAngle(truePose.headingDegrees, fix.bearing.toDouble())) <= 25.0
                val corrected = fuseArGpsCourse(
                    truePose.headingDegrees, fix.bearing.toDouble(), fix.speed.toDouble(),
                    stableGpsCourseCycles, aligned, gyroMagnitudeRadPerSec > 0.6, 0.08
                )
                applyArMagneticDeclination(truePose, signedTurnAngle(truePose.headingDegrees, corrected))
            } else truePose
        }
    }
    val heading = (cameraPose?.headingDegrees ?: Double.NaN).toFloat()
    val pitch = (cameraPose?.pitchDegrees ?: Double.NaN).toFloat()
    val roll = (cameraPose?.rollDegrees ?: Double.NaN).toFloat()
    val projectionReady = sensorReady && sensorAccuracy != SensorManager.SENSOR_STATUS_UNRELIABLE && cameraPose != null
    val reconnectDirectionText = remember(location, routeMatchCandidate, cameraPose) {
        val fix = location
        val target = routeMatchCandidate
        val currentHeading = cameraPose?.headingDegrees
        if (fix == null || target == null || currentHeading == null) {
            "方向待定"
        } else {
            val bearing = bearingToPoint(
                fix.latitude to fix.longitude,
                target.snappedLat to target.snappedLon
            )
            val delta = signedTurnAngle(currentHeading, bearing)
            when {
                kotlin.math.abs(delta) < 15.0 -> "正前方"
                kotlin.math.abs(delta) > 165.0 -> "正后方"
                delta > 0.0 && delta < 90.0 -> "右前方 ${delta.roundToInt()}°"
                delta > 0.0 -> "右后方 ${delta.roundToInt()}°"
                delta > -90.0 -> "左前方 ${kotlin.math.abs(delta).roundToInt()}°"
                else -> "左后方 ${kotlin.math.abs(delta).roundToInt()}°"
            }
        }
    }

    LaunchedEffect(location, route, ageRefresh, navigationFinished) {
        val fix = location
        if (navigationFinished || fix == null || route == null || !fix.hasAccuracy() ||
            fix.provider != android.location.LocationManager.GPS_PROVIDER ||
            fix.accuracy > 20f || gpsAgeMillis > 15_000L
        ) {
            routeMatchCandidate = null
            routeMatch = null
            return@LaunchedEffect
        }
        val previousFix = lastMatchedFix
        val previousMatch = lastStableRouteMatch ?: routeMatchAtProgress(route, confirmedProgress)
        val elapsedSeconds = previousFix?.let {
            ((fix.elapsedRealtimeNanos - it.elapsedRealtimeNanos).coerceAtLeast(0L) / 1_000_000_000.0)
        } ?: 0.0
        val speed = if (fix.hasSpeed()) fix.speed.toDouble().coerceIn(0.0, 5.5) else 1.8
        // Reacquire only near the saved progress after reopening. Never search the full
        // route on the first fix, or a coincident out-and-back leg can steal the match.
        val allowedAdvance = if (previousFix == null) 150.0 else {
            routeProgressAdvanceLimitMeters(
                speed, elapsedSeconds, fix.accuracy.toDouble(), previousFix.accuracy.toDouble()
            )
        }
        val course = if (fix.hasBearing() && fix.hasSpeed() && fix.speed >= 1.2f) fix.bearing.toDouble() else null
        val candidate = matchRouteContinuously(
            route, fix.latitude to fix.longitude, previousMatch, allowedAdvance, course
        )
        routeMatchCandidate = candidate
        if (candidate != null && candidate.distanceMeters <= routeSnapLimitMeters) {
            routeMatch = candidate
            lastMatchedFix = Location(fix)
            lastStableRouteMatch = candidate
            confirmedProgress = maxOf(confirmedProgress, candidate.progressMeters)
            navigationPrefs.edit()
                .putFloat(routeProgressKey, confirmedProgress.toFloat())
                .putInt(routeSegmentKey, candidate.segmentIndex)
                .apply()
        } else {
            routeMatch = null
        }
    }

    // Sample the route and the snapped ground reference on the same vertical datum.
    LaunchedEffect(location, routeMatch, route) {
        val fix = location
        val match = routeMatch
        val activeRoute = route
        if (fix == null || match == null || activeRoute == null || !gpsArUsable) {
            terrainSnapshot = null
        } else {
            val requestedProgress = match.progressMeters
            val gpxSmoothed = smoothArRouteElevations(routeElevations)
            val samples = sampleArRouteAheadAdaptive(activeRoute, gpxSmoothed, requestedProgress, 120.0)
            val coordinates = samples.map { it.latitude to it.longitude }
            val dem = withContext(Dispatchers.IO) {
                ArTerrainDem.elevations(coordinates) to
                    ArTerrainDem.elevation(match.snappedLat, match.snappedLon)
            }
            val gpxAnchor = samples.firstOrNull()?.elevationMeters
            val alignedGpxFallback = if (dem.second != null && gpxAnchor != null) {
                samples.map { sample ->
                    sample.elevationMeters?.let { it + (dem.second!! - gpxAnchor) }
                }
            } else samples.map { it.elevationMeters }
            val resolved = if (dem.second != null) {
                resolveArElevationSamples(dem.first, alignedGpxFallback)
            } else {
                alignedGpxFallback
            }
            val reference = dem.second ?: gpxAnchor
            terrainSnapshot = if (reference != null) {
                ArTerrainSnapshot(requestedProgress, resolved, reference)
            } else null
        }
    }
    // When the GPS fix is outside the snap tolerance, fetch ground heights for a
    // temporary connector from the user's position to the nearest forward route point.
    LaunchedEffect(location, routeMatchCandidate, rejoinConnectorVisible, navigationFinished) {
        val fix = location
        val candidate = routeMatchCandidate
        if (!rejoinConnectorVisible || navigationFinished || fix == null || candidate == null || !gpsQualityOk) {
            rejoinGroundElevations = null
        } else {
            rejoinGroundElevations = withContext(Dispatchers.IO) {
                ArTerrainDem.elevation(fix.latitude, fix.longitude) to
                    ArTerrainDem.elevation(candidate.snappedLat, candidate.snappedLon)
            }
        }
    }

    LaunchedEffect(routeMatch, location, gpsReliable, navigationFinished) {
        val match = routeMatch
        val fix = location
        if (!navigationFinished && !destinationPromptHandled && match != null && fix != null && gpsReliable &&
            gpsAgeMillis <= 10_000L && fix.accuracy <= 25f && match.remainingMeters <= 35.0 &&
            haversineForAr(fix.latitude, fix.longitude, routePoints.last().first, routePoints.last().second) <= maxOf(25.0, fix.accuracy.toDouble() * 1.5)
        ) {
            destinationPromptHandled = true
            destinationPromptVisible = true
        } else if (match != null && match.remainingMeters > 100.0) {
            destinationPromptHandled = false
        }
    }

    val upcomingTurn = remember(routeMatch, route) {
        val activeMatch = routeMatch
        if (activeMatch != null && route != null) findUpcomingCameraTurn(route, activeMatch) else null
    }

    // Resample and smooth the route only when route progress/elevation data changes.
    // The camera pose may update ~50 times per second; it must not rebuild GPX geometry.
    val smoothedGpxElevations = remember(routeElevations) { smoothArRouteElevations(routeElevations) }
    val fallbackRouteSamples = remember(route, smoothedGpxElevations, routeMatch?.progressMeters) {
        val activeRoute = route
        val progress = routeMatch?.progressMeters
        if (activeRoute != null && progress != null) {
            sampleArRouteAheadAdaptive(activeRoute, smoothedGpxElevations, progress, 120.0)
        } else {
            emptyList()
        }
    }
    val activeTerrainSnapshot = remember(terrainSnapshot, routeMatch?.progressMeters) {
        val progress = routeMatch?.progressMeters
        terrainSnapshot?.takeIf { progress != null && kotlin.math.abs(it.progressMeters - progress) <= 8.0 }
    }
    val arRouteSamples = remember(fallbackRouteSamples, activeTerrainSnapshot) {
        if (activeTerrainSnapshot != null &&
            activeTerrainSnapshot.routeElevations.size == fallbackRouteSamples.size
        ) {
            fallbackRouteSamples.mapIndexed { index, sample ->
                sample.copy(elevationMeters = activeTerrainSnapshot.routeElevations[index])
            }
        } else {
            fallbackRouteSamples
        }
    }
    val arReferenceElevation = activeTerrainSnapshot?.currentGroundElevationMeters
        ?: fallbackRouteSamples.firstOrNull()?.elevationMeters

    val displayProjectName = remember(projectName) {
        if (projectName.matches(Regex("""\d{4}-\d{2}-\d{2}.*\d{10,}.*"""))) "当前徒步项目" else projectName
    }

    var startMapPromptVisible by remember { mutableStateOf(false) }
    var startMapPromptHandled by remember { mutableStateOf(false) }
    var distanceToRouteStart by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(location, routePoints) {
        val fix = location
        val age = fix?.let {
            ((SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos).coerceAtLeast(0L) / 1_000_000L)
        } ?: Long.MAX_VALUE
        if (confirmedProgress <= 20.0 && !startMapPromptHandled && fix != null && routePoints.size >= 2 &&
            fix.provider == android.location.LocationManager.GPS_PROVIDER &&
            fix.hasAccuracy() && fix.accuracy <= 25f && age <= 10_000L
        ) {
            val start = routePoints.first()
            val distance = haversineForAr(fix.latitude, fix.longitude, start.first, start.second)
            distanceToRouteStart = distance
            if (distance <= 1000.0) startMapPromptVisible = true
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
        cameraSessionActive = true
        onDispose {
            cameraSessionActive = false
            activeCameraProvider?.let { provider -> runCatching { provider.unbindAll() } }
        }
    }

    DisposableEffect(context) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val gyroSensor = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val magneticSensor = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val listener = object : SensorEventListener {
            private val rotation = FloatArray(9)
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_GYROSCOPE -> {
                        gyroMagnitudeRadPerSec = kotlin.math.sqrt(
                            event.values.take(3).sumOf { it.toDouble() * it.toDouble() }
                        )
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        magneticFieldMagnitudeMicroTesla = kotlin.math.sqrt(
                            event.values.take(3).sumOf { it.toDouble() * it.toDouble() }
                        )
                    }
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rotation, event.values)
                        val rawPose = arCameraPoseFromRotationMatrix(rotation, windowManager.defaultDisplay.rotation)
                        magneticCameraPose = rawPose?.let {
                            cameraPoseFilter.applyAdaptive(
                                it, gyroMagnitudeRadPerSec, magneticFieldMagnitudeMicroTesla, sensorAccuracy
                            )
                        }
                        sensorReady = magneticCameraPose != null
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                sensorAccuracy = accuracy
            }
        }
        if (sensor != null) manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        else sensorReady = false
        gyroSensor?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        magneticSensor?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        onDispose { manager.unregisterListener(listener) }
    }

    // The foreground navigation service owns the only GPS/network registration.
    // The camera page consumes the shared stream, so opening AR cannot add duplicate GPS requests.
    DisposableEffect(context, locationGranted, navigationFinished) {
        if (!locationGranted || navigationFinished) {
            onDispose { }
        } else {
            val observer = object : TrailLocationObserver {
                override fun onLocationChanged(value: Location) {
                    if (value.provider == android.location.LocationManager.GPS_PROVIDER) {
                        location = Location(value)
                        locationError = ""
                    }
                }
                override fun onProviderDisabled(provider: String) {
                    if (provider == android.location.LocationManager.GPS_PROVIDER) {
                        locationError = "GPS 已关闭，请开启系统定位"
                    }
                }
            }
            val serviceIntent = android.content.Intent(context, TrailNavigationService::class.java)
            TrailLocationHub.subscribe(observer)
            try {
                ContextCompat.startForegroundService(context, serviceIntent)
            } catch (e: Exception) {
                locationError = e.localizedMessage ?: "无法启动 GPS 定位服务"
            }
            onDispose {
                TrailLocationHub.unsubscribe(observer)
                runCatching { context.stopService(serviceIntent) }
            }
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
                            if (!cameraSessionActive) return@addListener
                            try {
                                val provider = future.get()
                                activeCameraProvider = provider
                                cameraIntrinsics = ArCameraIntrinsics(4.2, 5.6, 4.2, false)
                                val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
                                val preview = Preview.Builder().setTargetRotation(targetRotation).build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }
                                provider.unbindAll()
                                val camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                                val camera2Info = Camera2CameraInfo.from(camera.cameraInfo)
                                // Read characteristics from the Camera2 camera actually bound by CameraX.
                                val physicalSize = camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                                val focalLengths = camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                                val pixelArray = camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                                val calibrated = camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
                                val distortion = camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_DISTORTION)
                                val sensorRotation = camera.cameraInfo.getSensorRotationDegrees(targetRotation)
                                if (physicalSize != null && focalLengths != null && focalLengths.isNotEmpty()) {
                                    val rotated = sensorRotation % 180 != 0
                                    val validCalibration = calibrated != null && calibrated.size >= 4 &&
                                        pixelArray != null && calibrated[0] > 0f && calibrated[1] > 0f
                                    cameraIntrinsics = ArCameraIntrinsics(
                                        if (rotated) physicalSize.height.toDouble() else physicalSize.width.toDouble(),
                                        if (rotated) physicalSize.width.toDouble() else physicalSize.height.toDouble(),
                                        focalLengths.first().toDouble(),
                                        validCalibration,
                                        intrinsicFxPixels = if (validCalibration) calibrated!![0].toDouble() else null,
                                        intrinsicFyPixels = if (validCalibration) calibrated!![1].toDouble() else null,
                                        sourcePixelWidth = pixelArray?.width?.toDouble(),
                                        sourcePixelHeight = pixelArray?.height?.toDouble(),
                                        distortionK1 = distortion?.getOrNull(0)?.toDouble() ?: 0.0,
                                        distortionK2 = distortion?.getOrNull(1)?.toDouble() ?: 0.0
                                    )
                                }
                                cameraError = ""
                            } catch (e: Exception) {
                                cameraError = e.localizedMessage ?: "无法启动摄像头"
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    }
                )
                Canvas(Modifier.fillMaxSize()) {
                    val fix = location
                    val match = routeMatch
                    val pose = cameraPose
                    if (fix != null && match != null && gpsArUsable && projectionReady &&
                        pose != null && route != null && routePoints.size > 1
                    ) {
                        val samples = arRouteSamples
                        val referenceElevation = arReferenceElevation
                        val focalPixels = cameraIntrinsics.focalPixels(size.width.toDouble(), size.height.toDouble())
                        val ribbonPoints = samples.mapIndexed { index, sample ->
                            val previous = samples[(index - 1).coerceAtLeast(0)]
                            val next = samples[(index + 1).coerceAtMost(samples.lastIndex)]
                            val incoming = bearingToPoint(previous.latitude to previous.longitude, sample.latitude to sample.longitude)
                            val outgoing = bearingToPoint(sample.latitude to sample.longitude, next.latitude to next.longitude)
                            val turn = kotlin.math.abs(signedTurnAngle(incoming, outgoing))
                            val halfWidth = if (turn > 120.0) 0.35 else 0.65
                            val tangent = bearingToPoint(
                                previous.latitude to previous.longitude,
                                next.latitude to next.longitude
                            )
                            val leftGeo = offsetArGeoPoint(sample.latitude, sample.longitude, tangent - 90.0, halfWidth)
                            val rightGeo = offsetArGeoPoint(sample.latitude, sample.longitude, tangent + 90.0, halfWidth)
                            val leftWorld = arRouteSampleWorldVectorFromAnchor(
                                match.snappedLat, match.snappedLon, fix.latitude, fix.longitude,
                                sample.copy(latitude = leftGeo.first, longitude = leftGeo.second),
                                referenceElevation, cameraHeightMeters.toDouble()
                            )
                            val rightWorld = arRouteSampleWorldVectorFromAnchor(
                                match.snappedLat, match.snappedLon, fix.latitude, fix.longitude,
                                sample.copy(latitude = rightGeo.first, longitude = rightGeo.second),
                                referenceElevation, cameraHeightMeters.toDouble()
                            )
                            val center = projectArRouteSampleFromAnchor(
                                match.snappedLat, match.snappedLon, fix.latitude, fix.longitude, sample,
                                referenceElevation, cameraHeightMeters.toDouble(), pose, focalPixels,
                                size.width.toDouble(), size.height.toDouble(),
                                cameraIntrinsics.distortionK1, cameraIntrinsics.distortionK2
                            )
                            CameraRibbonPoint(center, leftWorld, rightWorld, sample.progressMeters)
                        }

                        for (i in 1 until ribbonPoints.size) {
                            val a = ribbonPoints[i - 1]
                            val b = ribbonPoints[i]
                            val distanceAlong = (a.progressMeters + b.progressMeters) * 0.5 - match.progressMeters
                            val alpha = (1.0 - distanceAlong / 135.0).toFloat().coerceIn(0.16f, 0.9f) * if (gpsWeak) 0.48f else 1f
                            val clippedQuad = projectArWorldPolygon(
                                listOf(a.leftWorld, a.rightWorld, b.rightWorld, b.leftWorld),
                                pose, focalPixels, size.width.toDouble(), size.height.toDouble(),
                                0.5, cameraIntrinsics.distortionK1, cameraIntrinsics.distortionK2
                            )
                            if (clippedQuad.size >= 3) {
                                val quad = Path().apply {
                                    moveTo(clippedQuad.first().x, clippedQuad.first().y)
                                    clippedQuad.drop(1).forEach { lineTo(it.x, it.y) }
                                    close()
                                }
                                drawPath(quad, Color(0xFF052A43).copy(alpha = 0.54f * alpha))
                                drawPath(quad, Color(0xFF168CFF).copy(alpha = 0.72f * alpha))
                            }
                            if (a.center != null && b.center != null) {
                                drawLine(
                                    Color(0xFFBDEEFF).copy(alpha = 0.92f * alpha),
                                    androidx.compose.ui.geometry.Offset(a.center.x, a.center.y),
                                    androidx.compose.ui.geometry.Offset(b.center.x, b.center.y),
                                    2.2f
                                )
                                if ((a.progressMeters / 24.0).toInt() != (b.progressMeters / 24.0).toInt()) {
                                    val dx = b.center.x - a.center.x
                                    val dy = b.center.y - a.center.y
                                    val length = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                                    val ux = dx / length
                                    val uy = dy / length
                                    val mid = androidx.compose.ui.geometry.Offset(
                                        (a.center.x + b.center.x) * 0.5f,
                                        (a.center.y + b.center.y) * 0.5f
                                    )
                                    val arrowLength = 10f.coerceAtLeast((length * 0.38f).coerceAtMost(24f))
                                    val tip = androidx.compose.ui.geometry.Offset(mid.x + ux * arrowLength, mid.y + uy * arrowLength)
                                    val base = androidx.compose.ui.geometry.Offset(mid.x - ux * arrowLength * 0.55f, mid.y - uy * arrowLength * 0.55f)
                                    val perpX = -uy * arrowLength * 0.5f
                                    val perpY = ux * arrowLength * 0.5f
                                    val arrow = Path().apply {
                                        moveTo(tip.x, tip.y)
                                        lineTo(base.x + perpX, base.y + perpY)
                                        lineTo(base.x - perpX, base.y - perpY)
                                        close()
                                    }
                                    drawPath(arrow, Color.White.copy(alpha = 0.94f * alpha))
                                }
                            }
                        }
                    }

                    // Off-route connector: draw a temporary dashed path from the live GPS
                    // position to the continuity-matched point on the correct forward route leg.
                    val reconnectTarget = routeMatchCandidate
                    if (rejoinConnectorVisible && fix != null && reconnectTarget != null &&
                        reconnectTarget.distanceMeters <= 20.0 && projectionReady && pose != null
                    ) {
                        val reconnectFocalPixels = cameraIntrinsics.focalPixels(size.width.toDouble(), size.height.toDouble())
                        val ground = rejoinGroundElevations
                        val referenceGround = ground?.first
                        val targetGround = ground?.second
                        val connectorPoints = (0..8).map { index ->
                            val fraction = index / 8.0
                            val latitude = fix.latitude + (reconnectTarget.snappedLat - fix.latitude) * fraction
                            val longitude = fix.longitude + (reconnectTarget.snappedLon - fix.longitude) * fraction
                            val elevation = if (referenceGround != null && targetGround != null) {
                                referenceGround + (targetGround - referenceGround) * fraction
                            } else null
                            projectArRouteSample(
                                fix.latitude, fix.longitude,
                                ArRouteSample(latitude, longitude, 0.0, elevation),
                                referenceGround, cameraHeightMeters.toDouble(), pose, reconnectFocalPixels,
                                size.width.toDouble(), size.height.toDouble()
                            )
                        }
                        val visibleConnector = connectorPoints.filterNotNull()
                        if (visibleConnector.size >= 2) {
                            val connectorPath = Path().apply {
                                moveTo(visibleConnector.first().x, visibleConnector.first().y)
                                visibleConnector.drop(1).forEach { lineTo(it.x, it.y) }
                            }
                            drawPath(
                                connectorPath,
                                Color(0xFFFFA726).copy(alpha = 0.95f),
                                style = Stroke(width = 8f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 12f), 0f))
                            )
                            drawPath(
                                connectorPath,
                                Color.White.copy(alpha = 0.95f),
                                style = Stroke(width = 2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 12f), 0f))
                            )
                            val endpoint = visibleConnector.last()
                            drawCircle(Color(0xFFFFA726), 13f, androidx.compose.ui.geometry.Offset(endpoint.x, endpoint.y))
                            drawCircle(Color.White, 5f, androidx.compose.ui.geometry.Offset(endpoint.x, endpoint.y))
                        }
                    }

                    // Stable view reticle; the route itself is projected through the camera pose.
                    val cx = size.width / 2f
                    drawCircle(ArLime.copy(alpha = 0.96f), 30f, androidx.compose.ui.geometry.Offset(cx, size.height * 0.5f), style = Stroke(3f))
                    drawCircle(Color.White.copy(alpha = 0.9f), 4f, androidx.compose.ui.geometry.Offset(cx, size.height * 0.5f))
                    drawLine(ArLime, androidx.compose.ui.geometry.Offset(cx - 42f, size.height * 0.5f), androidx.compose.ui.geometry.Offset(cx - 25f, size.height * 0.5f), 3f)
                    drawLine(ArLime, androidx.compose.ui.geometry.Offset(cx + 25f, size.height * 0.5f), androidx.compose.ui.geometry.Offset(cx + 42f, size.height * 0.5f), 3f)
                    drawLine(ArLime, androidx.compose.ui.geometry.Offset(cx, size.height * 0.5f - 42f), androidx.compose.ui.geometry.Offset(cx, size.height * 0.5f - 25f), 3f)
                    drawLine(ArLime, androidx.compose.ui.geometry.Offset(cx, size.height * 0.5f + 25f), androidx.compose.ui.geometry.Offset(cx, size.height * 0.5f + 42f), 3f)
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
                        Text(
                            if (heading.isFinite()) "方位 ${heading.roundToInt()}°  ${compassDirection(heading)}" else "方位 -- · 等待姿态传感器",
                            color = Color.White, fontSize = 14.sp, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Text(
                            "俯仰 ${if (pitch.isFinite()) pitch.roundToInt() else 0}° · 横滚 ${if (roll.isFinite()) roll.roundToInt() else 0}°",
                            color = ArLime, fontSize = 11.sp, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    Surface(
                        color = Color.Black.copy(alpha = .68f), shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (projectionReady) { if (cameraIntrinsics.calibrated) "相机标定就绪" else "视角估算模式" } else if (sensorReady) "罗盘精度较低" else "读取姿态…", Modifier.padding(horizontal = 10.dp, vertical = 9.dp), color = ArLime, fontSize = 11.sp, maxLines = 1)
                    }
                }
                if (rejoinConnectorVisible && routeMatchCandidate != null) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 58.dp),
                        color = Color(0xEE382414),
                        shape = RoundedCornerShape(18.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFB74D))
                    ) {
                        Text(
                            "接入点 ${reconnectDirectionText} · ${routeMatchCandidate!!.distanceMeters.roundToInt()} m",
                            Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                            color = Color(0xFFFFE0B2), fontSize = 12.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                        )
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
                    if (rejoinConnectorVisible || offRoute) {
                        val reconnectDistance = routeMatchCandidate?.distanceMeters ?: 0.0
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF4A211B),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF8A65))
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    if (rejoinConnectorVisible) "路线在附近 · 已显示接入辅助线"
                                    else "当前位置远离 GPX 轨迹，暂无法安全接入",
                                    color = Color(0xFFFFAB91), fontSize = 15.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                )
                                Text(
                                    if (rejoinConnectorVisible) {
                                        "目标在${reconnectDirectionText} · 距离约 ${reconnectDistance.roundToInt()} m；橙色虚线标出接入方向，若路线在画面外，按方向提示转动手机"
                                    } else {
                                        "距轨迹约 ${reconnectDistance.roundToInt()} m；请先靠近路线后再接入"
                                    },
                                    color = Color(0xFFFFCCBC), fontSize = 12.sp
                                )
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
                                Text("当前精度 ±${location?.accuracy?.roundToInt() ?: 0} m · 等待更稳定的定位（建议 ≤25 m）",
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
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("相机高度 ${"%.2f".format(Locale.CHINA, cameraHeightMeters)} m", color = Color(0xFFB9C8BE), fontSize = 11.sp)
                        Slider(
                            modifier = Modifier.weight(1f),
                            value = cameraHeightMeters,
                            onValueChange = {
                                cameraHeightMeters = it.coerceIn(1.1f, 1.9f)
                                navigationPrefs.edit().putFloat("ar_camera_height_$projectId", cameraHeightMeters).apply()
                            },
                            valueRange = 1.1f..1.9f,
                            steps = 7
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            when {
                                !locationGranted -> "需要精确位置权限"
                                locationError.isNotBlank() -> locationError
                                location == null -> "等待 GPS 卫星定位"
                                !gpsQualityOk -> "GPS 精度/新鲜度不足 · ${location!!.accuracy.roundToInt()} m"
                                offRoute -> "距 GPX 轨迹 ${routeMatchCandidate!!.distanceMeters.roundToInt()} m · ${if (routeMatchCandidate!!.distanceMeters <= 20.0) "临时接入指引" else "等待靠近路线"}"
                                gpsReliable -> "GPS 稳定 · ±${location!!.accuracy.roundToInt()} m · ${if (terrainSnapshot != null) "DEM 地形贴合" else if (routeElevations.size == routePoints.size && routeElevations.any { it != null }) "平滑高程辅助" else "地面高度近似"}"
                                else -> "正在连续匹配路线…"
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
    if (destinationPromptVisible) {
        AlertDialog(
            onDismissRequest = { destinationPromptVisible = false },
            title = { Text("目的地就在附近") },
            text = { Text("已到达 GPX 终点附近。结束本次导航后，下次打开这条路线会从起点重新开始。") },
            confirmButton = {
                TextButton(onClick = {
                    destinationPromptVisible = false
                    navigationFinished = true
                    confirmedProgress = 0.0
                    lastStableRouteMatch = route?.let { routeMatchAtProgress(it, 0.0) }
                    navigationPrefs.edit().putFloat(routeProgressKey, 0f).putInt(routeSegmentKey, 0).apply()
                    android.widget.Toast.makeText(context, "本次导航已结束，下次将从起点重新开始", android.widget.Toast.LENGTH_LONG).show()
                }) { Text("结束导航") }
            },
            dismissButton = { TextButton(onClick = { destinationPromptVisible = false }) { Text("继续导航") } }
        )
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
