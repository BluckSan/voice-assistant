package com.hakkiiler.voiceassistant.data.image

import android.os.StatFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Скачивает модели рисования (~1.9 ГБ) по тому же принципу, что и [com.hakkiiler.voiceassistant.data.stt.ModelDownloader]:
 * HttpURLConnection, файл .part, проверка отмены на каждом блоке, прогресс в байтах, атомарное переименование.
 * Дополнительно, потому что файлы большие:
 * - докачка с места обрыва (HTTP Range), .part лежит в filesDir и переживает перезапуск;
 * - до 4 автоматических попыток с паузой при сетевых ошибках;
 * - проверка SHA-256 (считается на лету) и свободного места до начала загрузки.
 *
 * После загрузки LCM-LoRA вливается в модель ([DrawController] вызывает nativeMergeLora),
 * и в папке остаются только [ImageModelSource.MERGED_FILE] + TAESD (+ апскейлер, если скачан).
 */
class ImageModelDownloader(private val filesDir: File) {

    val dir: File get() = File(filesDir, "image-models")

    fun file(f: ImageModelFile) = File(dir, f.fileName)
    private fun part(f: ImageModelFile) = File(dir, f.fileName + ".part")
    val mergedFile: File get() = File(dir, ImageModelSource.MERGED_FILE)
    val mergeTmp: File get() = File(dir, ImageModelSource.MERGED_FILE + ".tmp")

    fun isInstalled(f: ImageModelFile) = file(f).let { it.isFile && it.length() == f.bytes }
    fun isMerged() = mergedFile.isFile && mergedFile.length() > ImageModelSource.MERGED_BYTES / 2
    /** Готово к рисованию: модель с влитой LCM-LoRA и TAESD на месте. */
    fun allInstalled() = isMerged() && isInstalled(ImageModelSource.TAESD)
    fun upscalerInstalled() = isInstalled(ImageModelSource.UPSCALER)
    /** Скачаны исходники, но слияние ещё не сделано (например, приложение закрыли посередине). */
    fun needsMerge() = !isMerged() && isInstalled(ImageModelSource.BASE) && isInstalled(ImageModelSource.LCM_LORA)

    private fun pending(): List<ImageModelFile> = buildList {
        if (!isMerged()) { add(ImageModelSource.BASE); add(ImageModelSource.LCM_LORA) }
        add(ImageModelSource.TAESD)
    }

    /** Уже скачанные байты (включая недокачанные .part) — для прогресса и «Продолжить». */
    fun downloadedBytes(): Long = if (isMerged()) {
        ImageModelSource.requiredBytes - ImageModelSource.TAESD.bytes + progressOf(ImageModelSource.TAESD)
    } else {
        ImageModelSource.required.sumOf { progressOf(it) }
    }

    private fun progressOf(f: ImageModelFile) = when {
        isInstalled(f) -> f.bytes
        part(f).isFile -> minOf(part(f).length(), f.bytes)
        else -> 0L
    }

    /** Файлы прошлых версий (старая модель из версии 1.0) — удаляем, чтобы не занимали 2 ГБ. */
    fun removeStale() {
        val known = (ImageModelSource.required + ImageModelSource.UPSCALER).flatMap { listOf(it.fileName, it.fileName + ".part") }
            .toSet() + ImageModelSource.MERGED_FILE
        dir.listFiles()?.forEach { if (it.name !in known) it.deleteRecursively() }
    }

    /**
     * @param onProgress (скачано байт по всем файлам, всего байт) — из IO-потока
     */
    suspend fun downloadAll(onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        removeStale()
        val files = pending()
        val need = files.filterNot { isInstalled(it) }.sumOf { it.bytes - (part(it).takeIf { p -> p.isFile }?.length() ?: 0L) } +
            (if (isMerged()) 0L else ImageModelSource.MERGED_BYTES) // слияние пишет ещё одну копию модели
        ensureSpace(need)
        val total = ImageModelSource.requiredBytes
        var doneBefore = total - files.sumOf { it.bytes }
        for (f in files) {
            if (!isInstalled(f)) {
                val base = doneBefore
                downloadWithRetry(f) { done -> onProgress(base + done, total) }
            }
            doneBefore += f.bytes
            onProgress(doneBefore, total)
        }
    }

    /** Апскейлер RealESRGAN (64 МБ) — по желанию, отдельной кнопкой. */
    suspend fun downloadUpscaler(onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val f = ImageModelSource.UPSCALER
        if (isInstalled(f)) return@withContext
        ensureSpace(f.bytes)
        downloadWithRetry(f) { done -> onProgress(done, f.bytes) }
        onProgress(f.bytes, f.bytes)
    }

    private fun ensureSpace(need: Long) {
        val free = StatFs(dir.path).availableBytes
        if (free < need + RESERVE_BYTES) {
            throw IOException(
                "Недостаточно места: нужно ещё ${mb(need + RESERVE_BYTES)} МБ, свободно ${mb(free)} МБ / Not enough storage",
            )
        }
    }

    /** После успешного слияния исходники больше не нужны. */
    fun finishMerge(): Boolean {
        if (!mergeTmp.isFile || mergeTmp.length() < ImageModelSource.MERGED_BYTES / 2) return false
        if (!mergeTmp.renameTo(mergedFile)) return false
        file(ImageModelSource.BASE).delete()
        file(ImageModelSource.LCM_LORA).delete()
        return true
    }

    private suspend fun downloadWithRetry(f: ImageModelFile, onProgress: (Long) -> Unit) {
        var attempt = 0
        while (true) {
            try {
                fetch(f, onProgress)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChecksumException) {
                part(f).delete() // битый файл — докачивать нельзя, только заново
                throw e
            } catch (e: IOException) {
                attempt++
                if (attempt >= MAX_ATTEMPTS) throw e
                delay(2_000L * attempt)
            }
        }
    }

    private suspend fun fetch(f: ImageModelFile, onProgress: (Long) -> Unit) {
        val target = part(f)
        val digest = MessageDigest.getInstance("SHA-256")
        var done = if (target.isFile) target.length() else 0L
        if (done > f.bytes) { target.delete(); done = 0 }
        // при докачке хэш уже скачанной части пересчитываем с диска
        if (done > 0) {
            target.inputStream().buffered(BUF).use { input ->
                val buf = ByteArray(BUF)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
        }

        if (done < f.bytes) {
            val conn = (URL(f.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true // HF отдаёт 302 на CDN
                if (done > 0) setRequestProperty("Range", "bytes=$done-")
            }
            try {
                val code = conn.responseCode
                val append = when {
                    done > 0 && code == HttpURLConnection.HTTP_PARTIAL -> true
                    code == HttpURLConnection.HTTP_OK -> {
                        // сервер не умеет Range — начинаем сначала
                        if (done > 0) { digest.reset(); done = 0 }
                        false
                    }
                    code == 416 -> { target.delete(); throw IOException("HTTP 416") }
                    else -> throw IOException("HTTP $code")
                }
                var lastReport = 0L
                conn.inputStream.use { input ->
                    FileOutputStream(target, append).use { out ->
                        val buf = ByteArray(BUF)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            if (done - lastReport > 1024 * 1024) { lastReport = done; onProgress(done) }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
        onProgress(done)
        if (done != f.bytes) throw IOException("Загрузка оборвалась на ${mb(done)} из ${mb(f.bytes)} МБ")
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (!sha.equals(f.sha256, ignoreCase = true)) throw ChecksumException(f.fileName)
        if (!target.renameTo(file(f))) throw IOException("Не удалось сохранить ${f.fileName}")
    }

    fun deleteAll() {
        dir.deleteRecursively()
    }

    fun deleteUpscaler() {
        file(ImageModelSource.UPSCALER).delete()
        part(ImageModelSource.UPSCALER).delete()
    }

    class ChecksumException(name: String) : IOException("Файл $name повреждён (не совпала контрольная сумма), скачай заново")

    private fun mb(b: Long) = b / (1024 * 1024)

    private companion object {
        const val MAX_ATTEMPTS = 4
        const val BUF = 256 * 1024
        const val RESERVE_BYTES = 300L * 1024 * 1024
    }
}
