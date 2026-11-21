package com.tubu.trailkit

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.PointF
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
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

internal const val TRAILKIT_MAP_STYLE = "https://styles.maptoolkit.org/street.json"
// OpenFreeMap remains the offline-download source; Maptoolkit Community tiles prohibit bulk/offline extraction.
internal const val TRAILKIT_OFFLINE_VECTOR_STYLE = "https://tiles.openfreemap.org/styles/liberty"

// Prevent the surrounding Compose scroll container from stealing map drag gestures.
private class GestureFriendlyMapView(context: Context) : MapView(context) {
    var onUserGesture: (() -> Unit)? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var userGestureSent = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                userGestureSent = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Pinch/tilt/rotate gestures are also intentional map exploration.
                if (!userGestureSent) {
                    userGestureSent = true
                    onUserGesture?.invoke()
                }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (!userGestureSent && hypot((event.x-downX).toDouble(), (event.y-downY).toDouble()) >= touchSlop) {
                    userGestureSent = true
                    onUserGesture?.invoke()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                userGestureSent = false
            }
        }
        return super.dispatchTouchEvent(event)
    }
}

@Composable
internal fun MapLibreBaseMap(
    centerLat: Double,
    centerLon: Double,
    zoom: Float,
    mapBearing: Float = 0f,
    styleUrl: String = TRAILKIT_MAP_STYLE,
    onCameraChanged: (Double, Double, Float) -> Unit,
    onUserGesture: () -> Unit,
    onTap: (Double, Double) -> Unit,
    onLongPress: (Double, Double) -> Unit,
    onProjectionChanged: (((Double, Double) -> PointF)?) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraCallback by rememberUpdatedState(onCameraChanged)
    val userGestureCallback by rememberUpdatedState(onUserGesture)
    val tapCallback by rememberUpdatedState(onTap)
    val longCallback by rememberUpdatedState(onLongPress)
    val projectionCallback by rememberUpdatedState(onProjectionChanged)
    val mapView = remember(context) {
        MapLibre.getInstance(context.applicationContext)
        // Keep recently viewed raster/vector resources on disk for offline use.
        // 512 MiB gives high-zoom imagery tiles room to remain available between launches.
        try { OfflineManager.getInstance(context).setMaximumAmbientCacheSize(512L * 1024L * 1024L, null) } catch (_: Exception) {}
        val dispatcher = Dispatcher().apply { maxRequests = 4; maxRequestsPerHost = 4 }
        val http = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val url = original.url
                val request = original.newBuilder()
                    .header("User-Agent", "TrailKit-Android/1.3 (+https://github.com/qiang1284779398-hue/tubu)")
                    .build()
                chain.proceed(request)
            }.build()
        HttpRequestUtil.setOkHttpClient(http)
        GestureFriendlyMapView(context).also { it.onCreate(null) }
    }
    var map by remember(mapView) { mutableStateOf<MapLibreMap?>(null) }
    var ready by remember(mapView) { mutableStateOf(false) }
    var appliedStyleUrl by remember(mapView) { mutableStateOf("") }

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
            projectionCallback(null)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    AndroidView(
        factory = {
            mapView.getMapAsync { nativeMap ->
                map = nativeMap
                nativeMap.uiSettings.isCompassEnabled = false
                // Let MapLibre handle gestures natively. Publish camera state at up to 30 fps: MapLibre keeps native gestures/rendering
                // independent, while the Compose GPX/MBTiles overlay stays responsive without
                // forcing a full overlay recomposition on every GL frame.
                var lastCameraPublishMs = 0L
                fun publishCamera(force: Boolean = false) {
                    val now = SystemClock.elapsedRealtime()
                    if (!force && now - lastCameraPublishMs < 33L) return
                    lastCameraPublishMs = now
                    val pos = nativeMap.cameraPosition
                    val target = pos.target ?: return
                    cameraCallback(target.latitude, target.longitude, pos.zoom.toFloat())
                }
                nativeMap.addOnCameraMoveListener { publishCamera() }
                nativeMap.addOnCameraIdleListener { publishCamera(true) }
                // The GPX overlay is drawn in an unrotated Web-Mercator screen space.
                nativeMap.uiSettings.isScrollGesturesEnabled = true
                nativeMap.uiSettings.isZoomGesturesEnabled = true
                nativeMap.uiSettings.isDoubleTapGesturesEnabled = true
                nativeMap.uiSettings.isQuickZoomGesturesEnabled = true
                nativeMap.uiSettings.isRotateGesturesEnabled = false
                // Enable two-finger tilt so Maptoolkit Street's native building-extrusion layer
                // can be inspected in 3D. Rotation stays disabled because the route overlay
                // is intentionally north-up.
                nativeMap.uiSettings.isTiltGesturesEnabled = true
                nativeMap.addOnMapClickListener { point ->
                    tapCallback(point.latitude, point.longitude)
                    true
                }
                nativeMap.addOnMapLongClickListener { point ->
                    longCallback(point.latitude, point.longitude)
                    true
                }
                nativeMap.setStyle(styleUrl) {
                    nativeMap.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(centerLat, centerLon))
                        .zoom(zoom.toDouble().coerceIn(2.0, 20.0))
                        .bearing(mapBearing.toDouble())
                        .tilt(nativeMap.cameraPosition.tilt)
                        .build()
                    ready = true
                    appliedStyleUrl = styleUrl
                    projectionCallback { lat, lon -> nativeMap.projection.toScreenLocation(LatLng(lat, lon)) }
                }
            }
            mapView
        },
        modifier = Modifier.fillMaxSize(),
        update = { view ->
            (view as? GestureFriendlyMapView)?.onUserGesture = { userGestureCallback() }
            val nativeMap = map
            if (ready && nativeMap != null) {
                if (appliedStyleUrl != styleUrl) {
                    val camera = nativeMap.cameraPosition
                    nativeMap.setStyle(styleUrl) {
                        nativeMap.cameraPosition = camera
                        appliedStyleUrl = styleUrl
                        projectionCallback { lat, lon -> nativeMap.projection.toScreenLocation(LatLng(lat, lon)) }
                    }
                }
                val p = nativeMap.cameraPosition
                val target = p.target
                if (target == null || abs(target.latitude - centerLat) > 0.00015 ||
                    abs(target.longitude - centerLon) > 0.00015 ||
                    abs(p.zoom - zoom.toDouble()) > 0.08 || abs(p.bearing - mapBearing.toDouble()) > 0.5) {
                    nativeMap.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(centerLat, centerLon))
                        .zoom(zoom.toDouble().coerceIn(2.0, 20.0))
                        .bearing(mapBearing.toDouble())
                        .build()
                }
            }
        }
    )
    // Keep attribution matched to the active imagery/vector provider.
    Row(
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            styleUrl.contains("satellite") -> Text(
                "Esri · World Imagery",
                modifier = Modifier.clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.esri.com/en-us/legal/terms/full-master-agreement"))) },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            else -> {
                Text(
                    "Maptoolkit",
                    modifier = Modifier.clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.maptoolkit.org/"))) },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "© OpenStreetMap",
                    modifier = Modifier.clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright"))) },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
    }
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

private data class OfflineBatchBounds(val west:Double,val south:Double,val east:Double,val north:Double)

private fun splitOfflineBounds(west:Double,south:Double,east:Double,north:Double,minZoom:Int,maxZoom:Int,targetTiles:Int=250):List<OfflineBatchBounds> {
    val result=mutableListOf<OfflineBatchBounds>()
    fun split(box:OfflineBatchBounds,depth:Int) {
        val count=estimatedTileCount(box.west,box.south,box.east,box.north,minZoom,maxZoom)
        if(count<=targetTiles || depth>=18) { result.add(box); return }
        val spanX=abs(lonTile(box.east,maxZoom)-lonTile(box.west,maxZoom))
        val spanY=abs(latTile(box.south,maxZoom)-latTile(box.north,maxZoom))
        if(spanX>=spanY && box.east-box.west>0.00001) {
            val mid=(box.west+box.east)/2.0
            split(OfflineBatchBounds(box.west,box.south,mid,box.north),depth+1)
            split(OfflineBatchBounds(mid,box.south,box.east,box.north),depth+1)
        } else if(box.north-box.south>0.00001) {
            val mid=(box.south+box.north)/2.0
            split(OfflineBatchBounds(box.west,box.south,box.east,mid),depth+1)
            split(OfflineBatchBounds(box.west,mid,box.east,box.north),depth+1)
        } else result.add(box)
    }
    split(OfflineBatchBounds(west,south,east,north),0)
    return result
}

internal const val TRAILKIT_TERRAIN_STYLE = "https://raw.githubusercontent.com/qiang1284779398-hue/tubu/main/map-styles/trailkit-terrain.json"
internal const val TRAILKIT_ESRI_SATELLITE_STYLE = "https://raw.githubusercontent.com/qiang1284779398-hue/tubu/main/map-styles/trailkit-esri-satellite.json"

@Composable
internal fun RouteOfflineDownloadCard(points:List<Pair<Double,Double>>,projectId:String,selectedStyle:String,onSelectStyle:(String)->Unit) {
    val context=LocalContext.current
    var progress by remember(projectId){mutableFloatStateOf(0f)}
    var batchIndex by remember(projectId){mutableIntStateOf(0)}
    var batchTotal by remember(projectId){mutableIntStateOf(0)}
    var downloaded by remember(projectId){mutableLongStateOf(0L)}
    var required by remember(projectId){mutableLongStateOf(0L)}
    var bytes by remember(projectId){mutableLongStateOf(0L)}
    var busy by remember(projectId){mutableStateOf(false)}
    var complete by remember(projectId){mutableStateOf(false)}
    var message by remember(projectId){mutableStateOf("Esri 卫星图使用 MapLibre 客户端缓存（上限 512 MB）。联网浏览过的区域在缓存仍保留且服务端允许缓存时，可能支持离线显示；不提供批量卫星图下载。")}

    fun startDownload(styleUrl:String,label:String) {
        if(points.size<2 || busy)return
        try {
            val minLat=points.minOf{it.first};val maxLat=points.maxOf{it.first}
            val minLon=points.minOf{it.second};val maxLon=points.maxOf{it.second}
            val midLat=(minLat+maxLat)/2.0
            val latPad=2000.0/111320.0
            val lonPad=2000.0/(111320.0*cos(Math.toRadians(midLat)).coerceAtLeast(0.15))
            val south=(minLat-latPad).coerceAtLeast(-85.0)
            val north=(maxLat+latPad).coerceAtMost(85.0)
            val west=(minLon-lonPad).coerceAtLeast(-180.0)
            val east=(maxLon+lonPad).coerceAtMost(180.0)
            val minZoom=8;val maxZoom=16
            val batches=splitOfflineBounds(west,south,east,north,minZoom,maxZoom,250)
            if(batches.isEmpty()){message="没有计算出有效下载区域";return}
            val manager=OfflineManager.getInstance(context)
            manager.setOfflineMapboxTileCountLimit(3000L)
            busy=true;complete=false;progress=0f;batchIndex=0;batchTotal=batches.size
            downloaded=0L;required=0L;bytes=0L
            message="正在准备$label：路线范围拆分为 ${batches.size} 个小区域…"
            lateinit var processNext:(Int)->Unit
            processNext={index->
                if(index>=batches.size) {
                    busy=false;complete=true;progress=1f
                    message="${label}全部下载完成，共 ${batches.size} 个离线小区域。可切换图层，断网查看已下载范围。"
                } else {
                    batchIndex=index+1
                    val b=batches[index]
                    val bounds=LatLngBounds.Builder().include(LatLng(b.north,b.east)).include(LatLng(b.south,b.west)).build()
                    val definition=OfflineTilePyramidRegionDefinition(styleUrl,bounds,minZoom.toDouble(),maxZoom.toDouble(),1.0f,true)
                    val metadata=JSONObject().put("name","TrailKit-${label}-${projectId}-${index+1}").put("projectId",projectId).put("layer",label).put("batch",index+1).put("provider",if(label=="卫星图")"Esri World Imagery" else "EOX Terrain").toString().toByteArray(Charsets.UTF_8)
                    manager.createOfflineRegion(definition,metadata,object:OfflineManager.CreateOfflineRegionCallback {
                        override fun onCreate(offlineRegion:OfflineRegion) {
                            var finished=false
                            offlineRegion.setObserver(object:OfflineRegion.OfflineRegionObserver {
                                override fun onStatusChanged(status:OfflineRegionStatus) {
                                    if(finished)return
                                    downloaded=status.completedResourceCount;required=status.requiredResourceCount;bytes=status.completedResourceSize
                                    val local=status.requiredResourceCount.takeIf{it>0}?.let{status.completedResourceCount.toFloat()/it.toFloat()}?:0f
                                    progress=((index+local)/batches.size.toFloat()).coerceIn(0f,1f)
                                    if(status.completedResourceSize>=48L*1024L*1024L && !status.isComplete) {
                                        finished=true;offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE);busy=false
                                        message="第 ${index+1}/${batches.size} 个区域接近 48 MB 安全阈值，已暂停；已完成区域保留。请降低最高缩放级别后重试。"
                                    } else if(status.isComplete) {
                                        finished=true;offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                        message="${label}下载中：已完成 ${index+1}/${batches.size} 个小区域…"
                                        processNext(index+1)
                                    } else message="${label}下载中：区域 ${index+1}/${batches.size}，${status.completedResourceCount}/${status.requiredResourceCount} 项资源…"
                                }
                                override fun onError(error:OfflineRegionError) {
                                    if(finished)return
                                    finished=true;busy=false;message="第 ${index+1} 个区域下载失败：${error.message}。已完成区域保留。"
                                }
                                override fun mapboxTileCountLimitExceeded(limit:Long) {
                                    if(finished)return
                                    finished=true;offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE);busy=false
                                    message="服务端瓦片限制为 $limit；已完成区域保留。请降低最高缩放级别后重试。"
                                }
                            })
                            offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                        }
                        override fun onError(error:String){busy=false;message="无法创建第 ${index+1} 个离线区域：$error"}
                    })
                }
            }
            processNext(0)
        } catch(e:Exception){busy=false;message="启动离线下载失败："+(e.message ?: "未知错误")}
    }

    Card(modifier=Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
            Text("离线地图下载",style=MaterialTheme.typography.titleSmall)
            Text(message,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth()) {
                OutlinedButton(onClick={startDownload(TRAILKIT_OFFLINE_VECTOR_STYLE,"标准矢量图")},enabled=!busy&&points.size>=2,modifier=Modifier.weight(1f)){Text("下载矢量")}
                OutlinedButton(onClick={startDownload(TRAILKIT_TERRAIN_STYLE,"地形图")},enabled=!busy&&points.size>=2,modifier=Modifier.weight(1f)){Text("下载地形")}
                OutlinedButton(onClick={},enabled=false,modifier=Modifier.weight(1f)){Text("卫星自动缓存")}
            }
            if(busy||complete) {
                LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth())
                Text("${(progress*100).toInt()}% · 区域 ${batchIndex.coerceAtMost(batchTotal)}/$batchTotal · $downloaded/$required 项 · ${bytes/1024/1024} MB（当前区域）",style=MaterialTheme.typography.labelSmall)
            }
            Text("卫星底图使用 Esri World Imagery。MapLibre 客户端缓存上限为 512 MB；这不是整条路线的离线下载，未浏览过的区域不能保证断网显示。",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
