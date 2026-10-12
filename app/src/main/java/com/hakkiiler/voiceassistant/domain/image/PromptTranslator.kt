package com.hakkiiler.voiceassistant.domain.image

import com.hakkiiler.voiceassistant.domain.parser.Text

/**
 * Грубый пословный перевод RU → EN для запросов на рисование.
 * Текстовый энкодер SD 1.5 (CLIP) понимает только английский, поэтому
 * «нарисуй кота в космосе» превращается в "cat in space". Результат пользователь видит и может поправить.
 * Словарь: `app/src/main/resources/prompt/ru_en.txt`.
 */
class PromptTranslator(private val exact: Map<String, String>, stems: Map<String, String>) {
    private val stemsByLength = stems.entries.sortedByDescending { it.key.length }

    fun needsTranslation(text: String) = text.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }

    /** Если в запросе нет кириллицы — возвращает его как есть. */
    fun translate(text: String): String {
        if (!needsTranslation(text)) return text.trim()
        val out = mutableListOf<String>()
        for (token in Text.tokens(text)) {
            val word = when {
                !needsTranslation(token) -> token
                else -> exact[token] ?: stemsByLength.firstOrNull { token.startsWith(it.key) }?.value
            } ?: continue
            if (out.lastOrNull() != word) out += word
        }
        // предлоги в конце/начале без существительного смысла не несут
        while (out.isNotEmpty() && out.last() in DANGLING) out.removeAt(out.lastIndex)
        while (out.isNotEmpty() && out.first() in DANGLING) out.removeAt(0)
        return out.joinToString(" ")
    }

    companion object {
        private val DANGLING = setOf("in", "on", "with", "and", "or", "near", "to", "for", "made of", "along", "above", "under")
        private const val RESOURCE = "/prompt/ru_en.txt"

        fun parse(config: String): PromptTranslator {
            val exact = mutableMapOf<String, String>()
            val stems = mutableMapOf<String, String>()
            for (raw in config.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val key = line.substring(0, eq).trim().lowercase().replace('ё', 'е')
                val value = line.substring(eq + 1).trim()
                if (value.isEmpty()) continue
                if (key.endsWith("*")) stems[key.dropLast(1)] = value else exact[key] = value
            }
            return PromptTranslator(exact, stems)
        }

        val default: PromptTranslator by lazy {
            val text = PromptTranslator::class.java.getResourceAsStream(RESOURCE)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("Не найден $RESOURCE")
            parse(text)
        }
    }
}
