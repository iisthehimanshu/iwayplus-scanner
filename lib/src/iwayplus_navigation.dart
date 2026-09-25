import 'dart:async';
import 'dart:convert';

import 'package:flutter/widgets.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:webview_flutter_android/webview_flutter_android.dart';
import 'package:webview_flutter_wkwebview/webview_flutter_wkwebview.dart';

import 'bridge_script.dart';
import 'scanner.dart';
import 'types.dart';

/// Hosts the Iwayplus navigation page and relays native scan data into it.
///
/// Scanning is driven entirely by the page: nothing starts until it sends a
/// `start` command. That keeps the radios idle while the bundle is still
/// loading, and means the host never has to guess what positioning needs.
///
/// Showing this widget opens navigation and removing it ends it — removal
/// stops every stream. Give it bounded constraints: like any WebView it cannot
/// size itself inside a `Column` or a scroll view.
///
/// Reach [IwayplusNavigationState.reload] and
/// [IwayplusNavigationState.stopScanning] through a `GlobalKey`.
class IwayplusNavigation extends StatefulWidget {
  const IwayplusNavigation({
    super.key,
    required this.url,
    this.config,
    this.autoRequestPermissions = true,
    this.startSuspended = false,
    this.onClose,
    this.onCommand,
    this.onPermissionResult,
  });

  /// URL of the hosted navigation page, with its parameters.
  final String url;

  /// Scanner tunables, applied when the page reports it is ready.
  final ScannerConfig? config;

  /// Request Android runtime permissions on first build (default true). Set
  /// false if the app runs its own permission flow — but grant them before
  /// showing this widget either way.
  final bool autoRequestPermissions;

  /// Start with scanning suspended (default false).
  ///
  /// For a host that pre-warms this page off screen: the page boots and asks
  /// to scan long before anyone looks at it, and a request granted then would
  /// hold the radios open behind a hidden view. Suspended, the streams the
  /// page asks for are recorded but not started, so [resumeScanning] can begin
  /// them the moment the map is actually shown — with no window in which they
  /// ran unwatched.
  final bool startSuspended;

  /// The page asked to be dismissed.
  final VoidCallback? onClose;

  /// A command the bridge does not recognise. Return true if the app handled
  /// it. Use this for app-level intents — sharing, booking, deep links.
  final bool Function(Map<String, dynamic> command)? onCommand;

  /// Outcome of the automatic permission request.
  final ValueChanged<bool>? onPermissionResult;

  @override
  State<IwayplusNavigation> createState() => IwayplusNavigationState();
}

class IwayplusNavigationState extends State<IwayplusNavigation>
    with WidgetsBindingObserver {
  late final WebViewController _controller;
  StreamSubscription<String>? _relay;

  /// Streams the page has started. Restarted on resume, since backgrounding
  /// stops them.
  final Set<ScannerStream> _running = {};

  /// While true, streams the page asks for are recorded but not started.
  late bool _suspended = widget.startSuspended;

  /// Reloads the page. Scanning state is kept; the page re-requests streams.
  Future<void> reload() => _controller.reload();

  /// Stops every stream without closing the page.
  ///
  /// Forgets what was running, so nothing resumes on its own. Use
  /// [pauseScanning] when the page is only being hidden.
  Future<void> stopScanning() {
    _running.clear();
    return IwayplusScanner.stopAll();
  }

  /// Suspends the streams but remembers them, so [resumeScanning] can put the
  /// page back exactly as it was.
  ///
  /// This is what a host needs when the map is kept alive off screen: a
  /// pre-warmed page has booted and asked to scan long before anyone looks at
  /// it, and it must not hold the radios open in the meantime. Deliberately
  /// mirrors [didChangeAppLifecycleState], which already does this on every
  /// backgrounding — which is why it keeps [_running] rather than clearing it.
  Future<void> pauseScanning() {
    _suspended = true;
    if (_running.isEmpty) return Future<void>.value();
    return IwayplusScanner.stop(_running);
  }

  /// Restarts whatever [pauseScanning] suspended. A no-op if the page never
  /// started a stream.
  Future<void> resumeScanning() {
    _suspended = false;
    if (_running.isEmpty) return Future<void>.value();
    return IwayplusScanner.start(_running);
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);

    // Subscribed before the page loads, so nothing emitted by an early start
    // is missed. Envelopes are forwarded as strings, never parsed here.
    _relay = IwayplusScanner.rawEvents.listen(
      (json) => _run(relayStatement(json)),
      onError: (Object error) => debugPrint('Iwayplus scanner stream: $error'),
    );

    _controller = _buildController()..loadRequest(Uri.parse(widget.url));

    if (widget.autoRequestPermissions) {
      requestScannerPermissions().then((granted) {
        if (mounted) widget.onPermissionResult?.call(granted);
      });
    }
  }

  WebViewController _buildController() {
    final PlatformWebViewControllerCreationParams params =
        WebViewPlatform.instance is WebKitWebViewPlatform
        ? WebKitWebViewControllerCreationParams(
            allowsInlineMediaPlayback: true,
            mediaTypesRequiringUserAction: const <PlaybackMediaTypes>{},
          )
        : const PlatformWebViewControllerCreationParams();

    final controller = WebViewController.fromPlatformCreationParams(params)
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..addJavaScriptChannel(
        hostChannelName,
        onMessageReceived: (message) => _handleCommand(message.message),
      )
      ..setNavigationDelegate(
        NavigationDelegate(
          // Injected twice on purpose: onPageStarted is the earliest point
          // available, and onPageFinished covers a WebView that discards
          // scripts run before the new document exists. The script ignores a
          // second run.
          onPageStarted: (_) => _run(bridgeBootstrap),
          onPageFinished: (_) => _run(bridgeBootstrap),
        ),
      );

    final platform = controller.platform;
    if (platform is AndroidWebViewController) {
      platform
        ..setMediaPlaybackRequiresUserGesture(false)
        // The page falls back to browser geolocation when the bridge is
        // absent, and uses it as a coarse first fix even when it is present.
        // The app-level location permission is what actually gates it.
        ..setGeolocationPermissionsPromptCallbacks(
          onShowPrompt: (_) async =>
              const GeolocationPermissionsResponse(allow: true, retain: false),
        );
    }
    return controller;
  }

  Future<void> _run(String javascript) async {
    try {
      await _controller.runJavaScript(javascript);
    } catch (_) {
      // No document yet, or the page is mid-navigation. The next navigation
      // callback injects again, and a dropped envelope is recoverable — the
      // page tolerates sequence gaps.
    }
  }

  void _handleCommand(String message) {
    final Map<String, dynamic> command;
    try {
      final decoded = jsonDecode(message);
      if (decoded is! Map) return;
      command = Map<String, dynamic>.from(decoded);
    } on FormatException {
      return;
    }

    switch (command['cmd']) {
      case 'ready':
        final config = widget.config;
        if (config != null) unawaited(IwayplusScanner.configure(config));
        unawaited(IwayplusScanner.getState());
      case 'configure':
        final config = command['config'];
        if (config is Map) {
          unawaited(
            IwayplusScanner.configure(
              ScannerConfig.fromJson(Map<String, dynamic>.from(config)),
            ),
          );
        }
      case 'start':
        final streams = _streamsOf(command);
        _running.addAll(streams);
        // Recorded either way, so resuming restores exactly what the page
        // asked for; only actually started when something is watching.
        if (!_suspended) unawaited(IwayplusScanner.start(streams));
      case 'stop':
        final streams = _streamsOf(command);
        _running.removeAll(streams);
        unawaited(IwayplusScanner.stop(streams));
      case 'stopAll':
        _running.clear();
        unawaited(IwayplusScanner.stopAll());
      case 'getState':
        unawaited(IwayplusScanner.getState());
      case 'close':
        widget.onClose?.call();
      default:
        widget.onCommand?.call(command);
    }
  }

  List<ScannerStream> _streamsOf(Map<String, dynamic> command) => [
    for (final name in (command['streams'] as List? ?? const []))
      ?ScannerStream.byName(name),
  ];

  /// Scanning is foreground-only by design. Backgrounding suspends the
  /// WebView's JavaScript anyway, so readings gathered there would have nowhere
  /// to go and would only drain the battery.
  ///
  /// Keyed on `paused` rather than `inactive`: iOS reports `inactive` while a
  /// system permission prompt is on screen, which is exactly when scanning
  /// should keep its place.
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (_running.isEmpty) return;
    switch (state) {
      case AppLifecycleState.resumed:
        // A suspended page stays suspended across backgrounding: coming back
        // to the app is not the same as looking at the map.
        if (!_suspended) unawaited(IwayplusScanner.start(_running));
      case AppLifecycleState.paused:
        unawaited(IwayplusScanner.stop(_running));
      default:
        break;
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    unawaited(_relay?.cancel());
    _running.clear();
    unawaited(IwayplusScanner.stopAll());
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => WebViewWidget(controller: _controller);
}
