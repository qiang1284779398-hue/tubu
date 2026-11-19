package com.tubu.trailkit

import android.graphics.Color
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ConsoleMessage
import android.util.Log
import android.view.View
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

@Composable
internal fun Terrain3DDialog(projectName: String, encodedTrack: String, visible: Boolean, onClose: () -> Unit) {
    if (!visible) return
    val context = LocalContext.current
    val points = remember(encodedTrack) { decodeTrackPoints(encodedTrack) }
    val routeJson = remember(points) {
        if (points.size > 1) {
            val coordinates = JSONArray()
            points.forEach { p -> coordinates.put(JSONArray().put(p.second).put(p.first)) }
            JSONObject()
                .put("type", "Feature")
                .put("properties", JSONObject())
                .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coordinates))
                .toString()
        } else null
    }
    BackHandler(onBack = onClose)

    val webView = remember(context, routeJson) {
        WebView(context).apply {
            setBackgroundColor(Color.rgb(18, 27, 24))
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.allowFileAccessFromFileURLs = true
            settings.allowUniversalAccessFromFileURLs = true
            settings.loadsImagesAutomatically = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    Log.e("TrailKit3D", "JS ${message.lineNumber()}: ${message.message()}")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                    super.onReceivedError(view, request, error)
                    Log.e("TrailKit3D", "Web error ${error.errorCode}: ${error.description} ${request.url}")
                }\n                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    val routeScript = routeJson?.let {
                        "window.__trailkitPendingRoute = $it; if (window.setTrailRoute) window.setTrailRoute($it);"
                    } ?: ""
                    view.evaluateJavascript("window.__trailkitPageReady = true; $routeScript", null)
                }
            }
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            loadUrl("file:///android_asset/terrain3d.html")
        }
    }

    DisposableEffect(webView) {
        onDispose {
            try {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            } catch (_: Exception) {}
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("TRUE 3D TERRAIN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text("$projectName · DEM 三维地形", style = MaterialTheme.typography.titleMedium)
                        }
                        TextButton(onClick = onClose) { Text("关闭") }
                    }
                    Text("Esri 卫星影像 · Terrarium 高程数据 · 山体起伏 · GPX 贴地显示",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { webView },
                update = {}
            )
        }
    }
}
