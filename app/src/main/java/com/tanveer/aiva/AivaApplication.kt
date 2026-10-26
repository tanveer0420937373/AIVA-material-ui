package com.tanveer.aiva

import android.app.Application
import android.content.Context
import com.tanveer.aiva.core.security.SecureKeyStore
import com.tanveer.aiva.data.local.ImageStore
import com.tanveer.aiva.data.local.PreferencesStore
import com.tanveer.aiva.data.local.ThreadStore
import com.tanveer.aiva.data.repositories.AiRepository
import com.tanveer.aiva.data.repositories.ChatRepository
import com.tanveer.aiva.data.repositories.ModelRepository
import com.tanveer.aiva.data.repositories.SettingsRepository
import com.tanveer.aiva.ui.chat.GenerationController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency container — small enough that a DI framework would add more weight than value. */
class AppContainer(context: Context) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val images = ImageStore(context.filesDir)
    val keys = SecureKeyStore(context).also { runCatching { it.reconcile() } }
    val settings = SettingsRepository(PreferencesStore(context), appScope)
    val chats = ChatRepository(ThreadStore(context), settings, images, appScope)
    val models = ModelRepository(context, keys)
    val ai = AiRepository(keys, images, models)
    val generation = GenerationController(chats, ai, models, settings, appScope)
}

class AivaApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
