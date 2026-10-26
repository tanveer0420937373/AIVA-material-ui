package com.tanveer.aiva.data.remote

import com.tanveer.aiva.domain.models.AiModel
import com.tanveer.aiva.domain.models.Capability
import com.tanveer.aiva.domain.models.ModelCategory
import com.tanveer.aiva.domain.models.ProviderId
import com.tanveer.aiva.domain.models.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/** Reads the models actually exposed by each provider account. No model IDs are invented. */
class ProviderModelDirectory {
    suspend fun openRouter(apiKey: String): List<AiModel> = withContext(Dispatchers.IO) {
        requireKey(apiKey, ProviderId.OpenRouter)
        // OpenRouter's GET /api/v1/models returns the catalog in one response. This endpoint
        // does not document offset/limit pagination, so do not request fake pages.
        val response = getJson(
            "https://openrouter.ai/api/v1/models?output_modalities=text",
            mapOf("Authorization" to "Bearer ${apiKey.trim()}"),
        )
        val data = response.optJSONArray("data")
            ?: throw ProviderException(null, "OpenRouter returned an unexpected model-list response.")
        val models = mutableListOf<AiModel>()
        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index) ?: continue
            val remoteId = item.optString("id").trim()
            if (remoteId.isBlank() || !isSafeRemoteId(remoteId)) continue
            val architecture = item.optJSONObject("architecture")
            val inputs = stringArray(architecture?.optJSONArray("input_modalities"))
            val outputs = stringArray(architecture?.optJSONArray("output_modalities"))
            // Only expose models that can accept chat text and return text in this chat picker.
            if (inputs.isNotEmpty() && "text" !in inputs) continue
            // This client's decoder renders text deltas; don't advertise audio/image-output APIs as chat models.
            if (outputs.isNotEmpty() && outputs != setOf("text")) continue
            val name = item.optString("name").trim().ifBlank { remoteId }
            val baseDescription = item.optString("description").trim()
            val pricing = item.optJSONObject("pricing")
            val promptCost = pricing?.optString("prompt")?.toDoubleOrNull()
            val completionCost = pricing?.optString("completion")?.toDoubleOrNull()
            val context = item.optInt("context_length", 0)
            val details = buildList<String> {
                if (context > 0) add("Context ${context.withGrouping()} tokens")
                if (promptCost != null || completionCost != null) {
                    val input = promptCost?.let { formatCost(it) } ?: "n/a"
                    val output = completionCost?.let { formatCost(it) } ?: "n/a"
                    add("Input $input / output $output per 1M tokens")
                }
            }.joinToString(" · ")
            val description = listOf(baseDescription, details).filter { it.isNotBlank() }.joinToString("\n")
            val capabilities = mutableListOf(Capability.Text)
            if ("image" in inputs) capabilities += Capability.Images
            val searchable = "$name $remoteId $baseDescription".lowercase(Locale.ROOT)
            if (listOf("code", "coding", "programmer", "software engineer").any(searchable::contains)) capabilities += Capability.Code
            if (listOf("reason", "thinking", "o1", "o3", "r1").any(searchable::contains)) capabilities += Capability.Reasoning
            val vendor = remoteId.substringBefore('/').ifBlank { "OpenRouter" }
            models += AiModel(
                id = "or:$remoteId",
                name = name,
                provider = ProviderId.OpenRouter,
                vendor = vendor,
                remoteId = remoteId,
                description = description.ifBlank { "Available through OpenRouter." },
                category = if (Capability.Reasoning in capabilities || Capability.Code in capabilities) ModelCategory.Advanced else ModelCategory.Everyday,
                capabilities = capabilities,
                mark = vendor.firstOrNull()?.uppercaseChar()?.toString() ?: "O",
                tone = toneFor(vendor),
            )
        }
        models.distinctBy { it.remoteId }.sortedWith(modelNameComparator())
            .also { if (it.isEmpty()) throw ProviderException(null, "OpenRouter returned no chat-compatible text models.") }
    }

    suspend fun gemini(apiKey: String): List<AiModel> = withContext(Dispatchers.IO) {
        requireKey(apiKey, ProviderId.Gemini)
        val models = mutableListOf<AiModel>()
        var pageToken: String? = null
        var pages = 0
        do {
            val query = buildString {
                append("pageSize=1000")
                if (!pageToken.isNullOrBlank()) append("&pageToken=").append(URLEncoder.encode(pageToken, Charsets.UTF_8.name()))
            }
            val response = getJson(
                "https://generativelanguage.googleapis.com/v1beta/models?$query",
                mapOf("x-goog-api-key" to apiKey.trim()),
            )
            val data = response.optJSONArray("models") ?: JSONArray()
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val methods = stringArray(item.optJSONArray("supportedGenerationMethods"))
                // This picker is for chat/generation. Embedding, TTS and other non-chat APIs are not compatible.
                if ("generateContent" !in methods) continue
                val resourceName = item.optString("name").trim()
                val remoteId = resourceName.removePrefix("models/").trim()
                if (remoteId.isBlank() || !isSafeRemoteId(remoteId)) continue
                val name = item.optString("displayName").trim().ifBlank { remoteId }
                val description = item.optString("description").trim()
                val searchable = "$name $remoteId $description".lowercase(Locale.ROOT)
                // The current Gemini chat protocol expects text candidates from generateContent.
                // Skip separate embedding, TTS, transcription, and audio/image-output-only APIs.
                if (listOf("embedding", "text-to-speech", "tts", "transcription", "native-audio", "audio generation", "image generation", "imagen").any(searchable::contains)) continue
                val capabilities = mutableListOf(Capability.Text)
                if (listOf("image", "vision", "multimodal").any(searchable::contains) || remoteId.startsWith("gemini-")) capabilities += Capability.Images
                if (listOf("code", "coding", "programmer").any(searchable::contains)) capabilities += Capability.Code
                if (listOf("thinking", "reasoning", "pro").any(searchable::contains)) capabilities += Capability.Reasoning
                val details = buildList<String> {
                    val inputLimit = item.optInt("inputTokenLimit", 0)
                    if (inputLimit > 0) {
                        add("Input ${inputLimit.withGrouping()} tokens")
                    }
                    val outputLimit = item.optInt("outputTokenLimit", 0)
                    if (outputLimit > 0) {
                        add("Output ${outputLimit.withGrouping()} tokens")
                    }
                }.joinToString(" · ")
                models += AiModel(
                    id = "gm:$remoteId",
                    name = name,
                    provider = ProviderId.Gemini,
                    vendor = "Google",
                    remoteId = remoteId,
                    description = listOf(description, details).filter { it.isNotBlank() }.joinToString("\n").ifBlank { "Available through the Gemini API." },
                    category = if (Capability.Reasoning in capabilities || Capability.Code in capabilities) ModelCategory.Advanced else ModelCategory.Everyday,
                    capabilities = capabilities,
                    mark = "G",
                    tone = Tone.Peach,
                )
            }
            pageToken = response.optString("nextPageToken").takeIf { it.isNotBlank() && it != "null" }
            pages++
        } while (!pageToken.isNullOrBlank() && pages < MAX_GEMINI_PAGES)

        models.distinctBy { it.remoteId }.sortedWith(modelNameComparator())
            .also { if (it.isEmpty()) throw ProviderException(null, "Google returned no models that support generateContent for this key.") }
    }

    private fun getJson(url: String, headers: Map<String, String>): JSONObject {
        val connection = Net.open(url, "GET", null, headers, readTimeoutMs = CATALOG_TIMEOUT_MS)
        return try {
            Net.requireSuccess(connection)
            val body = Net.readCapped(connection.inputStream, MAX_CATALOG_BYTES)
            if (body.isBlank()) throw ProviderException(null, "The provider returned an empty model list.")
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun requireKey(key: String, provider: ProviderId) {
        if (key.isBlank()) throw ProviderException(null, "${provider.displayName} API key is missing. Add it in Settings first.")
    }

    private fun stringArray(array: JSONArray?): Set<String> = buildSet {
        if (array == null) return@buildSet
        for (i in 0 until array.length()) array.optString(i).trim().lowercase(Locale.ROOT).takeIf { it.isNotBlank() }?.let(::add)
    }

    private fun isSafeRemoteId(value: String): Boolean =
        value.length <= 200 && value.matches(Regex("^[A-Za-z0-9._:/+-]+$"))

    private fun toneFor(vendor: String): Tone = when (vendor.lowercase(Locale.ROOT)) {
        "anthropic", "google", "gemini" -> Tone.Peach
        "deepseek", "qwen", "alibaba" -> Tone.Lavender
        "meta-llama", "meta", "mistralai", "microsoft" -> Tone.Mint
        else -> Tone.Cyan
    }

    /** OpenRouter prices are USD per token; multiply by 1M for readable per-million-token prices. */
    private fun formatCost(value: Double): String = if (value == 0.0) {
        "Free"
    } else {
        "\$" + String.format(Locale.US, "%.4f", value * 1_000_000)
    }

    private fun Int.withGrouping(): String = String.format(Locale.US, "%,d", this)

    private fun modelNameComparator(): Comparator<AiModel> =
        compareBy<AiModel> { it.name.lowercase(Locale.ROOT) }.thenBy { it.remoteId.lowercase(Locale.ROOT) }

    private companion object {
        const val CATALOG_TIMEOUT_MS = 30_000
        const val MAX_CATALOG_BYTES = 8_000_000
        const val OPENROUTER_PAGE_SIZE = 1000
        const val MAX_OPENROUTER_PAGES = 20
        const val MAX_GEMINI_PAGES = 20
    }
}
