package com.tubu.trailkit

import kotlin.math.*

/** Camera pose expressed in a true-north East/North/Up world frame. */
data class ArVector3(val east: Double, val north: Double, val up: Double) {
    operator fun unaryMinus() = ArVector3(-east, -north, -up)
    operator fun plus(other: ArVector3) = ArVector3(east + other.east, north + other.north, up + other.up)
    operator fun minus(other: ArVector3) = ArVector3(east - other.east, north - other.north, up - other.up)
    operator fun times(value: Double) = ArVector3(east * value, north * value, up * value)
    fun dot(other: ArVector3): Double = east * other.east + north * other.north + up * other.up
}

data class ArCameraPose(
    val imageRight: ArVector3,
    val imageUp: ArVector3,
    val forward: ArVector3
) {
    val headingDegrees: Double
        get() = (Math.toDegrees(atan2(forward.east, forward.north)) + 360.0) % 360.0
    val pitchDegrees: Double
        get() = Math.toDegrees(asin(forward.up.coerceIn(-1.0, 1.0)))
    val rollDegrees: Double
        get() = Math.toDegrees(atan2(imageRight.up, imageUp.up))
}

/**
 * Low-pass filters the fused rotation-vector pose before it drives the camera overlay.
 * Filtering the 3D basis (rather than heading degrees) avoids a false 359° -> 0° jump.
 */
internal class ArCameraPoseFilter(private val response: Double = 0.22) {
    private var filtered: ArCameraPose? = null
    private val defaultAlpha = response.coerceIn(0.05, 0.8)
    private var magneticBaseline: Double? = null

    @Synchronized
    fun apply(raw: ArCameraPose): ArCameraPose = blendPose(raw, defaultAlpha)

    /**
     * Adaptive filter. The blend convention is previous*(1-alpha)+raw*alpha:
     * larger alpha follows rapid turns more closely.
     */
    @Synchronized
    fun applyAdaptive(
        raw: ArCameraPose,
        angularVelocityRadPerSec: Double,
        magneticFieldMagnitudeMicroTesla: Double?,
        sensorAccuracy: Int
    ): ArCameraPose {
        val speed = angularVelocityRadPerSec.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        val alpha = when {
            speed < 0.15 -> 0.12
            speed > 0.6 -> 0.42
            else -> 0.12 + (speed - 0.15) / 0.45 * 0.30
        }
        val magnitude = magneticFieldMagnitudeMicroTesla?.takeIf { it.isFinite() && it in 10.0..100.0 }
        if (magnitude != null && magneticBaseline == null) magneticBaseline = magnitude
        val baseline = magneticBaseline
        val deviation = if (magnitude != null && baseline != null && baseline > 0.0)
            kotlin.math.abs(magnitude - baseline) / baseline else 0.0
        val reliability = when {
            sensorAccuracy == 0 || deviation >= 0.20 -> 0.08
            deviation >= 0.15 -> 0.45
            else -> 1.0
        }
        if (magnitude != null && reliability == 1.0) {
            magneticBaseline = baseline?.let { it * 0.995 + magnitude * 0.005 } ?: magnitude
        }
        return blendPose(raw, (alpha * reliability).coerceIn(0.04, 0.50))
    }

    private fun blendPose(raw: ArCameraPose, alpha: Double): ArCameraPose {
        val previous = filtered
        if (previous == null) {
            filtered = raw
            return raw
        }
        fun blend(from: ArVector3, to: ArVector3): ArVector3 =
            from * (1.0 - alpha) + to * alpha
        fun normalize(vector: ArVector3): ArVector3? {
            val length = sqrt(vector.dot(vector))
            return if (length.isFinite() && length > 1e-6) vector * (1.0 / length) else null
        }
        fun cross(a: ArVector3, b: ArVector3) = ArVector3(
            a.north * b.up - a.up * b.north,
            a.up * b.east - a.east * b.up,
            a.east * b.north - a.north * b.east
        )
        val forward = normalize(blend(previous.forward, raw.forward)) ?: raw.forward
        val blendedRight = blend(previous.imageRight, raw.imageRight)
        val orthogonalRight = blendedRight - forward * blendedRight.dot(forward)
        val right = normalize(orthogonalRight) ?: raw.imageRight
        val up = normalize(cross(right, forward)) ?: raw.imageUp
        return ArCameraPose(right, up, forward).also { filtered = it }
    }

    @Synchronized
    fun reset() {
        filtered = null
        magneticBaseline = null
    }
}

/**
 * Converts Android's device-to-world rotation matrix into the camera view basis.
 * The back camera looks along device -Z; display rotation remaps the image X/Y axes.
 */
fun arCameraPoseFromRotationMatrix(matrix: FloatArray, displayRotation: Int): ArCameraPose? {
    if (matrix.size < 9 || matrix.take(9).any { !it.isFinite() }) return null
    val x = ArVector3(matrix[0].toDouble(), matrix[3].toDouble(), matrix[6].toDouble())
    val y = ArVector3(matrix[1].toDouble(), matrix[4].toDouble(), matrix[7].toDouble())
    val z = ArVector3(matrix[2].toDouble(), matrix[5].toDouble(), matrix[8].toDouble())
    val (right, up) = when (displayRotation) {
        1 -> y to -x
        2 -> -x to -y
        3 -> -y to x
        else -> x to y
    }
    val pose = ArCameraPose(right, up, -z)
    val lengths = listOf(pose.imageRight, pose.imageUp, pose.forward).map { sqrt(it.dot(it)) }
    if (lengths.any { it < 0.5 || it > 1.5 }) return null
    return pose
}

/** Android rotation vectors reference magnetic north; route bearings use true north. */
fun applyArMagneticDeclination(pose: ArCameraPose, declinationDegrees: Double): ArCameraPose {
    val radians = Math.toRadians(declinationDegrees)
    val c = cos(radians)
    val s = sin(radians)
    fun rotate(v: ArVector3) = ArVector3(
        v.east * c + v.north * s,
        v.north * c - v.east * s,
        v.up
    )
    return ArCameraPose(rotate(pose.imageRight), rotate(pose.imageUp), rotate(pose.forward))
}

data class ArCameraIntrinsics(
    val sensorWidthMm: Double,
    val sensorHeightMm: Double,
    val focalLengthMm: Double,
    val calibrated: Boolean
) {
    /** Pixel focal length after CameraX FILL_CENTER crops the sensor image to the viewport. */
    fun focalPixels(viewWidth: Double, viewHeight: Double): Double {
        if (viewWidth <= 0.0 || viewHeight <= 0.0 || sensorWidthMm <= 0.0 ||
            sensorHeightMm <= 0.0 || focalLengthMm <= 0.0) return 0.0
        val sensorAspect = sensorWidthMm / sensorHeightMm
        val viewAspect = viewWidth / viewHeight
        return if (viewAspect > sensorAspect) {
            focalLengthMm / sensorWidthMm * viewWidth
        } else {
            focalLengthMm / sensorHeightMm * viewHeight
        }
    }
}

data class ArScreenPoint(val x: Float, val y: Float)
data class ArRouteSample(
    val latitude: Double,
    val longitude: Double,
    val progressMeters: Double,
    val elevationMeters: Double?
)

private fun arInterpolateRoutePoint(
    route: PreparedRoute,
    elevations: List<Double?>,
    distanceMeters: Double
): ArRouteSample {
    val prefix = route.prefixMeters
    val target = distanceMeters.coerceIn(0.0, route.totalMeters)
    var low = 0
    var high = prefix.lastIndex
    while (low < high) {
        val mid = (low + high + 1) ushr 1
        if (prefix[mid] <= target) low = mid else high = mid - 1
    }
    val index = low.coerceAtMost(route.points.lastIndex - 1)
    val start = route.points[index]
    val end = route.points[index + 1]
    val segmentLength = prefix[index + 1] - prefix[index]
    val fraction = if (segmentLength <= 0.001) 0.0 else ((target - prefix[index]) / segmentLength).coerceIn(0.0, 1.0)
    val startElevation = elevations.getOrNull(index)
    val endElevation = elevations.getOrNull(index + 1)
    val elevation = if (startElevation != null && endElevation != null) {
        startElevation + (endElevation - startElevation) * fraction
    } else null
    return ArRouteSample(
        start.first + (end.first - start.first) * fraction,
        start.second + (end.second - start.second) * fraction,
        target,
        elevation
    )
}

/** Median-filter short elevation spikes caused by arm/wrist motion and barometric noise. */
fun smoothArRouteElevations(elevations: List<Double?>, radius: Int = 2): List<Double?> {
    if (elevations.isEmpty() || radius <= 0) return elevations
    val median = elevations.indices.map { index ->
        val start = (index - radius).coerceAtLeast(0)
        val end = (index + radius).coerceAtMost(elevations.lastIndex)
        val values = (start..end).mapNotNull { elevations[it] }.filter { it.isFinite() }.sorted()
        if (values.isEmpty()) null else values[values.size / 2]
    }
    // A light 3-point pass suppresses residual barometric noise without touching route XY.
    return median.indices.map { index ->
        val values = (index - 1..index + 1).mapNotNull { median.getOrNull(it) }
        if (values.isEmpty()) null else values.average()
    }
}
/** Resamples GPX at a fixed metric interval so sparse files still render smooth bends. */
fun sampleArRouteAhead(
    route: PreparedRoute,
    elevations: List<Double?>,
    startProgressMeters: Double,
    maxAheadMeters: Double = 120.0,
    stepMeters: Double = 4.0
): List<ArRouteSample> {
    if (route.points.size < 2 || route.totalMeters <= 0.0 || stepMeters <= 0.0) return emptyList()
    val start = startProgressMeters.coerceIn(0.0, route.totalMeters)
    val end = min(route.totalMeters, start + maxAheadMeters.coerceAtLeast(0.0))
    val samples = ArrayList<ArRouteSample>()
    var distance = start
    while (distance < end) {
        samples.add(arInterpolateRoutePoint(route, elevations, distance))
        distance += stepMeters
    }
    samples.add(arInterpolateRoutePoint(route, elevations, end))
    return samples.distinctBy { (it.progressMeters * 10.0).roundToLong() }
}

private fun arDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val radius = 6371000.0
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dp = Math.toRadians(lat2 - lat1)
    val dl = Math.toRadians(lon2 - lon1)
    val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
    return 2.0 * radius * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

private fun arBearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dl = Math.toRadians(lon2 - lon1)
    return (Math.toDegrees(atan2(sin(dl) * cos(p2), cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl))) + 360.0) % 360.0
}

fun offsetArGeoPoint(
    latitude: Double,
    longitude: Double,
    bearingDegrees: Double,
    distanceMeters: Double
): Pair<Double, Double> {
    val radius = 6371000.0
    val bearing = Math.toRadians(bearingDegrees)
    val lat1 = Math.toRadians(latitude)
    val lon1 = Math.toRadians(longitude)
    val angular = distanceMeters / radius
    val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing))
    val lon2 = lon1 + atan2(
        sin(bearing) * sin(angular) * cos(lat1),
        cos(angular) - sin(lat1) * sin(lat2)
    )
    return Math.toDegrees(lat2) to ((Math.toDegrees(lon2) + 540.0) % 360.0 - 180.0)
}

/** Projects a real East/North/Up point through the calibrated camera basis. */
fun projectArWorldPoint(
    eastMeters: Double,
    northMeters: Double,
    upMeters: Double,
    pose: ArCameraPose,
    focalPixels: Double,
    viewWidth: Double,
    viewHeight: Double
): ArScreenPoint? {
    if (focalPixels <= 0.0 || viewWidth <= 0.0 || viewHeight <= 0.0) return null
    val world = ArVector3(eastMeters, northMeters, upMeters)
    val cameraX = pose.imageRight.dot(world)
    val cameraY = -pose.imageUp.dot(world)
    val cameraZ = pose.forward.dot(world)
    if (cameraZ <= 0.5) return null
    val x = viewWidth * 0.5 + focalPixels * cameraX / cameraZ
    val y = viewHeight * 0.5 + focalPixels * cameraY / cameraZ
    if (!x.isFinite() || !y.isFinite() || abs(x) > viewWidth * 8 || abs(y) > viewHeight * 8) return null
    return ArScreenPoint(x.toFloat(), y.toFloat())
}

/**
 * Projects a GPX point relative to the current GPS fix. Elevation is aligned to the
 * elevation at the matched route position, avoiding dependence on absolute GPS altitude.
 */
fun projectArRouteSample(
    originLatitude: Double,
    originLongitude: Double,
    sample: ArRouteSample,
    referenceElevationMeters: Double?,
    cameraHeightMeters: Double,
    pose: ArCameraPose,
    focalPixels: Double,
    viewWidth: Double,
    viewHeight: Double
): ArScreenPoint? {
    val distance = arDistanceMeters(originLatitude, originLongitude, sample.latitude, sample.longitude)
    val bearing = Math.toRadians(arBearingDegrees(originLatitude, originLongitude, sample.latitude, sample.longitude))
    val east = distance * sin(bearing)
    val north = distance * cos(bearing)
    val up = if (sample.elevationMeters != null && referenceElevationMeters != null) {
        sample.elevationMeters - referenceElevationMeters - cameraHeightMeters
    } else {
        -cameraHeightMeters
    }
    return projectArWorldPoint(east, north, up, pose, focalPixels, viewWidth, viewHeight)
}


/** Camera-space vertex used for robust clipping at the near plane. */
data class ArCameraSpaceVertex(val x: Double, val y: Double, val z: Double)

/** Clips a polygon against z >= nearPlane, creating intersection vertices instead of dropping a whole segment. */
fun clipArPolygonToNearPlane(
    vertices: List<ArCameraSpaceVertex>,
    nearPlane: Double = 0.5
): List<ArCameraSpaceVertex> {
    if (vertices.size < 3 || !nearPlane.isFinite() || nearPlane <= 0.0) return emptyList()
    val output = ArrayList<ArCameraSpaceVertex>()
    var previous = vertices.last()
    var previousInside = previous.z >= nearPlane
    for (current in vertices) {
        val currentInside = current.z >= nearPlane
        if (currentInside != previousInside) {
            val denominator = current.z - previous.z
            if (kotlin.math.abs(denominator) > 1e-12) {
                val t = ((nearPlane - previous.z) / denominator).coerceIn(0.0, 1.0)
                output += ArCameraSpaceVertex(
                    previous.x + (current.x - previous.x) * t,
                    previous.y + (current.y - previous.y) * t,
                    nearPlane
                )
            }
        }
        if (currentInside) output += current
        previous = current
        previousInside = currentInside
    }
    return output
}

fun projectArWorldPolygon(
    worldVertices: List<ArVector3>,
    pose: ArCameraPose,
    focalPixels: Double,
    viewWidth: Double,
    viewHeight: Double,
    nearPlane: Double = 0.5
): List<ArScreenPoint> {
    if (focalPixels <= 0.0 || viewWidth <= 0.0 || viewHeight <= 0.0) return emptyList()
    val cameraVertices = worldVertices.map { world ->
        ArCameraSpaceVertex(
            pose.imageRight.dot(world),
            -pose.imageUp.dot(world),
            pose.forward.dot(world)
        )
    }
    return clipArPolygonToNearPlane(cameraVertices, nearPlane).mapNotNull { point ->
        val x = viewWidth * 0.5 + focalPixels * point.x / point.z
        val y = viewHeight * 0.5 + focalPixels * point.y / point.z
        if (x.isFinite() && y.isFinite() && kotlin.math.abs(x) <= viewWidth * 8 &&
            kotlin.math.abs(y) <= viewHeight * 8
        ) ArScreenPoint(x.toFloat(), y.toFloat()) else null
    }
}

/** Interpolates missing DEM samples from the nearest valid neighbours, then uses GPX fallback. */
fun resolveArElevationSamples(dem: List<Double?>, gpxFallback: List<Double?>): List<Double?> {
    if (dem.isEmpty()) return emptyList()
    val valid = dem.indices.filter { dem[it]?.isFinite() == true }
    return dem.indices.map { index ->
        dem[index]?.takeIf { it.isFinite() } ?: run {
            val before = valid.lastOrNull { it < index }
            val after = valid.firstOrNull { it > index }
            when {
                before != null && after != null -> {
                    val a = dem[before]!!
                    val b = dem[after]!!
                    a + (b - a) * (index - before).toDouble() / (after - before)
                }
                else -> gpxFallback.getOrNull(index)?.takeIf { it.isFinite() }
            }
        }
    }
}

/** Adaptive 0-120 m sampling: dense close to the camera and around bends/steep grade. */
fun sampleArRouteAheadAdaptive(
    route: PreparedRoute,
    elevations: List<Double?>,
    startProgressMeters: Double,
    maxAheadMeters: Double = 120.0
): List<ArRouteSample> {
    if (route.points.size < 2 || route.totalMeters <= 0.0 || maxAheadMeters <= 0.0) return emptyList()
    val start = startProgressMeters.coerceIn(0.0, route.totalMeters)
    val end = min(route.totalMeters, start + maxAheadMeters)
    val result = ArrayList<ArRouteSample>()
    var distance = start
    while (distance < end - 0.001) {
        val sample = arInterpolateRoutePoint(route, elevations, distance)
        val before = arInterpolateRoutePoint(route, elevations, (distance - 2.0).coerceAtLeast(0.0))
        val after = arInterpolateRoutePoint(route, elevations, (distance + 2.0).coerceAtMost(route.totalMeters))
        val turn = abs(signedTurnAngle(
            arBearingDegrees(before.latitude, before.longitude, sample.latitude, sample.longitude),
            arBearingDegrees(sample.latitude, sample.longitude, after.latitude, after.longitude)
        ))
        val grade = if (before.elevationMeters != null && after.elevationMeters != null) {
            abs(after.elevationMeters - before.elevationMeters) / 4.0
        } else 0.0
        val near = distance - start < 30.0
        val step = when {
            turn > 15.0 || grade > 0.12 -> if (near) 0.75 else 1.75
            near -> 2.0
            else -> 3.5
        }
        result += sample
        distance += step
    }
    result += arInterpolateRoutePoint(route, elevations, end)
    return result.distinctBy { (it.progressMeters * 100.0).roundToLong() }
}

/** Conservative course correction; callers must establish phone/course alignment and no manual turn. */
fun fuseArGpsCourse(
    magneticHeadingDegrees: Double,
    gpsCourseDegrees: Double,
    speedMetersPerSecond: Double,
    stableCycles: Int,
    phoneAlignedWithTravel: Boolean,
    userTurningPhone: Boolean,
    gain: Double = 0.08
): Double {
    if (!magneticHeadingDegrees.isFinite() || !gpsCourseDegrees.isFinite() ||
        speedMetersPerSecond < 1.0 || stableCycles < 3 || !phoneAlignedWithTravel || userTurningPhone
    ) return magneticHeadingDegrees
    val delta = signedTurnAngle(magneticHeadingDegrees, gpsCourseDegrees)
    return (magneticHeadingDegrees + delta * gain.coerceIn(0.05, 0.10) + 360.0) % 360.0
}

fun arRouteSampleWorldVectorFromAnchor(
    anchorLatitude: Double,
    anchorLongitude: Double,
    cameraLatitude: Double,
    cameraLongitude: Double,
    sample: ArRouteSample,
    referenceElevationMeters: Double?,
    cameraHeightMeters: Double
): ArVector3 {
    fun enu(lat: Double, lon: Double): Pair<Double, Double> {
        val distance = arDistanceMeters(anchorLatitude, anchorLongitude, lat, lon)
        val bearing = Math.toRadians(arBearingDegrees(anchorLatitude, anchorLongitude, lat, lon))
        return distance * sin(bearing) to distance * cos(bearing)
    }
    val camera = enu(cameraLatitude, cameraLongitude)
    val target = enu(sample.latitude, sample.longitude)
    val up = if (sample.elevationMeters != null && referenceElevationMeters != null)
        sample.elevationMeters - referenceElevationMeters - cameraHeightMeters else -cameraHeightMeters
    return ArVector3(target.first - camera.first, target.second - camera.second, up)
}

/** ENU projection anchored at P_snap while retaining the real GPS optical-centre offset. */
fun projectArRouteSampleFromAnchor(
    anchorLatitude: Double,
    anchorLongitude: Double,
    cameraLatitude: Double,
    cameraLongitude: Double,
    sample: ArRouteSample,
    referenceElevationMeters: Double?,
    cameraHeightMeters: Double,
    pose: ArCameraPose,
    focalPixels: Double,
    viewWidth: Double,
    viewHeight: Double
): ArScreenPoint? {
    fun enu(lat: Double, lon: Double): Pair<Double, Double> {
        val distance = arDistanceMeters(anchorLatitude, anchorLongitude, lat, lon)
        val bearing = Math.toRadians(arBearingDegrees(anchorLatitude, anchorLongitude, lat, lon))
        return distance * sin(bearing) to distance * cos(bearing)
    }
    val camera = enu(cameraLatitude, cameraLongitude)
    val target = enu(sample.latitude, sample.longitude)
    val up = if (sample.elevationMeters != null && referenceElevationMeters != null)
        sample.elevationMeters - referenceElevationMeters - cameraHeightMeters else -cameraHeightMeters
    return projectArWorldPoint(
        target.first - camera.first, target.second - camera.second, up,
        pose, focalPixels, viewWidth, viewHeight
    )
}

/** Removes only unmistakable isolated coordinate spikes; ordinary bends and sparse legs remain intact. */
fun cleanArRoutePoints(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
    if (points.size < 3) return points
    val cleaned = ArrayList<Pair<Double, Double>>(points.size)
    cleaned += points.first()
    for (index in 1 until points.lastIndex) {
        val previous = cleaned.last()
        val current = points[index]
        val next = points[index + 1]
        val incoming = arDistanceMeters(previous.first, previous.second, current.first, current.second)
        val outgoing = arDistanceMeters(current.first, current.second, next.first, next.second)
        val bypass = arDistanceMeters(previous.first, previous.second, next.first, next.second)
        if (!(incoming > 120.0 && outgoing > 120.0 && bypass < 30.0)) cleaned += current
    }
    cleaned += points.last()
    return cleaned
}

