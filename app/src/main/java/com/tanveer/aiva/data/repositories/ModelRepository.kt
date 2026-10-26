package com.tanveer.aiva.data.repositories

import android.content.Context
import com.tanveer.aiva.core.security.SecureKeyStore
import com.tanveer.aiva.data.local.ModelCatalog
import com.tanveer.aiva.data.remote.ProviderModelDirectory
import com.tanveer.aiva.domain.models.AiModel
import com.tanveer.aiva.domain.models.Capability
import com.tanveer.aiva.domain.models.ModelCategory
import com.tanveer.aiva.domain.models.ProviderId
import com.tanveer.aiva.domain.models.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class ModelProviderStatus(
    val loading: Boolean = false,
    val message: String = "",
    val count: Int = 0,
    val live: Boolean = false,
    val cached: Boolean = false,
)

/** Dynamic model directory with an on-device metadata cache; credentials are never cached here. */
class ModelRepository(context: Context, private val keys: SecureKeyStore) {
    private val cache = context.getSharedPreferences("aiva_model_directory", Context.MODE_PRIVATE)
    private val directory = ProviderModelDirectory()
    private val cacheLock = Any()

    private val _statuses = MutableStateFlow(initialStatuses())
    val statuses: StateFlow<Map<ProviderId, ModelProviderStatus>> = _statuses.asStateFlow()

    private val _catalog = MutableStateFlow(buildCatalog())
    val catalog: StateFlow<List<AiModel>> = _catalog.asStateFlow()
    val models: List<AiModel> get() = _catalog.value

    fun find(id: String): AiModel? {
        val current = _catalog.value.firstOrNull { it.id == id }
        if (current != null) return current
        val legacy = ModelCatalog.findOrNull(id) ?: return null
        val key = cacheKey(legacy.provider)
        // If a live catalog was loaded, don't silently revive a model removed by the provider.
        if (cache.contains(key) && !(legacy.provider == ProviderId.Gemini && Capability.ImageGeneration in legacy.capabilities)) return null
        return legacy.takeIf { it.provider != ProviderId.HuggingFace }
    }

    fun isReady(model: AiModel?): Boolean {
        if (model == null || model.unavailable || model.provider == ProviderId.HuggingFace) return false
        if (Capability.ImageGeneration in model.capabilities && Capability.Text !in model.capabilities) {
            return ProviderId.Gemini in keys.configured.value
        }
        return model.provider in keys.configured.value
    }

    suspend fun refresh(provider: ProviderId) {
        if (provider == ProviderId.HuggingFace) {
            setStatus(provider, ModelProviderStatus(message = "Hugging Face is temporarily on hold and is not available in model selection."))
            return
        }
        // Rapid provider-tab taps must not start duplicate directory downloads.
        if (_statuses.value[provider]?.loading == true) return
        val apiKey = keys.get(provider)
        if (apiKey.isNullOrBlank()) {
            val cachedModels = models.filter { it.provider == provider && isListedChatModel(it) }
            val hasSavedCatalog = cache.contains(cacheKey(provider))
            setStatus(provider, ModelProviderStatus(
                message = when {
                    hasSavedCatalog -> "Add this provider's API key in Settings to refresh the live catalog. Showing ${cachedModels.size} saved models."
                    cachedModels.isNotEmpty() -> "Add this provider's API key in Settings to load all models. Showing ${cachedModels.size} bundled examples, not the complete list."
                    else -> "Add this provider's API key in Settings to load its live model catalog."
                },
                count = cachedModels.size,
                cached = hasSavedCatalog,
            ))
            return
        }

        val previous = _statuses.value[provider] ?: ModelProviderStatus()
        setStatus(provider, previous.copy(loading = true, message = "Checking available models…"))
        try {
            val discovered = when (provider) {
                ProviderId.OpenRouter -> directory.openRouter(apiKey)
                ProviderId.Gemini -> directory.gemini(apiKey)
                ProviderId.HuggingFace -> emptyList()
            }.map(::assignStableId)
            synchronized(cacheLock) {
                cache.edit().putString(cacheKey(provider), modelsToJson(discovered).toString()).apply()
            }
            _catalog.value = buildCatalog()
            setStatus(provider, ModelProviderStatus(
                loading = false,
                message = "Loaded ${discovered.size} chat-compatible models · sorted A–Z",
                count = discovered.size,
                live = true,
                cached = true,
            ))
        } catch (cancelled: CancellationException) {
            setStatus(provider, previous.copy(loading = false, message = "Model refresh cancelled."))
            throw cancelled
        } catch (failure: Exception) {
            val hasCache = cache.contains(cacheKey(provider))
            val available = models.count { it.provider == provider && isListedChatModel(it) }
            val reason = safeMessage(failure)
            setStatus(provider, ModelProviderStatus(
                loading = false,
                message = if (hasCache) "Live refresh failed; showing $available saved models. $reason" else "Live catalog could not be loaded. Any visible entries are bundled examples, not the complete provider list. $reason",
                count = available,
                live = false,
                cached = hasCache,
            ))
        }
    }

    private fun assignStableId(model: AiModel): AiModel {
        val legacy = ModelCatalog.production.firstOrNull {
            it.provider == model.provider && it.remoteId == model.remoteId &&
                Capability.Text in it.capabilities && Capability.ImageGeneration !in it.capabilities
        }
        if (legacy != null) return model.copy(id = legacy.id)
        val prefix = if (model.provider == ProviderId.OpenRouter) "or" else "gm"
        return model.copy(id = "$prefix:${model.remoteId}")
    }

    private fun buildCatalog(): List<AiModel> {
        val base = ModelCatalog.production.filter { it.provider != ProviderId.HuggingFace }
        val result = mutableListOf<AiModel>()
        listOf(ProviderId.OpenRouter, ProviderId.Gemini).forEach { provider ->
            val json = cache.getString(cacheKey(provider), null)
            val cachedModels = json?.let { raw -> runCatching { modelsFromJson(JSONArray(raw)) }.getOrNull() }
                ?.takeIf { it.isNotEmpty() }
            if (cachedModels != null) {
                result += cachedModels
            } else {
                // A damaged/obsolete catalog cache should not blank the picker completely.
                result += base.filter { it.provider == provider && isListedChatModel(it) }
            }
        }
        // AIVA Canvas is an app-specific image-generation profile, not a row returned by Gemini models.list.
        result += base.filter { it.provider == ProviderId.Gemini && Capability.ImageGeneration in it.capabilities }
        return result.distinctBy { it.id }
    }

    private fun initialStatuses(): Map<ProviderId, ModelProviderStatus> =
        listOf(ProviderId.OpenRouter, ProviderId.Gemini, ProviderId.HuggingFace).associateWith { provider ->
            val cached = readCached(provider)
            when {
                provider == ProviderId.HuggingFace -> ModelProviderStatus(message = "Hugging Face is temporarily on hold.")
                cached != null -> ModelProviderStatus(
                    message = "${cached.size} saved models · refreshing from provider when selected",
                    count = cached.size,
                    cached = true,
                )
                else -> ModelProviderStatus(message = "Select this provider to load its live model catalog.")
            }
        }

    private fun readCached(provider: ProviderId): List<AiModel>? {
        if (!cache.contains(cacheKey(provider))) return null
        return runCatching { modelsFromJson(JSONArray(cache.getString(cacheKey(provider), "[]"))) }.getOrDefault(emptyList())
    }

    private fun modelsToJson(models: List<AiModel>): JSONArray = JSONArray().apply {
        models.forEach { model ->
            put(JSONObject()
                .put("id", model.id)
                .put("name", model.name)
                .put("provider", model.provider.name)
                .put("vendor", model.vendor)
                .put("remoteId", model.remoteId)
                .put("description", model.description)
                .put("category", model.category.name)
                .put("capabilities", JSONArray(model.capabilities.map { it.name }))
                .put("mark", model.mark)
                .put("tone", model.tone.name)
            )
        }
    }

    private fun modelsFromJson(array: JSONArray): List<AiModel> = buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val provider = runCatching { ProviderId.valueOf(item.optString("provider")) }.getOrNull() ?: continue
            if (provider == ProviderId.HuggingFace) continue
            val id = item.optString("id")
            val remoteId = item.optString("remoteId")
            if (id.isBlank() || remoteId.isBlank()) continue
            val capabilities = item.optJSONArray("capabilities")?.let { caps ->
                buildList { for (c in 0 until caps.length()) runCatching { Capability.valueOf(caps.optString(c)) }.getOrNull()?.let(::add) }
            }.orEmpty().ifEmpty { listOf(Capability.Text) }
            add(AiModel(
                id = id,
                name = item.optString("name").ifBlank { remoteId },
                provider = provider,
                vendor = item.optString("vendor").ifBlank { provider.displayName },
                remoteId = remoteId,
                description = item.optString("description"),
                category = runCatching { ModelCategory.valueOf(item.optString("category")) }.getOrDefault(ModelCategory.Everyday),
                capabilities = capabilities,
                mark = item.optString("mark").ifBlank { provider.displayName.take(1) },
                tone = runCatching { Tone.valueOf(item.optString("tone")) }.getOrDefault(Tone.Cyan),
            ))
        }
    }

    private fun setStatus(provider: ProviderId, status: ModelProviderStatus) {
        _statuses.value = _statuses.value.toMutableMap().apply { put(provider, status) }
    }

    private fun cacheKey(provider: ProviderId): String = when (provider) {
        ProviderId.OpenRouter -> "catalog_openrouter"
        ProviderId.Gemini -> "catalog_gemini"
        ProviderId.HuggingFace -> "catalog_huggingface"
    }

    private fun isListedChatModel(model: AiModel): Boolean =
        Capability.Text in model.capabilities && Capability.ImageGeneration !in model.capabilities

    private fun safeMessage(failure: Exception): String {
        val message = failure.message.orEmpty().replace(Regex("(?i)Bearer\\s+[^\\s]+"), "Bearer [redacted]")
        return message.take(240).ifBlank { "Check your internet connection, API key, and provider access." }
    }

}
