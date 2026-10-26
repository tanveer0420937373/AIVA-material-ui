package com.tanveer.aiva.data.local

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tanveer.aiva.domain.models.AppPreferences
import com.tanveer.aiva.domain.models.SpeechPace
import com.tanveer.aiva.domain.models.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "aiva_preferences")

class PreferencesStore(private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("theme")
        val model = stringPreferencesKey("model")
        val voice = stringPreferencesKey("voice")
        val speed = stringPreferencesKey("speed")
        val autoTitle = booleanPreferencesKey("auto_title")
        val saveHistory = booleanPreferencesKey("save_history")
        val enterToSend = booleanPreferencesKey("enter_to_send")
    }

    val preferences: Flow<AppPreferences> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it.toAppPreferences() }

    suspend fun update(transform: (AppPreferences) -> AppPreferences) {
        context.dataStore.edit { stored ->
            val next = transform(stored.toAppPreferences())
            stored[Keys.theme] = next.theme.name
            stored[Keys.model] = ModelCatalog.migrateId(next.modelId)
            stored[Keys.voice] = next.voice
            stored[Keys.speed] = SpeechPace.fromLabel(next.speed).label
            stored[Keys.autoTitle] = next.autoTitle
            stored[Keys.saveHistory] = next.saveHistory
            stored[Keys.enterToSend] = next.enterToSend
        }
    }

    private fun Preferences.toAppPreferences(): AppPreferences {
        val defaults = AppPreferences()
        val storedVoice = this[Keys.voice]
        return AppPreferences(
            theme = this[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: defaults.theme,
            modelId = ModelCatalog.migrateId(this[Keys.model]),
            voice = if (storedVoice == null || storedVoice == "Serena" || storedVoice == "Atlas" || storedVoice == "Nova") "" else storedVoice,
            speed = SpeechPace.fromLabel(this[Keys.speed] ?: defaults.speed).label,
            autoTitle = this[Keys.autoTitle] ?: defaults.autoTitle,
            saveHistory = this[Keys.saveHistory] ?: defaults.saveHistory,
            enterToSend = this[Keys.enterToSend] ?: defaults.enterToSend,
        )
    }
}
