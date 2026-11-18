package com.tubu.trailkit
import android.content.Context
import android.net.Uri
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.util.Locale
import kotlin.math.*
data class GpxTrackResult(val summary:String,val encodedPoints:String,val encodedWaypoints:String="[]")
fun readGpxTrack(context:Context,uri:Uri):GpxTrackResult {
 val points=mutableListOf<Pair<Double,Double>>();val elevations=mutableListOf<Double>()
 val waypoints=JSONArray()
 context.contentResolver.openInputStream(uri)?.use{input->
  val parser=Xml.newPullParser();parser.setInput(input,"UTF-8");var event=parser.eventType
  var waypoint:JSONObject?=null
  while(event!=XmlPullParser.END_DOCUMENT){
   if(event==XmlPullParser.START_TAG)when(parser.name.lowercase(Locale.ROOT)){
    "trkpt","rtept"->{val lat=parser.getAttributeValue(null,"lat")?.toDoubleOrNull();val lon=parser.getAttributeValue(null,"lon")?.toDoubleOrNull();if(lat!=null&&lon!=null&&lat in -90.0..90.0&&lon in -180.0..180.0)points.add(lat to lon)}
    "wpt"->{val lat=parser.getAttributeValue(null,"lat")?.toDoubleOrNull();val lon=parser.getAttributeValue(null,"lon")?.toDoubleOrNull();if(lat!=null&&lon!=null&&lat in -90.0..90.0&&lon in -180.0..180.0)waypoint=JSONObject().put("lat",lat).put("lon",lon)}
    "name"->{if(waypoint!=null){val name=parser.nextText().trim();if(name.isNotBlank())waypoint!!.put("name",name)}}
    "ele"->{val elevation=parser.nextText().toDoubleOrNull();if(elevation!=null)elevations.add(elevation)}
   }
   if(event==XmlPullParser.END_TAG && parser.name.equals("wpt",true)){waypoint?.let{waypoints.put(it)};waypoint=null}
   event=parser.next()
  }
 }?:error("无法读取 GPX 文件")
 require(points.size>=2){"GPX 中没有足够的轨迹点"}
 fun segment(a:Pair<Double,Double>,b:Pair<Double,Double>):Double{
  val r=6371000.0;val dLat=Math.toRadians(b.first-a.first);val dLon=Math.toRadians(b.second-a.second)
  val h=sin(dLat/2).pow(2)+cos(Math.toRadians(a.first))*cos(Math.toRadians(b.first))*sin(dLon/2).pow(2)
  return 2*r*asin(sqrt(h.coerceIn(0.0,1.0)))
 }
 val meters=points.zipWithNext().sumOf{segment(it.first,it.second)}
 val ascent=elevations.zipWithNext().sumOf{(a,b)->(b-a).coerceAtLeast(0.0)}
 val json=JSONArray();points.forEach{json.put(JSONArray().put(it.first).put(it.second))}
 val summary="GPX轨迹："+points.size+" 个轨迹点 · "+String.format(Locale.CHINA,"%.2f",meters/1000.0)+" km · 累计爬升约 "+ascent.roundToInt()+" m · "+waypoints.length()+" 个途经点"
 return GpxTrackResult(summary,json.toString(),waypoints.toString())
}
fun decodeTrackPoints(encoded:String):List<Pair<Double,Double>> = try {
 val a=JSONArray(encoded);List(a.length()){i->val p=a.getJSONArray(i);p.getDouble(0) to p.getDouble(1)}
}catch(_:Exception){emptyList()}
data class GpxWaypoint(val lat:Double,val lon:Double,val name:String)
fun decodeGpxWaypoints(encoded:String):List<GpxWaypoint> = try {
 val a=JSONArray(encoded);List(a.length()){i->val p=a.getJSONObject(i);GpxWaypoint(p.getDouble("lat"),p.getDouble("lon"),p.optString("name","途经点"))}
}catch(_:Exception){emptyList()}
