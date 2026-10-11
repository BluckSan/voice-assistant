package com.hakkiiler.voiceassistant.domain.image

import com.hakkiiler.voiceassistant.domain.parser.NumberWords
import com.hakkiiler.voiceassistant.domain.parser.Text

/**
 * Локальная проверка запроса на рисование. Отклоняет ТОЛЬКО запросы на незаконный контент —
 * сексуализированные изображения несовершеннолетних. Других фильтров нет.
 *
 * Список терминов лежит отдельно: `app/src/main/resources/content_safety/blocked_terms.txt`.
 */
class ContentSafety(
    private val always: List<String>,
    private val minor: List<String>,
    private val sexual: List<String>,
) {
    sealed interface Verdict {
        data object Allowed : Verdict
        data object Refused : Verdict
    }

    fun check(prompt: String): Verdict {
        val text = Text.normalize(prompt)
        if (text.isEmpty()) return Verdict.Allowed
        if (Text.hasAny(text, always)) return Verdict.Refused
        val mentionsMinor = Text.hasAny(text, minor) || mentionsAgeUnder18(text)
        if (mentionsMinor && Text.hasAny(text, sexual)) return Verdict.Refused
        return Verdict.Allowed
    }

    fun isAllowed(vararg prompts: String?): Boolean = prompts.filterNotNull().all { check(it) == Verdict.Allowed }

    /** «12 лет», «двенадцатилетняя» не ловим словарём, поэтому отдельно: число < 18 рядом со словом о возрасте. */
    private fun mentionsAgeUnder18(text: String): Boolean {
        val tokens = text.split(' ')
        var i = 0
        while (i < tokens.size) {
            val parsed = NumberWords.parseAt(tokens, i)
            if (parsed != null) {
                val (value, used) = parsed
                val next = tokens.drop(i + used).take(2).joinToString(" ")
                if (value in 0..17 && Text.hasAny(next, AGE_WORDS)) return true
                i += maxOf(used, 1)
            } else {
                // «12yo», «12лет» без пробела
                AGE_GLUED.find(tokens[i])?.let { m -> if (m.groupValues[1].toInt() < 18) return true }
                i++
            }
        }
        return false
    }

    companion object {
        private val AGE_WORDS = listOf("year old", "years old", "year", "years", "yo", "y o", "yr*", "лет", "летн*", "год", "года", "годик*")
        private val AGE_GLUED = Regex("^(\\d{1,2})(yo|yrs?|лет|летн\\S*)$")

        private const val RESOURCE = "/content_safety/blocked_terms.txt"

        /** Разбирает файл со списком: секции [always], [minor], [sexual], строки с # — комментарии. */
        fun parse(config: String): ContentSafety {
            val sections = mutableMapOf<String, MutableList<String>>()
            var current: String? = null
            for (raw in config.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                if (line.startsWith("[") && line.endsWith("]")) {
                    current = line.substring(1, line.length - 1).trim().lowercase()
                    continue
                }
                val section = current ?: continue
                val star = line.endsWith("*")
                val term = Text.normalize(line.trimEnd('*')) + if (star) "*" else ""
                if (term.isNotBlank() && term != "*") sections.getOrPut(section) { mutableListOf() } += term
            }
            return ContentSafety(
                always = sections["always"].orEmpty(),
                minor = sections["minor"].orEmpty(),
                sexual = sections["sexual"].orEmpty(),
            )
        }

        /** Список из ресурсов приложения (работает и в unit-тестах). */
        val default: ContentSafety by lazy {
            val text = ContentSafety::class.java.getResourceAsStream(RESOURCE)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("Не найден $RESOURCE")
            parse(text)
        }
    }
}
