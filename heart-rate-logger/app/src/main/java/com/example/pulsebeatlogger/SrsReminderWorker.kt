package com.example.pulsebeatlogger

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.example.pulsebeatlogger.data.AppDatabase
import java.util.concurrent.TimeUnit

/**
 * Runs once per day, checks how many SRS items are due across all skills,
 * and fires a notification so the user comes back and keeps their streak.
 *
 * Scheduled via [SrsReminderWorker.schedule] — call this from MainActivity.
 */
class SrsReminderWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val WORK_NAME   = "srs_daily_reminder"
        private const val CHANNEL_ID  = "srs_reminders"
        private const val NOTIF_ID    = 1001

        /**
         * Schedules a periodic daily notification at approximately the preferred hour.
         * Safe to call multiple times — WorkManager de-duplicates by work name.
         */
        fun schedule(context: Context, hourOfDay: Int = 9) {
            createChannel(context)

            // Calculate initial delay so the first run fires near the target hour
            val now = java.util.Calendar.getInstance()
            val target = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, hourOfDay)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                if (before(now)) add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            val initialDelay = target.timeInMillis - now.timeInMillis

            val request = PeriodicWorkRequestBuilder<SrsReminderWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,   // Don't reset if already scheduled
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        private fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Daily Practice Reminders",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Reminds you when SRS review items are due"
                    enableVibration(true)
                }
                context.getSystemService(NotificationManager::class.java)
                    ?.createNotificationChannel(channel)
            }
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val db = AppDatabase.getInstance(context)
            val now = System.currentTimeMillis()
            val totalDue: Int = db.learningItemDao().countAllDue(now)

            if (totalDue == 0) return Result.success()   // nothing due, skip notification

            val bySkill = db.learningItemDao().countDueBySkill(now)

            val skillSummary = bySkill.take(3).joinToString(", ") { "${it.cnt} ${it.skillName}" }
            val title = if (totalDue == 1) "1 review item due today" else "$totalDue review items due today"
            val body  = if (bySkill.size == 1) "Keep your ${bySkill[0].skillName} streak going!"
                        else skillSummary + if (bySkill.size > 3) " + more" else ""

            fireNotification(title, body)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun fireNotification(title: String, body: String) {
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true

        if (!hasPermission) return

        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIF_ID, notification)
    }
}
