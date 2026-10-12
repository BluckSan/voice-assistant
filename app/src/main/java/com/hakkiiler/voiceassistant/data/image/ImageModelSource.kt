package com.hakkiiler.voiceassistant.data.image

/** Файл модели для рисования: откуда качать и как проверить. */
data class ImageModelFile(
    val fileName: String,
    val url: String,
    val bytes: Long,
    /** SHA-256 (совпадает с X-Linked-ETag на Hugging Face). */
    val sha256: String,
)

/**
 * Модели рисования. Ссылки закреплены на конкретные ревизии / релизы, чтобы файл не поменялся без нашего ведома.
 *
 * - Realistic Vision V6.0 B1 (SG161222) — фотореалистичная SD 1.5, хорошо рисует людей. GGUF q8_0 со встроенным VAE.
 *   Лицензия оригинала: CreativeML OpenRAIL-M (GGUF-репозиторий second-state помечен Apache-2.0).
 * - LCM-LoRA SD1.5 (latent-consistency, OpenRAIL++) — 4–8 шагов вместо 25–30. После загрузки один раз
 *   вливается в модель (nativeMergeLora), исходные файлы удаляются.
 * - TAESD (madebyollin, MIT) — быстрый декодер латентов, по желанию.
 * - RealESRGAN x4plus (xinntao, BSD-3-Clause) — нейросетевое увеличение, качается отдельно по желанию.
 */
object ImageModelSource {
    val BASE = ImageModelFile(
        fileName = "realisticVisionV60B1_v51HyperVAE-Q8_0.gguf",
        url = "https://huggingface.co/second-state/Realistic_Vision_V6.0_B1-GGUF/resolve/81bf97de0845f1373bf99b7d9b541642deac3dab/realisticVisionV60B1_v51HyperVAE-Q8_0.gguf",
        bytes = 1_765_950_304L,
        sha256 = "1325806de9a9a552c143fee1912a21aface359e01b7330ac72dce5aca9c193da",
    )

    val LCM_LORA = ImageModelFile(
        fileName = "lcm-lora-sdv1-5.safetensors",
        url = "https://huggingface.co/latent-consistency/lcm-lora-sdv1-5/resolve/cf2fced511dbe7e26c8d1d397e728fbab875db4b/pytorch_lora_weights.safetensors",
        bytes = 134_621_556L,
        sha256 = "8f90d840e075ff588a58e22c6586e2ae9a6f7922996ee6649a7f01072333afe4",
    )

    val TAESD = ImageModelFile(
        fileName = "taesd.safetensors",
        url = "https://huggingface.co/madebyollin/taesd/resolve/614f76814bbe30edbe2e627ace1c2234c81a2c0e/diffusion_pytorch_model.safetensors",
        bytes = 9_793_292L,
        sha256 = "db169d69145ec4ff064e49d99c95fa05d3eb04ee453de35824a6d0f325513549",
    )

    val UPSCALER = ImageModelFile(
        fileName = "RealESRGAN_x4plus.pth",
        url = "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.1.0/RealESRGAN_x4plus.pth",
        bytes = 67_040_989L,
        sha256 = "4fa0d38905f75ac06eb49a7951b426670021be3018265fd191d2125df9d682f1",
    )

    /** Обязательный набор: модель + LCM-LoRA + TAESD. */
    val required = listOf(BASE, LCM_LORA, TAESD)
    val requiredBytes = required.sumOf { it.bytes }

    /** Итоговый файл после вливания LCM-LoRA. */
    const val MERGED_FILE = "realistic-vision-v6-lcm-q8_0.gguf"
    const val MERGED_BYTES = 1_765_950_304L

    /** Пробовать «на лету» не нужно: после слияния LoRA семплер LCM, CFG ~1. */
    const val SAMPLER = "lcm"

    const val MODEL_NAME = "Realistic Vision V6.0 B1 + LCM-LoRA"

    /** Для экрана «О моделях» и README. */
    val credits = listOf(
        "Realistic Vision V6.0 B1 — SG161222, CreativeML OpenRAIL-M (GGUF q8_0: second-state)",
        "LCM-LoRA SD1.5 — latent-consistency, OpenRAIL++",
        "TAESD — madebyollin, MIT",
        "Real-ESRGAN x4plus — xinntao, BSD-3-Clause",
        "stable-diffusion.cpp — leejet, MIT",
    )
}
