package com.hakkiiler.voiceassistant.data.stt

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hakkiiler.voiceassistant.domain.lang.Hypothesis
import com.hakkiiler.voiceassistant.domain.lang.WordConf
import com.hakkiiler.voiceassistant.domain.model.Language
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Двуязычное распознавание: один поток с микрофона подаётся сразу в несколько
 * распознавателей Vosk (RU и EN). Когда любой из них определил конец фразы,
 * у остальных забирается финальный результат, и наружу уходят все гипотезы —
 * какую выбрать, решает [com.hakkiiler.voiceassistant.domain.lang.LanguageSelector].
 */
class BilingualSpeechEngine {

    interface Listener {
        fun onPartial(text: String)
        fun onUtterance(hypotheses: List<Hypothesis>)
        fun onError(e: Throwable)
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    /** Пока ассистент говорит, звук не распознаём, чтобы он не слышал сам себя. */
    @Volatile var muted = false
    private var thread: Thread? = null

    val isRunning get() = running

    fun start(models: Map<Language, Model>, listener: Listener) {
        stop()
        running = true
        thread = Thread({ loop(models, listener) }, "vosk-bilingual").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
    }

    @SuppressLint("MissingPermission") // разрешение проверяется до запуска сервиса
    private fun loop(models: Map<Language, Model>, listener: Listener) {
        val recognizers = models.mapValues { (_, m) ->
            Recognizer(m, SAMPLE_RATE.toFloat()).apply { setWords(true) }
        }
        var recorder: AudioRecord? = null
        try {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, CHUNK * 4),
            )
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Микрофон недоступен" }
            recorder.startRecording()
            val buf = ByteArray(CHUNK)
            var wasMuted = false
            var lastPartial = ""
            while (running) {
                val n = recorder.read(buf, 0, buf.size)
                if (n <= 0) continue
                if (muted) { wasMuted = true; continue }
                if (wasMuted) {
                    recognizers.values.forEach { it.reset() }
                    wasMuted = false
                }
                val ended = recognizers.filter { (_, r) -> r.acceptWaveForm(buf, n) }.keys
                if (ended.isNotEmpty()) {
                    val hyps = recognizers.map { (lang, r) ->
                        parse(lang, if (lang in ended) r.result else r.finalResult)
                    }
                    lastPartial = ""
                    if (hyps.any { !it.isBlank }) main.post { listener.onUtterance(hyps) }
                } else {
                    // для интерфейса показываем самую длинную частичную гипотезу
                    val partial = recognizers.values
                        .map { JSONObject(it.partialResult).optString("partial") }
                        .maxByOrNull { it.length }.orEmpty()
                    if (partial != lastPartial) {
                        lastPartial = partial
                        main.post { listener.onPartial(partial) }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "recognition loop failed", e)
            running = false
            main.post { listener.onError(e) }
        } finally {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            recognizers.values.forEach { runCatching { it.close() } }
        }
    }

    companion object {
        private const val TAG = "BilingualSTT"
        const val SAMPLE_RATE = 16000
        /** 4096 байт = 2048 сэмплов ≈ 128 мс. */
        private const val CHUNK = 4096

        /** Разбор JSON-результата Vosk: {"result":[{"conf":..,"word":..}], "text":".."} */
        fun parse(lang: Language, json: String): Hypothesis {
            val o = JSONObject(json)
            val words = mutableListOf<WordConf>()
            o.optJSONArray("result")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val w = arr.getJSONObject(i)
                    words += WordConf(w.optString("word"), w.optDouble("conf", 0.5).toFloat())
                }
            }
            return Hypothesis(lang, o.optString("text"), words)
        }
    }
}
