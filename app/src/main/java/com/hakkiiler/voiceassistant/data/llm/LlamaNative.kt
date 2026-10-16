package com.hakkiiler.voiceassistant.data.llm

import android.util.Log
import com.hakkiiler.voiceassistant.data.image.StableDiffusionNative

/** JNI-мост к llama.cpp (app/src/main/cpp/llamajni.cpp). */
object LlamaNative {
    fun interface PieceListener {
        /** Кусок ответа в UTF-8 (может резать многобайтный символ). @return false — остановить. */
        fun onPiece(bytes: ByteArray): Boolean
    }

    val unsupportedReason: String? by lazy {
        StableDiffusionNative.unsupportedReason ?: try {
            System.loadLibrary("llamajni"); null
        } catch (e: Throwable) {
            Log.e("LlamaNative", "loadLibrary failed", e)
            "Не удалось загрузить llama.cpp: ${e.message}"
        }
    }

    external fun nativeOpen(path: String, nCtx: Int, threads: Int, gpu: Boolean): Long
    external fun nativeVulkanProbe(): String
    external fun nativeClose(handle: Long)
    external fun nativeCancel(handle: Long)
    external fun nativeLastError(handle: Long): String
    external fun nativeChat(
        handle: Long, roles: Array<String>, contents: Array<String>,
        maxTokens: Int, temperature: Float, listener: PieceListener?,
    ): ByteArray?
}
