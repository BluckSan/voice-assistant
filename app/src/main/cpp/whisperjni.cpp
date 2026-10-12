// JNI-обёртка над whisper.cpp для com.hakkiiler.voiceassistant.data.stt.WhisperNative.
// Whisper умеет смешанную речь: «открой YouTube и включи music» распознаётся одной фразой.
#include <jni.h>
#include <android/log.h>
#include <string>
#include "whisper.h"

#define FN(name) Java_com_hakkiiler_voiceassistant_data_stt_WhisperNative_##name

extern "C" JNIEXPORT jlong JNICALL FN(nativeOpen)(JNIEnv* env, jobject, jstring jpath) {
    const char* p = env->GetStringUTFChars(jpath, nullptr);
    auto cp = whisper_context_default_params();
    cp.use_gpu = false;
    cp.flash_attn = true;
    whisper_context* ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(jpath, p);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL FN(nativeClose)(JNIEnv*, jobject, jlong h) {
    if (h) whisper_free(reinterpret_cast<whisper_context*>(h));
}

/**
 * @param lang "ru", "en" или "auto"
 * @param prompt подсказка-контекст (смешанные слова), может быть пустой
 * @return UTF-8: "<код языка>\n<текст>"
 */
extern "C" JNIEXPORT jbyteArray JNICALL FN(nativeTranscribe)(JNIEnv* env, jobject, jlong h, jfloatArray jpcm,
                                                             jstring jlang, jstring jprompt, jint threads) {
    auto* ctx = reinterpret_cast<whisper_context*>(h);
    if (!ctx) return nullptr;
    const char* lang = env->GetStringUTFChars(jlang, nullptr);
    const char* prompt = env->GetStringUTFChars(jprompt, nullptr);
    std::string lang_s(lang), prompt_s(prompt);
    env->ReleaseStringUTFChars(jlang, lang);
    env->ReleaseStringUTFChars(jprompt, prompt);

    jsize n = env->GetArrayLength(jpcm);
    jfloat* pcm = env->GetFloatArrayElements(jpcm, nullptr);

    auto wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.n_threads = threads;
    wp.language = lang_s.c_str();
    wp.detect_language = false;
    wp.translate = false;
    wp.no_context = true;
    wp.no_timestamps = true;
    wp.single_segment = false;
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_special = false;
    wp.print_timestamps = false;
    wp.suppress_nst = true;
    wp.temperature_inc = 0.0f;  // без повторов с температурой: быстрее и предсказуемее
    wp.initial_prompt = prompt_s.empty() ? nullptr : prompt_s.c_str();

    int rc = whisper_full(ctx, wp, pcm, n);
    env->ReleaseFloatArrayElements(jpcm, pcm, JNI_ABORT);
    if (rc != 0) return nullptr;

    std::string out = whisper_lang_str(whisper_full_lang_id(ctx));
    out += "\n";
    for (int i = 0; i < whisper_full_n_segments(ctx); i++) out += whisper_full_get_segment_text(ctx, i);
    jbyteArray a = env->NewByteArray(out.size());
    env->SetByteArrayRegion(a, 0, out.size(), reinterpret_cast<const jbyte*>(out.data()));
    return a;
}
