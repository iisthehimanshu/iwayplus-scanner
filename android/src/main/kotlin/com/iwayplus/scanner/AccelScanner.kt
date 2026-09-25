package com.iwayplus.scanner

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Raw accelerometer, gravity included, for the page's step detector.
 *
 * In a WebView the page's only alternative is `devicemotion`, and Chromium
 * serves that event by running the accelerometer, the linear-acceleration
 * sensor and the gyroscope together at ~60Hz — all three, for as long as a
 * listener is attached, even though the step detector reads one field. This
 * runs the accelerometer alone, at a rate the page chooses.
 *
 * Samples are batched rather than emitted individually: every emission is a
 * bridge crossing, and the step detector only needs them in order, not one at
 * a time. The batch window is also handed to the sensor as its report latency,
 * so a device with a sensor FIFO can hold the samples in hardware instead of
 * waking the CPU for each one.
 */
class AccelScanner(
  context: Context,
  private val sink: ScannerSink,
) {
  private val handler = Handler(Looper.getMainLooper())
  private val sensorManager: SensorManager? =
    context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
  private val accelerometer: Sensor? =
    sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

  private var config = ScannerConfig()
  private var flushRunnable: Runnable? = null

  /** Sensor timestamp (ns since boot) of the last sample kept. */
  private var lastKeptNanos = 0L

  // Both the sensor callback (registered without a handler, so on the main
  // looper) and the flush runnable run on the main thread; no locking needed.
  private val buffer = ArrayList<JSONArray>()

  var isScanning: Boolean = false
    private set

  fun configure(config: ScannerConfig) {
    val rateChanged = config.accelIntervalMs != this.config.accelIntervalMs ||
      config.accelFlushIntervalMs != this.config.accelFlushIntervalMs
    this.config = config
    if (isScanning && rateChanged) {
      // The sampling period is fixed at registration, so a new rate needs a
      // fresh registration rather than just a rescheduled flush.
      stop()
      start()
    }
  }

  fun powerState(): String = if (accelerometer == null) "unsupported" else "on"

  fun start() {
    if (isScanning) return
    val sensor = accelerometer
    if (sensor == null || sensorManager == null) {
      sink.emitError("NO_ACCELEROMETER", "No accelerometer on this device")
      return
    }
    lastKeptNanos = 0L
    isScanning = sensorManager.registerListener(
      listener,
      sensor,
      (config.accelIntervalMs * 1_000L).toInt(),
      (config.accelFlushIntervalMs * 1_000L).toInt(),
    )
    if (!isScanning) {
      sink.emitError("ACCEL_FAILED", "Accelerometer could not be started")
      return
    }
    flushRunnable = object : Runnable {
      override fun run() {
        flush()
        if (isScanning) handler.postDelayed(this, config.accelFlushIntervalMs)
      }
    }.also { handler.postDelayed(it, config.accelFlushIntervalMs) }
  }

  fun stop() {
    if (!isScanning) return
    isScanning = false
    sensorManager?.unregisterListener(listener)
    flushRunnable?.let { handler.removeCallbacks(it) }
    flushRunnable = null
    // Dropped, not flushed: after a stop nobody is waiting on these, and a
    // late batch would land in whatever the page does next.
    buffer.clear()
  }

  private val listener = object : SensorEventListener {
    override fun onSensorChanged(event: SensorEvent) {
      if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
      // The sampling period is only a hint, and devices exceed it — a Samsung
      // asked for 25Hz delivered 126Hz. Samples arriving early are dropped so
      // the page gets the rate it configured.
      // The 10% slack keeps a sensor running at exactly the requested period
      // from losing samples to jitter.
      val minGapNanos = config.accelIntervalMs * 900_000L
      if (lastKeptNanos != 0L && event.timestamp - lastKeptNanos < minGapNanos) return
      lastKeptNanos = event.timestamp
      // Bounded for the same reason as the BLE buffer: if the flush stalls,
      // this must not grow without limit.
      if (buffer.size >= MAX_BUFFERED_SAMPLES) return
      // [x, y, z, epochMs] rather than an object: at ~25 samples a second the
      // repeated keys would be most of the payload.
      buffer.add(
        JSONArray()
          .put(event.values[0].toDouble())
          .put(event.values[1].toDouble())
          .put(event.values[2].toDouble())
          .put(epochMillisOf(event)),
      )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
  }

  private fun flush() {
    if (buffer.isEmpty()) return
    val payload = JSONObject().put("samples", JSONArray(buffer))
    buffer.clear()
    sink.emit("accel", payload.toString())
  }

  /**
   * `SensorEvent.timestamp` counts from boot, not from the epoch. With batching
   * a sample can reach this callback well after it was measured, so its own
   * timestamp is converted rather than stamping it with the time it arrived.
   */
  private fun epochMillisOf(event: SensorEvent): Long {
    val ageNanos = SystemClock.elapsedRealtimeNanos() - event.timestamp
    return System.currentTimeMillis() - ageNanos / 1_000_000L
  }

  private companion object {
    /** A few seconds of samples at the fastest allowed rate. */
    const val MAX_BUFFERED_SAMPLES = 500
  }
}
