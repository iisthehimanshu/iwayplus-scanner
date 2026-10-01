import AVFoundation
import Foundation

/// Speaks for the page.
///
/// The navigation page sends its spoken instructions over the bridge instead
/// of using the WebView's own speech engine. On Android there is no such
/// engine at all; here there is one, but it will not speak until the user has
/// tapped the page, it is silenced by the ring/silent switch, and it cannot
/// duck music. Speaking natively keeps both platforms behaving the same.
///
/// Every request is answered with `speech` events carrying the request's `id`
/// and a `state`: `start`, then exactly one of `done`, `stopped` or `error`.
/// The page holds an instruction's slot until one of the last three arrives.
final class IwayplusSpeaker: NSObject, AVSpeechSynthesizerDelegate {
  private let synthesizer = AVSpeechSynthesizer()
  private var ids: [ObjectIdentifier: String] = [:]
  private let emit: (String, String) -> Void

  /// - Parameter emit: `(type, payloadJson)`, the same shape the scanners use.
  init(emit: @escaping (String, String) -> Void) {
    self.emit = emit
    super.init()
    synthesizer.delegate = self
  }

  /// - Parameter requestJson: `{id, text, language?, rate?}`. `rate` is a
  ///   multiple of normal speed.
  func speak(_ requestJson: String) {
    let parsed = try? JSONSerialization.jsonObject(with: Data(requestJson.utf8))
    let request = parsed as? [String: Any] ?? [:]
    let id = request["id"].map { "\($0)" } ?? ""
    let text = request["text"] as? String ?? ""
    guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
      report(id, "error", "Nothing to speak")
      return
    }

    // The page decides what may interrupt what, and by the time a request
    // arrives it has already chosen to replace whatever is playing. The
    // utterance being cut off reports `stopped` through the delegate.
    if synthesizer.isSpeaking {
      synthesizer.stopSpeaking(at: .immediate)
    }

    let utterance = AVSpeechUtterance(string: text)
    if let language = request["language"] as? String,
      let voice = AVSpeechSynthesisVoice(language: language)
    {
      utterance.voice = voice
    }
    let rate = (request["rate"] as? NSNumber)?.floatValue ?? 1
    utterance.rate = min(
      max(AVSpeechUtteranceDefaultSpeechRate * rate, AVSpeechUtteranceMinimumSpeechRate),
      AVSpeechUtteranceMaximumSpeechRate
    )
    ids[ObjectIdentifier(utterance)] = id
    activateSession()
    synthesizer.speak(utterance)
  }

  func stop() {
    synthesizer.stopSpeaking(at: .immediate)
  }

  /// `playback` is heard with the ring/silent switch off, which a navigation
  /// instruction has to be. `duckOthers` lowers music rather than stopping it.
  private func activateSession() {
    let session = AVAudioSession.sharedInstance()
    try? session.setCategory(.playback, mode: .voicePrompt, options: [.duckOthers])
    try? session.setActive(true)
  }

  /// Hands the session back so ducked music comes up again.
  private func releaseSessionIfIdle() {
    guard !synthesizer.isSpeaking else { return }
    try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
  }

  func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didStart utterance: AVSpeechUtterance) {
    report(ids[ObjectIdentifier(utterance)] ?? "", "start")
  }

  func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
    finish(utterance, "done")
  }

  func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
    finish(utterance, "stopped")
  }

  private func finish(_ utterance: AVSpeechUtterance, _ state: String) {
    let id = ids.removeValue(forKey: ObjectIdentifier(utterance)) ?? ""
    report(id, state)
    releaseSessionIfIdle()
  }

  private func report(_ id: String, _ state: String, _ message: String? = nil) {
    var payload: [String: Any] = ["id": id, "state": state]
    if let message = message { payload["message"] = message }
    guard
      let data = try? JSONSerialization.data(withJSONObject: payload),
      let json = String(data: data, encoding: .utf8)
    else { return }
    emit("speech", json)
  }
}
