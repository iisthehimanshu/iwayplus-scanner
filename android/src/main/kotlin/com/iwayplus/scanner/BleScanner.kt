package com.iwayplus.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Batched BLE advertisement scanning.
 *
 * Filtered to IwayPlus beacons by advertised-name prefix ([IWAYPLUS_NAME_PREFIX]),
 * and nothing else, in hardware (see [scanFilters]) — only on Android 13+,
 * where `ScanFilter` can match a prefix; older devices get every advertiser.
 * That prefix is a property of the hardware rather than of a venue, so this
 * still does not tie the host app to a venue's beacon layout: which IW beacons
 * matter, and what they mean, is decided downstream. Every advertisement that
 * survives is forwarded with its manufacturer data attached.
 */
class BleScanner(
  private val context: Context,
  private val sink: ScannerSink,
) {
  private val handler = Handler(Looper.getMainLooper())
  private val adapter: BluetoothAdapter? =
    (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

  private var config = ScannerConfig()
  private var scanCallback: ScanCallback? = null
  private var timeoutRunnable: Runnable? = null
  private var restartRunnable: Runnable? = null
  private var flushRunnable: Runnable? = null

  // Advertisements are buffered rather than dispatched individually.
  //
  // SCAN_MODE_LOW_LATENCY + CALLBACK_TYPE_ALL_MATCHES + reportDelay(0) delivers
  // ~230 callbacks/sec in a beacon-dense venue, and ScanCallback runs on the
  // main looper — dispatching each packet across the bridge from the UI thread
  // starves the page of frames. Flushing 4x/sec costs nothing in fidelity: the
  // consumer groups by device over a one-second window anyway.
  //
  // Both the callback and the flush runnable run on the main looper, so the
  // buffer needs no synchronisation.
  private val buffer = ArrayList<JSONObject>()
  private var dropped = 0
  private var windowStart = 0L

  var isScanning: Boolean = false
    private set

  fun configure(config: ScannerConfig) {
    this.config = config
    if (isScanning) {
      // Reschedule the periodic work so a new interval takes effect now
      // rather than after the next tick of the old one.
      stopTimers()
      startTimers()
    }
  }

  fun hasPermission(): Boolean {
    val needed = if (Build.VERSION.SDK_INT >= 31) {
      Manifest.permission.BLUETOOTH_SCAN
    } else {
      Manifest.permission.ACCESS_FINE_LOCATION
    }
    return ActivityCompat.checkSelfPermission(context, needed) ==
      PackageManager.PERMISSION_GRANTED
  }

  fun powerState(): String = when {
    adapter == null -> "unsupported"
    !hasPermission() -> "unauthorized"
    adapter.isEnabled -> "on"
    else -> "off"
  }

  fun start() {
    if (isScanning) stop()
    if (!hasPermission()) {
      sink.emitError("PERMISSION_DENIED", "Bluetooth scan permission not granted")
      return
    }
    if (adapter?.isEnabled != true) {
      sink.emitError("BLUETOOTH_OFF", "Bluetooth is not enabled")
      return
    }
    isScanning = true
    windowStart = System.currentTimeMillis()
    startScan()
    startTimers()
  }

  fun stop() {
    val wasScanning = isScanning
    isScanning = false
    stopTimers()
    stopScan()
    if (wasScanning) flush()
    buffer.clear()
    dropped = 0
  }

  @SuppressLint("MissingPermission")
  private fun startScan() {
    val scanner = adapter?.bluetoothLeScanner ?: return

    scanCallback = object : ScanCallback() {
      override fun onScanResult(callbackType: Int, result: ScanResult) {
        if (buffer.size >= config.maxBufferedReadings) {
          // Reported to the consumer rather than swallowed: a non-zero count
          // means the venue is denser than the configured cap, which is a
          // tuning problem, not missing beacons.
          dropped++
          return
        }
        buffer.add(readingOf(result, nameOf(result)))
      }

      override fun onScanFailed(errorCode: Int) {
        sink.emitError("SCAN_FAILED", "BLE scan failed with code $errorCode")
      }
    }

    val settings = ScanSettings.Builder()
      .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
      .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
      .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
      .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
      .setReportDelay(0L)
      .build()

    try {
      scanner.startScan(scanFilters(), settings, scanCallback)
    } catch (error: SecurityException) {
      isScanning = false
      sink.emitError("PERMISSION_DENIED", "Bluetooth scan permission revoked")
    }
  }

  /**
   * Hardware scan filters that pass only IwayPlus beacons.
   *
   * On Android 13+ the local-name AD structure is matched against the
   * [IWAYPLUS_NAME_PREFIX] bytes as a prefix, so non-IwayPlus advertisers are
   * dropped by the controller (or the Bluetooth stack, when the controller has
   * no free filter slots) and never wake this process. The mask clears ASCII's
   * case bit, keeping the match case-insensitive like the positioning engine,
   * which compares on a lower-cased name.
   * Both the complete and the shortened name AD types are matched, since a
   * beacon may advertise either.
   *
   * `ScanFilter` has no prefix match below Android 13 — `setDeviceName` is an
   * exact match — so older devices scan unfiltered.
   */
  private fun scanFilters(): List<ScanFilter> {
    if (Build.VERSION.SDK_INT < 33) return emptyList()
    val prefix = IWAYPLUS_NAME_PREFIX.toByteArray(Charsets.US_ASCII)
    val caseInsensitiveMask = ByteArray(prefix.size) { ASCII_CASE_MASK }
    return listOf(
      ScanRecord.DATA_TYPE_LOCAL_NAME_COMPLETE,
      ScanRecord.DATA_TYPE_LOCAL_NAME_SHORT,
    ).map { type ->
      ScanFilter.Builder()
        .setAdvertisingDataTypeWithData(type, prefix, caseInsensitiveMask)
        .build()
    }
  }

  @SuppressLint("MissingPermission")
  private fun stopScan() {
    val callback = scanCallback ?: return
    scanCallback = null
    try {
      adapter?.bluetoothLeScanner?.stopScan(callback)
    } catch (error: SecurityException) {
      Log.w(TAG, "Unable to stop BLE scan", error)
    }
  }

  private fun nameOf(result: ScanResult): String =
    result.scanRecord?.deviceName
      ?: runCatching { result.device.name }.getOrNull()
      ?: ""

  private fun readingOf(result: ScanResult, name: String): JSONObject {
    val record = result.scanRecord

    var manufacturerHex = ""
    val manufacturerData = record?.manufacturerSpecificData
    if (manufacturerData != null && manufacturerData.size() > 0) {
      manufacturerHex = toHex(manufacturerData.valueAt(0))
    }

    // Epoch millis rather than a formatted string: building a SimpleDateFormat
    // per packet (locale lookup, pattern parse, Calendar allocation) was the
    // single most expensive thing on this callback, and the consumer only
    // parsed it straight back into a timestamp.
    return JSONObject()
      .put("device", result.device.address)
      .put("name", name)
      .put("rssi", result.rssi)
      .put("timestamp", System.currentTimeMillis())
      .put("manufacturerHex", manufacturerHex)
  }

  private fun startTimers() {
    flushRunnable = object : Runnable {
      override fun run() {
        flush()
        if (isScanning) handler.postDelayed(this, config.flushIntervalMs)
      }
    }.also { handler.postDelayed(it, config.flushIntervalMs) }

    // Android throttles a scan left running too long; tearing it down and
    // starting it again keeps results flowing.
    restartRunnable = object : Runnable {
      override fun run() {
        if (!isScanning) return
        stopScan()
        handler.postDelayed({ if (isScanning) startScan() }, RESTART_GAP_MS)
        if (isScanning) handler.postDelayed(this, config.restartIntervalMs)
      }
    }.also { handler.postDelayed(it, config.restartIntervalMs) }

    config.timeoutMs?.let { timeout ->
      timeoutRunnable = Runnable { stop() }.also { handler.postDelayed(it, timeout) }
    }
  }

  private fun stopTimers() {
    flushRunnable?.let { handler.removeCallbacks(it) }
    restartRunnable?.let { handler.removeCallbacks(it) }
    timeoutRunnable?.let { handler.removeCallbacks(it) }
    flushRunnable = null
    restartRunnable = null
    timeoutRunnable = null
  }

  private fun flush() {
    val now = System.currentTimeMillis()
    if (buffer.isEmpty() && dropped == 0) {
      windowStart = now
      return
    }
    val readings = JSONArray()
    buffer.forEach { readings.put(it) }
    val payload = JSONObject()
      .put("readings", readings)
      .put("from", windowStart)
      .put("to", now)
      .put("dropped", dropped)

    buffer.clear()
    dropped = 0
    windowStart = now
    sink.emit("ble", payload.toString())
  }

  private companion object {
    const val TAG = "IwayplusBleScanner"
    const val RESTART_GAP_MS = 100L
    val HEX = "0123456789ABCDEF".toCharArray()

    /** Advertised-name prefix every IwayPlus beacon carries. */
    const val IWAYPLUS_NAME_PREFIX = "IW"

    /** Clears bit 5, which is the only difference between ASCII upper and lower case. */
    const val ASCII_CASE_MASK: Byte = 0xDF.toByte()
  }

  /**
   * `"%02X".format(byte)` builds a Formatter and parses the format string for
   * every byte of every packet. This is the same output from a lookup table.
   */
  private fun toHex(bytes: ByteArray): String {
    val out = CharArray(bytes.size * 2)
    for (i in bytes.indices) {
      val value = bytes[i].toInt() and 0xFF
      out[i * 2] = HEX[value ushr 4]
      out[i * 2 + 1] = HEX[value and 0x0F]
    }
    return String(out)
  }
}

internal fun ScannerSink.emitError(code: String, message: String) {
  emit("error", JSONObject().put("code", code).put("message", message).toString())
}
