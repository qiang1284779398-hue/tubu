package com.tubu.trailkit

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun Terrain3DDialog(
    projectId: String,
    projectName: String,
    encodedTrack: String,
    encodedElevations: String,
    encodedWaypoints: String,
    visible: Boolean,
    onClose: () -> Unit
) {
    if (!visible) return
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val track = remember(encodedTrack) { decodeTrackPoints(encodedTrack) }
    val elevations = remember(encodedElevations) { decodeGpxElevations(encodedElevations) }
    val waypoints = remember(encodedWaypoints) { buildTerrainWaypointSnapshot(encodedWaypoints) }
    val payload = remember(projectId, track, elevations, waypoints) {
        buildTerrainLabPayload(projectId, track, elevations, waypoints)
    }
    val tileCache = remember(context) { TerrainTileCache(context.applicationContext) }
    val assetLoader = remember(context) {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
    }
    var status by remember { mutableStateOf("正在启动 MapLibre 三维地形…") }
    var selectedWaypoint by remember { mutableStateOf<JSONObject?>(null) }
    var walkingMode by remember { mutableStateOf(false) }
    val webView = remember(context) { WebView(context) }
    val bridge = remember(webView, payload) {
        TerrainLabBridge(
            webView = webView,
            payload = payload,
            tileCache = tileCache,
            onStatus = { status = it },
            onWaypoint = { raw -> selectedWaypoint = runCatching { JSONObject(raw) }.getOrNull() },
            onWalk = { walkingMode = true }
        )
    }

    LaunchedEffect(walkingMode) {
        if (walkingMode) {
            webView.evaluateJavascript("window.TerrainLab&&window.TerrainLab.pause()", null)
            webView.onPause()
        } else {
            webView.onResume()
            webView.evaluateJavascript("window.TerrainLab&&window.TerrainLab.resume()", null)
        }
    }

    DisposableEffect(webView, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    webView.evaluateJavascript("window.TerrainLab&&window.TerrainLab.pause()", null)
                    webView.onPause()
                }
                Lifecycle.Event.ON_START -> {
                    webView.onResume()
                    webView.evaluateJavascript("window.TerrainLab&&window.TerrainLab.resume()", null)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            webView.stopLoading()
            webView.removeJavascriptInterface("TrailKitBridge")
            webView.loadUrl("about:blank")
            webView.destroy()
            tileCache.close()
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("3D TERRAIN LAB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text("$projectName · 三维地形总览", style = MaterialTheme.typography.titleMedium)
                    }
                    TextButton(onClick = { walkingMode = true }) { Text("原生行走") }
                    TextButton(onClick = onClose) { Text("关闭") }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = {
                        webView.apply {
                            setBackgroundColor(Color.rgb(18, 29, 24))
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadsImagesAutomatically = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.setSupportZoom(false)
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            addJavascriptInterface(bridge, "TrailKitBridge")
                            webViewClient = object : androidx.webkit.WebViewClientCompat() {
                                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest) =
                                    tileCache.intercept(request.url.toString())
                                        ?: assetLoader.shouldInterceptRequest(request.url)
                                        ?: super.shouldInterceptRequest(view, request)
                                override fun onReceivedError(
                                    view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?
                                ) {
                                    if (request?.isForMainFrame == true) status = "地图页面加载失败：${error?.description ?: "未知错误"}"
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                        status = "地图引擎：${message.message().take(180)}"
                                    }
                                    return true
                                }
                            }
                            loadUrl("https://appassets.androidplatform.net/assets/terrain3d.html")
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                selectedWaypoint?.let { waypoint ->
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                        tonalElevation = 5.dp
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(waypoint.optString("name", "途经点"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                TextButton(onClick = { selectedWaypoint = null }) { Text("收起") }
                            }
                            val description = waypoint.optString("description")
                            if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodyMedium)
                            val elevation = waypoint.optDouble("elevation", Double.NaN)
                            if (elevation.isFinite()) Text("海拔：${elevation.toInt()} m", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            Surface {
                Text(status, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (walkingMode) {
        NativeTerrainWalkDialog(projectName, encodedTrack, true) { walkingMode = false }
    }
}

private class TerrainLabBridge(
    private val webView: WebView,
    private val payload: String,
    private val tileCache: TerrainTileCache,
    private val onStatus: (String) -> Unit,
    private val onWaypoint: (String) -> Unit,
    private val onWalk: () -> Unit
) {
    @JavascriptInterface fun onReady() {
        webView.post {
            webView.evaluateJavascript(
                "window.TerrainLab&&window.TerrainLab.setData(${JSONObject.quote(payload)})",
                null
            )
        }
    }
    @JavascriptInterface fun onStatus(message: String) {
        webView.post { onStatus(message.take(220)) }
    }
    @JavascriptInterface fun onWaypointClick(properties: String) {
        webView.post { onWaypoint(properties) }
    }
    @JavascriptInterface fun onWalkRequested() {
        webView.post { onWalk() }
    }
    @JavascriptInterface fun onViewportChanged(zoom: Double, bearing: Double, pitch: Double) {
        webView.post { onStatus("视角：z${"%.1f".format(zoom)} · 方位 ${bearing.toInt()}° · 俯仰 ${pitch.toInt()}°") }
    }
    @JavascriptInterface fun getCacheStats(): String = tileCache.statsJson()
}

private fun buildTerrainLabPayload(
    projectId: String,
    coordinates: List<Pair<Double, Double>>,
    elevations: List<Double?>,
    waypoints: List<TerrainWaypointSnapshot>
): String {
    fun featureCollection(features: JSONArray) = JSONObject()
        .put("type", "FeatureCollection")
        .put("features", features)

    val lineFeatures = JSONArray()
    val ribbonFeatures = JSONArray()
    TerrainRouteGeometry.buildLods(coordinates, elevations, waypoints).forEach { lod ->
        val lineCoordinates = JSONArray()
        lod.line.forEach { point ->
            lineCoordinates.put(JSONArray().put(point.lon).put(point.lat))
        }
        lineFeatures.put(
            JSONObject().put("type", "Feature")
                .put("properties", JSONObject().put("lod", lod.id))
                .put("geometry", JSONObject().put("type", "LineString").put("coordinates", lineCoordinates))
        )
        val ring = JSONArray()
        lod.ribbon.forEach { (lon, lat) -> ring.put(JSONArray().put(lon).put(lat)) }
        ribbonFeatures.put(
            JSONObject().put("type", "Feature")
                .put("properties", JSONObject().put("lod", lod.id))
                .put("geometry", JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(ring)))
        )
    }

    val waypointFeatures = JSONArray()
    waypoints.forEach { waypoint ->
        waypointFeatures.put(
            JSONObject().put("type", "Feature").put("id", waypoint.id)
                .put("properties", JSONObject()
                    .put("id", waypoint.id)
                    .put("name", waypoint.name)
                    .put("description", waypoint.description)
                    .put("elevation", waypoint.elevation ?: JSONObject.NULL)
                    .put("priority", waypoint.priority)
                    .put("spriteId", "wp-${waypoint.id}"))
                .put("geometry", JSONObject().put("type", "Point")
                    .put("coordinates", JSONArray().put(waypoint.lon).put(waypoint.lat)))
        )
    }

    val elevationProfile = JSONArray()
    elevations.forEach { elevationProfile.put(it ?: JSONObject.NULL) }
    val start = coordinates.firstOrNull()
    val end = coordinates.lastOrNull()
    return JSONObject()
        .put("projectId", projectId)
        .put("routeLine", featureCollection(lineFeatures))
        .put("routeRibbon", featureCollection(ribbonFeatures))
        .put("waypoints", featureCollection(waypointFeatures))
        .put("elevationProfile", elevationProfile)
        .put("start", start?.let { JSONArray().put(it.second).put(it.first) } ?: JSONObject.NULL)
        .put("end", end?.let { JSONArray().put(it.second).put(it.first) } ?: JSONObject.NULL)
        .toString()
}
