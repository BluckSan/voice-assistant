package com.hakkiiler.voiceassistant.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Подготовка фото для img2img: поворот по EXIF, подбор размера модели (стороны кратны 64, 512–768)
 * с сохранением пропорций и обрезка по центру.
 */
object InitImage {
    const val MIN_SIDE = 512
    const val MAX_SIDE = 768

    /** Размер для модели под пропорции фото: длинная сторона 768, короткая — ближайшее кратное 64 в 512..768. */
    fun targetSize(w: Int, h: Int): DrawSize {
        require(w > 0 && h > 0)
        val landscape = w >= h
        val ratio = minOf(w, h).toDouble() / maxOf(w, h)
        val candidates = (MIN_SIDE..MAX_SIDE step 64)
        val short = candidates.minBy { abs(it.toDouble() / MAX_SIDE - ratio) }
        return if (landscape) DrawSize(MAX_SIDE, short) else DrawSize(short, MAX_SIDE)
    }

    /** Центральная область исходника с пропорциями target. */
    fun cropRect(w: Int, h: Int, target: DrawSize): Rect {
        val tr = target.width.toDouble() / target.height
        return if (w.toDouble() / h > tr) {
            val cw = (h * tr).roundToInt().coerceAtMost(w)
            val x = (w - cw) / 2
            Rect(x, 0, x + cw, h)
        } else {
            val ch = (w / tr).roundToInt().coerceAtMost(h)
            val y = (h - ch) / 2
            Rect(0, y, w, y + ch)
        }
    }

    class Loaded(val bitmap: Bitmap, val info: SourceInfo)

    suspend fun load(context: Context, uri: Uri): Loaded = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        var name = uri.lastPathSegment ?: "photo"
        var bytes = 0L
        runCatching {
            cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) bytes = c.getLong(1)
                }
            }
        }.onFailure { PickLog.w(context, "query $uri", it) }
        PickLog.i(context, "load $uri name=$name bytes=$bytes type=${cr.getType(uri)}")

        // 1) Android 9+: ImageDecoder — сам поворачивает по EXIF, понимает HEIC/HEIF, сразу уменьшает (без OOM на 50 Мп)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val r = runCatching {
                var ow = 0
                var oh = 0
                val src = android.graphics.ImageDecoder.createSource(cr, uri)
                val bmp = android.graphics.ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                    ow = info.size.width
                    oh = info.size.height
                    val scale = MAX_SIDE * 1.5 / minOf(ow, oh)
                    if (scale < 1) decoder.setTargetSize((ow * scale).roundToInt().coerceAtLeast(1), (oh * scale).roundToInt().coerceAtLeast(1))
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE // аппаратный bitmap нельзя читать по пикселям
                    decoder.isMutableRequired = false
                }
                Loaded(prepare(bmp), SourceInfo(name, ow, oh, bytes))
            }
            r.onSuccess { PickLog.i(context, "ImageDecoder ok ${it.info.width}x${it.info.height}") }
                .onFailure { PickLog.w(context, "ImageDecoder failed, fallback to BitmapFactory", it) }
            r.getOrNull()?.let { return@withContext it }
        }

        // 2) копия во временный файл (некоторые провайдеры отдают поток без перемотки/с ошибками) + BitmapFactory + EXIF
        val tmp = java.io.File(context.cacheDir, "pick-source.tmp")
        (cr.openInputStream(uri) ?: error("Нет доступа к файлу (openInputStream вернул null)")).use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        PickLog.i(context, "copied to cache: ${tmp.length()} bytes")
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            runCatching {
                var ow = 0
                var oh = 0
                val bmp = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(tmp)) { d, info, _ ->
                    ow = info.size.width; oh = info.size.height
                    val scale = MAX_SIDE * 1.5 / minOf(ow, oh)
                    if (scale < 1) d.setTargetSize((ow * scale).roundToInt().coerceAtLeast(1), (oh * scale).roundToInt().coerceAtLeast(1))
                    d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
                return@withContext Loaded(prepare(bmp), SourceInfo(name, ow, oh, bytes))
            }.onFailure { PickLog.w(context, "ImageDecoder(file) failed", it) }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // при inJustDecodeBounds decodeStream ВСЕГДА возвращает null — размер берём из bounds (ошибка 1.3.0/1.3.1)
        BitmapFactory.decodeFile(tmp.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Формат не поддерживается (${bounds.outMimeType ?: cr.getType(uri)})" }
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val raw = BitmapFactory.decodeFile(tmp.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: error("Не удалось декодировать фото (${bounds.outMimeType})")
        val orientation = runCatching {
            ExifInterface(tmp.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        tmp.delete()
        val rotated = rotate(raw, orientation)
        val swap = orientation in setOf(ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE)
        val ow = if (swap) bounds.outHeight else bounds.outWidth
        val oh = if (swap) bounds.outWidth else bounds.outHeight
        PickLog.i(context, "BitmapFactory ok ${ow}x$oh sample=$sample orientation=$orientation")
        Loaded(prepare(rotated), SourceInfo(name, ow, oh, bytes))
    }

    private fun rotate(b: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return b
        }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    /** Обрезка по центру и масштаб ровно в размер модели. */
    fun prepare(b: Bitmap): Bitmap {
        val target = targetSize(b.width, b.height)
        val src = cropRect(b.width, b.height, target)
        val out = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(b, src, Rect(0, 0, target.width, target.height), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    fun toRgb(b: Bitmap): ByteArray {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        val out = ByteArray(px.size * 3)
        for (i in px.indices) {
            out[i * 3] = (px[i] shr 16).toByte()
            out[i * 3 + 1] = (px[i] shr 8).toByte()
            out[i * 3 + 2] = px[i].toByte()
        }
        return out
    }

    /** LCM + img2img: шагов реально выполняется ≈ steps × strength, поэтому добавляем, чтобы качество не падало. */
    fun stepsFor(steps: Int, strength: Float): Int = (steps / strength.coerceIn(0.2f, 1f)).roundToInt().coerceIn(steps, 16)
}
