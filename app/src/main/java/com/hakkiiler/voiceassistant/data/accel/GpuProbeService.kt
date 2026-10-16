package com.hakkiiler.voiceassistant.data.accel

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.system.Os
import android.util.Log
import com.hakkiiler.voiceassistant.data.image.StableDiffusionNative
import com.hakkiiler.voiceassistant.data.llm.LlamaNative

/**
 * Самопроверка Vulkan в отдельном процессе (:gpuprobe). Если драйвер видеочипа уронит процесс,
 * основное приложение просто увидит файл без «done» и выберет CPU.
 */
class GpuProbeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val noF16 = intent?.getBooleanExtra(EXTRA_NO_F16, false) == true
        Thread {
            val f = Accel.probeFile(this)
            fun append(s: String) = runCatching { f.appendText(s + "\n") }
            try {
                if (noF16) Os.setenv("GGML_VK_DISABLE_F16", "1", true)
                if (StableDiffusionNative.isAvailable) {
                    append("gpu=" + StableDiffusionNative.nativeGpuName())
                    append("sd=" + StableDiffusionNative.nativeVulkanProbe())
                } else append("sd=fail unavailable")
                append("llm=" + if (LlamaNative.unsupportedReason == null) LlamaNative.nativeVulkanProbe() else "fail unavailable")
            } catch (t: Throwable) {
                Log.e("GpuProbe", "probe failed", t)
                append("sd=fail ${t.javaClass.simpleName}")
            }
            append("done")
            stopSelf()
            // процесс пробы больше не нужен: освобождаем память видеочипа и драйвера
            Process.killProcess(Process.myPid())
        }.start()
        return START_NOT_STICKY
    }

    companion object {
        const val EXTRA_NO_F16 = "no_f16"
    }
}
