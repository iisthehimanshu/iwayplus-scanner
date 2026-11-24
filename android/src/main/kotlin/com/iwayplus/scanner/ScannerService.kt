package com.iwayplus.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the process, and with it the app's scanning, alive outside the app's
 * UI: on other screens, in recents, and after the task is swiped away.
 *
 * Runs only while [AppScanSession] holds streams. The page's scanning never
 * starts it, so in `webOnly` mode it never runs.
 *
 * The host app declares it (see the README), not this plugin's manifest: a
 * merged declaration would make every host answer Play's foreground-service
 * questions, including the ones that never scan outside the map.
 */
class ScannerService : Service() {

  override fun onCreate() {
    super.onCreate()
    running = this
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // Foreground first, whatever happens next: a service started with
    // startForegroundService that stops without calling it crashes the app.
    if (!goForeground()) {
      stopSelf()
      return START_NOT_STICKY
    }
    if (!AppScanSession.resumeFromService(this)) {
      finish()
      return START_NOT_STICKY
    }
    // Sticky, so a process Android kills is brought back and the session
    // resumes. On Android 14+ that restart happens from the background, which a
    // location service is only allowed with background location; without it
    // goForeground fails and the session waits for the app to reopen.
    return START_STICKY
  }

  /** Called because the service is declared without `android:stopWithTask`. */
  override fun onTaskRemoved(rootIntent: Intent?) {
    AppScanSession.onTaskRemoved(this)
    super.onTaskRemoved(rootIntent)
  }

  override fun onDestroy() {
    if (running === this) running = null
    AppScanSession.onServiceDestroyed(this)
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  internal fun finish() {
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  /** Shows a changed title or text without restarting anything. */
  @SuppressLint("MissingPermission")
  internal fun refreshNotification() {
    if (Build.VERSION.SDK_INT >= 33 &&
      ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      return
    }
    ensureChannel()
    NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
  }

  private fun goForeground(): Boolean = try {
    ensureChannel()
    ServiceCompat.startForeground(
      this,
      NOTIFICATION_ID,
      buildNotification(),
      if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
    )
    true
  } catch (error: Exception) {
    // SecurityException: location not granted, or not eligible to start from
    // the background. ForegroundServiceStartNotAllowedException: the latter,
    // reported differently.
    Log.w(TAG, "Could not run the scanning service in the foreground", error)
    false
  }

  private fun ensureChannel() {
    NotificationManagerCompat.from(this).createNotificationChannel(
      NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
        .setName(AppScanStore(this).channelName)
        .setShowBadge(false)
        .build(),
    )
  }

  private fun buildNotification(): Notification {
    val store = AppScanStore(this)
    val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
      PendingIntent.getActivity(
        this,
        0,
        it,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    }
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle(store.title)
      .setContentText(store.text)
      .setSmallIcon(iconFor(store.iconName))
      .setOngoing(true)
      .setSilent(true)
      .setOnlyAlertOnce(true)
      .setShowWhen(false)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      // Android 12+ otherwise holds a foreground service's notification back
      // for up to 10 seconds; the user should see at once that scanning runs.
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .setContentIntent(open)
      .build()
  }

  @SuppressLint("DiscouragedApi")
  private fun iconFor(name: String?): Int {
    if (name != null) {
      for (type in listOf("drawable", "mipmap")) {
        val id = resources.getIdentifier(name, type, packageName)
        if (id != 0) return id
      }
      Log.w(TAG, "No drawable or mipmap named \"$name\"; using the app icon")
    }
    return applicationInfo.icon
  }

  internal companion object {
    private const val TAG = "IwayplusScannerService"
    private const val CHANNEL_ID = "iwayplus_scanner"
    private const val NOTIFICATION_ID = 0x1A9

    /** The live instance, if any. Main thread only. */
    var running: ScannerService? = null
      private set
  }
}
