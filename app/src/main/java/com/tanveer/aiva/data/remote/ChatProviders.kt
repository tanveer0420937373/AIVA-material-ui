package com.tanveer.aiva.data.remote

import android.util.Base64
import com.tanveer.aiva.domain.models.AiModel
import com.tanveer.aiva.domain.models.Capability
import com.tanveer.aiva.domain.models.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException
import java.net.HttpURLConnection

/** Emits real provider deltas. Cancelling the collector closes the socket. */
interface ChatProvider {
    fun stream(model: AiModel, turns: List<TranscriptTurn>, apiKey: String): Flow<ReplyDelta>
}

class OpenRouterProvider : ChatProvider {
    override fun stream(model: AiModel, turns: List<TranscriptTurn>, apiKey: String): Flow<ReplyDelta> = flow {
        if (apiKey.isBlank()) throw ProviderException(null, "OpenRouter isn’t set up yet. Add an API key in Settings.")
        val allowImages = Capability.Images in model.capabilities
        val payload = openRouterBody(model.remoteId, turns, allowImages).toString().toByteArray(Charsets.UTF_8)
        emitChatStream(
            url = "https://openrouter.ai/api/v1/chat/completions",
            body = payload,
            headers = mapOf(
                "Authorization" to "Bearer $apiKey",
                "HTTP-Referer" to "https://aiva.local",
                "X-Title" to "AIVA",
            ),
        )
    }.flowOn(Dispatchers.IO)
}

class GeminiProvider : ChatProvider {
    override fun stream(model: AiModel, turns: List<TranscriptTurn>, apiKey: String): Flow<ReplyDelta> = flow {
        if (apiKey.isBlank()) throw ProviderException(null, "Google AI Studio isn’t set up yet. Add an API key in Settings.")
        if (Capability.ImageGeneration in model.capabilities && Capability.Text !in model.capabilities) {
            emitImage(model, turns, apiKey)
            return@flow
        }
        val allowImages = Capability.Images in model.capabilities
        val payload = geminiBody(turns, allowImages).toString().toByteArray(Charsets.UTF_8)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${safeModelId(model.remoteId)}:streamGenerateContent?alt=sse"
        emitGeminiStream(url, payload, apiKey)
    }.flowOn(Dispatchers.IO)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ReplyDelta>.emitImage(
        model: AiModel,
        turns: List<TranscriptTurn>,
        apiKey: String,
    ) {
        val prompt = turns.lastOrNull { it.role == Role.User }?.text.orEmpty()
        val payload = geminiImageRequest(model.remoteId, prompt).toString().toByteArray(Charsets.UTF_8)
        val body = callWithRetry {
            val conn = Net.open(
                "https://generativelanguage.googleapis.com/v1beta/interactions",
                "POST",
                payload,
                mapOf("x-goog-api-key" to apiKey),
                readTimeoutMs = 120_000,
            )
            try {
                Net.requireSuccess(conn)
                Net.readCapped(conn.inputStream, 12_000_000)
            } finally {
                conn.disconnect()
            }
        }
        val decoded = geminiGeneratedImage(body)
        val bytes = try {
            Base64.decode(decoded.base64, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            throw ProviderException(null, "Google AI Studio returned an image AIVA could not read.")
        }
        if (sniffImageMime(bytes) == null) {
            throw ProviderException(null, imageBytesError(bytes))
        }
        emit(ReplyDelta.Image(bytes, sniffImageMime(bytes) ?: decoded.mimeType))
        emit(ReplyDelta.End(partial = false))
    }
}

class HuggingFaceProvider : ChatProvider {
    override fun stream(model: AiModel, turns: List<TranscriptTurn>, apiKey: String): Flow<ReplyDelta> = flow {
        if (apiKey.isBlank()) throw ProviderException(null, "Hugging Face isn’t set up yet. Add an API key in Settings.")
        val target = resolveHuggingFace(model, HfOverrides())
        if (target.image) {
            val prompt = turns.lastOrNull { it.text.isNotBlank() }?.text.orEmpty()
            val payload = huggingFaceImageBody(prompt).toString().toByteArray(Charsets.UTF_8)
            val bytes = callWithRetry {
                val conn = Net.open(target.url, "POST", payload, mapOf("Authorization" to "Bearer $apiKey"), 120_000)
                try {
                    Net.requireSuccess(conn)
                    readBytesCapped(conn, 12_000_000)
                } finally {
                    conn.disconnect()
                }
            }
            val mime = sniffImageMime(bytes) ?: throw ProviderException(null, imageBytesError(bytes))
            emit(ReplyDelta.Image(bytes, mime))
            emit(ReplyDelta.End(partial = false))
            return@flow
        }
        val allowImages = Capability.Images in model.capabilities
        val payload = huggingFaceChatBody(target.model, turns, allowImages).toString().toByteArray(Charsets.UTF_8)
        emitChatStream(target.url, payload, mapOf("Authorization" to "Bearer $apiKey"))
    }.flowOn(Dispatchers.IO)
}

private suspend fun kotlinx.coroutines.flow.FlowCollector<ReplyDelta>.emitChatStream(
    url: String,
    body: ByteArray,
    headers: Map<String, String>,
) {
    var attempt = 0
    var emitted = false
    var partial = false
    while (true) {
        currentCoroutineContext().ensureActive()
        val conn = try {
            Net.open(url, "POST", body, headers)
        } catch (e: IOException) {
            if (emitted || attempt == RetryPolicy.MAX_ATTEMPTS - 1) {
                throw ProviderException(null, "The connection dropped before the reply finished.")
            }
            kotlinx.coroutines.delay(RetryPolicy.delayMs(attempt, null))
            attempt++
            continue
        }
        try {
            try {
                Net.requireSuccess(conn)
            } catch (e: ProviderException) {
                if (emitted || !RetryPolicy.retryable(e.httpCode, emitted) || attempt == RetryPolicy.MAX_ATTEMPTS - 1) throw e
                kotlinx.coroutines.delay(RetryPolicy.delayMs(attempt, e.retryAfterSeconds))
                attempt++
                continue
            }
            if (Net.isEventStream(conn)) {
                readSse(conn) { data ->
                    openRouterText(data).forEach {
                        emitted = true
                        emit(ReplyDelta.Text(it))
                    }
                    if (finishMeansPartial(openRouterFinish(data))) partial = true
                }
            } else {
                val text = Net.readCapped(conn.inputStream, 2_000_000)
                openRouterText(text).forEach {
                    emitted = true
                    emit(ReplyDelta.Text(it))
                }
                if (finishMeansPartial(openRouterFinish(text))) partial = true
            }
            if (!emitted) throw ProviderException(null, "The model returned an empty reply.")
            emit(ReplyDelta.End(partial = partial))
            return
        } catch (e: IOException) {
            if (emitted) throw ProviderException(null, "The connection dropped before the reply finished.")
            if (attempt == RetryPolicy.MAX_ATTEMPTS - 1) {
                throw ProviderException(null, "Couldn’t reach the provider. Check your connection and try again.")
            }
            kotlinx.coroutines.delay(RetryPolicy.delayMs(attempt, null))
            attempt++
        } finally {
            conn.disconnect()
        }
    }
}

/** Streams actual Gemini SSE chunks. Retries are allowed only before any user-visible text. */
private suspend fun kotlinx.coroutines.flow.FlowCollector<ReplyDelta>.emitGeminiStream(
    url: String,
    body: ByteArray,
    apiKey: String,
) {
    var attempt = 0
    var emitted = false
    var partial = false
    var blockedReason: String? = null
    while (true) {
        currentCoroutineContext().ensureActive()
        val conn = try {
            Net.open(url, "POST", body, mapOf("x-goog-api-key" to apiKey), readTimeoutMs = 90_000)
        } catch (failure: IOException) {
            if (attempt >= RetryPolicy.MAX_ATTEMPTS - 1) {
                throw ProviderException(null, "Couldn’t reach Google AI Studio. Check your connection and try again.")
            }
            kotlinx.coroutines.delay(RetryPolicy.delayMs(attempt, null))
            attempt++
            continue
        }
        try {
            Net.requireSuccess(conn)
            if (Net.isEventStream(conn)) {
                readSse(conn) { data ->
                    val pieces = geminiPieces(data)
                    pieces.forEach { piece ->
                        piece.blocked?.let { blockedReason = it }
                        if (piece.text.isNotEmpty()) {
                            emitted = true
                            emit(ReplyDelta.Text(piece.text))
                        }
                    }
                    if (geminiFinishReason(data) in setOf("MAX_TOKENS", "RECITATION")) partial = true
                }
            } else {
                val response = Net.readCapped(conn.inputStream, 2_000_000)
                val pieces = geminiPieces(response)
                blockedReason = pieces.mapNotNull { it.blocked }.firstOrNull()
                pieces.filter { it.text.isNotEmpty() }.forEach { piece ->
                    emitted = true
                    emit(ReplyDelta.Text(piece.text))
                }
                if (geminiFinishReason(response) in setOf("MAX_TOKENS", "RECITATION")) partial = true
            }
            if (!emitted) throw ProviderException(400, blockedReason ?: "The model returned an empty reply.")
            emit(ReplyDelta.End(partial = partial))
            return
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: ProviderException) {
            if (emitted || !RetryPolicy.retryable(failure.httpCode, emitted) || attempt >= RetryPolicy.MAX_ATTEMPTS - 1) throw failure
            kotlinx.coroutines.delay(RetryPolicy.delayMs(attempt, failure.retryAfterSeconds))
            attempt++
        } catch (failure: IOException) {
            if (emitted) throw ProviderException(null, "The Google AI Studio stream ended before the reply finished.")
            if (attempt >= RetryPolicy.MAX_ATTEMPTS - 1) {
                throw ProviderException(null, "Couldn’t reach Google AI Studio. Check your connection and try again.")
            }
            kotlinx.coroutines.delay(RetryPolicy.delayMs(attempt, null))
            attempt++
        } finally {
            conn.disconnect()
        }
    }
}

private fun geminiFinishReason(body: String): String? {
    val json = runCatching { org.json.JSONObject(body) }.getOrNull() ?: return null
    return json.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason")
        ?.takeIf { it.isNotBlank() && it != "null" }
}

private suspend fun readSse(conn: HttpURLConnection, onData: suspend (String) -> Unit) {
    conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
        while (true) {
            currentCoroutineContext().ensureActive()
            val line = reader.readLine() ?: break
            if (line.length > 1_000_000) throw ProviderException(null, "The provider sent an oversized event.")
            when (val event = consumeSseLine(line)) {
                is SseEvent.Data -> onData(event.data)
                SseEvent.Done -> break
                SseEvent.Comment, SseEvent.Ignore -> Unit
            }
        }
    }
}

private fun readBytesCapped(conn: HttpURLConnection, limit: Int): ByteArray {
    val input = conn.inputStream
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8_192)
    var total = 0
    while (total < limit) {
        val n = input.read(buf, 0, minOf(buf.size, limit - total))
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
    }
    input.close()
    return out.toByteArray()
}

/** Authenticated probe that does not print the key or the response body. */
object ProviderProbe {
    fun openRouter(apiKey: String): String = probe(
        "https://openrouter.ai/api/v1/key",
        mapOf("Authorization" to "Bearer $apiKey"),
        "OpenRouter accepted this key.",
    )

    fun gemini(apiKey: String): String {
        val conn = Net.open(
            "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1",
            "GET",
            null,
            mapOf("x-goog-api-key" to apiKey),
            readTimeoutMs = 20_000,
        )
        try {
            Net.requireSuccess(conn)
            val body = Net.readCapped(conn.inputStream, 256_000)
            val models = org.json.JSONObject(body).optJSONArray("models")
                ?: throw ProviderException(null, "Google returned an unexpected model-list response.")
            return "Google AI Studio accepted this key; model-list API is reachable (${models.length()} model(s) on the first page)."
        } finally {
            conn.disconnect()
        }
    }

    fun huggingFace(apiKey: String): String = probe(
        "https://huggingface.co/api/whoami-v2",
        mapOf("Authorization" to "Bearer $apiKey"),
        "Hugging Face accepted this key.",
    )

    private fun probe(url: String, headers: Map<String, String>, ok: String): String {
        val conn = Net.open(url, "GET", null, headers, readTimeoutMs = 20_000)
        try {
            Net.requireSuccess(conn)
            return ok
        } finally {
            conn.disconnect()
        }
    }
}
