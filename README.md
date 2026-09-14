# iwayplus_scanner

BLE + GPS + heading scanning for a Flutter host app, relayed into the Iwayplus
navigation page running in a WebView.

The Flutter counterpart of
[`@iwayplus/react-native-scanner`](https://github.com/iisthehimanshu/react-native-scanner).
The host app contributes **sensors and permissions**. Everything else —
positioning maths, beacon maps, venue configuration, the map, the navigation UI
— is served from Iwayplus and runs inside the WebView. No venue logic and none
of `navigation_sdk`'s plugins are compiled into the host binary.

```
┌─ host app (this plugin) ──┐        ┌─ WebView (hosted by Iwayplus) ─┐
│  CoreBluetooth / BLE scan │  JSON  │  positioning algorithms         │
│  CoreLocation / GPS       │ ─────► │  beacon map, routing            │
│  magnetometer / heading   │        │  map + navigation UI            │
└───────────────────────────┘        └─────────────────────────────────┘
              ▲                                      │
              └────────── start / stop ◄─────────────┘
```

Scan data never leaves the device: it goes into the WebView, not to a server.

> **Flutter apps have a second option.** A Flutter app can depend on
> `navigation_sdk` directly and get the map compiled in, with no WebView. This
> plugin trades that rendering speed for what the WebView gives React Native
> apps: SDK updates arrive when Iwayplus redeploys the page, and the app carries
> none of the SDK's dependencies.

## Requirements

| | |
|---|---|
| Flutter | 3.3+ (built with 3.44.6) |
| Android | minSdk 24, compileSdk 36 |
| iOS | 14.0+ |
| WebView | `webview_flutter` 4.14+ (pulled in by this plugin) |

## Install

```yaml
dependencies:
  iwayplus_scanner:
    git:
      url: https://github.com/iisthehimanshu/iwayplus-scanner.git
```

## Permissions

**Android** — the plugin's manifest declares `INTERNET`, `ACCESS_FINE_LOCATION`,
`ACCESS_COARSE_LOCATION`, `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, and the legacy
`BLUETOOTH` / `BLUETOOTH_ADMIN` up to API 30. They merge into your app, but the
runtime ones must still be **granted before the map is shown** — see Usage.

`BLUETOOTH_SCAN` is declared without `neverForLocation`: beacon signal strength
is used to work out where the user is, which is exactly what that flag promises
isn't happening.

**iOS** — add to `ios/Runner/Info.plist`:

```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>Used to find nearby beacons so we can show your position indoors.</string>
<key>NSLocationWhenInUseUsageDescription</key>
<string>Used to show your position on the venue map.</string>
```

Without `NSBluetoothAlwaysUsageDescription`, iOS terminates the app the moment
scanning starts. iOS raises its own prompts on first use; there is nothing to
call from Dart. Scanning is foreground-only, so add no background modes.

## Usage

```dart
import 'package:flutter/material.dart';
import 'package:iwayplus_scanner/iwayplus_scanner.dart';

class VenueMapScreen extends StatefulWidget {
  const VenueMapScreen({super.key});

  @override
  State<VenueMapScreen> createState() => _VenueMapScreenState();
}

class _VenueMapScreenState extends State<VenueMapScreen> {
  static const venueName = 'YOUR_VENUE_NAME';
  static const apiKey = 'YOUR_API_KEY';

  // Grant before showing the map. Scanning against a denied adapter produces
  // no readings and no error the page can explain — it just never locates.
  // Resolves true straight away on iOS, which shows its own prompts.
  late final Future<bool> _granted = requestScannerPermissions();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Venue map')),
      body: FutureBuilder<bool>(
        future: _granted,
        builder: (context, snapshot) {
          if (!snapshot.hasData) return const SizedBox.shrink();
          if (!snapshot.data!) {
            return const Center(
              child: Text('Indoor positioning needs Bluetooth and location.'),
            );
          }
          return IwayplusNavigation(
            url: 'https://maps.iwayplus.in/iwaymaps/'
                '?venueName=${Uri.encodeQueryComponent(venueName)}'
                '&apiKey=${Uri.encodeQueryComponent(apiKey)}',
            autoRequestPermissions: false, // already requested above
          );
        },
      ),
    );
  }
}
```

Open it like any other screen — `Navigator.push(context, MaterialPageRoute(builder: (_) => const VenueMapScreen()))`.
**Showing `IwayplusNavigation` opens navigation; removing it ends it**, and
removal stops every stream. Give users their own way back (the app bar's back
button above): inside a WebView the page's exit has nowhere to go, so `onClose`
does not fire today.

`IwayplusNavigation` needs bounded constraints. Inside a `Column` wrap it in
`Expanded`; it cannot size itself inside a scroll view.

### URL parameters

| Parameter | Required | Meaning |
|---|---|---|
| `venueName` | **Yes** | Venue to load. |
| `apiKey` | **Yes** | Authenticates the SDK and tracks API usage. Without either one, the page shows an error screen. |
| `destinationLandmarkId` | No | Opens the detail panel for this landmark. |
| `sourceLandmarkId` | No | Sets the user's starting position. Mutually exclusive with `destinationLandmarkId`. |

Use the landmark's `polyId` for both landmark parameters, and URL-encode every value.

### Widget

| Parameter | Type | Description |
|---|---|---|
| `url` | `String` | **Required.** The hosted page with its parameters. |
| `config` | `ScannerConfig?` | Scanner tunables — batching window, scan restart interval, GPS interval. The defaults suit most venues. |
| `autoRequestPermissions` | `bool` | Request Android permissions on first build. Default `true`. |
| `onPermissionResult` | `ValueChanged<bool>?` | Outcome of that request. |
| `onClose` | `VoidCallback?` | The page asked to be dismissed. |
| `onCommand` | `bool Function(Map<String, dynamic>)?` | Commands from the page the bridge does not recognise. Return `true` if handled. |

A `GlobalKey<IwayplusNavigationState>` exposes `reload()` and `stopScanning()`.

### Scanning without the WebView

```dart
final subscription = IwayplusScanner.events.listen((event) {
  if (event.type == 'ble') print(BlePayload.fromJson(event.payload).readings.length);
});
await IwayplusScanner.start([ScannerStream.ble, ScannerStream.gps, ScannerStream.heading]);
```

## Protocol

Identical to `@iwayplus/react-native-scanner` — the page cannot tell which host
it is running in. The page drives scanning; commands travel page → host through
a JavaScript channel, and events travel host → page as JSON envelopes:

```json
{"v":1,"seq":42,"t":1757337600000,"type":"ble","payload":{ … }}
```

Event types: `hello`, `ble`, `gps`, `heading`, `adapter`, `error`. The React
Native package's `src/types.ts` is the source of truth for the schemas;
`lib/src/types.dart` mirrors it.

`webview_flutter` cannot inject scripts at document start, so the bridge is
injected from the page-started and page-finished callbacks. That is safe: the
page checks for an existing bridge before waiting up to 5 seconds for the
`iwayplusscannerready` event.

## Behaviour notes

- Scanning stops when the app goes to the background and resumes when it
  returns. The WebView's JavaScript is suspended in the background, so readings
  gathered there would have nowhere to go.
- Advertisements are batched (250 ms by default) rather than sent one by one.
- On iOS the Bluetooth manager is created on first use, so linking the plugin
  does not raise the Bluetooth prompt at app launch.

## Keeping in sync with react-native-scanner

The scanning code is **copied byte-for-byte** from `react-native-scanner`, in
the same `com.iwayplus.scanner` package, so a plain `diff` shows any drift. A fix
to any of these must land in both packages:

| This plugin | react-native-scanner |
|---|---|
| `android/src/main/kotlin/com/iwayplus/scanner/BleScanner.kt` | `android/src/main/java/com/iwayplus/scanner/BleScanner.kt` |
| `android/src/main/kotlin/com/iwayplus/scanner/GpsScanner.kt` | `android/src/main/java/com/iwayplus/scanner/GpsScanner.kt` |
| `android/src/main/kotlin/com/iwayplus/scanner/HeadingScanner.kt` | `android/src/main/java/com/iwayplus/scanner/HeadingScanner.kt` |
| `android/src/main/kotlin/com/iwayplus/scanner/ScannerConfig.kt` | `android/src/main/java/com/iwayplus/scanner/ScannerConfig.kt` |
| `ios/iwayplus_scanner/Sources/iwayplus_scanner/IwayplusScannerImpl.swift` | `ios/IwayplusScannerImpl.swift` |

```bash
RN=../react-native-scanner
for f in BleScanner GpsScanner HeadingScanner ScannerConfig; do
  diff -q "$RN/android/src/main/java/com/iwayplus/scanner/$f.kt" "android/src/main/kotlin/com/iwayplus/scanner/$f.kt"
done
diff -q "$RN/ios/IwayplusScannerImpl.swift" ios/iwayplus_scanner/Sources/iwayplus_scanner/IwayplusScannerImpl.swift
```

`lib/src/bridge_script.dart` and `lib/src/types.dart` are ports rather than
copies: they must behave the same as `src/bridgeScript.ts` and `src/types.ts`.

One consequence of the shared package name: a single app cannot link both this
plugin and `react-native-scanner`.

## Example

`example/` is the same smoke test as the React Native ScannerTestApp: a
**native** tab with live adapter, BLE, GPS, heading and sequence counters, and a
**bridge** tab running the hosted page.

```bash
cd example
flutter run --dart-define=IWAYPLUS_API_KEY=YOUR_API_KEY
```
