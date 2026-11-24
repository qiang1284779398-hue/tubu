package com.tubu.trailkit

import android.graphics.Color
import android.os.SystemClock
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun Terrain3DDialog(
    projectId: String,
    projectName: String,
    trackCoordinates: List<Pair<Double, Double>>,
    elevations: List<Double?>,
    waypoints: List<TerrainWaypointSnapshot>,
    visible: Boolean,
    onClose: () -> Unit
) {
    if (!visible) return
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var payload by remember(projectId, trackCoordinates, elevations, waypoints) {
        mutableStateOf<String?>(null)
    }
    val tileCache = remember(context, projectId) { TerrainTileCache(context.applicationContext) }
    val assetLoader = remember(context) {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
    }
    var status by remember { mutableStateOf("正在整理路线与航点数据…") }
    var selectedWaypoint by remember { mutableStateOf<JSONObject?>(null) }
    var walkingMode by remember { mutableStateOf(false) }
    val isWalkingMode by rememberUpdatedState(walkingMode)
    val webView = remember(context, projectId) { WebView(context) }
    val bridge = remember(webView, payload) {
        payload?.let { readyPayload ->
            TerrainLabBridge(
                webView = webView,
                payload = readyPayload,
                tileCache = tileCache,
                onStatus = { status = it },
                onWaypoint = { raw -> selectedWaypoint = runCatching { JSONObject(raw) }.getOrNull() },
                onWalk = { walkingMode = true }
            )
        }
    }

    LaunchedEffect(projectId, trackCoordinates, elevations, waypoints) {
        val startedAt = SystemClock.elapsedRealtime()
        status = "路线构建开始：${trackCoordinates.size} 个 GPX 点 · ${waypoints.size} 个航点"
        try {
            val built = withContext(Dispatchers.Default) {
                buildTerrainLabPayload(projectId, trackCoordinates, elevations, waypoints)
            }
            payload = built
            val chunks = terrainPayloadChunks(built)
            status = "路线构建完成：${trackCoordinates.size} 点 · ${built.length} 字符 · ${chunks.size} 块 · ${(SystemClock.elapsedRealtime() - startedAt)} ms"
        } catch (error: Throwable) {
            status = "路线构建异常：${error.stackTraceToString().take(900)}"
            payload = null
        }
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
                    if (isWalkingMode) {
                        webView.onPause()
                    } else {
                        webView.onResume()
                        webView.evaluateJavascript("window.TerrainLab&&window.TerrainLab.resume()", null)
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            webView.stopLoading()
            webView.onPause()
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
                val readyBridge = bridge
                if (readyBridge != null) {
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
                                addJavascriptInterface(readyBridge, "TrailKitBridge")
                                webViewClient = object : androidx.webkit.WebViewClientCompat() {
                                    override fun shouldInterceptRequest(
                                        view: WebView?,
                                        request: WebResourceRequest
                                    ): WebResourceResponse? {
                                        if (request.method.equals("GET", ignoreCase = true)) {
                                            tileCache.intercept(request.url.toString())?.let { return it }
                                        }
                                        return assetLoader.shouldInterceptRequest(request.url)
                                            ?: super.shouldInterceptRequest(view, request)
                                    }
                                }
                                webChromeClient = object : WebChromeClient() {
                                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                        if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                            status = "地图 JS 控制台异常：${message.message().take(900)}"
                                        }
                                        return true
                                    }
                                }
                                loadUrl("https://appassets.androidplatform.net/assets/terrain3d.html")
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                        Text("长路线会在后台生成简化层级与真实宽度缎带，不阻塞主界面", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
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
                                TextButton(onClick = {
                                    selectedWaypoint = null
                                    webView.evaluateJavascript("window.TerrainLab&&window.TerrainLab.clearSelectedWaypoint()", null)
                                }) { Text("收起") }
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
        NativeTerrainWalkDialog(projectName, trackCoordinates, true) { walkingMode = false }
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
    private val payloadDispatchStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    @JavascriptInterface fun onReady() {
        if (!payloadDispatchStarted.compareAndSet(false, true)) return
        webView.post {
            val chunks = terrainPayloadChunks(payload)
            onStatus("路线传输开始：${payload.length} 字符 · ${chunks.size} 块（每块不超过 48 Ki 字符）")
            fun sendChunk(index: Int) {
                if (index >= chunks.size) {
                    onStatus("路线分块发送完成：${chunks.size}/${chunks.size}，等待页面校验与 setData…")
                    webView.evaluateJavascript("window.TerrainLab&&window.TerrainLab.finishDataTransfer()", null)
                    return
                }
                val script = "window.TerrainLab&&window.TerrainLab.appendDataChunk($index,${JSONObject.quote(chunks[index])})"
                webView.evaluateJavascript(script) {
                    if (index == 0 || (index + 1) % 10 == 0 || index + 1 == chunks.size) {
                        onStatus("路线分块发送：${index + 1}/${chunks.size}")
                    }
                    sendChunk(index + 1)
                }
            }
            webView.evaluateJavascript(
                "window.TerrainLab&&window.TerrainLab.beginDataTransfer(${chunks.size})"
            ) { sendChunk(0) }
        }
    }
    @JavascriptInterface fun onStatus(message: String) {
        webView.post { onStatus(message.take(900)) }
    }
    @JavascriptInterface fun onWaypointClick(properties: String) {
        webView.post { onWaypoint(properties) }
    }
    @JavascriptInterface fun onWalkRequested() {
        webView.post { onWalk() }
    }
    @JavascriptInterface fun getCacheStats(): String = tileCache.statsJson()
}


internal fun terrainPayloadChunks(payload: String, maxChars: Int = 48 * 1024): List<String> {
    require(maxChars > 0) { "maxChars must be positive" }
    return payload.chunked(maxChars)
}

internal fun buildTerrainLabPayload(
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
