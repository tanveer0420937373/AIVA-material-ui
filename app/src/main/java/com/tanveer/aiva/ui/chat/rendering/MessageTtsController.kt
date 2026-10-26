package com.tanveer.aiva.ui.chat.rendering

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlin.math.max

/**
 * One shared native TTS player for all assistant messages.
 *
 * Android TextToSpeech cannot seek within an active utterance. To provide
 * practical seeking, text is split into short chunks and the seekbar maps
 * to estimated chunk durations. Pause resumes from the beginning of the
 * current chunk; it cannot resume at an exact phoneme/word boundary.
 */
class MessageTtsController(
    context: Context,
    private val onStateChanged: (State) -> Unit = {},
    private val onError: (String) -> Unit = {}
) : AutoCloseable {

    data class State(
        val visible: Boolean = false,
        val messageId: String? = null,
        val isPlaying: Boolean = false,
        val isPaused: Boolean = false,
        val progressMs: Long = 0L,
        val durationMs: Long = 0L
    )

    private data class Chunk(val text: String, val durationMs: Long)

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var initialized = false
    private var released = false
    private var chunks: List<Chunk> = emptyList()
    private var currentChunk = 0
    private var activeUtteranceId: String? = null
    private var utteranceStartedAt = 0L
    private var chunkStartProgressMs = 0L
    private var requestCounter = 0L
    private var currentLocale: Locale = Locale.getDefault()
    private var currentState = State()

    private val ticker = object : Runnable {
        override fun run() {
            if (released || !currentState.visible || !currentState.isPlaying) return
            val current = chunks.getOrNull(currentChunk)
            if (current != null) {
                val elapsed = (System.currentTimeMillis() - utteranceStartedAt)
                    .coerceAtLeast(0L)
                    .coerceAtMost(current.durationMs)
                publish(currentState.copy(
                    progressMs = (chunkStartProgressMs + elapsed)
                        .coerceAtMost(currentState.durationMs)
                ))
            }
            mainHandler.postDelayed(this, 200L)
        }
    }

    init {
        checkMainThread()
        engine = TextToSpeech(appContext) { status ->
            if (released) return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) {
                initialized = true
                engine?.setSpeechRate(1.0f)
                if (currentState.visible && !currentState.isPaused) {
                    startCurrentChunk()
                }
            } else {
                initialized = false
                publish(currentState.copy(isPlaying = false, isPaused = true))
                onError("Text-to-Speech initialization failed. Check Android TTS settings.")
            }
        }

        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (!isCurrentUtterance(utteranceId)) return
                mainHandler.post {
                    if (isCurrentUtterance(utteranceId)) {
                        publish(currentState.copy(isPlaying = true, isPaused = false))
                        mainHandler.removeCallbacks(ticker)
                        mainHandler.post(ticker)
                    }
                }
            }

            override fun onDone(utteranceId: String?) {
                if (!isCurrentUtterance(utteranceId)) return
                mainHandler.post {
                    if (!isCurrentUtterance(utteranceId)) return@post
                    activeUtteranceId = null
                    currentChunk++
                    if (currentChunk >= chunks.size) {
                        mainHandler.removeCallbacks(ticker)
                        publish(currentState.copy(
                            visible = true,
                            isPlaying = false,
                            isPaused = false,
                            progressMs = currentState.durationMs
                        ))
                    } else {
                        startCurrentChunk()
                    }
                }
            }

            @Deprecated("Required by Android TTS API")
            override fun onError(utteranceId: String?) {
                handleUtteranceError(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                handleUtteranceError(utteranceId)
            }
        })
    }

    fun play(messageId: String, markdown: String) {
        checkMainThread()
        check(!released) { "MessageTtsController has been released." }
        require(messageId.isNotBlank()) { "A stable message ID is required." }

        val cleanText = cleanForSpeech(markdown)
        if (cleanText.isBlank()) {
            onError("This message has no readable text.")
            return
        }

        stopUtteranceOnly()
        chunks = splitIntoChunks(cleanText)
        currentChunk = 0
        activeUtteranceId = null
        val duration = chunks.sumOf { it.durationMs }.coerceAtLeast(1L)

        publish(State(
            visible = true,
            messageId = messageId,
            isPlaying = false,
            // If the engine is still initializing, the init callback will
            // automatically begin this request unless the user pauses it.
            isPaused = false,
            progressMs = 0L,
            durationMs = duration
        ))

        if (initialized) startCurrentChunk()
    }

    fun togglePlayPause() {
        checkMainThread()
        if (released || !currentState.visible) return

        if (currentState.isPlaying) {
            // Pause is implemented at chunk boundaries. Resume restarts the
            // current sentence/chunk because Android TTS has no native pause.
            val elapsed = (System.currentTimeMillis() - utteranceStartedAt)
                .coerceAtLeast(0L)
            val chunk = chunks.getOrNull(currentChunk)
            val nextProgress = if (chunk != null) {
                (chunkStartProgressMs + elapsed.coerceAtMost(chunk.durationMs))
                    .coerceAtMost(currentState.durationMs)
            } else currentState.progressMs
            stopUtteranceOnly()
            publish(currentState.copy(
                isPlaying = false,
                isPaused = true,
                progressMs = nextProgress
            ))
            return
        }

        if (chunks.isEmpty()) return
        if (currentChunk >= chunks.size) {
            currentChunk = 0
            publish(currentState.copy(progressMs = 0L))
        }
        if (initialized) startCurrentChunk()
        else publish(currentState.copy(isPlaying = false, isPaused = true))
    }

    fun seekTo(progressMs: Long) {
        checkMainThread()
        if (released || chunks.isEmpty()) return

        val target = progressMs.coerceIn(0L, currentState.durationMs)
        var accumulated = 0L
        var targetIndex = chunks.lastIndex
        var found = false

        for (index in chunks.indices) {
            val end = accumulated + chunks[index].durationMs
            if (target < end) {
                targetIndex = index
                found = true
                break
            }
            accumulated = end
        }

        stopUtteranceOnly()
        currentChunk = targetIndex
        chunkStartProgressMs = if (found) accumulated else chunks.sumOf { it.durationMs }
        val wasPlaying = currentState.isPlaying
        publish(currentState.copy(
            progressMs = target,
            isPlaying = false,
            isPaused = !wasPlaying
        ))

        if (target >= currentState.durationMs) {
            currentChunk = chunks.size
            publish(currentState.copy(
                progressMs = currentState.durationMs,
                isPlaying = false,
                isPaused = false
            ))
        } else if (wasPlaying && initialized) {
            startCurrentChunk()
        }
    }

    fun closePlayer() {
        checkMainThread()
        stopUtteranceOnly()
        chunks = emptyList()
        currentChunk = 0
        publish(State())
    }

    fun state(): State {
        checkMainThread()
        return currentState
    }

    private fun startCurrentChunk() {
        if (released || !initialized) return
        val chunk = chunks.getOrNull(currentChunk) ?: return
        val tts = engine ?: return

        val locale = if (containsDevanagari(chunk.text)) {
            Locale.forLanguageTag("hi-IN")
        } else {
            Locale.forLanguageTag("en-IN")
        }
        currentLocale = locale
        var languageStatus = runCatching { tts.setLanguage(locale) }
            .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)

        if (languageStatus == TextToSpeech.LANG_MISSING_DATA ||
            languageStatus == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            languageStatus = runCatching { tts.setLanguage(Locale.getDefault()) }
                .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        }

        if (languageStatus == TextToSpeech.LANG_MISSING_DATA ||
            languageStatus == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            publish(currentState.copy(isPlaying = false, isPaused = true))
            onError("No compatible speech voice is installed. Check Android Text-to-Speech settings.")
            return
        }

        chunkStartProgressMs = chunks.take(currentChunk).sumOf { it.durationMs }
        utteranceStartedAt = System.currentTimeMillis()
        val id = "aiva_tts_${++requestCounter}"
        activeUtteranceId = id
        publish(currentState.copy(
            isPlaying = true,
            isPaused = false,
            progressMs = chunkStartProgressMs
        ))

        val result = runCatching {
            tts.speak(chunk.text, TextToSpeech.QUEUE_FLUSH, null, id)
        }.getOrDefault(TextToSpeech.ERROR)

        if (result == TextToSpeech.ERROR) {
            activeUtteranceId = null
            publish(currentState.copy(isPlaying = false, isPaused = true))
            onError("Unable to start speech playback.")
        }
    }

    private fun splitIntoChunks(text: String): List<Chunk> {
        val sentences = text.split(Regex("""(?<=[.!?।])\s+|\n+"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val pieces = mutableListOf<String>()

        sentences.forEach { sentence ->
            if (sentence.length <= 220) {
                pieces += sentence
            } else {
                sentence.chunked(180).forEach { pieces += it.trim() }
            }
        }

        return pieces.map { piece ->
            val words = piece.split(Regex("""\s+""")).size
            // Approximate timeline; actual TTS duration depends on installed voice.
            Chunk(piece, max(700L, words * 360L))
        }
    }

    private fun cleanForSpeech(markdown: String): String {
        var text = markdown
        text = text.replace(Regex("""(?s)```[^\n]*\n.*?(?:```|$)"""), " ")
        text = text.replace(Regex("""(?s)~~~[^\n]*\n.*?(?:~~~|$)"""), " ")
        text = text.replace(Regex("""`[^`]*`"""), " ")
        text = text.replace(Regex("""!\[([^\]]*)]\([^)]+\)"""), "$1")
        text = text.replace(Regex("""\[([^\]]+)]\(([^)]+)\)"""), "$1")
        text = text.replace(Regex("""(?m)^\s{0,3}#{1,6}\s+"""), "")
        text = text.replace(Regex("""(?m)^\s*>\s?"""), "")
        text = text.replace(Regex("""(?m)^\s*[-+*]\s+"""), "")
        text = text.replace(Regex("""(?m)^\s*\d+[.)]\s+"""), "")
        text = text.replace(Regex("""(?m)^\s*\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)+\|?\s*$"""), "")
        text = text.replace(Regex("""(?m)^\s*(?:-{3,}|\*{3,}|_{3,})\s*$"""), "")
        text = text.replace("|", ", ")
        text = text.replace(Regex("""\*\*|__|~~"""), "")
        text = text.replace(Regex("""(?<!\w)[*_](?!\s)|(?<!\s)[*_](?!\w)"""), "")
        return text.replace('\u00A0', ' ')
            .replace(Regex("""[ \t]+"""), " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
            .take(12000)
    }

    private fun containsDevanagari(text: String): Boolean =
        text.any { it.code in 0x0900..0x097F }

    private fun isCurrentUtterance(id: String?): Boolean =
        !released && id != null && id == activeUtteranceId

    private fun handleUtteranceError(id: String?) {
        if (!isCurrentUtterance(id)) return
        mainHandler.post {
            if (!isCurrentUtterance(id)) return@post
            activeUtteranceId = null
            mainHandler.removeCallbacks(ticker)
            publish(currentState.copy(isPlaying = false, isPaused = true))
            onError("Speech playback failed.")
        }
    }

    private fun stopUtteranceOnly() {
        activeUtteranceId = null
        mainHandler.removeCallbacks(ticker)
        runCatching { engine?.stop() }
    }

    private fun publish(state: State) {
        currentState = state
        onStateChanged(state)
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "MessageTtsController must be accessed on the main thread."
        }
    }

    override fun close() {
        checkMainThread()
        if (released) return
        released = true
        initialized = false
        pendingUnusedCleanup()
        stopUtteranceOnly()
        runCatching { engine?.shutdown() }
        engine = null
        currentState = State()
    }

    private fun pendingUnusedCleanup() {
        chunks = emptyList()
    }
}
