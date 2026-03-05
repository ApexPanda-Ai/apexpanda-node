package com.apexpanda.node

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 获取定位：优先 FusedLocationProvider，无 GMS 时回退到系统 LocationManager
 * getLastKnownLocation 常为 null，需主动 requestSingleUpdate 等待一次定位
 */
class LocationHandler(private val context: Context) {

    private val fusedClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    private val locationManager: LocationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    suspend fun get(params: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext mapOf("error" to "PERMISSION_DENIED: 需要定位权限，请点击「请求相机/定位/通知权限」并允许")
        }
        val loc = getLocationWithRetry()
        if (loc != null) {
            mapOf(
                "ok" to true,
                "lat" to loc.latitude,
                "lon" to loc.longitude,
                "accuracy" to (loc.accuracy?.toDouble() ?: 0.0),
                "altitude" to (loc.altitude ?: 0.0)
            )
        } else {
            val hint = if (!isLocationEnabled()) {
                "定位服务未开启。请到 设置→定位 开启，并选择「高精度」或「使用 GPS 与网络」"
            } else {
                "无法获取定位。若在室内请移至窗边或室外；或在 设置→定位→定位模式 中切换为「高精度」"
            }
            mapOf("error" to hint)
        }
    }

    private fun isLocationEnabled(): Boolean = try {
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (_: Exception) { false }

    private suspend fun getLocationWithRetry(): Location? {
        var loc = try { fusedClient.lastLocation.await() } catch (e: Exception) {
            Log.w(TAG, "FusedLocationProvider failed", e)
            null
        }
        if (loc != null) return loc

        loc = getLastFromManager()
        if (loc != null) return loc

        return requestFreshLocation()
    }

    private fun getLastFromManager(): Location? {
        val providers = locationManager.getProviders(true) ?: emptyList()
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { it in providers }) {
            try {
                locationManager.getLastKnownLocation(provider)?.let { return it }
            } catch (_: SecurityException) { }
        }
        return null
    }

    private suspend fun requestFreshLocation(): Location? = suspendCancellableCoroutine { cont ->
        val providers = locationManager.getProviders(true) ?: emptyList()
        val provider = when {
            LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
            LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
            else -> {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
        }
        val mainHandler = Handler(Looper.getMainLooper())
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                try { locationManager.removeUpdates(this) } catch (_: SecurityException) { }
                if (!cont.isCompleted) cont.resume(location)
            }
        }
        try {
            locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.w(TAG, "requestSingleUpdate permission", e)
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        val timeoutRunnable = Runnable {
            try { locationManager.removeUpdates(listener) } catch (_: SecurityException) { }
            if (!cont.isCompleted) cont.resume(null)
        }
        mainHandler.postDelayed(timeoutRunnable, 12_000)
        cont.invokeOnCancellation {
            mainHandler.removeCallbacks(timeoutRunnable)
            try { locationManager.removeUpdates(listener) } catch (_: SecurityException) { }
        }
    }

    companion object {
        private const val TAG = "LocationHandler"
    }
}
