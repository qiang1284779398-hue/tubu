package com.tubu.trailkit
import android.content.Context
import android.net.Uri
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.util.Locale
import kotlin.math.*
data class GpxTrackResult(val summary:String,val encodedPoints:String,val encodedWaypoints:String="[]",val encodedElevations:String="[]")
fun readGpxTrack(context:Context,uri:Uri):GpxTrackResult {
 val points=mutableListOf<Pair<Double,Double>>();val elevations=mutableListOf<Double?>()
 val waypoints=JSONArray()
 context.contentResolver.openInputStream(uri)?.use{input->
  val parser=Xml.newPullParser();parser.setInput(input,"UTF-8");var event=parser.eventType
  var waypoint:JSONObject?=null
  var activeTrackPointIndex:Int?=null
  while(event!=XmlPullParser.END_DOCUMENT){
   if(event==XmlPullParser.START_TAG)when(parser.name.lowercase(Locale.ROOT)){
    "trkpt","rtept"->{val lat=parser.getAttributeValue(null,"lat")?.toDoubleOrNull();val lon=parser.getAttributeValue(null,"lon")?.toDoubleOrNull();if(lat!=null&&lon!=null&&lat in -90.0..90.0&&lon in -180.0..180.0){points.add(lat to lon);elevations.add(null);activeTrackPointIndex=points.lastIndex}}
    "wpt"->{val lat=parser.getAttributeValue(null,"lat")?.toDoubleOrNull();val lon=parser.getAttributeValue(null,"lon")?.toDoubleOrNull();if(lat!=null&&lon!=null&&lat in -90.0..90.0&&lon in -180.0..180.0)waypoint=JSONObject().put("lat",lat).put("lon",lon)}
    "name"->{if(waypoint!=null){val name=parser.nextText().trim();if(name.isNotBlank())waypoint!!.put("name",name)}}
    "desc","cmt","sym","type","time"->{
     if(waypoint!=null){
      val field=parser.name.lowercase(Locale.ROOT)
      val value=parser.nextText().trim()
      if(value.isNotBlank())waypoint!!.put(field,value)
     }
    }
    "ele"->{
     val elevation=parser.nextText().trim().toDoubleOrNull()
     if(waypoint!=null){if(elevation!=null)waypoint!!.put("ele",elevation)}
     else {val index=activeTrackPointIndex;if(elevation!=null&&index!=null)elevations[index]=elevation}
    }
   }
   if(event==XmlPullParser.END_TAG && (parser.name.equals("trkpt",true)||parser.name.equals("rtept",true)))activeTrackPointIndex=null
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
 val ascent=elevations.zipWithNext().sumOf{(a,b)->if(a!=null&&b!=null)(b-a).coerceAtLeast(0.0)else 0.0}
 val elevationJson=JSONArray();elevations.forEach{elevationJson.put(it?:JSONObject.NULL)}
 val json=JSONArray();points.forEach{json.put(JSONArray().put(it.first).put(it.second))}
 val summary="GPX轨迹："+points.size+" 个轨迹点 · "+String.format(Locale.CHINA,"%.2f",meters/1000.0)+" km · 累计爬升约 "+ascent.roundToInt()+" m · "+waypoints.length()+" 个途经点"
 return GpxTrackResult(summary,json.toString(),waypoints.toString(),elevationJson.toString())
}
// Decode only recorded GPX track points (trkpt), not waypoints or route points.
 // Each flushed trkpt can be recovered even if the GPX file was interrupted.
fun decodeRecordedTrackPoints(xml:String):List<Pair<Double,Double>> {
 val pointTag=Regex("""<trkpt\b([^>]*)>""",RegexOption.IGNORE_CASE)
 fun attribute(attributes:String,name:String):Double? =
  Regex("""(?:^|\s)$name\s*=\s*["']([^"']+)["']""",RegexOption.IGNORE_CASE)
   .find(attributes)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
 return pointTag.findAll(xml).mapNotNull { match ->
  val attributes=match.groupValues[1]
  val lat=attribute(attributes,"lat")
  val lon=attribute(attributes,"lon")
  if(lat!=null && lon!=null && lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
 }.toList()
}

private fun decodePolylineDeltas(encoded:String):List<Int> {
 val values=mutableListOf<Int>()
 var index=0
 while(index<encoded.length) {
  var result=0;var shift=0;var b:Int
  do {
   if(index>=encoded.length || shift>30) return values
   b=encoded[index++].code-63
   if(b<0 || b>63) return values
   result=result or ((b and 0x1f) shl shift)
   shift+=5
  } while(b>=0x20)
  values.add(if((result and 1)!=0) (result shr 1).inv() else result shr 1)
 }
 return values
}
fun decodeTrackPoints(encoded:String):List<Pair<Double,Double>> = try {
 if(encoded.startsWith("P1") || encoded.startsWith("P5")) {
  val scale=if(encoded.startsWith("P5")) 100_000.0 else 1_000_000.0
  val deltas=decodePolylineDeltas(encoded.substring(2))
  val result=ArrayList<Pair<Double,Double>>(deltas.size/2)
  var lat=0;var lon=0
  var i=0
  while(i+1<deltas.size) {
   lat+=deltas[i++];lon+=deltas[i++]
   result.add(lat/scale to lon/scale)
  }
  result
 } else {
  val a=JSONArray(encoded);List(a.length()){i->val p=a.getJSONArray(i);p.getDouble(0) to p.getDouble(1)}
 }
}catch(_:Exception){emptyList()}
data class GpxWaypoint(
 val lat:Double,
 val lon:Double,
 val name:String,
 val description:String="",
 val comment:String="",
 val type:String="",
 val symbol:String="",
 val elevation:Double?=null,
 val time:String=""
)
fun decodeGpxWaypoints(encoded:String):List<GpxWaypoint> = try {
 val source=if(encoded.startsWith("W1")) encoded.substring(2) else encoded
 val a=JSONArray(source);List(a.length()){i->
  val p=a.getJSONObject(i)
  GpxWaypoint(
   lat=p.getDouble("lat"),lon=p.getDouble("lon"),name=p.optString("name","途经点"),
   description=p.optString("desc",""),comment=p.optString("cmt",""),
   type=p.optString("type",""),symbol=p.optString("sym",""),
   elevation=if(p.has("ele")&&!p.isNull("ele"))p.optDouble("ele") else null,
   time=p.optString("time","")
  )
 }
}catch(_:Exception){emptyList()}

private fun escapeGpxXml(value:String):String =
 value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;")

// Merge independent GPX waypoints into an existing track/route document.
fun appendGpxWaypoints(gpxXml:String,waypoints:List<GpxWaypoint>):String {
 if(waypoints.isEmpty()) return gpxXml
 val closingIndex=gpxXml.lowercase(Locale.ROOT).lastIndexOf("</gpx>")
 if(closingIndex<0) return gpxXml
 val waypointXml=buildString {
  waypoints.forEach { wp ->
   append("<wpt lat=\"").append(wp.lat).append("\" lon=\"").append(wp.lon).append("\">")
   wp.elevation?.let{append("<ele>").append(it).append("</ele>")}
   if(wp.time.isNotBlank()) append("<time>").append(escapeGpxXml(wp.time)).append("</time>")
   if(wp.name.isNotBlank()) append("<name>").append(escapeGpxXml(wp.name)).append("</name>")
   if(wp.comment.isNotBlank()) append("<cmt>").append(escapeGpxXml(wp.comment)).append("</cmt>")
   if(wp.description.isNotBlank()) append("<desc>").append(escapeGpxXml(wp.description)).append("</desc>")
   if(wp.symbol.isNotBlank()) append("<sym>").append(escapeGpxXml(wp.symbol)).append("</sym>")
   if(wp.type.isNotBlank()) append("<type>").append(escapeGpxXml(wp.type)).append("</type>")
   append("</wpt>\n")
  }
 }
 return gpxXml.substring(0,closingIndex)+waypointXml+gpxXml.substring(closingIndex)
}

fun decodeGpxElevations(encoded:String):List<Double?> = try {
 if(encoded.startsWith("E1")) {
  val deltas=decodePolylineDeltas(encoded.substring(2))
  val result=ArrayList<Double?>(deltas.size)
  var elevation=0
  deltas.forEach { elevation+=it;result.add(elevation.toDouble()) }
  result
 } else {
  val a=JSONArray(encoded);List(a.length()){i->if(a.isNull(i))null else a.getDouble(i)}
 }
}catch(_:Exception){emptyList()}
