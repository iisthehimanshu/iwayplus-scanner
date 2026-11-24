package com.iwayplus.scanner

import android.content.Context
import android.util.Log

/** A scan stream the hub can run. [wire] is the name the page and Dart use. */
internal enum class ScanStream(val wire: String) {
  BLE("ble"),
  GPS("gps"),
  HEADING("heading"),
  ACCEL("accel");

  companion object {
    fun byWire(name: Any?): ScanStream? = entries.firstOrNull { it.wire == name }
  }
}

/**
 * Something that wants scan streams: a Flutter engine relaying to the page, or
 * the app's own scanning session ([AppScanSession]).
 */
internal fun interface ScanClaimant {
  /** One event from a stream this claimant holds, payload without envelope. */
  fun deliver(type: String, payloadJson: String)
}

/**
 * The one set of scanner cores in the process, shared by every claimant.
 *
 * Each claimant holds its own set of streams, and a stream's radio runs while
 * anyone holds it. Releasing a stream only drops that claimant's hold: the page
 * stopping BLE leaves it running for the app, and the app stopping leaves it
 * running for the page. A stream's events go only to the claimants holding it,
 * so the page never sees a stream it did not ask for, and its sequence numbers
 * stay free of gaps that would read as a stalled bridge.
 *
 * Owned by the process rather than by a Flutter engine, because the app's
 * session outlives the engine once the task is swiped away.
 *
 * Main thread only. Method calls, the cores' callbacks and the service all run
 * there, so nothing here is synchronised.
 */
internal class ScannerHub private constructor(context: Context) {

  private val claims = LinkedHashMap<ScanClaimant, MutableSet<ScanStream>>()

  val ble = BleScanner(context, sinkFor(ScanStream.BLE))
  val gps = GpsScanner(context, sinkFor(ScanStream.GPS))
  val heading = HeadingScanner(context, sinkFor(ScanStream.HEADING))
  val accel = AccelScanner(context, sinkFor(ScanStream.ACCEL))

  /** Tunables are process-wide: the page's `configure` also tunes the app's scans. */
  fun configure(config: ScannerConfig) {
    ble.configure(config)
    gps.configure(config)
    heading.configure(config)
    accel.configure(config)
  }

  fun isRunning(stream: ScanStream): Boolean = when (stream) {
    ScanStream.BLE -> ble.isScanning
    ScanStream.GPS -> gps.isScanning
    ScanStream.HEADING -> heading.isScanning
    ScanStream.ACCEL -> accel.isScanning
  }

  fun holds(claimant: ScanClaimant, stream: ScanStream): Boolean =
    claims[claimant]?.contains(stream) == true

  /**
   * Adds [claimant]'s hold on [streams] and starts any that need it.
   *
   * A stream someone else already has running is joined, not restarted, so the
   * page coming and going never interrupts the app's scan. A claimant asking
   * again for a stream only it holds restarts it, exactly as before the hub
   * existed: that is how the page recovers a scan Bluetooth being switched off
   * left marked as running.
   */
  fun claim(claimant: ScanClaimant, streams: Collection<ScanStream>) {
    val held = claims.getOrPut(claimant) { mutableSetOf() }
    for (stream in streams) {
      val joined = isRunning(stream) && heldByOtherThan(claimant, stream)
      // Held before starting, so an error the start emits reaches this claimant.
      held.add(stream)
      if (!joined) start(stream)
      Log.i(TAG, "claim ${stream.wire} by ${nameOf(claimant)}${if (joined) " (joined)" else ""}")
    }
  }

  /** Drops [claimant]'s hold on [streams], stopping any nobody else holds. */
  fun release(claimant: ScanClaimant, streams: Collection<ScanStream>) {
    val held = claims[claimant] ?: return
    for (stream in streams) {
      if (stream !in held) continue
      if (!heldByOtherThan(claimant, stream)) {
        // Stopped while still held, so the final flush reaches this claimant.
        stop(stream)
      }
      held.remove(stream)
      Log.i(TAG, "release ${stream.wire} by ${nameOf(claimant)}")
    }
    if (held.isEmpty()) claims.remove(claimant)
  }

  fun releaseAll(claimant: ScanClaimant) {
    release(claimant, claims[claimant]?.toList() ?: return)
  }

  /** Drops every claimant's holds except [keep]'s. */
  fun releaseAllExcept(keep: ScanClaimant) {
    for (claimant in claims.keys.toList()) {
      if (claimant !== keep) releaseAll(claimant)
    }
  }

  private fun heldByOtherThan(claimant: ScanClaimant, stream: ScanStream): Boolean =
    claims.any { (other, held) -> other !== claimant && stream in held }

  private fun start(stream: ScanStream) = when (stream) {
    ScanStream.BLE -> ble.start()
    ScanStream.GPS -> gps.start()
    ScanStream.HEADING -> heading.start()
    ScanStream.ACCEL -> accel.start()
  }

  private fun stop(stream: ScanStream) = when (stream) {
    ScanStream.BLE -> ble.stop()
    ScanStream.GPS -> gps.stop()
    ScanStream.HEADING -> heading.stop()
    ScanStream.ACCEL -> accel.stop()
  }

  /** Routes one core's output to whoever holds its stream. */
  private fun sinkFor(stream: ScanStream) = ScannerSink { type, payloadJson ->
    // Copied: a claimant reacting to an event may change the claims.
    for ((claimant, held) in claims.entries.toList()) {
      if (stream in held) claimant.deliver(type, payloadJson)
    }
  }

  private fun nameOf(claimant: ScanClaimant): String =
    if (claimant === AppScanSession) "app" else "page"

  companion object {
    private const val TAG = "IwayplusScannerHub"

    private var instance: ScannerHub? = null

    fun get(context: Context): ScannerHub =
      instance ?: ScannerHub(context.applicationContext).also { instance = it }
  }
}
