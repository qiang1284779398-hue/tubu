package com.tubu.trailkit

import android.location.Location

/** Stabilizes display fixes; callers must reject stale/outlier fixes before applying. */
internal object TrailLocationFilter {
    private var filtered: Location? = null
    @Synchronized
    fun apply(raw: Location): Location {
        val previous = filtered
        if (previous == null || raw.accuracy > 100f || raw.time <= previous.time) {
            filtered = Location(raw); return Location(raw)
        }
        val elapsed = ((raw.time - previous.time).coerceAtLeast(0L)) / 1000f
        val distance = previous.distanceTo(raw)
        val speed = if (raw.hasSpeed()) raw.speed.coerceIn(0f, 8f) else if (elapsed > 0f) distance / elapsed else 0f
        val quality = (1f - (raw.accuracy.coerceIn(8f, 65f) - 8f) / 57f).coerceIn(0f, 1f)
        val alpha = if (speed >= 0.8f) 0.72f + quality * 0.22f else 0.16f + quality * 0.28f
        val out = Location(raw).apply {
            latitude = previous.latitude + (raw.latitude - previous.latitude) * alpha
            longitude = previous.longitude + (raw.longitude - previous.longitude) * alpha
            if (raw.hasSpeed()) this.speed = raw.speed
            if (raw.hasBearing()) bearing = raw.bearing
        }
        filtered = out; return Location(out)
    }
    @Synchronized fun reset() { filtered = null }
}
