// JNI-обёртка над stable-diffusion.cpp для com.hakkiiler.voiceassistant.data.image.StableDiffusionNative
#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>

#include "stable-diffusion.h"

#define TAG "sdjni"

namespace {

JavaVM* g_vm = nullptr;
std::mutex g_err_mutex;
std::string g_last_error;

// Текущий слушатель прогресса (глобальный в sd.cpp, у нас всё равно одна генерация за раз).
jobject g_listener = nullptr;
jmethodID g_on_progress = nullptr;
std::mutex g_listener_mutex;

void log_cb(enum sd_log_level_t level, const char* text, void*) {
    int prio = ANDROID_LOG_DEBUG;
    switch (level) {
        case SD_LOG_DEBUG: return;  // слишком много шума
        case SD_LOG_VERBOSE: return;
        case SD_LOG_INFO: prio = ANDROID_LOG_INFO; break;
        case SD_LOG_WARN: prio = ANDROID_LOG_WARN; break;
        case SD_LOG_ERROR: prio = ANDROID_LOG_ERROR; break;
    }
    __android_log_write(prio, TAG, text);
    if (level == SD_LOG_ERROR) {
        std::lock_guard<std::mutex> lock(g_err_mutex);
        g_last_error = text;
        while (!g_last_error.empty() && (g_last_error.back() == '\n' || g_last_error.back() == ' ')) g_last_error.pop_back();
    }
}

void progress_cb(int step, int steps, float time, void*) {
    std::lock_guard<std::mutex> lock(g_listener_mutex);
    if (!g_listener || !g_vm) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
#ifdef __ANDROID__
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
#else  // хост-сборка для проверки обёртки на x86
        if (g_vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) return;
#endif
        attached = true;
    }
    env->CallVoidMethod(g_listener, g_on_progress, step, steps, time);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) g_vm->DetachCurrentThread();
}

std::string to_string(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

void clear_error() {
    std::lock_guard<std::mutex> lock(g_err_mutex);
    g_last_error.clear();
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    sd_set_log_callback(log_cb, nullptr);
    sd_set_progress_callback(progress_cb, nullptr);
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_hakkiiler_voiceassistant_data_image_StableDiffusionNative_nativeSystemInfo(JNIEnv* env, jobject) {
    std::string info = std::string("sd.cpp ") + sd_commit() + " | " + sd_get_system_info();
    return env->NewStringUTF(info.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_hakkiiler_voiceassistant_data_image_StableDiffusionNative_nativeLastError(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_err_mutex);
    return env->NewStringUTF(g_last_error.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_hakkiiler_voiceassistant_data_image_StableDiffusionNative_nativeCreate(
        JNIEnv* env, jobject, jstring model_path, jstring taesd_path, jint threads, jboolean mmap) {
    clear_error();
    std::string model = to_string(env, model_path);
    std::string taesd = to_string(env, taesd_path);

    sd_ctx_params_t p;
    sd_ctx_params_init(&p);
    p.model_path = model.c_str();
    p.taesd_path = taesd.empty() ? nullptr : taesd.c_str();
    p.n_threads = threads;
    p.enable_mmap = mmap;
    p.backend = "cpu";
    p.vae_conv_direct = false;
    p.eager_load = true;  // всё грузим здесь, а не на первом шаге: прогресс и ошибки памяти предсказуемее
    sd_ctx_t* ctx = new_sd_ctx(&p);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_hakkiiler_voiceassistant_data_image_StableDiffusionNative_nativeFree(JNIEnv*, jobject, jlong ctx) {
    if (ctx) free_sd_ctx(reinterpret_cast<sd_ctx_t*>(ctx));
}

extern "C" JNIEXPORT void JNICALL
Java_com_hakkiiler_voiceassistant_data_image_StableDiffusionNative_nativeCancel(JNIEnv*, jobject, jlong ctx) {
    if (ctx) sd_cancel_generation(reinterpret_cast<sd_ctx_t*>(ctx), SD_CANCEL_ALL);
}

/** @return RGB-байты width*height*3 или null при ошибке/отмене. */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_hakkiiler_voiceassistant_data_image_StableDiffusionNative_nativeGenerate(
        JNIEnv* env, jobject, jlong handle, jstring jprompt, jstring jnegative,
        jint width, jint height, jint steps, jfloat cfg, jlong seed, jobject listener) {
    auto* ctx = reinterpret_cast<sd_ctx_t*>(handle);
    if (!ctx) return nullptr;
    clear_error();
    sd_cancel_generation(ctx, SD_CANCEL_RESET);

    std::string prompt = to_string(env, jprompt);
    std::string negative = to_string(env, jnegative);

    {
        std::lock_guard<std::mutex> lock(g_listener_mutex);
        if (listener) {
            g_listener = env->NewGlobalRef(listener);
            jclass cls = env->GetObjectClass(listener);
            g_on_progress = env->GetMethodID(cls, "onProgress", "(IIF)V");
            env->DeleteLocalRef(cls);
        }
    }

    sd_img_gen_params_t gp;
    sd_img_gen_params_init(&gp);
    gp.prompt = prompt.c_str();
    gp.negative_prompt = negative.c_str();
    gp.width = width;
    gp.height = height;
    gp.seed = seed;
    gp.batch_count = 1;
    gp.sample_params.sample_steps = steps;
    gp.sample_params.guidance.txt_cfg = cfg;
    // метод сэмплирования и расписание sd.cpp выбирает сам (для SD-Turbo: Euler A + discrete)

    sd_image_t* images = nullptr;
    int count = 0;
    bool ok = generate_image(ctx, &gp, &images, &count);

    {
        std::lock_guard<std::mutex> lock(g_listener_mutex);
        if (g_listener) {
            env->DeleteGlobalRef(g_listener);
            g_listener = nullptr;
        }
    }

    jbyteArray result = nullptr;
    if (ok && images && count > 0 && images[0].data) {
        const sd_image_t& img = images[0];
        const jsize channels = static_cast<jsize>(img.channel);
        const jsize pixels = static_cast<jsize>(img.width * img.height);
        result = env->NewByteArray(pixels * 3);  // при нехватке памяти вернёт null + OutOfMemoryError
        if (result) {
            if (channels == 3) {
                env->SetByteArrayRegion(result, 0, pixels * 3, reinterpret_cast<const jbyte*>(img.data));
            } else {
                // на всякий случай приводим к RGB
                auto* tmp = static_cast<jbyte*>(malloc(static_cast<size_t>(pixels) * 3));
                if (tmp) {
                    for (jsize i = 0; i < pixels; i++)
                        for (int c = 0; c < 3; c++)
                            tmp[i * 3 + c] = static_cast<jbyte>(img.data[i * channels + (c < channels ? c : 0)]);
                    env->SetByteArrayRegion(result, 0, pixels * 3, tmp);
                    free(tmp);
                }
            }
        }
    }
    if (images) free_sd_images(images, count);
    return result;
}
