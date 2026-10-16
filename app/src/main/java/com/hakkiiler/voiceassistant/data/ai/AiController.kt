package com.hakkiiler.voiceassistant.data.ai

import com.hakkiiler.voiceassistant.data.accel.Accel
import com.hakkiiler.voiceassistant.data.accel.AccelMode
import com.hakkiiler.voiceassistant.data.accel.Backend
import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.hakkiiler.voiceassistant.data.image.ImageModelFile
import com.hakkiiler.voiceassistant.data.llm.LlamaNative
import com.hakkiiler.voiceassistant.data.stt.WhisperNative
import com.hakkiiler.voiceassistant.domain.llm.ChatPrompt
import com.hakkiiler.voiceassistant.domain.model.ChatMessage
import com.hakkiiler.voiceassistant.domain.model.Language
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.Executors

enum class AiModel(val file: ImageModelFile, val title: String) {
    WHISPER(AiModelSource.WHISPER, AiModelSource.WHISPER_NAME),
    LLM(AiModelSource.LLM, AiModelSource.LLM_NAME),
}

data class AiDownloadState(
    val installed: Boolean = false,
    val running: Boolean = false,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
) {
    val progress: Float get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/** Состояние нейросети-собеседника для UI. */
data class LlmState(
    val loading: Boolean = false,
    /** Текст ответа, который сейчас печатается (null — ничего не генерируется). */
    val streaming: String? = null,
)

/**
 * Whisper (смешанная RU/EN речь) и локальная LLM (разговор + разбор команд):
 * загрузка файлов, нативные контексты в отдельных потоках, выгрузка по таймеру и перед рисованием.
 */
class AiController(private val context: Context, private val scope: CoroutineScope) {

    val downloader = ResumableDownloader(File(context.filesDir, "ai-models"))

    private val states = AiModel.entries.associateWith {
        MutableStateFlow(AiDownloadState(installed = downloader.isInstalled(it.file), downloadedBytes = downloader.progressOf(it.file), totalBytes = it.file.bytes))
    }
    fun state(m: AiModel): StateFlow<AiDownloadState> = states.getValue(m)
    fun installed(m: AiModel) = states.getValue(m).value.installed
    private val jobs = mutableMapOf<AiModel, Job>()

    val unsupportedReason: String? get() = LlamaNative.unsupportedReason

    private val _llm = MutableStateFlow(LlmState())
    val llm: StateFlow<LlmState> = _llm

    private val whisperThread = Executors.newSingleThreadExecutor { r -> Thread(r, "whisper") }.asCoroutineDispatcher()
    private val llmThread = Executors.newSingleThreadExecutor { r -> Thread(r, "llama") }.asCoroutineDispatcher()
    private var whisperCtx = 0L
    private var llmCtx = 0L
    @Volatile private var activeLlm = 0L
    private var whisperRelease: Job? = null
    private var llmRelease: Job? = null

    // ---------------- загрузка ----------------

    fun download(m: AiModel) {
        if (jobs[m]?.isActive == true) return
        val st = states.getValue(m)
        jobs[m] = scope.launch {
            st.value = st.value.copy(running = true, error = null)
            try {
                downloader.download(m.file) { done -> st.value = st.value.copy(running = true, downloadedBytes = done) }
                st.value = AiDownloadState(installed = true, downloadedBytes = m.file.bytes, totalBytes = m.file.bytes)
            } catch (e: CancellationException) {
                st.value = st.value.copy(running = false, downloadedBytes = downloader.progressOf(m.file))
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "download $m", e)
                st.value = st.value.copy(running = false, downloadedBytes = downloader.progressOf(m.file), error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancelDownload(m: AiModel) { jobs[m]?.cancel() }

    fun delete(m: AiModel) {
        if (jobs[m]?.isActive == true) return
        scope.launch {
            if (m == AiModel.LLM) withContext(llmThread) { closeLlm() } else withContext(whisperThread) { closeWhisper() }
            downloader.delete(m.file)
            states.getValue(m).value = AiDownloadState(totalBytes = m.file.bytes)
        }
    }

    // ---------------- Whisper ----------------

    val whisperReady: Boolean get() = installed(AiModel.WHISPER) && WhisperNative.unsupportedReason == null

    /**
     * Распознать фразу целиком. [hint] — язык, который выбрал Vosk: для смешанной речи он обычно RU,
     * и Whisper с подсказкой пишет английские слова латиницей («поставь reminder»).
     * @return (язык ответа, текст) или null
     */
    suspend fun transcribe(pcm16: ShortArray, hint: Language?): Pair<Language, String>? {
        if (!whisperReady || pcm16.size < SAMPLE_RATE / 4) return null
        return withContext(whisperThread) {
            whisperRelease?.cancel()
            if (whisperCtx == 0L) {
                whisperCtx = WhisperNative.nativeOpen(downloader.file(AiModelSource.WHISPER).absolutePath)
                if (whisperCtx == 0L) return@withContext null
            }
            val f = FloatArray(pcm16.size) { pcm16[it] / 32768f }
            val lang = when (hint) { Language.RU -> "ru"; Language.EN -> "en"; null -> "auto" }
            val prompt = if (hint == Language.EN) "" else WHISPER_PROMPT
            val t0 = System.currentTimeMillis()
            val out = WhisperNative.nativeTranscribe(whisperCtx, f, lang, prompt, threads())?.toString(Charsets.UTF_8)
            Log.i(TAG, "whisper ${pcm16.size / SAMPLE_RATE.toFloat()}s audio in ${System.currentTimeMillis() - t0} ms: $out")
            scheduleWhisperRelease()
            out ?: return@withContext null
            val text = out.substringAfter('\n').trim().trim('.', ' ').let(::cleanWhisper)
            if (text.isBlank()) return@withContext null
            ChatPrompt.languageOf(text, hint ?: Language.RU) to text
        }
    }

    private fun closeWhisper() {
        if (whisperCtx != 0L) { WhisperNative.nativeClose(whisperCtx); whisperCtx = 0 }
    }

    private fun scheduleWhisperRelease() {
        whisperRelease?.cancel()
        whisperRelease = scope.launch { delay(KEEP_WHISPER_MS); withContext(whisperThread) { closeWhisper() } }
    }

    // ---------------- LLM ----------------

    val llmReady: Boolean get() = installed(AiModel.LLM) && unsupportedReason == null

    /**
     * Ответ на последнее сообщение пользователя с учётом истории. Текст печатается в [llm].streaming.
     * @throws IllegalStateException при нехватке памяти / занятом рисовании / ошибке модели
     */
    /** Бэкенд открытой сейчас модели. */
    private var llmGpu = false

    /** Открыть LLM на видеочипе (если выбран/прошёл самопроверку) или на больших ядрах CPU. */
    private suspend fun openLlm(): Long {
        Accel.pinToBigCores()
        val path = downloader.file(AiModelSource.LLM).absolutePath
        var backend = Accel.resolve(llm = true)
        if (backend == Backend.VULKAN) {
            Accel.beginGpuRun("llm-load")
            val h = try { LlamaNative.nativeOpen(path, N_CTX, Accel.cpuThreads(), true) } finally { Accel.endGpuRun() }
            if (h != 0L) { llmGpu = true; Log.i(TAG, "LLM on Vulkan"); return h }
            if (Accel.mode.value != AccelMode.AUTO) return 0L
            Accel.markBad(llm = true, reason = "load failed")
            backend = Backend.CPU
        }
        llmGpu = false
        Log.i(TAG, "LLM on CPU, threads=${Accel.cpuThreads()} ($backend)")
        return LlamaNative.nativeOpen(path, N_CTX, Accel.cpuThreads(), false)
    }

    private inline fun <T> gpuGuard(block: () -> T): T {
        if (!llmGpu) return block()
        Accel.beginGpuRun("llm")
        try { return block() } finally { Accel.endGpuRun() }
    }

    suspend fun chat(history: List<ChatMessage>, now: LocalDateTime = LocalDateTime.now()): String {
        check(llmReady) { "Нейросеть не скачана / Model not installed" }
        if (!HeavyModelGuard.beforeChat()) error("Сейчас идёт рисование — подожди, пока закончится. / Drawing in progress.")
        llmRelease?.cancel()
        try {
            return withContext(llmThread) {
                if (llmCtx == 0L) {
                    _llm.value = LlmState(loading = true)
                    ensureMemory()
                    llmCtx = openLlm()
                    if (llmCtx == 0L) error("Не удалось загрузить нейросеть / Failed to load the model")
                }
                _llm.value = LlmState(streaming = "")
                val system = ChatPrompt.system(now)
                var msgs = trimHistory(history, MAX_HISTORY_CHARS)
                var result: String? = null
                for (attempt in 0..2) {
                    result = gpuGuard { run(system, msgs) }
                    if (result != null) break
                    val err = LlamaNative.nativeLastError(llmCtx)
                    if (err != "context overflow") error("Ошибка нейросети: $err")
                    msgs = trimHistory(msgs, MAX_HISTORY_CHARS / (2 shl attempt))
                }
                result?.trim() ?: error("Слишком длинный разговор — очисти чат / Conversation too long")
            }
        } finally {
            _llm.value = LlmState()
            scheduleLlmRelease()
        }
    }

    /**
     * Перевести запрос на рисование в английский промпт для SD (без истории, жадно, без вывода в чат).
     * Память под SD освобождает вызывающий (DrawController); после — LLM выгружается перед рисованием.
     */
    suspend fun writeDrawPrompt(request: String): String? {
        if (!llmReady) return null
        llmRelease?.cancel()
        return withContext(llmThread) {
            if (llmCtx == 0L) {
                ensureMemory()
                llmCtx = openLlm()
                if (llmCtx == 0L) return@withContext null
            }
            activeLlm = llmCtx
            try {
                val w = com.hakkiiler.voiceassistant.domain.image.DrawPromptWriter
                val roles = arrayOf("system") + w.EXAMPLES.flatMap { listOf("user", "assistant") } + "user"
                val contents = arrayOf(w.SYSTEM) + w.EXAMPLES.flatMap { listOf(it.first, it.second) } + request
                gpuGuard { LlamaNative.nativeChat(llmCtx, roles, contents, 96, 0f, null)?.toString(Charsets.UTF_8) }
            } finally {
                activeLlm = 0
            }
        }
    }

    private fun run(system: String, msgs: List<ChatMessage>): String? {
        val roles = arrayOf("system") + msgs.map { if (it.fromUser) "user" else "assistant" }
        val contents = arrayOf(system) + msgs.map { it.text }
        val buf = ByteArrayOutputStream()
        activeLlm = llmCtx
        try {
            val bytes = LlamaNative.nativeChat(llmCtx, roles, contents, MAX_TOKENS, TEMPERATURE) { piece ->
                buf.write(piece)
                _llm.value = LlmState(streaming = decodeComplete(buf.toByteArray()))
                true
            } ?: return null
            return bytes.toString(Charsets.UTF_8)
        } finally {
            activeLlm = 0
        }
    }

    fun cancelChat() {
        val h = activeLlm
        if (h != 0L) LlamaNative.nativeCancel(h)
    }

    private fun ensureMemory() {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        if (info.availMem < AiModelSource.LLM_RAM) {
            error("Мало свободной памяти для нейросети: ${info.availMem / MB} МБ, нужно ~${AiModelSource.LLM_RAM / MB} МБ. Закрой тяжёлые приложения. / Not enough RAM.")
        }
    }

    private fun closeLlm() {
        if (llmCtx != 0L) { LlamaNative.nativeClose(llmCtx); llmCtx = 0 }
    }

    private fun scheduleLlmRelease() {
        llmRelease?.cancel()
        llmRelease = scope.launch { delay(KEEP_LLM_MS); withContext(llmThread) { closeLlm() } }
    }

    /** Перед рисованием и при нехватке памяти. */
    suspend fun unloadAll() {
        cancelChat()
        withContext(llmThread) { closeLlm() }
        withContext(whisperThread) { closeWhisper() }
    }

    companion object {
        private const val TAG = "AiController"
        private const val MB = 1024L * 1024
        private const val SAMPLE_RATE = 16000
        private const val N_CTX = 4096
        private const val MAX_TOKENS = 320
        private const val TEMPERATURE = 0.5f
        private const val MAX_HISTORY_CHARS = 6000
        private const val KEEP_LLM_MS = 5 * 60_000L
        private const val KEEP_WHISPER_MS = 10 * 60_000L
        private const val WHISPER_PROMPT = "Открой YouTube, поставь reminder на завтра, включи music в Spotify, напиши в Telegram."

        fun threads(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            return if (cores >= 8) 6 else (cores - 1).coerceAtLeast(2)
        }

        /** Последние сообщения, пока влезают в лимит символов; первым должен идти пользователь. */
        fun trimHistory(history: List<ChatMessage>, maxChars: Int): List<ChatMessage> {
            val out = ArrayDeque<ChatMessage>()
            var total = 0
            for (m in history.asReversed()) {
                if (out.isNotEmpty() && total + m.text.length > maxChars) break
                out.addFirst(m)
                total += m.text.length
            }
            while (out.size > 1 && !out.first().fromUser) out.removeFirst()
            return out.toList()
        }

        /** Декодируем только целые UTF-8 символы: кусок токена может оборвать букву посередине. */
        fun decodeComplete(bytes: ByteArray): String {
            var end = bytes.size
            var i = end - 1
            var back = 0
            while (i >= 0 && back < 4 && (bytes[i].toInt() and 0xC0) == 0x80) { i--; back++ }
            if (i >= 0) {
                val lead = bytes[i].toInt() and 0xFF
                val need = when { lead >= 0xF0 -> 4; lead >= 0xE0 -> 3; lead >= 0xC0 -> 2; else -> 1 }
                if (end - i < need) end = i
            }
            return String(bytes, 0, end, Charsets.UTF_8)
        }

        /** Whisper иногда дописывает «Продолжение следует…» и т.п. на тишине — убираем. */
        fun cleanWhisper(text: String): String {
            val junk = listOf("продолжение следует", "субтитры", "spasibo", "thanks for watching", "[музыка]", "[music]")
            if (junk.any { text.lowercase().contains(it) } && text.length < 60) return ""
            return text.replace(Regex("\\s+"), " ").removePrefix("- ").trim()
        }
    }
}
