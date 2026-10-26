package com.tanveer.aiva.data.local

import com.tanveer.aiva.domain.models.AiModel
import com.tanveer.aiva.domain.models.Capability
import com.tanveer.aiva.domain.models.ModelCategory
import com.tanveer.aiva.domain.models.ProviderId
import com.tanveer.aiva.domain.models.Tone

/**
 * Production catalog. Identifiers were checked on 14 Oct 2026:
 * - OpenRouter `GET /api/v1/model/{id}` for GPT-4o mini, Claude Sonnet 4.5, DeepSeek R1.
 * - Google AI docs for `gemini-3.8-flash` (`generateContent`) and `gemini-nano-banana-2.1` (Interactions image API).
 * - Hugging Face Inference Providers chat docs for `meta-llama/Llama-3.1-8B-Instruct` with the `:fastest` route.
 *
 * Retired and not shipped: `aiva-demo`, `gemini-2.0-flash` (shut down), `anthropic/claude-3.5-sonnet` (OpenRouter 404).
 * There is no offline demo model. Image generation is a separate capability, not a chat model.
 */
object ModelCatalog {
    val production: List<AiModel> = listOf(
        AiModel(
            id = "gpt-4o-mini",
            name = "GPT-4o mini",
            provider = ProviderId.OpenRouter,
            vendor = "OpenAI",
            remoteId = "openai/gpt-4o-mini",
            description = "Everyday conversation and image understanding through OpenRouter.",
            category = ModelCategory.Everyday,
            capabilities = listOf(Capability.Text, Capability.Images),
            mark = "◎",
            tone = Tone.Mint,
        ),
        AiModel(
            id = "gemini-flash",
            name = "Gemini Flash",
            provider = ProviderId.Gemini,
            vendor = "Google",
            remoteId = "gemini-3.8-flash",
            description = "Fast multimodal answers from Google AI Studio.",
            category = ModelCategory.Everyday,
            capabilities = listOf(Capability.Text, Capability.Images, Capability.Code),
            mark = "G",
            tone = Tone.Peach,
        ),
        AiModel(
            id = "llama-hf",
            name = "Llama 3.1 8B",
            provider = ProviderId.HuggingFace,
            vendor = "Meta",
            remoteId = "meta-llama/Llama-3.1-8B-Instruct:fastest",
            description = "Text chat through Hugging Face Inference Providers. This model does not accept images.",
            category = ModelCategory.Everyday,
            capabilities = listOf(Capability.Text, Capability.Code),
            mark = "L",
            tone = Tone.Mint,
        ),
        AiModel(
            id = "claude-sonnet",
            name = "Claude Sonnet 4.5",
            provider = ProviderId.OpenRouter,
            vendor = "Anthropic",
            remoteId = "anthropic/claude-sonnet-4.5",
            description = "Stronger writing and coding, with image understanding, through OpenRouter.",
            category = ModelCategory.Advanced,
            capabilities = listOf(Capability.Text, Capability.Images, Capability.Code),
            mark = "✳",
            tone = Tone.Peach,
        ),
        AiModel(
            id = "deepseek-r1",
            name = "DeepSeek R1",
            provider = ProviderId.OpenRouter,
            vendor = "DeepSeek",
            remoteId = "deepseek/deepseek-r1",
            description = "Reasoning model for harder problems. Text only — it cannot see images.",
            category = ModelCategory.Advanced,
            capabilities = listOf(Capability.Text, Capability.Reasoning, Capability.Code),
            mark = "R",
            tone = Tone.Lavender,
        ),
        AiModel(
            id = "aiva-canvas",
            name = "AIVA Canvas",
            provider = ProviderId.Gemini,
            vendor = "Google",
            remoteId = "gemini-nano-banana-2.1",
            description = "Image generation with Gemini Nano Banana 2.1. Needs a Google AI Studio key. Not a text chat model.",
            category = ModelCategory.Advanced,
            capabilities = listOf(Capability.ImageGeneration),
            mark = "◈",
            tone = Tone.Cyan,
        ),
    )

    /** Ids that older builds stored and that must not be treated as live models. */
    val retiredIds: Set<String> = setOf(
        "aiva-demo",
        "gemini-2.0-flash",
        "claude-3.5-sonnet",
    )

    fun findOrNull(id: String): AiModel? = production.firstOrNull { it.id == id }

    /** Maps a persisted id to a real catalog id, or blank when the user must choose again. */
    fun migrateId(stored: String?): String {
        val id = stored?.trim().orEmpty()
        if (id.isEmpty() || id in retiredIds) return ""
        return if (findOrNull(id) != null) id else ""
    }
}
