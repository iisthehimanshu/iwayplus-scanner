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
