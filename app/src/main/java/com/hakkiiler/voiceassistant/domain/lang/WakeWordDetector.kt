package com.hakkiiler.voiceassistant.domain.lang

import com.hakkiiler.voiceassistant.domain.parser.Text

data class WakeHit(val hypothesis: Hypothesis, val wakeWord: String, val remainder: String)

/**
 * Ищет слово-активатор в гипотезах обоих распознавателей.
 * «Ассистент» по-русски английская модель часто слышит как "assistant" и наоборот,
 * поэтому проверяем все гипотезы и допускаем мелкие ошибки распознавания
 * (падежи: «ассистента», опечатки: «асистент»).
 */
object WakeWordDetector {

    fun detect(hypotheses: List<Hypothesis>, wakeWords: List<String>): WakeHit? {
        val hits = hypotheses.mapNotNull { h -> find(h, wakeWords) }
        // предпочитаем гипотезу, где после активатора есть команда, затем более длинную
        return hits.maxWithOrNull(compareBy<WakeHit>({ it.remainder.isNotBlank() }, { it.remainder.length }))
    }

    fun find(h: Hypothesis, wakeWords: List<String>): WakeHit? {
        val tokens = Text.tokens(h.text)
        if (tokens.isEmpty()) return null
        for (wake in wakeWords) {
            val w = Text.tokens(wake)
            if (w.isEmpty()) continue
            for (i in 0..tokens.size - w.size) {
                val ok = w.indices.all { j -> tokenMatches(tokens[i + j], w[j]) }
                if (ok) {
                    val after = tokens.drop(i + w.size)
                    val before = tokens.take(i).filterNot { it in LEADING_NOISE }
                    val remainder = (after.ifEmpty { before }).joinToString(" ")
                    return WakeHit(h, wake, remainder)
                }
            }
        }
        return null
    }

    fun tokenMatches(token: String, wake: String): Boolean {
        if (token == wake) return true
        if (wake.length >= 5 && token.startsWith(wake) && token.length - wake.length <= 2) return true // падежи
        if (wake.length >= 6 && Text.levenshtein(token, wake) <= 1) return true
        return false
    }

    private val LEADING_NOISE = setOf("эй", "слушай", "окей", "ok", "okay", "hey", "hi", "o", "о")
}

/** Все гипотезы, в которых нашёлся активатор (для выбора языка между ними). */
fun WakeWordDetector.detectAll(hypotheses: List<Hypothesis>, wakeWords: List<String>): List<WakeHit> =
    hypotheses.mapNotNull { find(it, wakeWords) }
