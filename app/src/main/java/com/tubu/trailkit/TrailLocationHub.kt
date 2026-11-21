package com.tubu.trailkit

import android.location.Location
import java.util.concurrent.CopyOnWriteArraySet

/**
 * In-process fan-out for the single location stream owned by TrailNavigationService.
 * The service is the only class that calls requestLocationUpdates.
 */
interface TrailLocationObserver {
    fun onLocationChanged(location: Location)
    fun onProviderEnabled(provider: String) {}
    fun onProviderDisabled(provider: String) {}
}

object TrailLocationHub {
    private val observers = CopyOnWriteArraySet<TrailLocationObserver>()

    @Volatile
    private var latestLocation: Location? = null

    fun subscribe(observer: TrailLocationObserver) {
        observers.add(observer)
        latestLocation?.let { observer.onLocationChanged(Location(it)) }
    }

    fun unsubscribe(observer: TrailLocationObserver) {
        observers.remove(observer)
    }

    fun publish(location: Location) {
        latestLocation = Location(location)
        observers.forEach { observer ->
            try {
                observer.onLocationChanged(Location(location))
            } catch (_: Exception) {
                // One UI observer must not interrupt delivery to the other consumers.
            }
        }
    }

    fun providerEnabled(provider: String) {
        observers.forEach { observer ->
            try { observer.onProviderEnabled(provider) } catch (_: Exception) {}
        }
    }

    fun providerDisabled(provider: String) {
        observers.forEach { observer ->
            try { observer.onProviderDisabled(provider) } catch (_: Exception) {}
        }
    }
}
