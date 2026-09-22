/// Smoke test for the iwayplus_scanner plugin on real hardware.
///
/// Two tabs, because two things can break independently:
///   Native — the plugin itself: permissions, adapters, event envelopes.
///   Bridge — the WebView relay: injection, page → host commands, host → page
///            events, against the hosted navigation page.
///
/// Run with the credentials the hosted page requires:
/// `flutter run --dart-define=IWAYPLUS_API_KEY=KEY --dart-define=IWAYPLUS_VENUE=VENUE`
/// (the venue defaults to Iwayplus).
///
/// To test a navigation_sdk web build that isn't deployed yet, point the
/// bridge tab at a local server with
/// `--dart-define=IWAYPLUS_MAP_URL=http://localhost:8130/iwaymaps/` and, on
/// Android, `adb reverse tcp:8130 tcp:8130`. A localhost page uses the dev
/// backend, so pass a dev API key.
///
/// `--dart-define=IWAYPLUS_BUILDING_IDS=ID1,ID2` adds `buildingIds` to the link.
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:iwayplus_scanner/iwayplus_scanner.dart';

const _apiKey = String.fromEnvironment('IWAYPLUS_API_KEY');
const _venue = String.fromEnvironment(
  'IWAYPLUS_VENUE',
  defaultValue: 'Iwayplus',
);
const _mapUrl = String.fromEnvironment(
  'IWAYPLUS_MAP_URL',
  defaultValue: 'https://maps.iwayplus.in/iwaymaps/',
);

/// Optional comma-separated building ids that limit the venue to those
/// buildings. Left out of the link when unset.
const _buildingIds = String.fromEnvironment('IWAYPLUS_BUILDING_IDS');

const _ink = Color(0xFF0B1020);
const _panel = Color(0xFF141B2E);
const _accent = Color(0xFF1D4ED8);
const _label = Color(0xFF8FA0BF);
const _section = Color(0xFF7DD3FC);

void main() => runApp(const ScannerExampleApp());

class ScannerExampleApp extends StatelessWidget {
  const ScannerExampleApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
    debugShowCheckedModeBanner: false,
    title: 'iwayplus_scanner',
    theme: ThemeData(
      useMaterial3: true,
      brightness: Brightness.dark,
      scaffoldBackgroundColor: _ink,
      colorScheme: ColorScheme.fromSeed(
        seedColor: _accent,
        brightness: Brightness.dark,
      ),
    ),
    home: const _Home(),
  );
}

class _Home extends StatefulWidget {
  const _Home();

  @override
  State<_Home> createState() => _HomeState();
}

class _HomeState extends State<_Home> {
  int _tab = 0;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
              child: Row(
                children: [
                  for (final (index, name) in const [
                    (0, 'native'),
                    (1, 'bridge'),
                  ])
                    Expanded(
                      child: Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 4),
                        child: FilledButton(
                          style: FilledButton.styleFrom(
                            backgroundColor: _tab == index ? _accent : _panel,
                            foregroundColor: _tab == index
                                ? Colors.white
                                : _label,
                            padding: const EdgeInsets.symmetric(vertical: 14),
                            textStyle: const TextStyle(
                              fontSize: 17,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                          onPressed: () => setState(() => _tab = index),
                          child: Text(name),
                        ),
                      ),
                    ),
                ],
              ),
            ),
            // Only one tab is alive at a time, like the React Native test app:
            // leaving the bridge tab disposes the WebView, which stops scanning.
            Expanded(
              child: _tab == 0 ? const _NativePanel() : const _BridgePanel(),
            ),
          ],
        ),
      ),
    );
  }
}

// ── Native ──────────────────────────────────────────────────────────────────

class _NativePanel extends StatefulWidget {
  const _NativePanel();

  @override
  State<_NativePanel> createState() => _NativePanelState();
}

class _NativePanelState extends State<_NativePanel> {
  StreamSubscription<ScannerEnvelope>? _subscription;
  bool _scanning = false;

  AdapterState? _adapter;
  int _batches = 0;
  int _readings = 0;
  int _dropped = 0;
  final Set<String> _devices = {};
  BleReading? _strongest;
  final List<(int, int)> _recent = []; // (time ms, readings) for readings/sec
  GpsPayload? _gps;
  HeadingPayload? _heading;
  int _seq = 0;
  int _gaps = 0;
  String? _lastError;

  @override
  void initState() {
    super.initState();
    _subscription = IwayplusScanner.events.listen(_onEvent);
    IwayplusScanner.getState();
  }

  void _onEvent(ScannerEnvelope event) {
    setState(() {
      if (_seq != 0 && event.sequence != _seq + 1 && event.sequence != 1) {
        _gaps++;
      }
      _seq = event.sequence;
      switch (event.type) {
        case 'adapter':
          _adapter = AdapterState.fromJson(event.payload);
        case 'ble':
          final batch = BlePayload.fromJson(event.payload);
          _batches++;
          _readings += batch.readings.length;
          _dropped += batch.dropped;
          for (final reading in batch.readings) {
            _devices.add(reading.device);
            if (_strongest == null || reading.rssi > _strongest!.rssi) {
              _strongest = reading;
            }
          }
          final now = DateTime.now().millisecondsSinceEpoch;
          _recent
            ..add((now, batch.readings.length))
            ..removeWhere((entry) => now - entry.$1 > 1000);
        case 'gps':
          _gps = GpsPayload.fromJson(event.payload);
        case 'heading':
          _heading = HeadingPayload.fromJson(event.payload);
        case 'error':
          final error = ErrorPayload.fromJson(event.payload);
          _lastError = '${error.code}: ${error.message}';
      }
    });
  }

  Future<void> _toggle() async {
    if (_scanning) {
      await IwayplusScanner.stopAll();
      setState(() => _scanning = false);
      return;
    }
    if (!await requestScannerPermissions()) {
      setState(() => _lastError = 'Permissions denied');
      await IwayplusScanner.getState();
      return;
    }
    setState(() {
      _scanning = true;
      _batches = _readings = _dropped = _seq = _gaps = 0;
      _devices.clear();
      _recent.clear();
      _strongest = null;
      _lastError = null;
    });
    await IwayplusScanner.start(ScannerStream.values);
  }

  @override
  void dispose() {
    _subscription?.cancel();
    if (_scanning) IwayplusScanner.stopAll();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final adapter = _adapter;
    final perSecond = _recent.fold<int>(0, (sum, entry) => sum + entry.$2);
    final strongest = _strongest;
    final gps = _gps;
    final heading = _heading;

    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 32),
      children: [
        const _Section('adapter'),
        _Row('bluetooth', adapter?.bluetooth ?? '-'),
        _Row('location', adapter?.location ?? '-'),
        _Row(
          'permissions',
          adapter == null
              ? '-'
              : 'ble ${adapter.bluetoothPermission ? 'yes' : 'no'} · '
                    'loc ${adapter.locationPermission ? 'yes' : 'no'}',
        ),
        const _Section('ble'),
        _Row('batches', '$_batches'),
        _Row('readings', '$_readings'),
        _Row('readings/sec', '$perSecond'),
        _Row('unique devices', '${_devices.length}'),
        _Row('dropped', '$_dropped'),
        _Row(
          'strongest',
          strongest == null
              ? '-'
              : '${strongest.name.isEmpty ? strongest.device.substring(0, 8) : strongest.name} '
                    '(${strongest.rssi} dBm)',
        ),
        const _Section('other streams'),
        _Row(
          'gps',
          gps == null
              ? '-'
              : '${gps.latitude.toStringAsFixed(5)}, ${gps.longitude.toStringAsFixed(5)} '
                    '(±${gps.accuracy.round()}m)',
        ),
        _Row('heading', heading == null ? '-' : '${heading.heading.round()}°'),
        const _Section('protocol'),
        _Row('seq', '$_seq'),
        _Row('seq gaps', '$_gaps'),
        _Row('last error', _lastError ?? '-'),
        const SizedBox(height: 28),
        FilledButton(
          style: FilledButton.styleFrom(
            backgroundColor: _scanning ? const Color(0xFFB91C1C) : _accent,
            padding: const EdgeInsets.symmetric(vertical: 18),
            textStyle: const TextStyle(
              fontSize: 18,
              fontWeight: FontWeight.w700,
            ),
          ),
          onPressed: _toggle,
          child: Text(_scanning ? 'stop scanning' : 'start scanning'),
        ),
      ],
    );
  }
}

class _Section extends StatelessWidget {
  const _Section(this.title);
  final String title;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(top: 24, bottom: 6),
    child: Text(
      title.toUpperCase(),
      style: const TextStyle(
        color: _section,
        fontSize: 14,
        fontWeight: FontWeight.w700,
        letterSpacing: 0.8,
      ),
    ),
  );
}

class _Row extends StatelessWidget {
  const _Row(this.label, this.value);
  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(vertical: 11),
    decoration: const BoxDecoration(
      border: Border(bottom: BorderSide(color: _panel)),
    ),
    child: Row(
      children: [
        Text(label, style: const TextStyle(color: _label, fontSize: 16)),
        const SizedBox(width: 16),
        Expanded(
          child: Text(
            value,
            textAlign: TextAlign.right,
            style: const TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w500,
              fontFeatures: [FontFeature.tabularFigures()],
            ),
          ),
        ),
      ],
    ),
  );
}

// ── Bridge ──────────────────────────────────────────────────────────────────

class _BridgePanel extends StatefulWidget {
  const _BridgePanel();

  @override
  State<_BridgePanel> createState() => _BridgePanelState();
}

class _BridgePanelState extends State<_BridgePanel> {
  bool _closed = false;
  String _lastCommand = '-';

  static final String _url =
      '$_mapUrl'
      '?venueName=${Uri.encodeQueryComponent(_venue)}'
      '&apiKey=${Uri.encodeQueryComponent(_apiKey)}'
      '${_buildingIds.isEmpty ? '' : '&buildingIds=${Uri.encodeQueryComponent(_buildingIds)}'}';

  @override
  Widget build(BuildContext context) {
    if (_apiKey.isEmpty) {
      return const _Message(
        title: 'API key needed',
        body:
            'The hosted page requires venueName and apiKey. Run with\n\n'
            'flutter run --dart-define=IWAYPLUS_API_KEY=<key>',
      );
    }
    if (_closed) {
      return _Message(
        title: 'page sent: close',
        body:
            'The host received the command from inside the WebView, which '
            'means page → host works.',
        action: ('reopen', () => setState(() => _closed = false)),
      );
    }
    return Column(
      children: [
        Expanded(
          child: IwayplusNavigation(
            url: _url,
            config: const ScannerConfig(flushIntervalMs: 250),
            onClose: () => setState(() => _closed = true),
            onCommand: (command) {
              setState(() => _lastCommand = '${command['cmd']}');
              return true;
            },
          ),
        ),
        Padding(
          padding: const EdgeInsets.all(10),
          child: Text(
            'last unknown command: $_lastCommand',
            style: const TextStyle(color: _label),
          ),
        ),
      ],
    );
  }
}

class _Message extends StatelessWidget {
  const _Message({required this.title, required this.body, this.action});
  final String title;
  final String body;
  final (String, VoidCallback)? action;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.all(24),
    child: Column(
      mainAxisAlignment: MainAxisAlignment.center,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          title,
          style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
        ),
        const SizedBox(height: 12),
        SelectableText(
          body,
          style: const TextStyle(color: _label, fontSize: 15),
        ),
        if (action case (final label, final onPressed)) ...[
          const SizedBox(height: 24),
          FilledButton(onPressed: onPressed, child: Text(label)),
        ],
      ],
    ),
  );
}
