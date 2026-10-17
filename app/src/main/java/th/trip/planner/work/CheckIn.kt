package th.trip.planner.work

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
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import th.trip.planner.MainActivity
import th.trip.planner.R
import th.trip.planner.data.Repository
import th.trip.planner.logic.TripLogic
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/** Daily reminder (10:00 by default) asking whether the trip is going to plan. */
object CheckInScheduler {
    private const val WORK = "daily_check_in"
    const val CHANNEL = "check_in"

    fun createChannel(context: Context) {
        val ch = NotificationChannel(CHANNEL, "Trip check-in", NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    fun schedule(context: Context, hour: Int) {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(hour, 0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val req = OneTimeWorkRequestBuilder<CheckInWorker>()
            .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WORK)
}

class CheckInWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = Repository(applicationContext)
        val trip = repo.loadTrip()
        if (!trip.activated || trip.finished) return Result.success()
        val today = LocalDate.now()
        val first = TripLogic.firstDay(trip)
        val last = TripLogic.lastDay(trip)
        val during = first != null && last != null && today >= first && today <= last.plusDays(1)
        if (during && today.toString() !in trip.checkIns) notify(repo.lang)
        if (last == null || today <= last.plusDays(1)) CheckInScheduler.schedule(applicationContext, repo.checkInHour)
        return Result.success()
    }

    private fun notify(lang: String) {
        val ctx = applicationContext
        th.trip.planner.ui.I18n.set(ctx, lang.ifBlank { th.trip.planner.ui.I18n.deviceDefault() })
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CheckInScheduler.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_trip)
            .setContentTitle(th.trip.planner.ui.tr("ทริปวันนี้เป็นไปตามแผนไหม?", "Is today going to plan?"))
            .setContentText(th.trip.planner.ui.tr("แตะเพื่อยืนยันหรือปรับแผนการเดินทาง", "Tap to confirm or adjust your trip"))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(1001, n)
    }
}
