package com.hakkiiler.voiceassistant.data.stt

import android.util.Log
import com.hakkiiler.voiceassistant.data.image.StableDiffusionNative

/** JNI-мост к whisper.cpp (app/src/main/cpp/whisperjni.cpp). Те же требования к процессору, что и у рисования. */
object WhisperNative {
    val unsupportedReason: String? by lazy {
        StableDiffusionNative.unsupportedReason ?: try {
            System.loadLibrary("whisperjni"); null
        } catch (e: Throwable) {
            Log.e("WhisperNative", "loadLibrary failed", e)
            "Не удалось загрузить whisper: ${e.message}"
        }
    }

    external fun nativeOpen(path: String): Long
    external fun nativeClose(ctx: Long)
    /** @return UTF-8 "<язык>\n<текст>" или null */
    external fun nativeTranscribe(ctx: Long, pcm: FloatArray, lang: String, prompt: String, threads: Int): ByteArray?
}
