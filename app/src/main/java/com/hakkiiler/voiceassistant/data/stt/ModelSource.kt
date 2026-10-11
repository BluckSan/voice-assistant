package com.hakkiiler.voiceassistant.data.stt

import com.hakkiiler.voiceassistant.domain.model.Language

/** Откуда берутся модели Vosk: официальные архивы alphacephei.com. */
object ModelSource {
    fun name(lang: Language) = when (lang) {
        Language.RU -> "vosk-model-small-ru-0.22"
        Language.EN -> "vosk-model-small-en-us-0.15"
    }

    fun url(lang: Language) = "https://alphacephei.com/vosk/models/${name(lang)}.zip"

    /** Примерный размер архива — для прогресса, если сервер не отдал Content-Length. */
    fun approxBytes(lang: Language): Long = when (lang) {
        Language.RU -> 46_236_750L
        Language.EN -> 41_205_931L
    }

    fun dirName(lang: Language) = when (lang) {
        Language.RU -> "model-ru"
        Language.EN -> "model-en"
    }
}
