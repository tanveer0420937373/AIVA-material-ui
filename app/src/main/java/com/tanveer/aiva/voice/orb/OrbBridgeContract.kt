package com.tanveer.aiva.voice.orb

import org.json.JSONObject
import com.tanveer.aiva.domain.models.VoiceState

/** Normalized state passed to the future HTML orb renderer. */
data class OrbFrame(val state: VoiceState, val inputLevel: Float = 0f, val outputLevel: Float = 0f)

/**
 * Message contract for the future HTML orb (see docs/ORB_INTEGRATION.md).
 *
 * Native → web only. The WebView renderer will call
 *   window.aivaOrb.update({"state":"listening","input":0.42,"output":0})
 * through evaluateJavascript, at most ~30 times per second. The page never gets microphone access;
 * its only callbacks are AivaOrbHost.onReady() / onError(message) on one minimal @JavascriptInterface.
 */
object OrbBridgeContract {
    const val ASSET_ENTRY = "file:///android_asset/orb/index.html"
    const val JS_HOST_NAME = "AivaOrbHost"

    fun updateScript(frame: OrbFrame): String {
        val json = JSONObject()
            .put("state", frame.state.name.lowercase())
            .put("input", frame.inputLevel.coerceIn(0f, 1f).toDouble())
            .put("output", frame.outputLevel.coerceIn(0f, 1f).toDouble())
        return "window.aivaOrb && window.aivaOrb.update($json);"
    }
}
