import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:iwayplus_scanner/iwayplus_scanner.dart';

void main() {
  group('relayStatement', () {
    test('passes the envelope as a string literal the page can parse', () {
      const json =
          '{"v":1,"seq":3,"type":"heading","payload":{"heading":12.5}}';
      final statement = relayStatement(json);
      final literal = RegExp(r'__receive\((.*)\);$').firstMatch(statement)![1]!;
      expect(jsonDecode(literal), json);
    });

    test('escapes line separators that would break JavaScript source', () {
      final statement = relayStatement('{"name":"a\u2028b\u2029c"}');
      expect(statement.contains('\u2028'), isFalse);
      expect(statement.contains('\u2029'), isFalse);
      expect(statement, contains(r'\u2028'));
      expect(statement, contains(r'\u2029'));
    });

    test('guards against a page without the bridge', () {
      expect(relayStatement('{}'), startsWith('window.__iwayplusScanner &&'));
    });
  });

  group('bridgeBootstrap', () {
    test('sends commands through the Flutter channel', () {
      expect(bridgeBootstrap, contains('window.IwayplusScannerHost'));
      expect(bridgeBootstrap, isNot(contains('ReactNativeWebView')));
    });

    test('is idempotent and announces itself', () {
      expect(
        bridgeBootstrap,
        contains('if (window.__iwayplusScanner) return;'),
      );
      expect(bridgeBootstrap, contains("new Event('iwayplusscannerready')"));
    });

    test('exposes every command the page calls', () {
      for (final command in [
        'configure',
        'start',
        'stop',
        'stopAll',
        'getState',
        'ready',
        'close',
      ]) {
        expect(bridgeBootstrap, contains("cmd: '$command'"));
      }
    });

    test('advertises every stream the host can run', () {
      // The page only asks for accel when it sees it here, so a missing entry
      // silently sends the page back to devicemotion.
      for (final stream in ScannerStream.values) {
        expect(bridgeBootstrap, contains("'${stream.name}'"));
      }
    });
  });

  group('types', () {
    test('ScannerConfig omits unset fields', () {
      expect(const ScannerConfig().toJson(), isEmpty);
      expect(
        const ScannerConfig(flushIntervalMs: 250, headingFilterDeg: 2).toJson(),
        {'flushIntervalMs': 250, 'headingFilterDeg': 2.0},
      );
    });

    test('ScannerConfig round-trips what the page sends', () {
      final config = ScannerConfig.fromJson({
        'flushIntervalMs': 500,
        'gpsDistanceFilterM': 1,
        'unknown': true,
      });
      expect(config.toJson(), {
        'flushIntervalMs': 500,
        'gpsDistanceFilterM': 1.0,
      });
    });

    test('ScannerEnvelope rejects malformed input', () {
      expect(ScannerEnvelope.tryParse({'type': 'ble'}), isNull);
      final envelope = ScannerEnvelope.tryParse({
        'v': 1,
        'seq': 7,
        't': 1000,
        'type': 'ble',
        'payload': {'readings': [], 'from': 1, 'to': 2, 'dropped': 0},
      })!;
      expect(envelope.sequence, 7);
      expect(BlePayload.fromJson(envelope.payload).readings, isEmpty);
    });

    test('ScannerStream.byName matches page stream names', () {
      expect(ScannerStream.byName('heading'), ScannerStream.heading);
      expect(ScannerStream.byName('accel'), ScannerStream.accel);
      expect(ScannerStream.byName('wifi'), isNull);
    });

    test('AdapterState reads the native state object', () {
      final state = AdapterState.fromJson({
        'bluetooth': 'on',
        'location': 'off',
        'permissions': {'bluetooth': true, 'location': false},
        'scanning': {'ble': true, 'gps': false, 'heading': true},
      });
      expect(state.bluetooth, 'on');
      expect(state.locationPermission, isFalse);
      expect(state.scanningHeading, isTrue);
      // Absent from hosts built before the accelerometer stream existed.
      expect(state.scanningAccel, isFalse);
    });

    test('AccelPayload reads [x, y, z, timestamp] samples', () {
      final payload = AccelPayload.fromJson({
        'samples': [
          [0.1, -0.2, 9.8, 1000],
          [1, 2, 3, 1040.0],
          [1, 2, 3], // malformed, skipped
        ],
      });
      expect(payload.samples, hasLength(2));
      expect(payload.samples.first.z, 9.8);
      expect(payload.samples.last.x, 1.0);
      expect(payload.samples.last.timestamp, 1040);
    });
  });
}
