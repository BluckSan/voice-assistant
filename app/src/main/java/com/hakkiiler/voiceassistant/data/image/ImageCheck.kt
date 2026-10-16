package com.hakkiiler.voiceassistant.data.image

/** Проверка результата видеочипа: NaN в ggml обычно даёт чёрную или однотонную картинку. */
object ImageCheck {
    /** true — картинка почти однотонная (разброс яркости по выборке пикселей < 6 из 255). */
    fun looksBroken(rgb: ByteArray): Boolean {
        if (rgb.size < 3) return true
        val px = rgb.size / 3
        val step = (px / 4096).coerceAtLeast(1)
        var min = 255
        var max = 0
        var i = 0
        while (i < px) {
            val o = i * 3
            val y = ((rgb[o].toInt() and 0xFF) * 3 + (rgb[o + 1].toInt() and 0xFF) * 6 + (rgb[o + 2].toInt() and 0xFF)) / 10
            if (y < min) min = y
            if (y > max) max = y
            i += step
        }
        return max - min < 6
    }
}
