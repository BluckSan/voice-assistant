package com.hakkiiler.voiceassistant.data.image

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал выбора фото для «Изменить фото»: filesDir/logs/photo-pick.log (до ~200 КБ).
 * Его можно отправить кнопкой «Поделиться журналом», если фото не загружается.
 */
object PickLog {
    private const val TAG = "PhotoPick"
    private const val MAX = 200_000L

    fun file(context: Context) = File(File(context.filesDir, "logs").apply { mkdirs() }, "photo-pick.log")

    fun i(context: Context, msg: String) = write(context, "I", msg, null)
    fun w(context: Context, msg: String, t: Throwable? = null) = write(context, "W", msg, t)

    @Synchronized
    private fun write(context: Context, level: String, msg: String, t: Throwable?) {
        if (level == "W") Log.w(TAG, msg, t) else Log.i(TAG, msg)
        runCatching {
            val f = file(context)
            if (f.length() > MAX) f.writeText(f.readText().takeLast((MAX / 2).toInt()))
            val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
            f.appendText("$ts $level $msg" + (t?.let { "\n  " + Log.getStackTraceString(it).lines().take(8).joinToString("\n  ") } ?: "") + "\n")
        }
    }

    /** Заголовок с устройством — пишется при каждом выборе. */
    fun header(context: Context) = i(
        context,
        "device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT} " +
            "pickerAvailable=${runCatching { androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context) }.getOrNull()}",
    )
}
