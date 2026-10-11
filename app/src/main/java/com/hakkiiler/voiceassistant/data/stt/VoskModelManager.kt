package com.hakkiiler.voiceassistant.data.stt

import android.content.Context
import com.hakkiiler.voiceassistant.BuildConfig
import com.hakkiiler.voiceassistant.domain.model.Language
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.vosk.Model
import org.vosk.android.StorageService
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Загружает офлайн-модели Vosk.
 * full: модели лежат в assets; при первом запуске StorageService копирует их во внешнюю папку приложения.
 * lite: модели скачиваются [ModelDownloader] в filesDir/models и открываются оттуда.
 */
class VoskModelManager(private val context: Context) {
    private val models = mutableMapOf<Language, Model>()
    private val mutex = Mutex()
    val downloader = ModelDownloader(context.filesDir, context.cacheDir)
    val bundled: Boolean = BuildConfig.BUNDLED_MODELS

    private fun isInAssets(lang: Language): Boolean =
        context.assets.list(ModelSource.dirName(lang))?.contains("uuid") == true

    /** Модель готова к работе (в APK или уже скачана). */
    fun isAvailable(lang: Language): Boolean =
        if (bundled) isInAssets(lang) else downloader.isInstalled(lang)

    suspend fun load(lang: Language): Model = mutex.withLock {
        models[lang]?.let { return it }
        val model = if (bundled) {
            if (!isInAssets(lang)) throw IOException("Модель ${ModelSource.dirName(lang)} не найдена в assets — запусти scripts/download_models.sh")
            suspendCancellableCoroutine { cont ->
                StorageService.unpack(
                    context, ModelSource.dirName(lang), ModelSource.dirName(lang),
                    { m -> cont.resume(m) },
                    { e -> cont.resumeWithException(e) },
                )
            }
        } else {
            if (!downloader.isInstalled(lang)) {
                throw IOException(
                    if (lang == Language.RU) "Русская модель не скачана — скачай её в Настройках"
                    else "English model is not downloaded — get it in Settings",
                )
            }
            Model(downloader.modelDir(lang).absolutePath)
        }
        models[lang] = model
        model
    }

    suspend fun loadAll(langs: Collection<Language>): Map<Language, Model> = langs.associateWith { load(it) }

    fun close() {
        models.values.forEach { runCatching { it.close() } }
        models.clear()
    }
}
