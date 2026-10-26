package com.tanveer.aiva.voice.orb

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.tanveer.aiva.domain.models.VoiceState
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Locale

/**
 * Visual states understood by the orb page. [js] values are the exact strings in
 * `AIVAOrb.states` (assets/AIVA_Orb_Android_Ready.html); they are the only strings ever
 * placed into a script, so no user, model or provider text can reach the page.
 */
enum class OrbState(val js: String) {
    Idle("idle"),
    Listening("listening"),
    Processing("processing"),
    Speaking("speaking"),
    Muted("muted"),
    Error("error");

    companion object {
        /** Exhaustive on purpose: a new [VoiceState] will not compile until it is mapped here. */
        fun from(state: VoiceState): OrbState = when (state) {
            VoiceState.Idle -> Idle
            VoiceState.Listening -> Listening
            VoiceState.Processing -> Processing
            VoiceState.Speaking -> Speaking
            VoiceState.Muted -> Muted
            VoiceState.Error -> Error
        }
    }
}

enum class OrbStatus { Loading, Ready, Failed }

/**
 * Hosts the supplied HTML/WebGL orb inside the Live Voice screen and is the only code that talks
 * to it. Native code stays in charge of the microphone, recognizer, AI request and TTS; this class
 * only forwards their state ([setState]) and the recognizer's RMS level ([setAmplitude]).
 *
 * Page API used (window.AIVAOrbApp in the HTML): isReady, getStatus, setState, setAmplitude,
 * setOptions, pause, resume, reset. No JavaScript bridge is registered and the page has no
 * microphone access.
 *
 * All methods must be called on the main thread. If the page cannot be shown, [fallback] (the
 * existing static orb image) stays visible and [onStatusChanged] reports [OrbStatus.Failed].
 */
class OrbController(
    private val container: FrameLayout,
    private val fallback: View?,
    private val onStatusChanged: (OrbStatus, String?) -> Unit = { _, _ -> },
) {
    var status: OrbStatus = OrbStatus.Loading
        private set

    private val appContext: Context = container.context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: OrbWebView? = null
    private var released = false
    private var paused = false
    private var rendererRestarts = 0
    private var desiredState = OrbState.Idle
    private var appliedState: OrbState? = null
    private var lastAmplitudeSentAt = 0L

    private val readyPoll = Runnable { pollReady() }
    private val loadWatchdog = Runnable { fail("Orb page did not become ready in ${LOAD_TIMEOUT_MS / 1000}s") }

    /** Creates the WebView and loads the bundled page. Does not touch the microphone. */
    fun start() {
        if (released || webView != null || status == OrbStatus.Failed) return
        attachNewWebView()
    }

    /** Applies a voice state. Safe to call before the page is ready; the latest value wins. */
    fun setState(state: OrbState) {
        if (released) return
        val previous = desiredState
        desiredState = state
        if (status != OrbStatus.Ready) return
        // Never let a microphone level outlive the listening phase.
        if (previous == OrbState.Listening && state != OrbState.Listening) sendAmplitude(0f, force = true)
        pushState()
    }

    /** Forwards a normalised 0..1 microphone level; only used while listening, ~20 updates/s max. */
    fun setAmplitude(level: Float) {
        if (released || paused || status != OrbStatus.Ready || desiredState != OrbState.Listening) return
        sendAmplitude(level, force = false)
    }

    /** Stops animation work while the screen is not visible. */
    fun pause() {
        if (released || paused) return
        paused = true
        if (status == OrbStatus.Ready) runJs(JS_PAUSE)
        webView?.let { runCatching { it.onPause() } }
    }

    fun resume() {
        if (released || !paused) return
        paused = false
        webView?.let { runCatching { it.onResume() } }
        if (status == OrbStatus.Ready) {
            runJs(JS_RESUME)
            pushState()
        }
    }

    /** Returns the page to its idle look and clears any audio level. */
    fun reset() {
        if (released || status != OrbStatus.Ready) return
        desiredState = OrbState.Idle
        appliedState = OrbState.Idle
        runJs(JS_RESET)
    }

    /** Permanently releases the WebView and all callbacks. Call when the screen is torn down. */
    fun release() {
        if (released) return
        released = true
        mainHandler.removeCallbacksAndMessages(null)
        destroyWebView()
    }

    // ---------------------------------------------------------------- WebView lifecycle

    private fun attachNewWebView() {
        val view = try {
            OrbWebView(container.context)
        } catch (t: Throwable) {
            // Missing / updating system WebView package, etc.
            fail("WebView unavailable: ${t.javaClass.simpleName}")
            return
        }
        configure(view)
        view.visibility = View.INVISIBLE
        view.webViewClient = OrbClient(view)
        container.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        webView = view
        status = OrbStatus.Loading
        appliedState = null
        mainHandler.removeCallbacks(loadWatchdog)
        mainHandler.postDelayed(loadWatchdog, LOAD_TIMEOUT_MS)
        if (paused) runCatching { view.onPause() }
        view.loadUrl(ORB_URL)
    }

    private fun destroyWebView() {
        val view = webView ?: return
        webView = null
        runCatching { view.stopLoading() }
        runCatching { view.webViewClient = WebViewClient() }
        runCatching { (view.parent as? ViewGroup)?.removeView(view) }
        runCatching { view.removeAllViews() }
        runCatching { view.destroy() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun configure(view: WebView) {
        view.setBackgroundColor(Color.TRANSPARENT)
        with(view.settings) {
            // JavaScript is required by the trusted, bundled orb page and nothing else.
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
            // The app has a dark theme; stop WebView from "force darkening" the orb's colours.
            if (Build.VERSION.SDK_INT >= 33) {
                isAlgorithmicDarkeningAllowed = false
            } else if (Build.VERSION.SDK_INT >= 29) {
                forceDark = WebSettings.FORCE_DARK_OFF
            }
        }
    }

    // ---------------------------------------------------------------- readiness

    private fun pollReady() {
        val view = webView ?: return
        if (released || status != OrbStatus.Loading) return
        try {
            view.evaluateJavascript(JS_PROBE) { raw ->
                if (released || view !== webView || status != OrbStatus.Loading) return@evaluateJavascript
                when (val renderer = raw.orEmpty().trim('"')) {
                    "none" -> fail("Orb page reported no usable renderer")
                    "", "null", "pending" -> mainHandler.postDelayed(readyPoll, READY_POLL_MS)
                    else -> onReady(renderer)
                }
            }
        } catch (t: Throwable) {
            fail("Orb readiness check failed: ${t.javaClass.simpleName}")
        }
    }

    private fun onReady(renderer: String) {
        val view = webView ?: return
        mainHandler.removeCallbacks(loadWatchdog)
        status = OrbStatus.Ready
        if (renderer == "canvas2d") {
            Log.w(TAG, "WebGL unavailable on this device; orb is using the page's Canvas2D fallback.")
        }
        runJs(JS_OPTIONS)
        if (paused) runJs(JS_PAUSE)
        pushState()
        view.visibility = View.VISIBLE
        fallback?.visibility = View.GONE
        onStatusChanged(OrbStatus.Ready, renderer)
    }

    private fun fail(reason: String) {
        if (released || status == OrbStatus.Failed) return
        Log.w(TAG, "Orb unavailable: $reason")
        status = OrbStatus.Failed
        mainHandler.removeCallbacks(readyPoll)
        mainHandler.removeCallbacks(loadWatchdog)
        destroyWebView()
        fallback?.visibility = View.VISIBLE
        onStatusChanged(OrbStatus.Failed, reason)
    }

    private fun failAsync(reason: String) {
        mainHandler.post { fail(reason) }
    }

    private fun handleRendererGone(view: WebView) {
        if (released || view !== webView) return
        destroyWebView()
        fallback?.visibility = View.VISIBLE
        if (rendererRestarts < MAX_RENDERER_RESTARTS) {
            rendererRestarts++
            Log.w(TAG, "Orb WebView renderer terminated; restarting ($rendererRestarts/$MAX_RENDERER_RESTARTS).")
            attachNewWebView()
        } else {
            fail("WebView renderer terminated repeatedly")
        }
    }

    // ---------------------------------------------------------------- page calls (allowlisted, no user text)

    private fun pushState() {
        val state = desiredState
        if (state == appliedState) return
        appliedState = state
        runJs("window.AIVAOrbApp&&window.AIVAOrbApp.setState('${state.js}')") { result ->
            if (result == "false") Log.w(TAG, "Orb page rejected state '${state.js}'")
        }
    }

    private fun sendAmplitude(level: Float, force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastAmplitudeSentAt < AMPLITUDE_MIN_INTERVAL_MS) return
        lastAmplitudeSentAt = now
        val value = if (level.isNaN()) 0f else level.coerceIn(0f, 1f)
        runJs("window.AIVAOrbApp&&window.AIVAOrbApp.setAmplitude(" + String.format(Locale.US, "%.3f", value) + ")")
    }

    private fun runJs(script: String, callback: ((String?) -> Unit)? = null) {
        val view = webView ?: return
        try {
            view.evaluateJavascript(script, callback?.let { cb -> ValueCallback<String> { result -> cb(result) } })
        } catch (t: Throwable) {
            Log.w(TAG, "Orb JavaScript call failed: ${t.javaClass.simpleName}")
        }
    }

    // ---------------------------------------------------------------- WebViewClient

    private inner class OrbClient(private val owner: WebView) : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            return if (request.method == "GET" && request.url.toString() == ORB_URL) orbPage() else blocked()
        }

        // Navigation is never needed after the first load; refuse all of it (API 24+ and API 21-23 variants).
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

        @Suppress("OVERRIDE_DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean = true

        override fun onPageFinished(view: WebView, url: String?) {
            if (view === webView && url == ORB_URL) mainHandler.post(readyPoll)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame && view === webView) failAsync("Orb page failed to load (${error.errorCode})")
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
            if (Build.VERSION.SDK_INT < 23 && failingUrl == ORB_URL && view === webView) failAsync("Orb page failed to load ($errorCode)")
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (request.isForMainFrame && view === webView) failAsync("Orb page returned HTTP ${errorResponse.statusCode}")
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Returning true keeps the app alive; the WebView must be discarded and rebuilt.
            mainHandler.post { handleRendererGone(owner) }
            return true
        }

        private fun orbPage(): WebResourceResponse? = try {
            WebResourceResponse(
                "text/html",
                "utf-8",
                200,
                "OK",
                mapOf("Cache-Control" to "no-store", "Content-Security-Policy" to PAGE_CSP),
                appContext.assets.open(ASSET_NAME),
            )
        } catch (e: IOException) {
            failAsync("Orb asset missing: $ASSET_NAME")
            blocked()
        }

        private fun blocked() = WebResourceResponse(
            "text/plain", "utf-8", 404, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)),
        )
    }

    /** Orb surface only: never takes focus, scrolls, or consumes touches meant for native controls. */
    private class OrbWebView(context: Context) : WebView(context) {
        init {
            isClickable = false
            isLongClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
            isHapticFeedbackEnabled = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onTouchEvent(event: MotionEvent?): Boolean = false
    }

    companion object {
        private const val TAG = "AivaOrb"

        /** File in the app's assets folder. */
        const val ASSET_NAME = "AIVA_Orb_Android_Ready.html"

        /**
         * Virtual https origin. Requests for it are answered by [OrbClient.shouldInterceptRequest] from
         * the bundled asset only; nothing is ever fetched from the network and every other URL is refused.
         */
        const val ORB_URL = "https://appassets.androidplatform.net/assets/aiva_orb.html"

        private const val PAGE_CSP = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
            "img-src 'none'; connect-src 'none'; font-src 'none'; media-src 'none'; object-src 'none'; " +
            "base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

        private const val READY_POLL_MS = 100L
        private const val LOAD_TIMEOUT_MS = 10_000L
        private const val MAX_RENDERER_RESTARTS = 1

        /** 50 ms => at most 20 amplitude updates per second (spec: 15-25). */
        private const val AMPLITUDE_MIN_INTERVAL_MS = 50L

        /**
         * Noise gate applied inside the page (AIVAOrb option `gate`, 0..0.5) on top of
         * [com.tanveer.aiva.data.remote.inputLevelFromRms]. Starting value; tune on a real device.
         */
        private const val INPUT_NOISE_GATE = "0.10"

        private const val JS_PROBE =
            "(function(){var a=window.AIVAOrbApp;if(!a||!a.isReady())return 'pending';" +
                "var s=a.getStatus();return (s&&s.renderer)||'none';})()"
        private const val JS_OPTIONS = "window.AIVAOrbApp&&window.AIVAOrbApp.setOptions({gate:$INPUT_NOISE_GATE})"
        private const val JS_PAUSE = "window.AIVAOrbApp&&window.AIVAOrbApp.pause()"
        private const val JS_RESUME = "window.AIVAOrbApp&&window.AIVAOrbApp.resume()"
        private const val JS_RESET = "window.AIVAOrbApp&&window.AIVAOrbApp.reset()"
    }
}
