package dev.quietshield.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import dev.quietshield.QuietPrefs
import dev.quietshield.filter.LocalDnsVpnService
import dev.quietshield.filter.RootDnsService
import java.time.LocalDateTime
import java.time.LocalTime

object ScheduleCoordinator {
    private const val REQUEST = 803

    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.cancel(pending(context))
        if (!QuietPrefs.bool(context, "schedule_enabled")) return
        val start = parse(QuietPrefs.string(context, "schedule_start", "23:00")) ?: return
        val end = parse(QuietPrefs.string(context, "schedule_end", "07:00")) ?: return
        val days = selectedDays(context)
        val now = LocalDateTime.now()
        val candidates = mutableListOf<Pair<LocalDateTime, Boolean>>()
        for (offset in 0..8) {
            val date = now.toLocalDate().plusDays(offset.toLong())
            if (date.dayOfWeek.value in days) {
                candidates += LocalDateTime.of(date, start) to true
                val endDate = if (end <= start) date.plusDays(1) else date
                candidates += LocalDateTime.of(endDate, end) to false
            }
        }
        val next = candidates.filter { it.first.isAfter(now) }.minByOrNull { it.first } ?: return
        val intent = Intent(context, ScheduleReceiver::class.java).setAction(ACTION).putExtra(EXTRA_ENABLE, next.second)
        val pi = PendingIntent.getBroadcast(context, REQUEST, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.first.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), pi)
    }

    fun isWindowActive(context: Context): Boolean {
        if (!QuietPrefs.bool(context, "schedule_enabled")) return false
        val start = parse(QuietPrefs.string(context, "schedule_start", "23:00")) ?: return false
        val end = parse(QuietPrefs.string(context, "schedule_end", "07:00")) ?: return false
        val days = selectedDays(context)
        val now = LocalDateTime.now()
        val minute = now.toLocalTime()
        if (end > start) return now.dayOfWeek.value in days && minute >= start && minute < end
        if (minute >= start) return now.dayOfWeek.value in days
        if (minute < end) return now.toLocalDate().minusDays(1).dayOfWeek.value in days
        return false
    }

    private fun selectedDays(context: Context) = QuietPrefs.string(context, "schedule_days", "1,2,3,4,5,6,7")
        .split(',').mapNotNull { it.toIntOrNull() }.toSet()
    private fun pending(context: Context) = PendingIntent.getBroadcast(
        context, REQUEST, Intent(context, ScheduleReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
    )
    private fun parse(value: String) = runCatching { LocalTime.parse(value) }.getOrNull()
    const val ACTION = "dev.quietshield.SCHEDULE"
    const val EXTRA_ENABLE = "enable"
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val enable = intent.getBooleanExtra(ScheduleCoordinator.EXTRA_ENABLE, false)
        if (!QuietPrefs.bool(context, "schedule_enabled")) return
        QuietPrefs.setBool(context, "schedule_active", enable)
        if (enable) {
            if (QuietPrefs.networkMode(context) == "root") context.startForegroundService(Intent(context, RootDnsService::class.java))
            else if (VpnService.prepare(context) == null) context.startForegroundService(Intent(context, LocalDnsVpnService::class.java))
        } else {
            context.stopService(Intent(context, LocalDnsVpnService::class.java))
            context.stopService(Intent(context, RootDnsService::class.java))
        }
        ScheduleCoordinator.schedule(context)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val shouldRun = ScheduleCoordinator.isWindowActive(context)
        QuietPrefs.setBool(context, "schedule_active", shouldRun)
        if (shouldRun) {
            if (QuietPrefs.networkMode(context) == "root") context.startForegroundService(Intent(context, RootDnsService::class.java))
            else if (VpnService.prepare(context) == null) context.startForegroundService(Intent(context, LocalDnsVpnService::class.java))
        }
        ScheduleCoordinator.schedule(context)
    }
}

