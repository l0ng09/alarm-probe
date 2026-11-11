package com.example.alarmprobe

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var scroller: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pad = (16 * resources.displayMetrics.density).toInt()
        root.setPadding(pad, pad, pad, pad)

        status = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
        }
        root.addView(status)

        fun row(vararg buttons: Button) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.forEach {
                line.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            root.addView(line)
        }

        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            setOnClickListener { onClick() }
        }

        row(
            btn("设 5 分钟后") { scheduleIn(5) },
            btn("设 30 分钟后") { scheduleIn(30) }
        )
        row(
            btn("设到 23:00") { scheduleAt23() },
            btn("取消闹钟") { Probe.cancel(this); refresh() }
        )
        row(
            btn("① 通知权限") { requestNotification() },
            btn("② 精确闹钟") { openExactAlarmSettings() }
        )
        row(
            btn("③ 电池白名单") { openBatterySettings() },
            btn("④ 自启动/详情") { openAppDetails() }
        )
        row(
            btn("刷新日志") { refresh() },
            btn("清空日志") { Probe.clearLog(this); refresh() }
        )

        logView = TextView(this).apply {
            textSize = 11f
            setTextIsSelectable(true)
        }
        scroller = ScrollView(this).apply { addView(logView) }
        root.addView(
            scroller,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)
    }

    private fun scheduleIn(minutes: Int) {
        val target = System.currentTimeMillis() + minutes * 60_000L
        val ok = Probe.schedule(this, target)
        toast(if (ok) "已设 $minutes 分钟后：${Probe.ts(target)}" else "没设成：精确闹钟权限没开，点②去授权")
        refresh()
    }

    private fun scheduleAt23() {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        val ok = Probe.schedule(this, cal.timeInMillis)
        toast(if (ok) "已设到 ${Probe.ts(cal.timeInMillis)}" else "没设成：精确闹钟权限没开，点②去授权")
        refresh()
    }

    private fun requestNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
        } else {
            toast("通知权限已具备")
        }
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
            )
        } else {
            toast("Android 11 及以下不需要这个权限")
        }
    }

    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            openAppDetails()
        }
    }

    private fun openAppDetails() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
    }

    private fun refresh() {
        val nm = getSystemService(NotificationManager::class.java)
        val pm = getSystemService(PowerManager::class.java)
        val notif = nm.areNotificationsEnabled()
        val exact = Probe.canScheduleExact(this)
        val battery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(packageName)
        } else {
            true
        }
        val target = Probe.savedTarget(this)

        status.text = listOf(
            "通知权限:${flag(notif)}  精确闹钟:${flag(exact)}  电池白名单:${flag(battery)}",
            "已设闹钟: ${if (target > 0) Probe.ts(target) else "无"}",
            "判定: 偏差 ≤1min 算准 | 连续 3 晚 ≥3/3 | 划掉 App 后仍须触发"
        ).joinToString("\n")

        logView.text = Probe.readLog(this)
        scroller.post { scroller.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun flag(b: Boolean) = if (b) "开" else "关"
    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }
}
