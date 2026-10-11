package com.hakkiiler.voiceassistant.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hakkiiler.voiceassistant.di.AppContainer
import com.hakkiiler.voiceassistant.domain.model.AppInfo
import com.hakkiiler.voiceassistant.domain.model.CustomCommand
import com.hakkiiler.voiceassistant.domain.model.Event
import com.hakkiiler.voiceassistant.domain.model.Language
import com.hakkiiler.voiceassistant.domain.model.LanguageMode
import com.hakkiiler.voiceassistant.domain.model.VoiceGender
import com.hakkiiler.voiceassistant.overlay.OverlayService
import com.hakkiiler.voiceassistant.service.AssistantService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class AssistantViewModel(app: Application) : AndroidViewModel(app) {
    private val c = AppContainer.from(app)

    val state = c.state
    val settings = c.settings.settings
    val chat = c.chat.items
    val events = c.events.items
    val commands = c.commands.items

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps

    fun modelAvailable(lang: Language) = lang in c.modelDownloads.installed.value
    val modelsBundled = c.models.bundled
    val installedModels = c.modelDownloads.installed
    val downloadState = c.modelDownloads.state
    fun downloadModels(langs: List<Language>) = c.modelDownloads.download(langs)
    fun cancelDownload() = c.modelDownloads.cancel()
    fun resetDownloadError() = c.modelDownloads.clearError()

    /** Первый запуск lite: пользователь выбрал язык(и) — сохраняем режим и качаем нужные модели. */
    fun setupLanguage(mode: LanguageMode) {
        viewModelScope.launch { c.settings.setLanguageMode(mode) }
        c.modelDownloads.download(c.modelDownloads.required(mode))
    }
    fun ttsHas(lang: Language) = lang in c.voice.availableLanguages
    fun canScheduleExact() = c.reminders.canScheduleExact()

    // ---- ассистент ----
    fun listenNow() = AssistantService.start(getApplication(), listenNow = true)

    fun setBackgroundListening(enabled: Boolean) = viewModelScope.launch {
        c.settings.setBackgroundListening(enabled)
        if (enabled) AssistantService.start(getApplication(), listenNow = false) else AssistantService.stop(getApplication())
    }

    /** Ввод текстом — удобно без микрофона; язык по алфавиту. */
    fun sendText(text: String) {
        if (text.isBlank()) return
        val lang = if (text.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' }) Language.RU else Language.EN
        viewModelScope.launch {
            val reply = c.process(text, lang)
            c.voice.gender = settings.value.voiceGender
            c.voice.speak(reply.text, reply.language)
        }
    }

    fun clearChat() = viewModelScope.launch { c.chat.clear() }

    // ---- события ----
    fun saveEvent(title: String, timestamp: Long, reminderMinutes: Int, id: String? = null) = viewModelScope.launch {
        val e = Event(id ?: UUID.randomUUID().toString(), title.ifBlank { "Событие" }, timestamp, reminderMinutes)
        c.events.upsert(e)
        c.reminders.schedule(e)
    }

    fun deleteEvent(e: Event) = viewModelScope.launch {
        c.reminders.cancel(e)
        c.events.remove(e.id)
    }

    // ---- команды ----
    fun loadApps() = viewModelScope.launch {
        _apps.value = withContext(Dispatchers.IO) { c.system.launchableApps() }
    }

    fun saveCommand(cmd: CustomCommand) = viewModelScope.launch { c.commands.upsert(cmd) }
    fun newCommand(trigger: String, app: AppInfo) = saveCommand(
        CustomCommand(UUID.randomUUID().toString(), trigger.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            app.packageName, app.label),
    )
    fun deleteCommand(cmd: CustomCommand) = viewModelScope.launch { c.commands.remove(cmd.id) }

    // ---- рисование ----
    val draw = c.draw

    // ---- настройки ----
    fun setLanguageMode(m: LanguageMode) = viewModelScope.launch { c.settings.setLanguageMode(m) }
    fun setVoiceGender(g: VoiceGender) = viewModelScope.launch { c.settings.setVoiceGender(g) }
    fun addWakeWord(w: String) = viewModelScope.launch { c.settings.addWakeWord(w) }
    fun removeWakeWord(w: String) = viewModelScope.launch { c.settings.removeWakeWord(w) }

    fun setOverlay(enabled: Boolean) = viewModelScope.launch {
        c.settings.setOverlayEnabled(enabled)
        if (enabled) OverlayService.start(getApplication()) else OverlayService.stop(getApplication())
    }
}
