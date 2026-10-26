package com.siteroute.planner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/** Fetch a reasonably current position only when the user taps Use Current Location. */
object CurrentGps {
    suspend fun locate(context: Context): GeoPoint {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        require(fine || coarse) { "Allow location permission to find nearby addresses." }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOfNotNull(
            LocationManager.NETWORK_PROVIDER.takeIf { manager.isProviderEnabled(it) },
            LocationManager.GPS_PROVIDER.takeIf { fine && manager.isProviderEnabled(it) }
        )
        require(providers.isNotEmpty()) {
            "Turn on your phone's Location/GPS service, then try again."
        }

        val cached = providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.filter {
            System.currentTimeMillis() - it.time in 0L..120_000L &&
                it.hasAccuracy() && it.accuracy <= 150f
        }.minByOrNull { it.accuracy }
        if (cached != null) return GeoPoint(cached.latitude, cached.longitude)

        val best = AtomicReference<Location?>(null)
        val fresh = withTimeoutOrNull(18_000L) {
            suspendCancellableCoroutine<Location> { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(fix: Location) {
                        if (!fix.latitude.isFinite() || !fix.longitude.isFinite()) return
                        val prior = best.get()
                        if (prior == null || (fix.hasAccuracy() && fix.accuracy <
                                    (prior.accuracy.takeIf { prior.hasAccuracy() } ?: Float.MAX_VALUE))) {
                            best.set(fix)
                        }
                        if ((fix.hasAccuracy() && fix.accuracy <= 125f) ||
                            (fix.provider == LocationManager.GPS_PROVIDER &&
                                fix.hasAccuracy() && fix.accuracy <= 220f)) {
                            manager.removeUpdates(this)
                            if (continuation.isActive) continuation.resume(fix)
                        }
                    }
                }
                try {
                    providers.forEach { provider ->
                        manager.requestLocationUpdates(
                            provider, 0L, 0f, listener, Looper.getMainLooper()
                        )
                    }
                } catch (error: Exception) {
                    manager.removeUpdates(listener)
                    continuation.cancel(error)
                }
                continuation.invokeOnCancellation { manager.removeUpdates(listener) }
            }
        }
        val chosen = fresh ?: best.get()?.takeIf {
            it.hasAccuracy() && it.accuracy <= 1000f &&
                System.currentTimeMillis() - it.time in 0L..120_000L
        }
        require(chosen != null) {
            "Couldn't get a GPS fix. Check Location settings and move to an open area."
        }
        return GeoPoint(chosen.latitude, chosen.longitude)
    }
}
