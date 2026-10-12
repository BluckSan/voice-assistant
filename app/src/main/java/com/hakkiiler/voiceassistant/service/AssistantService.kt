package com.hakkiiler.voiceassistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.hakkiiler.voiceassistant.MainActivity
import com.hakkiiler.voiceassistant.R
import com.hakkiiler.voiceassistant.data.stt.BilingualSpeechEngine
import com.hakkiiler.voiceassistant.di.AppContainer
import com.hakkiiler.voiceassistant.domain.lang.Hypothesis
import com.hakkiiler.voiceassistant.domain.lang.WakeWordDetector
import com.hakkiiler.voiceassistant.domain.lang.detectAll
import com.hakkiiler.voiceassistant.domain.model.AssistantStatus
import com.hakkiiler.voiceassistant.domain.model.Language
import com.hakkiiler.voiceassistant.domain.model.LanguageMode
import com.hakkiiler.voiceassistant.domain.usecases.Replies
import com.hakkiiler.voiceassistant.domain.usecases.Reply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground-сервис с микрофоном: слушает активатор («ассистент» / "assistant"),
 * затем команду, выполняет её и отвечает голосом.
 *
 * Режимы: фоновое прослушивание (настройка «Слушать в фоне») или разовая сессия по кнопке —
 * после ответа разовая сессия завершается и микрофон освобождается.
 */
class AssistantService : Service(), BilingualSpeechEngine.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var c: AppContainer
    private val engine = BilingualSpeechEngine()
    private var timeoutJob: Job? = null
    private var activateWhenReady = false
    private var lastLanguage: Language? = null

    private val mode get() = c.settings.settings.value.languageMode
    private val backgroundMode get() = c.settings.settings.value.backgroundListening

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        c = AppContainer.from(this)
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(getString(R.string.status_loading)), type)
        startEngine()
        // смена режима языка → перезапуск распознавателей с нужным набором моделей
        scope.launch {
            c.settings.settings.map { it.languageMode }.distinctUntilChanged().drop(1).collect { startEngine() }
        }
        scope.launch {
            c.settings.settings.map { it.voiceGender }.distinctUntilChanged().collect { c.voice.gender = it }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_LISTEN_NOW -> {
                if (engine.isRunning && c.state.value.status != AssistantStatus.LOADING_MODELS) activate(null) else activateWhenReady = true
            }
        }
        return START_NOT_STICKY
    }

    private fun startEngine() {
        scope.launch {
            setStatus(AssistantStatus.LOADING_MODELS)
            withContext(Dispatchers.IO) { engine.stop() }
            val langs = mode.forcedLanguage()?.let { listOf(it) } ?: Language.entries
            val models = runCatching { withContext(Dispatchers.IO) { c.models.loadAll(langs) } }.getOrElse { e ->
                Log.e(TAG, "model load", e)
                c.state.value = c.state.value.copy(status = AssistantStatus.ERROR, error = e.message)
                updateNotification(e.message ?: "error")
                return@launch
            }
            engine.start(models, this@AssistantService)
            if (activateWhenReady) { activateWhenReady = false; activate(null) } else idle()
        }
    }

    // ---------- распознавание ----------

    override fun onPartial(text: String) {
        c.state.value = c.state.value.copy(partialText = text)
    }

    override fun onError(e: Throwable) {
        c.state.value = c.state.value.copy(status = AssistantStatus.ERROR, error = e.message)
        updateNotification(e.message ?: "error")
    }

    override fun onUtterance(hypotheses: List<Hypothesis>, audio: ShortArray) {
        val status = c.state.value.status
        if (status == AssistantStatus.SPEAKING || status == AssistantStatus.PROCESSING) return
        val wakeWords = c.settings.settings.value.wakeWords
        val forced = mode.forcedLanguage()
        val hyps = if (forced != null) hypotheses.filter { it.language == forced } else hypotheses

        if (status == AssistantStatus.LISTENING) {
            // уже активны: активатор в начале фразы допускается, но не обязателен
            val sel = c.languageSelector.select(hyps, mode, lastLanguage) ?: return
            val hit = hyps.firstOrNull { it.language == sel.language }?.let { WakeWordDetector.find(it, wakeWords) }
            val text = hit?.remainder ?: sel.text
            if (text.isBlank()) activate(sel.language) else handleCommand(text, sel.language, sel.score, audio)
            return
        }

        // ждём активатор
        val hits = WakeWordDetector.detectAll(hyps, wakeWords)
        if (hits.isEmpty()) {
            c.state.value = c.state.value.copy(partialText = "")
            return
        }
        val withCommand = hits.filter { it.remainder.isNotBlank() }
        if (withCommand.isNotEmpty()) {
            // «ассистент открой телеграм» — выбираем язык по остатку фразы
            val remainders = withCommand.map { h -> Hypothesis(h.hypothesis.language, h.remainder, h.hypothesis.words) }
            val sel = c.languageSelector.select(remainders, mode, lastLanguage) ?: return
            handleCommand(sel.text, sel.language, sel.score, audio)
        } else {
            val sel = c.languageSelector.select(hits.map { it.hypothesis }, mode, lastLanguage)
            activate(sel?.language ?: hits.first().hypothesis.language)
        }
    }

    /** Активатор услышан без команды — отвечаем «Слушаю» и ждём команду. */
    private fun activate(lang: Language?) {
        val l = lang ?: lastLanguage ?: (if (mode == LanguageMode.EN) Language.EN else Language.RU)
        speak(Reply(Replies(l).listening, l, keepListening = true))
    }

    private fun handleCommand(voskText: String, voskLang: Language, score: Double, audio: ShortArray? = null) {
        timeoutJob?.cancel()
        c.state.value = c.state.value.copy(
            status = AssistantStatus.PROCESSING, partialText = voskText, lastLanguage = voskLang, lastConfidence = score.toFloat(),
        )
        updateNotification(statusText(AssistantStatus.PROCESSING))
        scope.launch {
            var text = voskText
            var lang = voskLang
            // Whisper переслушивает всю фразу: понимает русские и английские слова вперемешку
            if (audio != null && c.ai.whisperReady) {
                val forced = mode.forcedLanguage()
                val w = runCatching { c.ai.transcribe(audio, forced ?: voskLang) }
                    .onFailure { Log.w(TAG, "whisper", it) }.getOrNull()
                if (w != null) {
                    val hit = WakeWordDetector.find(Hypothesis(w.first, w.second, emptyList()), c.settings.settings.value.wakeWords)
                    val cleaned = hit?.let { stripWake(w.second, it.wakeWord) } ?: w.second
                    if (cleaned.isNotBlank()) {
                        text = cleaned
                        lang = forced ?: w.first
                    }
                }
                c.state.value = c.state.value.copy(partialText = text, lastLanguage = lang)
            }
            lastLanguage = lang
            val reply = c.process(text, lang)
            speak(reply)
        }
    }

    /** «Ассистент, поставь reminder» → «поставь reminder» (с сохранением регистра и латиницы). */
    private fun stripWake(text: String, wake: String): String {
        val words = text.split(Regex("\\s+"))
        val n = wake.split(' ').size
        val idx = words.indices.firstOrNull { i ->
            i + n <= words.size && WakeWordDetector.tokenMatches(
                words.subList(i, i + n).joinToString(" ") { it.lowercase().trim(',', '.', '!', '?') }, wake.lowercase(),
            )
        } ?: return text
        val rest = (words.take(idx) + words.drop(idx + n)).joinToString(" ").trim(',', '.', ' ', '!')
        return rest
    }

    private fun speak(reply: Reply) {
        timeoutJob?.cancel()
        setStatus(AssistantStatus.SPEAKING)
        engine.muted = true
        c.voice.speak(reply.text, reply.language) {
            scope.launch {
                delay(250) // хвост эха динамика
                engine.muted = false
                when {
                    reply.shutdown -> { scope.launch { c.settings.setBackgroundListening(false) }; stopSelf() }
                    reply.keepListening || c.inDialog -> listen()
                    backgroundMode -> idle()
                    else -> stopSelf() // разовая сессия закончена
                }
            }
        }
    }

    private fun listen() {
        setStatus(AssistantStatus.LISTENING)
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(LISTEN_TIMEOUT_MS)
            if (c.state.value.status == AssistantStatus.LISTENING) {
                if (backgroundMode) idle() else stopSelf()
            }
        }
    }

    private fun idle() = setStatus(AssistantStatus.WAITING_WAKE_WORD)

    private fun setStatus(s: AssistantStatus) {
        c.state.value = c.state.value.copy(status = s, error = null, partialText = if (s == AssistantStatus.WAITING_WAKE_WORD) "" else c.state.value.partialText)
        updateNotification(statusText(s))
    }

    private fun statusText(s: AssistantStatus) = getString(
        when (s) {
            AssistantStatus.LOADING_MODELS -> R.string.status_loading
            AssistantStatus.WAITING_WAKE_WORD -> R.string.status_waiting
            AssistantStatus.LISTENING -> R.string.status_listening
            AssistantStatus.PROCESSING -> R.string.status_processing
            AssistantStatus.SPEAKING -> R.string.status_speaking
            AssistantStatus.ERROR -> R.string.status_error
            AssistantStatus.STOPPED -> R.string.status_stopped
        },
    )

    override fun onDestroy() {
        timeoutJob?.cancel()
        engine.stop()
        c.voice.stop()
        c.state.value = c.state.value.copy(status = AssistantStatus.STOPPED, partialText = "")
        scope.cancel()
        super.onDestroy()
    }

    // ---------- уведомление ----------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.channel_assistant), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AssistantService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    companion object {
        private const val TAG = "AssistantService"
        private const val CHANNEL = "assistant"
        private const val NOTIFICATION_ID = 42
        private const val LISTEN_TIMEOUT_MS = 8_000L
        const val ACTION_START = "start"
        const val ACTION_LISTEN_NOW = "listen_now"
        const val ACTION_STOP = "stop"

        /** Запуск: фоновое ожидание активатора или сразу слушать команду. */
        fun start(context: Context, listenNow: Boolean) {
            val i = Intent(context, AssistantService::class.java).setAction(if (listenNow) ACTION_LISTEN_NOW else ACTION_START)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AssistantService::class.java))
        }
    }
}
