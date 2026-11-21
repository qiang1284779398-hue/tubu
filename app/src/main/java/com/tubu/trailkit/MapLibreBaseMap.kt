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
import org.maplibre.geojson.Feature
import org.maplibre.android.offline.OfflineGeometryRegionDefinition
import java.util.concurrent.TimeUnit
import java.util.UUID
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
    cameraRequestId: Long,
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
    var lastAppliedCameraRequestId by remember(mapView) { mutableLongStateOf(Long.MIN_VALUE) }
    val latestCameraRequestId by rememberUpdatedState(cameraRequestId)
    val latestMapBearing by rememberUpdatedState(mapBearing)
    var lastAppliedBearing by remember(mapView) { mutableFloatStateOf(Float.NaN) }

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
                        .bearing(latestMapBearing.toDouble())
                        .tilt(nativeMap.cameraPosition.tilt)
                        .build()
                    lastAppliedCameraRequestId = latestCameraRequestId
                    lastAppliedBearing = latestMapBearing
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
                // Never echo the camera snapshot from an in-progress native gesture
                // back into MapLibre. That feedback loop repeatedly resets pan/pinch
                // mid-gesture and is perceived as map jitter rather than low FPS.
                if (lastAppliedCameraRequestId != cameraRequestId) {
                    nativeMap.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(centerLat, centerLon))
                        .zoom(zoom.toDouble().coerceIn(2.0, 20.0))
                        .bearing(mapBearing.toDouble())
                        .tilt(p.tilt)
                        .build()
                    lastAppliedCameraRequestId = cameraRequestId
                    lastAppliedBearing = mapBearing
                } else if (lastAppliedBearing.isNaN() || abs(p.bearing - mapBearing.toDouble()) > 0.5) {
                    // Heading/track-up may change independently; preserve the native
                    // gesture's current target and zoom while applying bearing only.
                    nativeMap.cameraPosition = CameraPosition.Builder()
                        .target(p.target ?: LatLng(centerLat, centerLon))
                        .zoom(p.zoom)
                        .bearing(mapBearing.toDouble())
                        .tilt(p.tilt)
                        .build()
                    lastAppliedBearing = mapBearing
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

internal const val TRAILKIT_TERRAIN_STYLE = "https://raw.githubusercontent.com/qiang1284779398-hue/tubu/main/map-styles/trailkit-terrain.json"
internal const val TRAILKIT_ESRI_SATELLITE_STYLE = "https://raw.githubusercontent.com/qiang1284779398-hue/tubu/main/map-styles/trailkit-esri-satellite.json"

@Composable
internal fun RouteOfflineDownloadCard(points:List<Pair<Double,Double>>,projectId:String,selectedStyle:String,onSelectStyle:(String)->Unit) {
    val context=LocalContext.current
    val prefs=remember(context){context.getSharedPreferences("trailkit_offline_downloads",Context.MODE_PRIVATE)}
    val vectorTaskKey="task_${projectId}_vector"
    val terrainTaskKey="task_${projectId}_terrain"
    val vectorRouteKey="route_${projectId}_vector"
    val terrainRouteKey="route_${projectId}_terrain"

    var progress by remember(projectId){mutableFloatStateOf(0f)}
    var batchIndex by remember(projectId){mutableIntStateOf(0)}
    var batchTotal by remember(projectId){mutableIntStateOf(0)}
    var downloaded by remember(projectId){mutableLongStateOf(0L)}
    var required by remember(projectId){mutableLongStateOf(0L)}
    var bytes by remember(projectId){mutableLongStateOf(0L)}
    var estimatedTiles by remember(projectId){mutableIntStateOf(0)}
    var busy by remember(projectId){mutableStateOf(false)}
    var complete by remember(projectId){mutableStateOf(false)}
    var paused by remember(projectId){mutableStateOf(false)}
    var message by remember(projectId){mutableStateOf("按路线走廊分级下载：9–11 级覆盖路线附近，12–15 级提供细节；缩小范围以减少流量。已保存区域可恢复。")}
    var activeRegion by remember(projectId){mutableStateOf<OfflineRegion?>(null)}

    LaunchedEffect(projectId,points) {
        val hasVector=!prefs.getString(vectorTaskKey,null).isNullOrBlank()
        val hasTerrain=!prefs.getString(terrainTaskKey,null).isNullOrBlank()
        if(hasVector||hasTerrain) {
            message="检测到之前的离线地图任务。再次点击对应图层按钮，会按已保存的区域记录继续下载，不会从头重建。"
        }
    }

    DisposableEffect(projectId) {
        onDispose {
            try { activeRegion?.setDownloadState(OfflineRegion.STATE_INACTIVE) } catch (_:Exception) {}
        }
    }

    fun pauseDownload() {
        paused=true
        busy=false
        try { activeRegion?.setDownloadState(OfflineRegion.STATE_INACTIVE) } catch (_:Exception) {}
        message="已暂停。当前区域和已下载的瓦片均保留；再次点击同一图层即可继续。"
    }

    fun startDownload(styleUrl:String,label:String,isTerrain:Boolean) {
        if(points.size<2 || busy)return
        try {
            val plan=OfflineDownloadPlanner.createPlan(points)
            if(plan.regions.isEmpty()) {
                message="路线点不足或坐标无效，无法生成离线下载范围。"
                return
            }
            val taskKey=if(isTerrain)terrainTaskKey else vectorTaskKey
            val routeKey=if(isTerrain)terrainRouteKey else vectorRouteKey
            val routeHash=points.hashCode().toString()
            val storedId=prefs.getString(taskKey,null)
            val storedHash=prefs.getString(routeKey,null)
            val taskId=if(!storedId.isNullOrBlank() && storedHash==routeHash) storedId else UUID.randomUUID().toString()
            prefs.edit().putString(taskKey,taskId).putString(routeKey,routeHash).apply()

            val manager=OfflineManager.getInstance(context)
            // Do not lower MapLibre's built-in device-wide offline tile limit to 3,000.
            // MapLibre's documented default is 6,000; route-corridor planning reduces demand
            // instead of bypassing the SDK's device storage guard.
            busy=true;paused=false;complete=false;progress=0f;batchIndex=0
            batchTotal=plan.regions.size;downloaded=0L;required=0L;bytes=0L
            estimatedTiles=plan.estimatedTileCount
            activeRegion=null
            message="正在准备$label：路线总长约 ${"%.1f".format(java.util.Locale.US,plan.routeLengthMeters/1000.0)} km，分为 ${plan.regions.size} 个走廊区域；预估约 ${plan.estimatedTileCount} 张地图瓦片（9–15 级）。"
            var completedBytes=0L
            var completedResources=0L
            var completedRegions=0
            var stopped=false

            lateinit var processNext:(Int)->Unit
            lateinit var watchRegion:(OfflineRegion,Int,Int,Long,Long)->Unit

            fun stopForRecoverableError(text:String) {
                if(stopped)return
                stopped=true
                try { activeRegion?.setDownloadState(OfflineRegion.STATE_INACTIVE) } catch (_:Exception) {}
                activeRegion=null
                busy=false
                paused=true
                message=text+" 已有下载内容保留，点击同一图层按钮继续。"
            }

            watchRegion = watch@{ region,index,doneCount,doneBytes,doneResources ->
                var terminal=false
                activeRegion=region
                region.setObserver(object:OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(status:OfflineRegionStatus) {
                        if(terminal || stopped || paused)return
                        val local=status.requiredResourceCount.takeIf{it>0L}?.let{
                            (status.completedResourceCount.toDouble()/it.toDouble()).toFloat()
                        }?:0f
                        progress=((doneCount+local)/plan.regions.size.toFloat()).coerceIn(0f,1f)
                        downloaded=doneResources+status.completedResourceCount
                        required=status.requiredResourceCount
                        bytes=doneBytes+status.completedResourceSize
                        message="$label 下载中：区域 ${index+1}/${plan.regions.size}（${plannedLabel(plan.regions[index])}），${status.completedResourceCount}/${status.requiredResourceCount} 项资源，累计 ${bytes/1024/1024} MB。"
                        if(status.isComplete && status.requiredResourceCount>0L) {
                            terminal=true
                            region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            activeRegion=null
                            completedRegions=doneCount+1
                            completedBytes=doneBytes+status.completedResourceSize
                            completedResources=doneResources+status.completedResourceCount
                            processNext(index+1)
                        }
                    }
                    override fun onError(error:OfflineRegionError) {
                        if(terminal || stopped || paused)return
                        // MapLibre retries recoverable resource errors with backoff and after connectivity returns.
                        message="网络或地图源暂时不稳定，MapLibre 正在重试当前区域；不要删除任务。"
                    }
                    override fun mapboxTileCountLimitExceeded(limit:Long) {
                        if(terminal || stopped || paused)return
                        terminal=true
                        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        activeRegion=null
                        stopForRecoverableError("本机离线瓦片总量达到 SDK 上限 $limit。请删除不再需要的旧离线区域或释放存储空间后继续。")
                    }
                })
                region.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }

            processNext = next@{ index ->
                if(stopped || paused)return@next
                if(index>=plan.regions.size) {
                    busy=false;complete=true;paused=false;progress=1f
                    activeRegion=null
                    downloaded=completedResources
                    bytes=completedBytes
                    message="$label 下载完成：${plan.regions.size} 个路线走廊区域，约 ${plan.estimatedTileCount} 张瓦片；实际资源 ${completedResources} 项、${completedBytes/1024/1024} MB。"
                } else {
                    batchIndex=index+1
                    val planned=plan.regions[index]
                    manager.listOfflineRegions(object:OfflineManager.ListOfflineRegionsCallback {
                        override fun onList(regions:Array<OfflineRegion>?) {
                            if(stopped || paused)return
                            val existing=regions.orEmpty().firstOrNull { region ->
                                try {
                                    val meta=JSONObject(String(region.metadata,Charsets.UTF_8))
                                    meta.optString("app")=="TrailKit" &&
                                        meta.optString("taskId")==taskId &&
                                        meta.optInt("batch",-1)==index
                                } catch (_:Exception) { false }
                            }
                            if(existing!=null) {
                                existing.getStatus(object:OfflineRegion.OfflineRegionStatusCallback {
                                    override fun onStatus(status:OfflineRegionStatus?) {
                                        if(stopped || paused)return
                                        if(status==null) {
                                            stopForRecoverableError("无法读取第 ${index+1} 个区域状态。")
                                            return
                                        }
                                        if(status.isComplete && status.requiredResourceCount>0L) {
                                            completedRegions++
                                            completedBytes+=status.completedResourceSize
                                            completedResources+=status.completedResourceCount
                                            bytes=completedBytes
                                            downloaded=completedResources
                                            progress=(completedRegions.toFloat()/plan.regions.size).coerceIn(0f,1f)
                                            existing.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                            processNext(index+1)
                                        } else {
                                            watchRegion(existing,index,completedRegions,completedBytes,completedResources)
                                        }
                                    }
                                    override fun onError(error:String?) {
                                        stopForRecoverableError("读取第 ${index+1} 个区域失败："+(error?: "未知错误"))
                                    }
                                })
                            } else {
                                val geometry=try {
                                    val featureJson=JSONObject().put("type","Feature")
                                        .put("geometry",JSONObject(planned.geoJson))
                                        .put("properties",JSONObject()).toString()
                                    Feature.fromJson(featureJson).geometry()
                                        ?: throw IllegalStateException("GeoJSON 没有 geometry")
                                } catch(e:Exception) {
                                    stopForRecoverableError("路线区域几何生成失败："+(e.message?:"未知错误"))
                                    return
                                }
                                val definition=OfflineGeometryRegionDefinition(
                                    styleUrl,geometry,planned.minZoom.toDouble(),planned.maxZoom.toDouble(),1.0f,true
                                )
                                val metadata=JSONObject()
                                    .put("app","TrailKit")
                                    .put("name","TrailKit-$label-$projectId-${index+1}")
                                    .put("taskId",taskId)
                                    .put("projectId",projectId)
                                    .put("layer",label)
                                    .put("batch",index)
                                    .put("batchTotal",plan.regions.size)
                                    .put("minZoom",planned.minZoom)
                                    .put("maxZoom",planned.maxZoom)
                                    .put("bufferMeters",planned.bufferMeters)
                                    .put("estimatedTiles",planned.estimatedTileCount)
                                    .toString().toByteArray(Charsets.UTF_8)
                                manager.createOfflineRegion(definition,metadata,object:OfflineManager.CreateOfflineRegionCallback {
                                    override fun onCreate(offlineRegion:OfflineRegion) {
                                        if(stopped || paused) {
                                            offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                            return
                                        }
                                        watchRegion(offlineRegion,index,completedRegions,completedBytes,completedResources)
                                    }
                                    override fun onError(error:String) {
                                        stopForRecoverableError("创建第 ${index+1} 个离线区域失败：$error")
                                    }
                                })
                            }
                        }
                        override fun onError(error:String) {
                            stopForRecoverableError("读取已下载区域失败：$error")
                        }
                    })
                }
            }
            processNext(0)
        } catch(e:Exception) {
            busy=false
            message="启动离线下载失败："+(e.message ?: "未知错误")
        }
    }

    val hasVectorTask=!prefs.getString(vectorTaskKey,null).isNullOrBlank()
    val hasTerrainTask=!prefs.getString(terrainTaskKey,null).isNullOrBlank()
    Card(modifier=Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
            Text("离线地图下载",style=MaterialTheme.typography.titleSmall)
            Text(message,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("下载范围：路线两侧约 900 m 的低级别地图（9–11 级）+ 两侧约 220 m 的高细节地图（12–15 级）。相比旧方案减少低级别覆盖宽度和高一级缩放层，优先保留徒步路线附近。",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick={startDownload(TRAILKIT_OFFLINE_VECTOR_STYLE,"标准矢量图",false)},
                    enabled=!busy&&points.size>=2,
                    modifier=Modifier.weight(1f)
                ){Text(if(hasVectorTask)"继续矢量" else "下载矢量")}
                OutlinedButton(
                    onClick={startDownload(TRAILKIT_TERRAIN_STYLE,"地形图",true)},
                    enabled=!busy&&points.size>=2,
                    modifier=Modifier.weight(1f)
                ){Text(if(hasTerrainTask)"继续地形" else "下载地形")}
                OutlinedButton(onClick={pauseDownload()},enabled=busy,modifier=Modifier.weight(1f)){Text("暂停")}
            }
            if(busy||complete||paused) {
                LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth())
                Text(
                    "${(progress*100).toInt()}% · 区域 ${batchIndex.coerceAtMost(batchTotal)}/$batchTotal · 已完成 $downloaded 项 · 当前区域需 $required 项 · 累计 ${bytes/1024/1024} MB",
                    style=MaterialTheme.typography.labelSmall
                )
            }
            Text("预估瓦片数仅作下载前规划参考，实际资源量取决于地图样式、文字字体和服务端瓦片；MapLibre 不提供可靠的下载前 MB 估算。暂停、网络中断或退出页面后，已下载区域会保留，可继续同一任务。",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("卫星底图仍使用 Esri World Imagery 的浏览缓存（上限 512 MB），当前不提供批量离线卫星下载。",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun plannedLabel(plan:OfflineDownloadRegionPlan):String =
    "${plan.minZoom}–${plan.maxZoom} 级 · ${plan.bufferMeters.toInt()} m 缓冲"
