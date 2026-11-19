package com.tubu.trailkit

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.util.Locale
import kotlin.math.*

fun analyzeGpx(context: Context, uri: Uri): String {
    val points = mutableListOf<Pair<Double, Double>>()
    val elevations = mutableListOf<Double>()
    context.contentResolver.openInputStream(uri)?.use { input ->
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name.lowercase(Locale.ROOT)) {
                    "trkpt", "rtept" -> {
                        val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        if (lat != null && lon != null) points.add(lat to lon)
                    }
                    "ele" -> parser.nextText().toDoubleOrNull()?.let { elevations.add(it) }
                }
            }
            event = parser.next()
        }
    } ?: error("无法读取 GPX 文件")
    require(points.size >= 2) { "GPX 中没有足够的轨迹点" }
    fun segment(a: Pair<Double,Double>, b: Pair<Double,Double>): Double {
        val radius=6371000.0
        val dLat=Math.toRadians(b.first-a.first)
        val dLon=Math.toRadians(b.second-a.second)
        val h=sin(dLat/2).pow(2)+cos(Math.toRadians(a.first))*cos(Math.toRadians(b.first))*sin(dLon/2).pow(2)
        return 2*radius*asin(sqrt(h.coerceIn(0.0,1.0)))
    }
    val meters=points.zipWithNext().sumOf { segment(it.first,it.second) }
    val ascent=elevations.zipWithNext().sumOf { (a,b)->(b-a).coerceAtLeast(0.0) }
    return "GPX轨迹：" + points.size + " 个轨迹点 · " +
        String.format(Locale.CHINA,"%.2f",meters/1000.0) + " km · 累计爬升约 " +
        ascent.roundToInt() + " m"
}
