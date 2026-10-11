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
 * Модели рисования. Ссылки закреплены на конкретные ревизии репозиториев Hugging Face,
 * чтобы файл не поменялся без нашего ведома.
 *
 * - SD-Turbo (Stability AI, Stability AI Community License), GGUF: веса UNet в q8_0, остальное f16.
 *   Делает картинку 512×512 за 1–4 шага без CFG — для телефона это в 10+ раз быстрее обычной SD 1.5.
 * - TAESD (madebyollin, MIT) — крошечный декодер латентов: декодирование в разы быстрее полного VAE.
 */
object ImageModelSource {
    val MODEL = ImageModelFile(
        fileName = "sd_turbo-f16-q8_0.gguf",
        url = "https://huggingface.co/Green-Sky/SD-Turbo-GGUF/resolve/19a31586d02d64a73b4419bc193b3ecfaf38e1f0/sd_turbo-f16-q8_0.gguf",
        bytes = 2_023_745_376L,
        sha256 = "d50be7655f0a554cf8041c145d88b210bd5f3c545423119dee62ae08cae51580",
    )

    val TAESD = ImageModelFile(
        fileName = "taesd.safetensors",
        url = "https://huggingface.co/madebyollin/taesd/resolve/614f76814bbe30edbe2e627ace1c2234c81a2c0e/diffusion_pytorch_model.safetensors",
        bytes = 9_793_292L,
        sha256 = "db169d69145ec4ff064e49d99c95fa05d3eb04ee453de35824a6d0f325513549",
    )

    val all = listOf(MODEL, TAESD)
    val totalBytes = all.sumOf { it.bytes }

    const val MODEL_NAME = "SD-Turbo (GGUF q8_0)"
    const val LICENSE = "Stability AI Community License"
}
