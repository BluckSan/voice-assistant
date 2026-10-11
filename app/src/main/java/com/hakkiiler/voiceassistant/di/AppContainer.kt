package com.hakkiiler.voiceassistant.di

import android.content.Context
import android.content.Intent
import com.hakkiiler.voiceassistant.MainActivity
import com.hakkiiler.voiceassistant.VoiceAssistantApp
import com.hakkiiler.voiceassistant.data.repository.ChatRepository
import com.hakkiiler.voiceassistant.data.repository.CommandRepository
import com.hakkiiler.voiceassistant.data.repository.EventRepository
import com.hakkiiler.voiceassistant.data.repository.SettingsRepository
import com.hakkiiler.voiceassistant.data.image.DrawController
import com.hakkiiler.voiceassistant.data.stt.ModelDownloadController
import com.hakkiiler.voiceassistant.data.stt.VoskModelManager
import com.hakkiiler.voiceassistant.data.system.ReminderManager
import com.hakkiiler.voiceassistant.data.system.SystemControlManager
import com.hakkiiler.voiceassistant.data.tts.AssistantVoice
import com.hakkiiler.voiceassistant.domain.lang.LanguageSelector
import com.hakkiiler.voiceassistant.domain.model.AssistantState
import com.hakkiiler.voiceassistant.domain.model.ChatMessage
import com.hakkiiler.voiceassistant.domain.model.Language
import com.hakkiiler.voiceassistant.domain.usecases.DrawLauncher
import com.hakkiiler.voiceassistant.domain.usecases.ProcessVoiceCommandUseCase
import com.hakkiiler.voiceassistant.domain.usecases.Reply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Ручной DI: один экземпляр всех зависимостей на приложение. */
class AppContainer(context: Context) {
    private val app = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settings = SettingsRepository(app, appScope)
    val events = EventRepository(app, appScope)
    val commands = CommandRepository(app, appScope)
    val chat = ChatRepository(app, appScope)
    val system = SystemControlManager(app)
    val reminders = ReminderManager(app)
    val models = VoskModelManager(app)
    val modelDownloads = ModelDownloadController(models, appScope)
    val languageSelector by lazy { LanguageSelector() }
    val voice by lazy { AssistantVoice(app) }
    /** Рисование: модель SD-Turbo, генерация, загрузка (data/image). */
    val draw = DrawController(app, appScope)

    /** Состояние ассистента, общее для сервиса, оверлея и UI. */
    val state = MutableStateFlow(AssistantState())

    private val useCase = ProcessVoiceCommandUseCase(system, events, commands, reminders, drawLauncher = DrawLauncherImpl())

    /** Голосовое «нарисуй …»: запускаем генерацию и выводим приложение на экран рисования. */
    private inner class DrawLauncherImpl : DrawLauncher {
        override fun draw(prompt: String?): DrawLauncher.Result {
            val result = this@AppContainer.draw.draw(prompt)
            if (result != DrawLauncher.Result.UNSUPPORTED && result != DrawLauncher.Result.BUSY) {
                // из фонового сервиса Android может не дать открыть окно — тогда вкладка откроется при следующем входе
                runCatching {
                    app.startActivity(
                        Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                }
            }
            return result
        }
    }
    private val mutex = Mutex()

    val inDialog get() = useCase.inDialog

    /** Выполняет фразу (голосом или текстом), пишет обе реплики в историю чата. */
    suspend fun process(text: String, lang: Language): Reply = mutex.withLock {
        chat.add(ChatMessage(UUID.randomUUID().toString(), text, fromUser = true, language = lang, timestamp = System.currentTimeMillis()))
        val reply = runCatching { useCase.execute(text, lang) }.getOrElse { e ->
            Reply(if (lang == Language.RU) "Ошибка: ${e.message}" else "Error: ${e.message}", lang)
        }
        chat.add(ChatMessage(UUID.randomUUID().toString(), reply.text, fromUser = false, language = reply.language, timestamp = System.currentTimeMillis()))
        reply
    }

    companion object {
        fun from(context: Context): AppContainer = (context.applicationContext as VoiceAssistantApp).container
    }
}
