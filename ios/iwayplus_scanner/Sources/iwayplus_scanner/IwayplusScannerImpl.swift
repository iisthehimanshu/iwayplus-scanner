import CoreBluetooth
import CoreLocation
import Foundation
import UIKit

/// Scanning core.
///
/// Deliberately free of React Native types: it emits `(type, payloadJson)`
/// pairs through a closure, exactly as the Android cores emit through
/// `ScannerSink`. The same class can therefore be wrapped by the TurboModule
/// here or by a Flutter plugin, without either copy of the scanning logic
/// drifting from the other.
@objc(IwayplusScannerImpl)
public final class IwayplusScannerImpl: NSObject,
  CBCentralManagerDelegate,
  CLLocationManagerDelegate
{
  /// `(type, payloadJson)` — the envelope is added by the caller.
  @objc public var onEvent: ((String, String) -> Void)?

  private var centralManager: CBCentralManager!
  private var locationManager: CLLocationManager!

  // Tunables, all supplied by the page. See ScannerConfig on the TS side.
  private var flushInterval: TimeInterval = 0.25
  private var timeoutMs: Double?
  private var gpsDistanceFilter: CLLocationDistance = kCLDistanceFilterNone
  private var headingFilterDeg: CLLocationDegrees = 1
  private var maxBufferedReadings = 2000

  // CoreBluetooth delivers one callback per advertisement and, with duplicates
  // allowed, that is a very high rate in a beacon-dense venue. The Flutter
  // plugin this was ported from dispatched each one individually; batching
  // here matches the Android behaviour and keeps the bridge quiet.
  private var buffer: [[String: Any]] = []
  private var dropped = 0
  private var windowStart = Date().timeIntervalSince1970 * 1000
  private var flushTimer: Timer?
  private var timeoutTimer: Timer?

  private var wantsBleScan = false
  private var startGpsAfterAuthorization = false

  @objc public private(set) var isScanningBle = false
  @objc public private(set) var isScanningGps = false
  @objc public private(set) var isScanningHeading = false

  @objc public override init() {
    super.init()
    locationManager = CLLocationManager()
    locationManager.delegate = self
    centralManager = CBCentralManager(
      delegate: self,
      queue: .main,
      options: [CBCentralManagerOptionShowPowerAlertKey: false]
    )
  }

  // MARK: - Configuration

  @objc public func configure(_ configJson: String) {
    guard
      let data = configJson.data(using: .utf8),
      let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    else { return }

    if let value = object["flushIntervalMs"] as? Double {
      flushInterval = min(max(value, 50), 5000) / 1000.0
    }
    if let value = object["timeoutMs"] as? Double, value > 0 {
      timeoutMs = value
    } else if object["timeoutMs"] is NSNull || object["timeoutMs"] == nil {
      timeoutMs = nil
    }
    if let value = object["gpsDistanceFilterM"] as? Double {
      gpsDistanceFilter = value <= 0 ? kCLDistanceFilterNone : value
    }
    if let value = object["headingFilterDeg"] as? Double {
      headingFilterDeg = min(max(value, 0), 45)
    }
    if let value = object["maxBufferedReadings"] as? Int {
      maxBufferedReadings = min(max(value, 100), 20000)
    }

    if isScanningBle {
      restartFlushTimer()
    }
    if isScanningGps {
      locationManager.distanceFilter = gpsDistanceFilter
    }
    if isScanningHeading {
      locationManager.headingFilter = headingFilterDeg
    }
  }

  // MARK: - BLE

  @objc public func startBle() {
    guard centralManager != nil else { return }

    switch centralManager.state {
    case .unauthorized:
      emitError("PERMISSION_DENIED", "Bluetooth permission not granted")
      return
    case .unsupported:
      emitError("UNSUPPORTED", "Bluetooth LE is not supported on this device")
      return
    case .poweredOff:
      // Not a hard failure: `centralManagerDidUpdateState` starts the scan if
      // the user switches Bluetooth on while the page is open.
      wantsBleScan = true
      isScanningBle = true
      emitError("BLUETOOTH_OFF", "Bluetooth is not enabled")
      return
    default:
      break
    }

    wantsBleScan = true
    isScanningBle = true
    windowStart = Date().timeIntervalSince1970 * 1000
    startBleScanIfReady()
    restartFlushTimer()

    if let timeout = timeoutMs {
      timeoutTimer?.invalidate()
      timeoutTimer = Self.mainTimer(interval: timeout / 1000.0, repeats: false) {
        [weak self] _ in
        self?.stopBle()
      }
    }
  }

  @objc public func stopBle() {
    let wasScanning = isScanningBle
    wantsBleScan = false
    isScanningBle = false
    centralManager?.stopScan()
    flushTimer?.invalidate()
    flushTimer = nil
    timeoutTimer?.invalidate()
    timeoutTimer = nil
    if wasScanning { flush() }
    buffer.removeAll()
    dropped = 0
  }

  private func startBleScanIfReady() {
    guard wantsBleScan, centralManager.state == .poweredOn else { return }
    centralManager.scanForPeripherals(
      withServices: nil,
      options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
    )
  }

  private func restartFlushTimer() {
    flushTimer?.invalidate()
    flushTimer = Self.mainTimer(interval: flushInterval, repeats: true) {
      [weak self] _ in
      self?.flush()
    }
  }

  /// A timer on the main run loop in `.common` modes.
  ///
  /// `Timer.scheduledTimer` attaches to the calling thread's run loop, which
  /// never runs on a GCD worker thread, so pinning to main keeps the timer alive
  /// whichever wrapper calls in. `.common` keeps it firing while UIKit tracks a
  /// touch; in the default mode BLE batches would pause for as long as the user
  /// pans the map.
  private static func mainTimer(
    interval: TimeInterval,
    repeats: Bool,
    _ block: @escaping (Timer) -> Void
  ) -> Timer {
    let timer = Timer(timeInterval: interval, repeats: repeats, block: block)
    RunLoop.main.add(timer, forMode: .common)
    return timer
  }

  public func centralManagerDidUpdateState(_ central: CBCentralManager) {
    switch central.state {
    case .poweredOn:
      startBleScanIfReady()
    case .poweredOff:
      central.stopScan()
    default:
      break
    }
    emitState()
  }

  public func centralManager(
    _ central: CBCentralManager,
    didDiscover peripheral: CBPeripheral,
    advertisementData: [String: Any],
    rssi RSSI: NSNumber
  ) {
    guard isScanningBle else { return }
    guard buffer.count < maxBufferedReadings else {
      dropped += 1
      return
    }

    let name = peripheral.name
      ?? advertisementData[CBAdvertisementDataLocalNameKey] as? String
      ?? ""

    // The first two bytes are the company identifier, which the Android
    // `manufacturerSpecificData` accessor already strips. Dropping them here
    // keeps the hex string identical across platforms.
    var manufacturerHex = ""
    if let data = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data,
       data.count > 2 {
      manufacturerHex = data.dropFirst(2).map { String(format: "%02X", $0) }.joined()
    }

    buffer.append([
      // iOS never exposes the hardware address; the peripheral UUID is stable
      // per install, which is what the consumer keys readings on.
      "device": peripheral.identifier.uuidString,
      "name": name,
      "rssi": RSSI.intValue,
      "timestamp": Int(Date().timeIntervalSince1970 * 1000),
      "manufacturerHex": manufacturerHex,
    ])
  }

  private func flush() {
    let now = Date().timeIntervalSince1970 * 1000
    guard !buffer.isEmpty || dropped > 0 else {
      windowStart = now
      return
    }
    let payload: [String: Any] = [
      "readings": buffer,
      "from": Int(windowStart),
      "to": Int(now),
      "dropped": dropped,
    ]
    buffer.removeAll(keepingCapacity: true)
    dropped = 0
    windowStart = now
    emit("ble", payload)
  }

  // MARK: - Location

  @objc public func startGps() {
    switch locationManager.authorizationStatus {
    case .notDetermined:
      startGpsAfterAuthorization = true
      locationManager.requestWhenInUseAuthorization()
      return
    case .restricted, .denied:
      emitError("PERMISSION_DENIED", "Location permission not granted")
      return
    default:
      break
    }

    locationManager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
    locationManager.distanceFilter = gpsDistanceFilter
    locationManager.activityType = .otherNavigation
    locationManager.pausesLocationUpdatesAutomatically = false
    // Foreground only by design — the WebView's JS is suspended in the
    // background, so readings gathered there would have nowhere to go.
    locationManager.startUpdatingLocation()
    isScanningGps = true
  }

  @objc public func stopGps() {
    startGpsAfterAuthorization = false
    isScanningGps = false
    locationManager?.stopUpdatingLocation()
  }

  @objc public func startHeading() {
    guard CLLocationManager.headingAvailable() else {
      emitError("NO_COMPASS", "No magnetometer on this device")
      return
    }
    locationManager.headingFilter = headingFilterDeg
    locationManager.headingOrientation = headingOrientation()
    locationManager.startUpdatingHeading()
    isScanningHeading = true
  }

  @objc public func stopHeading() {
    isScanningHeading = false
    locationManager?.stopUpdatingHeading()
  }

  public func locationManager(
    _ manager: CLLocationManager,
    didUpdateLocations locations: [CLLocation]
  ) {
    guard let location = locations.last else { return }
    emit("gps", [
      "latitude": location.coordinate.latitude,
      "longitude": location.coordinate.longitude,
      "accuracy": location.horizontalAccuracy,
      "bearing": location.course,
      "altitude": location.altitude,
      "speed": location.speed,
      "timestamp": Int(location.timestamp.timeIntervalSince1970 * 1000),
    ])
  }

  public func locationManager(
    _ manager: CLLocationManager,
    didUpdateHeading newHeading: CLHeading
  ) {
    // A negative accuracy means the reading is unreliable, which the consumer
    // needs to know rather than have hidden behind a plausible-looking angle.
    emit("heading", [
      "heading": newHeading.magneticHeading,
      "accuracy": newHeading.headingAccuracy,
      "timestamp": Int(newHeading.timestamp.timeIntervalSince1970 * 1000),
    ])
  }

  public func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
    emitError("LOCATION_ERROR", error.localizedDescription)
  }

  public func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
    let status = manager.authorizationStatus
    if startGpsAfterAuthorization,
       status == .authorizedWhenInUse || status == .authorizedAlways {
      startGpsAfterAuthorization = false
      startGps()
    }
    emitState()
  }

  private func headingOrientation() -> CLDeviceOrientation {
    switch UIDevice.current.orientation {
    case .landscapeLeft: return .landscapeLeft
    case .landscapeRight: return .landscapeRight
    case .portraitUpsideDown: return .portraitUpsideDown
    default: return .portrait
    }
  }

  // MARK: - State

  @objc public func stopAll() {
    stopBle()
    stopGps()
    stopHeading()
  }

  @objc public func stateJson() -> String {
    let bluetooth: String
    switch centralManager?.state {
    case .poweredOn: bluetooth = "on"
    case .poweredOff: bluetooth = "off"
    case .unauthorized: bluetooth = "unauthorized"
    case .unsupported: bluetooth = "unsupported"
    default: bluetooth = "unknown"
    }

    let status = locationManager?.authorizationStatus
    let locationGranted = status == .authorizedWhenInUse || status == .authorizedAlways
    let location: String
    switch status {
    case .authorizedWhenInUse, .authorizedAlways:
      location = CLLocationManager.locationServicesEnabled() ? "on" : "off"
    case .denied, .restricted: location = "unauthorized"
    case .notDetermined: location = "unknown"
    default: location = "unknown"
    }

    return Self.serialize([
      "bluetooth": bluetooth,
      "location": location,
      "permissions": [
        "bluetooth": bluetooth != "unauthorized" && bluetooth != "unsupported",
        "location": locationGranted,
      ],
      "scanning": [
        "ble": isScanningBle,
        "gps": isScanningGps,
        "heading": isScanningHeading,
      ],
    ])
  }

  @objc public func helloJson() -> String {
    Self.serialize([
      "moduleVersion": "0.1.1",
      "platform": "ios",
      "osVersion": UIDevice.current.systemVersion,
    ])
  }

  private func emitState() {
    onEvent?("adapter", stateJson())
  }

  private func emit(_ type: String, _ payload: [String: Any]) {
    onEvent?(type, Self.serialize(payload))
  }

  private func emitError(_ code: String, _ message: String) {
    emit("error", ["code": code, "message": message])
  }

  private static func serialize(_ value: [String: Any]) -> String {
    guard
      let data = try? JSONSerialization.data(withJSONObject: value),
      let json = String(data: data, encoding: .utf8)
    else {
      return "{}"
    }
    return json
  }
}
