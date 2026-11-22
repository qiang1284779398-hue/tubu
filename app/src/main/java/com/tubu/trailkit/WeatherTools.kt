package com.tubu.trailkit

import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil

private data class RouteWeatherPeak(
    val latitude: Double,
    val longitude: Double,
    val elevation: Double,
    val source: String
)

private fun findRouteWeatherPeak(trackData: String, elevationData: String): RouteWeatherPeak {
    val points = decodeTrackPoints(trackData)
    require(points.size >= 2) { "当前项目没有有效 GPX 轨迹，请先导入 GPX。" }

    val recordedElevations = decodeGpxElevations(elevationData)
    val recordedPeak = points.indices.mapNotNull { index ->
        val elevation = recordedElevations.getOrNull(index)
            ?.takeIf { it.isFinite() && it in -500.0..10000.0 }
        elevation?.let {
            RouteWeatherPeak(points[index].first, points[index].second, it, "GPX 文件记录的海拔")
        }
    }.maxByOrNull { it.elevation }

    if (recordedPeak != null) return recordedPeak

    // No usable GPX elevation: sample evenly along the full route using the existing Terrarium DEM.
    // Cap samples to keep first-time terrain lookup practical on a phone.
    val stride = ceil(points.size / 500.0).toInt().coerceAtLeast(1)
    val indices = (points.indices step stride).toMutableList()
    if (indices.lastOrNull() != points.lastIndex) indices.add(points.lastIndex)
    val sampleCount = indices.distinct().size

    var best: RouteWeatherPeak? = null
    indices.distinct().forEach { index ->
        val point = points[index]
        val elevation = ArTerrainDem.elevation(point.first, point.second)
        if (elevation != null && elevation.isFinite() &&
            (best == null || elevation > best!!.elevation)
        ) {
            best = RouteWeatherPeak(point.first, point.second, elevation, "DEM 地形高程（沿 GPX 轨迹采样 ${sampleCount} 点）")
        }
    }
    return best ?: error("GPX 没有海拔数据，且 DEM 高程数据暂时无法获取。请检查网络后重试。")
}

private fun windyPointForecastUrl(latitude: Double, longitude: Double): String {
    val lat = String.format(java.util.Locale.US, "%.5f", latitude)
    val lon = String.format(java.util.Locale.US, "%.5f", longitude)
    // Open Windy's own point-forecast page inside the app WebView.
    // The embed2 map does not expose the native Meteogram/model selector.
    return "https://www.windy.com/$lat/$lon?$lat,$lon,9"
}

@Composable
fun TrailWeatherDialog(project: Project, onClose: () -> Unit) {
    var peak by remember(project.trackData, project.elevationData) {
        mutableStateOf<RouteWeatherPeak?>(null)
    }
    var loading by remember(project.trackData, project.elevationData) { mutableStateOf(true) }
    var error by remember(project.trackData, project.elevationData) { mutableStateOf("") }

    LaunchedEffect(project.trackData, project.elevationData) {
        loading = true
        error = ""
        peak = null
        try {
            peak = withContext(Dispatchers.IO) {
                findRouteWeatherPeak(project.trackData, project.elevationData)
            }
        } catch (e: Exception) {
            error = e.message ?: "无法确定 GPX 轨迹最高点。"
        } finally {
            loading = false
        }
    }

    val url = remember(peak) {
        peak?.let { windyPointForecastUrl(it.latitude, it.longitude) }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.94f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Windy 山地天气", style = MaterialTheme.typography.titleLarge)
                        Text("预报目标：ECMWF  ·  显示目标：Meteogram  ·  GPX 最高点", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onClose) { Text("关闭") }
                }
                HorizontalDivider()
                when {
                    loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text("正在计算轨迹最高点…", fontSize = 13.sp)
                            Text("无 GPX 海拔时会自动沿轨迹查询 DEM", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    peak == null -> Box(Modifier.fillMaxWidth().weight(1f).padding(22.dp), contentAlignment = Alignment.Center) {
                        Text(error, color = MaterialTheme.colorScheme.error)
                    }
                    else -> {
                        val p = peak!!
                        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp)) {
                            Text(
                                "最高点  ${String.format(java.util.Locale.CHINA, "%.1f", p.elevation)} m  ·  ${String.format(java.util.Locale.US, "%.5f", p.latitude)}, ${String.format(java.util.Locale.US, "%.5f", p.longitude)}",
                                fontSize = 13.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                            Text(p.source, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        AndroidView(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            factory = { context ->
                                WebView(context).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.loadsImagesAutomatically = true
                                    settings.useWideViewPort = true
                                    settings.loadWithOverviewMode = true
                                    settings.mediaPlaybackRequiresUserGesture = true
                                    webViewClient = WebViewClient()
                                    webChromeClient = WebChromeClient()
                                    setBackgroundColor(android.graphics.Color.WHITE)
                                    loadUrl(url!!)
                                }
                            },
                            update = { webView ->
                                if (webView.url != url) webView.loadUrl(url!!)
                            },
                            onRelease = { webView ->
                                webView.stopLoading()
                                webView.destroy()
                            }
                        )
                    }
                }
                Text(
                    "已在应用内打开 Windy 原生点位预报。请在 Windy 页面选择 ECMWF 来源与 Meteogram 显示；需联网加载。山地局地天气变化快，预报不能替代现场判断。",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
