package com.hakkiiler.voiceassistant.data.stt

import com.hakkiiler.voiceassistant.domain.model.Language
import com.hakkiiler.voiceassistant.domain.model.LanguageMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class DownloadState(
    val running: Boolean = false,
    val current: Language? = null,
    /** 0..1 по всем выбранным моделям. */
    val progress: Float = 0f,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
)

/** Загрузка моделей для lite-сборки; переживает поворот экрана (живёт в AppContainer). */
class ModelDownloadController(private val manager: VoskModelManager, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(DownloadState())
    val state: StateFlow<DownloadState> = _state

    private val _installed = MutableStateFlow(installedNow())
    /** Какие модели уже есть на телефоне (или в APK). */
    val installed: StateFlow<Set<Language>> = _installed

    private var job: Job? = null

    private fun installedNow() = Language.entries.filter { manager.isAvailable(it) }.toSet()

    fun required(mode: LanguageMode): List<Language> = mode.forcedLanguage()?.let { listOf(it) } ?: Language.entries

    fun missing(mode: LanguageMode) = required(mode).filterNot { it in _installed.value }

    fun download(langs: List<Language>) {
        if (job?.isActive == true) return
        val todo = langs.filterNot { manager.isAvailable(it) }
        if (todo.isEmpty()) { _installed.value = installedNow(); return }
        val total = todo.sumOf { ModelSource.approxBytes(it) }
        job = scope.launch {
            var before = 0L
            _state.value = DownloadState(running = true, totalBytes = total)
            try {
                for (lang in todo) {
                    _state.value = _state.value.copy(current = lang)
                    manager.downloader.download(lang) { done, _ ->
                        val all = before + done
                        _state.value = _state.value.copy(
                            downloadedBytes = all,
                            progress = (all.toFloat() / total).coerceIn(0f, 1f),
                        )
                    }
                    before += ModelSource.approxBytes(lang)
                    _installed.value = installedNow()
                }
                _state.value = DownloadState(progress = 1f, downloadedBytes = total, totalBytes = total)
            } catch (e: CancellationException) {
                _state.value = DownloadState()
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(running = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancel() { job?.cancel() }

    fun clearError() { if (!_state.value.running) _state.value = DownloadState() }
}
