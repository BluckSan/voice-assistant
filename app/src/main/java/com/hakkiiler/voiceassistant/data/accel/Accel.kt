package com.hakkiiler.voiceassistant.data.accel

import android.content.Context
import android.content.Intent
import android.system.Os
import android.util.Log
import com.hakkiiler.voiceassistant.BuildConfig
import com.hakkiiler.voiceassistant.data.image.StableDiffusionNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

enum class AccelMode { AUTO, VULKAN, CPU }

/** Чем реально считали: для подписи в интерфейсе. */
enum class Backend { VULKAN, CPU }

/** Результат самопроверки Vulkan (отдельно для ggml рисования и ggml чата — это разные версии). */
data class ProbeResult(val sdOk: Boolean, val llmOk: Boolean, val noF16: Boolean, val detail: String, val gpu: String)

/**
 * Выбор ускорителя (Авто / Vulkan / CPU), самопроверка видеочипа в отдельном процессе (падение драйвера Mali
 * не роняет приложение), запоминание результата, «маркер падения» во время работы на видеочипе,
 * и привязка CPU-потоков к большим ядрам.
 */
object Accel {
    private const val TAG = "Accel"
    private const val PREFS = "accel"
    private const val K_MODE = "mode"
    private const val K_PROBE = "probe_v" // + versionCode: после обновления проверяем заново
    private const val PROBE_TIMEOUT_MS = 45_000L

    private lateinit var app: Context
    private val mutex = Mutex()

    private val _mode = MutableStateFlow(AccelMode.AUTO)
    val mode: StateFlow<AccelMode> = _mode.asStateFlow()
    private val _probe = MutableStateFlow<ProbeResult?>(null)
    val probe: StateFlow<ProbeResult?> = _probe.asStateFlow()
    private val _probing = MutableStateFlow(false)
    val probing: StateFlow<Boolean> = _probing.asStateFlow()

    /** Последний реально использованный бэкенд для рисования / чата (null — ещё не запускали). */
    private val _drawBackend = MutableStateFlow<Backend?>(null)
    val drawBackend: StateFlow<Backend?> = _drawBackend.asStateFlow()
    private val _llmBackend = MutableStateFlow<Backend?>(null)
    val llmBackend: StateFlow<Backend?> = _llmBackend.asStateFlow()

    private val prefs get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val probeKey get() = K_PROBE + BuildConfig.VERSION_CODE
    private val crashMarker get() = File(app.filesDir, "gpu/running.marker")
    internal fun probeFile(ctx: Context) = File(ctx.filesDir, "gpu/probe.txt")

    fun init(context: Context) {
        app = context.applicationContext
        _mode.value = prefs.getString(K_MODE, null)?.let { runCatching { AccelMode.valueOf(it) }.getOrNull() } ?: AccelMode.AUTO
        _probe.value = prefs.getString(probeKey, null)?.let(::decode)
        // прошлый запуск упал посреди работы на видеочипе — в «Авто» больше его не используем
        if (crashMarker.exists()) {
            val what = runCatching { crashMarker.readText() }.getOrDefault("?")
            crashMarker.delete()
            Log.w(TAG, "previous GPU run crashed: $what")
            markBad(what.startsWith("llm"), "crash during $what")
        }
        if (_probe.value?.noF16 == true) runCatching { Os.setenv("GGML_VK_DISABLE_F16", "1", true) }
    }

    fun setMode(m: AccelMode) {
        _mode.value = m
        prefs.edit().putString(K_MODE, m.name).apply()
    }

    /** Сбросить запомненный результат и проверить заново. */
    suspend fun reprobe(): ProbeResult {
        prefs.edit().remove(probeKey).apply()
        _probe.value = null
        return ensureProbe()
    }

    /** Решение для рисования (SD) или чата (LLM). */
    suspend fun resolve(llm: Boolean): Backend = when (_mode.value) {
        AccelMode.CPU -> Backend.CPU
        AccelMode.VULKAN -> Backend.VULKAN
        AccelMode.AUTO -> {
            val p = ensureProbe()
            if (if (llm) p.llmOk else p.sdOk) Backend.VULKAN else Backend.CPU
        }
    }.also { if (llm) _llmBackend.value = it else _drawBackend.value = it }

    /** Перед долгой работой на видеочипе: если процесс упадёт, при следующем запуске «Авто» выберет CPU. */
    fun beginGpuRun(what: String) {
        runCatching { crashMarker.parentFile?.mkdirs(); crashMarker.writeText(what) }
    }

    fun endGpuRun() { crashMarker.delete() }

    /** Видеочип выдал мусор (NaN / чёрная картинка) или ошибку — в «Авто» дальше CPU. */
    fun markBad(llm: Boolean, reason: String) {
        val old = _probe.value ?: ProbeResult(sdOk = true, llmOk = true, noF16 = false, detail = "", gpu = "")
        val p = if (llm) old.copy(llmOk = false, detail = "llm: $reason") else old.copy(sdOk = false, detail = "sd: $reason")
        save(p)
        if (llm) _llmBackend.value = Backend.CPU else _drawBackend.value = Backend.CPU
    }

    suspend fun ensureProbe(): ProbeResult = mutex.withLock {
        _probe.value?.takeIf { !it.detail.startsWith("service:") }?.let { return it }
        _probing.value = true
        try {
            var r = runProbe(disableF16 = false)
            if (!r.sdOk || !r.llmOk) {
                // у Mali бывают проблемы с fp16-шейдерами: пробуем без них
                val r2 = runProbe(disableF16 = true)
                if ((r2.sdOk && !r.sdOk) || (r2.llmOk && !r.llmOk)) {
                    r = r2.copy(noF16 = true)
                    runCatching { Os.setenv("GGML_VK_DISABLE_F16", "1", true) }
                }
            }
            // сервис не запустился (приложение в фоне) — не запоминаем, проверим в следующий раз
            if (r.detail.startsWith("service:")) _probe.value = r else save(r)
            r
        } finally {
            _probing.value = false
        }
    }

    private suspend fun runProbe(disableF16: Boolean): ProbeResult = withContext(Dispatchers.IO) {
        if (!StableDiffusionNative.isAvailable) return@withContext ProbeResult(false, false, false, "native unavailable", "")
        val f = probeFile(app)
        f.parentFile?.mkdirs()
        f.writeText("pending\n")
        val started = System.currentTimeMillis()
        try {
            app.startService(Intent(app, GpuProbeService::class.java).putExtra(GpuProbeService.EXTRA_NO_F16, disableF16))
        } catch (e: Exception) {
            return@withContext ProbeResult(false, false, false, "service: ${e.message}", "")
        }
        var text = "pending"
        while (System.currentTimeMillis() - started < PROBE_TIMEOUT_MS) {
            delay(250)
            text = runCatching { f.readText() }.getOrDefault("pending")
            if (text.contains("done")) break
        }
        Log.i(TAG, "probe(noF16=$disableF16) in ${System.currentTimeMillis() - started} ms: ${text.replace('\n', ' ')}")
        parseProbe(text, disableF16)
    }

    /** Файл пробы: строки «sd=ok …», «llm=…», «gpu=…», «done». Без «done» — процесс пробы упал или завис. */
    internal fun parseProbe(text: String, noF16: Boolean): ProbeResult {
        val lines = text.lines()
        fun v(k: String) = lines.firstOrNull { it.startsWith("$k=") }?.substringAfter('=')
        val sd = v("sd")
        val llm = v("llm")
        val crashed = "done" !in lines.map { it.trim() }
        val detail = when {
            crashed && sd == null -> "probe crashed/timeout in sd"
            crashed -> "probe crashed/timeout in llm (sd=$sd)"
            else -> "sd=$sd; llm=$llm"
        }
        return ProbeResult(
            sdOk = sd?.startsWith("ok") == true,
            llmOk = !crashed && llm?.startsWith("ok") == true,
            noF16 = noF16,
            detail = detail,
            gpu = v("gpu").orEmpty(),
        )
    }

    private fun save(p: ProbeResult) {
        _probe.value = p
        prefs.edit().putString(probeKey, encode(p)).apply()
    }

    private fun encode(p: ProbeResult) = listOf(p.sdOk, p.llmOk, p.noF16, p.gpu.replace('|', ' '), p.detail.replace('|', ' ')).joinToString("|")
    private fun decode(s: String): ProbeResult? = runCatching {
        val a = s.split('|')
        ProbeResult(a[0].toBoolean(), a[1].toBoolean(), a[2].toBoolean(), a.getOrElse(4) { "" }, a[3])
    }.getOrNull()

    // ---------------- CPU: большие ядра ----------------

    /** Максимальные частоты ядер (кГц) из sysfs; пусто, если не читаются. */
    private val freqs: List<Long> by lazy {
        val cores = Runtime.getRuntime().availableProcessors()
        val f = (0 until cores).mapNotNull { i ->
            runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
        }
        if (f.size == cores) f else emptyList()
    }

    /**
     * Индексы «больших» ядер: частота ≥ 85% от максимальной. Dimensity 8400: 4×2.1 + 3×3.0 + 1×3.25 ГГц →
     * ядра 4–7. Маленькие ядра в общем пуле тормозят всех: ggml ждёт самый медленный поток на каждом шаге.
     */
    val bigCores: List<Int> by lazy { bigCoresOf(freqs).also { Log.i(TAG, "big cores=$it freqs=$freqs") } }

    internal fun bigCoresOf(freqs: List<Long>): List<Int> {
        if (freqs.isEmpty()) return emptyList()
        val max = freqs.max()
        return freqs.indices.filter { freqs[it] >= max * 0.85 }
    }

    /** Потоки для CPU-режима: по одному на большое ядро (минимум 4, если ядер мало — все). */
    fun cpuThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        val big = bigCores.size
        return when {
            big >= 4 -> big
            big > 0 -> 4.coerceAtMost(cores)
            else -> (cores / 2).coerceIn(2, 4)
        }
    }

    /** Маска для sched_setaffinity: большие ядра (если их ≥ 4), иначе 0 — не трогаем. */
    fun bigCoreMask(): Long = if (bigCores.size >= 4) bigCores.fold(0L) { m, i -> m or (1L shl i) } else 0L

    /** Привязать текущий поток (и создаваемые им потоки ggml) к большим ядрам. */
    fun pinToBigCores() {
        val m = bigCoreMask()
        if (m != 0L && StableDiffusionNative.isAvailable) {
            runCatching { StableDiffusionNative.nativeSetAffinity(m) }
        }
    }

    fun label(b: Backend?): String = when (b) {
        Backend.VULKAN -> "Vulkan" + (_probe.value?.gpu?.takeIf { it.isNotBlank() }?.let { " · ${it.take(24)}" } ?: "")
        Backend.CPU -> "CPU · ${cpuThreads()} ${if (bigCores.size >= 4) "больших ядра" else "потоков"}"
        null -> "—"
    }
}
