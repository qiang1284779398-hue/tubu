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
    private val alpha = response.coerceIn(0.05, 0.8)

    @Synchronized
    fun apply(raw: ArCameraPose): ArCameraPose {
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
    return elevations.indices.map { index ->
        val start = (index - radius).coerceAtLeast(0)
        val end = (index + radius).coerceAtMost(elevations.lastIndex)
        val values = (start..end).mapNotNull { elevations[it] }.filter { it.isFinite() }.sorted()
        if (values.isEmpty()) null else values[values.size / 2]
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
    if (cameraZ <= 1.0) return null
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
