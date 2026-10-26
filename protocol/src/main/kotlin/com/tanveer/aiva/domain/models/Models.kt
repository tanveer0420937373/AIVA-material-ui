package com.tanveer.aiva.domain.models

enum class Role { User, Assistant }

/**
 * How an assistant message finished.
 * [Complete] is the only successful reply. [Partial] kept text from a cut-off stream.
 */
enum class Delivery { Complete, Partial, Stopped, Failed }

data class ChatMessage(
    val id: String,
    val role: Role,
    val text: String,
    val createdAt: Long,
    val isError: Boolean = false,
    val wasStopped: Boolean = false,
    val attachments: List<String> = emptyList(),
    /** Relative files under app-private image storage. Never remote URLs or content URIs. */
    val generatedImages: List<String> = emptyList(),
    val delivery: Delivery = Delivery.Complete,
)

data class ChatThread(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messages: List<ChatMessage>,
) {
    val preview: String
        get() = messages.lastOrNull()?.let { message ->
            message.text.ifBlank {
                when {
                    message.generatedImages.isNotEmpty() -> "Image"
                    message.attachments.isNotEmpty() -> "Image"
                    else -> ""
                }
            }
        }
            ?.replace(Regex("[*_`#>]"), "")
            ?.lineSequence()?.firstOrNull { it.isNotBlank() }
            ?.trim() ?: "No messages yet"
}

enum class ThemeMode { Light, Dark, System }

data class AppPreferences(
    val theme: ThemeMode = ThemeMode.Light,
    /** Empty until the user picks a catalog model. Never a demo id. */
    val modelId: String = "",
    /** Empty selects the device TTS default. */
    val voice: String = "",
    val speed: String = "Normal",
    val autoTitle: Boolean = true,
    val saveHistory: Boolean = true,
    val enterToSend: Boolean = false,
)

enum class SpeechPace(val label: String, val rate: Float) {
    Slow("Slow", 0.85f),
    Normal("Normal", 1.0f),
    Fast("Fast", 1.2f),
    ;

    companion object {
        fun fromLabel(label: String): SpeechPace = entries.firstOrNull { it.label == label } ?: Normal
        val labels: List<String> = entries.map { it.label }
    }
}

enum class Capability(val label: String) {
    Text("Text"), Images("Images"), Code("Code"), Reasoning("Reasoning"), ImageGeneration("Image generation")
}

/** Where a model's replies come from. Every production provider needs the user's own key. */
enum class ProviderId(val displayName: String, val needsKey: Boolean) {
    OpenRouter("OpenRouter", true),
    Gemini("Google AI Studio", true),
    HuggingFace("Hugging Face", true),
}

enum class ModelCategory(val label: String) { Everyday("Everyday"), Advanced("Advanced") }

enum class Tone { Lavender, Peach, Mint, Cyan }

data class AiModel(
    val id: String,
    val name: String,
    val provider: ProviderId,
    val vendor: String,
    /** Identifier sent to the provider API. */
    val remoteId: String,
    val description: String,
    val category: ModelCategory,
    val capabilities: List<Capability>,
    val mark: String,
    val tone: Tone,
    val unavailable: Boolean = false,
)

enum class VoiceState(val label: String, val hint: String) {
    Idle("Ready when you are", "Tap the microphone to speak"),
    Listening("Listening", "Speak naturally, then tap stop"),
    Processing("Thinking", "Sending your words to the selected model"),
    Speaking("Speaking", "Tap stop to interrupt"),
    Muted("Microphone muted", "Unmute, then tap start to listen again"),
    Error("Voice unavailable", "Check microphone permission and your provider settings"),
}
