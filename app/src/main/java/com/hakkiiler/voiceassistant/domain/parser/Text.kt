package com.hakkiiler.voiceassistant.domain.parser

/** Общие текстовые утилиты для разбора распознанной речи. */
object Text {
    private val junk = Regex("[^\\p{L}\\p{N}:'\\s]")
    private val spaces = Regex("\\s+")

    fun normalize(s: String): String = s.lowercase()
        .replace('ё', 'е')
        .replace(junk, " ")
        .replace(spaces, " ")
        .trim()

    fun tokens(s: String): List<String> = normalize(s).split(' ').filter { it.isNotEmpty() }

    /**
     * Проверяет, есть ли во фразе одна из ключевых фраз.
     * Синтаксис ключа: "слово" — целое слово/фраза; "основа*" — слово, начинающееся с основы
     * (удобно для русских падежей: "музык*" → музыка, музыку, музыки).
     */
    fun matches(text: String, phrase: String): Boolean {
        val padded = " $text "
        return if (phrase.endsWith("*")) {
            padded.contains(" " + phrase.dropLast(1))
        } else {
            padded.contains(" $phrase ")
        }
    }

    fun hasAny(text: String, phrases: Collection<String>): Boolean = phrases.any { matches(text, it) }

    /** Удаляет из текста все вхождения ключевых фраз (целыми словами). */
    fun removeAll(text: String, phrases: Collection<String>): String {
        var result = " $text "
        // длинные фразы первыми, чтобы "open the" не разбивалось на части
        for (p in phrases.sortedByDescending { it.length }) {
            result = if (p.endsWith("*")) {
                result.replace(Regex(" " + Regex.escape(p.dropLast(1)) + "\\S*(?= )"), "")
            } else {
                result.replace(" $p ", " ")
            }
        }
        return result.replace(spaces, " ").trim()
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** Похожесть строк 0..1. */
    fun similarity(a: String, b: String): Double {
        val max = maxOf(a.length, b.length)
        return if (max == 0) 1.0 else 1.0 - levenshtein(a, b).toDouble() / max
    }

    private val translit = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ж' to "zh", 'з' to "z",
        'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p",
        'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch",
        'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
    )

    /** Грубая транслитерация RU→Latin, чтобы «телеграм» находил «Telegram». */
    fun transliterate(s: String): String = buildString {
        for (c in s) append(translit[c] ?: c.toString())
    }

    fun capitalize(s: String): String = s.replaceFirstChar { it.titlecase() }
}
