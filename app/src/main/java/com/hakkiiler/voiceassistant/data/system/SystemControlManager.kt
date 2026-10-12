package com.hakkiiler.voiceassistant.data.system

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import com.hakkiiler.voiceassistant.domain.model.AppInfo
import com.hakkiiler.voiceassistant.domain.model.SettingsPanel
import com.hakkiiler.voiceassistant.domain.usecases.DeviceController
import kotlin.math.roundToInt

/** Реальное управление телефоном: громкость, фонарик, медиакнопки, запуск приложений. */
class SystemControlManager(private val context: Context) : DeviceController {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val camera = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    @Volatile private var appsCache: List<AppInfo>? = null
    @Volatile private var appsCacheTime = 0L

    override fun setVolume(percent: Int): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (max * percent.coerceIn(0, 100) / 100.0).roundToInt()
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        return currentVolumePercent()
    }

    override fun adjustVolume(up: Boolean): Int {
        val dir = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        // два шага, чтобы изменение было заметно на слух
        repeat(2) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, if (it == 0) AudioManager.FLAG_SHOW_UI else 0) }
        return currentVolumePercent()
    }

    private fun currentVolumePercent(): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100.0 / max).roundToInt()
    }

    override fun setFlashlight(on: Boolean): Boolean = try {
        val id = camera.cameraIdList.firstOrNull { id ->
            camera.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        if (id == null) false else { camera.setTorchMode(id, on); true }
    } catch (e: Exception) {
        Log.w(TAG, "torch", e); false
    }

    override fun batteryLevel(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    override fun clearMemory(): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        // Начиная с Android 8 getRunningAppProcesses видит только свой процесс,
        // поэтому закрываем фоновые процессы известных приложений.
        val own = context.packageName
        var closed = 0
        for (app in launchableApps()) {
            if (app.packageName == own) continue
            runCatching { am.killBackgroundProcesses(app.packageName); closed++ }
        }
        return closed
    }

    override fun openSettings(panel: SettingsPanel): Boolean {
        val action = when (panel) {
            SettingsPanel.WIFI -> Settings.ACTION_WIFI_SETTINGS
            SettingsPanel.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
            SettingsPanel.DISPLAY -> Settings.ACTION_DISPLAY_SETTINGS
            SettingsPanel.LANGUAGE -> Settings.ACTION_LOCALE_SETTINGS
            SettingsPanel.GENERAL -> Settings.ACTION_SETTINGS
        }
        return start(Intent(action))
    }

    override fun openPowerMenu(): Boolean = start(Intent("android.intent.action.POWER_MENU"))

    override fun isMusicActive(): Boolean = audio.isMusicActive

    override fun mediaPlayPause(play: Boolean) =
        sendMediaKey(if (play) KeyEvent.KEYCODE_MEDIA_PLAY else KeyEvent.KEYCODE_MEDIA_PAUSE)

    override fun mediaSkip(next: Boolean) =
        sendMediaKey(if (next) KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    private fun sendMediaKey(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    override fun openApp(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return start(intent)
    }

    override fun setTimer(seconds: Int, label: String?): Boolean = start(
        Intent(android.provider.AlarmClock.ACTION_SET_TIMER)
            .putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            .apply { if (!label.isNullOrBlank()) putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label) },
    )

    private fun start(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.w(TAG, "startActivity failed: ${intent.action}", e); false
    }

    override fun launchableApps(): List<AppInfo> {
        val cached = appsCache
        if (cached != null && System.currentTimeMillis() - appsCacheTime < 60_000) return cached
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(query, PackageManager.MATCH_ALL)
            .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
        appsCache = list
        appsCacheTime = System.currentTimeMillis()
        return list
    }

    companion object { private const val TAG = "SystemControl" }
}
