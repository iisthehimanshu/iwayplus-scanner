package com.iwayplus.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import androidx.core.app.ActivityCompat
import org.json.JSONObject

/** Platform location updates, forwarded unsmoothed. */
class GpsScanner(
  private val context: Context,
  private val sink: ScannerSink,
) {
  private val manager: LocationManager? =
    context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

  private var config = ScannerConfig()

  var isScanning: Boolean = false
    private set

  fun configure(config: ScannerConfig) {
    this.config = config
    if (isScanning) {
      stop()
      start()
    }
  }

  fun hasPermission(): Boolean =
    ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED

  fun powerState(): String = when {
    manager == null -> "unsupported"
    !hasPermission() -> "unauthorized"
    isAnyProviderEnabled() -> "on"
    else -> "off"
  }

  private fun isAnyProviderEnabled(): Boolean {
    val gps = runCatching {
      manager?.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }.getOrNull() ?: false
    val network = runCatching {
      manager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }.getOrNull() ?: false
    return gps || network
  }

  @SuppressLint("MissingPermission")
  fun start() {
    if (isScanning) stop()

    if (!hasPermission()) {
      sink.emitError("PERMISSION_DENIED", "Location permission not granted")
      return
    }

    val manager = this.manager
    if (manager == null) {
      sink.emitError("NO_PROVIDER", "No location service on this device")
      return
    }

    val gpsEnabled = runCatching {
      manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }.getOrDefault(false)
    val networkEnabled = runCatching {
      manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }.getOrDefault(false)

    if (!gpsEnabled && !networkEnabled) {
      sink.emitError("NO_PROVIDER", "No location provider enabled")
      return
    }

    try {
      // Indoors the GPS provider often yields nothing at all, so the network
      // provider is registered too when it is available — a coarse fix still
      // anchors the venue and the floor guess, and the consumer weighs fixes
      // by their reported accuracy.
      if (gpsEnabled) {
        manager.requestLocationUpdates(
          LocationManager.GPS_PROVIDER,
          config.gpsIntervalMs,
          config.gpsDistanceFilterM,
          listener,
        )
      }
      if (networkEnabled) {
        manager.requestLocationUpdates(
          LocationManager.NETWORK_PROVIDER,
          config.gpsIntervalMs,
          config.gpsDistanceFilterM,
          listener,
        )
      }
      isScanning = true
    } catch (error: SecurityException) {
      sink.emitError("PERMISSION_DENIED", "Location permission revoked")
    }
  }

  fun stop() {
    isScanning = false
    try {
      manager?.removeUpdates(listener)
    } catch (error: SecurityException) {
      Log.w(TAG, "Unable to remove location updates", error)
    }
  }

  private val listener = object : LocationListener {
    override fun onLocationChanged(location: Location) {
      sink.emit(
        "gps",
        JSONObject()
          .put("latitude", location.latitude)
          .put("longitude", location.longitude)
          .put("accuracy", location.accuracy)
          .put("bearing", if (location.hasBearing()) location.bearing else -1f)
          .put("altitude", location.altitude)
          .put("speed", if (location.hasSpeed()) location.speed else -1f)
          .put("timestamp", location.time)
          .toString(),
      )
    }

    @Deprecated("Required by LocationListener on API < 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) {
      if (!isAnyProviderEnabled()) {
        sink.emitError("NO_PROVIDER", "Location providers disabled")
      }
    }
  }

  private companion object {
    const val TAG = "IwayplusGpsScanner"
  }
}
