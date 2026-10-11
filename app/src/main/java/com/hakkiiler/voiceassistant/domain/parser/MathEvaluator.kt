package com.hakkiiler.voiceassistant.domain.parser

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

sealed interface MathResult {
    data class Value(val value: Double) : MathResult {
        fun format(): String =
            if (value == value.roundToLong().toDouble() && abs(value) < 1e15) value.roundToLong().toString()
            else String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
    }
    data object DivisionByZero : MathResult
    data object Invalid : MathResult
}

/** Вычисляет примеры, продиктованные словами: «сколько будет два плюс три умножить на четыре». */
object MathEvaluator {
    private val ops: Map<Char, List<String>> = mapOf(
        '+' to listOf("плюс", "прибавить", "сложить", "plus", "add", "added"),
        '-' to listOf("минус", "отнять", "вычесть", "minus", "subtract", "less"),
        '*' to listOf("умножить", "умножь", "умноженное", "помножить", "times", "multiplied", "multiply", "x", "х"),
        '/' to listOf("разделить", "раздели", "деленное", "делить", "поделить", "подели", "divided", "divide", "over"),
    )
    private val symbol = ops.flatMap { (op, ws) -> ws.map { it to op } }.toMap()

    fun opVocabulary(): Set<String> = symbol.keys

    /** Есть ли во фразе хотя бы «число оператор число». */
    fun looksLikeMath(text: String): Boolean = evaluate(text) !is MathResult.Invalid

    fun evaluate(text: String): MathResult {
        val tokens = Text.tokens(text)
        val nums = mutableListOf<Double>()
        val opsSeq = mutableListOf<Char>()
        var expectNumber = true
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (expectNumber) {
                val n = NumberWords.parseAt(tokens, i)
                if (n != null) {
                    nums += n.first.toDouble(); expectNumber = false; i += n.second; continue
                }
            } else {
                val op = symbol[t]
                if (op != null) {
                    opsSeq += op; expectNumber = true; i++; continue
                }
                // после числа идёт не оператор и не мусор — закончили
                if (NumberWords.isNumberToken(t)) break
            }
            i++ // слова-связки: «сколько», «будет», «на», "by", "what"...
        }
        if (nums.size < 2 || opsSeq.size < nums.size - 1) return MathResult.Invalid
        val n = nums.toMutableList()
        val o = opsSeq.take(n.size - 1).toMutableList()
        // сначала * и /
        var k = 0
        while (k < o.size) {
            if (o[k] == '*' || o[k] == '/') {
                if (o[k] == '/' && n[k + 1] == 0.0) return MathResult.DivisionByZero
                n[k] = if (o[k] == '*') n[k] * n[k + 1] else n[k] / n[k + 1]
                n.removeAt(k + 1); o.removeAt(k)
            } else k++
        }
        var acc = n[0]
        for (j in o.indices) acc = if (o[j] == '+') acc + n[j + 1] else acc - n[j + 1]
        return MathResult.Value(acc)
    }
}
