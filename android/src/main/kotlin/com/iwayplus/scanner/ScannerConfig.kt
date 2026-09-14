package com.iwayplus.scanner

import org.json.JSONObject

/**
 * Tunables supplied by the page, never baked in as constants.
 *
 * Every value here was a hardcoded literal in the Flutter plugin this module
 * was ported from. They live in configuration because this module ships inside
 * a host app on someone else's release cycle: a constant that needs retuning
 * for a dense venue must be changeable by redeploying the web bundle.
 */
data class ScannerConfig(
  val flushIntervalMs: Long = 250L,
  val restartIntervalMs: Long = 60_000L,
  val timeoutMs: Long? = null,
  val gpsIntervalMs: Long = 1_000L,
  val gpsDistanceFilterM: Float = 0f,
  val headingFilterDeg: Float = 1f,
  val maxBufferedReadings: Int = 2_000,
) {
  companion object {
    fun fromJson(json: String?): ScannerConfig {
      if (json.isNullOrBlank()) return ScannerConfig()
      val defaults = ScannerConfig()
      return try {
        val obj = JSONObject(json)
        ScannerConfig(
          flushIntervalMs = obj.optLong("flushIntervalMs", defaults.flushIntervalMs)
            .coerceIn(50L, 5_000L),
          restartIntervalMs = obj.optLong("restartIntervalMs", defaults.restartIntervalMs)
            .coerceIn(5_000L, 600_000L),
          timeoutMs = if (obj.has("timeoutMs") && !obj.isNull("timeoutMs")) {
            obj.optLong("timeoutMs").takeIf { it > 0 }
          } else {
            null
          },
          gpsIntervalMs = obj.optLong("gpsIntervalMs", defaults.gpsIntervalMs)
            .coerceIn(200L, 60_000L),
          gpsDistanceFilterM = obj.optDouble(
            "gpsDistanceFilterM",
            defaults.gpsDistanceFilterM.toDouble(),
          ).toFloat().coerceAtLeast(0f),
          headingFilterDeg = obj.optDouble(
            "headingFilterDeg",
            defaults.headingFilterDeg.toDouble(),
          ).toFloat().coerceIn(0f, 45f),
          maxBufferedReadings = obj.optInt("maxBufferedReadings", defaults.maxBufferedReadings)
            .coerceIn(100, 20_000),
        )
      } catch (error: Exception) {
        // A malformed config must not take scanning down with it.
        defaults
      }
    }
  }
}

/** Where a scanner core sends its output. Keeps the cores free of React types. */
fun interface ScannerSink {
  /** @param payloadJson a serialised JSON object, without the envelope. */
  fun emit(type: String, payloadJson: String)
}
