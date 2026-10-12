package com.hakkiiler.voiceassistant.domain.usecases

import com.hakkiiler.voiceassistant.domain.model.AppInfo
import com.hakkiiler.voiceassistant.domain.model.CustomCommand
import com.hakkiiler.voiceassistant.domain.model.Event
import com.hakkiiler.voiceassistant.domain.model.SettingsPanel

/** Управление телефоном. Реализация — data/system/SystemControlManager. */
interface DeviceController {
    /** @return установленная громкость в процентах */
    fun setVolume(percent: Int): Int
    fun adjustVolume(up: Boolean): Int
    fun setFlashlight(on: Boolean): Boolean
    fun batteryLevel(): Int
    /** @return сколько фоновых процессов закрыто */
    fun clearMemory(): Int
    fun openSettings(panel: SettingsPanel): Boolean
    fun openPowerMenu(): Boolean
    fun isMusicActive(): Boolean
    fun mediaPlayPause(play: Boolean)
    fun mediaSkip(next: Boolean)
    fun openApp(packageName: String): Boolean
    /** Таймер через системное приложение «Часы». */
    fun setTimer(seconds: Int, label: String?): Boolean = false
    fun launchableApps(): List<AppInfo>
}

interface EventStore {
    fun currentEvents(): List<Event>
    suspend fun add(event: Event)
}

interface CommandStore {
    fun currentCommands(): List<CustomCommand>
}

interface ReminderScheduler {
    fun schedule(event: Event)
}

/** Рисование по голосовой команде. Реализация — data/image/ImageStudio. */
interface DrawLauncher {
    enum class Result {
        /** Экран открыт, генерация запущена. */
        STARTED,
        /** Экран открыт, но модель ещё не скачана — запрос подставлен в поле. */
        NEEDS_MODEL,
        /** Экран открыт без запроса («нарисуй» без описания). */
        OPENED,
        /** Уже идёт другая генерация или загрузка модели. */
        BUSY,
        /** Телефон не поддерживает нативную библиотеку (не arm64 / нет i8mm). */
        UNSUPPORTED,
    }

    /** @param prompt описание в исходном виде (RU или EN), null — просто открыть экран */
    fun draw(prompt: String?): Result
}
