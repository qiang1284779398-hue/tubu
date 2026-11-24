package com.tubu.trailkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.*

class TerrainRouteGeometryTest {
    @Test fun createsFourLodsAndPreservesEndpointsAndSharpBends() {
        val points = (0..1000).map { i ->
            val lat = 28.0 + i * 0.00001
            val lon = 98.0 + if (i < 500) i * 0.00001 else 0.005 - (i - 500) * 0.00001
            lat to lon
        }
        val lods = TerrainRouteGeometry.buildLods(points, emptyList(), emptyList())
        assertEquals(listOf("overview", "mid", "near", "full"), lods.map { it.id })
        lods.forEach {
            assertEquals(points.first().first, it.line.first().lat, 1e-9)
            assertEquals(points.last().second, it.line.last().lon, 1e-9)
            assertTrue(it.ribbon.size >= 5)
            assertEquals(it.ribbon.first(), it.ribbon.last())
            assertTrue(it.ribbon.all { p -> p.first.isFinite() && p.second.isFinite() })
        }
        assertEquals(points.size, lods.last().line.size)
        assertTrue(lods.first().line.size < lods.last().line.size)
    }

    @Test fun waypointSnapshotKeepsDescriptionElevationAndStableIds() {
        val snapshot = buildTerrainWaypointSnapshot(
            listOf(
                GpxWaypoint(28.1, 98.2, "冰湖", description = "高山湖泊", elevation = 3900.0),
                GpxWaypoint(28.2, 98.3, "")
            )
        )
        assertEquals(2, snapshot.size)
        assertEquals("wp-0", snapshot[0].id)
        assertEquals("冰湖", snapshot[0].name)
        assertEquals("高山湖泊", snapshot[0].description)
        assertEquals(3900.0, snapshot[0].elevation!!, 0.0)
        assertEquals(1, snapshot[0].priority)
        assertEquals("途经点 2", snapshot[1].name)
        assertEquals(2, snapshot[1].priority)
    }


    @Test fun ribbonHasRealWorldWidthOnStraightTrack() {
        val coordinates = listOf(
            28.0 to 98.0,
            28.0 to 98.001,
            28.0 to 98.002
        )
        val ribbon = TerrainRouteGeometry.buildLods(coordinates, emptyList(), emptyList()).last().ribbon
        fun distanceMeters(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
            val lat1 = Math.toRadians(a.second)
            val lat2 = Math.toRadians(b.second)
            val dLat = lat2 - lat1
            val dLon = Math.toRadians(b.first - a.first)
            val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
            return 2.0 * 6371000.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }
        // Ring order is left side forward, then right side backward.
        assertEquals(2.5, distanceMeters(ribbon[0], ribbon[5]), 0.05)
        assertEquals(ribbon.first(), ribbon.last())
    }

    @Test fun handles100KmTrackWith50000PointsWithoutFixedTileOrPointLimit() {
        val pointCount = 50_000
        val stepMeters = 100_000.0 / (pointCount - 1)
        val latitudeStep = stepMeters / 111_195.0
        val points = (0 until pointCount).map { i ->
            (28.0 + i * latitudeStep) to 98.0
        }
        val measuredLength = (points.last().first - points.first().first) * 111_195.0
        assertEquals(100_000.0, measuredLength, 2.0)

        val lods = TerrainRouteGeometry.buildLods(points, emptyList(), emptyList())
        assertEquals(pointCount, lods.last().line.size)
        assertTrue(lods.first().line.size < lods.last().line.size)
        assertTrue(lods.all { it.ribbon.isNotEmpty() && it.ribbon.all { p -> p.first.isFinite() && p.second.isFinite() } })
    }
}
