package com.iwayplus.scanner

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject

/** A start the app asked for that cannot happen; [code] reaches Dart. */
internal class ScanStartException(val code: String, message: String) : Exception(message)

/**
 * The app's own scanning, in [ScanMode] `persistent` — the claimant that is not
 * the page.
 *
 * It holds BLE and GPS in [ScannerHub] for as long as the app wants, on any
 * screen of the app and with the app in recents, kept alive by
 * [ScannerService]. When the task is swiped away it keeps going for the
 * configured `keepAliveAfterSwipe`, and reopening the app before then cancels
 * the countdown. Nothing the page does stops it, and stopping it never stops the
 * page: the two are separate claimants in the hub.
 *
 * The session is persisted, so a service Android restarts after killing the
 * process, or the app reopened after that, carries on where it was — unless the
 * swipe deadline passed in the meantime.
 *
 * Main thread only, like the hub.
 */
internal object AppScanSession : ScanClaimant {
  private const val TAG = "IwayplusAppScan"

  /** The only streams the app may hold; heading and accel are for the live map. */
  val allowedStreams = setOf(ScanStream.BLE, ScanStream.GPS)

  private val handler = Handler(Looper.getMainLooper())
  private var appContext: Context? = null

  /** Plugin instances whose `app_events` channel has a listener. */
  private val listeners = LinkedHashSet<(String) -> Unit>()
  private var sequence = 0L

  /** True while the app's streams are held in the hub. */
  var isActive = false
    private set

  /** Epoch ms at which a swiped-away session ends, or 0 when none is pending. */
  private var deadline = 0L

  private val expire = Runnable { appContext?.let { expireNow(it) } }

  fun addListener(listener: (String) -> Unit) {
    listeners.add(listener)
  }

  fun removeListener(listener: (String) -> Unit) {
    listeners.remove(listener)
  }

  // ── what the app calls ───────────────────────────────────────────────────

  /** Applies a mode from Dart: `{"mode":"webOnly"}` or `{"mode":"persistent",…}`. */
  fun setMode(context: Context, modeJson: String?) {
    remember(context)
    val json = try {
      JSONObject(modeJson ?: "")
    } catch (error: Exception) {
      throw IllegalArgumentException("Scan mode is not a JSON object")
    }
    val store = AppScanStore(context)
    when (json.optString("mode")) {
      AppScanStore.MODE_WEB_ONLY -> {
        store.mode = AppScanStore.MODE_WEB_ONLY
        stop(context)
      }
      AppScanStore.MODE_PERSISTENT -> {
        val keepAlive = if (json.isNull("keepAliveAfterSwipeMs")) {
          AppScanStore.UNTIL_STOPPED
        } else {
          json.optLong("keepAliveAfterSwipeMs", AppScanStore.UNTIL_STOPPED)
        }
        require(keepAlive >= AppScanStore.UNTIL_STOPPED) { "keepAliveAfterSwipeMs is negative" }
        val notification = json.optJSONObject("notification") ?: JSONObject()
        store.mode = AppScanStore.MODE_PERSISTENT
        store.keepAliveMs = keepAlive
        store.title = notification.optString("title", AppScanStore.DEFAULT_TITLE)
        store.text = notification.optString("text", AppScanStore.DEFAULT_TEXT)
        store.channelName = notification.optString("channelName", AppScanStore.DEFAULT_CHANNEL)
        store.iconName = notification.optString("iconName").takeIf { it.isNotEmpty() }
        ScannerService.running?.refreshNotification()
      }
      else -> throw IllegalArgumentException("Unknown scan mode \"${json.optString("mode")}\"")
    }
    Log.i(TAG, "mode ${store.mode}, keepAliveAfterSwipe ${store.keepAliveMs}ms")
  }

  /**
   * Starts, or changes the streams of, the app's session. Must be called while
   * the app is in the foreground: Android only lets a location foreground
   * service start from there.
   */
  fun start(context: Context, streams: Collection<ScanStream>) {
    remember(context)
    val store = AppScanStore(context)
    if (store.mode != AppScanStore.MODE_PERSISTENT) {
      throw ScanStartException(
        "WEB_ONLY_MODE",
        "App scanning needs the persistent scan mode; call setMode first",
      )
    }
    val wanted = streams.toSet()
    if (wanted.isEmpty() || !allowedStreams.containsAll(wanted)) {
      throw ScanStartException("INVALID_STREAMS", "App scanning runs ble and gps only")
    }
    checkServiceDeclared(context)
    if (!hasLocationPermission(context)) {
      // A location foreground service cannot start without it (Android 14+),
      // and neither stream delivers anything without it below that.
      throw ScanStartException("PERMISSION_DENIED", "Location permission not granted")
    }

    store.streams = wanted
    clearDeadline(store)

    if (ScannerService.running == null) {
      try {
        ContextCompat.startForegroundService(
          context,
          android.content.Intent(context, ScannerService::class.java),
        )
      } catch (error: Exception) {
        // ForegroundServiceStartNotAllowedException when called from the
        // background, SecurityException when a manifest entry is missing.
        store.streams = emptySet()
        throw ScanStartException(
          "FOREGROUND_NOT_ALLOWED",
          "Could not start the scanning service: ${error.message}",
        )
      }
    } else {
      ScannerService.running?.refreshNotification()
    }

    val hub = ScannerHub.get(context)
    if (!isActive) sequence = 0
    hub.release(this, allowedStreams - wanted)
    // A stream already held and running is left alone; one held but stopped
    // (Bluetooth was off when it started) is started again.
    hub.claim(this, wanted.filterNot { hub.holds(this, it) && hub.isRunning(it) })
    isActive = true
    Log.i(TAG, "app scanning ${wanted.map { it.wire }}")
  }

  /** Ends the app's session. The page's streams are untouched. */
  fun stop(context: Context) {
    remember(context)
    val store = AppScanStore(context)
    store.streams = emptySet()
    clearDeadline(store)
    if (isActive) {
      ScannerHub.get(context).releaseAll(this)
      isActive = false
      Log.i(TAG, "app scanning stopped")
    }
    sequence = 0
    // A service still on its way up sees no streams and stops itself, after
    // going foreground as Android requires.
    ScannerService.running?.finish()
  }

  /** The persisted session, which is what the app's intent is, not what runs. */
  fun isScanning(context: Context): Boolean = isActive || AppScanStore(context).let {
    it.mode == AppScanStore.MODE_PERSISTENT && it.streams.isNotEmpty() && !it.hasExpired()
  }

  // ── what the platform tells us ────────────────────────────────────────────

  /**
   * An activity attached: the app is open again. Cancels a pending swipe
   * deadline, and resumes a session the process lost while it was gone.
   */
  fun onAppVisible(context: Context) {
    remember(context)
    val store = AppScanStore(context)
    if (store.hasExpired()) {
      Log.i(TAG, "keep-alive ended while the app was away")
      stop(context)
      return
    }
    if (store.deadline > 0) Log.i(TAG, "app reopened; swipe countdown cancelled")
    clearDeadline(store)
    if (!isActive && store.mode == AppScanStore.MODE_PERSISTENT && store.streams.isNotEmpty()) {
      try {
        start(context, store.streams)
      } catch (error: ScanStartException) {
        Log.w(TAG, "could not resume app scanning: ${error.message}")
        store.streams = emptySet()
      }
    }
  }

  /** The task was swiped out of recents. */
  fun onTaskRemoved(context: Context) {
    remember(context)
    // The page went with the task. Its engine may not detach before the
    // process is cached, and its holds must not outlive the WebView.
    ScannerHub.get(context).releaseAllExcept(this)
    if (!isActive) return
    val store = AppScanStore(context)
    val keepAlive = store.keepAliveMs
    if (keepAlive == AppScanStore.UNTIL_STOPPED) {
      Log.i(TAG, "task removed; scanning until the app stops it")
      return
    }
    deadline = System.currentTimeMillis() + keepAlive
    store.deadline = deadline
    schedule(keepAlive)
    Log.i(TAG, "task removed; scanning for another ${keepAlive}ms")
  }

  /**
   * [ScannerService] is running, after a start or a restart Android made on its
   * own. False when there is nothing for it to keep alive.
   */
  fun resumeFromService(context: Context): Boolean {
    remember(context)
    val store = AppScanStore(context)
    if (store.mode != AppScanStore.MODE_PERSISTENT || store.streams.isEmpty()) return false
    if (store.hasExpired()) {
      Log.i(TAG, "keep-alive ended while the process was dead")
      store.streams = emptySet()
      clearDeadline(store)
      return false
    }
    if (!isActive) {
      // A new process: Android restarted the sticky service.
      sequence = 0
      ScannerHub.get(context).claim(this, store.streams)
      isActive = true
      Log.i(TAG, "app scanning restored after a restart: ${store.streams.map { it.wire }}")
    }
    deadline = store.deadline
    if (deadline > 0) schedule(deadline - System.currentTimeMillis())
    return true
  }

  /**
   * The service is gone. Without it the scan would only last until Android
   * reclaims the process, so the holds go too. The persisted session stays:
   * a restart or a reopen picks it up.
   */
  fun onServiceDestroyed(context: Context) {
    handler.removeCallbacks(expire)
    if (!isActive) return
    ScannerHub.get(context).releaseAll(this)
    isActive = false
    Log.i(TAG, "scanning service destroyed; app scanning paused")
  }

  // ── delivery ──────────────────────────────────────────────────────────────

  override fun deliver(type: String, payloadJson: String) {
    // The countdown runs on a Handler, whose clock stops while the device is
    // in deep sleep. Readings wake the CPU, so checking here bounds the
    // overrun to the next reading.
    if (deadline > 0 && System.currentTimeMillis() >= deadline) {
      appContext?.let { expireNow(it) }
      return
    }
    // What to do with readings no app UI is listening for is not decided yet;
    // until it is, they are dropped.
    if (listeners.isEmpty()) return
    sequence += 1
    val envelope = StringBuilder(payloadJson.length + 96)
      .append("{\"v\":").append(IwayplusScannerPlugin.PROTOCOL_VERSION)
      .append(",\"seq\":").append(sequence)
      .append(",\"t\":").append(System.currentTimeMillis())
      .append(",\"type\":\"").append(type)
      .append("\",\"payload\":").append(payloadJson)
      .append('}')
      .toString()
    for (listener in listeners.toList()) listener(envelope)
  }

  // ── internals ─────────────────────────────────────────────────────────────

  private fun remember(context: Context) {
    if (appContext == null) appContext = context.applicationContext
  }

  private fun schedule(delayMs: Long) {
    handler.removeCallbacks(expire)
    handler.postDelayed(expire, delayMs.coerceAtLeast(0L))
  }

  private fun clearDeadline(store: AppScanStore) {
    handler.removeCallbacks(expire)
    deadline = 0L
    store.deadline = 0L
  }

  private fun expireNow(context: Context) {
    Log.i(TAG, "keep-alive after swipe ended")
    stop(context)
  }

  private fun hasLocationPermission(context: Context): Boolean =
    listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
      .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

  /**
   * The service and its permissions are declared by the host app, not merged
   * from this plugin, so hosts that never use app scanning carry no foreground
   * service for Play to ask about. Checked up front so a missing entry is an
   * error the app sees, rather than a service that dies in the background.
   */
  @Suppress("DEPRECATION")
  private fun checkServiceDeclared(context: Context) {
    val packageManager = context.packageManager
    val component = ComponentName(context, ScannerService::class.java)
    val info = try {
      packageManager.getServiceInfo(component, 0)
    } catch (error: PackageManager.NameNotFoundException) {
      throw ScanStartException(
        "SERVICE_NOT_DECLARED",
        "Declare com.iwayplus.scanner.ScannerService in AndroidManifest.xml (see the iwayplus_scanner README)",
      )
    }
    if (Build.VERSION.SDK_INT >= 29 &&
      info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION == 0
    ) {
      throw ScanStartException(
        "SERVICE_NOT_DECLARED",
        "ScannerService needs android:foregroundServiceType=\"location\"",
      )
    }
    val requested = packageManager
      .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
      .requestedPermissions
      ?.toSet()
      .orEmpty()
    val needed = buildList {
      if (Build.VERSION.SDK_INT >= 28) add("android.permission.FOREGROUND_SERVICE")
      if (Build.VERSION.SDK_INT >= 34) add("android.permission.FOREGROUND_SERVICE_LOCATION")
    }
    val missing = needed - requested
    if (missing.isNotEmpty()) {
      throw ScanStartException(
        "SERVICE_NOT_DECLARED",
        "Add ${missing.joinToString()} to AndroidManifest.xml",
      )
    }
  }
}

/** The app's session as it survives the process. */
internal class AppScanStore(context: Context) {
  private val prefs = context.applicationContext
    .getSharedPreferences("com.iwayplus.scanner.app_scan", Context.MODE_PRIVATE)

  var mode: String
    get() = prefs.getString(KEY_MODE, MODE_WEB_ONLY) ?: MODE_WEB_ONLY
    set(value) = put { putString(KEY_MODE, value) }

  /** How long scanning continues after a swipe; [UNTIL_STOPPED] for no limit. */
  var keepAliveMs: Long
    get() = prefs.getLong(KEY_KEEP_ALIVE, UNTIL_STOPPED)
    set(value) = put { putLong(KEY_KEEP_ALIVE, value) }

  var streams: Set<ScanStream>
    get() = prefs.getStringSet(KEY_STREAMS, emptySet()).orEmpty()
      .mapNotNull { ScanStream.byWire(it) }
      .toSet()
    set(value) = put { putStringSet(KEY_STREAMS, value.map { it.wire }.toSet()) }

  /** Epoch ms at which a swiped-away session ends; 0 when none is pending. */
  var deadline: Long
    get() = prefs.getLong(KEY_DEADLINE, 0L)
    set(value) = put { putLong(KEY_DEADLINE, value) }

  var title: String
    get() = prefs.getString(KEY_TITLE, DEFAULT_TITLE) ?: DEFAULT_TITLE
    set(value) = put { putString(KEY_TITLE, value) }

  var text: String
    get() = prefs.getString(KEY_TEXT, DEFAULT_TEXT) ?: DEFAULT_TEXT
    set(value) = put { putString(KEY_TEXT, value) }

  var channelName: String
    get() = prefs.getString(KEY_CHANNEL, DEFAULT_CHANNEL) ?: DEFAULT_CHANNEL
    set(value) = put { putString(KEY_CHANNEL, value) }

  /** A drawable or mipmap name in the host app; null for the app icon. */
  var iconName: String?
    get() = prefs.getString(KEY_ICON, null)
    set(value) = put { if (value == null) remove(KEY_ICON) else putString(KEY_ICON, value) }

  fun hasExpired(): Boolean = deadline > 0 && System.currentTimeMillis() >= deadline

  /**
   * Committed, not applied: these writes are rare, and the one made when the
   * task is swiped away may be the last thing the process does.
   */
  private inline fun put(edit: android.content.SharedPreferences.Editor.() -> Unit) {
    prefs.edit().apply(edit).commit()
  }

  companion object {
    const val MODE_WEB_ONLY = "webOnly"
    const val MODE_PERSISTENT = "persistent"
    const val UNTIL_STOPPED = -1L

    const val DEFAULT_TITLE = "Finding your location"
    const val DEFAULT_TEXT = "Scanning for nearby beacons"
    const val DEFAULT_CHANNEL = "Location scanning"

    private const val KEY_MODE = "mode"
    private const val KEY_KEEP_ALIVE = "keepAliveMs"
    private const val KEY_STREAMS = "streams"
    private const val KEY_DEADLINE = "deadline"
    private const val KEY_TITLE = "title"
    private const val KEY_TEXT = "text"
    private const val KEY_CHANNEL = "channelName"
    private const val KEY_ICON = "iconName"
  }
}
