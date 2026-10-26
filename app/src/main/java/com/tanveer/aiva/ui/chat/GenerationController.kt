package com.tanveer.aiva.ui.chat

import com.tanveer.aiva.data.local.newMessage
import com.tanveer.aiva.data.local.withDelivery
import com.tanveer.aiva.data.remote.ReplyDelta
import com.tanveer.aiva.data.remote.visionBlockReason
import com.tanveer.aiva.data.repositories.AiRepository
import com.tanveer.aiva.data.repositories.ChatRepository
import com.tanveer.aiva.data.repositories.ModelRepository
import com.tanveer.aiva.data.repositories.SettingsRepository
import com.tanveer.aiva.domain.models.ChatMessage
import com.tanveer.aiva.domain.models.Delivery
import com.tanveer.aiva.domain.models.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One in-flight provider call for the process. Stop cancels the socket.
 * A cut-off stream that already produced text is saved as [Delivery.Partial], never as a success.
 */
class GenerationController(
    private val chats: ChatRepository,
    private val ai: AiRepository,
    private val models: ModelRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val _activeThreadId = MutableStateFlow<String?>(null)
    private val _activeMessageId = MutableStateFlow<String?>(null)
    val activeThreadId: StateFlow<String?> = _activeThreadId.asStateFlow()
    val activeMessageId: StateFlow<String?> = _activeMessageId.asStateFlow()
    private var job: Job? = null

    fun send(threadId: String, text: String, attachments: List<String>, onFinished: ((ChatMessage?) -> Unit)? = null) {
        if (job?.isActive == true) {
            onFinished?.invoke(newMessage(Role.Assistant, "Another response is already in progress. Stop it before starting a new one.").withDelivery(Delivery.Failed))
            return
        }
        val body = text.trim().ifBlank { if (attachments.isEmpty()) return else "Take a look at this image" }
        chats.addMessage(threadId, newMessage(Role.User, body, attachments))
        start(threadId, onFinished)
    }

    fun regenerate(threadId: String, messageId: String) {
        if (job?.isActive == true) return
        chats.removeMessagesFrom(threadId, messageId)
        start(threadId, null)
    }

    /** Answers a thread whose latest message is still the user's, exactly once. */
    fun ensureAnswer(threadId: String, onFinished: ((ChatMessage?) -> Unit)? = null) {
        if (job?.isActive == true) return
        val last = chats.thread(threadId)?.messages?.lastOrNull() ?: return
        if (last.role == Role.User) start(threadId, onFinished)
    }

    fun stop() {
        job?.cancel()
    }

    private fun start(threadId: String, onFinished: ((ChatMessage?) -> Unit)?) {
        val history = chats.thread(threadId)?.messages ?: return
        val modelId = settings.preferences.value?.modelId.orEmpty()
        val model = models.find(modelId)
        val attachmentCount = history.lastOrNull { it.role == Role.User }?.attachments?.size ?: 0
        if (model == null) {
            localError(threadId, "Choose a model and add its API key in Settings. Nothing was sent.", onFinished)
            return
        }
        if (!models.isReady(model)) {
            val needsImageKey = com.tanveer.aiva.domain.models.Capability.ImageGeneration in model.capabilities &&
                com.tanveer.aiva.domain.models.Capability.Text !in model.capabilities
            val message = if (needsImageKey) {
                "Image generation currently needs a Google AI Studio key. Hugging Face is temporarily on hold. Nothing was sent."
            } else {
                "${model.provider.displayName} needs an API key. Add one in Settings. Nothing was sent."
            }
            localError(threadId, message, onFinished)
            return
        }
        visionBlockReason(model, attachmentCount)?.let {
            localError(threadId, it, onFinished)
            return
        }
        val reply = newMessage(Role.Assistant, "")
        chats.addMessage(threadId, reply, persist = false)
        _activeThreadId.value = threadId
        _activeMessageId.value = reply.id
        job = scope.launch {
            val buffer = StringBuilder()
            val images = mutableListOf<String>()
            var partial = false
            try {
                ai.reply(model, history).collect { delta ->
                    if (chats.thread(threadId) == null) throw CancellationException("deleted")
                    when (delta) {
                        is ReplyDelta.Text -> {
                            buffer.append(delta.value)
                            chats.updateMessage(threadId, reply.id, persist = false) { it.copy(text = buffer.toString()) }
                        }
                        is ReplyDelta.Image -> {
                            val path = ai.storeGenerated(delta.bytes, delta.mimeType)
                            images += path
                            chats.updateMessage(threadId, reply.id, persist = false) {
                                it.copy(generatedImages = images.toList(), text = buffer.toString())
                            }
                        }
                        is ReplyDelta.End -> partial = delta.partial
                    }
                }
                if (buffer.isEmpty() && images.isEmpty()) {
                    commit(threadId, reply.id, "The model returned an empty reply.", Delivery.Failed, images)
                } else {
                    commit(threadId, reply.id, buffer.toString(), if (partial) Delivery.Partial else Delivery.Complete, images)
                }
            } catch (e: CancellationException) {
                commit(threadId, reply.id, buffer.toString(), Delivery.Stopped, images)
                throw e
            } catch (e: Exception) {
                if (buffer.isNotEmpty() || images.isNotEmpty()) {
                    commit(threadId, reply.id, buffer.toString(), Delivery.Partial, images)
                } else {
                    commit(threadId, reply.id, e.message ?: "Something went wrong. Please try again.", Delivery.Failed, images)
                }
            } finally {
                val message = chats.thread(threadId)?.messages?.lastOrNull { it.id == reply.id }
                if (_activeMessageId.value == reply.id) {
                    _activeMessageId.value = null
                    _activeThreadId.value = null
                }
                onFinished?.invoke(message)
            }
        }
    }

    private fun localError(threadId: String, text: String, onFinished: ((ChatMessage?) -> Unit)?) {
        val message = newMessage(Role.Assistant, text).withDelivery(Delivery.Failed)
        chats.addMessage(threadId, message)
        onFinished?.invoke(message)
    }

    private fun commit(threadId: String, messageId: String, text: String, delivery: Delivery, images: List<String>) {
        val shown = when {
            text.isNotBlank() -> text
            delivery == Delivery.Stopped -> ""
            delivery == Delivery.Failed -> "Something went wrong. Please try again."
            images.isNotEmpty() -> ""
            else -> text
        }
        chats.updateMessage(threadId, messageId, persist = true) { message ->
            message.withDelivery(delivery, shown, images)
        }
    }
}
