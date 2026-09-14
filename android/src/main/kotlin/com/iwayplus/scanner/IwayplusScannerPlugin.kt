package com.iwayplus.scanner

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import org.json.JSONObject

/**
 * The Flutter surface of the scanner.
 *
 * The Flutter counterpart of react-native-scanner's `IwayplusScannerModule`:
 * the scanner cores in this directory are shared with that package verbatim,
 * and this class only adds the envelope and the channels. Every event crosses
 * as a JSON string wrapped in an envelope with a protocol version and a
 * monotonic sequence number — the widget forwards those strings into the
 * WebView without parsing them, and the page uses sequence gaps to notice a
 * stalled bridge.
 */
class IwayplusScannerPlugin :
  FlutterPlugin,
  MethodChannel.MethodCallHandler,
  EventChannel.StreamHandler,
  ActivityAware,
  PluginRegistry.RequestPermissionsResultListener {

  private lateinit var methods: MethodChannel
  private lateinit var events: EventChannel
  private val mainHandler = Handler(Looper.getMainLooper())

  private var ble: BleScanner? = null
  private var gps: GpsScanner? = null
  private var heading: HeadingScanner? = null

  private var eventSink: EventChannel.EventSink? = null
  private var sequence = 0L
  private var announced = false

  private var activityBinding: ActivityPluginBinding? = null
  private var pendingPermissionResult: MethodChannel.Result? = null

  private val sink = ScannerSink { type, payloadJson -> send(type, payloadJson) }

  override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    val context = binding.applicationContext
    ble = BleScanner(context, sink)
    gps = GpsScanner(context, sink)
    heading = HeadingScanner(context, sink)

    methods = MethodChannel(binding.binaryMessenger, METHOD_CHANNEL)
    methods.setMethodCallHandler(this)
    events = EventChannel(binding.binaryMessenger, EVENT_CHANNEL)
    events.setStreamHandler(this)
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    stopAllStreams()
    methods.setMethodCallHandler(null)
    events.setStreamHandler(null)
    ble = null
    gps = null
    heading = null
  }

  override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
    val ble = ble
    val gps = gps
    val heading = heading
    if (ble == null || gps == null || heading == null) {
      result.error(ERROR_CODE, "Scanner is detached from the engine", null)
      return
    }
    try {
      when (call.method) {
        "configure" -> {
          val config = ScannerConfig.fromJson(call.arguments as? String)
          ble.configure(config)
          gps.configure(config)
          heading.configure(config)
          result.success(null)
        }
        "startBle" -> { announceOnce(); ble.start(); result.success(null) }
        "stopBle" -> { ble.stop(); result.success(null) }
        "startGps" -> { announceOnce(); gps.start(); result.success(null) }
        "stopGps" -> { gps.stop(); result.success(null) }
        "startHeading" -> { announceOnce(); heading.start(); result.success(null) }
        "stopHeading" -> { heading.stop(); result.success(null) }
        "stopAll" -> {
          stopAllStreams()
          // The page reads a sequence reset as "this is a fresh session", which
          // is what a full stop is.
          sequence = 0
          announced = false
          result.success(null)
        }
        "getState" -> {
          val state = stateJson()
          send("adapter", state)
          result.success(state)
        }
        "requestPermissions" -> requestPermissions(result)
        else -> result.notImplemented()
      }
    } catch (error: Exception) {
      result.error(ERROR_CODE, error.message, null)
    }
  }

  // ── events ────────────────────────────────────────────────────────────────

  override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
    eventSink = events
  }

  override fun onCancel(arguments: Any?) {
    eventSink = null
  }

  /**
   * Builds the envelope by string concatenation rather than parsing the payload
   * and re-serialising it: this runs on the BLE flush path several times a
   * second with tens of readings, and the payload is valid JSON already.
   */
  private fun send(type: String, payloadJson: String) {
    sequence += 1
    val envelope = StringBuilder(payloadJson.length + 96)
      .append("{\"v\":").append(PROTOCOL_VERSION)
      .append(",\"seq\":").append(sequence)
      .append(",\"t\":").append(System.currentTimeMillis())
      .append(",\"type\":\"").append(type)
      .append("\",\"payload\":").append(payloadJson)
      .append('}')
      .toString()
    // EventSink must be called on the platform thread. The cores already emit
    // there; this only guards a future caller that does not.
    if (Looper.myLooper() == Looper.getMainLooper()) {
      eventSink?.success(envelope)
    } else {
      mainHandler.post { eventSink?.success(envelope) }
    }
  }

  private fun announceOnce() {
    if (announced) return
    announced = true
    send(
      "hello",
      JSONObject()
        .put("moduleVersion", MODULE_VERSION)
        .put("platform", "android")
        .put("osVersion", Build.VERSION.RELEASE ?: "unknown")
        .toString(),
    )
    send("adapter", stateJson())
  }

  private fun stopAllStreams() {
    ble?.stop()
    gps?.stop()
    heading?.stop()
  }

  private fun stateJson(): String = JSONObject()
    .put("bluetooth", ble?.powerState() ?: "unknown")
    .put("location", gps?.powerState() ?: "unknown")
    .put(
      "permissions",
      JSONObject()
        .put("bluetooth", ble?.hasPermission() ?: false)
        .put("location", gps?.hasPermission() ?: false),
    )
    .put(
      "scanning",
      JSONObject()
        .put("ble", ble?.isScanning ?: false)
        .put("gps", gps?.isScanning ?: false)
        .put("heading", heading?.isScanning ?: false),
    )
    .toString()

  // ── permissions ───────────────────────────────────────────────────────────

  /**
   * BLUETOOTH_SCAN / BLUETOOTH_CONNECT are runtime permissions from API 31;
   * below that, BLE scanning is gated on location permission alone.
   */
  private fun wantedPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31) {
      arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
      )
    } else {
      arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

  private fun requestPermissions(result: MethodChannel.Result) {
    val activity: Activity? = activityBinding?.activity
    if (activity == null) {
      result.error(ERROR_CODE, "Permissions need a foreground activity", null)
      return
    }
    val wanted = wantedPermissions()
    val missing = wanted.filter {
      ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
    }
    if (missing.isEmpty()) {
      result.success(true)
      return
    }
    if (pendingPermissionResult != null) {
      result.error(ERROR_CODE, "A permission request is already in progress", null)
      return
    }
    pendingPermissionResult = result
    activity.requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST_CODE)
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray,
  ): Boolean {
    if (requestCode != PERMISSION_REQUEST_CODE) return false
    val result = pendingPermissionResult ?: return true
    pendingPermissionResult = null
    val activity = activityBinding?.activity
    val granted = activity != null && wantedPermissions().all {
      ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
    }
    result.success(granted)
    return true
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    activityBinding = binding
    binding.addRequestPermissionsResultListener(this)
  }

  override fun onDetachedFromActivityForConfigChanges() = onDetachedFromActivity()

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) =
    onAttachedToActivity(binding)

  override fun onDetachedFromActivity() {
    activityBinding?.removeRequestPermissionsResultListener(this)
    activityBinding = null
  }

  companion object {
    const val METHOD_CHANNEL = "iwayplus_scanner"
    const val EVENT_CHANNEL = "iwayplus_scanner/events"
    const val MODULE_VERSION = "0.1.0"
    const val PROTOCOL_VERSION = 1
    private const val ERROR_CODE = "IWAYPLUS_SCANNER_ERROR"
    private const val PERMISSION_REQUEST_CODE = 0x1A7
  }
}
