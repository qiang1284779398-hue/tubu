package com.tubu.trailkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationEngineTest {
    @Test fun projectsGpsFixOntoRouteSegment() {
        val points=listOf(0.0 to 0.0, 0.0 to 0.01)
        val match=matchRoute(points,0.001 to 0.005)
        assertNotNull(match)
        assertTrue(match!!.distanceMeters in 105.0..118.0)
        assertTrue(match.progressMeters in 545.0..570.0)
        assertTrue(match.remainingMeters in 545.0..570.0)
    }
    @Test fun exactRouteFixHasNearZeroDeviation() {
        val points=listOf(31.0 to 121.0,31.001 to 121.001,31.002 to 121.002)
        val match=matchRoute(points,31.001 to 121.001)
        assertNotNull(match)
        assertTrue(match!!.distanceMeters<0.5)
    }
    @Test fun directionLabelsCoverEightSectors() {
        assertEquals("北",directionLabel(0f))
        assertEquals("东",directionLabel(90f))
        assertEquals("南",directionLabel(180f))
        assertEquals("西",directionLabel(270f))
    }
    @Test fun turnGuidanceUsesShortestTurnDirection() {
        assertEquals("方向基本正确",turnGuidance(90f,100f))
        assertEquals("向右修正 90°",turnGuidance(0f,90f))
        assertEquals("向左修正 90°",turnGuidance(180f,90f))
    }
    @Test fun waypointBearingUsesTrueNorthClockwiseDegrees() {
        assertTrue(kotlin.math.abs(bearingToPoint(0.0 to 0.0,0.0 to 1.0)-90.0)<0.01)
        assertTrue(kotlin.math.abs(bearingToPoint(0.0 to 0.0,1.0 to 0.0)-0.01)<0.01)
    }
    @Test fun reversedRouteCanBeMatchedFromOppositeStart() {
        val route=listOf(0.0 to 0.0,0.0 to 0.01,0.0 to 0.02)
        val reversed=route.asReversed()
        val match=matchRoute(reversed,0.0 to 0.015)
        assertNotNull(match)
        assertTrue(match!!.progressMeters in 500.0..620.0)
        assertTrue(match.remainingMeters in 500.0..620.0)
    }
}
