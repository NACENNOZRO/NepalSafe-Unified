package com.example.disasterreport

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * LocationHelper
 * Module 4 (client side) -- gets the phone's live GPS coordinates at the
 * moment the photo is taken. This is generally more accurate and reliable
 * than relying on EXIF GPS alone (some phones strip EXIF GPS by default,
 * or the user may deny the camera app location permission while still
 * allowing this app permission), so the backend prioritizes this value
 * (see main.py: lat/lng form fields) over EXIF.
 *
 * Requires in AndroidManifest.xml:
 *   <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
 * And a runtime permission request before calling getCurrentLocation()
 * (see MainActivity.kt for the permission-request flow).
 */
class LocationHelper(private val context: Context) {

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Suspends until a fresh GPS fix is available, or returns null if
     * permission is missing or no location could be obtained (GPS off,
     * indoors with poor signal, etc). Callers MUST handle null -- the
     * backend falls back to EXIF GPS, and ultimately to "unavailable",
     * rather than the app fabricating a location.
     */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): Pair<Double, Double>? {
        if (!hasLocationPermission()) return null

        return suspendCancellableCoroutine { continuation ->
            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        continuation.resume(Pair(location.latitude, location.longitude))
                    } else {
                        continuation.resume(null)
                    }
                }
                .addOnFailureListener {
                    continuation.resume(null)
                }
        }
    }
}
