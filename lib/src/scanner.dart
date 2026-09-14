import 'dart:convert';
import 'dart:io' show Platform;

import 'package:flutter/services.dart';

import 'types.dart';

/// Imperative access to the native scanner.
///
/// Most apps should render `IwayplusNavigation` instead, which owns the
/// WebView and both directions of the relay. This exists for apps that want
/// the scan streams for their own purposes.
class IwayplusScanner {
  IwayplusScanner._();

  static const MethodChannel _methods = MethodChannel('iwayplus_scanner');
  static const EventChannel _events = EventChannel('iwayplus_scanner/events');

  /// One shared native subscription. Each `receiveBroadcastStream()` call
  /// would re-register the platform listener and replace the previous one, so
  /// every subscriber goes through this single stream.
  static final Stream<String> _raw = _events
      .receiveBroadcastStream()
      .cast<String>();

  /// Envelopes, still JSON-encoded. The WebView relay forwards these untouched.
  static Stream<String> get rawEvents => _raw;

  /// Envelopes, parsed. Costs a parse per batch.
  static Stream<ScannerEnvelope> get events => _raw
      .map(_decode)
      .where((envelope) => envelope != null)
      .cast<ScannerEnvelope>();

  static ScannerEnvelope? _decode(String json) {
    try {
      final decoded = jsonDecode(json);
      if (decoded is! Map) return null;
      return ScannerEnvelope.tryParse(Map<String, dynamic>.from(decoded));
    } on FormatException {
      // A malformed envelope is a bug in the native side, not something the
      // app can act on. Dropping it keeps one bad batch from ending the stream.
      return null;
    }
  }

  /// Safe to call while scanning; the new values take effect immediately.
  static Future<void> configure(ScannerConfig config) =>
      _methods.invokeMethod<void>('configure', jsonEncode(config.toJson()));

  static Future<void> start(Iterable<ScannerStream> streams) => Future.wait([
    for (final stream in streams)
      _methods.invokeMethod<void>(switch (stream) {
        ScannerStream.ble => 'startBle',
        ScannerStream.gps => 'startGps',
        ScannerStream.heading => 'startHeading',
      }),
  ]);

  static Future<void> stop(Iterable<ScannerStream> streams) => Future.wait([
    for (final stream in streams)
      _methods.invokeMethod<void>(switch (stream) {
        ScannerStream.ble => 'stopBle',
        ScannerStream.gps => 'stopGps',
        ScannerStream.heading => 'stopHeading',
      }),
  ]);

  /// Stops every stream and resets the sequence counter.
  static Future<void> stopAll() => _methods.invokeMethod<void>('stopAll');

  /// Current adapter power and permission status. Also emitted as an
  /// `adapter` event.
  static Future<AdapterState> getState() async {
    final json = await _methods.invokeMethod<String>('getState');
    return AdapterState.fromJson(
      Map<String, dynamic>.from(jsonDecode(json ?? '{}') as Map),
    );
  }
}

/// Requests the runtime permissions scanning needs.
///
/// Android only — iOS raises its own prompts on first use of CoreBluetooth and
/// CoreLocation, driven by the usage strings in the host's Info.plist, so this
/// resolves `true` there straight away.
///
/// Call this and confirm it resolves `true` *before* showing the navigation
/// view. Starting a scan against a denied adapter produces no readings and no
/// error the page can explain to the user.
Future<bool> requestScannerPermissions() async {
  if (!Platform.isAndroid) return true;
  return await IwayplusScanner._methods.invokeMethod<bool>(
        'requestPermissions',
      ) ??
      false;
}
