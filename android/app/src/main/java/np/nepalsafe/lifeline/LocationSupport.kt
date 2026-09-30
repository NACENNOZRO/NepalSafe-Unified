package np.nepalsafe.lifeline

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager

data class AppLocation(val latitude: Double, val longitude: Double, val exact: Boolean, val source: String)

object LocationSupport {
    const val FALLBACK_LAT = 28.2603
    const val FALLBACK_LNG = 85.6797

    fun bestAvailable(context: Context): AppLocation {
        val identity = context.getSharedPreferences(LifelineService.IDENTITY_PREFERENCES, Context.MODE_PRIVATE)
        val hasPermission = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val latest = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            ).mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
                .maxByOrNull(Location::getTime)
            if (latest != null && android.os.SystemClock.elapsedRealtimeNanos() - latest.elapsedRealtimeNanos in 0L..300_000_000_000L) {
                identity.edit()
                    .putFloat("cached_latitude", latest.latitude.toFloat())
                    .putFloat("cached_longitude", latest.longitude.toFloat())
                    .apply()
                return AppLocation(latest.latitude, latest.longitude, true, "app_gps")
            }
        }
        if (identity.contains("cached_latitude") && identity.contains("cached_longitude")) {
            return AppLocation(
                identity.getFloat("cached_latitude", FALLBACK_LAT.toFloat()).toDouble(),
                identity.getFloat("cached_longitude", FALLBACK_LNG.toFloat()).toDouble(),
                false,
                "cached_gps"
            )
        }
        return AppLocation(FALLBACK_LAT, FALLBACK_LNG, false, "langtang_fallback")
    }
}
