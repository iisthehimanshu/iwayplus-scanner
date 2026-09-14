package com.iwayplus.scanner

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import org.json.JSONObject
import kotlin.math.abs

/**
 * Device heading from the rotation-vector sensor.
 *
 * The Flutter plugin never exposed heading — the Dart side read it through
 * `flutter_compass`, which has no web implementation. In a WebView the
 * alternative would be `DeviceOrientationEvent`, whose permission prompt is
 * unreliable inside WKWebView, so heading is sourced natively here instead.
 */
class HeadingScanner(
  private val context: Context,
  private val sink: ScannerSink,
) {
  private val sensorManager: SensorManager? =
    context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
  private val rotationVector: Sensor? =
    sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
  private val displayManager: DisplayManager? =
    context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

  private val rotationMatrix = FloatArray(9)
  private val orientation = FloatArray(3)

  private var config = ScannerConfig()
  private var lastHeading = Float.NaN

  var isScanning: Boolean = false
    private set

  fun configure(config: ScannerConfig) {
    this.config = config
  }

  fun powerState(): String = if (rotationVector == null) "unsupported" else "on"

  fun start() {
    if (isScanning) return
    val sensor = rotationVector
    if (sensor == null || sensorManager == null) {
      sink.emitError("NO_COMPASS", "No rotation vector sensor on this device")
      return
    }
    lastHeading = Float.NaN
    isScanning = sensorManager.registerListener(
      listener,
      sensor,
      SensorManager.SENSOR_DELAY_GAME,
    )
  }

  fun stop() {
    if (!isScanning) return
    isScanning = false
    sensorManager?.unregisterListener(listener)
  }

  private val listener = object : SensorEventListener {
    override fun onSensorChanged(event: SensorEvent) {
      if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

      SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

      // The rotation matrix is expressed in the device's native orientation.
      // On a landscape-locked or rotated screen that is not the direction the
      // user is facing, so the axes are remapped to the current display first.
      val (axisX, axisY) = when (displayRotation()) {
        Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
        Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
        Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
        else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
      }
      val remapped = FloatArray(9)
      SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remapped)
      SensorManager.getOrientation(remapped, orientation)

      val heading = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toFloat()

      // Suppressing sub-threshold changes matters more here than elsewhere:
      // the sensor fires far faster than the consumer needs, and every update
      // is a bridge crossing.
      if (!lastHeading.isNaN() && angularDelta(heading, lastHeading) < config.headingFilterDeg) {
        return
      }
      lastHeading = heading

      sink.emit(
        "heading",
        JSONObject()
          .put("heading", heading)
          .put("accuracy", accuracyDegrees)
          .put("timestamp", System.currentTimeMillis())
          .toString(),
      )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
      accuracyDegrees = when (accuracy) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> 5f
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> 15f
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> 30f
        else -> -1f
      }
    }
  }

  private var accuracyDegrees = -1f

  /**
   * Read through DisplayManager rather than `context.display` or
   * `WindowManager.defaultDisplay`.
   *
   * Both of those require a visual Context, and the Context this module is
   * constructed with is the React *application* context. Asking it for a
   * display throws UnsupportedOperationException — and because this runs in a
   * SensorEventListener callback, that exception unwinds through JNI and
   * aborts the process rather than surfacing as a Java crash. DisplayManager
   * is a plain system service and answers correctly from any Context.
   */
  private fun displayRotation(): Int =
    displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0

  /** Shortest angular distance between two bearings, in degrees. */
  private fun angularDelta(a: Float, b: Float): Float {
    val delta = abs(a - b) % 360f
    return if (delta > 180f) 360f - delta else delta
  }
}
