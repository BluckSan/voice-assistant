package com.hakkiiler.voiceassistant.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hakkiiler.voiceassistant.MainActivity
import com.hakkiiler.voiceassistant.R
import com.hakkiiler.voiceassistant.di.AppContainer
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Показывает уведомление-напоминание о событии. */
class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val time = intent.getLongExtra(EXTRA_TIME, 0L)
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(time)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify((intent.getStringExtra(EXTRA_ID) ?: title).hashCode(), n)
        }
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TIME = "time"
        const val CHANNEL = "reminders"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = context.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Напоминания", NotificationManager.IMPORTANCE_HIGH))
            }
        }
    }
}

/** После перезагрузки AlarmManager забывает будильники — ставим напоминания заново. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val c = AppContainer.from(context)
        c.appScope.launch {
            try {
                val now = System.currentTimeMillis()
                c.events.loadNow().filter { it.timestamp > now }.forEach { c.reminders.schedule(it) }
            } finally {
                pending.finish()
            }
        }
    }
}
