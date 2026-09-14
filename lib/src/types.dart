/// The wire protocol between the native scanner and the navigation page.
///
/// Mirrors `src/types.ts` in `@iwayplus/react-native-scanner`, which is the
/// source of truth for the contract: the page decodes exactly these shapes
/// whichever host relays them.
library;

/// Protocol version carried in every envelope's `v`.
const int protocolVersion = 1;

/// A scan stream the native side can run.
enum ScannerStream {
  ble,
  gps,
  heading;

  /// Matches the names the page sends in `start` / `stop` commands.
  static ScannerStream? byName(Object? name) => switch (name) {
    'ble' => ScannerStream.ble,
    'gps' => ScannerStream.gps,
    'heading' => ScannerStream.heading,
    _ => null,
  };
}

/// Tunables. All of them live here rather than as native constants so they can
/// be retuned from the web bundle without a host-app release.
class ScannerConfig {
  const ScannerConfig({
    this.flushIntervalMs,
    this.restartIntervalMs,
    this.timeoutMs,
    this.gpsIntervalMs,
    this.gpsDistanceFilterM,
    this.headingFilterDeg,
    this.maxBufferedReadings,
  });

  /// Reads a config object sent by the page. Unknown keys are ignored.
  factory ScannerConfig.fromJson(Map<String, dynamic> json) => ScannerConfig(
    flushIntervalMs: (json['flushIntervalMs'] as num?)?.toInt(),
    restartIntervalMs: (json['restartIntervalMs'] as num?)?.toInt(),
    timeoutMs: (json['timeoutMs'] as num?)?.toInt(),
    gpsIntervalMs: (json['gpsIntervalMs'] as num?)?.toInt(),
    gpsDistanceFilterM: (json['gpsDistanceFilterM'] as num?)?.toDouble(),
    headingFilterDeg: (json['headingFilterDeg'] as num?)?.toDouble(),
    maxBufferedReadings: (json['maxBufferedReadings'] as num?)?.toInt(),
  );

  /// Batching window in ms (default 250).
  final int? flushIntervalMs;

  /// How often to tear down and restart the BLE scan, in ms (default 60000).
  /// Android throttles a long-running scan; a periodic restart keeps results
  /// flowing.
  final int? restartIntervalMs;

  /// Stop scanning automatically after this many ms. Null for no timeout.
  final int? timeoutMs;

  /// GPS update interval in ms (default 1000).
  final int? gpsIntervalMs;

  /// Minimum movement before a GPS update, in metres (default 0).
  final double? gpsDistanceFilterM;

  /// Heading updates below this change in degrees are suppressed (default 1).
  final double? headingFilterDeg;

  /// Hard cap on advertisements buffered within one flush window
  /// (default 2000).
  final int? maxBufferedReadings;

  /// Unset fields are omitted, so the native defaults apply.
  Map<String, dynamic> toJson() => {
    if (flushIntervalMs != null) 'flushIntervalMs': flushIntervalMs,
    if (restartIntervalMs != null) 'restartIntervalMs': restartIntervalMs,
    if (timeoutMs != null) 'timeoutMs': timeoutMs,
    if (gpsIntervalMs != null) 'gpsIntervalMs': gpsIntervalMs,
    if (gpsDistanceFilterM != null) 'gpsDistanceFilterM': gpsDistanceFilterM,
    if (headingFilterDeg != null) 'headingFilterDeg': headingFilterDeg,
    if (maxBufferedReadings != null) 'maxBufferedReadings': maxBufferedReadings,
  };
}

/// One scan event as it crosses the bridge.
///
/// `seq` is monotonic and resets only on `stopAll`; gaps mean the bridge
/// stalled.
class ScannerEnvelope {
  const ScannerEnvelope({
    required this.version,
    required this.sequence,
    required this.time,
    required this.type,
    required this.payload,
  });

  /// Returns null for anything that is not a well-formed envelope.
  static ScannerEnvelope? tryParse(Map<String, dynamic> json) {
    final type = json['type'];
    final payload = json['payload'];
    if (type is! String || payload is! Map) return null;
    return ScannerEnvelope(
      version: (json['v'] as num?)?.toInt() ?? 0,
      sequence: (json['seq'] as num?)?.toInt() ?? 0,
      time: (json['t'] as num?)?.toInt() ?? 0,
      type: type,
      payload: Map<String, dynamic>.from(payload),
    );
  }

  final int version;
  final int sequence;

  /// Emission time, epoch ms, from the native clock.
  final int time;

  /// `hello`, `ble`, `gps`, `heading`, `adapter` or `error`.
  final String type;
  final Map<String, dynamic> payload;
}

/// One BLE advertisement, forwarded verbatim and unfiltered.
class BleReading {
  const BleReading({
    required this.device,
    required this.name,
    required this.rssi,
    required this.timestamp,
    required this.manufacturerHex,
  });

  factory BleReading.fromJson(Map<String, dynamic> json) => BleReading(
    device: json['device'] as String? ?? '',
    name: json['name'] as String? ?? '',
    rssi: (json['rssi'] as num?)?.toInt() ?? 0,
    timestamp: (json['timestamp'] as num?)?.toInt() ?? 0,
    manufacturerHex: json['manufacturerHex'] as String? ?? '',
  );

  /// Android: MAC address. iOS: the CoreBluetooth peripheral UUID.
  final String device;
  final String name;
  final int rssi;
  final int timestamp;

  /// Manufacturer-specific data, uppercase hex; empty when absent.
  final String manufacturerHex;
}

/// A flush window's worth of advertisements.
class BlePayload {
  const BlePayload({
    required this.readings,
    required this.from,
    required this.to,
    required this.dropped,
  });

  factory BlePayload.fromJson(Map<String, dynamic> json) => BlePayload(
    readings: [
      for (final reading in (json['readings'] as List? ?? const []))
        if (reading is Map)
          BleReading.fromJson(Map<String, dynamic>.from(reading)),
    ],
    from: (json['from'] as num?)?.toInt() ?? 0,
    to: (json['to'] as num?)?.toInt() ?? 0,
    dropped: (json['dropped'] as num?)?.toInt() ?? 0,
  );

  final List<BleReading> readings;
  final int from;
  final int to;

  /// Advertisements dropped because the buffer hit `maxBufferedReadings`.
  final int dropped;
}

class GpsPayload {
  const GpsPayload({
    required this.latitude,
    required this.longitude,
    required this.accuracy,
    required this.bearing,
    required this.altitude,
    required this.speed,
    required this.timestamp,
  });

  factory GpsPayload.fromJson(Map<String, dynamic> json) => GpsPayload(
    latitude: (json['latitude'] as num?)?.toDouble() ?? 0,
    longitude: (json['longitude'] as num?)?.toDouble() ?? 0,
    accuracy: (json['accuracy'] as num?)?.toDouble() ?? -1,
    bearing: (json['bearing'] as num?)?.toDouble() ?? -1,
    altitude: (json['altitude'] as num?)?.toDouble() ?? 0,
    speed: (json['speed'] as num?)?.toDouble() ?? -1,
    timestamp: (json['timestamp'] as num?)?.toInt() ?? 0,
  );

  final double latitude;
  final double longitude;

  /// Metres.
  final double accuracy;

  /// Course over ground in degrees, or -1 when unavailable.
  final double bearing;
  final double altitude;

  /// Metres/second.
  final double speed;
  final int timestamp;
}

class HeadingPayload {
  const HeadingPayload({
    required this.heading,
    required this.accuracy,
    required this.timestamp,
  });

  factory HeadingPayload.fromJson(Map<String, dynamic> json) => HeadingPayload(
    heading: (json['heading'] as num?)?.toDouble() ?? 0,
    accuracy: (json['accuracy'] as num?)?.toDouble() ?? -1,
    timestamp: (json['timestamp'] as num?)?.toInt() ?? 0,
  );

  /// Degrees from magnetic north, 0-360.
  final double heading;

  /// Degrees of uncertainty, or -1 when the platform does not report it.
  final double accuracy;
  final int timestamp;
}

/// Why positioning is not working, when it is not working.
///
/// Power states are `on`, `off`, `unauthorized`, `unsupported` or `unknown`.
class AdapterState {
  const AdapterState({
    required this.bluetooth,
    required this.location,
    required this.bluetoothPermission,
    required this.locationPermission,
    required this.scanningBle,
    required this.scanningGps,
    required this.scanningHeading,
  });

  factory AdapterState.fromJson(Map<String, dynamic> json) {
    final permissions = json['permissions'] as Map? ?? const {};
    final scanning = json['scanning'] as Map? ?? const {};
    return AdapterState(
      bluetooth: json['bluetooth'] as String? ?? 'unknown',
      location: json['location'] as String? ?? 'unknown',
      bluetoothPermission: permissions['bluetooth'] == true,
      locationPermission: permissions['location'] == true,
      scanningBle: scanning['ble'] == true,
      scanningGps: scanning['gps'] == true,
      scanningHeading: scanning['heading'] == true,
    );
  }

  final String bluetooth;
  final String location;
  final bool bluetoothPermission;
  final bool locationPermission;
  final bool scanningBle;
  final bool scanningGps;
  final bool scanningHeading;
}

class ErrorPayload {
  const ErrorPayload({required this.code, required this.message});

  factory ErrorPayload.fromJson(Map<String, dynamic> json) => ErrorPayload(
    code: json['code'] as String? ?? '',
    message: json['message'] as String? ?? '',
  );

  final String code;
  final String message;
}
