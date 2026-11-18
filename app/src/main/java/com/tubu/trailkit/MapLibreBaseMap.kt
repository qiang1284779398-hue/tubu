package com.tubu.trailkit

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.util.concurrent.TimeUnit
import kotlin.math.*

internal const val TRAILKIT_MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"

@Composable
internal fun MapLibreBaseMap(
    centerLat: Double,
    centerLon: Double,
    zoom: Float,
    onCameraChanged: (Double, Double, Float) -> Unit,
    onTap: (Double, Double) -> Unit,
    onLongPress: (Double, Double) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraCallback by rememberUpdatedState(onCameraChanged)
    val tapCallback by rememberUpdatedState(onTap)
    val longCallback by rememberUpdatedState(onLongPress)
    val mapView = remember(context) {
        MapLibre.getInstance(context.applicationContext)
        val dispatcher = Dispatcher().apply { maxRequests = 4; maxRequestsPerHost = 4 }
        val http = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "TrailKit-Android/1.3 (+https://github.com/qiang1284779398-hue/tubu)")
                    .build()
                chain.proceed(request)
            }.build()
        HttpRequestUtil.setOkHttpClient(http)
        MapView(context).also { it.onCreate(null) }
    }
    var map by remember(mapView) { mutableStateOf<MapLibreMap?>(null) }
    var ready by remember(mapView) { mutableStateOf(false) }

    DisposableEffect(mapView, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            try { mapView.onPause(); mapView.onStop(); mapView.onDestroy() } catch (_: Exception) {}
            map = null
            ready = false
        }
    }

    AndroidView(
        factory = {
            mapView.getMapAsync { nativeMap ->
                map = nativeMap
                nativeMap.uiSettings.isCompassEnabled = false
                nativeMap.addOnCameraIdleListener {
                    val pos = nativeMap.cameraPosition
                    val target = pos.target
                    if (target != null) cameraCallback(target.latitude, target.longitude, pos.zoom.toFloat())
                }
                nativeMap.addOnMapClickListener { point ->
                    tapCallback(point.latitude, point.longitude)
                    true
                }
                nativeMap.addOnMapLongClickListener { point ->
                    longCallback(point.latitude, point.longitude)
                    true
                }
                nativeMap.setStyle(TRAILKIT_MAP_STYLE) {
                    nativeMap.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(centerLat, centerLon))
                        .zoom(zoom.toDouble().coerceIn(2.0, 18.0))
                        .build()
                    ready = true
                }
            }
            mapView
        },
        modifier = Modifier.fillMaxSize(),
        update = {
            val nativeMap = map
            if (ready && nativeMap != null) {
                val p = nativeMap.cameraPosition
                val target = p.target
                if (target == null || abs(target.latitude - centerLat) > 0.00015 ||
                    abs(target.longitude - centerLon) > 0.00015 ||
                    abs(p.zoom - zoom.toDouble()) > 0.08) {
                    nativeMap.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(centerLat, centerLon))
                        .zoom(zoom.toDouble().coerceIn(2.0, 18.0))
                        .build()
                }
            }
        }
    )
}

private fun lonTile(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * (1 shl z)
private fun latTile(lat: Double, z: Int): Double {
    val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
    return (1.0 - ln(tan(r) + 1.0 / cos(r)) / Math.PI) / 2.0 * (1 shl z)
}
private fun estimatedTileCount(west: Double, south: Double, east: Double, north: Double, minZoom: Int, maxZoom: Int): Int {
    var total = 0
    for (z in minZoom..maxZoom) {
        val x1 = floor(lonTile(west, z)).toInt()
        val x2 = floor(lonTile(east, z)).toInt()
        val y1 = floor(latTile(north, z)).toInt()
        val y2 = floor(latTile(south, z)).toInt()
        total += (x2 - x1 + 1).coerceAtLeast(1) * (y2 - y1 + 1).coerceAtLeast(1)
    }
    return total
}

@Composable
internal fun RouteOfflineDownloadCard(points: List<Pair<Double, Double>>, projectId: String) {
    val context = LocalContext.current
    var progress by remember(projectId) { mutableFloatStateOf(0f) }
    var downloaded by remember(projectId) { mutableLongStateOf(0L) }
    var required by remember(projectId) { mutableLongStateOf(0L) }
    var bytes by remember(projectId) { mutableLongStateOf(0L) }
    var busy by remember(projectId) { mutableStateOf(false) }
    var complete by remember(projectId) { mutableStateOf(false) }
    var message by remember(projectId) { mutableStateOf("按整条 GPX 轨迹自动计算离线地图范围，包含约 2 km 安全缓冲。") }
    var region by remember(projectId) { mutableStateOf<OfflineRegion?>(null) }

    fun startDownload() {
        if (points.size < 2 || busy) return
        try {
            val minLat = points.minOf { it.first }
            val maxLat = points.maxOf { it.first }
            val minLon = points.minOf { it.second }
            val maxLon = points.maxOf { it.second }
            val midLat = (minLat + maxLat) / 2.0
            val latPad = 2000.0 / 111320.0
            val lonPad = 2000.0 / (111320.0 * cos(Math.toRadians(midLat)).coerceAtLeast(0.15))
            val south = (minLat - latPad).coerceAtLeast(-85.0)
            val north = (maxLat + latPad).coerceAtMost(85.0)
            val west = (minLon - lonPad).coerceAtLeast(-180.0)
            val east = (maxLon + lonPad).coerceAtMost(180.0)
            val minZoom = 8
            var maxZoom = 16
            while (maxZoom > 10 && estimatedTileCount(west, south, east, north, minZoom, maxZoom) > 2600) maxZoom--
            val estimate = estimatedTileCount(west, south, east, north, minZoom, maxZoom)
            if (estimate > 3000) {
                message = "路线范围太大，已无法在服务方单次 3,000 瓦片限制内下载。可缩小路线范围后再试。"
                return
            }
            val manager = OfflineManager.getInstance(context)
            manager.setOfflineMapboxTileCountLimit(3000L)
            val bounds = LatLngBounds.Builder()
                .include(LatLng(north, east))
                .include(LatLng(south, west))
                .build()
            val definition = OfflineTilePyramidRegionDefinition(
                TRAILKIT_MAP_STYLE, bounds, minZoom.toDouble(), maxZoom.toDouble(),
                1.0f, true
            )
            val metadata = JSONObject()
                .put("name", "TrailKit-$projectId")
                .put("projectId", projectId)
                .put("provider", "OpenFreeMap")
                .toString().toByteArray(Charsets.UTF_8)
            busy = true
            complete = false
            progress = 0f
            downloaded = 0L
            required = 0L
            bytes = 0L
            message = "正在创建离线地图包（约 $estimate 个地图瓦片，缩放 $minZoom–$maxZoom）…"
            manager.createOfflineRegion(definition, metadata, object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    region = offlineRegion
                    offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            downloaded = status.completedResourceCount
                            required = status.requiredResourceCount
                            bytes = status.completedResourceSize
                            progress = if (status.requiredResourceCount > 0) {
                                (status.completedResourceCount.toFloat() / status.requiredResourceCount.toFloat()).coerceIn(0f, 1f)
                            } else 0f
                            if (status.completedResourceSize > 50L * 1024L * 1024L) {
                                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                busy = false
                                message = "已达到服务方单次 50 MB 上限，当前下载部分已保留；请缩小范围或降低缩放级别。"
                            } else if (status.isComplete) {
                                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                busy = false
                                complete = true
                                progress = 1f
                                message = "离线地图下载完成，断网后也可以打开此区域。"
                            } else {
                                message = "正在下载地图资源…"
                            }
                        }
                        override fun onError(error: OfflineRegionError) {
                            busy = false
                            message = "地图下载出错：${error.message}"
                        }
                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            busy = false
                            message = "达到服务方瓦片限制（$limit），请缩小范围或降低缩放级别。"
                        }
                    })
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }
                override fun onError(error: String) {
                    busy = false
                    message = "无法创建离线地图：$error"
                }
            })
        } catch (e: Exception) {
            busy = false
            message = "启动离线地图下载失败：${e.message ?: "未知错误"}"
        }
    }

    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("OpenFreeMap · 轨迹离线地图", style = MaterialTheme.typography.titleSmall)
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = { startDownload() }, enabled = !busy && points.size >= 2) {
                    Text(if (complete) "重新下载" else if (busy) "下载中" else "下载地图")
                }
            }
            if (busy || complete) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text("${(progress * 100).toInt()}% · $downloaded/$required 项资源 · ${(bytes / 1024 / 1024)} MB", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
