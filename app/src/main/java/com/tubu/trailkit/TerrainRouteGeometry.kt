package com.tubu.trailkit

import kotlin.math.*

internal data class TerrainRoutePoint(val lat: Double, val lon: Double, val elevation: Double? = null)
internal data class TerrainRouteLod(
    val id: String,
    val line: List<TerrainRoutePoint>,
    val ribbon: List<Pair<Double, Double>>
)
internal data class TerrainWaypointSnapshot(
    val id: String,
    val lat: Double,
    val lon: Double,
    val name: String,
    val description: String,
    val elevation: Double?,
    val priority: Int
)

internal fun buildTerrainWaypointSnapshot(encoded: String): List<TerrainWaypointSnapshot> =
    decodeGpxWaypoints(encoded).mapIndexed { index, waypoint ->
        val name = waypoint.name.ifBlank { "途经点 ${index + 1}" }
        TerrainWaypointSnapshot(
            id = "wp-$index",
            lat = waypoint.lat,
            lon = waypoint.lon,
            name = name,
            description = waypoint.description.ifBlank { waypoint.comment },
            elevation = waypoint.elevation,
            priority = if (waypoint.name.isNotBlank()) 1 else 2
        )
    }

internal object TerrainRouteGeometry {
    private const val EARTH_RADIUS = 6378137.0
    private data class XY(val x: Double, val y: Double)

    fun buildLods(
        coordinates: List<Pair<Double, Double>>,
        elevations: List<Double?>,
        waypoints: List<TerrainWaypointSnapshot>,
        ribbonWidthMeters: Double = 2.5
    ): List<TerrainRouteLod> {
        if (coordinates.size < 2) return emptyList()
        val points = coordinates.mapIndexed { index, coordinate ->
            TerrainRoutePoint(coordinate.first, coordinate.second, elevations.getOrNull(index))
        }
        val centerLat = points.map { it.lat }.average()
        val centerLon = points.map { it.lon }.average()
        val cosLat = cos(Math.toRadians(centerLat)).coerceAtLeast(1e-6)
        val projected = points.map {
            XY(
                EARTH_RADIUS * cosLat * Math.toRadians(it.lon - centerLon),
                EARTH_RADIUS * Math.toRadians(it.lat - centerLat)
            )
        }
        val anchors = linkedSetOf(0, points.lastIndex)
        waypoints.forEach { waypoint ->
            var best = 0
            var bestDistance = Double.POSITIVE_INFINITY
            val targetX = EARTH_RADIUS * cosLat * Math.toRadians(waypoint.lon - centerLon)
            val targetY = EARTH_RADIUS * Math.toRadians(waypoint.lat - centerLat)
            projected.forEachIndexed { index, p ->
                val distance = hypot(p.x - targetX, p.y - targetY)
                if (distance < bestDistance) { bestDistance = distance; best = index }
            }
            anchors.add(best)
        }
        for (i in 1 until projected.lastIndex) {
            val a = projected[i - 1]; val b = projected[i]; val c = projected[i + 1]
            val ax = b.x - a.x; val ay = b.y - a.y
            val bx = c.x - b.x; val by = c.y - b.y
            val denom = hypot(ax, ay) * hypot(bx, by)
            if (denom > 0.01) {
                val turn = Math.toDegrees(acos(((ax * bx + ay * by) / denom).coerceIn(-1.0, 1.0)))
                if (turn >= 25.0) anchors.add(i)
            }
        }
        val specs = listOf("overview" to 20.0, "mid" to 6.0, "near" to 1.2, "full" to 0.0)
        return specs.map { (id, tolerance) ->
            val indices = simplify(projected, anchors, tolerance)
            val selected = indices.map { points[it] }
            val selectedXY = indices.map { projected[it] }
            val ring = buffer(selectedXY, ribbonWidthMeters / 2.0).map { p ->
                val lon = centerLon + Math.toDegrees(p.x / (EARTH_RADIUS * cosLat))
                val lat = centerLat + Math.toDegrees(p.y / EARTH_RADIUS)
                lon to lat
            }
            TerrainRouteLod(id, selected, ring)
        }
    }

    private fun simplify(points: List<XY>, anchors: Set<Int>, tolerance: Double): List<Int> {
        if (tolerance <= 0.0 || points.size <= 2) return points.indices.toList()
        val fixed = anchors.sorted()
        val keep = BooleanArray(points.size)
        fixed.forEach { keep[it] = true }
        for (segment in 0 until fixed.lastIndex) {
            val start = fixed[segment]
            val end = fixed[segment + 1]
            if (end <= start + 1) continue
            val stack = ArrayDeque<Pair<Int, Int>>()
            stack.addLast(start to end)
            while (stack.isNotEmpty()) {
                val (a, b) = stack.removeLast()
                var maxDistance = tolerance
                var maxIndex = -1
                for (i in a + 1 until b) {
                    val distance = perpendicularDistance(points[i], points[a], points[b])
                    if (distance > maxDistance) { maxDistance = distance; maxIndex = i }
                }
                if (maxIndex >= 0) {
                    keep[maxIndex] = true
                    stack.addLast(a to maxIndex)
                    stack.addLast(maxIndex to b)
                }
            }
        }
        return points.indices.filter { keep[it] }
    }

    private fun perpendicularDistance(p: XY, a: XY, b: XY): Double {
        val dx = b.x - a.x; val dy = b.y - a.y
        val length2 = dx * dx + dy * dy
        if (length2 <= 1e-12) return hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / length2).coerceIn(0.0, 1.0)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    private fun buffer(points: List<XY>, halfWidth: Double): List<XY> {
        if (points.size < 2) return emptyList()
        fun side(sign: Double): List<XY> {
            val result = ArrayList<XY>(points.size + 8)
            for (i in points.indices) {
                val p = points[i]
                if (i == 0 || i == points.lastIndex) {
                    val a = points[if (i == 0) 0 else i - 1]
                    val b = points[if (i == 0) 1 else i]
                    val dx = b.x - a.x; val dy = b.y - a.y
                    val len = hypot(dx, dy).coerceAtLeast(1e-9)
                    result.add(XY(p.x - dy / len * halfWidth * sign, p.y + dx / len * halfWidth * sign))
                } else {
                    val prev = points[i - 1]; val next = points[i + 1]
                    val d1x = p.x - prev.x; val d1y = p.y - prev.y
                    val d2x = next.x - p.x; val d2y = next.y - p.y
                    val l1 = hypot(d1x, d1y).coerceAtLeast(1e-9)
                    val l2 = hypot(d2x, d2y).coerceAtLeast(1e-9)
                    val n1x = -d1y / l1 * sign; val n1y = d1x / l1 * sign
                    val n2x = -d2y / l2 * sign; val n2y = d2x / l2 * sign
                    var mx = n1x + n2x; var my = n1y + n2y
                    val ml = hypot(mx, my)
                    if (ml < 1e-6) {
                        result.add(XY(p.x + n1x * halfWidth, p.y + n1y * halfWidth))
                        result.add(XY(p.x + n2x * halfWidth, p.y + n2y * halfWidth))
                    } else {
                        mx /= ml; my /= ml
                        val denominator = mx * n2x + my * n2y
                        val miterLength = if (abs(denominator) < 1e-6) Double.POSITIVE_INFINITY else halfWidth / denominator
                        if (!miterLength.isFinite() || abs(miterLength) > halfWidth * 2.0) {
                            result.add(XY(p.x + n1x * halfWidth, p.y + n1y * halfWidth))
                            result.add(XY(p.x + n2x * halfWidth, p.y + n2y * halfWidth))
                        } else {
                            result.add(XY(p.x + mx * miterLength, p.y + my * miterLength))
                        }
                    }
                }
            }
            return result
        }
        val left = side(1.0)
        val right = side(-1.0).asReversed()
        val ring = left + right
        return if (ring.isEmpty()) ring else ring + ring.first()
    }
}
