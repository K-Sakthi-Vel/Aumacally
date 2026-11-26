package com.sakthi.autoclock

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.UUID

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private val dayNames = arrayOf("", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { f() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        col.addView(TextView(this).apply { text = "AutoClock"; textSize = 26f })
        status = TextView(this).apply { setPadding(0, dp(8), 0, dp(8)) }
        col.addView(status)
        col.addView(btn("1. Enable accessibility service") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        col.addView(btn("2. Allow notifications & exact alarms") { perms() })
        col.addView(btn("3. Disable battery optimisation") { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) })
        col.addView(btn("+ New automation") { newAutomation() })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        Scheduler.scheduleAll(this)
        refresh()
    }

    private fun refresh() {
        status.text = if (ClockService.instance != null) "✅ Accessibility service is running"
        else "⚠️ Accessibility service is OFF. Do step 1 first."
        list.removeAllViews()
        Store.all(this).forEach { list.addView(card(it)) }
    }

    private fun card(a: Automation): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0x14000000)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) }
        }
        c.addView(TextView(this).apply {
            textSize = 16f
            text = "${a.name}  ·  ${"%02d:%02d".format(a.hour, a.minute)}  ·  ${a.days.joinToString(" ") { dayNames[it] }}\n" +
                "${a.steps.size} steps · ${a.guards.size} stop rule(s) · ${a.targetPackage}"
        })
        c.addView(Switch(this).apply {
            text = "Enabled"; isChecked = a.enabled
            setOnCheckedChangeListener { _, on ->
                a.enabled = on; Store.save(this@MainActivity, a); Scheduler.schedule(this@MainActivity, a)
            }
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(b: Button) = row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        add(btn("Run now") { Runner.start(this, a) })
        add(btn("Re-record") { record(a) })
        add(btn("Delete") {
            Scheduler.cancel(this, a); Store.delete(this, a.id); refresh()
        })
        c.addView(row)
        return c
    }

    private fun newAutomation() {
        if (ClockService.instance == null) { Toast.makeText(this, "Enable the accessibility service first (step 1)", Toast.LENGTH_LONG).show(); return }
        val et = EditText(this).apply { hint = "e.g. Clock In" }
        AlertDialog.Builder(this).setTitle("Name").setView(et)
            .setPositiveButton("Next") { _, _ ->
                val name = et.text.toString().ifBlank { "Automation" }
                TimePickerDialog(this, { _, h, m ->
                    val a = Automation(UUID.randomUUID().toString(), name, h, m, listOf(2, 3, 4, 5, 6))
                    a.guards.add(Guard("100m range", "Out of 100 m range, clock-in rejected"))
                    record(a)
                }, 9, 30, true).show()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun record(a: Automation) {
        val svc = ClockService.instance
        if (svc == null) { Toast.makeText(this, "Enable the accessibility service first", Toast.LENGTH_LONG).show(); return }
        svc.startRecording(a)
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Toast.makeText(this, "Open Zoho CRM, do the steps, then tap Done on the floating bar", Toast.LENGTH_LONG).show()
    }

    private fun perms() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1); return }
        exactAlarm()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        exactAlarm()
    }

    private fun exactAlarm() {
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms())
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        else Toast.makeText(this, "Notifications and alarms are allowed", Toast.LENGTH_SHORT).show()
    }
}
