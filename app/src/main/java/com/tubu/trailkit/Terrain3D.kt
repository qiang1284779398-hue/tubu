package com.tubu.trailkit

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONArray
import org.json.JSONObject

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun Terrain3DDialog(projectName: String, encodedTrack: String, visible: Boolean, onClose: () -> Unit) {
    if (!visible) return
    val context = LocalContext.current
    val points = remember(encodedTrack) { decodeTrackPoints(encodedTrack) }
    val routeGeoJson = remember(points) {
        val coordinates = JSONArray()
        points.forEach { (lat, lon) -> coordinates.put(JSONArray().put(lon).put(lat)) }
        JSONObject().put("type", "Feature").put("geometry",
            JSONObject().put("type", "LineString").put("coordinates", coordinates)
        ).put("properties", JSONObject())
    }
    BackHandler(onBack = onClose)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("3D TERRAIN LAB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text("$projectName · 三维地形", style = MaterialTheme.typography.titleMedium)
                        }
                        TextButton(onClick = onClose) { Text("关闭") }
                    }
                    Text("在线预览阶段 · 地形与卫星影像需要网络。离线地形包和 GLB/OBJ 导出正在后续阶段开发。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = {
                WebView(context).apply {
                    setBackgroundColor(AndroidColor.rgb(22, 34, 30))
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = false
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            val payload = JSONObject.quote(routeGeoJson.toString())
                            view.evaluateJavascript("window.setTrailRoute && window.setTrailRoute(JSON.parse($payload));", null)
                        }
                    }
                    loadUrl("file:///android_asset/terrain3d.html")
                }
            }, update = { view ->
                val payload = JSONObject.quote(routeGeoJson.toString())
                view.evaluateJavascript("window.setTrailRoute && window.setTrailRoute(JSON.parse($payload));", null)
            })
        }
    }
}
