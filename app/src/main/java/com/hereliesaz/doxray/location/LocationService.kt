package com.hereliesaz.doxray.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Best-effort wrapper around [FusedLocationProviderClient.getLastLocation].
 * Returns null when:
 *   - neither COARSE nor FINE permission is granted
 *   - the device has no cached fix
 *   - Play Services throws or is missing
 *
 * Never throws. Safe to call from any thread.
 */
class LocationService(private val context: Context) {
    private val fused = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    suspend fun getLastLocation(): Location? {
        if (!hasAnyLocationPermission()) return null
        return try {
            suspendCancellableCoroutine { cont ->
                fused.lastLocation
                    .addOnSuccessListener { loc -> cont.resume(loc) { _, _, _ -> } }
                    .addOnFailureListener { cont.resume(null) { _, _, _ -> } }
            }
        } catch (e: Exception) { null }
    }

    private fun hasAnyLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
