package com.hakkiiler.voiceassistant.data.image

import kotlin.math.pow
import kotlin.math.roundToInt

/** Размер генерации. Только чистая логика — покрыта юнит-тестами. */
data class DrawSize(val width: Int, val height: Int) {
    val label get() = "$width×$height"
    val orientation get() = when {
        width == height -> Orientation.SQUARE
        height > width -> Orientation.PORTRAIT
        else -> Orientation.LANDSCAPE
    }
    val megapixels get() = width.toDouble() * height / 1_000_000.0
    /** Площадь относительно 512×512. */
    val area get() = width.toDouble() * height / (512.0 * 512.0)

    enum class Orientation { SQUARE, PORTRAIT, LANDSCAPE }
}

/** Насколько тяжело для телефона с 8 ГБ ОЗУ. */
enum class Load { LIGHT, NORMAL, HEAVY, EXTREME }

object DrawPresets {
    val SIZES = listOf(
        DrawSize(384, 384),
        DrawSize(512, 512),
        DrawSize(512, 768),
        DrawSize(768, 512),
        DrawSize(768, 768),
        DrawSize(640, 960),
        DrawSize(960, 640),
        DrawSize(768, 1152),
        DrawSize(1152, 768),
    )
    val DEFAULT_SIZE = DrawSize(512, 768)
    val STEPS = 2..10
    const val DEFAULT_STEPS = 6
    const val DEFAULT_CFG = 1.0f
    const val UPSCALE = 2

    /** Работает только при CFG > 1 (иначе sd.cpp его не использует). */
    const val DEFAULT_NEGATIVE =
        "deformed, distorted, disfigured, bad anatomy, extra limbs, extra fingers, mutated hands, missing legs, " +
            "cropped, blurry, cartoon, painting, lowres, watermark, text"

    private const val MB = 1024L * 1024
    private const val GB = 1024.0 * 1024 * 1024
    /** RealESRGAN x4plus на 512×512: замер на box ~260 с (6 потоков); на телефоне закладываем столько же. */
    private const val ESRGAN_SEC_512 = 260.0

    /**
     * Как считать конкретную картинку, чтобы влезть в память телефона.
     * @param flashAttn flash-attention в UNet: память почти не растёт с размером, но шаг в ~2.7 раза дольше (замер на CPU)
     * @param vaeTiling декодировать полным VAE плитками: пик памяти ниже, декод в ~2.3 раза дольше
     */
    data class Plan(val flashAttn: Boolean, val vaeTiling: Boolean, val peakBytes: Long, val seconds: IntRange)

    /*
     * Замеры sd.cpp на box (x86, 6 потоков, q8_0 с влитой LCM, CFG 1), пиковый RSS вместе с весами 1.75 ГБ:
     *   512×768 без FA: 12.3 с/шаг, 2.88 ГБ;   с FA: 33 с/шаг, 2.36 ГБ
     *   640×960 без FA: 26.6 с/шаг, 4.6 ГБ;    768×768 без FA: 4.4 ГБ
     *   768×1152 без FA: ~48 с/шаг, 7.7 ГБ;    с FA: 150 с/шаг, 2.2 ГБ
     *   полный VAE 512×768: 16 с, пик 4.3 ГБ;  плитками: 37 с, пик не выше сэмплинга; TAESD: 5 с
     * Отсюда: шаг ≈ 6.4 с · S^1.6, память без FA ≈ 2.0 + 0.45·S² ГБ, где S — площадь относительно 512×512.
     */
    private fun samplingBytes(area: Double, flash: Boolean) =
        ((if (flash) 2.15 + 0.08 * area else 2.0 + 0.45 * area * area) * GB).toLong()

    private fun decodeBytes(area: Double, tiled: Boolean) = ((if (tiled) 2.3 else 1.9 + 1.6 * area) * GB).toLong()

    /** Запас под систему и сам UI: не планируем впритык к доступной памяти. */
    private const val MARGIN = 450L * MB
    private const val DECODE_RESERVE = 1_500L * MB

    /**
     * @param availBytes сколько памяти сейчас свободно (ActivityManager.MemoryInfo.availMem);
     *  по умолчанию — типичное значение для 8 ГБ телефона с открытым приложением
     */
    fun plan(
        size: DrawSize, steps: Int, cfg: Float, fastDecoder: Boolean, upscale: Boolean, neural: Boolean,
        availBytes: Long = TYPICAL_AVAIL_8GB,
    ): Plan {
        val a = size.area
        val flash = samplingBytes(a, false) + MARGIN > availBytes
        // полный VAE без плиток — только с большим запасом: иначе система уходит в своп и «проявление» тянется минутами
        val tiled = !fastDecoder && decodeBytes(a, false) + MARGIN + DECODE_RESERVE > availBytes
        val upBytes = if (upscale) (size.width * size.height * 4L * 4 * 7) else 0L // float-буферы Lanczos / ESRGAN
        val peak = maxOf(samplingBytes(a, flash), if (fastDecoder) 0L else decodeBytes(a, tiled)) + upBytes
        // время на box → телефон: середина ×1.4, диапазон 0.7…1.5 от середины (Dimensity 8400 / SD 8 Gen, 4 потока)
        val step = 6.4 * a.pow(1.6) * (if (flash) 2.7 else 1.0) * (if (cfg > 1.01f) 2.0 else 1.0)
        val decode = when {
            fastDecoder -> 3.5 * a
            tiled -> 25.0 * a
            else -> 11.0 * a
        }
        val up = when {
            !upscale -> 0.0
            neural -> ESRGAN_SEC_512 * a
            else -> 1.0
        }
        val mid = (10.0 + step * steps + decode + up) * 1.4
        return Plan(flash, tiled, peak, (mid * 0.7).roundToInt()..(mid * 1.5).roundToInt())
    }

    /** Типично свободно на 8 ГБ телефоне, когда открыто только наше приложение. */
    const val TYPICAL_AVAIL_8GB = 4_000L * MB

    fun estimateBytes(size: DrawSize, upscale: Boolean): Long =
        plan(size, DEFAULT_STEPS, DEFAULT_CFG, fastDecoder = false, upscale = upscale, neural = false).peakBytes

    fun load(size: DrawSize): Load = when {
        size.area <= 0.6 -> Load.LIGHT
        size.area <= 1.6 -> Load.NORMAL
        size.area <= 2.4 -> Load.HEAVY
        else -> Load.EXTREME
    }

    fun estimateSeconds(size: DrawSize, steps: Int, cfg: Float, upscale: Boolean, neural: Boolean): IntRange =
        plan(size, steps, cfg, fastDecoder = false, upscale = upscale, neural = neural).seconds

    fun outputSize(size: DrawSize, upscale: Boolean) =
        if (upscale) DrawSize(size.width * UPSCALE, size.height * UPSCALE) else size

    fun formatDuration(sec: Int): String = when {
        sec < 60 -> "${sec} с"
        else -> "${(sec + 30) / 60} мин"
    }
}
