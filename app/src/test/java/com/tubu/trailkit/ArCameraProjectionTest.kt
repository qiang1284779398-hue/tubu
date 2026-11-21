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
}
