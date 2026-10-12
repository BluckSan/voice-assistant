package com.hakkiiler.voiceassistant.data.image

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Быстрое качественное увеличение без нейросети: Lanczos-3 (раздельно по осям) + лёгкая «нерезкая маска».
 * 512×768 → 1024×1536 занимает доли секунды. Работает с ARGB IntArray, без android.graphics — покрыто юнит-тестами.
 */
object ImageUpscale {

    fun upscale(src: IntArray, w: Int, h: Int, factor: Int, sharpen: Float = 0.55f): IntArray {
        require(src.size == w * h && factor >= 1)
        if (factor == 1) return src.copyOf()
        val tw = w * factor
        val th = h * factor
        // по горизонтали: w×h → tw×h, потом по вертикали: tw×h → tw×th; каналы во float для точности
        val r = FloatArray(w * h); val g = FloatArray(w * h); val b = FloatArray(w * h)
        for (i in src.indices) {
            val p = src[i]
            r[i] = ((p shr 16) and 0xFF).toFloat(); g[i] = ((p shr 8) and 0xFF).toFloat(); b[i] = (p and 0xFF).toFloat()
        }
        val hx = Kernel(w, tw)
        val r1 = FloatArray(tw * h); val g1 = FloatArray(tw * h); val b1 = FloatArray(tw * h)
        for (y in 0 until h) {
            val row = y * w
            val out = y * tw
            for (x in 0 until tw) {
                var sr = 0f; var sg = 0f; var sb = 0f
                val start = hx.start[x]
                val base = x * Kernel.TAPS
                for (k in 0 until Kernel.TAPS) {
                    val sx = (start + k).coerceIn(0, w - 1)
                    val wt = hx.weights[base + k]
                    sr += r[row + sx] * wt; sg += g[row + sx] * wt; sb += b[row + sx] * wt
                }
                r1[out + x] = sr; g1[out + x] = sg; b1[out + x] = sb
            }
        }
        val vy = Kernel(h, th)
        val r2 = FloatArray(tw * th); val g2 = FloatArray(tw * th); val b2 = FloatArray(tw * th)
        for (y in 0 until th) {
            val start = vy.start[y]
            val base = y * Kernel.TAPS
            val out = y * tw
            for (x in 0 until tw) {
                var sr = 0f; var sg = 0f; var sb = 0f
                for (k in 0 until Kernel.TAPS) {
                    val sy = (start + k).coerceIn(0, h - 1)
                    val wt = vy.weights[base + k]
                    val i = sy * tw + x
                    sr += r1[i] * wt; sg += g1[i] * wt; sb += b1[i] * wt
                }
                r2[out + x] = sr; g2[out + x] = sg; b2[out + x] = sb
            }
        }
        if (sharpen > 0f) {
            unsharp(r2, tw, th, sharpen); unsharp(g2, tw, th, sharpen); unsharp(b2, tw, th, sharpen)
        }
        val dst = IntArray(tw * th)
        for (i in dst.indices) {
            dst[i] = (0xFF shl 24) or (clamp(r2[i]) shl 16) or (clamp(g2[i]) shl 8) or clamp(b2[i])
        }
        return dst
    }

    private fun clamp(v: Float): Int = v.roundToInt().coerceIn(0, 255)

    /** Нерезкая маска: v += amount * (v - gauss(v)), гаусс σ≈1 раздельный. */
    private fun unsharp(c: FloatArray, w: Int, h: Int, amount: Float) {
        val k = floatArrayOf(0.054f, 0.244f, 0.404f, 0.244f, 0.054f)
        val tmp = FloatArray(c.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var s = 0f
                for (j in -2..2) s += c[row + (x + j).coerceIn(0, w - 1)] * k[j + 2]
                tmp[row + x] = s
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f
                for (j in -2..2) s += tmp[(y + j).coerceIn(0, h - 1) * w + x] * k[j + 2]
                val i = y * w + x
                val d = c[i] - s
                // порог: не усиливаем шум на гладких участках (кожа, небо)
                if (abs(d) > 1.5f) c[i] += amount * d
            }
        }
    }

    /** Предрасчитанные веса Lanczos-3 для каждой выходной координаты. */
    private class Kernel(src: Int, dst: Int) {
        val start = IntArray(dst)
        val weights = FloatArray(dst * TAPS)

        init {
            val scale = src.toDouble() / dst
            for (i in 0 until dst) {
                val center = (i + 0.5) * scale - 0.5
                val first = floor(center).toInt() - (A - 1)
                start[i] = first
                var sum = 0.0
                for (k in 0 until TAPS) {
                    val wv = lanczos(center - (first + k))
                    weights[i * TAPS + k] = wv.toFloat()
                    sum += wv
                }
                for (k in 0 until TAPS) weights[i * TAPS + k] = (weights[i * TAPS + k] / sum).toFloat()
            }
        }

        companion object {
            const val A = 3
            const val TAPS = 2 * A
            fun lanczos(x: Double): Double = when {
                x == 0.0 -> 1.0
                abs(x) >= A -> 0.0
                else -> { val px = PI * x; A * sin(px) * sin(px / A) / (px * px) }
            }
        }
    }

}
