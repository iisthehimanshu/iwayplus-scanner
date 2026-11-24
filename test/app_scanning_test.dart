import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:iwayplus_scanner/iwayplus_scanner.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('ScanMode', () {
    test('webOnly', () {
      expect(const ScanMode.webOnly().toJson(), {'mode': 'webOnly'});
    });

    test('persistent with a keep-alive', () {
      final json = const ScanMode.persistent(
        keepAliveAfterSwipe: Duration(minutes: 30),
        notification: ScanNotification(title: 'Empower', iconName: 'ic_scan'),
      ).toJson();
      expect(json['mode'], 'persistent');
      expect(json['keepAliveAfterSwipeMs'], 1800000);
      expect(json['notification'], {
        'title': 'Empower',
        'text': 'Scanning for nearby beacons',
        'channelName': 'Location scanning',
        'iconName': 'ic_scan',
      });
    });

    test('persistent without a keep-alive runs until stopped', () {
      final json = const ScanMode.persistent().toJson();
      expect(json.containsKey('keepAliveAfterSwipeMs'), isTrue);
      expect(json['keepAliveAfterSwipeMs'], isNull);
      expect((json['notification'] as Map).containsKey('iconName'), isFalse);
    });

    test('a negative keep-alive is rejected', () {
      expect(
        () => const ScanMode.persistent(
          keepAliveAfterSwipe: Duration(seconds: -1),
        ).toJson(),
        throwsArgumentError,
      );
    });
  });

  group('app scanning on Android', () {
    const channel = MethodChannel('iwayplus_scanner');
    final calls = <MethodCall>[];

    setUp(() {
      debugDefaultTargetPlatformOverride = TargetPlatform.android;
      calls.clear();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            calls.add(call);
            return call.method == 'isAppScanning' ? true : null;
          });
    });

    tearDown(() {
      debugDefaultTargetPlatformOverride = null;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
    });

    test('setMode sends the mode as JSON', () async {
      await IwayplusScanner.setMode(
        const ScanMode.persistent(keepAliveAfterSwipe: Duration.zero),
      );
      expect(calls.single.method, 'setScanMode');
      final sent = jsonDecode(calls.single.arguments as String) as Map;
      expect(sent['mode'], 'persistent');
      expect(sent['keepAliveAfterSwipeMs'], 0);
    });

    test('startAppScanning sends stream names, once each', () async {
      await IwayplusScanner.startAppScanning([
        ScannerStream.ble,
        ScannerStream.gps,
        ScannerStream.ble,
      ]);
      expect(calls.single.method, 'startAppScanning');
      expect(calls.single.arguments, ['ble', 'gps']);
    });

    test('startAppScanning refuses the live-map streams', () {
      expect(
        () => IwayplusScanner.startAppScanning([ScannerStream.heading]),
        throwsArgumentError,
      );
      expect(
        () => IwayplusScanner.startAppScanning(const []),
        throwsArgumentError,
      );
      expect(calls, isEmpty);
    });

    test('stop and query reach the platform', () async {
      await IwayplusScanner.stopAppScanning();
      expect(await IwayplusScanner.isAppScanning(), isTrue);
      expect(calls.map((c) => c.method), ['stopAppScanning', 'isAppScanning']);
    });
  });

  group('app scanning elsewhere', () {
    setUp(() => debugDefaultTargetPlatformOverride = TargetPlatform.iOS);
    tearDown(() => debugDefaultTargetPlatformOverride = null);

    test('persistent mode is unsupported', () {
      expect(
        () => IwayplusScanner.setMode(const ScanMode.persistent()),
        throwsUnsupportedError,
      );
      expect(
        () => IwayplusScanner.startAppScanning([ScannerStream.ble]),
        throwsUnsupportedError,
      );
    });

    test('webOnly, stop and query are harmless no-ops', () async {
      await IwayplusScanner.setMode(const ScanMode.webOnly());
      await IwayplusScanner.stopAppScanning();
      expect(await IwayplusScanner.isAppScanning(), isFalse);
    });
  });
}
