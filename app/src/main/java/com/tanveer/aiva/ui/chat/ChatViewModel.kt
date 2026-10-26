package com.tanveer.aiva.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tanveer.aiva.AppContainer
import com.tanveer.aiva.domain.models.AiModel
import com.tanveer.aiva.domain.models.AppPreferences
import com.tanveer.aiva.domain.models.ChatThread
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatUiState(
    val loaded: Boolean = false,
    val thread: ChatThread? = null,
    val model: AiModel? = null,
    val preferences: AppPreferences = AppPreferences(),
    val generatingMessageId: String? = null,
    val modelReady: Boolean = false,
)

class ChatViewModel(private val threadId: String, private val c: AppContainer) : ViewModel() {
    val state: StateFlow<ChatUiState> = combine(
        c.chats.threads,
        c.chats.loaded,
        c.settings.preferences,
        c.generation.activeMessageId,
        c.keys.configured,
    ) { threads, loaded, prefs, generatingId, _ ->
        val preferences = prefs ?: AppPreferences()
        val model = c.models.find(preferences.modelId)
        val generatingThread = c.generation.activeThreadId.value
        ChatUiState(
            loaded = loaded,
            thread = threads.firstOrNull { it.id == threadId },
            model = model,
            preferences = preferences,
            generatingMessageId = generatingId.takeIf { generatingThread == threadId },
            modelReady = c.models.isReady(model),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    init {
        viewModelScope.launch {
            c.chats.loaded.first { it }
            c.settings.preferences.first { it != null }
            c.generation.ensureAnswer(threadId)
        }
    }

    fun send(text: String, attachments: List<String>) = c.generation.send(threadId, text, attachments)
    fun stop() = c.generation.stop()
    fun regenerate(messageId: String) = c.generation.regenerate(threadId, messageId)
}
