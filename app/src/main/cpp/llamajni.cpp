// JNI-обёртка над llama.cpp для com.hakkiiler.voiceassistant.data.llm.LlamaNative.
// Ядро (LlmSession) без JNI, чтобы его можно было проверить на хосте (scripts/host_llm_test.cpp).
#include <atomic>
#include <cstring>
#include <functional>
#include <string>
#include <vector>

#include "llama.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "llamajni", __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) (fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
#endif

struct LlmSession {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
    std::string tmpl;
    std::vector<llama_token> cached;  // токены, уже лежащие в KV-кэше (для повторного использования префикса)
    std::atomic<bool> cancel{false};
    std::string error;

    static LlmSession* open(const std::string& path, int n_ctx, int threads) {
        static bool inited = false;
        if (!inited) {
            llama_log_set([](ggml_log_level lvl, const char* t, void*) {
                if (lvl >= GGML_LOG_LEVEL_WARN) LOGI("%s", t);
            }, nullptr);
            llama_backend_init();
            inited = true;
        }
        auto mp = llama_model_default_params();
        llama_model* model = llama_model_load_from_file(path.c_str(), mp);
        if (!model) return nullptr;
        auto cp = llama_context_default_params();
        cp.n_ctx = n_ctx;
        cp.n_batch = 512;
        cp.n_ubatch = 512;
        cp.n_threads = threads;
        cp.n_threads_batch = threads;
        llama_context* ctx = llama_init_from_model(model, cp);
        if (!ctx) { llama_model_free(model); return nullptr; }
        auto* s = new LlmSession();
        s->model = model;
        s->ctx = ctx;
        s->vocab = llama_model_get_vocab(model);
        const char* t = llama_model_chat_template(model, nullptr);
        s->tmpl = t ? t : "chatml";
        return s;
    }

    ~LlmSession() {
        if (ctx) llama_free(ctx);
        if (model) llama_model_free(model);
    }

    std::string format(const std::vector<std::string>& roles, const std::vector<std::string>& contents) {
        std::vector<llama_chat_message> msgs;
        for (size_t i = 0; i < roles.size(); i++) msgs.push_back({roles[i].c_str(), contents[i].c_str()});
        std::vector<char> buf(8192);
        int n = llama_chat_apply_template(tmpl.c_str(), msgs.data(), msgs.size(), true, buf.data(), buf.size());
        if (n > (int)buf.size()) {
            buf.resize(n + 1);
            n = llama_chat_apply_template(tmpl.c_str(), msgs.data(), msgs.size(), true, buf.data(), buf.size());
        }
        if (n < 0) {  // шаблон не распознан — ChatML (Qwen)
            std::string r;
            for (auto& m : msgs) r += std::string("<|im_start|>") + m.role + "\n" + m.content + "<|im_end|>\n";
            return r + "<|im_start|>assistant\n";
        }
        return std::string(buf.data(), n);
    }

    std::vector<llama_token> tokenize(const std::string& text) {
        int n = -llama_tokenize(vocab, text.c_str(), text.size(), nullptr, 0, true, true);
        std::vector<llama_token> toks(n);
        llama_tokenize(vocab, text.c_str(), text.size(), toks.data(), n, true, true);
        return toks;
    }

    /**
     * Генерация ответа. on_piece получает куски UTF-8 (могут резать символ — склеивает вызывающий),
     * возвращает false для остановки. @return полный ответ, пусто при ошибке (error заполнен).
     */
    std::string generate(const std::string& prompt, int max_tokens, float temperature,
                         const std::function<bool(const std::string&)>& on_piece) {
        cancel = false;
        error.clear();
        std::vector<llama_token> toks = tokenize(prompt);
        const int n_ctx = llama_n_ctx(ctx);
        if ((int)toks.size() + max_tokens > n_ctx) {
            error = "context overflow";
            return {};
        }
        // общий префикс с прошлым запросом не пересчитываем
        size_t keep = 0;
        while (keep < cached.size() && keep < toks.size() && cached[keep] == toks[keep]) keep++;
        if (keep == toks.size()) keep--;  // хотя бы один токен нужно прогнать, чтобы получить логиты
        llama_memory_t mem = llama_get_memory(ctx);
        if (!llama_memory_seq_rm(mem, 0, keep, -1)) { llama_memory_clear(mem, true); keep = 0; }
        cached.resize(keep);

        const int n_batch = llama_n_batch(ctx);
        for (size_t i = keep; i < toks.size(); i += n_batch) {
            if (cancel) return {};
            int n = std::min<int>(n_batch, toks.size() - i);
            if (llama_decode(ctx, llama_batch_get_one(toks.data() + i, n)) != 0) {
                error = "decode failed";
                llama_memory_clear(mem, true);
                cached.clear();
                return {};
            }
            cached.insert(cached.end(), toks.begin() + i, toks.begin() + i + n);
        }

        auto sp = llama_sampler_chain_default_params();
        llama_sampler* smpl = llama_sampler_chain_init(sp);
        if (temperature <= 0.01f) {
            llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
        } else {
            llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
            llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.1f, 0.0f, 0.0f));
            llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
            llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
            llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        }

        std::string out;
        char piece[256];
        for (int i = 0; i < max_tokens && !cancel; i++) {
            llama_token t = llama_sampler_sample(smpl, ctx, -1);
            if (llama_vocab_is_eog(vocab, t)) break;
            int n = llama_token_to_piece(vocab, t, piece, sizeof(piece), 0, false);
            if (n > 0) {
                std::string p(piece, n);
                out += p;
                if (on_piece && !on_piece(p)) break;
            }
            if (llama_decode(ctx, llama_batch_get_one(&t, 1)) != 0) break;
            cached.push_back(t);
        }
        llama_sampler_free(smpl);
        return out;
    }
};

#ifndef LLAMA_HOST_TEST
#include <jni.h>

namespace {
std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    // GetStringUTFChars отдаёт «modified UTF-8» (эмодзи ломаются) — берём байты через String.getBytes("UTF-8")
    jclass cls = env->GetObjectClass(s);
    jmethodID gb = env->GetMethodID(cls, "getBytes", "(Ljava/lang/String;)[B");
    jstring enc = env->NewStringUTF("UTF-8");
    auto arr = (jbyteArray)env->CallObjectMethod(s, gb, enc);
    std::string r;
    if (arr) {
        jsize n = env->GetArrayLength(arr);
        r.resize(n);
        env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte*>(&r[0]));
        env->DeleteLocalRef(arr);
    }
    env->DeleteLocalRef(enc);
    env->DeleteLocalRef(cls);
    return r;
}
jbyteArray bytes(JNIEnv* env, const std::string& s) {
    jbyteArray a = env->NewByteArray(s.size());
    env->SetByteArrayRegion(a, 0, s.size(), reinterpret_cast<const jbyte*>(s.data()));
    return a;
}
std::string g_error;
}  // namespace

#define FN(name) Java_com_hakkiiler_voiceassistant_data_llm_LlamaNative_##name

extern "C" JNIEXPORT jlong JNICALL FN(nativeOpen)(JNIEnv* env, jobject, jstring path, jint n_ctx, jint threads) {
    auto* s = LlmSession::open(jstr(env, path), n_ctx, threads);
    if (!s) g_error = "Не удалось загрузить модель / model load failed";
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT void JNICALL FN(nativeClose)(JNIEnv*, jobject, jlong h) {
    delete reinterpret_cast<LlmSession*>(h);
}

extern "C" JNIEXPORT void JNICALL FN(nativeCancel)(JNIEnv*, jobject, jlong h) {
    if (h) reinterpret_cast<LlmSession*>(h)->cancel = true;
}

extern "C" JNIEXPORT jstring JNICALL FN(nativeLastError)(JNIEnv* env, jobject, jlong h) {
    auto* s = reinterpret_cast<LlmSession*>(h);
    return env->NewStringUTF((s ? s->error : g_error).c_str());
}

/** roles/contents — история диалога; listener.onPiece(byte[]): Boolean. Возвращает весь ответ в UTF-8. */
extern "C" JNIEXPORT jbyteArray JNICALL FN(nativeChat)(JNIEnv* env, jobject, jlong h, jobjectArray roles,
                                                       jobjectArray contents, jint max_tokens, jfloat temperature,
                                                       jobject listener) {
    auto* s = reinterpret_cast<LlmSession*>(h);
    if (!s) return nullptr;
    std::vector<std::string> r, c;
    jsize n = env->GetArrayLength(roles);
    for (jsize i = 0; i < n; i++) {
        auto a = (jstring)env->GetObjectArrayElement(roles, i);
        auto b = (jstring)env->GetObjectArrayElement(contents, i);
        r.push_back(jstr(env, a));
        c.push_back(jstr(env, b));
        env->DeleteLocalRef(a);
        env->DeleteLocalRef(b);
    }
    jmethodID on_piece = nullptr;
    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        on_piece = env->GetMethodID(cls, "onPiece", "([B)Z");
        env->DeleteLocalRef(cls);
    }
    std::string prompt = s->format(r, c);
    std::string out = s->generate(prompt, max_tokens, temperature, [&](const std::string& p) {
        if (!on_piece) return true;
        jbyteArray a = bytes(env, p);
        jboolean go = env->CallBooleanMethod(listener, on_piece, a);
        env->DeleteLocalRef(a);
        if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
        return (bool)go;
    });
    if (out.empty() && !s->error.empty()) return nullptr;
    return bytes(env, out);
}
#endif
