package com.tubu.trailkit
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
@Composable fun TrailTrackDialog(encoded:String,onClose:()->Unit){
 val points=decodeTrackPoints(encoded)
 AlertDialog(onDismissRequest=onClose,title={Text("离线 GPX 轨迹")},text={
  Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
   if(points.size<2) Text("没有可显示的轨迹点，请重新导入 GPX。")
   else {
    Text("已保存 "+points.size+" 个坐标点。无需网络即可查看轨迹线。",style=MaterialTheme.typography.bodySmall)
    Canvas(Modifier.fillMaxWidth().height(260.dp)){
     val minLat=points.minOf{it.first};val maxLat=points.maxOf{it.first};val minLon=points.minOf{it.second};val maxLon=points.maxOf{it.second}
     val latSpan=(maxLat-minLat).coerceAtLeast(0.000001);val lonSpan=(maxLon-minLon).coerceAtLeast(0.000001)
     val scale=minOf((size.width-40f)/lonSpan.toFloat(),(size.height-40f)/latSpan.toFloat())
     val w=lonSpan.toFloat()*scale;val h=latSpan.toFloat()*scale;val left=(size.width-w)/2;val top=(size.height-h)/2
     val path=Path()
     points.forEachIndexed{i,p->
      val x=left+((p.second-minLon).toFloat()*scale);val y=top+(h-(p.first-minLat).toFloat()*scale)
      if(i==0)path.moveTo(x,y) else path.lineTo(x,y)
     }
     drawPath(path,Color(0xFF1D6048),style=Stroke(width=5f,cap=StrokeCap.Round))
     val first=points.first();val last=points.last()
     fun pos(p:Pair<Double,Double>)=Offset(left+(p.second-minLon).toFloat()*scale,top+(h-(p.first-minLat).toFloat())*scale)
     drawCircle(Color(0xFF25864D),radius=8f,center=pos(first));drawCircle(Color(0xFFB44C3D),radius=8f,center=pos(last))
    }
    Text("起点：绿色圆点   终点：红色圆点",style=MaterialTheme.typography.bodySmall)
    Text("注意：这是离线轨迹示意，不含地形底图、道路、海拔阴影或导航指引。",style=MaterialTheme.typography.bodySmall,color=Color(0xFF9A5A32))
   }
  }
 },confirmButton={TextButton(onClick=onClose){Text("关闭")}})
}
