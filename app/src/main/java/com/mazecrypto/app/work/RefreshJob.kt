package com.mazecrypto.app.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.mazecrypto.app.MainActivity
import com.mazecrypto.app.R
import com.mazecrypto.app.data.Maze
import com.mazecrypto.app.widget.Widgets
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Background refresh for the widgets and alert rules, through the platform
 * JobScheduler (no extra library): a periodic job every 15/30/60 minutes
 * (Settings → Widgets) that only runs with a network, and a one-off job for
 * a tap on a widget's refresh icon.
 *
 * Android does not run periodic work more often than every 15 minutes; while
 * the app is open it refreshes on its own timer and the widgets follow.
 */
class RefreshJob : JobService() {
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        Maze.init(applicationContext)
        job = Maze.scope.launch {
            try {
                Maze.refresh(background = true)
            } finally {
                Widgets.clearBusy(applicationContext)
                Widgets.updateAll(applicationContext)
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        Widgets.clearBusy(applicationContext)
        return true
    }

    companion object {
        private const val TAG = "MazeCrypto"
        private const val PERIODIC = 1
        private const val NOW = 2

        private fun scheduler(ctx: Context) = ctx.getSystemService(JobScheduler::class.java)

        /** (Re)schedule the periodic job from the current settings; 0 minutes cancels it. */
        fun schedule(ctx: Context) {
            Maze.init(ctx)
            val minutes = Maze.settings.value.backgroundMinutes
            val js = scheduler(ctx)
            if (minutes <= 0) {
                js.cancel(PERIODIC)
                return
            }
            val period = minutes * 60_000L
            val existing = js.getPendingJob(PERIODIC)
            if (existing != null && existing.intervalMillis == period) return
            val info = JobInfo.Builder(PERIODIC, ComponentName(ctx, RefreshJob::class.java))
                .setPeriodic(period, minOf(period / 3, 5 * 60_000L))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
            runCatching { js.schedule(info) }.onFailure { Log.w(TAG, "periodic refresh not scheduled", it) }
        }

        /** Refresh as soon as there is a network. */
        fun runNow(ctx: Context) {
            val b = JobInfo.Builder(NOW, ComponentName(ctx, RefreshJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) b.setExpedited(true)
            runCatching { scheduler(ctx).schedule(b.build()) }.onFailure {
                Log.w(TAG, "expedited refresh refused", it)
                // Out of expedited quota: a regular job still runs within moments.
                runCatching {
                    scheduler(ctx).schedule(JobInfo.Builder(NOW, ComponentName(ctx, RefreshJob::class.java))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build())
                }.onFailure { e -> Log.w(TAG, "refresh not scheduled", e) }
            }
        }
    }
}

/** Notifications for alert rules that became true. */
object AlertNotifier {
    private const val CHANNEL = "alerts"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.notif_channel_alerts),
                NotificationManager.IMPORTANCE_DEFAULT).apply { description = ctx.getString(R.string.notif_channel_alerts_desc) })
        }
    }

    fun canPost(ctx: Context): Boolean {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    fun send(ctx: Context, title: String, text: String, ruleId: String) {
        if (!canPost(ctx)) return
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(ctx, ruleId.hashCode(),
            Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("mazecrypto://alerts"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setColor(0xFFE9D8B4.toInt())
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ruleId.hashCode(), n) }
    }
}
