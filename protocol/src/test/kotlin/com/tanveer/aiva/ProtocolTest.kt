package com.tanveer.aiva

import com.tanveer.aiva.data.local.ImageLimits
import com.tanveer.aiva.data.local.ModelCatalog
import com.tanveer.aiva.data.local.SnapshotPersister
import com.tanveer.aiva.data.local.ThreadCodec
import com.tanveer.aiva.data.local.plainSpeech
import com.tanveer.aiva.data.local.subsample
import com.tanveer.aiva.data.remote.DecodedImage
import com.tanveer.aiva.data.remote.EncodedImage
import com.tanveer.aiva.data.remote.HfOverrides
import com.tanveer.aiva.data.remote.ProviderException
import com.tanveer.aiva.data.remote.RetryPolicy
import com.tanveer.aiva.data.remote.SseEvent
import com.tanveer.aiva.data.remote.TranscriptTurn
import com.tanveer.aiva.data.remote.coalesceForGemini
import com.tanveer.aiva.data.remote.consumeSseLine
import com.tanveer.aiva.data.remote.contextMessages
import com.tanveer.aiva.data.remote.finishMeansPartial
import com.tanveer.aiva.data.remote.geminiBody
import com.tanveer.aiva.data.remote.geminiGeneratedImage
import com.tanveer.aiva.data.remote.geminiImageRequest
import com.tanveer.aiva.data.remote.geminiPieces
import com.tanveer.aiva.data.remote.httpFailure
import com.tanveer.aiva.data.remote.httpsEndpointError
import com.tanveer.aiva.data.remote.huggingFaceImageBody
import com.tanveer.aiva.data.remote.imageBytesError
import com.tanveer.aiva.data.remote.inputLevelFromRms
import com.tanveer.aiva.data.remote.keyFormatError
import com.tanveer.aiva.data.remote.openRouterBody
import com.tanveer.aiva.data.remote.openRouterFinish
import com.tanveer.aiva.data.remote.openRouterText
import com.tanveer.aiva.data.remote.orbUpdateScript
import com.tanveer.aiva.data.remote.redactSecrets
import com.tanveer.aiva.data.remote.resolveHuggingFace
import com.tanveer.aiva.data.remote.sniffImageMime
import com.tanveer.aiva.data.remote.visionBlockReason
import com.tanveer.aiva.domain.models.AiModel
import com.tanveer.aiva.domain.models.AppPreferences
import com.tanveer.aiva.domain.models.Capability
import com.tanveer.aiva.domain.models.ChatMessage
import com.tanveer.aiva.domain.models.ChatThread
import com.tanveer.aiva.domain.models.Delivery
import com.tanveer.aiva.domain.models.ModelCategory
import com.tanveer.aiva.domain.models.ProviderId
import com.tanveer.aiva.domain.models.Role
import com.tanveer.aiva.domain.models.Tone
import com.tanveer.aiva.domain.models.VoiceState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private val jpeg = EncodedImage("image/jpeg", "abc")

    @Test
    fun productionCatalogHasNoDemoOrRetiredIds() {
        assertEquals("", AppPreferences().modelId)
        assertTrue(ModelCatalog.production.isNotEmpty())
        assertTrue(ModelCatalog.production.none { it.unavailable })
        assertTrue(ModelCatalog.production.none { it.id == "aiva-demo" || it.remoteId == "demo" || it.remoteId == "gemini-2.0-flash" })
        assertTrue(ModelCatalog.production.none { it.remoteId == "anthropic/claude-3.5-sonnet" })
        assertTrue(ProviderId.entries.none { it.name == "Demo" })
        assertEquals("openai/gpt-4o-mini", ModelCatalog.findOrNull("gpt-4o-mini")!!.remoteId)
        assertEquals("gemini-3.8-flash", ModelCatalog.findOrNull("gemini-flash")!!.remoteId)
        assertEquals("anthropic/claude-sonnet-4.5", ModelCatalog.findOrNull("claude-sonnet")!!.remoteId)
        assertEquals("deepseek/deepseek-r1", ModelCatalog.findOrNull("deepseek-r1")!!.remoteId)
        assertEquals("gemini-nano-banana-2.1", ModelCatalog.findOrNull("aiva-canvas")!!.remoteId)
        assertEquals("meta-llama/Llama-3.1-8B-Instruct:fastest", ModelCatalog.findOrNull("llama-hf")!!.remoteId)
        assertFalse(ModelCatalog.findOrNull("deepseek-r1")!!.capabilities.contains(Capability.Images))
        assertEquals(listOf(Capability.ImageGeneration), ModelCatalog.findOrNull("aiva-canvas")!!.capabilities)
        assertEquals("", ModelCatalog.migrateId("aiva-demo"))
        assertEquals("", ModelCatalog.migrateId("not-a-model"))
        assertEquals("gpt-4o-mini", ModelCatalog.migrateId("gpt-4o-mini"))
        assertNull(ModelCatalog.findOrNull(""))
    }

    @Test
    fun threadCodecRoundTripLegacyAndSchemaGuard() {
        val thread = ChatThread(
            "id-1", "Title", 10L,
            listOf(
                ChatMessage(
                    "m", Role.User, "hi", 1L,
                    attachments = listOf("attachments/a.jpg"),
                    generatedImages = listOf("generated/b.png"),
                    delivery = Delivery.Partial,
                    wasStopped = true,
                ),
            ),
        )
        val encoded = ThreadCodec.encode(listOf(thread))
        assertTrue(encoded.contains("\"schema\":2"))
        assertEquals(thread.messages.single().delivery, ThreadCodec.decode(encoded).single().messages.single().delivery)

        val legacy = """[{"id":"old","title":"Old","updatedAt":5,"messages":[{"id":"u","role":"User","text":"hello","createdAt":1,"isError":true}]}]"""
        val old = ThreadCodec.decode(legacy).single().messages.single()
        assertEquals("hello", old.text)
        assertEquals(Delivery.Failed, old.delivery)
        assertTrue(old.attachments.isEmpty())

        try {
            ThreadCodec.decode("""{"schema":99,"threads":[]}""")
            throw AssertionError("expected newer schema to fail")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("99"))
        }
    }

    @Test
    fun staleHistorySnapshotsNeverWin() = runBlocking {
        val written = mutableListOf<String>()
        coroutineScope {
            val persister = SnapshotPersister<String>(this) { value ->
                delay(20)
                written += value
            }
            persister.submit("old")
            persister.submit("newer")
            persister.submit("cleared")
        }
        assertEquals(listOf("cleared"), written)
    }

    @Test
    fun openRouterBodyStreamsAndEncodesImages() {
        val turns = listOf(TranscriptTurn(Role.User, "look", listOf(jpeg), hadImages = true))
        val body = openRouterBody("openai/gpt-4o-mini", turns, allowImages = true)
        assertEquals(true, body.getBoolean("stream"))
        val content = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertTrue(content.getJSONObject(1).getJSONObject("image_url").getString("url").startsWith("data:image/jpeg;base64,"))

        val textOnly = openRouterBody("deepseek/deepseek-r1", turns, allowImages = false)
        assertTrue(textOnly.getJSONArray("messages").getJSONObject(0).getString("content").contains("does not accept images"))
    }

    @Test
    fun geminiCoalescesRolesAndSkipsThoughts() {
        val turns = listOf(
            TranscriptTurn(Role.Assistant, "earlier"),
            TranscriptTurn(Role.User, "one"),
            TranscriptTurn(Role.User, "two", hadImages = true),
            TranscriptTurn(Role.Assistant, "ok"),
        )
        val body = geminiBody(turns, allowImages = false)
        val contents = body.getJSONArray("contents")
        assertEquals(2, contents.length())
        assertEquals("user", contents.getJSONObject(0).getString("role"))
        assertTrue(contents.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text").contains("one"))

        val pieces = geminiPieces(
            """{"candidates":[{"content":{"parts":[{"thought":true,"text":"secret"},{"text":"Hi"}]}}]}""",
        )
        assertEquals(listOf("Hi"), pieces.map { it.text }.filter { it.isNotEmpty() })

        val blocked = geminiPieces("""{"promptFeedback":{"blockReason":"SAFETY"},"candidates":[]}""")
        assertEquals("Google blocked this prompt (SAFETY).", blocked.single().blocked)

        val empty = geminiPieces("""{"candidates":[{"content":{"parts":[]},"finishReason":"STOP"}]}""")
        assertTrue(empty.isEmpty() || empty.all { it.text.isEmpty() })
    }

    @Test
    fun geminiImageRequestAndParse() {
        val body = geminiImageRequest("gemini-nano-banana-2.1", "a red kite")
        assertEquals("gemini-nano-banana-2.1", body.getString("model"))
        assertEquals("a red kite", body.getJSONArray("input").getJSONObject(0).getString("text"))
        val image: DecodedImage = geminiGeneratedImage(
            """{"output_image":{"mime_type":"image/png","data":"aaaa"}}""",
        )
        assertEquals("image/png", image.mimeType)
        assertEquals("aaaa", image.base64)
        try {
            geminiImageRequest("gemini-nano-banana-2.1", "  ")
            throw AssertionError("blank prompt")
        } catch (e: ProviderException) {
            assertTrue(e.message!!.contains("Describe"))
        }
    }

    @Test
    fun errorsExposeRateLimitsAndRefuseSecretsInUrls() {
        val limited = httpFailure(429, """{"error":{"message":"slow down","code":429}}""", "3")
        assertTrue(limited.message!!.contains("Rate limited"))
        assertTrue(limited.message!!.contains("slow down"))
        assertEquals(3, limited.retryAfterSeconds)

        val denied = httpFailure(401, """{"error":{"message":"bad key"}}""", null)
        assertTrue(denied.message!!.contains("denied"))
        assertFalse(RetryPolicy.retryable(401, emitted = false))
        assertFalse(RetryPolicy.retryable(400, emitted = false))
        assertFalse(RetryPolicy.retryable(404, emitted = false))
        assertFalse(RetryPolicy.retryable(422, emitted = false))
        assertTrue(RetryPolicy.retryable(429, emitted = false))
        assertTrue(RetryPolicy.retryable(503, emitted = false))
        assertTrue(RetryPolicy.retryable(null, emitted = false))
        assertFalse(RetryPolicy.retryable(503, emitted = true))
        assertEquals(3_000L, RetryPolicy.delayMs(0, 3))

        val hf = httpFailure(503, """{"error":"loading","estimated_time":12.4}""", null)
        assertTrue(hf.message!!.contains("estimated wait"))

        assertNull(httpsEndpointError("  "))
        assertNull(httpsEndpointError("https://router.huggingface.co/v1/chat/completions"))
        assertTrue(httpsEndpointError("http://example.com")!!.contains("https"))
        assertTrue(httpsEndpointError("https://user:secret@example.com/v1")!!.contains("credentials"))
        assertTrue(httpsEndpointError("https://example.com/v1?api_key=abc")!!.contains("API keys"))
        assertEquals("[redacted]", redactSecrets("hf_abcdefghijklmnopqrstuvwxyz"))
        assertFalse(redactSecrets("Bearer sk-or-v1-abcdef123456").contains("sk-or-"))
    }

    @Test
    fun huggingFaceTargetsAndImageMagic() {
        val chat = AiModel(
            "hf", "Llama", ProviderId.HuggingFace, "Meta", "meta-llama/Llama-3.1-8B-Instruct:fastest",
            "", ModelCategory.Everyday, listOf(Capability.Text), "L", Tone.Mint,
        )
        val routed = resolveHuggingFace(chat, HfOverrides(chatEndpoint = "https://example.com/v1/chat/completions"))
        assertEquals("https://example.com/v1/chat/completions", routed.url)
        assertEquals(false, routed.image)

        val canvas = chat.copy(
            remoteId = "",
            capabilities = listOf(Capability.ImageGeneration),
        )
        val image = resolveHuggingFace(canvas, HfOverrides())
        assertTrue(image.url.contains("/hf-inference/models/black-forest-labs/FLUX.1-schnell"))
        assertEquals("a cat", huggingFaceImageBody("a cat").getString("inputs"))

        assertEquals("image/png", sniffImageMime(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 0, 0, 0)))
        assertEquals("image/jpeg", sniffImageMime(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)))
        assertNull(sniffImageMime("hello".toByteArray()))
        assertTrue(imageBytesError("""{"error":"gated"}""".toByteArray()).contains("gated"))
    }

    @Test
    fun openRouterDeltaErrorAndCutOff() {
        assertEquals(listOf("Hi"), openRouterText("""{"choices":[{"delta":{"content":"Hi"}}]}"""))
        assertEquals("length", openRouterFinish("""{"choices":[{"delta":{},"finish_reason":"length"}]}"""))
        assertTrue(finishMeansPartial("length"))
        assertFalse(finishMeansPartial("stop"))
        try {
            openRouterText("""{"error":{"message":"nope","code":401}}""")
            throw AssertionError("expected provider exception")
        } catch (e: ProviderException) {
            assertEquals(401, e.httpCode)
            assertEquals("nope", e.message)
        }
        assertEquals(SseEvent.Done, consumeSseLine("data: [DONE]"))
        assertTrue(consumeSseLine(": keep-alive") is SseEvent.Comment)
        assertTrue(consumeSseLine("id: 1") is SseEvent.Ignore)
        assertEquals("Hi", (consumeSseLine("data: Hi") as SseEvent.Data).data)
    }

    @Test
    fun capabilityRulesImagesAndSpeech() {
        val deepseek = ModelCatalog.findOrNull("deepseek-r1")!!
        assertTrue(visionBlockReason(deepseek, 1)!!.contains("only accepts text"))
        assertNull(visionBlockReason(ModelCatalog.findOrNull("gpt-4o-mini")!!, 1))
        assertTrue(visionBlockReason(ModelCatalog.findOrNull("aiva-canvas")!!, 1)!!.contains("Canvas"))
        val kept = contextMessages(
            listOf(
                ChatMessage("e", Role.Assistant, "nope", 1L, isError = true, delivery = Delivery.Failed),
                ChatMessage("u", Role.User, "hello", 2L),
            ),
        )
        assertEquals(listOf("u"), kept.map { it.id })
        assertEquals("code sample", plainSpeech("```kotlin\nsecret``` **code** sample"))
        assertTrue(plainSpeech("x".repeat(4000)).length <= 3500)
        assertEquals(1, subsample(100, 100))
        assertTrue(subsample(8000, 6000, ImageLimits.MAX_EDGE_PX) >= 4)
        assertEquals(0f, inputLevelFromRms(-2f), 0.001f)
        assertEquals(1f, inputLevelFromRms(20f), 0.001f)
        assertNull(keyFormatError("sk-or-v1-real-key"))
        assertTrue(keyFormatError("  ")!!.contains("Enter"))
        assertTrue(keyFormatError("https://example.com/key")!!.contains("web address"))
        assertEquals(VoiceState.Idle, VoiceState.Idle)
    }

    @Test
    fun orbScriptCarriesLevelsWithoutClaimingAudio() {
        val script = orbUpdateScript("Listening", 1.4f, -1f)
        assertTrue(script.startsWith("window.aivaOrb && window.aivaOrb.update("))
        val json = JSONObject(script.substringAfter("update(").removeSuffix(";"))
        assertEquals("listening", json.getString("state"))
        assertEquals(1.0, json.getDouble("input"), 0.001)
        assertEquals(0.0, json.getDouble("output"), 0.001)
        assertTrue(JSONArray().toString().isNotEmpty())
    }
}
