package com.mathieu.madtrainer.health

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
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.mathieu.madtrainer.MainActivity
import com.mathieu.madtrainer.R
import com.mathieu.madtrainer.data.Hydration
import com.mathieu.madtrainer.data.HydrationStore
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Rappels d'hydratation : vérification toutes les 30 min, notification si retard sur le rythme de la journée. */
object HydrationReminder {
    private const val CHANNEL = "hydration"
    private const val NOTIF_ID = 4201
    private const val WORK = "hydration-check"
    const val ACTION_ADD = "com.mathieu.madtrainer.HYDRATION_ADD"

    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<HydrationWorker>(30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Vérifie le retard et notifie si besoin. */
    fun check(context: Context) {
        HydrationStore.init(context)
        val s = HydrationStore.state.value
        if (!s.reminders || !canNotify(context)) return
        val minute = LocalTime.now().toSecondOfDay() / 60
        val expected = s.expectedNow()
        val since = if (s.lastReminderAt > 0 && System.currentTimeMillis() - s.lastReminderAt < 24 * 3600_000L) {
            ((System.currentTimeMillis() - s.lastReminderAt) / 60_000).toInt()
        } else null
        if (!Hydration.shouldRemind(s.drunkMl, expected, minute, s.wakeHour, s.sleepHour, since)) return
        notify(context, Hydration.reminderText(s.drunkMl, expected, s.targetMl))
        HydrationStore.markReminded()
    }

    private fun notify(context: Context, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Rappels d'hydratation", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val add = PendingIntent.getBroadcast(
            context, 1, Intent(context, HydrationReceiver::class.java).setAction(ACTION_ADD),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Pense à boire")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(0, "+${Hydration.GLASS_ML} ml bu", add)
            .setAutoCancel(true)
            .build()
        if (canNotify(context)) runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID, n) }
    }

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIF_ID)
}

class HydrationWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        runCatching { HydrationReminder.check(applicationContext) }
        return Result.success()
    }
}

/** Bouton « +250 ml » de la notification : enregistre le verre sans ouvrir l'appli. */
class HydrationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != HydrationReminder.ACTION_ADD) return
        HydrationStore.init(context)
        HydrationStore.add(Hydration.GLASS_ML)
        HydrationReminder.cancel(context)
    }
}
