package com.hakkiiler.voiceassistant.data.ai

import android.os.StatFs
import com.hakkiiler.voiceassistant.data.image.ImageModelFile
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
 * Загрузка больших моделей так же, как у рисования: .part + докачка через HTTP Range,
 * до 4 попыток, SHA-256 на лету, проверка свободного места, атомарное переименование.
 */
class ResumableDownloader(val dir: File) {

    fun file(f: ImageModelFile) = File(dir, f.fileName)
    private fun part(f: ImageModelFile) = File(dir, f.fileName + ".part")

    fun isInstalled(f: ImageModelFile) = file(f).let { it.isFile && it.length() == f.bytes }

    fun progressOf(f: ImageModelFile) = when {
        isInstalled(f) -> f.bytes
        part(f).isFile -> minOf(part(f).length(), f.bytes)
        else -> 0L
    }

    fun delete(f: ImageModelFile) {
        file(f).delete()
        part(f).delete()
    }

    suspend fun download(f: ImageModelFile, onProgress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        if (isInstalled(f)) { onProgress(f.bytes); return@withContext }
        val free = StatFs(dir.path).availableBytes
        val need = f.bytes - progressOf(f)
        if (free < need + RESERVE_BYTES) {
            throw IOException("Недостаточно места: нужно ещё ${mb(need + RESERVE_BYTES)} МБ, свободно ${mb(free)} МБ / Not enough storage")
        }
        var attempt = 0
        while (true) {
            try {
                fetch(f, onProgress)
                return@withContext
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChecksumException) {
                part(f).delete()
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
                instanceFollowRedirects = true
                if (done > 0) setRequestProperty("Range", "bytes=$done-")
            }
            try {
                val code = conn.responseCode
                val append = when {
                    done > 0 && code == HttpURLConnection.HTTP_PARTIAL -> true
                    code == HttpURLConnection.HTTP_OK -> {
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

    class ChecksumException(name: String) : IOException("Файл $name повреждён (не совпала контрольная сумма), скачай заново")

    private fun mb(b: Long) = b / (1024 * 1024)

    private companion object {
        const val MAX_ATTEMPTS = 4
        const val BUF = 256 * 1024
        const val RESERVE_BYTES = 300L * 1024 * 1024
    }
}
