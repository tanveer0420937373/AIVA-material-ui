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
 * - Hugging Face provider is intentionally paused in the current build and is not selectable.
 *
 * Bundled entries are emergency/cache fallbacks only; live provider model lists replace them after refresh.
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
            description = "AIVA image-generation profile using Google AI Studio. Hugging Face integration is currently on hold.",
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
        "llama-hf",
    )

    fun findOrNull(id: String): AiModel? = production.firstOrNull { it.id == id }

    private val DYNAMIC_MODEL_ID = Regex("^(or|gm):[A-Za-z0-9._:/+-]{1,200}$")

    /** Maps a persisted id to a real catalog id, or blank when the user must choose again. */
    fun migrateId(stored: String?): String {
        val id = stored?.trim().orEmpty()
        if (id.isEmpty() || id in retiredIds) return ""
        if (findOrNull(id) != null) return id
        // Dynamic provider model IDs survive process restarts; the live catalog resolves them later.
        return if (DYNAMIC_MODEL_ID.matches(id)) id else ""
    }
}
