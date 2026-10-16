package com.hakkiiler.voiceassistant

import android.app.Application
import com.hakkiiler.voiceassistant.di.AppContainer

class VoiceAssistantApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // процесс самопроверки видеочипа (:gpuprobe) — без остального приложения
        val proc = runCatching { java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000') }.getOrDefault("")
        if (proc.endsWith(":gpuprobe")) return
        com.hakkiiler.voiceassistant.data.accel.Accel.init(this)
        container = AppContainer(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // модель рисования занимает ~2 ГБ: отпускаем её первой, когда системе не хватает памяти
        if (::container.isInitialized) container.draw.onTrimMemory(level)
    }
}
