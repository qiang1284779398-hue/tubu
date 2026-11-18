package com.tubu.trailkit

import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import kotlin.math.*

private data class MbInfo(val name:String,val format:String,val minZoom:Int,val maxZoom:Int,val centerLon:Double,val centerLat:Double,val centerZoom:Float)
private fun readMbInfo(db:SQLiteDatabase):MbInfo {
    val values=mutableMapOf<String,String>()
    db.rawQuery("SELECT name,value FROM metadata",null).use { c -> while(c.moveToNext()) values[c.getString(0)]=c.getString(1) }
    val center=values["center"]?.split(",")?.mapNotNull{it.toDoubleOrNull()}
    return MbInfo(values["name"]?:"离线地图",values["format"]?:"jpg",values["minzoom"]?.toIntOrNull()?:0,values["maxzoom"]?.toIntOrNull()?:16,center?.getOrNull(0)?:0.0,center?.getOrNull(1)?:0.0,center?.getOrNull(2)?.toFloat()?:10f)
}
private fun worldX(lon:Double,z:Int):Double=(lon+180.0)/360.0*(256.0*(1 shl z))
private fun worldY(lat:Double,z:Int):Double {
    val r=lat.coerceIn(-85.0511,85.0511)*Math.PI/180.0
    return (1.0-ln(tan(r)+1.0/cos(r))/Math.PI)/2.0*(256.0*(1 shl z))
}
@Composable fun OfflineMapDialog(path:String,encoded:String,onClose:()->Unit) {
    val db=remember(path) { try { SQLiteDatabase.openDatabase(path,null,SQLiteDatabase.OPEN_READONLY) } catch(_:Exception){null} }
    DisposableEffect(db) { onDispose { try{db?.close()}catch(_:Exception){} } }
    val info=remember(db) { try{db?.let(::readMbInfo)}catch(_:Exception){null} }
    val points=remember(encoded){decodeTrackPoints(encoded)}
    var zoom by remember(path){mutableFloatStateOf((info?.centerZoom?:10f).coerceIn((info?.minZoom?:0).toFloat(),(info?.maxZoom?:16).toFloat()))}
    var centerLon by remember(path){mutableDoubleStateOf(if(info?.centerLon==0.0 && points.isNotEmpty()) points.map{it.second}.average() else info?.centerLon?:points.firstOrNull()?.second?:0.0)}
    var centerLat by remember(path){mutableDoubleStateOf(if(info?.centerLat==0.0 && points.isNotEmpty()) points.map{it.first}.average() else info?.centerLat?:points.firstOrNull()?.first?:0.0)}
    AlertDialog(onDismissRequest=onClose,title={Text(info?.name?: "离线地形地图")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(db==null || info==null) {
                Text("无法读取 MBTiles 地图文件。请导入标准栅格 MBTiles（PNG/JPG/WebP），矢量 PBF 暂不支持。")
            } else {
                Text("离线地图 · ${info.format.uppercase()} · 缩放级别 ${info.minZoom}–${info.maxZoom}",style=MaterialTheme.typography.labelSmall)
                Canvas(Modifier.fillMaxWidth().height(390.dp).pointerInput(Unit) {
                    detectTransformGestures { _,pan,zoomChange,_ ->
                        val next=(zoom+ln(zoomChange)/ln(2.0).toFloat()).coerceIn(info.minZoom.toFloat(),info.maxZoom.toFloat())
                        val scale=2.0.pow((next-zoom).toDouble())
                        val z=zoom.roundToInt().coerceIn(info.minZoom,info.maxZoom)
                        val wx=worldX(centerLon,z)-pan.x/scale
                        val wy=worldY(centerLat,z)-pan.y/scale
                        val world=256.0*(1 shl z)
                        centerLon=(wx/world*360.0-180.0).coerceIn(-180.0,180.0)
                        val n=Math.PI-2.0*Math.PI*wy/world
                        centerLat=(atan(sinh(n))*180.0/Math.PI).coerceIn(-85.0,85.0)
                        zoom=next
                    }
                }) {
                    val z=zoom.roundToInt().coerceIn(info.minZoom,info.maxZoom)
                    val scale=2.0.pow((zoom-z).toDouble()).toFloat()
                    val world=256.0*(1 shl z)
                    val cx=worldX(centerLon,z);val cy=worldY(centerLat,z)
                    val left=cx-size.width/2.0;val top=cy-size.height/2.0
                    val minX=floor(left/256.0).toInt();val maxX=floor((left+size.width)/256.0).toInt()
                    val minY=floor(top/256.0).toInt();val maxY=floor((top+size.height)/256.0).toInt()
                    val canvas=drawContext.canvas.nativeCanvas
                    val paint=Paint(Paint.FILTER_BITMAP_FLAG)
                    for(tx in minX..maxX) for(ty in minY..maxY) {
                        if(ty !in 0 until (1 shl z)) continue
                        val wrapped=((tx%(1 shl z))+(1 shl z))%(1 shl z)
                        try {
                            db.rawQuery("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",arrayOf(z.toString(),wrapped.toString(),((1 shl z)-1-ty).toString())).use { c ->
                                if(c.moveToFirst()) {
                                    val bytes=c.getBlob(0)
                                    val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
                                    if(bitmap!=null) {
                                        val x=(tx*256.0-left).toFloat()
                                        val y=(ty*256.0-top).toFloat()
                                        canvas.drawBitmap(bitmap,null,RectF(x,y,x+256f,y+256f),paint)
                                        bitmap.recycle()
                                    }
                                }
                            }
                        } catch(_:Exception) { }
                    }
                    if(points.size>1) {
                        val route=AndroidPath()
                        points.forEachIndexed { i,p ->
                            val x=(worldX(p.second,z)-cx+size.width/2.0).toFloat()
                            val y=(worldY(p.first,z)-cy+size.height/2.0).toFloat()
                            if(i==0)route.moveTo(x,y) else route.lineTo(x,y)
                        }
                        val routePaint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=android.graphics.Color.rgb(25,110,75);style=Paint.Style.STROKE;strokeWidth=5f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND }
                        canvas.drawPath(route,routePaint)
                    }
                }
                Text("双指缩放、拖动浏览；绿色线为导入的 GPX 轨迹。地图数据完全离线读取。",style=MaterialTheme.typography.bodySmall)
                Text("地图覆盖范围与等高线细节取决于导入的 MBTiles 数据；请使用有合法离线使用许可的数据。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary)
            }
        }
    },confirmButton={TextButton(onClick=onClose){Text("关闭")}})
}
