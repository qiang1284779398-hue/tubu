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
        val encoded = """W1[{"lat":28.1,"lon":98.2,"name":"冰湖","desc":"高山湖泊","ele":3900},{"lat":28.2,"lon":98.3}]"""
        val snapshot = buildTerrainWaypointSnapshot(encoded)
        assertEquals(2, snapshot.size)
        assertEquals("wp-0", snapshot[0].id)
        assertEquals("冰湖", snapshot[0].name)
        assertEquals("高山湖泊", snapshot[0].description)
        assertEquals(3900.0, snapshot[0].elevation!!, 0.0)
        assertEquals("途经点 2", snapshot[1].name)
    }

    @Test fun handlesLongTrackWithoutFixedTileOrPointLimit() {
        val points = (0 until 50_000).map { i ->
            val lat = 28.0 + i * 0.000001
            val lon = 98.0 + i * 0.000001
            lat to lon
        }
        val lods = TerrainRouteGeometry.buildLods(points, emptyList(), emptyList())
        assertEquals(50_000, lods.last().line.size)
        assertTrue(lods.all { it.ribbon.isNotEmpty() })
    }
}
