package com.hakkiiler.voiceassistant.domain.parser

/**
 * Разбор чисел, произнесённых словами, на русском и английском:
 * «двадцать пять» → 25, "one hundred twenty" → 120, «две тысячи сорок» → 2040.
 */
object NumberWords {
    private enum class Kind { UNIT, TEEN, TEN, HUNDRED }

    private data class W(val value: Int, val kind: Kind)

    private val words: Map<String, W> = buildMap {
        // RU
        listOf("ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять")
            .forEachIndexed { i, w -> put(w, W(i, Kind.UNIT)) }
        put("одна", W(1, Kind.UNIT)); put("одну", W(1, Kind.UNIT)); put("одно", W(1, Kind.UNIT))
        put("две", W(2, Kind.UNIT)); put("нуль", W(0, Kind.UNIT))
        listOf("десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать",
            "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать")
            .forEachIndexed { i, w -> put(w, W(10 + i, Kind.TEEN)) }
        listOf("двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят", "девяносто")
            .forEachIndexed { i, w -> put(w, W(20 + i * 10, Kind.TEN)) }
        listOf("сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот", "семьсот", "восемьсот", "девятьсот")
            .forEachIndexed { i, w -> put(w, W(100 * (i + 1), Kind.HUNDRED)) }
        // EN
        listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
            .forEachIndexed { i, w -> put(w, W(i, Kind.UNIT)) }
        listOf("ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
            .forEachIndexed { i, w -> put(w, W(10 + i, Kind.TEEN)) }
        listOf("twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
            .forEachIndexed { i, w -> put(w, W(20 + i * 10, Kind.TEN)) }
    }

    private val thousandWords = setOf("тысяча", "тысячи", "тысяч", "тысячу", "thousand")
    private val millionWords = setOf("миллион", "миллиона", "миллионов", "million")
    private const val HUNDRED_EN = "hundred"

    fun isNumberToken(t: String): Boolean =
        t.toLongOrNull() != null || t in words || t in thousandWords || t in millionWords || t == HUNDRED_EN

    /**
     * Пытается прочитать число, начиная с токена [start].
     * @return значение и количество съеденных токенов, либо null.
     */
    fun parseAt(tokens: List<String>, start: Int): Pair<Long, Int>? {
        if (start >= tokens.size) return null
        tokens[start].toLongOrNull()?.let { return it to 1 }

        var total = 0L
        var group = 0L          // текущая группа до 999
        var last: Kind? = null  // что было последним в группе — чтобы «два три» не склеивалось в 5
        var i = start
        var consumedAny = false
        while (i < tokens.size) {
            val t = tokens[i]
            val w = words[t]
            when {
                w != null -> {
                    val allowed = when (w.kind) {
                        Kind.HUNDRED -> last == null
                        Kind.TEN, Kind.TEEN -> last == null || last == Kind.HUNDRED
                        Kind.UNIT -> last == null || last == Kind.HUNDRED || last == Kind.TEN
                    }
                    if (!allowed) break
                    group += w.value
                    last = w.kind
                }
                t == HUNDRED_EN && group in 1..9 && last == Kind.UNIT -> {
                    group *= 100; last = Kind.HUNDRED
                }
                t in thousandWords && consumedAny.not() && group == 0L -> { // «тысяча»
                    total += 1000; last = null
                }
                t in thousandWords -> {
                    total += (if (group == 0L) 1 else group) * 1000; group = 0; last = null
                }
                t in millionWords -> {
                    total += (if (group == 0L) 1 else group) * 1_000_000; group = 0; last = null
                }
                t == "and" && consumedAny && i + 1 < tokens.size && tokens[i + 1] in words -> { /* one hundred and five */ }
                else -> break
            }
            consumedAny = true
            i++
        }
        if (!consumedAny) return null
        // хвостовой "and" не съедаем
        var consumed = i - start
        while (consumed > 0 && tokens[start + consumed - 1] == "and") consumed--
        return (total + group) to consumed
    }

    /** Все числа во фразе по порядку. */
    fun extractAll(text: String): List<Long> {
        val t = Text.tokens(text)
        val out = mutableListOf<Long>()
        var i = 0
        while (i < t.size) {
            val r = parseAt(t, i)
            if (r != null) { out += r.first; i += r.second } else i++
        }
        return out
    }

    fun first(text: String): Long? = extractAll(text).firstOrNull()

    /** Все слова-числительные — для лексикона выбора языка. */
    fun vocabulary(): Set<String> = words.keys + thousandWords + millionWords + HUNDRED_EN
}
