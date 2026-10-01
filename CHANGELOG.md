## 0.2.6

* The page's spoken instructions are now voiced by the host app. An Android
  WebView has no speech engine, so navigation instructions were silent unless
  TalkBack was on. The page sends `speak` / `stopSpeaking` over the bridge and
  the plugin speaks with the device's text-to-speech engine (Android
  `TextToSpeech`, iOS `AVSpeechSynthesizer`), reporting progress back as
  `speech` events.
* `IwayplusNavigation` tells the page whether TalkBack or VoiceOver is on, when
  the page says it is ready and whenever it changes. The page uses that to
  choose between a screen-reader announcement and speaking aloud.
* New `IwayplusScanner.speak()` and `stopSpeaking()`. Android: the plugin
  manifest declares the `TTS_SERVICE` query that Android 11+ needs to see the
  speech engine. Hosts must rebuild to pick up the native changes.

## 0.2.5

* The page's "Scan nearby QR" now works inside `IwayplusNavigation`. The
  WebView had no handler for the page's camera request, so Android denied it
  and the page showed "NotAllowedError: Permission denied", and iOS showed
  WebKit's own camera prompt every time the scanner opened. The view now asks
  for the system camera permission the first time the page opens the camera,
  and grants the page's request from then on: one prompt, once.
* New `requestCameraPermission()`, for hosts that want to ask earlier.
* Android: the plugin manifest declares `CAMERA`, with the camera feature
  marked not required. iOS hosts need `NSCameraUsageDescription` in
  Info.plist; without it the scanner stays unavailable.

## 0.2.4

* iOS: `requestScannerPermissions()` now asks for location while the app is in
  use, and resolves to the answer. It used to resolve `true` without asking,
  and location was only ever requested when GPS started, which the page never
  did: it read a location that was never asked as not granted and showed its
  "permission required" prompt instead. iOS lists Location on the app's
  Settings page only once the app has asked, so "Open Settings" could not fix
  it either. `autoRequestPermissions` now covers iOS the same way.
* iOS: `IwayplusScanner.openSettings()` shows the location prompt when location
  was never asked, and opens Settings only once it has been answered.

## 0.2.3

* New `openSettings` command. The bridge bootstrap exposes
  `window.__iwayplusScanner.openSettings()`, and the host answers it by
  opening the app's page in system settings. The page calls it from its
  "permission required" prompt. Before this, that prompt's "Open Settings"
  button did nothing inside the WebView. `onCommand` sees the command first
  and can return true to handle it itself. Pages detect the command by the
  method's presence, so an older host is never sent it.
* New `IwayplusScanner.openSettings()`.

## 0.2.2

* New `accel` stream: the raw accelerometer, gravity included, for the page's
  step detector. Samples are in Android's convention (m/s², ~+9.8 on the axis
  pointing up at rest); iOS readings are converted to match. It samples at
  25Hz by default (`accelIntervalMs`) and batches every 100ms
  (`accelFlushIntervalMs`). On Android the batch window is also the sensor's
  report latency, and samples that arrive faster than the configured rate are
  dropped — devices treat the rate as a hint and can deliver several times
  more.
* The bridge bootstrap now lists its streams in `window.__iwayplusScanner.streams`.
  The page reads it before asking for `accel`, so a host built before this
  release is never asked for a stream it cannot run, and the page falls back
  to `devicemotion` there.
* `adapter` state reports `scanning.accel`.
* Android: when GPS delivers no good fix for 5 seconds (`gpsNoFixTimeoutMs`)
  — what being indoors looks like now that the network provider is gone — GPS
  updates are requested every 5 seconds (`gpsBackoffIntervalMs`) instead of
  every `gpsIntervalMs`. A fix worse than 20 m (`gpsGoodAccuracyM`), or with no
  accuracy, backs off at once. Only a good fix restores `gpsIntervalMs`. Every
  fix is still forwarded.
  GPS is never switched off. iOS is unchanged: it has no update interval, and
  Core Location keeps delivering Wi-Fi-assisted fixes indoors.
* New `gpsStatus` event (Android): `{backedOff, intervalMs, reason, timestamp}`,
  emitted when GPS starts and on every backoff or recovery, so the switch is
  visible without waiting for a fix.
* iOS: `CoreMotion` is added to the linked frameworks.
* Android 13+: BLE advertisements are filtered to IwayPlus beacons by a
  hardware `ScanFilter` on the advertised-name prefix (`IW`,
  case-insensitive), so other advertisers never reach the app. The name check
  in the scan callback is removed. Below Android 13, where `ScanFilter` cannot
  match a prefix, scanning is unfiltered and every advertiser is relayed.

## 0.2.1

* Heading now samples at `SENSOR_DELAY_UI` (~16.7Hz) instead of
  `SENSOR_DELAY_GAME` (50Hz). A walking user's heading does not change fast
  enough to need 50 samples a second, and each one cost a sensor wakeup plus
  a matrix remap before the angular filter could discard it. The filter stays:
  the delay is a hint the platform may exceed.
* GPS is GNSS only. The Android network provider is no longer registered —
  it derives a fix from cell towers and Wi-Fi, so indoors it reports the
  building rather than the user, and beacons are the authority once inside.
  `powerState` and the start guard now report on the GPS provider alone, so a
  caller is told the provider is unavailable rather than being started against
  one that delivers nothing.
* No change on iOS for either: Core Location owns the heading rate, and has no
  provider split to remove.

## 0.2.0

* **Breaking:** BLE advertisements are filtered to IwayPlus beacons. Only
  advertisements whose advertised name starts with `IW` (case-insensitive)
  are delivered; everything else is dropped in the scan callback on both
  Android and iOS.
* Consumers that relied on receiving every nearby advertiser no longer do.
  In particular a surrounding-device proximity report built from non-IW
  advertisers will now be empty.
* This saves CPU and platform-channel traffic, not radio power: Android's
  `ScanFilter` cannot match a name by prefix, so the radio still reports
  every advertiser and only the relay is skipped.
* Scanner cores stay in step with `@iwayplus/react-native-scanner` 0.2.0.

## 0.1.0

* Initial release: native BLE, GPS and heading scanning on Android and iOS,
  relayed into the Iwayplus navigation page through `IwayplusNavigation`.
* Scanner cores shared verbatim with `@iwayplus/react-native-scanner` 0.1.1,
  including its iOS BLE delivery fix.
