package com.hakkiiler.voiceassistant.data.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.hakkiiler.voiceassistant.domain.model.Event
import com.hakkiiler.voiceassistant.domain.usecases.ReminderScheduler
import com.hakkiiler.voiceassistant.service.NotificationReceiver

/** Напоминания через AlarmManager (точные, если система разрешила, иначе «примерно вовремя»). */
class ReminderManager(private val context: Context) : ReminderScheduler {
    private val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    override fun schedule(event: Event) {
        val at = event.timestamp - event.reminderMinutes * 60_000L
        if (at <= System.currentTimeMillis()) return
        val pi = pendingIntent(event)
        if (canScheduleExact()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(event: Event) = alarms.cancel(pendingIntent(event))

    private fun pendingIntent(event: Event): PendingIntent {
        val intent = Intent(context, NotificationReceiver::class.java)
            .putExtra(NotificationReceiver.EXTRA_ID, event.id)
            .putExtra(NotificationReceiver.EXTRA_TITLE, event.title)
            .putExtra(NotificationReceiver.EXTRA_TIME, event.timestamp)
        return PendingIntent.getBroadcast(
            context, event.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
