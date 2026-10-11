package com.hakkiiler.voiceassistant.data.image

import android.os.Build
import android.util.Log
import java.io.File

/**
 * JNI-мост к stable-diffusion.cpp (app/src/main/cpp/sdjni.cpp).
 * Библиотека собрана только для arm64-v8a с расширениями dotprod + i8mm (ARMv8.6+, например Dimensity 8400),
 * поэтому перед загрузкой проверяем процессор: иначе на старом телефоне была бы SIGILL вместо понятной ошибки.
 */
object StableDiffusionNative {
    private const val TAG = "StableDiffusion"

    fun interface ProgressListener {
        /** Вызывается из потока генерации. */
        fun onProgress(step: Int, steps: Int, seconds: Float)
    }

    /** null — всё хорошо; иначе причина, почему рисование на этом устройстве недоступно. */
    val unsupportedReason: String? by lazy { check() }
    val isAvailable: Boolean get() = unsupportedReason == null

    private fun check(): String? {
        if ("arm64-v8a" !in Build.SUPPORTED_ABIS) return "Нужен 64-битный ARM-процессор (arm64-v8a)"
        val features = cpuFeatures()
        val missing = listOf("asimddp", "i8mm").filterNot { it in features }
        if (features.isNotEmpty() && missing.isNotEmpty()) {
            return "Процессор не поддерживает ${missing.joinToString()} (нужен ARMv8.6+, 2022 год и новее)"
        }
        return try {
            System.loadLibrary("sdjni")
            Log.i(TAG, nativeSystemInfo())
            null
        } catch (e: Throwable) {
            Log.e(TAG, "loadLibrary failed", e)
            "Не удалось загрузить нативную библиотеку: ${e.message}"
        }
    }

    private fun cpuFeatures(): Set<String> = runCatching {
        File("/proc/cpuinfo").readLines()
            .firstOrNull { it.startsWith("Features") }
            ?.substringAfter(':')?.trim()?.split(' ')?.toSet()
    }.getOrNull().orEmpty()

    external fun nativeSystemInfo(): String
    external fun nativeLastError(): String
    external fun nativeCreate(modelPath: String, taesdPath: String?, threads: Int, mmap: Boolean): Long
    external fun nativeFree(ctx: Long)
    external fun nativeCancel(ctx: Long)
    external fun nativeGenerate(
        ctx: Long, prompt: String, negative: String,
        width: Int, height: Int, steps: Int, cfg: Float, seed: Long,
        listener: ProgressListener?,
    ): ByteArray?
}
