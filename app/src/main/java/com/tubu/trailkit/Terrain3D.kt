package com.tubu.trailkit

import android.graphics.Color
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

@Composable
internal fun Terrain3DDialog(projectName: String, encodedTrack: String, visible: Boolean, onClose: () -> Unit) {
    if (!visible) return
    val context = LocalContext.current
    val points = remember(encodedTrack) { decodeTrackPoints(encodedTrack) }
    val routeFeature = remember(points) {
        if (points.size > 1) Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.second, it.first) }))
        else null
    }
    BackHandler(onBack = onClose)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("NATIVE TERRAIN VIEW", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text("$projectName · 三维地形预览", style = MaterialTheme.typography.titleMedium)
                        }
                        TextButton(onClick = onClose) { Text("关闭") }
                    }
                    Text("原生地图渲染 · 卫星影像 · 俯仰视角 · GPX轨迹",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = {
                    MapLibre.getInstance(context.applicationContext)
                    MapView(context).apply {
                        onCreate(null)
                        getMapAsync { map ->
                            map.uiSettings.isCompassEnabled = true
                            map.uiSettings.isRotateGesturesEnabled = true
                            map.uiSettings.isTiltGesturesEnabled = true
                            map.uiSettings.isZoomGesturesEnabled = true
                            map.setStyle(Style.Builder().fromJson(
                                """{"version":8,"sources":{"sat":{"type":"raster","tiles":["https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"],"tileSize":256,"maxzoom":19,"attribution":"Esri World Imagery"}},"layers":[{"id":"satellite","type":"raster","source":"sat"}]}"""
                            )) { style ->
                                map.cameraPosition = CameraPosition.Builder()
                                    .target(LatLng(28.45, 98.88))
                                    .zoom(11.2)
                                    .tilt(58.0)
                                    .bearing(-18.0)
                                    .build()
                                if (routeFeature != null) {
                                    style.addSource(GeoJsonSource("trail-route-native", routeFeature))
                                    style.addLayer(LineLayer("trail-route-native-line", "trail-route-native")
                                        .withProperties(PropertyFactory.lineColor("#FFB547"), PropertyFactory.lineWidth(4.5f), PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
                                    )
                                    val bounds=points.fold(org.maplibre.android.geometry.LatLngBounds.Builder().include(LatLng(points.first().first,points.first().second)).include(LatLng(points.first().first,points.first().second))){ b,p-> b.include(LatLng(p.first,p.second)) }.build()
                                    if(points.size>1)map.easeCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(bounds,48),800)
                                }
                            }
                        }
                    }
                },
                update = {}
            )
        }
    }
}
