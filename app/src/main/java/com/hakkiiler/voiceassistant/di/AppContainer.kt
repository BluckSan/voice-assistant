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
import com.hakkiiler.voiceassistant.data.ai.AiController
import com.hakkiiler.voiceassistant.data.ai.HeavyModelGuard
import com.hakkiiler.voiceassistant.domain.llm.ChatPrompt
import com.hakkiiler.voiceassistant.domain.llm.ToolCalls
import com.hakkiiler.voiceassistant.domain.model.VoiceCommand
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
    /** Рисование: Realistic Vision V6 + LCM, генерация, загрузка (data/image). */
    val draw = DrawController(app, appScope)
    /** Whisper (смешанная речь) и локальная нейросеть для разговора (data/ai). */
    val ai = AiController(app, appScope)

    init {
        HeavyModelGuard.unloadChat = { ai.unloadAll() }
        HeavyModelGuard.releaseDraw = { draw.releaseForOtherModel() }
        // русский запрос на рисование → английский промпт силами LLM (если скачана)
        draw.llmPromptWriter = { text -> if (ai.llmReady) ai.writeDrawPrompt(text) else null }
        draw.promptCancel = { ai.cancelChat() }
    }

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
        val userMsg = ChatMessage(UUID.randomUUID().toString(), text, fromUser = true, language = lang, timestamp = System.currentTimeMillis())
        chat.add(userMsg)
        val reply = runCatching { route(text, lang, userMsg) }.getOrElse { e ->
            Reply(if (lang == Language.RU) "Ошибка: ${e.message}" else "Error: ${e.message}", lang)
        }
        chat.add(ChatMessage(UUID.randomUUID().toString(), reply.text, fromUser = false, language = reply.language, timestamp = System.currentTimeMillis()))
        reply
    }

    /**
     * Сначала быстрые правила (открыть, громкость, таймер…), для смешанной фразы — оба языка.
     * Всё, что правила не поняли, и разговоры — локальной нейросети; её JSON-вызов инструмента выполняем как команду.
     */
    private suspend fun route(text: String, lang: Language, userMsg: ChatMessage): Reply {
        if (useCase.inDialog || useCase.hasCustomCommand(text)) return useCase.execute(text, lang)
        val primary = ChatPrompt.languageOf(text, lang)
        val other = if (primary == Language.RU) Language.EN else Language.RU
        val cmd = useCase.parse(text, primary)
        val actionable = cmd !is VoiceCommand.Unknown && cmd !is VoiceCommand.Chat
        if (actionable) return useCase.execute(text, primary)
        val alt = useCase.parse(text, other)
        if (alt !is VoiceCommand.Unknown && alt !is VoiceCommand.Chat) return useCase.executeCommand(alt, text, primary)
        if (!ai.llmReady) return useCase.execute(text, primary)

        val answer = ai.chat(chat.items.value.filterNot { it.id == userMsg.id } + userMsg)
        ToolCalls.parse(answer)?.let { return useCase.executeCommand(it, text, primary) }
        val spoken = ChatPrompt.forSpeech(answer).ifBlank { return useCase.execute(text, primary) }
        return Reply(spoken, ChatPrompt.languageOf(spoken, primary))
    }

    companion object {
        fun from(context: Context): AppContainer = (context.applicationContext as VoiceAssistantApp).container
    }
}
