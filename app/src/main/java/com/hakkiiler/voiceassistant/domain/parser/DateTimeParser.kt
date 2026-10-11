package com.hakkiiler.voiceassistant.domain.parser

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * Что удалось понять о времени из фразы.
 * [absolute] — для относительных выражений («через 10 минут», "in an hour").
 * [consumed] — индексы токенов, ушедших на дату/время (чтобы остаток стал названием события).
 */
data class DateTimeSpec(
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val absolute: LocalDateTime? = null,
    val hasDayPeriod: Boolean = false,
    val consumed: Set<Int> = emptySet(),
) {
    val isEmpty get() = date == null && time == null && absolute == null

    /** Превращает в конкретный момент; null, если известна только дата без времени. */
    fun resolve(now: LocalDateTime): LocalDateTime? {
        absolute?.let { return it }
        val t = time ?: return null
        if (date != null) return LocalDateTime.of(date, t)
        val today = LocalDateTime.of(now.toLocalDate(), t)
        if (today.isAfter(now)) return today
        // «в пять» в 14:00 скорее значит 17:00, чем 05:00 завтра
        if (!hasDayPeriod && t.hour in 1..11) {
            val evening = today.plusHours(12)
            if (evening.isAfter(now)) return evening
        }
        return today.plusDays(1)
    }

    fun withDate(d: LocalDate?): DateTimeSpec = if (d == null || date != null) this else copy(date = d)
}

/** Разбор даты и времени в свободной речи на русском и английском. */
object DateTimeParser {

    private val weekdays: List<Pair<DayOfWeek, List<String>>> = listOf(
        DayOfWeek.MONDAY to listOf("понедельник*", "monday*"),
        DayOfWeek.TUESDAY to listOf("вторник*", "tuesday*"),
        DayOfWeek.WEDNESDAY to listOf("сред*", "wednesday*"),
        DayOfWeek.THURSDAY to listOf("четверг*", "thursday*"),
        DayOfWeek.FRIDAY to listOf("пятниц*", "friday*"),
        DayOfWeek.SATURDAY to listOf("суббот*", "saturday*"),
        DayOfWeek.SUNDAY to listOf("воскресень*", "sunday*"),
    )

    private fun stemMatch(token: String, stem: String) =
        if (stem.endsWith("*")) token.startsWith(stem.dropLast(1)) else token == stem

    fun weekdayOf(token: String): DayOfWeek? =
        weekdays.firstOrNull { (_, stems) -> stems.any { stemMatch(token, it) } }?.first

    /** Ближайший такой день недели строго после сегодняшнего. */
    fun nextWeekday(today: LocalDate, dow: DayOfWeek): LocalDate = today.with(TemporalAdjusters.next(dow))

    private val hourWords = setOf("час", "часа", "часов", "o'clock", "oclock", "hours", "hour")
    private val minuteWords = setOf("минут", "минуты", "минуту", "минута", "мин", "minutes", "minute", "min")
    private val introducers = setOf("в", "во", "к", "на", "at", "by", "for")

    fun parse(text: String, now: LocalDateTime): DateTimeSpec? {
        val raw = Text.tokens(text)
        // "p m" / "a m" → "pm" / "am"
        val tokens = mutableListOf<String>()
        val map = mutableListOf<IntArray>()
        var k = 0
        while (k < raw.size) {
            if (k + 1 < raw.size && raw[k] in setOf("p", "a") && raw[k + 1] == "m") {
                tokens += raw[k] + "m"; map += intArrayOf(k, k + 1); k += 2
            } else {
                tokens += raw[k]; map += intArrayOf(k); k++
            }
        }
        val used = mutableSetOf<Int>()
        fun use(vararg idx: Int) { idx.forEach { i -> map[i].forEach { used += it } } }

        relative(tokens, now, ::use)?.let { return DateTimeSpec(absolute = it, consumed = used) }

        val today = now.toLocalDate()
        var date: LocalDate? = null
        for (i in tokens.indices) {
            val t = tokens[i]
            when {
                t == "послезавтра" -> { date = today.plusDays(2); use(i) }
                t == "after" && i > 0 && tokens[i - 1] == "day" && tokens.getOrNull(i + 1) == "tomorrow" -> {
                    date = today.plusDays(2); use(i - 1, i, i + 1); if (i >= 2 && tokens[i - 2] == "the") use(i - 2)
                }
                (t == "завтра" || t == "tomorrow") && date == null -> { date = today.plusDays(1); use(i) }
                (t == "сегодня" || t == "today" || t == "tonight") && date == null -> { date = today; use(i) }
                weekdayOf(t) != null && date == null -> {
                    date = nextWeekday(today, weekdayOf(t)!!); use(i)
                    if (i > 0 && tokens[i - 1] in setOf("в", "во", "on", "this", "next")) use(i - 1)
                }
            }
        }

        var time: LocalTime? = null
        var period = false
        // явное время цифрами 17:30
        for (i in tokens.indices) {
            val m = Regex("^(\\d{1,2}):(\\d{2})$").find(tokens[i])
            if (m != null) {
                val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
                if (h < 24 && mi < 60) { time = LocalTime.of(h, mi); use(i); if (i > 0 && tokens[i - 1] in introducers) use(i - 1) }
            }
        }
        if (time == null) {
            for (i in tokens.indices) {
                when (tokens[i]) {
                    "полдень", "noon" -> { time = LocalTime.NOON; period = true; use(i) }
                    "полночь", "midnight" -> { time = LocalTime.MIDNIGHT; period = true; use(i) }
                }
                if (time != null) { if (i > 0 && tokens[i - 1] in introducers) use(i - 1); break }
            }
        }
        if (time == null) {
            val r = clockTime(tokens, ::use)
            if (r != null) { time = r.first; period = r.second }
        }
        if (date == null && time == null) return null
        return DateTimeSpec(date = date, time = time, hasDayPeriod = period, consumed = used)
    }

    private fun relative(tokens: List<String>, now: LocalDateTime, use: (IntArray) -> Unit): LocalDateTime? {
        for (i in tokens.indices) {
            if (tokens[i] != "через" && tokens[i] != "in") continue
            var j = i + 1
            if (tokens.getOrNull(j) == "полчаса") { use(intArrayOf(i, j)); return now.plusMinutes(30) }
            if (tokens.getOrNull(j) == "half" && tokens.getOrNull(j + 1) in setOf("an", "a") && tokens.getOrNull(j + 2) == "hour") {
                use(intArrayOf(i, j, j + 1, j + 2)); return now.plusMinutes(30)
            }
            var amount = 1L
            val idx = mutableListOf(i)
            val n = NumberWords.parseAt(tokens, j)
            if (n != null) { amount = n.first; repeat(n.second) { idx += j + it }; j += n.second }
            else if (tokens.getOrNull(j) in setOf("an", "a")) { idx += j; j++ }
            val unit = tokens.getOrNull(j) ?: continue
            idx += j
            val result = when {
                unit.startsWith("минут") || unit == "мин" || unit.startsWith("minute") -> now.plusMinutes(amount)
                unit.startsWith("час") || unit.startsWith("hour") -> now.plusHours(amount)
                unit.startsWith("сут") || unit.startsWith("дн") || unit == "день" || unit.startsWith("day") -> now.plusDays(amount)
                unit.startsWith("недел") || unit.startsWith("week") -> now.plusWeeks(amount)
                else -> null
            }
            if (result != null) { use(idx.toIntArray()); return result }
        }
        return null
    }

    /** «в пять тридцать вечера», "at 7 pm", «в час дня», «в 9 часов 15 минут». */
    private fun clockTime(tokens: List<String>, use: (IntArray) -> Unit): Pair<LocalTime, Boolean>? {
        for (i in tokens.indices) {
            val intro = tokens[i] in introducers
            val start = if (intro) i + 1 else i
            if (start >= tokens.size) continue
            var hour: Int
            var j = start
            val idx = mutableListOf<Int>()
            if (intro && tokens[start] == "час") { // «в час» = 13:00 / 01:00
                hour = 1; idx += start; j = start + 1
            } else {
                val n = NumberWords.parseAt(tokens, start) ?: continue
                if (n.first !in 0..24) continue
                hour = n.first.toInt(); repeat(n.second) { idx += start + it }; j = start + n.second
            }
            var marker = false
            if (tokens.getOrNull(j) in hourWords) { idx += j; j++; marker = true }
            var minute = 0
            val m = NumberWords.parseAt(tokens, j)
            if (m != null && m.first in 0..59 && tokens.getOrNull(j + m.second) !in hourWords) {
                minute = m.first.toInt(); repeat(m.second) { idx += j + it }; j += m.second
                if (tokens.getOrNull(j) in minuteWords) { idx += j; j++ }
            }
            // период суток
            var period = false
            when (tokens.getOrNull(j)) {
                "утра", "am" -> { if (hour == 12) hour = 0; period = true; idx += j }
                "дня", "вечера", "pm" -> { if (hour < 12) hour += 12; period = true; idx += j }
                "ночи" -> { if (hour == 12) hour = 0; period = true; idx += j }
                "in" -> if (tokens.getOrNull(j + 1) == "the") {
                    when (tokens.getOrNull(j + 2)) {
                        "morning" -> { if (hour == 12) hour = 0; period = true; idx += listOf(j, j + 1, j + 2) }
                        "afternoon", "evening" -> { if (hour < 12) hour += 12; period = true; idx += listOf(j, j + 1, j + 2) }
                    }
                }
                "at" -> if (tokens.getOrNull(j + 1) == "night") {
                    if (hour in 6..11) hour += 12; period = true; idx += listOf(j, j + 1)
                }
            }
            // без предлога засчитываем только с явным маркером: «пять вечера», "7 pm", «9 часов»
            if (!intro && !marker && !period) continue
            if (hour == 24) hour = 0
            if (hour !in 0..23) continue
            if (intro) idx += i
            use(idx.toIntArray())
            return LocalTime.of(hour, minute) to period
        }
        return null
    }
}
