package com.hakkiiler.voiceassistant.domain.model

import java.util.Locale

/** Языки, которые понимает ассистент. */
enum class Language(val locale: Locale, val label: String) {
    RU(Locale("ru", "RU"), "RU"),
    EN(Locale.US, "EN");
}

/** Режим распознавания: оба языка сразу или принудительно один. */
enum class LanguageMode { AUTO, RU, EN;

    fun forcedLanguage(): Language? = when (this) {
        AUTO -> null
        RU -> Language.RU
        EN -> Language.EN
    }
}
