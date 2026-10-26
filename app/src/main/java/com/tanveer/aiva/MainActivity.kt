package com.tanveer.aiva

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import android.text.Html
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tanveer.aiva.data.local.plainSpeech
import com.tanveer.aiva.data.remote.ProviderProbe
import com.tanveer.aiva.data.repositories.ModelProviderStatus
import com.tanveer.aiva.data.remote.keyFormatError
import com.tanveer.aiva.domain.models.AiModel
import com.tanveer.aiva.domain.models.AppPreferences
import com.tanveer.aiva.domain.models.ChatMessage
import com.tanveer.aiva.domain.models.ChatThread
import com.tanveer.aiva.domain.models.Delivery
import com.tanveer.aiva.domain.models.ModelCategory
import com.tanveer.aiva.domain.models.ProviderId
import com.tanveer.aiva.domain.models.Role
import com.tanveer.aiva.domain.models.SpeechPace
import com.tanveer.aiva.domain.models.ThemeMode
import com.tanveer.aiva.domain.models.VoiceState
import com.tanveer.aiva.ui.chat.ChatViewModel
import com.tanveer.aiva.ui.chat.ModelPickerAdapter
import com.tanveer.aiva.voice.orb.OrbController
import com.tanveer.aiva.voice.orb.OrbState
import com.tanveer.aiva.voice.orb.OrbStatus
import com.tanveer.aiva.voice.session.DeviceVoiceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Native XML implementation of the AIVA UI. Business logic and repositories remain separate. */
class MainActivity : AppCompatActivity() {
    private enum class Page { HOME, HISTORY, CHAT, MODELS, SETTINGS, VOICE }
    private data class Destination(val page: Page, val threadId: String? = null)
    private enum class AttachmentTarget { HOME, CHAT }

    private lateinit var container: FrameLayout
    private lateinit var bottomNavigation: LinearLayout
    private lateinit var app: AppContainer
    private lateinit var imagePicker: ActivityResultLauncher<String>
    private lateinit var audioPermission: ActivityResultLauncher<String>
    private lateinit var saveImageLauncher: ActivityResultLauncher<Intent>
    private var pendingSaveImagePath: String? = null
    private var currentPage = Page.HOME
    private var currentThreadId: String? = null
    private val backStack = mutableListOf<Destination>()
    private var screenJob: Job? = null
    private var voiceSession: DeviceVoiceSession? = null
    private var orbController: OrbController? = null
    private var orbFailureNotified = false
    private var voiceThreadId: String? = null
    private val voiceTranscript = MutableStateFlow("")
    private var attachmentTarget = AttachmentTarget.HOME
    private var homeAttachments = mutableListOf<String>()
    private var chatAttachments = mutableListOf<String>()
    private var settingsBinding = false
    private var settingsReady = false
    private val feedbackByMessage = mutableMapOf<String, Int>()
    private var lastThemeMode = ThemeMode.Light
    private var lastChatFingerprint: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val themeName = getSharedPreferences(UI_PREFS, MODE_PRIVATE).getString(KEY_THEME, ThemeMode.Light.name)
        AppCompatDelegate.setDefaultNightMode(themeModeToDelegate(runCatching { ThemeMode.valueOf(themeName ?: "Light") }.getOrDefault(ThemeMode.Light)))
        super.onCreate(savedInstanceState)
        currentPage = savedInstanceState?.getString(STATE_PAGE)?.let { saved ->
            runCatching { Page.valueOf(saved) }.getOrDefault(Page.HOME)
        } ?: Page.HOME
        currentThreadId = savedInstanceState?.getString(STATE_THREAD_ID)
        val savedPages = savedInstanceState?.getStringArrayList(STATE_BACK_STACK_PAGES).orEmpty()
        val savedThreads = savedInstanceState?.getStringArrayList(STATE_BACK_STACK_THREADS).orEmpty()
        backStack.clear()
        savedPages.forEachIndexed { index, name ->
            val page = runCatching { Page.valueOf(name) }.getOrNull()
            if (page != null) backStack += Destination(page, savedThreads.getOrNull(index)?.takeIf { it.isNotEmpty() })
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root_view)) { root, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            root.setPadding(0, bars.top, 0, maxOf(bars.bottom, ime.bottom))
            windowInsets
        }
        ViewCompat.requestApplyInsets(findViewById(R.id.root_view))
        app = (application as AivaApplication).container
        voiceSession = DeviceVoiceSession(this, ::handleFinalVoiceSpeech)
        container = findViewById<FrameLayout>(R.id.screen_host)
        bottomNavigation = findViewById<LinearLayout>(R.id.bottom_navigation)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        imagePicker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            val target = attachmentTarget
            lifecycleScope.launch {
                val current = if (target == AttachmentTarget.HOME) homeAttachments else chatAttachments
                val room = (MAX_ATTACHMENTS - current.size).coerceAtLeast(0)
                if (room == 0) {
                    toast("You can attach up to $MAX_ATTACHMENTS images")
                    return@launch
                }
                val accepted = mutableListOf<String>()
                val errors = mutableListOf<String>()
                withContext(Dispatchers.IO) {
                    uris.take(room).forEach { uri ->
                        try {
                            accepted += app.images.import(contentResolver, uri)
                        } catch (e: Exception) {
                            errors += e.message ?: "Couldn’t use that image"
                        }
                    }
                }
                if (uris.size > room) errors += "Only $MAX_ATTACHMENTS images can be attached"
                when (target) {
                    AttachmentTarget.HOME -> homeAttachments = (homeAttachments + accepted).distinct().take(MAX_ATTACHMENTS).toMutableList()
                    AttachmentTarget.CHAT -> chatAttachments = (chatAttachments + accepted).distinct().take(MAX_ATTACHMENTS).toMutableList()
                }
                refreshAttachmentUi(target)
                errors.firstOrNull()?.let { toast(it) }
            }
        }
        audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && currentPage == Page.VOICE) {
                voiceSession?.setContinuousMode(true)
                voiceSession?.startListening()
            } else if (!granted) {
                toast("Microphone permission is required for voice input.")
            }
        }
        saveImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val sourcePath = pendingSaveImagePath
            pendingSaveImagePath = null
            val targetUri = result.data?.data
            if (result.resultCode != RESULT_OK || targetUri == null || sourcePath.isNullOrBlank()) return@registerForActivityResult
            lifecycleScope.launch {
                val saved = withContext(Dispatchers.IO) {
                    val file = app.images.file(sourcePath) ?: return@withContext false
                    runCatching {
                        val output = contentResolver.openOutputStream(targetUri, "w") ?: error("Could not open destination")
                        file.inputStream().use { input -> output.use { destination -> input.copyTo(destination) } }
                        true
                    }.getOrDefault(false)
                }
                toast(if (saved) "Image saved" else "Couldn’t save image")
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })

        bindBottomNavigation()
        renderPage()
        lifecycleScope.launch {
            app.settings.preferences.collect { prefs ->
                val mode = prefs?.theme ?: return@collect
                if (mode != lastThemeMode) {
                    lastThemeMode = mode
                    val mirrored = getSharedPreferences(UI_PREFS, MODE_PRIVATE).getString(KEY_THEME, null)
                    if (mirrored != mode.name) {
                        getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().putString(KEY_THEME, mode.name).apply()
                        AppCompatDelegate.setDefaultNightMode(themeModeToDelegate(mode))
                    }
                }
            }
        }
    }

    private fun bindBottomNavigation() {
        findViewById<TextView>(R.id.nav_home).setOnClickListener { navigateToTab(Page.HOME) }
        findViewById<TextView>(R.id.nav_history).setOnClickListener { navigateToTab(Page.HISTORY) }
        findViewById<TextView>(R.id.nav_voice).setOnClickListener { navigate(Page.VOICE) }
        findViewById<TextView>(R.id.nav_settings).setOnClickListener { navigateToTab(Page.SETTINGS) }
    }

    private fun navigateToTab(page: Page) {
        backStack.clear()
        navigate(page, pushCurrent = false)
    }

    private fun navigate(page: Page, threadId: String? = null, pushCurrent: Boolean = true) {
        if (pushCurrent) backStack.add(Destination(currentPage, currentThreadId))
        currentPage = page
        currentThreadId = threadId
        renderPage()
    }

    private fun goBack() {
        if (backStack.isNotEmpty()) {
            val previous = backStack.removeAt(backStack.lastIndex)
            currentPage = previous.page
            currentThreadId = previous.threadId
            renderPage()
        } else if (currentPage != Page.HOME) {
            navigateToTab(Page.HOME)
        } else {
            finish()
        }
    }

    private fun renderPage() {
        // Voice capture/TTS must not continue after the user leaves the voice screen.
        if (currentPage != Page.VOICE) voiceSession?.pause()
        screenJob?.cancel()
        screenJob = null
        lastChatFingerprint = null
        // The orb WebView belongs to the voice screen; release it before its views are discarded.
        orbController?.release()
        orbController = null
        container.removeAllViews()
        val layoutId = when (currentPage) {
            Page.HOME -> R.layout.screen_home
            Page.HISTORY -> R.layout.screen_history
            Page.CHAT -> R.layout.screen_chat
            Page.MODELS -> R.layout.screen_models
            Page.SETTINGS -> R.layout.screen_settings
            Page.VOICE -> R.layout.screen_voice
        }
        val screen = LayoutInflater.from(this).inflate(layoutId, container, false)
        container.addView(screen, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bottomNavigation.visibility = if (currentPage in setOf(Page.HOME, Page.HISTORY, Page.MODELS, Page.SETTINGS)) View.VISIBLE else View.GONE
        updateNavigationSelection()
        when (currentPage) {
            Page.HOME -> setupHome(screen)
            Page.HISTORY -> setupHistory(screen)
            Page.CHAT -> setupChat(screen, currentThreadId ?: return)
            Page.MODELS -> setupModels(screen)
            Page.SETTINGS -> setupSettings(screen)
            Page.VOICE -> setupVoice(screen)
        }
    }

    private fun updateNavigationSelection() {
        val items = listOf(
            R.id.nav_home to Page.HOME,
            R.id.nav_history to Page.HISTORY,
            R.id.nav_voice to Page.VOICE,
            R.id.nav_settings to Page.SETTINGS,
        )
        items.forEach { (id, page) ->
            findViewById<TextView>(id).setTextColor(ContextCompat.getColor(this, if (currentPage == page) R.color.aiva_primary else R.color.aiva_muted))
        }
    }

    private fun setupHome(view: View) {
        val message = view.findViewById<EditText>(R.id.home_message)
        val search = view.findViewById<EditText>(R.id.home_search)
        val recent = view.findViewById<LinearLayout>(R.id.home_recent_list)
        view.findViewById<View>(R.id.home_models).setOnClickListener { navigate(Page.MODELS) }
        view.findViewById<View>(R.id.home_new_chat).setOnClickListener { navigate(Page.CHAT, app.chats.createThread()) }
        view.findViewById<View>(R.id.home_voice).setOnClickListener { navigate(Page.VOICE) }
        view.findViewById<View>(R.id.home_voice_promo).setOnClickListener { navigate(Page.VOICE) }
        view.findViewById<View>(R.id.home_see_all).setOnClickListener { navigate(Page.HISTORY) }
        val homeAttachmentCount = view.findViewById<TextView>(R.id.home_attachment_count)
        homeAttachmentCount.setOnClickListener(null)
        view.findViewById<View>(R.id.home_attach).setOnClickListener {
            attachmentTarget = AttachmentTarget.HOME
            imagePicker.launch("image/*")
        }
        view.findViewById<View>(R.id.home_send).setOnClickListener {
            val text = message.text.toString().trim()
            if (text.isBlank() && homeAttachments.isEmpty()) {
                toast("Write a message or attach an image first")
            } else if (app.generation.activeMessageId.value != null) {
                toast("Another response is already in progress. Stop it before starting a new one.")
            } else {
                val thread = app.chats.createThread(text.ifBlank { "Take a look at this image" }, homeAttachments.toList())
                homeAttachments.clear()
                navigate(Page.CHAT, thread)
            }
        }
        refreshAttachmentUi(AttachmentTarget.HOME)
        bindSuggestion(view, R.id.suggestion_weekend, "Plan a calm weekend")
        bindSuggestion(view, R.id.suggestion_studio, "Name my studio")
        bindSuggestion(view, R.id.suggestion_kotlin, "Explain a Kotlin function")
        bindSuggestion(view, R.id.suggestion_note, "Write a kind note")
        search.addTextChangedListener(simpleTextWatcher { query ->
            renderHomeRows(recent, app.chats.threads.value, app.chats.loaded.value, query)
        })
        observeScreen {
            combine(app.chats.threads, app.chats.loaded, app.settings.preferences) { threads, loaded, prefs -> Triple(threads, loaded, prefs) }
                .collect { (threads, loaded, prefs) ->
                    val selectedModel = app.models.find(prefs?.modelId.orEmpty())
                    view.findViewById<TextView>(R.id.home_models).text = selectedModel?.name?.take(13) ?: "Model"
                    renderHomeRows(recent, threads, loaded, search.text.toString())
                }
        }
    }


    private fun renderHomeRows(target: LinearLayout, threads: List<ChatThread>, loaded: Boolean, query: String) {
        target.removeAllViews()
        if (!loaded) {
            target.addView(labelView("Loading conversations…", true))
            return
        }
        val visible = threads.filter { matchesThread(it, query) }.take(6)
        if (visible.isEmpty()) {
            val message = when {
                query.isNotBlank() -> "No matches. Try a different word or phrase."
                threads.isEmpty() -> "No conversations yet. Start a chat and it will appear here."
                else -> "No conversations match this search."
            }
            target.addView(labelView(message, true))
            return
        }
        appendThreadRows(target, visible)
    }

    private fun bindSuggestion(root: View, id: Int, text: String) {
        root.findViewById<View>(id).setOnClickListener { navigate(Page.CHAT, app.chats.createThread(text)) }
    }

    private fun setupHistory(view: View) {
        val search = view.findViewById<EditText>(R.id.history_search)
        val list = view.findViewById<LinearLayout>(R.id.history_list)
        view.findViewById<View>(R.id.history_new_chat).setOnClickListener { navigate(Page.CHAT, app.chats.createThread()) }
        search.addTextChangedListener(simpleTextWatcher { query -> renderHistoryRows(list, app.chats.threads.value, query) })
        observeScreen {
            combine(app.chats.threads, app.chats.loaded, app.settings.preferences) { threads, loaded, prefs -> Triple(threads, loaded, prefs) }
                .collect { (threads, loaded, prefs) ->
                    view.findViewById<TextView>(R.id.history_subtitle).text = if (prefs?.saveHistory == false)
                        "History saving is off — saved chats have been removed." else "Saved on this device only."
                    if (loaded) renderHistoryRows(list, threads, search.text.toString())
                }
        }
    }

    private fun renderHistoryRows(target: LinearLayout, threads: List<ChatThread>, query: String) {
        target.removeAllViews()
        val filtered = threads.filter { matchesThread(it, query) }
        if (filtered.isEmpty()) {
            target.addView(labelView(if (query.isBlank()) "Nothing here yet. Start a conversation from Home." else "No matching conversations. Try another search.", true))
            return
        }
        val day = 86_400_000L
        val now = System.currentTimeMillis()
        val groups = listOf(
            "Today" to filtered.filter { now - it.updatedAt < day },
            "This week" to filtered.filter { now - it.updatedAt in day until 7 * day },
            "Older" to filtered.filter { now - it.updatedAt >= 7 * day },
        )
        groups.forEach { (heading, items) ->
            if (items.isNotEmpty()) {
                target.addView(sectionHeading(heading))
                appendThreadRows(target, items)
            }
        }
    }

    private fun setupModels(view: View) {
        val search = view.findViewById<EditText>(R.id.models_search)
        val list = view.findViewById<RecyclerView>(R.id.models_list)
        val emptyState = view.findViewById<TextView>(R.id.models_empty_state)
        val catalogStatus = view.findViewById<TextView>(R.id.models_catalog_status)
        val refresh = view.findViewById<TextView>(R.id.models_refresh)
        val openRouterButton = view.findViewById<TextView>(R.id.models_provider_openrouter)
        val geminiButton = view.findViewById<TextView>(R.id.models_provider_gemini)
        val huggingFaceButton = view.findViewById<TextView>(R.id.models_provider_huggingface)
        view.findViewById<View>(R.id.models_back).setOnClickListener { goBack() }

        val adapter = ModelPickerAdapter { model ->
            lifecycleScope.launch {
                runCatching { app.settings.update { it.copy(modelId = model.id) } }
                    .onSuccess { toast("${model.name} selected") }
                    .onFailure { toast("Could not select model: ${it.message ?: "settings error"}") }
            }
        }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.setItemViewCacheSize(8)

        var selectedProvider = app.models.find(app.settings.preferences.value?.modelId.orEmpty())?.provider
            ?.takeIf { it != ProviderId.HuggingFace } ?: ProviderId.OpenRouter

        fun redraw() {
            fun styleProvider(button: TextView, selected: Boolean) {
                button.background = ContextCompat.getDrawable(
                    this,
                    if (selected) R.drawable.bg_primary_pill else R.drawable.bg_chip_accent,
                )
                button.setTextColor(ContextCompat.getColor(this, if (selected) android.R.color.white else R.color.aiva_primary))
            }
            styleProvider(openRouterButton, selectedProvider == ProviderId.OpenRouter)
            styleProvider(geminiButton, selectedProvider == ProviderId.Gemini)
            huggingFaceButton.isEnabled = false
            huggingFaceButton.alpha = 0.55f
            val status = app.models.statuses.value[selectedProvider] ?: ModelProviderStatus()
            catalogStatus.text = status.message
            refresh.text = if (status.loading) "Loading…" else "Refresh"
            refresh.isEnabled = !status.loading
            renderModelRows(
                adapter = adapter,
                emptyState = emptyState,
                query = search.text.toString(),
                provider = selectedProvider,
                prefs = app.settings.preferences.value,
                configured = app.keys.configured.value,
                providerStatus = status,
                catalog = app.models.models,
            )
        }

        fun selectProvider(provider: ProviderId, fetch: Boolean = true) {
            if (provider == ProviderId.HuggingFace) {
                toast("Hugging Face is temporarily on hold.")
                return
            }
            selectedProvider = provider
            redraw()
            if (fetch) lifecycleScope.launch { app.models.refresh(provider) }
        }

        openRouterButton.setOnClickListener { selectProvider(ProviderId.OpenRouter) }
        geminiButton.setOnClickListener { selectProvider(ProviderId.Gemini) }
        huggingFaceButton.setOnClickListener { toast("Hugging Face is temporarily on hold.") }
        refresh.setOnClickListener { lifecycleScope.launch { app.models.refresh(selectedProvider) } }
        search.addTextChangedListener(simpleTextWatcher { redraw() })

        observeScreen {
            combine(app.models.catalog, app.settings.preferences, app.keys.configured, app.models.statuses) { _, _, _, _ -> Unit }
                .collect { redraw() }
        }
        selectProvider(selectedProvider)
    }

    private fun renderModelRows(
        adapter: ModelPickerAdapter,
        emptyState: TextView,
        query: String,
        provider: ProviderId,
        prefs: AppPreferences?,
        configured: Set<ProviderId>,
        providerStatus: ModelProviderStatus,
        catalog: List<AiModel>,
    ) {
        if (provider == ProviderId.HuggingFace) {
            adapter.submitModels(emptyList(), prefs?.modelId, configured)
            emptyState.text = "Hugging Face is temporarily on hold."
            emptyState.visibility = View.VISIBLE
            return
        }
        val normalizedQuery = query.trim().lowercase(java.util.Locale.ROOT)
        fun matches(model: AiModel): Boolean = normalizedQuery.isBlank() || listOf(
            model.name, model.vendor, model.provider.displayName, model.remoteId, model.description,
        ).any { it.lowercase(java.util.Locale.ROOT).contains(normalizedQuery) } ||
            model.capabilities.any { it.label.lowercase(java.util.Locale.ROOT).contains(normalizedQuery) }

        // Provider API models are filtered and sorted locally, so search spans the whole catalog.
        // RecyclerView only creates the rows that are visible on screen.
        val filtered = catalog
            .filter { it.provider == provider }
            .filter(::matches)
            .sortedWith(compareBy<AiModel> { it.name.lowercase(java.util.Locale.ROOT) }.thenBy { it.remoteId.lowercase(java.util.Locale.ROOT) })
        adapter.submitModels(filtered, prefs?.modelId, configured)
        if (filtered.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            emptyState.text = when {
                normalizedQuery.isNotBlank() -> "No models match “$query”. Search by model name or exact API ID."
                providerStatus.loading -> "Loading models from ${provider.displayName}…"
                else -> "No models available. Add the provider API key in Settings, then tap Refresh."
            }
        } else {
            emptyState.visibility = View.GONE
        }
    }

    private fun setupChat(view: View, threadId: String) {
        val vm = provideChatViewModel(threadId)
        val title = view.findViewById<TextView>(R.id.chat_title)
        val modelLabel = view.findViewById<TextView>(R.id.chat_model)
        val messages = view.findViewById<LinearLayout>(R.id.chat_messages)
        val scroll = view.findViewById<ScrollView>(R.id.chat_scroll)
        val input = view.findViewById<EditText>(R.id.chat_message)
        val send = view.findViewById<TextView>(R.id.chat_send)
        val stop = view.findViewById<TextView>(R.id.chat_stop)
        val warning = view.findViewById<TextView>(R.id.chat_warning)
        val attachmentCount = view.findViewById<TextView>(R.id.chat_attachment_count)

        view.findViewById<View>(R.id.chat_back).setOnClickListener { goBack() }
        view.findViewById<View>(R.id.chat_choose_model).setOnClickListener { navigate(Page.MODELS) }
        view.findViewById<View>(R.id.chat_voice).setOnClickListener { navigate(Page.VOICE) }
        view.findViewById<View>(R.id.chat_attach).setOnClickListener {
            attachmentTarget = AttachmentTarget.CHAT
            imagePicker.launch("image/*")
        }
        refreshAttachmentUi(AttachmentTarget.CHAT)
        send.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isBlank() && chatAttachments.isEmpty()) {
                toast("Write a message or attach an image first")
            } else if (app.generation.activeMessageId.value != null) {
                toast("Another response is already in progress. Stop it before sending a new message.")
            } else {
                vm.send(text, chatAttachments.toList())
                chatAttachments.clear()
                refreshAttachmentUi(AttachmentTarget.CHAT)
                input.text.clear()
            }
        }
        stop.setOnClickListener { vm.stop() }
        input.setOnEditorActionListener { _, actionId, _ ->
            val prefs = app.settings.preferences.value ?: AppPreferences()
            if (prefs.enterToSend && input.text.toString().isNotBlank()) {
                send.performClick()
                true
            } else false
        }
        observeScreen {
            vm.state.collect { state ->
                title.text = state.thread?.title ?: "Conversation"
                val model = state.model
                modelLabel.text = when {
                    model == null -> "No model selected"
                    state.modelReady -> "${model.name} · ${model.provider.displayName}"
                    else -> "${model.name} · not set up"
                }
                val showWarning = model == null || !state.modelReady
                warning.visibility = if (showWarning) View.VISIBLE else View.GONE
                if (showWarning) {
                    warning.text = if (model == null) {
                        "Choose a model and add its API key in Settings. Tap here to open Settings."
                    } else {
                        "${model.provider.displayName} needs an API key. Tap here to open Settings."
                    }
                    warning.setOnClickListener { navigate(Page.SETTINGS) }
                }
                send.visibility = if (state.generatingMessageId == null) View.VISIBLE else View.GONE
                stop.visibility = if (state.generatingMessageId != null) View.VISIBLE else View.GONE
                val fingerprint = state.thread?.messages?.joinToString("|") { "${it.id}:${it.text.length}:${it.isError}:${it.wasStopped}" } + ":${state.generatingMessageId}"
                if (fingerprint != lastChatFingerprint) {
                    lastChatFingerprint = fingerprint
                    renderChatMessages(messages, state.thread?.messages.orEmpty(), state.generatingMessageId, vm, state.loaded, state.thread != null)
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
    }

    /** Minimal, HTML-escaped Markdown formatting for native TextViews. */
    private fun markdownToSpanned(markdown: String): CharSequence {
        var html = markdown
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
        html = Regex("(?s)```(?:[A-Za-z0-9_+.-]*\\n)?(.*?)```").replace(html) { match ->
            "<tt>${match.groupValues[1].trimEnd('\n').replace("\n", "<br>")}</tt>"
        }
        html = Regex("\\*\\*(.+?)\\*\\*").replace(html, "<b>$1</b>")
        html = Regex("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)").replace(html, "<i>$1</i>")
        html = Regex("`([^`\\n]+)`").replace(html, "<tt>$1</tt>")
        html = Regex("(?m)^-\\s+").replace(html, "&#8226; ")
        html = html.replace("\n", "<br>")
        return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
    }

    private fun renderChatMessages(
        target: LinearLayout,
        items: List<ChatMessage>,
        generatingId: String?,
        vm: ChatViewModel,
        loaded: Boolean,
        threadExists: Boolean,
    ) {
        target.removeAllViews()
        if (!loaded) {
            target.addView(labelView("Loading conversation…", true))
            return
        }
        if (!threadExists) {
            target.addView(labelView("Conversation not found. It may have been deleted.", true))
            return
        }
        if (items.isEmpty()) {
            target.addView(labelView("A fresh start\nAsk anything to begin this conversation.", true))
            return
        }
        items.forEach { item ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_message, target, false)
            val user = item.role == Role.User
            row.background = ContextCompat.getDrawable(this, if (user) R.drawable.bg_message_user else R.drawable.bg_message_assistant)
            val width = if (user) (resources.displayMetrics.widthPixels * 0.78f).toInt() else ViewGroup.LayoutParams.MATCH_PARENT
            val params = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = if (user) Gravity.END else Gravity.START
                bottomMargin = dp(8)
            }
            row.layoutParams = params
            row.findViewById<TextView>(R.id.message_role).apply {
                text = if (user) "You" else "AIVA"
                setTextColor(color(if (user) android.R.color.white else R.color.aiva_primary))
            }
            row.findViewById<TextView>(R.id.message_text).apply {
                text = when {
                    !user && generatingId == item.id && item.text.isBlank() -> "Thinking…"
                    !user -> markdownToSpanned(item.text)
                    else -> item.text
                }
                setTextColor(color(if (user) android.R.color.white else R.color.aiva_text))
            }
            val attachment = row.findViewById<TextView>(R.id.message_attachment)
            val images = row.findViewById<LinearLayout>(R.id.message_images)
            val paths = item.attachments + item.generatedImages
            if (paths.isNotEmpty()) {
                images.removeAllViews()
                images.visibility = View.VISIBLE
                var missing = 0
                paths.forEach { path ->
                    val thumb = app.images.thumbnail(path, 320)
                    if (thumb == null) {
                        missing++
                    } else {
                        images.addView(imageThumb(thumb, item.generatedImages.contains(path)))
                    }
                }
                attachment.visibility = View.VISIBLE
                attachment.text = when {
                    missing == paths.size -> "Image unavailable"
                    missing > 0 -> "▧  ${paths.size - missing} image${if (paths.size - missing == 1) "" else "s"} · $missing unavailable"
                    else -> "▧  ${paths.size} image${if (paths.size == 1) "" else "s"}"
                }
                attachment.setTextColor(color(if (user) android.R.color.white else R.color.aiva_muted))
            } else {
                images.visibility = View.GONE
                attachment.visibility = View.GONE
            }
            if (!user && item.delivery == Delivery.Partial && item.text.isNotBlank()) {
                attachment.visibility = View.VISIBLE
                attachment.text = "Reply was cut off before it finished."
            }
            if (!user && item.delivery == Delivery.Stopped && item.text.isBlank()) {
                row.findViewById<TextView>(R.id.message_text).text = "Stopped."
            }
            val actions = row.findViewById<View>(R.id.message_actions)
            if (user || generatingId == item.id) actions.visibility = View.GONE else {
                actions.visibility = View.VISIBLE
                row.findViewById<View>(R.id.message_copy).setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("AIVA response", item.text))
                    toast("Copied to clipboard")
                }
                val like = row.findViewById<TextView>(R.id.message_like)
                val dislike = row.findViewById<TextView>(R.id.message_dislike)
                val applyFeedbackColors = {
                    val selectedColor = color(R.color.aiva_primary)
                    val normalColor = color(R.color.aiva_muted)
                    like.setTextColor(if ((feedbackByMessage[item.id] ?: 0) == 1) selectedColor else normalColor)
                    dislike.setTextColor(if ((feedbackByMessage[item.id] ?: 0) == -1) selectedColor else normalColor)
                }
                like.setOnClickListener {
                    feedbackByMessage[item.id] = if ((feedbackByMessage[item.id] ?: 0) == 1) 0 else 1
                    applyFeedbackColors()
                }
                dislike.setOnClickListener {
                    feedbackByMessage[item.id] = if ((feedbackByMessage[item.id] ?: 0) == -1) 0 else -1
                    applyFeedbackColors()
                }
                applyFeedbackColors()
                val regenerate = row.findViewById<TextView>(R.id.message_regenerate)
                regenerate.visibility = if (generatingId == null) View.VISIBLE else View.GONE
                regenerate.setOnClickListener { vm.regenerate(item.id) }
                val share = row.findViewById<TextView>(R.id.message_share)
                val save = row.findViewByIdOrNull<TextView>(R.id.message_save)
                val generatedPath = item.generatedImages.firstOrNull()
                val generated = generatedPath?.let { app.images.file(it) }
                share.visibility = if (generated != null) View.VISIBLE else View.GONE
                share.setOnClickListener { generated?.let { shareImage(it) } }
                save?.visibility = if (generated != null) View.VISIBLE else View.GONE
                save?.setOnClickListener { generatedPath?.let { saveGeneratedImage(it) } }
            }
            if (item.isError) row.background = ContextCompat.getDrawable(this, R.drawable.bg_danger_pill)
            target.addView(row)
        }
    }

    private fun provideChatViewModel(threadId: String): ChatViewModel {
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (!modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                    throw IllegalArgumentException("Unsupported ViewModel: ${modelClass.name}")
                }
                @Suppress("UNCHECKED_CAST")
                return ChatViewModel(threadId, app) as T
            }
        }
        return ViewModelProvider(this, factory).get("chat-$threadId", ChatViewModel::class.java)
    }

    private fun setupVoice(view: View) {
        val stateText = view.findViewById<TextView>(R.id.voice_state)
        val hintText = view.findViewById<TextView>(R.id.voice_hint)
        val transcriptView = view.findViewById<TextView>(R.id.voice_transcript)
        val orb = view.findViewById<ImageView>(R.id.voice_orb)
        val mic = view.findViewById<TextView>(R.id.voice_mic)
        val micLabel = view.findViewById<TextView>(R.id.voice_mic_label)
        val mute = view.findViewById<TextView>(R.id.voice_mute)
        val muteLabel = view.findViewById<TextView>(R.id.voice_mute_label)
        val interrupt = view.findViewById<TextView>(R.id.voice_interrupt)
        val interruptLabel = view.findViewById<TextView>(R.id.voice_interrupt_label)
        val transcriptToggle = view.findViewById<TextView>(R.id.voice_transcript_toggle)
        val session = voiceSession
        var transcriptExpanded = false

        // HTML orb: shown in place of the static image once the page reports ready. It is only
        // rendered and driven from native voice state; it never starts or touches the microphone.
        val orbHost = view.findViewById<FrameLayout>(R.id.voice_orb_container)
        val orbView = OrbController(orbHost, orb) { orbStatus, detail ->
            if (orbStatus == OrbStatus.Failed) {
                orb.contentDescription = "AIVA voice orb (animation unavailable)"
                if (!orbFailureNotified) {
                    orbFailureNotified = true
                    toast("Orb animation is unavailable${detail?.let { ": $it" } ?: ""}. Voice still works.")
                }
            }
        }
        orbController = orbView
        orbView.start()

        view.findViewById<View>(R.id.voice_close).setOnClickListener { goBack() }
        view.findViewById<View>(R.id.voice_settings).setOnClickListener { navigate(Page.SETTINGS) }
        view.findViewById<View>(R.id.voice_model).setOnClickListener { navigate(Page.MODELS) }
        transcriptToggle.setOnClickListener {
            transcriptExpanded = !transcriptExpanded
            transcriptView.maxLines = if (transcriptExpanded) 12 else 2
            transcriptToggle.text = if (transcriptExpanded) "Collapse" else "Expand"
        }

        mic.setOnClickListener {
            val s = voiceSession ?: return@setOnClickListener
            when (s.state.value) {
                VoiceState.Idle, VoiceState.Error -> {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        s.setContinuousMode(true)
                        s.startListening()
                    } else {
                        audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
                VoiceState.Muted -> s.setMuted(false)
                VoiceState.Listening -> toast("AIVA is already listening. Use End to finish the conversation.")
                VoiceState.Processing, VoiceState.Speaking -> toast("Use Interrupt to stop AIVA’s current response.")
            }
        }

        mute.setOnClickListener {
            val s = voiceSession ?: return@setOnClickListener
            when (s.state.value) {
                VoiceState.Muted -> {
                    s.setMuted(false)
                    if (s.state.value != VoiceState.Listening) s.startListening()
                }
                VoiceState.Idle, VoiceState.Error -> {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        s.setContinuousMode(true)
                        s.setMuted(false)
                        s.startListening()
                    } else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
                else -> s.setMuted(true)
            }
        }

        interrupt.setOnClickListener {
            val s = voiceSession ?: return@setOnClickListener
            val threadId = voiceThreadId
            if (threadId != null && app.generation.activeThreadId.value == threadId) app.generation.stop()
            if (s.state.value == VoiceState.Processing || s.state.value == VoiceState.Speaking) {
                s.interrupt()
            } else {
                toast("There is no response to interrupt right now.")
            }
        }

        view.findViewById<View>(R.id.voice_end).setOnClickListener {
            val threadId = voiceThreadId
            if (threadId != null && app.generation.activeThreadId.value == threadId) app.generation.stop()
            voiceSession?.setContinuousMode(false)
            voiceSession?.pause()
            voiceThreadId = null
            voiceTranscript.value = ""
            goBack()
        }

        if (session != null) {
            observeScreen {
                // Orb follows the one existing voice pipeline: same state flow, same RMS level flow.
                launch { session.state.collect { orbView.setState(OrbState.from(it)) } }
                launch { session.inputLevel.collect { orbView.setAmplitude(it) } }
                val base = combine(session.state, app.settings.preferences) { state, prefs -> state to (prefs ?: AppPreferences()) }
                val withDetail = base.combine(session.detail) { pair, detail -> Triple(pair.first, pair.second, detail) }
                withDetail.combine(voiceTranscript) { statePrefsDetail, transcript ->
                    VoiceDisplay(statePrefsDetail.first, statePrefsDetail.second, statePrefsDetail.third, transcript)
                }.collect { display ->
                    val state = display.state
                    val prefs = display.preferences
                    val model = app.models.find(prefs.modelId)
                    view.findViewById<TextView>(R.id.voice_model).text = "${model?.name ?: "Choose model"} · ${model?.provider?.displayName ?: "No provider"}"
                    stateText.text = when (state) {
                        VoiceState.Idle -> "Ready when you are"
                        VoiceState.Listening -> "Listening…"
                        VoiceState.Processing -> "Thinking…"
                        VoiceState.Speaking -> "AIVA is speaking…"
                        VoiceState.Muted -> "Microphone muted"
                        VoiceState.Error -> "Voice needs attention"
                    }
                    hintText.text = display.detail.ifBlank {
                        when (state) {
                            VoiceState.Idle -> "Start once, then speak naturally"
                            VoiceState.Listening -> "Speak naturally — no need to tap again"
                            VoiceState.Processing -> "AIVA is preparing your reply"
                            VoiceState.Speaking -> "You can interrupt at any time"
                            VoiceState.Muted -> "Unmute to continue the conversation"
                            VoiceState.Error -> "Check permission or connection, then retry"
                        }
                    }
                    transcriptView.text = when {
                        state == VoiceState.Listening && display.detail.isNotBlank() -> "You: ${display.detail}"
                        display.transcript.isNotBlank() -> display.transcript
                        else -> "Your words and AIVA’s replies will appear here."
                    }
                    micLabel.text = when (state) {
                        VoiceState.Idle -> "Start"
                        VoiceState.Error -> "Retry"
                        VoiceState.Muted -> "Resume"
                        VoiceState.Listening, VoiceState.Processing, VoiceState.Speaking -> "Active"
                    }
                    mic.isEnabled = state in setOf(VoiceState.Idle, VoiceState.Error, VoiceState.Muted)
                    mic.alpha = if (mic.isEnabled) 1f else 0.72f
                    muteLabel.text = if (state == VoiceState.Muted) "Unmute" else "Mute"
                    mute.text = if (state == VoiceState.Muted) "♫" else "♩"
                    interruptLabel.text = if (state == VoiceState.Speaking) "Interrupt" else "Stop reply"
                    interrupt.isEnabled = state == VoiceState.Processing || state == VoiceState.Speaking
                    interrupt.alpha = if (interrupt.isEnabled) 1f else 0.55f
                    orb.alpha = when (state) {
                        VoiceState.Muted, VoiceState.Error -> 0.62f
                        VoiceState.Listening -> 1f
                        VoiceState.Processing -> 0.88f
                        else -> 1f
                    }
                    val targetScale = if (state == VoiceState.Listening || state == VoiceState.Speaking) 1.035f else 1f
                    orb.animate().cancel()
                    orb.animate().scaleX(targetScale).scaleY(targetScale).setDuration(280L).start()
                }
            }
        }
    }

    private data class VoiceDisplay(
        val state: VoiceState,
        val preferences: AppPreferences,
        val detail: String,
        val transcript: String,
    )

    /** Sends recognized speech through the exact same provider, history, and error pipeline as chat. */
    private fun handleFinalVoiceSpeech(text: String) {
        // SpeechRecognizer callbacks can arrive just as the user navigates away; do not
        // accidentally start a background voice turn after leaving the voice screen.
        if (currentPage != Page.VOICE || isFinishing || isDestroyed) return
        val normalized = text.trim()
        if (normalized.isEmpty()) return
        val session = voiceSession ?: return
        voiceTranscript.value = "You: $normalized\nAIVA: Thinking…"
        session.markProcessing()
        val threadId = voiceThreadId?.takeIf { app.chats.thread(it) != null }
            ?: app.chats.createThread().also { voiceThreadId = it }
        app.generation.send(threadId, normalized, emptyList()) { message ->
            lifecycleScope.launch(Dispatchers.Main.immediate) {
                if (isFinishing || isDestroyed || voiceThreadId != threadId) return@launch
                val reply = message
                if (reply == null) {
                    session.fail("AIVA couldn’t finish this voice request.")
                    voiceTranscript.value = "You: $normalized\nAIVA couldn’t finish this request."
                    return@launch
                }
                voiceTranscript.value = "You: $normalized\nAIVA: ${reply.text}"
                if (reply.delivery == Delivery.Complete && reply.text.isNotBlank() && currentPage == Page.VOICE) {
                    val prefs = app.settings.preferences.value ?: AppPreferences()
                    session.speak(reply.text, prefs.voice, SpeechPace.fromLabel(prefs.speed).rate)
                } else if (reply.delivery == Delivery.Stopped) {
                    if (session.state.value == VoiceState.Processing || session.state.value == VoiceState.Speaking) {
                        session.interrupt()
                    }
                } else if (reply.delivery != Delivery.Complete) {
                    session.fail(reply.text.ifBlank { "The voice request didn’t complete. Try again." })
                }
            }
        }
    }

    private fun setupSettings(view: View) {
        settingsReady = false
        val theme = view.findViewById<Spinner>(R.id.settings_theme)
        val voice = view.findViewById<Spinner>(R.id.settings_voice)
        val speed = view.findViewById<Spinner>(R.id.settings_speed)
        val autoTitle = view.findViewById<android.widget.Switch>(R.id.settings_auto_title)
        val enterSend = view.findViewById<android.widget.Switch>(R.id.settings_enter_send)
        val saveHistory = view.findViewById<android.widget.Switch>(R.id.settings_save_history)
        var voices = voiceSession?.voices?.value?.ifEmpty { listOf(DeviceVoiceSession.DEVICE_DEFAULT) }
            ?: listOf(DeviceVoiceSession.DEVICE_DEFAULT)
        val speeds = listOf("Slow", "Normal", "Fast")
        val availableVoices = voiceSession?.voices ?: MutableStateFlow(listOf(DeviceVoiceSession.DEVICE_DEFAULT))
        setupSpinner(theme, ThemeMode.entries.map { it.name })
        setupSpinner(voice, voices)
        setupSpinner(speed, speeds)
        val updateTheme: (ThemeMode) -> Unit = { selected ->
            lifecycleScope.launch {
                app.settings.update { it.copy(theme = selected) }
                getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().putString(KEY_THEME, selected.name).apply()
                AppCompatDelegate.setDefaultNightMode(themeModeToDelegate(selected))
            }
        }
        theme.onItemSelectedListener = spinnerListener { index ->
            val selected = ThemeMode.entries.getOrNull(index)
            if (settingsReady && !settingsBinding && selected != app.settings.preferences.value?.theme) selected?.let(updateTheme)
        }
        voice.onItemSelectedListener = spinnerListener { index ->
            val selected = voices.getOrNull(index)
            if (settingsReady && !settingsBinding && selected != app.settings.preferences.value?.voice) {
                selected?.let { value -> updatePreferences { it.copy(voice = value) } }
            }
        }
        speed.onItemSelectedListener = spinnerListener { index ->
            val selected = speeds.getOrNull(index)
            if (settingsReady && !settingsBinding && selected != app.settings.preferences.value?.speed) selected?.let { value -> updatePreferences { it.copy(speed = value) } }
        }
        autoTitle.setOnCheckedChangeListener { _, checked -> if (settingsReady && !settingsBinding) updatePreferences { it.copy(autoTitle = checked) } }
        enterSend.setOnCheckedChangeListener { _, checked -> if (settingsReady && !settingsBinding) updatePreferences { it.copy(enterToSend = checked) } }
        saveHistory.setOnCheckedChangeListener { _, checked ->
            if (settingsReady && !settingsBinding) {
                updatePreferences { it.copy(saveHistory = checked) }
                app.chats.onSaveHistoryChanged(checked)
                toast(if (checked) "History will be saved" else "Saved history removed from this device")
            }
        }
        view.findViewById<View>(R.id.settings_change_model).setOnClickListener { navigate(Page.MODELS) }
        view.findViewById<View>(R.id.settings_clear_history).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Clear all conversations?")
                .setMessage("Every conversation on this device will be deleted. This can’t be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear all") { _, _ -> app.chats.clearAll(); toast("All conversations cleared") }
                .show()
        }
        bindProviderKeyControls(view, ProviderId.OpenRouter, "openrouter", R.id.openrouter_key)
        bindProviderKeyControls(view, ProviderId.Gemini, "gemini", R.id.gemini_key)
        configureHuggingFaceHold(view)
        observeScreen {
            val settingsState = combine(app.settings.preferences, app.keys.configured) { prefs, configured ->
                (prefs ?: AppPreferences()) to configured
            }
            settingsState.combine(availableVoices) { state, discoveredVoices ->
                Triple(state.first, state.second, discoveredVoices.ifEmpty { listOf(DeviceVoiceSession.DEVICE_DEFAULT) })
            }.collect { (prefs, configured, discoveredVoices) ->
                settingsReady = false
                settingsBinding = true
                if (voices != discoveredVoices) {
                    voices = discoveredVoices
                    setupSpinner(voice, voices)
                }
                setSpinner(theme, ThemeMode.entries.indexOf(prefs.theme))
                val voiceIndex = if (prefs.voice.isBlank()) {
                    voices.indexOf(DeviceVoiceSession.DEVICE_DEFAULT)
                } else {
                    voices.indexOf(prefs.voice)
                }
                setSpinner(voice, voiceIndex)
                setSpinner(speed, speeds.indexOf(prefs.speed))
                autoTitle.isChecked = prefs.autoTitle
                enterSend.isChecked = prefs.enterToSend
                saveHistory.isChecked = prefs.saveHistory
                val model = app.models.find(prefs.modelId)
                view.findViewById<TextView>(R.id.settings_model_name).text = model?.name ?: "No model"
                view.findViewById<TextView>(R.id.settings_model_provider).text = model?.provider?.displayName ?: "Unknown"
                updateProviderStatus(view, "openrouter", "OpenRouter", ProviderId.OpenRouter in configured)
                updateProviderStatus(view, "gemini", "Google AI Studio", ProviderId.Gemini in configured)
                view.findViewById<TextView>(R.id.huggingface_status).text = "Hugging Face · Temporarily on hold"
                settingsBinding = false
                settingsReady = true
            }
        }
    }

    private fun bindProviderKeyControls(root: View, provider: ProviderId, prefix: String, editId: Int) {
        val field = root.findViewById<EditText>(editId)
        val toggle = root.findViewById<TextView>(resources.getIdentifier("${prefix}_toggle", "id", packageName))
        var showingSecret = false
        fun setSecretVisibility(visible: Boolean) {
            showingSecret = visible
            field.inputType = InputType.TYPE_CLASS_TEXT or (
                if (visible) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
            field.setSelection(field.text.length)
            toggle.text = if (visible) "Hide" else "Show"
        }
        toggle.setOnClickListener { setSecretVisibility(!showingSecret) }
        root.findViewById<View>(resources.getIdentifier("${prefix}_save", "id", packageName)).setOnClickListener {
            val secret = field.text.toString().trim()
            val validationError = keyFormatError(secret)
            if (validationError != null) {
                toast(validationError)
            } else {
                runCatching { app.keys.set(provider, secret) }
                    .onSuccess {
                        field.text.clear()
                        setSecretVisibility(false)
                        toast("${provider.displayName} key saved securely")
                    }
                    .onFailure { toast("Could not save key: ${it.message ?: "storage error"}") }
            }
        }
        root.findViewById<View>(resources.getIdentifier("${prefix}_remove", "id", packageName)).setOnClickListener {
            app.keys.remove(provider)
            field.text.clear()
            setSecretVisibility(false)
            toast("${provider.displayName} key removed")
        }
        val test = root.findViewById<View>(resources.getIdentifier("${prefix}_test", "id", packageName))
        test.setOnClickListener {
            val secret = field.text.toString().trim().ifBlank { app.keys.get(provider).orEmpty() }
            if (secret.isBlank()) {
                toast("Add or save the ${provider.displayName} API key first.")
                return@setOnClickListener
            }
            test.isEnabled = false
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        when (provider) {
                            ProviderId.OpenRouter -> ProviderProbe.openRouter(secret)
                            ProviderId.Gemini -> ProviderProbe.gemini(secret)
                            ProviderId.HuggingFace -> error("Hugging Face is temporarily on hold.")
                        }
                    }
                }
                test.isEnabled = true
                result.onSuccess { toast(it) }
                    .onFailure { toast("API test failed: ${it.message ?: "Check the key and connection."}") }
            }
        }
    }

    private fun configureHuggingFaceHold(root: View) {
        root.findViewById<TextView>(R.id.huggingface_status).text = "Hugging Face · Temporarily on hold"
        root.findViewById<EditText>(R.id.huggingface_key).apply {
            isEnabled = false
            hint = "Hugging Face integration is paused"
        }
        listOf(R.id.huggingface_toggle, R.id.huggingface_save, R.id.huggingface_test).forEach { id ->
            root.findViewById<View>(id).apply { isEnabled = false; alpha = 0.45f }
        }
        // Keep Remove enabled so a previously stored token can still be deleted from this phone.
        root.findViewById<View>(R.id.huggingface_remove).setOnClickListener {
            app.keys.remove(ProviderId.HuggingFace)
            toast("Saved Hugging Face key removed. Provider remains on hold.")
        }
    }

    private fun updateProviderStatus(root: View, prefix: String, label: String, configured: Boolean) {
        root.findViewById<TextView>(resources.getIdentifier("${prefix}_status", "id", packageName)).text =
            "$label · ${if (configured) "Key saved" else "Not set up"}"
    }

    private fun renderThreadRows(target: LinearLayout, threads: List<ChatThread>) {
        target.removeAllViews()
        if (threads.isEmpty()) {
            target.addView(labelView("No conversations match this search.", true))
            return
        }
        appendThreadRows(target, threads)
    }

    private fun appendThreadRows(target: LinearLayout, threads: List<ChatThread>) {
        threads.forEach { thread ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_thread, target, false)
            row.findViewById<TextView>(R.id.thread_title).text = thread.title
            row.findViewById<TextView>(R.id.thread_preview).text = thread.preview
            row.findViewById<View>(R.id.thread_open).setOnClickListener { navigate(Page.CHAT, thread.id) }
            row.findViewById<View>(R.id.thread_title).setOnClickListener { navigate(Page.CHAT, thread.id) }
            row.findViewById<View>(R.id.thread_preview).setOnClickListener { navigate(Page.CHAT, thread.id) }
            row.findViewById<View>(R.id.thread_rename).setOnClickListener { showRenameDialog(thread) }
            row.findViewById<View>(R.id.thread_delete).setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Delete conversation?")
                    .setMessage("“${thread.title}” will be removed from this device.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ -> app.chats.delete(thread.id); toast("Conversation deleted") }
                    .show()
            }
            target.addView(row)
        }
    }

    private fun showRenameDialog(thread: ChatThread) {
        val input = EditText(this).apply {
            setText(thread.title)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Rename conversation")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ -> app.chats.rename(thread.id, input.text.toString()) }
            .show()
    }

    private fun observeScreen(block: suspend CoroutineScope.() -> Unit) {
        screenJob?.cancel()
        screenJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { block(this) }
        }
    }

    private fun updatePreferences(transform: (AppPreferences) -> AppPreferences) {
        lifecycleScope.launch {
            runCatching { app.settings.update(transform) }
                .onFailure { toast("Could not update setting: ${it.message ?: "unknown error"}") }
        }
    }

    private fun setupSpinner(spinner: Spinner, values: List<String>) {
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, values).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun setSpinner(spinner: Spinner, index: Int) {
        if (index >= 0 && spinner.selectedItemPosition != index) spinner.setSelection(index, false)
    }

    private fun spinnerListener(onSelected: (Int) -> Unit) = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, item: View?, position: Int, id: Long) = onSelected(position)
    }

    private fun simpleTextWatcher(onChanged: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = onChanged(s?.toString().orEmpty())
        override fun afterTextChanged(s: Editable?) = Unit
    }

    private fun matchesThread(thread: ChatThread, query: String): Boolean = query.isBlank() ||
        thread.title.contains(query, true) || thread.preview.contains(query, true)

    private fun sectionHeading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(color(R.color.aiva_muted))
        setPadding(dp(4), dp(12), dp(4), dp(8))
    }

    private fun labelView(text: String, muted: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(color(if (muted) R.color.aiva_muted else R.color.aiva_text))
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(12), dp(8), dp(12))
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun refreshAttachmentUi(target: AttachmentTarget) {
        val count = if (target == AttachmentTarget.HOME) homeAttachments.size else chatAttachments.size
        val countView = findViewById<TextView>(if (target == AttachmentTarget.HOME) R.id.home_attachment_count else R.id.chat_attachment_count)
        if (countView != null) {
            countView.text = attachmentLabel(count)
            countView.visibility = if (count > 0) View.VISIBLE else View.GONE
        }
    }

    private fun imageThumb(bitmap: Bitmap, isGenerated: Boolean): View {
        val imageView = ImageView(this).apply {
            setImageBitmap(bitmap)
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply {
                marginEnd = dp(8)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ContextCompat.getDrawable(this@MainActivity, if (isGenerated) R.drawable.bg_message_user else R.drawable.bg_card)
            clipToOutline = true
        }
        return imageView
    }

    private fun shareImage(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.files", file)
        val mime = imageMimeType(file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(contentResolver, "AIVA image", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share image"))
    }

    private fun saveGeneratedImage(relativePath: String) {
        val file = app.images.file(relativePath)
        if (file == null) {
            toast("This generated image is no longer available.")
            return
        }
        val mime = imageMimeType(file)
        pendingSaveImagePath = relativePath
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, file.name)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(mime))
        }
        runCatching { saveImageLauncher.launch(intent) }
            .onFailure {
                pendingSaveImagePath = null
                toast("Couldn’t open the save dialog.")
            }
    }

    private fun imageMimeType(file: File): String = when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/png"
    }

    private fun attachmentLabel(count: Int): String = if (count == 0) "" else "$count image${if (count == 1) "" else "s"} attached"
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private inline fun <reified T : View> View.findViewByIdOrNull(id: Int): T? = runCatching { findViewById<T>(id) }.getOrNull()

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PAGE, currentPage.name)
        outState.putString(STATE_THREAD_ID, currentThreadId)
        val pages = ArrayList<String>()
        val threads = ArrayList<String>()
        for (dest in backStack) {
            pages.add(dest.page.name)
            threads.add(dest.threadId.orEmpty())
        }
        outState.putStringArrayList(STATE_BACK_STACK_PAGES, pages)
        outState.putStringArrayList(STATE_BACK_STACK_THREADS, threads)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        orbController?.resume()
    }

    override fun onStop() {
        // Stop orb rendering while the app is not visible.
        orbController?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        screenJob?.cancel()
        orbController?.release()
        orbController = null
        voiceSession?.shutdown()
        voiceSession = null
        super.onDestroy()
    }

    private fun themeModeToDelegate(mode: ThemeMode): Int = when (mode) {
        ThemeMode.Light -> AppCompatDelegate.MODE_NIGHT_NO
        ThemeMode.Dark -> AppCompatDelegate.MODE_NIGHT_YES
        ThemeMode.System -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    private companion object {
        const val UI_PREFS = "aiva_ui_preferences"
        const val KEY_THEME = "theme_mode"
        const val MAX_ATTACHMENTS = 4
        const val STATE_PAGE = "aiva_state_page"
        const val STATE_THREAD_ID = "aiva_state_thread_id"
        const val STATE_BACK_STACK_PAGES = "aiva_state_back_stack_pages"
        const val STATE_BACK_STACK_THREADS = "aiva_state_back_stack_threads"
    }
}
