package com.tanveer.aiva.data.remote

import com.tanveer.aiva.domain.models.AiModel
import com.tanveer.aiva.domain.models.Capability
import com.tanveer.aiva.domain.models.ChatMessage
import com.tanveer.aiva.domain.models.Delivery
import com.tanveer.aiva.domain.models.Role
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI

/** One piece of a provider reply. Image bytes are the raw file, already sniffed. */
sealed interface ReplyDelta {
    data class Text(val value: String) : ReplyDelta
    data class Image(val bytes: ByteArray, val mimeType: String) : ReplyDelta
    /** [partial] is true when the provider closed the reply early (length or a dropped stream). */
    data class End(val partial: Boolean) : ReplyDelta
}

data class EncodedImage(val mimeType: String, val base64: String)

data class TranscriptTurn(
    val role: Role,
    val text: String,
    val images: List<EncodedImage> = emptyList(),
    /** True when the user attached an image, even if this model cannot receive it. */
    val hadImages: Boolean = false,
)

data class HfOverrides(
    val chatModel: String = "",
    val imageModel: String = "",
    val chatEndpoint: String = "",
    val imageEndpoint: String = "",
)

data class HfTarget(val url: String, val model: String, val image: Boolean)

class ProviderException(
    val httpCode: Int?,
    message: String,
    val retryAfterSeconds: Int? = null,
) : Exception(message)

object HfDefaults {
    const val CHAT_URL = "https://router.huggingface.co/v1/chat/completions"
    const val CHAT_MODEL = "meta-llama/Llama-3.1-8B-Instruct:fastest"
    const val IMAGE_MODEL = "black-forest-labs/FLUX.1-schnell"

    fun imageUrl(model: String): String =
        "https://router.huggingface.co/hf-inference/models/$model"
}

private const val HISTORY_LIMIT = 32
private val MODEL_ID = Regex("^[A-Za-z0-9._:/+-]{1,200}$")
private val SECRET_QUERY = Regex("(?:^|&)(?:key|token|api_key|access_token)=", RegexOption.IGNORE_CASE)

fun safeModelId(id: String): String {
    val value = id.trim()
    if (!MODEL_ID.matches(value)) {
        throw ProviderException(null, "The model id contains unsupported characters.")
    }
    return value
}

fun resolveHuggingFace(model: AiModel, overrides: HfOverrides): HfTarget {
    val imageOnly = Capability.ImageGeneration in model.capabilities && Capability.Text !in model.capabilities
    return if (imageOnly) {
        val id = safeModelId(overrides.imageModel.trim().ifBlank { model.remoteId.ifBlank { HfDefaults.IMAGE_MODEL } })
        val url = overrides.imageEndpoint.trim().ifBlank { HfDefaults.imageUrl(id) }
        HfTarget(url, id, image = true)
    } else {
        val id = safeModelId(overrides.chatModel.trim().ifBlank { model.remoteId.ifBlank { HfDefaults.CHAT_MODEL } })
        val url = overrides.chatEndpoint.trim().ifBlank { HfDefaults.CHAT_URL }
        HfTarget(url, id, image = false)
    }
}

/** @return null when [raw] is blank or a safe https URL; otherwise a user-facing error. */
fun httpsEndpointError(raw: String): String? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    val uri = runCatching { URI(value) }.getOrNull() ?: return "Enter a full https URL."
    if (!uri.isAbsolute || !uri.scheme.equals("https", ignoreCase = true)) return "Only https endpoints are allowed."
    if (!uri.userInfo.isNullOrBlank()) return "Remove credentials from the endpoint URL."
    if (uri.host.isNullOrBlank()) return "The endpoint needs a host name."
    if (SECRET_QUERY.containsMatchIn(uri.rawQuery.orEmpty())) {
        return "Don't put API keys in the endpoint URL. Save the key in the field above."
    }
    return null
}

fun sniffImageMime(bytes: ByteArray): String? {
    if (bytes.size >= 8 &&
        bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
    ) return "image/png"
    if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
        return "image/jpeg"
    }
    if (bytes.size >= 12 &&
        bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
        bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
        bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
    ) return "image/webp"
    if (bytes.size >= 6) {
        val sig = bytes.copyOfRange(0, 6).decodeToString()
        if (sig == "GIF87a" || sig == "GIF89a") return "image/gif"
    }
    return null
}

fun imageBytesError(bytes: ByteArray): String {
    val text = runCatching { bytes.toString(Charsets.UTF_8).trim() }.getOrDefault("")
    if (text.startsWith("{") || text.startsWith("[")) {
        return jsonErrorMessage(text) ?: "Hugging Face did not return an image."
    }
    return "Hugging Face did not return an image."
}

fun extensionForMime(mime: String): String = when (mime) {
    "image/jpeg" -> "jpg"
    "image/webp" -> "webp"
    "image/gif" -> "gif"
    else -> "png"
}

fun httpFailure(code: Int, body: String, retryAfterHeader: String?): ProviderException {
    val retryAfter = parseRetryAfter(retryAfterHeader)
    val detail = jsonErrorMessage(body)?.take(280)
        ?: body.replace(Regex("\\s+"), " ").trim().take(180).ifBlank { null }
    val message = when (code) {
        401, 403 -> "Access was denied ($code). Check the API key and that this model is enabled for the account.${suffix(detail)}"
        404 -> "The model or endpoint was not found (404).${suffix(detail)}"
        408, 504 -> "The provider timed out.${suffix(detail)}"
        413 -> "The request was too large. Try a shorter prompt or a smaller image.${suffix(detail)}"
        429 -> "Rate limited.${retryAfter?.let { " Try again in about ${it}s." } ?: ""}${suffix(detail)}"
        in 500..599 -> "The provider is unavailable ($code).${retryAfter?.let { " It asked to wait about ${it}s." } ?: ""}${suffix(detail)}"
        300, 301, 302, 303, 307, 308 -> "The provider tried to redirect the request. AIVA will not follow redirects with your API key."
        else -> "The provider returned an error ($code).${suffix(detail)}"
    }
    return ProviderException(code, message.trim(), retryAfter)
}

private fun suffix(detail: String?) = if (detail.isNullOrBlank()) "" else " $detail"

fun jsonErrorMessage(body: String): String? {
    val trimmed = body.trim()
    if (trimmed.isEmpty() || !(trimmed.startsWith("{") || trimmed.startsWith("["))) return null
    val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
    json.optJSONObject("error")?.let { err ->
        val msg = err.optString("message").ifBlank { err.optString("status") }
        if (msg.isNotBlank()) return msg
    }
    val err = json.opt("error")
    if (err is String && err.isNotBlank()) {
        val wait = if (json.has("estimated_time")) json.optDouble("estimated_time") else Double.NaN
        return if (!wait.isNaN()) "$err (estimated wait ${wait.toInt()}s)" else err
    }
    json.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotBlank() }?.let {
        return "Prompt blocked ($it)."
    }
    val message = json.optString("message")
    if (message.isNotBlank()) return message
    return null
}

fun parseRetryAfter(header: String?): Int? =
    header?.trim()?.toIntOrNull()?.coerceIn(0, 3600)

fun trimTurns(turns: List<TranscriptTurn>): List<TranscriptTurn> =
    if (turns.size <= HISTORY_LIMIT) turns else turns.takeLast(HISTORY_LIMIT)

/** Gemini requires a user turn first and alternating roles. */
fun coalesceForGemini(turns: List<TranscriptTurn>): List<TranscriptTurn> {
    val trimmed = trimTurns(turns).filter { it.text.isNotBlank() || it.images.isNotEmpty() || it.hadImages }
    val dropped = trimmed.dropWhile { it.role != Role.User }
    val out = mutableListOf<TranscriptTurn>()
    for (turn in dropped) {
        val last = out.lastOrNull()
        if (last != null && last.role == turn.role) {
            out[out.lastIndex] = last.copy(
                text = listOf(last.text, turn.text).filter { it.isNotBlank() }.joinToString("\n"),
                images = last.images + turn.images,
                hadImages = last.hadImages || turn.hadImages,
            )
        } else {
            out += turn
        }
    }
    return out
}

fun openRouterBody(modelId: String, turns: List<TranscriptTurn>, allowImages: Boolean): JSONObject {
    val messages = JSONArray()
    trimTurns(turns).forEach { turn ->
        messages.put(JSONObject().put("role", if (turn.role == Role.User) "user" else "assistant").put("content", contentFor(turn, allowImages)))
    }
    return JSONObject().put("model", safeModelId(modelId)).put("stream", true).put("messages", messages)
}

private fun contentFor(turn: TranscriptTurn, allowImages: Boolean): Any {
    val images = if (allowImages && turn.role == Role.User) turn.images else emptyList()
    if (images.isEmpty()) return visibleText(turn, allowImages)
    val parts = JSONArray()
    parts.put(JSONObject().put("type", "text").put("text", turn.text.ifBlank { "What do you see in this image?" }))
    images.forEach { img ->
        parts.put(
            JSONObject()
                .put("type", "image_url")
                .put("image_url", JSONObject().put("url", "data:${img.mimeType};base64,${img.base64}")),
        )
    }
    return parts
}

private fun visibleText(turn: TranscriptTurn, allowImages: Boolean): String {
    val missing = turn.hadImages && turn.images.isEmpty() && turn.role == Role.User && !allowImages
    if (!missing) return turn.text
    val note = "(An image was attached in AIVA, but this model does not accept images.)"
    return if (turn.text.isBlank()) note else "${turn.text}\n$note"
}

fun geminiBody(turns: List<TranscriptTurn>, allowImages: Boolean): JSONObject {
    val contents = JSONArray()
    coalesceForGemini(turns).forEach { turn ->
        val parts = JSONArray()
        val images = if (allowImages && turn.role == Role.User) turn.images else emptyList()
        val text = visibleText(turn, allowImages).ifBlank {
            if (images.isNotEmpty()) "What do you see in this image?" else ""
        }
        if (text.isNotBlank()) parts.put(JSONObject().put("text", text))
        images.forEach { img ->
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", img.mimeType).put("data", img.base64),
                ),
            )
        }
        if (parts.length() == 0) return@forEach
        contents.put(
            JSONObject()
                .put("role", if (turn.role == Role.User) "user" else "model")
                .put("parts", parts),
        )
    }
    if (contents.length() == 0) {
        throw ProviderException(null, "There is no message to send.")
    }
    return JSONObject().put("contents", contents)
}

fun huggingFaceChatBody(modelId: String, turns: List<TranscriptTurn>, allowImages: Boolean): JSONObject =
    openRouterBody(modelId, turns, allowImages)

fun huggingFaceImageBody(prompt: String): JSONObject {
    val text = prompt.trim()
    if (text.isEmpty()) throw ProviderException(null, "Describe the image you want.")
    return JSONObject().put("inputs", text.take(4_000))
}

/** Text fragments from one OpenRouter or Hugging Face chat payload. */
fun openRouterText(data: String): List<String> {
    val json = try {
        JSONObject(data)
    } catch (_: JSONException) {
        throw ProviderException(null, "The provider sent a response AIVA could not read.")
    }
    if (json.has("error") && !json.isNull("error")) {
        val code = json.optJSONObject("error")?.optInt("code")?.takeIf { it != 0 }
        throw ProviderException(code, jsonErrorMessage(data) ?: "The provider reported an error.")
    }
    val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return emptyList()
    choice.optJSONObject("error")?.let { err ->
        throw ProviderException(
            err.optInt("code").takeIf { it != 0 },
            err.optString("message").ifBlank { "The provider reported an error." },
        )
    }
    val delta = choice.optJSONObject("delta")?.optString("content", "").orEmpty()
    if (delta.isNotEmpty()) return listOf(delta)
    val message = choice.optJSONObject("message")?.optString("content", "").orEmpty()
    return if (message.isNotEmpty()) listOf(message) else emptyList()
}

data class GeminiPiece(val text: String, val blocked: String?)

fun geminiPieces(data: String): List<GeminiPiece> {
    val trimmed = data.trim()
    if (trimmed.startsWith("[")) {
        val array = try {
            JSONArray(trimmed)
        } catch (_: JSONException) {
            throw ProviderException(null, "Google AI Studio sent a response AIVA could not read.")
        }
        return (0 until array.length()).flatMap { geminiObject(array.getJSONObject(it)) }
    }
    val json = try {
        JSONObject(trimmed)
    } catch (_: JSONException) {
        throw ProviderException(null, "Google AI Studio sent a response AIVA could not read.")
    }
    return geminiObject(json)
}

private fun geminiObject(json: JSONObject): List<GeminiPiece> {
    if (json.has("error") && !json.isNull("error")) {
        val code = json.optJSONObject("error")?.optInt("code")?.takeIf { it != 0 }
        throw ProviderException(code, jsonErrorMessage(json.toString()) ?: "Google AI Studio reported an error.")
    }
    val promptBlock = json.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotBlank() }
        ?.let { "Google blocked this prompt ($it)." }
    val candidates = json.optJSONArray("candidates") ?: return listOfNotNull(promptBlock?.let { GeminiPiece("", it) })
    if (candidates.length() == 0) return listOfNotNull(promptBlock?.let { GeminiPiece("", it) })
    val out = mutableListOf<GeminiPiece>()
    for (i in 0 until candidates.length()) {
        val cand = candidates.optJSONObject(i) ?: continue
        val finish = cand.optString("finishReason")
        val finishBlock = when (finish) {
            "SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII" -> "Google stopped this reply ($finish)."
            else -> null
        }
        val parts = cand.optJSONObject("content")?.optJSONArray("parts")
        if (parts == null) {
            val reason = promptBlock ?: finishBlock
            if (reason != null) out += GeminiPiece("", reason)
            continue
        }
        var emitted = false
        for (p in 0 until parts.length()) {
            val part = parts.optJSONObject(p) ?: continue
            if (part.optBoolean("thought")) continue
            val text = part.optString("text", "")
            if (text.isNotEmpty()) {
                out += GeminiPiece(text, null)
                emitted = true
            }
        }
        if (!emitted) {
            val reason = promptBlock ?: finishBlock
            if (reason != null) out += GeminiPiece("", reason)
        }
    }
    return out
}

private val SECRET_LEAK = Regex("(sk-or-[A-Za-z0-9_\\-]{6,}|sk-[A-Za-z0-9_\\-]{8,}|hf_[A-Za-z0-9]{8,}|AIza[0-9A-Za-z_\\-]{8,})")

fun redactSecrets(raw: String): String = SECRET_LEAK.replace(raw) { "[redacted]" }

fun keyFormatError(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return "Enter an API key."
    if (trimmed.length < 8) return "That key looks too short."
    if (trimmed.any { it.isWhitespace() }) return "The key can’t contain spaces."
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return "Paste the key, not a web address."
    return null
}

object RetryPolicy {
    const val MAX_ATTEMPTS = 3

    /** IO failures use a null code. Permanent 4xx must not be retried. */
    fun retryable(code: Int?, emitted: Boolean): Boolean {
        if (emitted) return false
        if (code == null) return true
        return code == 408 || code == 429 || code in 500..599
    }

    fun delayMs(attempt: Int, retryAfterSeconds: Int?): Long {
        if (retryAfterSeconds != null) return retryAfterSeconds.coerceIn(0, 30) * 1000L
        val base = 400L shl attempt.coerceIn(0, 4)
        val jitter = (attempt * 37L) % 150L
        return (base + jitter).coerceAtMost(8_000L)
    }
}

sealed interface SseEvent {
    data class Data(val data: String) : SseEvent
    data object Done : SseEvent
    data object Comment : SseEvent
    data object Ignore : SseEvent
}

fun consumeSseLine(line: String): SseEvent {
    if (line.isEmpty() || line.startsWith(":")) return SseEvent.Comment
    if (!line.startsWith("data:")) return SseEvent.Ignore
    val data = line.removePrefix("data:").trim()
    if (data == "[DONE]") return SseEvent.Done
    if (data.isEmpty()) return SseEvent.Ignore
    return SseEvent.Data(data)
}

fun finishMeansPartial(reason: String?): Boolean = reason == "length" || reason == "content_filter"

fun openRouterFinish(data: String): String? {
    val json = runCatching { JSONObject(data) }.getOrNull() ?: return null
    return json.optJSONArray("choices")?.optJSONObject(0)?.optString("finish_reason")?.takeIf { it.isNotBlank() && it != "null" }
}

fun visionBlockReason(model: AiModel, attachmentCount: Int): String? {
    if (attachmentCount <= 0) return null
    if (Capability.Images in model.capabilities) return null
    if (Capability.ImageGeneration in model.capabilities) {
        return "AIVA Canvas generates a new image from your prompt. Remove the attached photos or choose a model that can look at images."
    }
    return "${model.name} only accepts text. Remove the image, or choose a model marked Images."
}

fun contextMessages(messages: List<ChatMessage>): List<ChatMessage> =
    messages.filter { message ->
        message.text.isNotBlank() || message.attachments.isNotEmpty() || message.generatedImages.isNotEmpty()
    }.filter { !it.isError && it.delivery != Delivery.Failed }

fun geminiImageRequest(modelId: String, prompt: String): JSONObject {
    val text = prompt.trim()
    if (text.isEmpty()) throw ProviderException(null, "Describe the image you want.")
    return JSONObject()
        .put("model", safeModelId(modelId))
        .put(
            "input",
            JSONArray().put(JSONObject().put("type", "text").put("text", text.take(4_000))),
        )
}

data class DecodedImage(val mimeType: String, val base64: String)

/** Reads Gemini Interactions `output_image`, falling back to classic inline image parts. */
fun geminiGeneratedImage(body: String): DecodedImage {
    val json = try {
        JSONObject(body)
    } catch (_: JSONException) {
        throw ProviderException(null, "Google AI Studio sent a response AIVA could not read.")
    }
    if (json.has("error") && !json.isNull("error")) {
        val code = json.optJSONObject("error")?.optInt("code")?.takeIf { it != 0 }
        throw ProviderException(code, jsonErrorMessage(body) ?: "Google AI Studio reported an error.")
    }
    json.optJSONObject("output_image")?.let { image ->
        val data = image.optString("data").ifBlank { image.optString("bytes") }
        if (data.isNotBlank()) {
            val mime = image.optString("mime_type").ifBlank { image.optString("mimeType") }.ifBlank { "image/png" }
            return DecodedImage(mime, data)
        }
    }
    val steps = json.optJSONArray("steps")
    if (steps != null) {
        for (i in steps.length() - 1 downTo 0) {
            val content = steps.optJSONObject(i)?.optJSONObject("model_output")?.optJSONArray("content") ?: continue
            for (c in content.length() - 1 downTo 0) {
                val part = content.optJSONObject(c) ?: continue
                if (part.optString("type") == "image" && part.optString("data").isNotBlank()) {
                    return DecodedImage(part.optString("mime_type").ifBlank { "image/png" }, part.optString("data"))
                }
            }
        }
    }
    val parts = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
    if (parts != null) {
        for (i in 0 until parts.length()) {
            val inline = parts.optJSONObject(i)?.optJSONObject("inlineData")
                ?: parts.optJSONObject(i)?.optJSONObject("inline_data")
            val data = inline?.optString("data").orEmpty()
            if (data.isNotBlank()) {
                val mime = inline?.optString("mimeType")?.ifBlank { inline.optString("mime_type") } ?: "image/png"
                return DecodedImage(mime.ifBlank { "image/png" }, data)
            }
        }
    }
    val blocked = json.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotBlank() }
    throw ProviderException(null, blocked?.let { "Google blocked this prompt ($it)." } ?: "Google AI Studio did not return an image.")
}

/** Normalized 0..1 microphone level from a SpeechRecognizer RMS callback. */
fun inputLevelFromRms(rmsdB: Float): Float = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)

