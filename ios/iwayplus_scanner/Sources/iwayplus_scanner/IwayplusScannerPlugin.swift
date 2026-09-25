import Flutter
import UIKit

/// The Flutter surface of the scanner.
///
/// The Flutter counterpart of react-native-scanner's `IwayplusScanner.mm`:
/// `IwayplusScannerImpl.swift` is shared with that package verbatim, and this
/// class only adds the envelope and the channels.
///
/// Flutter delivers method calls on the main thread, which is where the core
/// keeps its state and its timers. That is why this wrapper needs none of the
/// main-queue hopping the React Native shim does.
public final class IwayplusScannerPlugin: NSObject, FlutterPlugin, FlutterStreamHandler {
  static let moduleVersion = "0.1.0"
  static let protocolVersion = 1

  /// Created on first use rather than at registration: constructing
  /// `CBCentralManager` is what raises the Bluetooth permission prompt, and a
  /// host app should not see that at launch just for linking this plugin.
  private var implStorage: IwayplusScannerImpl?
  private var eventSink: FlutterEventSink?
  private var sequence: Int64 = 0
  private var announced = false

  public static func register(with registrar: FlutterPluginRegistrar) {
    let instance = IwayplusScannerPlugin()
    let methods = FlutterMethodChannel(
      name: "iwayplus_scanner", binaryMessenger: registrar.messenger())
    registrar.addMethodCallDelegate(instance, channel: methods)
    let events = FlutterEventChannel(
      name: "iwayplus_scanner/events", binaryMessenger: registrar.messenger())
    events.setStreamHandler(instance)
  }

  private var impl: IwayplusScannerImpl {
    if let impl = implStorage { return impl }
    let impl = IwayplusScannerImpl()
    impl.onEvent = { [weak self] type, payloadJson in
      self?.send(type, payloadJson)
    }
    implStorage = impl
    return impl
  }

  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    switch call.method {
    case "configure":
      impl.configure(call.arguments as? String ?? "{}")
      result(nil)
    case "startBle":
      announceOnce()
      impl.startBle()
      result(nil)
    case "stopBle":
      implStorage?.stopBle()
      result(nil)
    case "startGps":
      announceOnce()
      impl.startGps()
      result(nil)
    case "stopGps":
      implStorage?.stopGps()
      result(nil)
    case "startHeading":
      announceOnce()
      impl.startHeading()
      result(nil)
    case "stopHeading":
      implStorage?.stopHeading()
      result(nil)
    case "startAccel":
      announceOnce()
      impl.startAccel()
      result(nil)
    case "stopAccel":
      implStorage?.stopAccel()
      result(nil)
    case "stopAll":
      implStorage?.stopAll()
      // A sequence reset reads as "fresh session" downstream, which is what a
      // full stop is.
      sequence = 0
      announced = false
      result(nil)
    case "getState":
      let state = impl.stateJson()
      send("adapter", state)
      result(state)
    case "requestPermissions":
      // iOS raises its own prompts on first use of CoreBluetooth and
      // CoreLocation, driven by the host's Info.plist usage strings.
      result(true)
    default:
      result(FlutterMethodNotImplemented)
    }
  }

  public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink)
    -> FlutterError?
  {
    eventSink = events
    return nil
  }

  public func onCancel(withArguments arguments: Any?) -> FlutterError? {
    eventSink = nil
    return nil
  }

  public func detachFromEngine(for registrar: FlutterPluginRegistrar) {
    implStorage?.stopAll()
    implStorage?.onEvent = nil
    implStorage = nil
    eventSink = nil
  }

  private func announceOnce() {
    guard !announced else { return }
    announced = true
    let hello: [String: Any] = [
      "moduleVersion": Self.moduleVersion,
      "platform": "ios",
      "osVersion": UIDevice.current.systemVersion,
    ]
    if let data = try? JSONSerialization.data(withJSONObject: hello),
      let json = String(data: data, encoding: .utf8)
    {
      send("hello", json)
    }
    send("adapter", impl.stateJson())
  }

  /// Built by string concatenation: the payload is valid JSON produced a
  /// moment earlier, and this runs on the BLE flush path several times a second.
  private func send(_ type: String, _ payloadJson: String) {
    sequence += 1
    let millis = Int64(Date().timeIntervalSince1970 * 1000)
    let envelope =
      "{\"v\":\(Self.protocolVersion),\"seq\":\(sequence),\"t\":\(millis),"
      + "\"type\":\"\(type)\",\"payload\":\(payloadJson)}"
    if Thread.isMainThread {
      eventSink?(envelope)
    } else {
      DispatchQueue.main.async { [weak self] in self?.eventSink?(envelope) }
    }
  }
}
