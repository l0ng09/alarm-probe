package com.example.alarmprobe

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Probe {
    const val PREFS = "probe"
    const val KEY_TARGET = "target"
    const val EXTRA_PLANNED = "planned_at"
    const val CHANNEL = "probe"
    const val LOG_FILE = "probe.log"

    private const val REQ_FIRE = 1001
    private const val REQ_SHOW = 1002
    private const val REQ_OPEN = 1003

    fun ts(t: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(t))

    fun log(ctx: Context, msg: String) {
        val line = "${ts(System.currentTimeMillis())} | $msg\n"
        try {
            ctx.openFileOutput(LOG_FILE, Context.MODE_APPEND).use { it.write(line.toByteArray()) }
        } catch (e: Exception) {
            android.util.Log.e("AlarmProbe", "write log failed", e)
        }
    }

    fun readLog(ctx: Context): String = try {
        File(ctx.filesDir, LOG_FILE).readText().ifEmpty { "(暂无日志，先点上面的按钮设一个闹钟)" }
    } catch (e: Exception) {
        "(暂无日志，先点上面的按钮设一个闹钟)"
    }

    fun clearLog(ctx: Context) {
        try {
            File(ctx.filesDir, LOG_FILE).writeText("")
        } catch (e: Exception) {
            android.util.Log.e("AlarmProbe", "clear log failed", e)
        }
        log(ctx, "LOG_CLEARED")
    }

    private fun fireIntent(ctx: Context, planned: Long): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, REQ_FIRE,
            Intent(ctx, ProbeReceiver::class.java).putExtra(EXTRA_PLANNED, planned),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun canScheduleExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    fun schedule(ctx: Context, triggerAt: Long): Boolean {
        if (!canScheduleExact(ctx)) {
            log(ctx, "BLOCKED exact alarm permission not granted -> open button ②")
            return false
        }

        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_TARGET, triggerAt).apply()

        val fire = fireIntent(ctx, triggerAt)
        val show = PendingIntent.getActivity(
            ctx, REQ_SHOW, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return try {
            val am = ctx.getSystemService(AlarmManager::class.java)
            am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), fire)
            log(
                ctx,
                "SCHEDULED planned=${ts(triggerAt)} in_sec=${(triggerAt - System.currentTimeMillis()) / 1000}"
            )
            true
        } catch (e: SecurityException) {
            log(ctx, "ERROR setAlarmClock: ${e.message}")
            false
        }
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(fireIntent(ctx, 0L))
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_TARGET).apply()
        log(ctx, "CANCELLED")
    }

    fun savedTarget(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_TARGET, 0L)

    fun openIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, REQ_OPEN, Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

class ProbeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val planned = intent.getLongExtra(Probe.EXTRA_PLANNED, 0L)
        val now = System.currentTimeMillis()
        val delta = now - planned

        // 关键：日志先落盘，与通知是否显示完全解耦
        Probe.log(ctx, "FIRED planned=${Probe.ts(planned)} fired=${Probe.ts(now)} delta_ms=$delta")

        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(Probe.CHANNEL, "闹钟探针", NotificationManager.IMPORTANCE_HIGH)
        )
        val notification = Notification.Builder(ctx, Probe.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("闹钟触发")
            .setContentText("偏差 ${delta / 1000.0}s，打开 App 看日志")
            .setContentIntent(Probe.openIntent(ctx))
            .setAutoCancel(true)
            .build()
        nm.notify((now % 100000).toInt(), notification)
        Probe.log(ctx, "NOTIFY posted permission=${nm.areNotificationsEnabled()}")
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        val target = Probe.savedTarget(ctx)
        Probe.log(ctx, "BOOT action=$action saved_target=$target")
        if (target > System.currentTimeMillis()) {
            val ok = Probe.schedule(ctx, target)
            Probe.log(ctx, "BOOT re-registered ok=$ok")
        } else {
            Probe.log(ctx, "BOOT expired, skip")
        }
    }
}
