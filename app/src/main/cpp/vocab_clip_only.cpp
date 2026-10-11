// Замена stable-diffusion.cpp/src/tokenizers/vocab/vocab.cpp для мобильной сборки.
// Оригинал встраивает словари всех поддерживаемых моделей (T5, UMT5, Gemma, Mistral, Qwen) —
// это ~110 МБ кода в .so. SD-Turbo нужен только словарь CLIP, остальные заменены заглушками.
#include <stdexcept>
#include "vocab.h"
#include "clip_merges.hpp"

std::string load_clip_merges() {
    return std::string(reinterpret_cast<const char*>(clip_merges_utf8_c_str), sizeof(clip_merges_utf8_c_str));
}

static std::string not_bundled(const char* name) {
    throw std::runtime_error(std::string(name) + " vocabulary is not bundled in the mobile build");
}

std::string load_qwen2_merges() { return not_bundled("qwen2"); }
std::string load_mistral_merges() { return not_bundled("mistral"); }
std::string load_mistral_vocab_json() { return not_bundled("mistral"); }
std::string load_t5_tokenizer_json() { return not_bundled("t5"); }
std::string load_umt5_tokenizer_json() { return not_bundled("umt5"); }
std::string load_gemma_merges() { return not_bundled("gemma"); }
std::string load_gemma_vocab_json() { return not_bundled("gemma"); }
