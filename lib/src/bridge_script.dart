import 'dart:convert';

/// Name of the JavaScript channel the page's commands arrive on.
const String hostChannelName = 'IwayplusScannerHost';

/// Injected into the page so `window.__iwayplusScanner` exists by the time the
/// navigation bundle looks for it.
///
/// Identical in behaviour to `BRIDGE_BOOTSTRAP` in
/// `@iwayplus/react-native-scanner` — the page cannot tell which host it is
/// in. Only the transport differs: commands go out through Flutter's
/// JavaScript channel rather than `window.ReactNativeWebView`.
///
/// `webview_flutter` cannot inject at document start, so this runs from the
/// navigation callbacks and may land after the page's own scripts. That is
/// safe: the page checks for an existing bridge first and otherwise waits for
/// the `iwayplusscannerready` event, and the guard on the first line makes a
/// second injection a no-op.
const String bridgeBootstrap =
    '''
(function () {
  if (window.__iwayplusScanner) return;

  var queue = [];
  var handler = null;

  window.__iwayplusScanner = {
    available: true,
    protocolVersion: 1,
    /**
     * The streams this host can run. The page checks it before asking for
     * one, so a host built before a stream existed is never asked for it.
     */
    streams: ['ble', 'gps', 'heading', 'accel'],

    /**
     * Whether the device's screen reader (TalkBack / VoiceOver) is on. null
     * until the host has said. A page cannot detect this itself; it uses it to
     * choose between a screen-reader announcement and speaking aloud.
     */
    screenReader: null,

    /**
     * The page assigns this. Events that arrive before it is set are queued,
     * because the native side can emit while the Dart bundle is still starting.
     */
    set onEvent(fn) {
      handler = fn;
      if (typeof fn === 'function') {
        var pending = queue;
        queue = [];
        for (var i = 0; i < pending.length; i++) {
          try { fn(pending[i]); } catch (e) {}
        }
      }
    },
    get onEvent() { return handler; },

    /** Called by the relay. Not part of the page-facing API. */
    __receive: function (json) {
      var event;
      try {
        event = JSON.parse(json);
      } catch (e) {
        return;
      }
      // Speech progress is for whoever asked for the speech. The scan handler
      // below still sees the event, so its sequence numbers stay unbroken.
      if (event && event.type === 'speech') {
        try {
          window.dispatchEvent(new CustomEvent('iwayplusspeech', { detail: event.payload }));
        } catch (e) {}
      }
      if (handler) {
        try { handler(event, json); } catch (e) {}
      } else {
        // Bounded: if the page never attaches a handler we must not grow
        // without limit while scanning runs.
        if (queue.length > 200) queue.shift();
        queue.push(event);
      }
    },

    /** Called by the host. Not part of the page-facing API. */
    __setScreenReader: function (on) {
      this.screenReader = !!on;
      try {
        window.dispatchEvent(new CustomEvent('iwayplusscreenreader', { detail: this.screenReader }));
      } catch (e) {}
    },

    /** Send a command object to the host app. */
    send: function (command) {
      var host = window.$hostChannelName;
      if (!host || typeof host.postMessage !== 'function') return false;
      host.postMessage(JSON.stringify(command));
      return true;
    },

    configure: function (config) { return this.send({ cmd: 'configure', config: config }); },
    start: function (streams) { return this.send({ cmd: 'start', streams: streams }); },
    stop: function (streams) { return this.send({ cmd: 'stop', streams: streams }); },
    stopAll: function () { return this.send({ cmd: 'stopAll' }); },
    getState: function () { return this.send({ cmd: 'getState' }); },
    ready: function () { return this.send({ cmd: 'ready' }); },
    /**
     * Opens the host app's settings page. The page calls this from its
     * "permission required" prompt: the permissions are the host's, so no
     * browser API inside the WebView can reach them.
     */
    openSettings: function () { return this.send({ cmd: 'openSettings' }); },
    close: function () { return this.send({ cmd: 'close' }); },

    /**
     * Speaks with the device's own speech engine. A WebView either has none
     * (Android) or will not use it without a tap (iOS).
     *
     * request: { id, text, language?, rate?, voices? }. `rate` is a multiple
     * of normal speed; `voices` are engine voice names in order of preference.
     * Progress arrives as `iwayplusspeech` window events whose detail is
     * { id, state }: `start`, then one of `done`, `stopped` or `error`.
     */
    speak: function (request) {
      var command = { cmd: 'speak' };
      for (var key in request) command[key] = request[key];
      return this.send(command);
    },
    stopSpeaking: function () { return this.send({ cmd: 'stopSpeaking' }); }
  };

  window.dispatchEvent(new Event('iwayplusscannerready'));
})();
''';

/// Wraps an envelope as a statement to run in the page.
String relayStatement(String json) {
  // jsonEncode produces a valid JS string literal, except that U+2028 and
  // U+2029 are legal inside JSON but end a line in JS source — they have to be
  // escaped or the statement is a syntax error.
  final literal = jsonEncode(
    json,
  ).replaceAll('\u2028', r'\u2028').replaceAll('\u2029', r'\u2029');
  return 'window.__iwayplusScanner && window.__iwayplusScanner.__receive($literal);';
}

/// Tells the page whether the device's screen reader is on.
///
/// Guarded twice: a page without the bridge, and a page whose bridge was
/// bootstrapped by an older copy of this package.
String screenReaderStatement(bool on) =>
    'window.__iwayplusScanner && window.__iwayplusScanner.__setScreenReader && '
    'window.__iwayplusScanner.__setScreenReader($on);';
