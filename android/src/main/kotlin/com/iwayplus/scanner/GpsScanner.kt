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

/**
 * Platform location updates, forwarded unsmoothed.
 *
 * GNSS only. The network provider is deliberately not registered: it bills
 * itself as a location fix but is derived from nearby cell towers and Wi-Fi
 * access points, so indoors it reports the building rather than the user and
 * at an accuracy the consumer cannot act on. Indoor position comes from the
 * beacons, which are the authority once the user is inside, and a coarse
 * network fix competing with them is noise rather than a fallback.
 */
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
    isGpsEnabled() -> "on"
    else -> "off"
  }

  private fun isGpsEnabled(): Boolean = runCatching {
    manager?.isProviderEnabled(LocationManager.GPS_PROVIDER)
  }.getOrNull() ?: false

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

    // Reported as unavailable when GNSS specifically is off, rather than
    // starting against a provider we no longer register and delivering
    // nothing. A caller that is told "on" and then hears silence has no way
    // to tell that apart from being somewhere without sky view.
    if (!isGpsEnabled()) {
      sink.emitError("NO_PROVIDER", "GPS provider is not enabled")
      return
    }

    try {
      manager.requestLocationUpdates(
        LocationManager.GPS_PROVIDER,
        config.gpsIntervalMs,
        config.gpsDistanceFilterM,
        listener,
      )
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
      if (!isGpsEnabled()) {
        sink.emitError("NO_PROVIDER", "GPS provider disabled")
      }
    }
  }

  private companion object {
    const val TAG = "IwayplusGpsScanner"
  }
}
