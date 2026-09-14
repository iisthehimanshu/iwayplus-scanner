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
      if (handler) {
        try { handler(event, json); } catch (e) {}
      } else {
        // Bounded: if the page never attaches a handler we must not grow
        // without limit while scanning runs.
        if (queue.length > 200) queue.shift();
        queue.push(event);
      }
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
    close: function () { return this.send({ cmd: 'close' }); }
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
