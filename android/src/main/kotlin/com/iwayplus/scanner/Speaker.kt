package com.iwayplus.scanner

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Speaks for the page.
 *
 * An Android WebView has no speech engine — `window.speechSynthesis` is
 * undefined there — so the navigation page cannot voice its own instructions.
 * It sends the text over the bridge and this speaks it with the device's
 * TextToSpeech engine.
 *
 * Every request is answered with `speech` events carrying the request's `id`
 * and a `state`: `start`, then exactly one of `done`, `stopped` or `error`.
 * The page holds an instruction's slot until one of the last three arrives, so
 * none of them may be dropped.
 */
class Speaker(
  private val context: Context,
  private val sink: ScannerSink,
) {
  private class Request(
    val id: String,
    val text: String,
    val language: String?,
    val rate: Float,
    val voices: List<String>,
  )

  private val main = Handler(Looper.getMainLooper())
  private var engine: TextToSpeech? = null
  private var ready = false

  /** Asked for while the engine was still starting; spoken once it is up. */
  private var waiting: Request? = null

  /**
   * @param requestJson `{id, text, language?, rate?, voices?}`. `rate` is a
   *   multiple of normal speed. `voices` are engine voice names in order of
   *   preference; the first one installed is used.
   */
  fun speak(requestJson: String?) = onMain { speakNow(requestJson) }

  private fun speakNow(requestJson: String?) {
    val json = JSONObject(requestJson ?: "{}")
    val request = Request(
      id = json.optString("id"),
      text = json.optString("text"),
      language = json.optString("language").ifEmpty { null },
      rate = json.optDouble("rate", 1.0).toFloat(),
      voices = json.optJSONArray("voices").toStringList(),
    )
    if (request.text.isBlank()) {
      emit(request.id, "error", "Nothing to speak")
      return
    }

    val current = engine
    if (current != null && ready) {
      start(current, request)
      return
    }

    // Only the newest request survives the wait: an instruction that was
    // replaced before the engine came up is stale by the time it could play.
    waiting?.let { emit(it.id, "stopped") }
    waiting = request
    if (current == null) {
      engine = TextToSpeech(context.applicationContext) { status -> onInit(status) }
    }
  }

  fun stop() = onMain { stopNow() }

  fun shutdown() = onMain {
    stopNow()
    engine?.shutdown()
    engine = null
    ready = false
  }

  private fun stopNow() {
    waiting?.let { emit(it.id, "stopped") }
    waiting = null
    // The utterance being cut off reports `stopped` through the listener.
    engine?.stop()
  }

  /**
   * All state here is main-thread only. The Flutter plugin already calls on
   * the main thread; a React Native module calls from its own.
   */
  private fun onMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
  }

  private fun onInit(status: Int) {
    val current = engine ?: return
    if (status != TextToSpeech.SUCCESS) {
      waiting?.let { emit(it.id, "error", "No text-to-speech engine on this device") }
      waiting = null
      current.shutdown()
      engine = null
      return
    }
    // Navigation guidance ducks music instead of pausing it, and follows the
    // media volume the user already adjusts for maps apps.
    current.setAudioAttributes(
      AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build(),
    )
    current.setOnUtteranceProgressListener(listener)
    ready = true
    waiting?.let { start(current, it) }
    waiting = null
  }

  private fun start(engine: TextToSpeech, request: Request) {
    request.language?.let { engine.setLanguage(Locale.forLanguageTag(it)) }
    if (request.voices.isNotEmpty()) {
      // `voices` can throw on engines that fail to enumerate; the language
      // set above is enough to speak with.
      val installed = runCatching { engine.voices }.getOrNull().orEmpty()
      request.voices
        .firstNotNullOfOrNull { name -> installed.firstOrNull { it.name == name } }
        ?.let { engine.voice = it }
    }
    engine.setSpeechRate(request.rate)
    // QUEUE_FLUSH: the page decides what may interrupt what, and by the time a
    // request arrives it has already chosen to replace whatever is playing.
    val result = engine.speak(request.text, TextToSpeech.QUEUE_FLUSH, null, request.id)
    if (result != TextToSpeech.SUCCESS) emit(request.id, "error", "Speech engine refused")
  }

  // The engine calls back on a binder thread. Events are numbered by the
  // caller's envelope, which is only ever touched from the main thread.
  private val listener = object : UtteranceProgressListener() {
    override fun onStart(utteranceId: String?) = post(utteranceId, "start")

    override fun onDone(utteranceId: String?) = post(utteranceId, "done")

    override fun onStop(utteranceId: String?, interrupted: Boolean) =
      post(utteranceId, "stopped")

    @Deprecated("Deprecated in the platform; still the only callback below API 21")
    override fun onError(utteranceId: String?) = post(utteranceId, "error", "Speech failed")

    override fun onError(utteranceId: String?, errorCode: Int) =
      post(utteranceId, "error", "Speech failed ($errorCode)")
  }

  private fun post(id: String?, state: String, message: String? = null) {
    main.post { emit(id, state, message) }
  }

  private fun emit(id: String?, state: String, message: String? = null) {
    val payload = JSONObject().put("id", id ?: "").put("state", state)
    if (message != null) payload.put("message", message)
    sink.emit("speech", payload.toString())
  }

  private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { optString(it) }.filter { it.isNotEmpty() }
  }
}
