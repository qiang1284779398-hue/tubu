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

private fun sphericalFallbackDistanceMeters(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
    val radius = 6371008.8
    val p1 = Math.toRadians(a.first)
    val p2 = Math.toRadians(b.first)
    val dp = Math.toRadians(b.first - a.first)
    val dl = Math.toRadians(b.second - a.second)
    val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
    return 2.0 * radius * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

/**
 * Inverse geodesic on the WGS84 ellipsoid (Vincenty).
 * The spherical fallback covers the rare near-antipodal non-convergence case.
 */
fun geodesicDistanceMeters(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
    if (a == b) return 0.0
    val semiMajor = 6378137.0
    val flattening = 1.0 / 298.257223563
    val semiMinor = (1.0 - flattening) * semiMajor
    val phi1 = Math.toRadians(a.first)
    val phi2 = Math.toRadians(b.first)
    val longitudeDifference = Math.toRadians(b.second - a.second)
    val reduced1 = atan((1.0 - flattening) * tan(phi1))
    val reduced2 = atan((1.0 - flattening) * tan(phi2))
    val sinU1 = sin(reduced1)
    val cosU1 = cos(reduced1)
    val sinU2 = sin(reduced2)
    val cosU2 = cos(reduced2)
    var lambda = ((longitudeDifference + Math.PI) % (2.0 * Math.PI) + 2.0 * Math.PI) % (2.0 * Math.PI) - Math.PI
    var sinSigma = 0.0
    var cosSigma = 0.0
    var sigma = 0.0
    var sinAlpha = 0.0
    var cosSquaredAlpha = 0.0
    var cosTwoSigmaM = 0.0
    var converged = false

    for (iteration in 0 until 100) {
        val sinLambda = sin(lambda)
        val cosLambda = cos(lambda)
        val term1 = cosU2 * sinLambda
        val term2 = cosU1 * sinU2 - sinU1 * cosU2 * cosLambda
        sinSigma = sqrt(term1 * term1 + term2 * term2)
        if (sinSigma < 1e-15) return 0.0
        cosSigma = sinU1 * sinU2 + cosU1 * cosU2 * cosLambda
        sigma = atan2(sinSigma, cosSigma)
        sinAlpha = cosU1 * cosU2 * sinLambda / sinSigma
        cosSquaredAlpha = (1.0 - sinAlpha * sinAlpha).coerceAtLeast(0.0)
        cosTwoSigmaM = if (cosSquaredAlpha < 1e-15) 0.0
            else cosSigma - 2.0 * sinU1 * sinU2 / cosSquaredAlpha
        val coefficient = flattening / 16.0 * cosSquaredAlpha *
            (4.0 + flattening * (4.0 - 3.0 * cosSquaredAlpha))
        val previousLambda = lambda
        lambda = longitudeDifference + (1.0 - coefficient) * flattening * sinAlpha *
            (sigma + coefficient * sinSigma * (cosTwoSigmaM +
                coefficient * cosSigma * (-1.0 + 2.0 * cosTwoSigmaM * cosTwoSigmaM)))
        if (abs(lambda - previousLambda) < 1e-12) {
            converged = true
            break
        }
    }
    if (!converged) return sphericalFallbackDistanceMeters(a, b)

    val uSquared = cosSquaredAlpha * (semiMajor * semiMajor - semiMinor * semiMinor) /
        (semiMinor * semiMinor)
    val coefficientA = 1.0 + uSquared / 16384.0 *
        (4096.0 + uSquared * (-768.0 + uSquared * (320.0 - 175.0 * uSquared)))
    val coefficientB = uSquared / 1024.0 *
        (256.0 + uSquared * (-128.0 + uSquared * (74.0 - 47.0 * uSquared)))
    val deltaSigma = coefficientB * sinSigma *
        (cosTwoSigmaM + coefficientB / 4.0 *
            (cosSigma * (-1.0 + 2.0 * cosTwoSigmaM * cosTwoSigmaM) -
                coefficientB / 6.0 * cosTwoSigmaM *
                (-3.0 + 4.0 * sinSigma * sinSigma) *
                (-3.0 + 4.0 * cosTwoSigmaM * cosTwoSigmaM)))
    return semiMinor * coefficientA * (sigma - deltaSigma)
}

private fun haversineMeters(a: Pair<Double, Double>, b: Pair<Double, Double>): Double =
    geodesicDistanceMeters(a, b)

private fun bearingDegrees(a:Pair<Double,Double>,b:Pair<Double,Double>):Double {
    val p1=Math.toRadians(a.first);val p2=Math.toRadians(b.first)
    val dl=Math.toRadians(b.second-a.second)
    return (Math.toDegrees(atan2(sin(dl)*cos(p2),cos(p1)*sin(p2)-sin(p1)*cos(p2)*cos(dl)))+360.0)%360.0
}

fun bearingToPoint(from:Pair<Double,Double>,to:Pair<Double,Double>):Double = bearingDegrees(from,to)

data class UtmCoordinate(val zone:Int,val hemisphere:Char,val easting:Double,val northing:Double)
fun toUtm(latitude:Double,longitude:Double):UtmCoordinate? {
    if(latitude !in -80.0..84.0 || longitude !in -180.0..180.0) return null
    val a=6378137.0
    val eccSquared=0.00669437999014
    val k0=0.9996
    var zone=floor((longitude+180.0)/6.0).toInt()+1
    if(latitude>=56.0 && latitude<64.0 && longitude>=3.0 && longitude<12.0) zone=32
    if(latitude>=72.0 && latitude<84.0) {
        zone=when {
            longitude<9.0 -> 31
            longitude<21.0 -> 33
            longitude<33.0 -> 35
            longitude<42.0 -> 37
            else -> zone
        }
    }
    zone=zone.coerceIn(1,60)
    val eccPrimeSquared=eccSquared/(1.0-eccSquared)
    val latRad=Math.toRadians(latitude)
    val lonRad=Math.toRadians(longitude)
    val lonOrigin=Math.toRadians((zone-1)*6-180+3.0)
    val n=a/sqrt(1.0-eccSquared*sin(latRad).pow(2))
    val t=tan(latRad).pow(2)
    val cc=eccPrimeSquared*cos(latRad).pow(2)
    val aa=cos(latRad)*(lonRad-lonOrigin)
    val m=a*((1-eccSquared/4-3*eccSquared.pow(2)/64-5*eccSquared.pow(3)/256)*latRad
        -(3*eccSquared/8+3*eccSquared.pow(2)/32+45*eccSquared.pow(3)/1024)*sin(2*latRad)
        +(15*eccSquared.pow(2)/256+45*eccSquared.pow(3)/1024)*sin(4*latRad)
        -(35*eccSquared.pow(3)/3072)*sin(6*latRad))
    val easting=k0*n*(aa+(1-t+cc)*aa.pow(3)/6+(5-18*t+t.pow(2)+72*cc-58*eccPrimeSquared)*aa.pow(5)/120)+500000.0
    var northing=k0*(m+n*tan(latRad)*(aa.pow(2)/2+(5-t+9*cc+4*cc.pow(2))*aa.pow(4)/24+(61-58*t+t.pow(2)+600*cc-330*eccPrimeSquared)*aa.pow(6)/720))
    if(latitude<0) northing+=10000000.0
    return UtmCoordinate(zone,if(latitude>=0)'N' else 'S',easting,northing)
}

data class PreparedRoute internal constructor(
    val points:List<Pair<Double,Double>>,
    val prefixMeters:DoubleArray,
    val totalMeters:Double
)
fun prepareRoute(points:List<Pair<Double,Double>>):PreparedRoute {
    val prefix=DoubleArray(points.size)
    for(i in 1 until points.size)prefix[i]=prefix[i-1]+haversineMeters(points[i-1],points[i])
    return PreparedRoute(points,prefix,prefix.lastOrNull()?:0.0)
}

/** Projects a GPS fix onto a precomputed GPX route without rebuilding its distance index. */
fun matchRoute(points:List<Pair<Double,Double>>,location:Pair<Double,Double>,lastSegmentIndex:Int?=null):RouteMatch? =
    matchRoute(prepareRoute(points),location,lastSegmentIndex)

fun matchRoute(route:PreparedRoute,location:Pair<Double,Double>,lastSegmentIndex:Int?=null):RouteMatch? {
    val points=route.points
    if(points.size<2)return null
    val prefix=route.prefixMeters
    val total=route.totalMeters
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


/**
 * Stateful-friendly map matching for live AR navigation.
 * Search is bounded by travelled route distance rather than raw GPX point count,
 * preventing nearby switchbacks or loop crossings from stealing the active segment.
 */
fun matchRouteContinuously(
    route: PreparedRoute,
    location: Pair<Double, Double>,
    previous: RouteMatch?,
    maxForwardProgressMeters: Double,
    courseBearingDegrees: Double? = null
): RouteMatch? {
    val points = route.points
    if (points.size < 2) return null
    val prefix = route.prefixMeters
    val latitudeRadians = Math.toRadians(location.first)
    val cosLatitude = cos(latitudeRadians).coerceAtLeast(0.01)
    val minProgress = previous?.let { (it.progressMeters - 12.0).coerceAtLeast(0.0) } ?: 0.0
    val maxProgress = previous?.let {
        min(route.totalMeters, it.progressMeters + maxForwardProgressMeters.coerceAtLeast(8.0))
    } ?: route.totalMeters

    fun segmentAtProgress(value: Double): Int {
        var low = 0
        var high = prefix.lastIndex
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (prefix[middle] <= value) low = middle else high = middle - 1
        }
        return low.coerceAtMost(points.lastIndex - 1)
    }

    val firstSegment = if (previous == null) 0 else segmentAtProgress(minProgress)
    val lastSegment = if (previous == null) points.lastIndex - 1 else segmentAtProgress(maxProgress)
    if (firstSegment > lastSegment) return null

    var bestScore = Double.POSITIVE_INFINITY
    var bestDistance = Double.POSITIVE_INFINITY
    var bestIndex = -1
    var bestFraction = 0.0
    var bestLat = 0.0
    var bestLon = 0.0
    var bestBearing = 0.0

    for (i in firstSegment..lastSegment) {
        val a = points[i]
        val b = points[i + 1]
        val ax = Math.toRadians(a.second - location.second) * 6371000.0 * cosLatitude
        val ay = Math.toRadians(a.first - location.first) * 6371000.0
        val bx = Math.toRadians(b.second - location.second) * 6371000.0 * cosLatitude
        val by = Math.toRadians(b.first - location.first) * 6371000.0
        val dx = bx - ax
        val dy = by - ay
        val denominator = dx * dx + dy * dy
        val fraction = if (denominator < 0.0001) 0.0 else {
            (-(ax * dx + ay * dy) / denominator).coerceIn(0.0, 1.0)
        }
        val px = ax + fraction * dx
        val py = ay + fraction * dy
        val distance = hypot(px, py)
        val segmentLength = prefix[i + 1] - prefix[i]
        val progress = prefix[i] + segmentLength * fraction
        if (previous != null && (progress < minProgress - 0.001 || progress > maxProgress + 0.001)) {
            continue
        }

        var score = distance
        if (previous != null) {
            val progressDelta = progress - previous.progressMeters
            if (progressDelta < -3.0) score += (-progressDelta - 3.0) * 2.5
            if (progressDelta > maxForwardProgressMeters * 0.75) {
                score += (progressDelta - maxForwardProgressMeters * 0.75) * 0.35
            }
        }
        if (courseBearingDegrees != null) {
            val directionError = kotlin.math.abs(signedTurnAngle(courseBearingDegrees, bearingDegrees(a, b)))
            if (directionError > 35.0) score += (directionError - 35.0) * 0.22
        }

        if (score < bestScore) {
            bestScore = score
            bestDistance = distance
            bestIndex = i
            bestFraction = fraction
            bestLat = a.first + (b.first - a.first) * fraction
            bestLon = a.second + (b.second - a.second) * fraction
            bestBearing = bearingDegrees(a, b)
        }
    }

    if (bestIndex < 0) return null
    val segmentLength = prefix[bestIndex + 1] - prefix[bestIndex]
    val progress = prefix[bestIndex] + segmentLength * bestFraction
    return RouteMatch(
        bestIndex, bestFraction, bestDistance, progress, route.totalMeters,
        bestLat, bestLon, bestBearing
    )
}

fun formatNavDistance(meters:Double):String = if(meters>=1000) String.format(java.util.Locale.CHINA,"%.2f km",meters/1000.0) else "${meters.roundToInt()} m"
fun formatDms(value:Double,isLatitude:Boolean):String {
    val absolute=abs(value)
    val degrees=absolute.toInt()
    val minutesFull=(absolute-degrees)*60.0
    val minutes=minutesFull.toInt()
    val seconds=(minutesFull-minutes)*60.0
    val hemisphere=if(isLatitude)if(value>=0)"N" else "S" else if(value>=0)"E" else "W"
    return String.format(java.util.Locale.US,"%d°%02d′%05.2f″%s",degrees,minutes,seconds,hemisphere)
}
fun directionLabel(degrees:Float):String {
    val names=listOf("北","东北","东","东南","南","西南","西","西北")
    return names[(kotlin.math.floor(((degrees%360f+360f)%360f+22.5f)/45f).toInt())%8]
}

fun signedTurnAngle(fromBearing:Double,toBearing:Double):Double = ((toBearing-fromBearing+540.0)%360.0)-180.0

fun turnGuidance(currentHeading:Float,targetBearing:Float):String {
    if(currentHeading.isNaN() || targetBearing.isNaN()) return "等待罗盘"
    val delta=((targetBearing-currentHeading+540f)%360f)-180f
    return when {
        kotlin.math.abs(delta)<18f -> "方向基本正确"
        delta>0f -> "向右修正 ${delta.roundToInt()}°"
        else -> "向左修正 ${kotlin.math.abs(delta).roundToInt()}°"
    }
}
