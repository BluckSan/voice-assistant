package com.hakkiiler.voiceassistant.data.image

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.hakkiiler.voiceassistant.domain.image.ContentSafety
import com.hakkiiler.voiceassistant.domain.image.PromptTranslator
import com.hakkiiler.voiceassistant.domain.usecases.DrawLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.hakkiiler.voiceassistant.domain.image.DrawPromptWriter
import java.util.concurrent.Executors
import kotlin.random.Random

/** Параметры генерации — они же содержимое формы на экране «Рисование». */
data class DrawForm(
    val prompt: String = "",
    val negativePrompt: String = DrawPresets.DEFAULT_NEGATIVE,
    val steps: Int = DrawPresets.DEFAULT_STEPS,
    val size: DrawSize = DrawPresets.DEFAULT_SIZE,
    /** 1.0 — без CFG (так рассчитана LCM); больше 1 — включает негативный запрос, но шаг вдвое дольше. */
    val cfg: Float = DrawPresets.DEFAULT_CFG,
    /** -1 — случайный. */
    val seed: Long = -1,
    /** TAESD: декодирование в ~2 раза быстрее, лица чуть мягче. */
    val fastDecoder: Boolean = false,
    /** Увеличить результат в 2 раза (по умолчанию Lanczos + резкость, за доли секунды). */
    val upscale: Boolean = true,
    /** Увеличивать нейросетью RealESRGAN (если скачана): детальнее, но несколько минут. */
    val neuralUpscale: Boolean = false,
    /** Что было сказано голосом, если запрос переведён с русского. */
    val spokenPrompt: String? = null,
    /** Английский запрос, по которому реально рисовала модель (если исходный был на русском). */
    val translatedPrompt: String? = null,
)

sealed interface DrawPhase {
    data object Idle : DrawPhase
    /** Перевод русского запроса на английский (нейросеть или словарь). */
    data object Translating : DrawPhase
    data object LoadingModel : DrawPhase
    /** Нажали «Отмена»: ждём, пока нативный код остановится на ближайшем шаге. */
    data object Cancelling : DrawPhase
    data class Sampling(val step: Int, val steps: Int) : DrawPhase
    data object Decoding : DrawPhase
    data object Upscaling : DrawPhase
    data class Error(val message: String) : DrawPhase
}

data class DrawResult(
    val bitmap: Bitmap,
    val prompt: String,
    val seed: Long,
    val width: Int,
    val height: Int,
    val steps: Int,
    val millis: Long,
    val file: File? = null,
)

/** Картинка из истории (лента последних работ). */
data class GalleryItem(val file: File, val prompt: String)

data class DrawState(
    val phase: DrawPhase = DrawPhase.Idle,
    val result: DrawResult? = null,
    val startedAt: Long = 0,
) {
    val running get() = phase is DrawPhase.LoadingModel || phase is DrawPhase.Translating || phase is DrawPhase.Cancelling || phase is DrawPhase.Sampling ||
        phase is DrawPhase.Decoding || phase is DrawPhase.Upscaling
}

data class ImageModelDownloadState(
    val running: Boolean = false,
    /** Идёт однократное вливание LCM-LoRA в модель (после загрузки). */
    val merging: Boolean = false,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = ImageModelSource.requiredBytes,
    val error: String? = null,
) {
    val progress get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

data class UpscalerState(
    val installed: Boolean = false,
    val running: Boolean = false,
    val downloadedBytes: Long = 0,
    val error: String? = null,
) {
    val progress get() = (downloadedBytes.toFloat() / ImageModelSource.UPSCALER.bytes).coerceIn(0f, 1f)
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

    private val _upscaler = MutableStateFlow(UpscalerState(installed = downloader.upscalerInstalled()))
    val upscaler: StateFlow<UpscalerState> = _upscaler

    private val galleryDir = File(context.filesDir, "drawings")
    private val _gallery = MutableStateFlow(loadGallery())
    val gallery: StateFlow<List<GalleryItem>> = _gallery

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
    private var ctxFlash: Boolean? = null
    @Volatile private var activeCtx = 0L

    private var downloadJob: Job? = null
    private var upscalerJob: Job? = null
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
                if (downloader.needsMerge()) {
                    _download.update { it.copy(merging = true) }
                    mergeLcm()
                }
                _installed.value = downloader.allInstalled()
                _download.value = ImageModelDownloadState(downloadedBytes = ImageModelSource.requiredBytes)
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

    /**
     * Однократно вливаем LCM-LoRA в модель: шаг потом ~1.5 раза быстрее, чем с LoRA «на лету».
     * Замер на box: ~6 с и ~2.6 ГБ памяти; на телефоне — до пары минут.
     */
    private suspend fun mergeLcm() = withContext(nativeDispatcher) {
        unsupportedReason?.let { throw IllegalStateException(it) }
        freeContext()
        downloader.mergeTmp.delete()
        val ok = StableDiffusionNative.nativeMergeLora(
            downloader.file(ImageModelSource.BASE).absolutePath,
            downloader.file(ImageModelSource.LCM_LORA).absolutePath,
            1.0f,
            downloader.mergeTmp.absolutePath,
            threadCount(),
        )
        if (!ok || !downloader.finishMerge()) {
            downloader.mergeTmp.delete()
            throw IllegalStateException(nativeError("Не удалось подготовить модель / Model preparation failed"))
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
            _upscaler.value = UpscalerState()
        }
    }

    fun downloadUpscaler() {
        if (upscalerJob?.isActive == true) return
        upscalerJob = scope.launch {
            _upscaler.value = UpscalerState(running = true)
            try {
                downloader.downloadUpscaler { done, _ -> _upscaler.value = UpscalerState(running = true, downloadedBytes = done) }
                _upscaler.value = UpscalerState(installed = downloader.upscalerInstalled())
            } catch (e: CancellationException) {
                _upscaler.value = UpscalerState()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "upscaler download failed", e)
                _upscaler.value = UpscalerState(error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancelUpscalerDownload() { upscalerJob?.cancel() }

    fun deleteUpscaler() {
        if (_state.value.running) return
        downloader.deleteUpscaler()
        _upscaler.value = UpscalerState()
    }

    // ---------------- генерация ----------------

    /** @return null, если запущено; иначе текст ошибки для показа. */
    fun generate(): String? {
        val f = _form.value
        val original = f.prompt.trim()
        if (original.isEmpty()) return "Опиши, что нарисовать / Enter a prompt"
        if (!safety.isAllowed(original, f.negativePrompt, f.spokenPrompt)) {
            val msg = "Запрос отклонён: похоже на незаконный контент с несовершеннолетними. / Refused: illegal content involving minors."
            _state.update { it.copy(phase = DrawPhase.Error(msg)) }
            return msg
        }
        unsupportedReason?.let { return it }
        if (!_installed.value) return "Сначала скачай модель / Download the model first"
        if (_state.value.running) return "Уже рисую / Already drawing"

        val seed = if (f.seed >= 0) f.seed else Random.nextLong(0, Int.MAX_VALUE.toLong())
        val size = f.size.takeIf { it in DrawPresets.SIZES } ?: DrawPresets.DEFAULT_SIZE
        val steps = f.steps.coerceIn(DrawPresets.STEPS.first, DrawPresets.STEPS.last)
        releaseJob?.cancel()
        cancelRequested = false
        StableDiffusionNative.nativeResetAbort()
        val needsTr = translator.needsTranslation(original)
        _state.update { it.copy(phase = if (needsTr) DrawPhase.Translating else DrawPhase.LoadingModel, startedAt = System.currentTimeMillis()) }
        drawJob = scope.launch {
            try {
                // CLIP в SD 1.5 понимает только английский: русский запрос переводим (нейросетью, иначе словарём)
                val prompt = if (needsTr) translatePrompt(original) else original
                _form.update { if (it.prompt.trim() == original) it.copy(translatedPrompt = prompt.takeIf { needsTr }) else it }
                if (!safety.isAllowed(prompt, f.negativePrompt, original)) {
                    error("Запрос отклонён: похоже на незаконный контент с несовершеннолетними. / Refused.")
                }
                if (cancelRequested) throw CancellationException("cancelled")
                _state.update { it.copy(phase = DrawPhase.LoadingModel) }
                // нейросеть-собеседник и Whisper выгружаем: вместе с SD они не влезут в память
                com.hakkiiler.voiceassistant.data.ai.HeavyModelGuard.beforeDraw()
                val result = withContext(nativeDispatcher) {
                    val plan = planFor(f, size, steps)
                    ensureMemory(plan)
                    val handle = ensureContext(f.fastDecoder, plan.flashAttn)
                    if (cancelRequested) throw CancellationException("cancelled")
                    activeCtx = handle
                    _state.update { it.copy(phase = DrawPhase.Sampling(0, steps)) }
                    val started = System.currentTimeMillis()
                    val rgb = try {
                        StableDiffusionNative.nativeGenerate(
                            handle, prompt, f.negativePrompt.trim(), size.width, size.height, steps, f.cfg, seed,
                            ImageModelSource.SAMPLER, plan.vaeTiling,
                        ) { step, total, _ ->
                            if (total == steps && !cancelRequested) {
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
                    var bitmap = toBitmap(rgb, size.width, size.height)
                    if (f.upscale && !cancelRequested) {
                        _state.update { it.copy(phase = DrawPhase.Upscaling) }
                        bitmap = upscale(rgb, bitmap, size, f.neuralUpscale)
                    }
                    if (cancelRequested) throw CancellationException("cancelled")
                    val file = runCatching { saveToHistory(bitmap, prompt) }.onFailure { Log.w(TAG, "history", it) }.getOrNull()
                    DrawResult(bitmap, prompt, seed, bitmap.width, bitmap.height, steps, System.currentTimeMillis() - started, file)
                }
                _state.value = DrawState(phase = DrawPhase.Idle, result = result)
            } catch (e: CancellationException) {
                _state.update { it.copy(phase = DrawPhase.Idle) }
            } catch (e: OutOfMemoryError) {
                withContext(nativeDispatcher) { freeContext() }
                _state.update { it.copy(phase = DrawPhase.Error(LOW_MEMORY)) }
            } catch (e: Exception) {
                Log.e(TAG, "generation failed", e)
                _state.update {
                    it.copy(phase = if (cancelRequested) DrawPhase.Idle else DrawPhase.Error(e.message ?: e.javaClass.simpleName))
                }
            } finally {
                cancelRequested = false
                scheduleRelease()
            }
        }
        return null
    }

    /**
     * ×2. По умолчанию — Lanczos-3 + нерезкая маска на CPU (доли секунды, см. [ImageUpscale]).
     * Если выбран «нейро» и скачан RealESRGAN — нейросеть дорисовывает детали, но это долго (минуты).
     */
    private fun upscale(rgb: ByteArray, bitmap: Bitmap, size: DrawSize, neural: Boolean): Bitmap {
        val factor = DrawPresets.UPSCALE
        val tw = size.width * factor
        val th = size.height * factor
        if (neural && downloader.upscalerInstalled()) {
            // модель SD освобождаем на время: так пик памяти ниже
            freeContext()
            val up = StableDiffusionNative.nativeUpscalerCreate(
                downloader.file(ImageModelSource.UPSCALER).absolutePath, threadCount(), UPSCALE_TILE,
            )
            if (up != 0L) {
                try {
                    val out = IntArray(2)
                    val big = StableDiffusionNative.nativeUpscale(up, rgb, size.width, size.height, out)
                    if (cancelRequested) throw CancellationException("cancelled")
                    if (big != null && out[0] > 0) {
                        val bigBitmap = toBitmap(big, out[0], out[1])
                        if (out[0] == tw) return bigBitmap
                        // x4 → x2: уменьшение ровно вдвое с фильтрацией
                        return Bitmap.createScaledBitmap(bigBitmap, tw, th, true).also { if (it !== bigBitmap) bigBitmap.recycle() }
                    }
                    Log.w(TAG, "ESRGAN failed: ${nativeError("")}")
                } finally {
                    StableDiffusionNative.nativeUpscalerFree(up)
                }
            }
        }
        val pixels = IntArray(size.width * size.height)
        bitmap.getPixels(pixels, 0, size.width, 0, 0, size.width, size.height)
        val big = ImageUpscale.upscale(pixels, size.width, size.height, factor)
        return Bitmap.createBitmap(big, tw, th, Bitmap.Config.ARGB_8888)
    }

    // ---------------- история (лента последних работ) ----------------

    private fun loadGallery(): List<GalleryItem> =
        galleryDir.listFiles { f -> f.name.endsWith(".png") }.orEmpty()
            .sortedByDescending { it.name }
            .take(MAX_GALLERY)
            .map { GalleryItem(it, File(it.path.removeSuffix(".png") + ".txt").takeIf { t -> t.isFile }?.readText().orEmpty()) }

    private fun saveToHistory(bitmap: Bitmap, prompt: String): File {
        galleryDir.mkdirs()
        val file = File(galleryDir, "${System.currentTimeMillis()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(galleryDir, file.name.removeSuffix(".png") + ".txt").writeText(prompt)
        galleryDir.listFiles { f -> f.name.endsWith(".png") }.orEmpty().sortedByDescending { it.name }.drop(MAX_GALLERY).forEach {
            it.delete()
            File(it.path.removeSuffix(".png") + ".txt").delete()
        }
        _gallery.value = loadGallery()
        return file
    }

    /** Показать картинку из ленты в превью. */
    fun openFromGallery(item: GalleryItem) {
        if (_state.value.running) return
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(item.file.path) } ?: return@launch
            _state.update {
                it.copy(result = DrawResult(bmp, item.prompt, -1, bmp.width, bmp.height, 0, 0, item.file))
            }
        }
    }

    fun deleteFromGallery(item: GalleryItem) {
        item.file.delete()
        File(item.file.path.removeSuffix(".png") + ".txt").delete()
        _gallery.value = loadGallery()
        _state.update { if (it.result?.file == item.file) it.copy(result = null) else it }
    }

    @Volatile private var cancelRequested = false

    fun cancel() {
        if (!_state.value.running || _state.value.phase == DrawPhase.Cancelling) return
        cancelRequested = true
        // флаг в нативном коде: сработает на ближайшем шаге, даже если сейчас грузится модель
        if (unsupportedReason == null) StableDiffusionNative.nativeCancel(activeCtx)
        promptCancel?.invoke()
        _state.update { it.copy(phase = DrawPhase.Cancelling) }
    }

    /** Перевод запроса нейросетью (задаёт AppContainer, если LLM скачана). null — использовать словарь. */
    @Volatile var llmPromptWriter: (suspend (String) -> String?)? = null
    @Volatile var promptCancel: (() -> Unit)? = null

    private suspend fun translatePrompt(original: String): String {
        val writer = llmPromptWriter
        // SD и LLM вместе в память не влезут: перед загрузкой LLM отпускаем модель рисования
        if (writer != null) withContext(nativeDispatcher) { freeContext() }
        val llm = runCatching { writer?.invoke(DrawPromptWriter.stripCommand(original)) }
            .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "llm prompt", it) }.getOrNull()
        if (cancelRequested) throw CancellationException("cancelled")
        val cleaned = llm?.let(DrawPromptWriter::cleanLlmOutput)
        return cleaned ?: DrawPromptWriter.fallback(original, translator)
    }

    fun clearError() = _state.update { if (it.phase is DrawPhase.Error) it.copy(phase = DrawPhase.Idle) else it }

    /** Свободная память для расчёта плана (если модель уже загружена — её вес тоже «наш»). */
    fun availableMemory(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return info.availMem + (if (ctx != 0L) MODEL_RAM else 0L)
    }

    /** План для формы: flash-attention / VAE плитками выбираются по реально свободной памяти. */
    fun planFor(f: DrawForm, size: DrawSize = f.size, steps: Int = f.steps): DrawPresets.Plan =
        DrawPresets.plan(size, steps, f.cfg, f.fastDecoder, f.upscale, f.neuralUpscale && downloader.upscalerInstalled(), availableMemory())

    /** Перед загрузкой проверяем свободную память: лучше понятная ошибка, чем убитое системой приложение. */
    private fun ensureMemory(plan: DrawPresets.Plan) {
        val avail = availableMemory()
        Log.i(TAG, "avail=${avail / MB} MB, need≈${plan.peakBytes / MB} MB, flash=${plan.flashAttn}, tiling=${plan.vaeTiling}")
        if (avail < plan.peakBytes) {
            throw IllegalStateException(
                "Мало свободной памяти: доступно ${avail / MB} МБ, нужно около ${plan.peakBytes / MB} МБ. " +
                    "Закрой тяжёлые приложения или выбери размер поменьше. / Not enough free RAM.",
            )
        }
    }

    private fun ensureContext(fastDecoder: Boolean, flashAttn: Boolean): Long {
        if (ctx != 0L && ctxFastDecoder == fastDecoder && ctxFlash == flashAttn) return ctx
        freeContext()
        _state.update { it.copy(phase = DrawPhase.LoadingModel) }
        val model = downloader.mergedFile.absolutePath
        val taesd = if (fastDecoder) downloader.file(ImageModelSource.TAESD).absolutePath else null
        val handle = StableDiffusionNative.nativeCreate(model, taesd, threadCount(), true, flashAttn)
        if (handle == 0L) throw IllegalStateException(nativeError("Не удалось загрузить модель / Failed to load the model"))
        ctx = handle
        ctxFastDecoder = fastDecoder
        ctxFlash = flashAttn
        return handle
    }

    private fun freeContext() {
        if (ctx != 0L) {
            StableDiffusionNative.nativeFree(ctx)
            ctx = 0
            ctxFastDecoder = null
            ctxFlash = null
        }
    }

    /** Модель (~1.8 ГБ) держим в памяти несколько минут для следующей картинки, потом отпускаем. */
    private fun scheduleRelease() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(KEEP_LOADED_MS)
            if (!_state.value.running) withContext(nativeDispatcher) { freeContext() }
        }
    }

    /**
     * Освободить модель рисования для LLM. @return false, если сейчас идёт рисование (тогда LLM грузить нельзя).
     */
    suspend fun releaseForOtherModel(): Boolean {
        if (_state.value.running) return false
        releaseJob?.cancel()
        withContext(nativeDispatcher) { freeContext() }
        return true
    }

    val isDrawing: Boolean get() = _state.value.running

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
        // перевод на английский — в generate(): нейросетью, если скачана, иначе словарём
        _form.update { it.copy(prompt = prompt, spokenPrompt = null, translatedPrompt = null, seed = -1) }
        if (!_installed.value) return DrawLauncher.Result.NEEDS_MODEL
        return if (generate() == null) DrawLauncher.Result.STARTED else DrawLauncher.Result.BUSY
    }

    private companion object {
        const val TAG = "DrawController"
        const val MB = 1024L * 1024
        const val KEEP_LOADED_MS = 3 * 60_000L
        const val LOW_MEMORY = "Не хватило памяти. Закрой другие приложения или выбери размер поменьше. / Out of memory."
        const val MODEL_RAM = 1_700L * MB
        const val MAX_GALLERY = 24
        const val UPSCALE_TILE = 128

        /**
         * Сколько потоков: только «быстрые» ядра (частота ≥ 60% от максимальной).
         * Dimensity 8400 — все 8 ядер Cortex-A725 (3.25/3.0/2.1 ГГц), раньше брали лишь 4 — шаг шёл ~1.6× медленнее.
         * Одно ядро оставляем системе и интерфейсу.
         */
        fun threadCount(): Int = cachedThreads

        private val cachedThreads: Int by lazy {
            val cores = Runtime.getRuntime().availableProcessors()
            val freqs = (0 until cores).mapNotNull { i ->
                runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
            }
            val fast = if (freqs.size == cores && freqs.isNotEmpty()) {
                val max = freqs.max()
                freqs.count { it >= max * 0.6 }
            } else cores / 2
            when {
                fast >= 8 -> 7
                fast >= 6 -> fast
                else -> fast.coerceAtLeast(4).coerceAtMost(cores)
            }.also { Log.i(TAG, "threads=$it (cores=$cores, freqs=$freqs)") }
        }
    }
}
