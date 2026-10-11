package com.hakkiiler.voiceassistant.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.hakkiiler.voiceassistant.domain.model.LanguageMode
import com.hakkiiler.voiceassistant.domain.model.VoiceGender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

data class AppSettings(
    val languageMode: LanguageMode = LanguageMode.AUTO,
    val wakeWords: List<String> = DEFAULT_WAKE_WORDS,
    val voiceGender: VoiceGender = VoiceGender.FEMALE,
    /** Слушать активатор в фоне (foreground-сервис с микрофоном). */
    val backgroundListening: Boolean = false,
    val overlayEnabled: Boolean = false,
) {
    companion object {
        val DEFAULT_WAKE_WORDS = listOf("ассистент", "assistant", "помощник")
    }
}

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private val modeKey = stringPreferencesKey("language_mode")
    private val wakeKey = stringPreferencesKey("wake_words")
    private val genderKey = stringPreferencesKey("voice_gender")
    private val bgKey = booleanPreferencesKey("background_listening")
    private val overlayKey = booleanPreferencesKey("overlay_enabled")
    private val listSer = ListSerializer(String.serializer())

    val settings: StateFlow<AppSettings> = context.appDataStore.data.map { p ->
        AppSettings(
            languageMode = p[modeKey]?.let { runCatching { LanguageMode.valueOf(it) }.getOrNull() } ?: LanguageMode.AUTO,
            wakeWords = p[wakeKey]?.let { runCatching { AppJson.decodeFromString(listSer, it) }.getOrNull() }
                ?.takeIf { it.isNotEmpty() } ?: AppSettings.DEFAULT_WAKE_WORDS,
            voiceGender = p[genderKey]?.let { runCatching { VoiceGender.valueOf(it) }.getOrNull() } ?: VoiceGender.FEMALE,
            backgroundListening = p[bgKey] ?: false,
            overlayEnabled = p[overlayKey] ?: false,
        )
    }.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun setLanguageMode(mode: LanguageMode) = context.appDataStore.edit { it[modeKey] = mode.name }
    suspend fun setVoiceGender(g: VoiceGender) = context.appDataStore.edit { it[genderKey] = g.name }
    suspend fun setBackgroundListening(v: Boolean) = context.appDataStore.edit { it[bgKey] = v }
    suspend fun setOverlayEnabled(v: Boolean) = context.appDataStore.edit { it[overlayKey] = v }

    suspend fun addWakeWord(word: String) {
        val w = word.trim().lowercase()
        if (w.isEmpty()) return
        val list = settings.value.wakeWords
        if (w !in list) saveWakeWords(list + w)
    }

    suspend fun removeWakeWord(word: String) {
        val list = settings.value.wakeWords
        if (list.size > 1) saveWakeWords(list - word)
    }

    private suspend fun saveWakeWords(list: List<String>) =
        context.appDataStore.edit { it[wakeKey] = AppJson.encodeToString(listSer, list) }
}
