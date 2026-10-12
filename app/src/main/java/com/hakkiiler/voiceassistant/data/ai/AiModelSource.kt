package com.hakkiiler.voiceassistant.data.ai

import com.hakkiiler.voiceassistant.data.image.ImageModelFile

/**
 * Нейросети для речи и разговора (версия 1.2). Ссылки закреплены на ревизии Hugging Face, SHA-256 = X-Linked-ETag.
 *
 * - Whisper small (OpenAI, MIT) в формате whisper.cpp q5_1 — многоязычное распознавание: в одной фразе
 *   понимает и русские, и английские слова («поставь reminder на завтра», «открой YouTube»).
 * - Qwen2.5-3B-Instruct (Alibaba, Qwen Research License) Q4_K_M — локальная LLM для разговора и разбора команд,
 *   хорошо говорит по-русски, умеет отвечать строкой JSON для вызова действий.
 */
object AiModelSource {
    val WHISPER = ImageModelFile(
        fileName = "ggml-small-q5_1.bin",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-small-q5_1.bin",
        bytes = 190_085_487L,
        sha256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
    )

    val LLM = ImageModelFile(
        fileName = "qwen2.5-3b-instruct-q4_k_m.gguf",
        url = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/7dabda4d13d513e3e842b20f0d435c732f172cbe/qwen2.5-3b-instruct-q4_k_m.gguf",
        bytes = 2_104_932_768L,
        sha256 = "626b4a6678b86442240e33df819e00132d3ba7dddfe1cdc4fbb18e0a9615c62d",
    )

    const val WHISPER_NAME = "Whisper small (RU+EN)"
    const val LLM_NAME = "Qwen2.5-3B-Instruct"

    /** Оценка памяти: веса + KV-кэш на 4096 токенов + буферы. */
    const val LLM_RAM = 2_600L * 1024 * 1024
    const val WHISPER_RAM = 450L * 1024 * 1024

    val credits = listOf(
        "Whisper small — OpenAI, MIT (whisper.cpp — ggml-org, MIT)",
        "Qwen2.5-3B-Instruct — Alibaba Qwen, Qwen Research License (llama.cpp — ggml-org, MIT)",
    )
}
