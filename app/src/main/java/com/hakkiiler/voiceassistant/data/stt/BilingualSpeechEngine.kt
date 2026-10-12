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
import kotlinx.coroutines.flow.MutableStateFlow
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
        /** @param audio PCM 16 кГц всей фразы — для повторного распознавания Whisper (смешанная речь). */
        fun onUtterance(hypotheses: List<Hypothesis>, audio: ShortArray)
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
            val audio = java.io.ByteArrayOutputStream()
            while (running) {
                val n = recorder.read(buf, 0, buf.size)
                if (n <= 0) continue
                if (muted) { wasMuted = true; level.value = 0f; continue }
                level.value = rmsLevel(buf, n)
                if (wasMuted) {
                    recognizers.values.forEach { it.reset() }
                    audio.reset()
                    wasMuted = false
                }
                audio.write(buf, 0, n)
                if (audio.size() > MAX_AUDIO_BYTES) {
                    // долгая тишина/шум без конца фразы — оставляем последние секунды
                    val tail = audio.toByteArray().let { it.copyOfRange(it.size - KEEP_AUDIO_BYTES, it.size) }
                    audio.reset(); audio.write(tail)
                }
                val ended = recognizers.filter { (_, r) -> r.acceptWaveForm(buf, n) }.keys
                if (ended.isNotEmpty()) {
                    val hyps = recognizers.map { (lang, r) ->
                        parse(lang, if (lang in ended) r.result else r.finalResult)
                    }
                    lastPartial = ""
                    val pcm = toShorts(audio.toByteArray())
                    audio.reset()
                    if (hyps.any { !it.isBlank }) main.post { listener.onUtterance(hyps, pcm) }
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
            level.value = 0f
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            recognizers.values.forEach { runCatching { it.close() } }
        }
    }

    companion object {
        private const val TAG = "BilingualSTT"

        /** Громкость с микрофона 0..1 — для анимации «волны» на экране (обновляется ~8 раз в секунду). */
        val level = MutableStateFlow(0f)

        /** RMS 16-бит PCM → 0..1 по логарифмической шкале (−60…0 дБ). */
        fun rmsLevel(buf: ByteArray, n: Int): Float {
            var sum = 0.0
            val samples = n / 2
            if (samples == 0) return 0f
            for (i in 0 until samples) {
                val v = ((buf[2 * i + 1].toInt() shl 8) or (buf[2 * i].toInt() and 0xFF)).toShort().toDouble()
                sum += v * v
            }
            val rms = kotlin.math.sqrt(sum / samples) / 32768.0
            if (rms <= 1e-6) return 0f
            val db = 20 * kotlin.math.log10(rms)
            return ((db + 60) / 60).toFloat().coerceIn(0f, 1f)
        }
        const val SAMPLE_RATE = 16000
        /** Не больше 30 с звука на фразу (Whisper всё равно берёт окно 30 с). */
        private const val MAX_AUDIO_BYTES = SAMPLE_RATE * 2 * 30
        private const val KEEP_AUDIO_BYTES = SAMPLE_RATE * 2 * 10

        fun toShorts(b: ByteArray): ShortArray = ShortArray(b.size / 2) { i ->
            ((b[2 * i + 1].toInt() shl 8) or (b[2 * i].toInt() and 0xFF)).toShort()
        }
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
