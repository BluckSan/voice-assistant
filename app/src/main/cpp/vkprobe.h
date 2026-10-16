// Самопроверка Vulkan-бэкенда ggml: считаем несколько маленьких операций на видеочипе и на CPU и сравниваем.
// Подключается в sdjni.cpp и llamajni.cpp — у каждого свой ggml (разные версии), API здесь общий.
#pragma once
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>
#include "ggml.h"
#include "ggml-alloc.h"
#include "ggml-backend.h"
#include "ggml-cpu.h"

namespace vkprobe {

inline ggml_backend_dev_t find_gpu() {
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t d = ggml_backend_dev_get(i);
        auto t = ggml_backend_dev_type(d);
        if (t == GGML_BACKEND_DEVICE_TYPE_GPU || t == GGML_BACKEND_DEVICE_TYPE_IGPU) return d;
    }
    return nullptr;
}

// Имя устройства ggml (например «Vulkan0») или пусто.
inline std::string gpu_name() {
    ggml_backend_dev_t d = find_gpu();
    return d ? ggml_backend_dev_name(d) : "";
}

// Строит граф: f16·f32 и q8_0·f32 матмулы, softmax, silu, add. Возвращает все выходы подряд.
inline bool run(ggml_backend_t be, std::vector<float>& out, std::string& err) {
    const int K = 256, M = 96, N = 40;
    ggml_init_params ip = {ggml_tensor_overhead() * 64 + ggml_graph_overhead(), nullptr, true};
    ggml_context* ctx = ggml_init(ip);
    if (!ctx) { err = "ggml_init"; return false; }
    ggml_tensor* a16 = ggml_new_tensor_2d(ctx, GGML_TYPE_F16, K, M);
    ggml_tensor* a8 = ggml_new_tensor_2d(ctx, GGML_TYPE_Q8_0, K, M);
    ggml_tensor* b = ggml_new_tensor_2d(ctx, GGML_TYPE_F32, K, N);
    ggml_tensor* r1 = ggml_mul_mat(ctx, a16, b);
    ggml_tensor* r2 = ggml_mul_mat(ctx, a8, b);
    ggml_tensor* r3 = ggml_soft_max(ctx, ggml_scale(ctx, r1, 0.05f));
    ggml_tensor* r4 = ggml_silu(ctx, ggml_add(ctx, r2, r1));
    ggml_tensor* outs[4] = {r1, r2, r3, r4};
    ggml_cgraph* g = ggml_new_graph(ctx);
    for (auto* t : outs) ggml_build_forward_expand(g, t);
    ggml_backend_buffer_t buf = ggml_backend_alloc_ctx_tensors(ctx, be);
    if (!buf) { err = "alloc"; ggml_free(ctx); return false; }

    // детерминированные данные
    std::vector<float> fa(K * M), fb(K * N);
    uint32_t s = 12345;
    auto rnd = [&]() { s = s * 1664525u + 1013904223u; return ((s >> 8) & 0xFFFF) / 32768.0f - 1.0f; };
    for (auto& v : fa) v = rnd();
    for (auto& v : fb) v = rnd();
    std::vector<ggml_fp16_t> h(K * M);
    ggml_fp32_to_fp16_row(fa.data(), h.data(), K * M);
    ggml_backend_tensor_set(a16, h.data(), 0, ggml_nbytes(a16));
    std::vector<uint8_t> q(ggml_nbytes(a8));
    ggml_quantize_chunk(GGML_TYPE_Q8_0, fa.data(), q.data(), 0, M, K, nullptr);
    ggml_backend_tensor_set(a8, q.data(), 0, q.size());
    ggml_backend_tensor_set(b, fb.data(), 0, ggml_nbytes(b));

    bool ok = ggml_backend_graph_compute(be, g) == GGML_STATUS_SUCCESS;
    if (!ok) err = "compute";
    out.clear();
    for (auto* t : outs) {
        std::vector<float> v(ggml_nelements(t));
        ggml_backend_tensor_get(t, v.data(), 0, v.size() * sizeof(float));
        out.insert(out.end(), v.begin(), v.end());
    }
    ggml_backend_buffer_free(buf);
    ggml_free(ctx);
    return ok;
}

// "ok <имя> <maxerr>" или "fail <причина>"
inline std::string self_test() {
    ggml_backend_dev_t d = find_gpu();
    if (!d) return "fail no-vulkan-device";
    std::string desc = ggml_backend_dev_description(d);
    ggml_backend_t gpu = ggml_backend_dev_init(d, nullptr);
    if (!gpu) return "fail init " + desc;
    ggml_backend_t cpu = ggml_backend_cpu_init();
    std::vector<float> rg, rc;
    std::string err;
    bool okg = run(gpu, rg, err);
    bool okc = okg && run(cpu, rc, err);
    ggml_backend_free(gpu);
    ggml_backend_free(cpu);
    if (!okg || !okc) return "fail " + err + " " + desc;
    double maxerr = 0, maxabs = 1e-6;
    for (size_t i = 0; i < rg.size(); ++i) {
        if (!std::isfinite(rg[i])) return "fail nan " + desc;
        maxerr = std::fmax(maxerr, std::fabs(rg[i] - rc[i]));
        maxabs = std::fmax(maxabs, std::fabs(rc[i]));
    }
    double rel = maxerr / maxabs;
    char b[64];
    snprintf(b, sizeof b, "%.4f", rel);
    if (rel > 0.03) return std::string("fail mismatch ") + b + " " + desc;
    return std::string("ok ") + b + " " + desc;
}

}  // namespace vkprobe
