package com.tubu.trailkit

import kotlin.math.*

data class RouteMatch(
    val segmentIndex:Int,
    val fraction:Double,
    val distanceMeters:Double,
    val progressMeters:Double,
    val totalMeters:Double,
    val snappedLat:Double,
    val snappedLon:Double,
    val bearingDegrees:Double
) {
    val remainingMeters:Double get()=(totalMeters-progressMeters).coerceAtLeast(0.0)
}

private fun haversineMeters(a:Pair<Double,Double>,b:Pair<Double,Double>):Double {
    val r=6371000.0
    val p1=Math.toRadians(a.first);val p2=Math.toRadians(b.first)
    val dp=Math.toRadians(b.first-a.first);val dl=Math.toRadians(b.second-a.second)
    val h=sin(dp/2).pow(2)+cos(p1)*cos(p2)*sin(dl/2).pow(2)
    return 2*r*asin(sqrt(h.coerceIn(0.0,1.0)))
}

private fun bearingDegrees(a:Pair<Double,Double>,b:Pair<Double,Double>):Double {
    val p1=Math.toRadians(a.first);val p2=Math.toRadians(b.first)
    val dl=Math.toRadians(b.second-a.second)
    return (Math.toDegrees(atan2(sin(dl)*cos(p2),cos(p1)*sin(p2)-sin(p1)*cos(p2)*cos(dl)))+360.0)%360.0
}

/** Projects a GPS fix onto every GPX segment in a local metric plane. */
fun matchRoute(points:List<Pair<Double,Double>>,location:Pair<Double,Double>,lastSegmentIndex:Int?=null):RouteMatch? {
    if(points.size<2)return null
    val prefix=DoubleArray(points.size)
    for(i in 1 until points.size)prefix[i]=prefix[i-1]+haversineMeters(points[i-1],points[i])
    val total=prefix.last()
    val lat0=Math.toRadians(location.first)
    val cosLat=cos(lat0).coerceAtLeast(0.01)
    var bestDistance=Double.POSITIVE_INFINITY
    var bestIndex=0
    var bestFraction=0.0
    var bestLat=points.first().first
    var bestLon=points.first().second
    var bestBearing=0.0
    fun scan(range:IntRange) {
        for(i in range) {
            val a=points[i];val b=points[i+1]
            val ax=Math.toRadians(a.second-location.second)*6371000.0*cosLat
            val ay=Math.toRadians(a.first-location.first)*6371000.0
            val bx=Math.toRadians(b.second-location.second)*6371000.0*cosLat
            val by=Math.toRadians(b.first-location.first)*6371000.0
            val dx=bx-ax;val dy=by-ay
            val denom=dx*dx+dy*dy
            val t=if(denom<0.0001)0.0 else (-(ax*dx+ay*dy)/denom).coerceIn(0.0,1.0)
            val px=ax+t*dx;val py=ay+t*dy
            val distance=hypot(px,py)
            if(distance<bestDistance) {
                bestDistance=distance;bestIndex=i;bestFraction=t
                bestLat=a.first+(b.first-a.first)*t
                bestLon=a.second+(b.second-a.second)*t
                bestBearing=bearingDegrees(a,b)
            }
        }
    }
    if(lastSegmentIndex==null) scan(0 until points.lastIndex)
    else {
        val from=(lastSegmentIndex-5).coerceAtLeast(0)
        val to=(lastSegmentIndex+200).coerceAtMost(points.lastIndex-1)
        scan(from..to)
        if(bestDistance>500.0) {
            bestDistance=Double.POSITIVE_INFINITY
            bestIndex=0;bestFraction=0.0
            scan(0 until points.lastIndex)
        }
    }
    val segmentLength=prefix[bestIndex+1]-prefix[bestIndex]
    return RouteMatch(bestIndex,bestFraction,bestDistance,prefix[bestIndex]+segmentLength*bestFraction,total,bestLat,bestLon,bestBearing)
}

fun formatNavDistance(meters:Double):String = if(meters>=1000) String.format(java.util.Locale.CHINA,"%.2f km",meters/1000.0) else "${meters.roundToInt()} m"
fun directionLabel(degrees:Float):String {
    val names=listOf("北","东北","东","东南","南","西南","西","西北")
    return names[(kotlin.math.floor(((degrees%360f+360f)%360f+22.5f)/45f).toInt())%8]
}
