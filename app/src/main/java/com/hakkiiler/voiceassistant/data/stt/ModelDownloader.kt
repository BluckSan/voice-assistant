package com.hakkiiler.voiceassistant.data.stt

import com.hakkiiler.voiceassistant.domain.model.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * Скачивает и распаковывает модель Vosk (только для сборки lite).
 * Архив → cacheDir, распаковка во временную папку, затем атомарное переименование в
 * filesDir/models/model-xx и файл uuid (по нему проверяем, что модель распакована целиком).
 */
class ModelDownloader(private val filesDir: File, private val cacheDir: File) {

    fun modelDir(lang: Language) = File(File(filesDir, "models"), ModelSource.dirName(lang))

    fun isInstalled(lang: Language) = File(modelDir(lang), "uuid").isFile

    /**
     * @param onProgress (скачано байт, всего байт) — вызывается из IO-потока
     */
    suspend fun download(lang: Language, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        if (isInstalled(lang)) return@withContext
        val zip = File(cacheDir, "${ModelSource.name(lang)}.zip.part")
        try {
            fetch(ModelSource.url(lang), zip, ModelSource.approxBytes(lang), onProgress)
            unpack(zip, lang)
        } finally {
            zip.delete()
        }
    }

    private suspend fun fetch(url: String, target: File, approx: Long, onProgress: (Long, Long) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: approx
            var done = 0L
            var lastReport = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 256 * 1024) { lastReport = done; onProgress(done, total) }
                    }
                }
            }
            if (conn.contentLengthLong > 0 && done != conn.contentLengthLong) throw IOException("Загрузка оборвалась")
            onProgress(done, total)
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun unpack(zip: File, lang: Language) {
        val dest = modelDir(lang)
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val root = tmp.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                coroutineContext.ensureActive()
                val e = zin.nextEntry ?: break
                // в архиве всё лежит в папке vosk-model-small-xx/… — срезаем её
                val rel = e.name.substringAfter('/', "")
                if (rel.isEmpty()) continue
                val out = File(tmp, rel)
                if (!out.canonicalPath.startsWith(root)) throw IOException("Bad zip entry: ${e.name}") // zip slip
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                }
            }
        }
        if (!File(tmp, "am/final.mdl").isFile) throw IOException("В архиве нет модели")
        File(tmp, "uuid").writeText(ModelSource.name(lang))
        dest.deleteRecursively()
        if (!tmp.renameTo(dest)) throw IOException("Не удалось сохранить модель")
    }

    fun delete(lang: Language) = modelDir(lang).deleteRecursively()
}
