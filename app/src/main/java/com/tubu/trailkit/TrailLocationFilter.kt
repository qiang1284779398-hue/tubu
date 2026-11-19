package com.tubu.trailkit

import android.location.Location
import kotlin.math.max

/** Adaptive on-screen GPS smoothing. Recording continues to use raw fixes. */
internal object TrailLocationFilter {
    private var filtered: Location? = null

    @Synchronized
    fun apply(raw: Location): Location {
        val previous = filtered
        if (previous == null || raw.accuracy > 120f) {
            filtered = Location(raw)
            return Location(raw)
        }
        val elapsed = ((raw.time - previous.time).coerceAtLeast(0L)) / 1000f
        val distance = previous.distanceTo(raw)
        val speed = if (raw.hasSpeed()) raw.speed else if (elapsed > 0f) distance / elapsed else 0f
        // Bypass smoothing for meaningful movement or large corrections.
        if (elapsed <= 0f || distance > max(18f, speed * max(elapsed, 1f) * 2.5f)) {
            filtered = Location(raw)
            return Location(raw)
        }
        val quality = (1f - (raw.accuracy.coerceIn(5f, 80f) - 5f) / 75f).coerceIn(0f, 1f)
        val alpha = if (speed >= 1.2f) 0.78f + quality * 0.16f else 0.24f + quality * 0.38f
        val out = Location(raw).apply {
            latitude = previous.latitude + (raw.latitude - previous.latitude) * alpha
            longitude = previous.longitude + (raw.longitude - previous.longitude) * alpha
            if (raw.hasSpeed()) this.speed = raw.speed
            if (raw.hasBearing()) bearing = raw.bearing
        }
        filtered = out
        return Location(out)
    }
    @Synchronized
    fun reset() { filtered = null }
}
