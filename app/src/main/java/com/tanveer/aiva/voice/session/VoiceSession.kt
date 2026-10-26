package com.tanveer.aiva.voice.session

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.tanveer.aiva.data.remote.inputLevelFromRms
import com.tanveer.aiva.domain.models.VoiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

interface VoiceSessionController {
    val state: StateFlow<VoiceState>
    val inputLevel: StateFlow<Float>
    val outputLevel: StateFlow<Float>
    val detail: StateFlow<String>
    val voices: StateFlow<List<String>>
    val isConnected: Boolean
    fun startListening()
    fun stopListening()
    fun setContinuousMode(enabled: Boolean)
    fun setMuted(muted: Boolean)
    fun markProcessing()
    fun speak(text: String, voiceName: String, rate: Float)
    fun interrupt()
    fun fail(message: String)
    fun pause()
    fun shutdown()
}

/**
 * Native speech recognition and on-device text-to-speech for AIVA Voice.
 * Continuous mode is opt-in and only remains active for the foreground voice session.
 * Output level stays at 0 because Android TTS does not expose amplitude.
 */
class DeviceVoiceSession(
    context: Context,
    private val onFinalSpeech: (String) -> Unit,
) : VoiceSessionController {
    private val appContext = context.applicationContext
    private val mainLooper = android.os.Looper.getMainLooper()
    private val _state = MutableStateFlow(VoiceState.Idle)
    override val state: StateFlow<VoiceState> = _state.asStateFlow()
    private val _inputLevel = MutableStateFlow(0f)
    override val inputLevel: StateFlow<Float> = _inputLevel.asStateFlow()
    private val _outputLevel = MutableStateFlow(0f)
    override val outputLevel: StateFlow<Float> = _outputLevel.asStateFlow()
    private val _detail = MutableStateFlow("")
    override val detail: StateFlow<String> = _detail.asStateFlow()
    private val _voices = MutableStateFlow<List<String>>(listOf(DEVICE_DEFAULT))
    override val voices: StateFlow<List<String>> = _voices.asStateFlow()
    override val isConnected: Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var continuousMode = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var shutDown = false
    /** Id of the utterance whose callbacks may still change state; TTS callbacks arrive on another thread. */
    @Volatile private var activeUtteranceId: String? = null

    init {
        if (!isConnected) {
            _state.value = VoiceState.Error
            _detail.value = "Speech recognition isn’t available on this phone."
        }
        tts = TextToSpeech(appContext) { status ->
            if (shutDown) return@TextToSpeech
            ttsReady = status == TextToSpeech.SUCCESS
            if (!ttsReady) {
                _detail.value = "Text-to-speech didn’t start. You can still read replies in the chat."
                return@TextToSpeech
            }
            val engine = tts ?: return@TextToSpeech
            val names = runCatching {
                engine.voices.orEmpty()
                    .mapNotNull { voice -> voice.name?.takeIf { it.isNotBlank() } }
                    .distinct()
                    .sorted()
            }.getOrDefault(emptyList())
            _voices.value = listOf(DEVICE_DEFAULT) + names
        }
    }

    fun start() {
        startListening()
    }

    fun end() {
        shutdown()
    }

    fun preview(state: VoiceState) {
        _state.value = state
    }

    override fun setContinuousMode(enabled: Boolean) {
        continuousMode = enabled && !shutDown
        if (!continuousMode && _state.value == VoiceState.Listening) {
            listening = false
            runOnMain { recognizer?.cancel() }
            _inputLevel.value = 0f
        }
    }

    override fun startListening() {
        if (shutDown) return
        if (!isConnected) {
            fail("Speech recognition isn’t available on this phone.")
            return
        }
        interruptSpeechOnly()
        val recognizer = recognizer ?: SpeechRecognizer.createSpeechRecognizer(appContext).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        listening = true
        _inputLevel.value = 0f
        _detail.value = ""
        _state.value = VoiceState.Listening
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runOnMain { recognizer?.startListening(intent) }
    }

    override fun stopListening() {
        if (!listening) return
        runOnMain { recognizer?.stopListening() }
    }

    override fun setMuted(muted: Boolean) {
        if (muted) {
            listening = false
            runOnMain { recognizer?.cancel() }
            _inputLevel.value = 0f
            _state.value = VoiceState.Muted
            _detail.value = VoiceState.Muted.hint
        } else if (_state.value == VoiceState.Muted) {
            _state.value = VoiceState.Idle
            _detail.value = if (continuousMode) "Listening will resume." else "Tap start to listen."
            if (continuousMode) startListening()
        }
    }

    override fun markProcessing() {
        listening = false
        _inputLevel.value = 0f
        _state.value = VoiceState.Processing
        _detail.value = ""
    }

    override fun speak(text: String, voiceName: String, rate: Float) {
        val engine = tts
        if (!ttsReady || engine == null) {
            _state.value = VoiceState.Idle
            _detail.value = if (text.isBlank()) "No reply to read aloud." else "Text-to-speech isn’t available. The reply is in your chat."
            if (continuousMode && !shutDown) startListening()
            return
        }
        if (text.isBlank()) {
            _state.value = VoiceState.Idle
            _detail.value = "The model didn’t return anything to read aloud."
            if (continuousMode && !shutDown) startListening()
            return
        }
        if (voiceName.isNotBlank() && voiceName != DEVICE_DEFAULT) {
            engine.voices?.firstOrNull { it.name == voiceName }?.let { engine.voice = it }
        }
        engine.setSpeechRate(rate.coerceIn(0.5f, 1.6f))
        _outputLevel.value = 0f
        // Speaking begins only when the engine reports onStart; until then the session stays in
        // its current (Processing) state. Callbacks are matched to the current utterance so a
        // flushed or interrupted utterance cannot change the state of a newer one.
        val id = UUID.randomUUID().toString()
        activeUtteranceId = id
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId != activeUtteranceId || shutDown) return
                _state.value = VoiceState.Speaking
            }
            override fun onDone(utteranceId: String?) {
                if (utteranceId != activeUtteranceId) return
                activeUtteranceId = null
                // Some engines report onDone without a preceding onStart for very short replies.
                val current = _state.value
                if (current == VoiceState.Speaking || current == VoiceState.Processing) {
                    if (continuousMode && !shutDown) {
                        startListening()
                    } else {
                        _state.value = VoiceState.Idle
                    }
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId != activeUtteranceId) return
                fail("The phone couldn’t speak that reply.")
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId != activeUtteranceId) return
                fail("The phone couldn’t speak that reply.")
            }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (utteranceId == activeUtteranceId) activeUtteranceId = null
            }
        })
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result == TextToSpeech.ERROR) fail("The phone couldn’t speak that reply.")
    }

    override fun interrupt() {
        val wasSpeakingOrProcessing = _state.value == VoiceState.Speaking || _state.value == VoiceState.Processing
        interruptSpeechOnly()
        if (listening) {
            stopListening()
        } else if (wasSpeakingOrProcessing) {
            _state.value = VoiceState.Idle
            if (continuousMode && !shutDown) startListening()
        }
    }

    override fun fail(message: String) {
        activeUtteranceId = null
        listening = false
        _inputLevel.value = 0f
        _state.value = VoiceState.Error
        _detail.value = message
    }

    override fun pause() {
        activeUtteranceId = null
        continuousMode = false
        listening = false
        _inputLevel.value = 0f
        runOnMain {
            recognizer?.cancel()
            tts?.stop()
        }
        if (_state.value != VoiceState.Error) _state.value = VoiceState.Idle
    }

    override fun shutdown() {
        shutDown = true
        pause()
        runOnMain {
            recognizer?.destroy()
            recognizer = null
            tts?.shutdown()
            tts = null
        }
    }

    private fun interruptSpeechOnly() {
        activeUtteranceId = null
        runOnMain { tts?.stop() }
    }

    private fun runOnMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == mainLooper) block() else android.os.Handler(mainLooper).post(block)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            listening = true
            _state.value = VoiceState.Listening
        }
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) {
            if (listening) _inputLevel.value = inputLevelFromRms(rmsdB)
        }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            listening = false
            _inputLevel.value = 0f
        }
        override fun onError(error: Int) {
            listening = false
            _inputLevel.value = 0f
            val message = when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required for voice input."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition couldn’t reach the network."
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech was heard. Tap start and try again."
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was heard. Tap start and try again."
                SpeechRecognizer.ERROR_AUDIO -> "The microphone couldn’t be opened."
                SpeechRecognizer.ERROR_CLIENT -> {
                    listening = false
                    _inputLevel.value = 0f
                    if (_state.value == VoiceState.Listening) _state.value = VoiceState.Idle
                    return
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition is busy. Try again in a moment."
                else -> "Speech recognition stopped (error $error)."
            }
            if (error == SpeechRecognizer.ERROR_CLIENT && _state.value == VoiceState.Muted) return
            if (continuousMode && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                _state.value = VoiceState.Idle
                _detail.value = "I’m listening. Go ahead when you’re ready."
                runOnMain {
                    android.os.Handler(mainLooper).postDelayed({
                        if (continuousMode && !shutDown && _state.value != VoiceState.Muted && _state.value != VoiceState.Error) {
                            startListening()
                        }
                    }, 350L)
                }
                return
            }
            fail(message)
        }
        override fun onResults(results: Bundle?) {
            listening = false
            _inputLevel.value = 0f
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
            if (text.isBlank()) {
                fail("No speech was heard. Tap start and try again.")
            } else {
                _state.value = VoiceState.Processing
                onFinalSpeech(text)
            }
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank() && listening) _detail.value = text
        }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    companion object {
        const val DEVICE_DEFAULT = "Device default"
    }
}
