package com.tubu.trailkit

import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebSettings
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
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
    val diagnostic = remember { mutableStateOf("正在创建 3D 地图视图…") }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val assetLoader = remember(context) {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
    }

    BackHandler(onBack = onClose)

    val webView = remember(context, routeJson, assetLoader) {
        WebView(context).apply {
            setBackgroundColor(Color.rgb(18, 27, 24))
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.loadsImagesAutomatically = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.javaScriptCanOpenWindowsAutomatically = false

            addJavascriptInterface(object {
                @JavascriptInterface
                fun report(message: String) {
                    mainHandler.post { diagnostic.value = message }
                }
            }, "TrailKitBridge")

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    val line = "JS ${message.lineNumber()}: ${message.message()}"
                    Log.e("TrailKit3D", line)
                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        mainHandler.post { diagnostic.value = "网页脚本错误：${message.message()}" }
                    }
                    return true
                }
            }

            webViewClient = object : WebViewClientCompat() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    return assetLoader.shouldInterceptRequest(request.url)
                }

                override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? {
                    return assetLoader.shouldInterceptRequest(Uri.parse(url))
                }

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    mainHandler.post { diagnostic.value = "本地地图页面已打开，正在启动 JavaScript…" }
                }

                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                    Log.e("TrailKit3D", "WebView renderer gone: crashed=${detail.didCrash()}")
                    mainHandler.post { diagnostic.value = "Android 网页渲染进程异常退出，请重新打开 3D 地图。" }
                    return true
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    val routeScript = routeJson?.let {
                        "window.__trailkitPendingRoute = $it; if (window.setTrailRoute) window.setTrailRoute($it);"
                    } ?: ""
                    view.evaluateJavascript("window.__trailkitPageReady = true; $routeScript", null)
                }
            }

            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            loadUrl("https://appassets.androidplatform.net/assets/terrain3d.html")
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
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { webView },
                    update = {}
                )
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).fillMaxWidth(0.48f).padding(start = 10.dp, top = 10.dp),
                    color = ComposeColor(0xE6101B16),
                    contentColor = ComposeColor.White,
                    shape = RoundedCornerShape(10.dp),
                    tonalElevation = 0.dp
                ) {
                    Text(
                        text = diagnostic.value,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
