package com.iwayplus.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 *
 * Without the network provider, going indoors means the fixes simply stop, or
 * degrade to what a chip sees through a window. Updates are requested at
 * [ScannerConfig.gpsBackoffIntervalMs] instead of [ScannerConfig.gpsIntervalMs]
 * after [ScannerConfig.gpsNoFixTimeoutMs] with no good fix, and at once on a
 * poor one — worse than [ScannerConfig.gpsGoodAccuracyM], or with no accuracy
 * at all. Only a good fix restores the normal interval. GPS is never turned
 * off: whether the user is outdoors again is exactly what a fix tells us.
 *
 * Poor fixes are still forwarded. This decides how often to ask the chip, not
 * what the consumer may use.
 */
class GpsScanner(
  private val context: Context,
  private val sink: ScannerSink,
) {
  private val manager: LocationManager? =
    context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

  private val handler = Handler(Looper.getMainLooper())
  private var config = ScannerConfig()

  /** True while updates are requested at the backoff interval. */
  private var backedOff = false

  /** Fires when no fix has arrived for [ScannerConfig.gpsNoFixTimeoutMs]. */
  private val noFixTimeout = Runnable { setBackedOff(true, REASON_NO_FIX) }

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

    backedOff = false
    if (requestUpdates(config.gpsIntervalMs)) {
      isScanning = true
      armNoFixTimeout()
      emitStatus(REASON_START)
    }
  }

  fun stop() {
    isScanning = false
    backedOff = false
    handler.removeCallbacks(noFixTimeout)
    try {
      manager?.removeUpdates(listener)
    } catch (error: SecurityException) {
      Log.w(TAG, "Unable to remove location updates", error)
    }
  }

  /**
   * Registering again with the same listener replaces the earlier request, so
   * this is also how the interval changes while scanning.
   */
  @SuppressLint("MissingPermission")
  private fun requestUpdates(intervalMs: Long): Boolean {
    val manager = this.manager ?: return false
    return try {
      manager.requestLocationUpdates(
        LocationManager.GPS_PROVIDER,
        intervalMs,
        config.gpsDistanceFilterM,
        listener,
      )
      true
    } catch (error: SecurityException) {
      isScanning = false
      sink.emitError("PERMISSION_DENIED", "Location permission revoked")
      false
    }
  }

  private fun armNoFixTimeout() {
    handler.removeCallbacks(noFixTimeout)
    handler.postDelayed(noFixTimeout, config.gpsNoFixTimeoutMs)
  }

  private fun setBackedOff(value: Boolean, reason: String) {
    if (!isScanning || backedOff == value) return
    backedOff = value
    val interval = if (value) config.gpsBackoffIntervalMs else config.gpsIntervalMs
    Log.i(TAG, "GPS $reason: ${if (value) "backing off" else "restoring"} to ${interval}ms updates")
    if (requestUpdates(interval)) emitStatus(reason)
  }

  /** Accurate enough to say the chip has a real view of the sky. */
  private fun isGoodFix(location: Location): Boolean =
    location.hasAccuracy() && location.accuracy <= config.gpsGoodAccuracyM

  private fun onFix(location: Location) {
    if (isGoodFix(location)) {
      armNoFixTimeout()
      setBackedOff(false, REASON_GOOD_FIX)
    } else {
      // Backed off already or about to be, so the no-fix timer has nothing
      // left to decide until a good fix re-arms it.
      handler.removeCallbacks(noFixTimeout)
      setBackedOff(true, REASON_POOR_FIX)
    }
  }

  /**
   * Which interval is in effect. Emitted on start and on every switch, so a
   * consumer can see the backoff without waiting for a fix that indoors will
   * not come.
   */
  private fun emitStatus(reason: String) {
    sink.emit(
      "gpsStatus",
      JSONObject()
        .put("backedOff", backedOff)
        .put("intervalMs", if (backedOff) config.gpsBackoffIntervalMs else config.gpsIntervalMs)
        .put("reason", reason)
        .put("timestamp", System.currentTimeMillis())
        .toString(),
    )
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
      // After the fix itself, so a consumer reading the `gpsStatus` this may
      // emit already has the fix that caused it.
      onFix(location)
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

    // `gpsStatus.reason`: why the interval is what it is.
    const val REASON_START = "start"
    const val REASON_NO_FIX = "noFix"
    const val REASON_POOR_FIX = "poorFix"
    const val REASON_GOOD_FIX = "goodFix"
  }
}
