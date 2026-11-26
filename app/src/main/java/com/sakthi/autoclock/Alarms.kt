package com.sakthi.autoclock

import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.location.LocationManagerCompat
import java.util.Calendar

object Scheduler {
    fun next(a: Automation): Long? {
        if (a.days.isEmpty()) return null
        val now = System.currentTimeMillis()
        for (offset in 0..7) {
            val c = Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.HOUR_OF_DAY, a.hour); set(Calendar.MINUTE, a.minute)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            if (c.timeInMillis > now && c.get(Calendar.DAY_OF_WEEK) in a.days) return c.timeInMillis
        }
        return null
    }

    private fun pi(c: Context, a: Automation) = PendingIntent.getBroadcast(
        c, a.id.hashCode(),
        Intent(c, AlarmReceiver::class.java).putExtra("id", a.id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun schedule(c: Context, a: Automation) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pi(c, a))
        if (!a.enabled) return
        val t = next(a) ?: return
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi(c, a))
        else
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi(c, a))
    }

    fun cancel(c: Context, a: Automation) {
        (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi(c, a))
    }

    fun scheduleAll(c: Context) = Store.all(c).forEach { schedule(c, it) }
}

object Notifier {
    private const val CH = "autoclock"

    fun activity(c: Context, req: Int, i: Intent): PendingIntent =
        PendingIntent.getActivity(c, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun post(
        c: Context, nid: Int, title: String, text: String,
        tap: PendingIntent? = null, actionLabel: String? = null, action: PendingIntent? = null
    ) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "AutoClock", NotificationManager.IMPORTANCE_HIGH))
        val b = NotificationCompat.Builder(c, CH)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        if (tap != null) b.setContentIntent(tap)
        if (action != null && actionLabel != null) b.addAction(0, actionLabel, action)
        try { nm.notify(nid, b.build()) } catch (_: Exception) {}
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val a = Store.get(c, i.getStringExtra("id") ?: return) ?: return
        if (a.enabled) {
            val run = Notifier.activity(c, a.id.hashCode(), Intent(c, RunActivity::class.java).putExtra("id", a.id))
            Notifier.post(c, a.id.hashCode(), "Time to ${a.name}",
                "Unlock your phone, then tap OK to start.", run, "OK", run)
        }
        Scheduler.schedule(c, a)   // queue the next occurrence
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) = Scheduler.scheduleAll(c)
}

/** Invisible activity opened by the notification's OK button. */
class RunActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.get(this, intent.getStringExtra("id") ?: "")?.let { Runner.start(this, it) }
        finish()
    }
}

object Runner {
    fun start(c: Context, a: Automation) {
        val svc = ClockService.instance
        if (svc == null) {
            Notifier.post(c, 2, "Accessibility service is off ❌", "Enable AutoClock in Accessibility settings.",
                Notifier.activity(c, 21, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
            return
        }
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!LocationManagerCompat.isLocationEnabled(lm)) {
            val p = Notifier.activity(c, 22, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            Notifier.post(c, 2, "Location is off ❌", "${a.name} stopped. Turn on location, then run again.", p, "Open settings", p)
            return
        }
        val launch = c.packageManager.getLaunchIntentForPackage(a.targetPackage)
        if (launch == null) {
            Notifier.post(c, 2, "Can't open the app ❌", "Package ${a.targetPackage} not found. Re-record ${a.name}.")
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        c.startActivity(launch)
        svc.startReplay(a)
    }
}
