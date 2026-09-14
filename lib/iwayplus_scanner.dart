/// Native BLE, GPS and heading scanning for a Flutter host app, relayed into
/// the Iwayplus navigation page running in a WebView.
///
/// Most apps only need [IwayplusNavigation]. [IwayplusScanner] is for apps that
/// want the scan streams for their own purposes.
library;

export 'src/bridge_script.dart' show bridgeBootstrap, relayStatement;
export 'src/iwayplus_navigation.dart';
export 'src/scanner.dart';
export 'src/types.dart';
