package com.hakkiiler.voiceassistant.domain.lang

import com.hakkiiler.voiceassistant.domain.model.Language
import com.hakkiiler.voiceassistant.domain.model.LanguageMode
import com.hakkiiler.voiceassistant.domain.parser.Keywords
import com.hakkiiler.voiceassistant.domain.parser.Lexicon
import com.hakkiiler.voiceassistant.domain.parser.Text

data class WordConf(val word: String, val conf: Float)

/** Гипотеза одного распознавателя (RU или EN) для одной и той же фразы. */
data class Hypothesis(val language: Language, val text: String, val words: List<WordConf>) {
    val isBlank get() = Text.normalize(text).isEmpty()
}

data class Selection(val language: Language, val text: String, val score: Double)

/**
 * Выбор языка, когда одну и ту же фразу распознали обе модели.
 *
 * Модель «чужого» языка всё равно что-то выдаёт (русская речь → английская абракадабра),
 * поэтому смотрим на два сигнала:
 *  1. средняя уверенность Vosk по словам (conf) — на чужом языке она заметно ниже;
 *  2. доля «знакомых» слов — ключевых слов команд, числительных, названий приложений.
 *     Настоящая команда почти всегда содержит такие слова, а абракадабра — нет.
 * Плюс небольшой бонус языку предыдущей фразы (люди редко переключаются посреди диалога).
 */
class LanguageSelector(
    private val lexicons: Map<Language, Lexicon> = Language.entries.associateWith { Keywords.lexicon(it) },
) {
    fun score(h: Hypothesis): Double {
        val tokens = Text.tokens(h.text)
        if (tokens.isEmpty()) return -1.0
        val meanConf = if (h.words.isEmpty()) 0.5 else h.words.map { it.conf.toDouble() }.average()
        val lexicon = lexicons.getValue(h.language)
        val known = tokens.count { lexicon.contains(it) }.toDouble() / tokens.size
        // одиночные короткие слова-«паразиты» английской модели на русской речи
        val junkPenalty = if (h.language == Language.EN && tokens.size == 1 && tokens[0] in EN_FILLER_NOISE) 0.25 else 0.0
        return CONF_WEIGHT * meanConf + LEXICON_WEIGHT * known - junkPenalty
    }

    fun select(hypotheses: List<Hypothesis>, mode: LanguageMode, lastLanguage: Language? = null): Selection? {
        val candidates = hypotheses.filterNot { it.isBlank }
        mode.forcedLanguage()?.let { forced ->
            val h = candidates.firstOrNull { it.language == forced } ?: return null
            return Selection(h.language, h.text, score(h))
        }
        return candidates
            .map { h -> h to score(h) + if (h.language == lastLanguage) STICKY_BONUS else 0.0 }
            .maxByOrNull { it.second }
            ?.let { (h, s) -> Selection(h.language, h.text, s) }
    }

    companion object {
        const val CONF_WEIGHT = 0.6
        const val LEXICON_WEIGHT = 0.4
        const val STICKY_BONUS = 0.03
        private val EN_FILLER_NOISE = setOf("the", "a", "huh", "uh", "oh", "um", "but", "and", "i", "it", "so", "yeah")
    }
}
