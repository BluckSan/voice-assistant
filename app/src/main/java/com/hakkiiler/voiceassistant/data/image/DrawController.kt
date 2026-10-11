package com.hakkiiler.voiceassistant.data.image

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.hakkiiler.voiceassistant.domain.image.ContentSafety
import com.hakkiiler.voiceassistant.domain.image.PromptTranslator
import com.hakkiiler.voiceassistant.domain.usecases.DrawLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.random.Random

/** Параметры генерации — они же содержимое формы на экране «Рисование». */
data class DrawForm(
    val prompt: String = "",
    val negativePrompt: String = "",
    val steps: Int = 4,
    val size: Int = 512,
    /** 1.0 — без CFG (так задумана SD-Turbo); больше 1 — включает негативный запрос, но шаг вдвое дольше. */
    val cfg: Float = 1.0f,
    /** -1 — случайный. */
    val seed: Long = -1,
    val fastDecoder: Boolean = true,
    /** Что было сказано голосом, если запрос переведён с русского. */
    val spokenPrompt: String? = null,
) {
    companion object {
        val SIZES = listOf(256, 384, 512)
        val STEPS = 1..8
    }
}

sealed interface DrawPhase {
    data object Idle : DrawPhase
    data object LoadingModel : DrawPhase
    data class Sampling(val step: Int, val steps: Int) : DrawPhase
    data object Decoding : DrawPhase
    data class Error(val message: String) : DrawPhase
}

data class DrawResult(val bitmap: Bitmap, val prompt: String, val seed: Long, val size: Int, val steps: Int, val millis: Long)

data class DrawState(
    val phase: DrawPhase = DrawPhase.Idle,
    val result: DrawResult? = null,
    val startedAt: Long = 0,
) {
    val running get() = phase is DrawPhase.LoadingModel || phase is DrawPhase.Sampling || phase is DrawPhase.Decoding
}

data class ImageModelDownloadState(
    val running: Boolean = false,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = ImageModelSource.totalBytes,
    val error: String? = null,
) {
    val progress get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/**
 * Всё рисование в одном месте (живёт в AppContainer, переживает повороты и смену вкладок):
 * загрузка модели, контекст stable-diffusion.cpp, генерация в отдельном потоке, отмена, нехватка памяти.
 */
class DrawController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val safety: ContentSafety = ContentSafety.default,
    private val translator: PromptTranslator = PromptTranslator.default,
) : DrawLauncher {

    val downloader = ImageModelDownloader(context.filesDir)

    private val _form = MutableStateFlow(DrawForm())
    val form: StateFlow<DrawForm> = _form

    private val _state = MutableStateFlow(DrawState())
    val state: StateFlow<DrawState> = _state

    private val _installed = MutableStateFlow(downloader.allInstalled())
    val installed: StateFlow<Boolean> = _installed

    private val _download = MutableStateFlow(ImageModelDownloadState(downloadedBytes = downloader.downloadedBytes()))
    val download: StateFlow<ImageModelDownloadState> = _download

    /** Запрос показать вкладку «Рисование» (голосовая команда); UI сбрасывает в false. */
    private val _openRequest = MutableStateFlow(false)
    val openRequest: StateFlow<Boolean> = _openRequest
    fun consumeOpenRequest() { _openRequest.value = false }

    val unsupportedReason: String? get() = StableDiffusionNative.unsupportedReason

    /** Один поток на всю нативную работу: контекст sd.cpp не потокобезопасен. */
    private val nativeDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "sd-worker").apply { priority = Thread.NORM_PRIORITY } }
        .asCoroutineDispatcher()

    private var ctx = 0L
    private var ctxFastDecoder: Boolean? = null
    @Volatile private var activeCtx = 0L

    private var downloadJob: Job? = null
    private var drawJob: Job? = null
    private var releaseJob: Job? = null

    fun updateForm(transform: (DrawForm) -> DrawForm) = _form.update(transform)

    // ---------------- загрузка модели ----------------

    fun downloadModel() {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            _download.value = ImageModelDownloadState(running = true, downloadedBytes = downloader.downloadedBytes())
            try {
                downloader.downloadAll { done, total ->
                    _download.value = ImageModelDownloadState(running = true, downloadedBytes = done, totalBytes = total)
                }
                _installed.value = downloader.allInstalled()
                _download.value = ImageModelDownloadState(downloadedBytes = ImageModelSource.totalBytes)
            } catch (e: CancellationException) {
                // .part остаётся — «Продолжить» докачает с того же места
                _download.value = ImageModelDownloadState(downloadedBytes = downloader.downloadedBytes())
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "model download failed", e)
                _download.value = ImageModelDownloadState(
                    downloadedBytes = downloader.downloadedBytes(),
                    error = e.message ?: e.javaClass.simpleName,
                )
            }
        }
    }

    fun cancelDownload() { downloadJob?.cancel() }

    fun deleteModel() {
        if (_state.value.running || downloadJob?.isActive == true) return
        scope.launch {
            withContext(nativeDispatcher) { freeContext() }
            downloader.deleteAll()
            _installed.value = false
            _download.value = ImageModelDownloadState()
        }
    }

    // ---------------- генерация ----------------

    /** @return null, если запущено; иначе текст ошибки для показа. */
    fun generate(): String? {
        val f = _form.value
        val prompt = f.prompt.trim()
        if (prompt.isEmpty()) return "Опиши, что нарисовать / Enter a prompt"
        if (!safety.isAllowed(prompt, f.negativePrompt, f.spokenPrompt)) {
            val msg = "Запрос отклонён: похоже на незаконный контент с несовершеннолетними. / Refused: illegal content involving minors."
            _state.update { it.copy(phase = DrawPhase.Error(msg)) }
            return msg
        }
        unsupportedReason?.let { return it }
        if (!_installed.value) return "Сначала скачай модель / Download the model first"
        if (_state.value.running) return "Уже рисую / Already drawing"

        val seed = if (f.seed >= 0) f.seed else Random.nextLong(0, Int.MAX_VALUE.toLong())
        val size = f.size.coerceIn(256, 512) / 64 * 64
        val steps = f.steps.coerceIn(DrawForm.STEPS.first, DrawForm.STEPS.last)
        releaseJob?.cancel()
        _state.update { it.copy(phase = DrawPhase.LoadingModel, startedAt = System.currentTimeMillis()) }
        drawJob = scope.launch {
            try {
                val result = withContext(nativeDispatcher) {
                    ensureMemory(size)
                    val handle = ensureContext(f.fastDecoder)
                    activeCtx = handle
                    _state.update { it.copy(phase = DrawPhase.Sampling(0, steps)) }
                    val started = System.currentTimeMillis()
                    val rgb = try {
                        StableDiffusionNative.nativeGenerate(
                            handle, prompt, f.negativePrompt.trim(), size, size, steps, f.cfg, seed,
                        ) { step, total, _ ->
                            if (total == steps) {
                                _state.update { s ->
                                    s.copy(phase = if (step >= total) DrawPhase.Decoding else DrawPhase.Sampling(step, total))
                                }
                            }
                        }
                    } finally {
                        activeCtx = 0
                    }
                    if (cancelRequested) throw CancellationException("cancelled")
                    rgb ?: throw IllegalStateException(nativeError("Генерация не удалась / Generation failed"))
                    DrawResult(toBitmap(rgb, size, size), prompt, seed, size, steps, System.currentTimeMillis() - started)
                }
                _state.value = DrawState(phase = DrawPhase.Idle, result = result)
            } catch (e: CancellationException) {
                _state.update { it.copy(phase = DrawPhase.Idle) }
            } catch (e: OutOfMemoryError) {
                withContext(nativeDispatcher) { freeContext() }
                _state.update { it.copy(phase = DrawPhase.Error(LOW_MEMORY)) }
            } catch (e: Exception) {
                Log.e(TAG, "generation failed", e)
                _state.update { it.copy(phase = DrawPhase.Error(e.message ?: e.javaClass.simpleName)) }
            } finally {
                cancelRequested = false
                scheduleRelease()
            }
        }
        return null
    }

    @Volatile private var cancelRequested = false

    fun cancel() {
        if (!_state.value.running) return
        cancelRequested = true
        activeCtx.takeIf { it != 0L }?.let { StableDiffusionNative.nativeCancel(it) }
        // загрузку контекста прервать нельзя — отмена сработает сразу после неё
    }

    fun clearError() = _state.update { if (it.phase is DrawPhase.Error) it.copy(phase = DrawPhase.Idle) else it }

    /** Перед загрузкой проверяем свободную память: лучше понятная ошибка, чем убитое системой приложение. */
    private fun ensureMemory(size: Int) {
        if (ctx != 0L) return // модель уже в памяти
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val need = estimateBytes(size)
        Log.i(TAG, "avail=${info.availMem / MB} MB, need≈${need / MB} MB, low=${info.lowMemory}")
        if (info.availMem < need) {
            throw IllegalStateException(
                "Мало свободной памяти: доступно ${info.availMem / MB} МБ, нужно около ${need / MB} МБ. " +
                    "Закрой тяжёлые приложения или выбери размер поменьше. / Not enough free RAM.",
            )
        }
    }

    private fun ensureContext(fastDecoder: Boolean): Long {
        if (ctx != 0L && ctxFastDecoder == fastDecoder) return ctx
        freeContext()
        _state.update { it.copy(phase = DrawPhase.LoadingModel) }
        val model = downloader.file(ImageModelSource.MODEL).absolutePath
        val taesd = if (fastDecoder) downloader.file(ImageModelSource.TAESD).absolutePath else null
        val handle = StableDiffusionNative.nativeCreate(model, taesd, threadCount(), true)
        if (handle == 0L) throw IllegalStateException(nativeError("Не удалось загрузить модель / Failed to load the model"))
        ctx = handle
        ctxFastDecoder = fastDecoder
        return handle
    }

    private fun freeContext() {
        if (ctx != 0L) {
            StableDiffusionNative.nativeFree(ctx)
            ctx = 0
            ctxFastDecoder = null
        }
    }

    /** Модель (~2 ГБ) держим в памяти несколько минут для следующей картинки, потом отпускаем. */
    private fun scheduleRelease() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(KEEP_LOADED_MS)
            if (!_state.value.running) withContext(nativeDispatcher) { freeContext() }
        }
    }

    /** Из Application.onTrimMemory: система просит память — выгружаем модель, если не рисуем. */
    fun onTrimMemory(level: Int) {
        @Suppress("DEPRECATION")
        val critical = level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        if (critical && !_state.value.running) scope.launch { withContext(nativeDispatcher) { freeContext() } }
    }

    private fun nativeError(fallback: String) = runCatching { StableDiffusionNative.nativeLastError() }.getOrNull()
        ?.takeIf { it.isNotBlank() }?.let { "$fallback: $it" } ?: fallback

    private fun toBitmap(rgb: ByteArray, w: Int, h: Int): Bitmap {
        val pixels = IntArray(w * h)
        for (i in pixels.indices) {
            val r = rgb[i * 3].toInt() and 0xFF
            val g = rgb[i * 3 + 1].toInt() and 0xFF
            val b = rgb[i * 3 + 2].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    // ---------------- голосовая команда ----------------

    override fun draw(prompt: String?): DrawLauncher.Result {
        if (unsupportedReason != null) return DrawLauncher.Result.UNSUPPORTED
        if (_state.value.running) return DrawLauncher.Result.BUSY
        _openRequest.value = true
        if (prompt == null) return DrawLauncher.Result.OPENED
        val english = translator.translate(prompt)
        _form.update {
            it.copy(
                prompt = english.ifBlank { prompt },
                spokenPrompt = if (translator.needsTranslation(prompt)) prompt else null,
                seed = -1,
            )
        }
        if (!_installed.value) return DrawLauncher.Result.NEEDS_MODEL
        return if (generate() == null) DrawLauncher.Result.STARTED else DrawLauncher.Result.BUSY
    }

    private companion object {
        const val TAG = "DrawController"
        const val MB = 1024L * 1024
        const val KEEP_LOADED_MS = 3 * 60_000L
        const val LOW_MEMORY = "Не хватило памяти. Закрой другие приложения или выбери размер 384/256. / Out of memory."

        /**
         * Веса ~1.8 ГБ + рабочие буферы, растущие с площадью картинки.
         * Замер sd.cpp на x86: пик ~2.3 ГБ RSS для 512×512 с TAESD; берём с небольшим запасом.
         */
        fun estimateBytes(size: Int): Long {
            val area = size.toDouble() * size / (512.0 * 512.0)
            return 1_900L * MB + (600L * MB * area).toLong()
        }

        fun threadCount(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            // у телефонов 1+3+4 ядра: медленные «маленькие» ядра только тормозят общий шаг
            return if (cores >= 8) 4 else (cores / 2).coerceAtLeast(2)
        }
    }
}
