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
        assertEquals("等待罗盘",turnGuidance(Float.NaN,100f))
        assertEquals("方向基本正确",turnGuidance(90f,100f))
        assertEquals("向右修正 90°",turnGuidance(0f,90f))
        assertEquals("向左修正 90°",turnGuidance(180f,90f))
    }
    @Test fun signedTurnAngleHandlesLeftRightAndNorthWraparound() {
        assertEquals(90.0,signedTurnAngle(0.0,90.0),0.001)
        assertEquals(-90.0,signedTurnAngle(0.0,270.0),0.001)
        assertEquals(20.0,signedTurnAngle(350.0,10.0),0.001)
    }
    @Test fun waypointBearingUsesTrueNorthClockwiseDegrees() {
        assertTrue(kotlin.math.abs(bearingToPoint(0.0 to 0.0,0.0 to 1.0)-90.0)<0.01)
        val north=bearingToPoint(0.0 to 0.0,1.0 to 0.0)
        assertTrue(north<0.01 || north>359.99)
    }
    @Test fun reversedRouteCanBeMatchedFromOppositeStart() {
        val route=listOf(0.0 to 0.0,0.0 to 0.01,0.0 to 0.02)
        val reversed=route.asReversed()
        val match=matchRoute(reversed,0.0 to 0.015)
        assertNotNull(match)
        assertTrue(match!!.progressMeters in 500.0..620.0)
        assertTrue(match.remainingMeters in 1660.0..1680.0)
    }
    @Test fun preparedRouteMatchesSameProgressWithoutRebuildingIndex() {
        val route=listOf(31.0 to 121.0,31.001 to 121.002,31.003 to 121.004)
        val fix=31.0015 to 121.003
        val direct=matchRoute(route,fix) ?: error("direct match missing")
        val prepared=matchRoute(prepareRoute(route),fix) ?: error("prepared match missing")
        assertEquals(direct.progressMeters,prepared.progressMeters,0.001)
        assertEquals(direct.distanceMeters,prepared.distanceMeters,0.001)
    }
    @Test fun utmConversionMatchesKnownWgs84Coordinate() {
        val utm=toUtm(48.8583,2.2945)
        assertNotNull(utm)
        assertEquals(31,utm!!.zone)
        assertEquals('N',utm.hemisphere)
        assertTrue(kotlin.math.abs(utm.easting-448251.0)<100.0)
        assertTrue(kotlin.math.abs(utm.northing-5411932.0)<100.0)
    }
    @Test fun utmRejectsLatitudesOutsideItsCoverage() {
        assertEquals(null,toUtm(85.0,0.0))
    }
    @Test fun utmEquatorAndPrimeMeridianUsesZone31() {
        val utm=toUtm(0.0,0.0)
        assertNotNull(utm)
        assertEquals(31,utm!!.zone)
        assertEquals('N',utm.hemisphere)
        assertTrue(kotlin.math.abs(utm.easting-166021.0)<100.0)
        assertTrue(kotlin.math.abs(utm.northing)<1.0)
    }
    @Test fun dmsCoordinatesUseCorrectHemisphereAndPrecision() {
        assertEquals("31°13′30.00″N",formatDms(31.225,true))
        assertEquals("121°28′30.00″W",formatDms(-121.475,false))
    }

    @Test fun continuousMatcherDoesNotJumpToNearbyLaterSwitchback() {
        val route = prepareRoute(listOf(
            0.0 to 0.0,
            0.0 to 0.001,
            0.000135 to 0.001,
            0.000135 to 0.0
        ))
        val previous = matchRoute(route, 0.0 to 0.0005) ?: error("initial match missing")
        val next = matchRouteContinuously(
            route,
            0.000135 to 0.0005,
            previous,
            maxForwardProgressMeters = 30.0
        ) ?: error("continuous match missing")
        assertEquals(0, next.segmentIndex)
        assertTrue(next.progressMeters < previous.progressMeters + 30.0)
        assertTrue(next.distanceMeters in 13.0..17.0)
    }

    @Test fun continuousMatcherCanAdvanceWhenAllowedDistanceIncreases() {
        val route = prepareRoute(listOf(0.0 to 0.0, 0.0 to 0.001, 0.001 to 0.001))
        val previous = matchRoute(route, 0.0 to 0.0004) ?: error("initial match missing")
        val next = matchRouteContinuously(route, 0.0004 to 0.001, previous, 100.0)
            ?: error("forward match missing")
        assertTrue(next.progressMeters > previous.progressMeters)
        assertTrue(next.progressMeters < previous.progressMeters + 100.0)
    }
}
