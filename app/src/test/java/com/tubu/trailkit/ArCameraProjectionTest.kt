package com.tubu.trailkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ArCameraProjectionTest {
    private val northFacingLevelPose = ArCameraPose(
        imageRight = ArVector3(1.0, 0.0, 0.0),
        imageUp = ArVector3(0.0, 0.0, 1.0),
        forward = ArVector3(0.0, 1.0, 0.0)
    )

    @Test fun groundPointAppearsBelowHorizonByItsPhysicalPerspective() {
        val point = projectArWorldPoint(0.0, 10.0, -1.4, northFacingLevelPose, 500.0, 1000.0, 1000.0)
        assertNotNull(point)
        assertEquals(500f, point!!.x, 0.01f)
        assertEquals(570f, point.y, 0.2f)
    }

    @Test fun eastwardPointProjectsToRightOfCameraCenter() {
        val point = projectArWorldPoint(2.0, 10.0, -1.4, northFacingLevelPose, 500.0, 1000.0, 1000.0)
        assertNotNull(point)
        assertTrue(point!!.x > 500f)
    }

    @Test fun cameraRotationMatrixUsesBackCameraOpticalAxis() {
        val pose = arCameraPoseFromRotationMatrix(
            floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f),
            0
        )
        assertNotNull(pose)
        assertEquals(0.0, pose!!.forward.east, 0.001)
        assertEquals(0.0, pose.forward.north, 0.001)
        assertEquals(-1.0, pose.forward.up, 0.001)
    }

    @Test fun declinationRotatesMagneticNorthIntoTrueNorth() {
        val magneticNorth = ArCameraPose(
            ArVector3(1.0, 0.0, 0.0),
            ArVector3(0.0, 0.0, 1.0),
            ArVector3(0.0, 1.0, 0.0)
        )
        val truePose = applyArMagneticDeclination(magneticNorth, 10.0)
        assertTrue(abs(truePose.headingDegrees - 10.0) < 0.001)
    }

    @Test fun intrinsicFocalLengthAccountsForPortraitFillCenterCrop() {
        val intrinsics = ArCameraIntrinsics(6.0, 4.5, 4.0, true)
        val focal = intrinsics.focalPixels(1080.0, 1920.0)
        assertTrue(abs(focal - (4.0 / 4.5 * 1920.0)) < 0.01)
    }

    @Test fun routeSamplesInterpolateElevationAndMetricSpacing() {
        val route = prepareRoute(listOf(0.0 to 0.0, 0.0 to 0.001))
        val samples = sampleArRouteAhead(route, listOf(100.0, 120.0), 20.0, 40.0, 10.0)
        assertTrue(samples.size >= 4)
        assertEquals(20.0, samples.first().progressMeters, 0.001)
        assertTrue(samples.zipWithNext().all { it.second.progressMeters > it.first.progressMeters })
        assertTrue(samples.first().elevationMeters!! > 100.0)
        assertTrue(samples.last().elevationMeters!! > samples.first().elevationMeters!!)
    }

    @Test fun poseFilterSmoothsHeadingAcrossNorthWithoutA180DegreeJump() {
        val filter = ArCameraPoseFilter(response = 0.25)
        fun pose(headingDegrees: Double): ArCameraPose {
            val angle = Math.toRadians(headingDegrees)
            return ArCameraPose(
                imageRight = ArVector3(kotlin.math.cos(angle), -kotlin.math.sin(angle), 0.0),
                imageUp = ArVector3(0.0, 0.0, 1.0),
                forward = ArVector3(kotlin.math.sin(angle), kotlin.math.cos(angle), 0.0)
            )
        }

        filter.apply(pose(359.0))
        val smoothed = filter.apply(pose(1.0))
        assertTrue(kotlin.math.abs(signedTurnAngle(359.0, smoothed.headingDegrees)) < 1.0)
        assertTrue(
            kotlin.math.min(smoothed.headingDegrees, 360.0 - smoothed.headingDegrees) < 1.0
        )
    }

    @Test fun poseFilterReducesSuddenRotationAndKeepsCameraBasisOrthogonal() {
        val filter = ArCameraPoseFilter(response = 0.25)
        fun pose(headingDegrees: Double): ArCameraPose {
            val angle = Math.toRadians(headingDegrees)
            return ArCameraPose(
                imageRight = ArVector3(kotlin.math.cos(angle), -kotlin.math.sin(angle), 0.0),
                imageUp = ArVector3(0.0, 0.0, 1.0),
                forward = ArVector3(kotlin.math.sin(angle), kotlin.math.cos(angle), 0.0)
            )
        }

        filter.apply(pose(0.0))
        val smoothed = filter.apply(pose(80.0))
        assertTrue(smoothed.headingDegrees in 15.0..25.0)
        assertTrue(kotlin.math.abs(smoothed.imageRight.dot(smoothed.forward)) < 1e-6)
        assertTrue(kotlin.math.abs(smoothed.imageRight.dot(smoothed.imageUp)) < 1e-6)
        assertTrue(kotlin.math.abs(smoothed.forward.dot(smoothed.imageUp)) < 1e-6)
    }

    @Test fun terrariumBilinearInterpolationUsesAllFourPixels() {
        assertEquals(15.0, ArTerrainDem.bilinear(0.0, 10.0, 20.0, 30.0, 0.5, 0.5), 1e-9)
        assertEquals(10.0, ArTerrainDem.bilinear(0.0, 10.0, 20.0, 30.0, 1.0, 0.0), 1e-9)
    }

    @Test fun missingDemSamplesInterpolateNeighboursThenUseGpxFallback() {
        val resolved = resolveArElevationSamples(
            listOf(100.0, null, null, 130.0, null),
            listOf(90.0, 91.0, 92.0, 93.0, 94.0)
        )
        assertEquals(110.0, resolved[1]!!, 1e-9)
        assertEquals(120.0, resolved[2]!!, 1e-9)
        assertEquals(94.0, resolved[4]!!, 1e-9)
    }

    @Test fun adaptiveSamplingIsNearDenseAndFarSparseOnStraightRoute() {
        val route = prepareRoute(listOf(0.0 to 0.0, 0.0 to 0.004))
        val samples = sampleArRouteAheadAdaptive(route, emptyList(), 0.0, 120.0)
        val nearGaps = samples.zipWithNext().filter { it.first.progressMeters < 28.0 }
            .map { it.second.progressMeters - it.first.progressMeters }
        val farGaps = samples.zipWithNext().filter { it.first.progressMeters > 40.0 }
            .map { it.second.progressMeters - it.first.progressMeters }
        assertTrue(nearGaps.isNotEmpty() && nearGaps.all { it <= 2.01 })
        assertTrue(farGaps.isNotEmpty() && farGaps.all { it <= 3.51 })
    }

    @Test fun adaptiveSamplingDensifiesAroundSharpRouteBend() {
        val route = prepareRoute(listOf(0.0 to 0.0, 0.0 to 0.001, 0.001 to 0.001))
        // Place the bend within the near 30 m zone, where the specification
        // requires 0.5–1.0 m spacing around sharp turns.
        val samples = sampleArRouteAheadAdaptive(route, emptyList(), 82.0, 60.0)
        val aroundCorner = samples.zipWithNext().filter {
            it.first.progressMeters in 106.0..114.0
        }.map { it.second.progressMeters - it.first.progressMeters }
        assertTrue("corner should be sampled densely: $aroundCorner", aroundCorner.any { it <= 1.0 })
    }

    @Test fun nearPlaneClipCreatesIntersectionVerticesInsteadOfDroppingTriangle() {
        val clipped = clipArPolygonToNearPlane(
            listOf(
                ArCameraSpaceVertex(-1.0, -1.0, 0.2),
                ArCameraSpaceVertex(1.0, -1.0, 2.0),
                ArCameraSpaceVertex(0.0, 1.0, 2.0)
            ),
            0.5
        )
        assertEquals(4, clipped.size)
        assertTrue(clipped.all { it.z >= 0.5 })
        assertTrue(clipped.count { kotlin.math.abs(it.z - 0.5) < 1e-9 } == 2)
    }

    @Test fun gpsCourseFusionRequiresStableAlignedNonTurningPhone() {
        val heading = 10.0
        assertEquals(heading, fuseArGpsCourse(heading, 40.0, 0.8, 5, true, false), 1e-9)
        assertEquals(heading, fuseArGpsCourse(heading, 40.0, 1.2, 2, true, false), 1e-9)
        assertEquals(heading, fuseArGpsCourse(heading, 40.0, 1.2, 3, false, false), 1e-9)
        assertEquals(heading, fuseArGpsCourse(heading, 40.0, 1.2, 3, true, true), 1e-9)
        assertTrue(fuseArGpsCourse(10.0, 20.0, 1.2, 3, true, false) > 10.0)
    }

    @Test fun routeProjectionKeepsRealCameraOffsetFromSnappedAnchor() {
        val sample = ArRouteSample(0.00009, 0.0, 10.0, 100.0)
        val cameraLongitude = 0.000018
        val point = projectArRouteSampleFromAnchor(
            0.0, 0.0, 0.0, cameraLongitude, sample,
            100.0, 1.45, northFacingLevelPose, 500.0, 1000.0, 1000.0
        )
        assertNotNull(point)
        assertTrue(point!!.x < 500f)
    }

    @Test fun isolatedGpsSpikeIsRemovedWithoutSmoothingRealRouteBend() {
        val points = listOf(
            0.0 to 0.0,
            0.0 to 0.0001,
            0.001 to 0.001,
            0.0 to 0.00011,
            0.0 to 0.0002
        )
        val cleaned = cleanArRoutePoints(points)
        assertTrue(cleaned.size < points.size)
        assertEquals(points.first(), cleaned.first())
        assertEquals(points.last(), cleaned.last())
    }
}
