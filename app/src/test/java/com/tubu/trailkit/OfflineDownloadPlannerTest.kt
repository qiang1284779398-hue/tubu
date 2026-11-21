package com.tubu.trailkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineDownloadPlannerTest {
    @Test fun shortRouteUsesSeparateBroadAndHighDetailCorridors() {
        val plan=OfflineDownloadPlanner.createPlan(listOf(31.0 to 121.0,31.01 to 121.01))
        assertEquals(2,plan.regions.size)
        assertEquals(9,plan.regions[0].minZoom
        assertEquals(11,plan.regions[0].maxZoom)
        assertEquals(900.0,plan.regions[0].bufferMeters,0.0
        assertEquals(12,plan.regions[1].minZoom)
        assertEquals(15,plan.regions[1].maxZoom)
        assertEquals(220.0,plan.regions[1].bufferMeters,0.0
        assertTrue(plan.regions.all{it.geoJson.startsWith("{")})
        assertTrue(plan.regions.all{it.estimatedTileCount>0})
    }

    @Test fun longRouteIsSplitIntoOverlappingDownloadRegions() {
        val plan=OfflineDownloadPlanner.createPlan(listOf(0.0 to 0.0,0.0 to 0.12))
        assertTrue(plan.regions.size>2)
        assertEquals(plan.regions.size/2,plan.regions.count{it.minZoom==9})
        assertEquals(plan.regions.size/2,plan.regions.count{it.minZoom==12})
    }

    @Test fun estimateGrowsWithRouteLengthAndBufferWidth() {
        val short=listOf(0.0 to 0.0,0.0 to 0.01)
        val long=listOf(0.0 to 0.0,0.0 to 0.03)
        val narrow=OfflineDownloadPlanner.estimateTileCount(short,300.0,13,16)
        val wide=OfflineDownloadPlanner.estimateTileCount(short,900.0,13,16)
        val longer=OfflineDownloadPlanner.estimateTileCount(long,300.0,13,16)
        assertTrue(wide>narrow)
        assertTrue(longer>narrow)
    }

    @Test fun invalidAndDuplicateCoordinatesAreDiscarded() {
        val plan=OfflineDownloadPlanner.createPlan(
            listOf(0.0 to 0.0,0.0 to 0.0,Double.NaN to 5.0,91.0 to 0.0)
        )
        assertTrue(plan.regions.isEmpty())
    }
}
