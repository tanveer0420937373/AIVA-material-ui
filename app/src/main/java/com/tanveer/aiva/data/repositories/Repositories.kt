package com.tanveer.aiva.data.repositories

import com.tanveer.aiva.core.security.SecureKeyStore
import com.tanveer.aiva.data.local.HistoryLoad
import com.tanveer.aiva.data.local.ImageStore
import com.tanveer.aiva.data.local.PreferencesStore
import com.tanveer.aiva.data.local.SnapshotPersister
import com.tanveer.aiva.data.local.ThreadStore
import com.tanveer.aiva.data.local.newMessage
import com.tanveer.aiva.data.remote.GeminiProvider
import com.tanveer.aiva.data.remote.OpenRouterProvider
import com.tanveer.aiva.data.remote.ProviderException
import com.tanveer.aiva.data.remote.ReplyDelta
import com.tanveer.aiva.data.remote.TranscriptTurn
import com.tanveer.aiva.data.remote.contextMessages
import com.tanveer.aiva.domain.models.AiModel
import com.tanveer.aiva.domain.models.AppPreferences
import com.tanveer.aiva.domain.models.Capability
import com.tanveer.aiva.domain.models.ChatMessage
import com.tanveer.aiva.domain.models.ChatThread
import com.tanveer.aiva.domain.models.ProviderId
import com.tanveer.aiva.domain.models.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

class SettingsRepository(private val store: PreferencesStore, scope: CoroutineScope) {
    val preferences: StateFlow<AppPreferences?> = store.preferences.stateIn(scope, SharingStarted.Eagerly, null)
    suspend fun update(transform: (AppPreferences) -> AppPreferences) = store.update(transform)
}

/**
 * In-memory conversations plus a single ordered writer.
 *
 * History off: the file is deleted immediately and further writes are barriers, but the open
 * session stays in memory until the process exits. The next cold start is empty.
 * First launch with no file does not invent sample chats.
 */
class ChatRepository(
    private val store: ThreadStore,
    private val settings: SettingsRepository,
    private val images: ImageStore,
    private val scope: CoroutineScope,
) {
    private val _threads = MutableStateFlow<List<ChatThread>>(emptyList())
    val threads: StateFlow<List<ChatThread>> = _threads.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val memory = Any()
    private var loadEpoch = 0
    private val persister = SnapshotPersister<List<ChatThread>?>(scope) { snapshot ->
        if (snapshot == null) store.clear() else store.save(snapshot)
    }

    init {
        scope.launch {
            val epoch = synchronized(memory) { loadEpoch }
            val prefs = settings.preferences.first { it != null }!!
            if (!prefs.saveHistory) {
                persister.submit(null)
                synchronized(memory) { _loaded.value = true }
                return@launch
            }
            when (val loaded = store.load()) {
                HistoryLoad.Missing -> Unit
                is HistoryLoad.Ready -> synchronized(memory) {
                    if (epoch != loadEpoch) return@synchronized
                    _threads.value = merge(loaded.threads, _threads.value)
                    images.deleteUnreferenced(referenced(_threads.value))
                }
                is HistoryLoad.Unreadable -> {
                    _notice.value = "Saved chats couldn’t be read. A recovery copy was kept on this phone."
                }
            }
            _loaded.value = true
        }
    }

    fun consumeNotice() { _notice.value = null }
    fun thread(id: String): ChatThread? = _threads.value.firstOrNull { it.id == id }

    fun createThread(firstMessage: String? = null, attachments: List<String> = emptyList()): String {
        val id = UUID.randomUUID().toString()
        val title = if (firstMessage != null && autoTitleEnabled()) titleFrom(firstMessage) else NEW_TITLE
        val msgs = firstMessage?.let { listOf(newMessage(Role.User, it, attachments)) } ?: emptyList()
        mutate { listOf(ChatThread(id, title, System.currentTimeMillis(), msgs)) + it }
        return id
    }

    fun addMessage(threadId: String, message: ChatMessage, persist: Boolean = true) = mutateThread(threadId, persist) { thread ->
        val retitle = thread.messages.isEmpty() && message.role == Role.User && thread.title == NEW_TITLE && autoTitleEnabled()
        thread.copy(
            title = if (retitle) titleFrom(message.text) else thread.title,
            messages = thread.messages + message,
        )
    }

    fun updateMessage(
        threadId: String,
        messageId: String,
        persist: Boolean = true,
        transform: (ChatMessage) -> ChatMessage,
    ) = mutateThread(threadId, persist) { thread ->
        thread.copy(messages = thread.messages.map { if (it.id == messageId) transform(it) else it })
    }

    fun removeMessagesFrom(threadId: String, messageId: String) {
        val removed = thread(threadId)?.messages?.let { messages ->
            val index = messages.indexOfFirst { it.id == messageId }
            if (index < 0) emptyList() else messages.drop(index)
        }.orEmpty()
        mutateThread(threadId) { thread ->
            val index = thread.messages.indexOfFirst { it.id == messageId }
            if (index < 0) thread else thread.copy(messages = thread.messages.take(index))
        }
        deleteImages(removed)
    }

    fun rename(threadId: String, title: String) =
        mutateThread(threadId) { it.copy(title = title.trim().ifBlank { it.title }) }

    fun delete(threadId: String) {
        val removed = thread(threadId)
        mutate { list -> list.filterNot { it.id == threadId } }
        if (removed != null) deleteImages(removed.messages)
    }

    fun clearAll() {
        val snapshot = synchronized(memory) {
            loadEpoch++
            val previous = _threads.value
            _threads.value = emptyList()
            previous
        }
        if (saving()) persister.submit(emptyList()) else persister.submit(null)
        snapshot.forEach { deleteImages(it.messages) }
    }

    /**
     * Turning history off deletes the file and blocks older queued snapshots from being rewritten.
     * Threads already on screen stay until the process exits.
     */
    fun onSaveHistoryChanged(enabled: Boolean) {
        if (enabled) {
            if (_threads.value.isNotEmpty()) persister.submit(_threads.value)
        } else {
            synchronized(memory) { loadEpoch++ }
            persister.submit(null)
        }
    }

    private fun autoTitleEnabled() = settings.preferences.value?.autoTitle ?: true
    private fun saving() = settings.preferences.value?.saveHistory != false

    private fun titleFrom(text: String) =
        text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.let { line ->
            if (line.length > 42) line.take(40).trimEnd() + "…" else line
        } ?: NEW_TITLE

    private fun mutateThread(id: String, persist: Boolean = true, transform: (ChatThread) -> ChatThread) {
        mutate(persist) { list ->
            list.map { if (it.id == id) transform(it).copy(updatedAt = System.currentTimeMillis()) else it }
                .sortedByDescending { it.updatedAt }
        }
    }

    private fun mutate(persist: Boolean = true, transform: (List<ChatThread>) -> List<ChatThread>) {
        val snapshot = synchronized(memory) {
            _threads.update(transform)
            _threads.value
        }
        if (persist && saving()) persister.submit(snapshot)
    }

    private fun deleteImages(messages: List<ChatMessage>) {
        val paths = messages.flatMap { it.attachments + it.generatedImages }
        images.delete(paths)
    }

    private fun referenced(threads: List<ChatThread>): Set<String> =
        threads.flatMap { thread -> thread.messages.flatMap { it.attachments + it.generatedImages } }.toSet()

    private fun merge(disk: List<ChatThread>, memory: List<ChatThread>): List<ChatThread> {
        if (memory.isEmpty()) return disk.sortedByDescending { it.updatedAt }
        val ids = memory.map { it.id }.toSet()
        return (memory + disk.filterNot { it.id in ids }).sortedByDescending { it.updatedAt }
    }

    private companion object { const val NEW_TITLE = "New conversation" }
}

class AiRepository(
    private val keys: SecureKeyStore,
    private val images: ImageStore,
    private val modelRepository: ModelRepository,
) {
    private val providers = mapOf(
        ProviderId.OpenRouter to OpenRouterProvider(),
        ProviderId.Gemini to GeminiProvider(),
    )

    fun reply(model: AiModel, history: List<ChatMessage>): Flow<ReplyDelta> = flow {
        if (isImageGenerator(model)) {
            emitImageWithFallback(model, history)
            return@flow
        }
        val apiKey = keyOrThrow(model.provider)
        if (model.provider == ProviderId.OpenRouter) {
            emitOpenRouterWithFallback(model, history, apiKey)
        } else {
            emitAll(providers.getValue(model.provider).stream(model, turns(model, history), apiKey))
        }
    }.flowOn(Dispatchers.IO)

    fun storeGenerated(bytes: ByteArray, mimeType: String): String = images.saveGenerated(bytes, mimeType)

    private suspend fun FlowCollector<ReplyDelta>.emitOpenRouterWithFallback(
        selected: AiModel,
        history: List<ChatMessage>,
        apiKey: String,
    ) {
        val hasImageAttachments = history.any { it.role == Role.User && it.attachments.isNotEmpty() }
        // Use the live OpenRouter catalog and keep fallback bounded so an outage cannot trigger
        // dozens of sequential requests when the provider exposes hundreds of models.
        val candidates = modelRepository.models
            .filter { candidate ->
                candidate.provider == ProviderId.OpenRouter &&
                    candidate.id != selected.id &&
                    !isImageGenerator(candidate) &&
                    Capability.Text in candidate.capabilities &&
                    (!hasImageAttachments || Capability.Images in candidate.capabilities)
            }
            .sortedWith(
                compareBy<AiModel> { if (it.category == selected.category) 0 else 1 }
                    .thenBy { if (Capability.Code in selected.capabilities && Capability.Code !in it.capabilities) 1 else 0 }
                    .thenBy { it.name.lowercase() },
            )
            .take(MAX_FALLBACK_MODELS)
        val chain = listOf(selected) + candidates
        var lastFailure: Exception? = null
        for ((index, candidate) in chain.withIndex()) {
            var emittedAny = false
            try {
                providers.getValue(ProviderId.OpenRouter)
                    .stream(candidate, turns(candidate, history), apiKey)
                    .collect { delta ->
                        emittedAny = true
                        emit(delta)
                    }
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val hasNext = index < chain.lastIndex
                if (!hasNext || emittedAny || !allowsModelFallback(failure)) throw failure
                lastFailure = failure
            }
        }
        throw lastFailure ?: ProviderException(null, "No compatible OpenRouter model could complete this request.")
    }

    /** Hugging Face is deliberately disabled while that provider is on hold. */
    private suspend fun FlowCollector<ReplyDelta>.emitImageWithFallback(
        selected: AiModel,
        history: List<ChatMessage>,
    ) {
        val googleKey = keys.get(ProviderId.Gemini)
        if (googleKey.isNullOrBlank()) {
            throw ProviderException(null, "Image generation currently requires a Google AI Studio key. Hugging Face is temporarily on hold.")
        }
        providers.getValue(ProviderId.Gemini)
            .stream(selected, turns(selected, history), googleKey)
            .collect { delta -> emit(delta) }
    }

    private fun keyOrThrow(provider: ProviderId): String {
        val apiKey = keys.get(provider)
        if (!apiKey.isNullOrBlank()) return apiKey
        val message = if (keys.wasUnreadable(provider)) {
            "The saved ${provider.displayName} key couldn’t be read. Enter it again in Settings. Nothing was sent."
        } else {
            "${provider.displayName} isn’t set up yet. Add an API key in Settings. Nothing was sent."
        }
        throw ProviderException(null, message)
    }

    private fun allowsModelFallback(failure: Exception): Boolean {
        val code = (failure as? ProviderException)?.httpCode ?: return false
        // Do not conceal invalid credentials or other permanent client errors.
        return code == 404 || code == 408 || code == 429 || code in 500..599
    }

    private fun isImageGenerator(model: AiModel): Boolean =
        Capability.ImageGeneration in model.capabilities && Capability.Text !in model.capabilities

    private fun turns(model: AiModel, history: List<ChatMessage>): List<TranscriptTurn> {
        val allowImages = Capability.Images in model.capabilities
        return contextMessages(history).takeLast(32).map { message ->
            val encoded = if (allowImages && message.role == Role.User && message.attachments.isNotEmpty()) {
                message.attachments.map { path ->
                    try {
                        images.encode(path)
                    } catch (e: IllegalArgumentException) {
                        throw ProviderException(null, e.message ?: "An attached image is no longer on this phone.")
                    }
                }
            } else {
                emptyList()
            }
            TranscriptTurn(
                role = message.role,
                text = message.text.take(24_000),
                images = encoded,
                hadImages = message.attachments.isNotEmpty(),
            )
        }
    }

    private companion object { const val MAX_FALLBACK_MODELS = 3 }
}
