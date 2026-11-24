package com.tubu.trailkit

/** Bounded, in-memory diagnostics for validating terrain AR quality without storing location history. */
internal object TerrainArDiagnostics {
    data class Sample(
        val timestampMillis: Long,
        val gpsAccuracyMeters: Double?,
        val gpsAgeMillis: Long,
        val snapDistanceMeters: Double?,
        val demSampleCount: Int,
        val elevationFallbackRatio: Double,
        val calibratedIntrinsics: Boolean,
        val occludedSampleRatio: Double,
        val frameDurationMillis: Double?
    )

    private const val MAX_SAMPLES = 240
    private val samples = ArrayDeque<Sample>()
    private var latest = Sample(
        System.currentTimeMillis(), null, Long.MAX_VALUE, null, 0, 1.0, false, 0.0, null
    )

    @Synchronized
    fun recordState(
        gpsAccuracyMeters: Double?,
        gpsAgeMillis: Long,
        snapDistanceMeters: Double?,
        demSampleCount: Int,
        elevationFallbackRatio: Double,
        calibratedIntrinsics: Boolean,
        occludedSampleRatio: Double
    ) {
        latest = latest.copy(
            timestampMillis = System.currentTimeMillis(),
            gpsAccuracyMeters = gpsAccuracyMeters,
            gpsAgeMillis = gpsAgeMillis,
            snapDistanceMeters = snapDistanceMeters,
            demSampleCount = demSampleCount.coerceAtLeast(0),
            elevationFallbackRatio = elevationFallbackRatio.coerceIn(0.0, 1.0),
            calibratedIntrinsics = calibratedIntrinsics,
            occludedSampleRatio = occludedSampleRatio.coerceIn(0.0, 1.0)
        )
    }

    @Synchronized
    fun recordFrame(frameDurationMillis: Double) {
        latest = latest.copy(
            timestampMillis = System.currentTimeMillis(),
            frameDurationMillis = frameDurationMillis.takeIf { it.isFinite() && it >= 0.0 }
        )
        samples.addLast(latest)
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<Sample> = samples.toList()
}
