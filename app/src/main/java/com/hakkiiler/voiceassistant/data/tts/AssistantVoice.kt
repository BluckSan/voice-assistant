package com.hakkiiler.voiceassistant.data.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.hakkiiler.voiceassistant.domain.model.Language
import com.hakkiiler.voiceassistant.domain.model.VoiceGender
import java.util.UUID

/**
 * Озвучка через системный Android TextToSpeech на языке, на котором говорил пользователь.
 * (Неиспользуемые модели Piper/ONNX из оригинального APK удалены — они весили ~140 МБ.)
 */
class AssistantVoice(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    @Volatile private var ready = false
    private val pending = mutableListOf<Triple<String, Language, () -> Unit>>()
    private val callbacks = mutableMapOf<String, () -> Unit>()
    var gender: VoiceGender = VoiceGender.FEMALE

    /** Есть ли в системе голос для языка (если нет — подскажем установить в настройках TTS). */
    val availableLanguages = mutableSetOf<Language>()

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) { Log.e(TAG, "TTS init failed: $status"); return }
        for (l in Language.entries) {
            if (tts.isLanguageAvailable(l.locale) >= TextToSpeech.LANG_AVAILABLE) availableLanguages += l
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finish(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
        })
        ready = true
        synchronized(pending) {
            pending.forEach { (t, l, cb) -> speak(t, l, cb) }
            pending.clear()
        }
    }

    private fun finish(id: String?) {
        val cb = synchronized(callbacks) { callbacks.remove(id) }
        cb?.invoke()
    }

    fun speak(text: String, lang: Language, onDone: () -> Unit = {}) {
        if (!ready) { synchronized(pending) { pending += Triple(text, lang, onDone) }; return }
        tts.language = lang.locale
        pickVoice(lang)?.let { tts.voice = it }
        val id = UUID.randomUUID().toString()
        synchronized(callbacks) { callbacks[id] = onDone }
        val res = tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
        if (res != TextToSpeech.SUCCESS) finish(id)
    }

    /** Голоса Google TTS называются вроде "ru-ru-x-dfc#female_1-local" — ищем нужный пол по имени. */
    private fun pickVoice(lang: Language): Voice? = runCatching {
        val voices = tts.voices?.filter {
            it.locale.language == lang.locale.language && !it.isNetworkConnectionRequired
        }.orEmpty()
        val tag = if (gender == VoiceGender.MALE) "male" else "female"
        voices.firstOrNull { v -> v.name.contains("#$tag") || (tag == "male" && v.name.contains("male") && !v.name.contains("female")) || (tag == "female" && v.name.contains("female")) }
            ?: voices.firstOrNull()
    }.getOrNull()

    fun stop() = tts.stop()

    fun shutdown() {
        tts.stop(); tts.shutdown()
    }

    companion object { private const val TAG = "AssistantVoice" }
}
