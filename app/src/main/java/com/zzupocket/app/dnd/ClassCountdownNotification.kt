package com.zzupocket.app.dnd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.zzupocket.app.MainActivity
import com.zzupocket.app.R
import com.zzupocket.app.ui.nav.AppRoutes
import java.time.Duration
import java.time.Instant

internal class ClassCountdownNotification(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun canPost(): Boolean = manager.areNotificationsEnabled() &&
        manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    fun show(countdown: ClassCountdown?, now: Instant) {
        if (countdown == null || !canPost()) {
            manager.cancel(PRE_CLASS_ID)
            manager.cancel(IN_CLASS_ID)
            return
        }
        val id = if (countdown.inClass) IN_CLASS_ID else PRE_CLASS_ID
        manager.cancel(if (countdown.inClass) PRE_CLASS_ID else IN_CLASS_ID)
        val deadline = countdown.deadline.toEpochMilli()
        val title = if (countdown.inClass) "距离下课" else "距离上课"
        if (manager.activeNotifications.any {
            it.id == id && it.notification.`when` == deadline &&
                it.notification.extras.getCharSequence(Notification.EXTRA_TITLE) == title
        }) return

        manager.createNotificationChannel(NotificationChannel(CHANNEL, "上课倒计时", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(AppRoutes.EXTRA_ROUTE, AppRoutes.SCHEDULE), PendingIntent.FLAG_IMMUTABLE)
        val start = countdown.lesson.start.toEpochMilli()
        val hide = PendingIntent.getBroadcast(context, id, Intent(context, ClassDndReceiver::class.java)
            .setAction(ACTION_HIDE)
            .setData(Uri.parse("class-countdown://hide/$start")), PendingIntent.FLAG_IMMUTABLE)
        // Android can send the delete intent when a notification reaches its own timeout.
        val dismiss = PendingIntent.getBroadcast(context, id, Intent(context, ClassDndReceiver::class.java)
            .setAction(ACTION_DISMISS)
            .setData(Uri.parse("class-countdown://dismiss/$start/$deadline")), PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle(title)
            .setContentIntent(open)
            .setDeleteIntent(dismiss)
            .addAction(Notification.Action.Builder(null, "隐藏本节", hide).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STOPWATCH)
            .setWhen(deadline)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setTimeoutAfter(Duration.between(now, countdown.deadline).toMillis().coerceAtLeast(1))
        if (Build.VERSION.SDK_INT >= 36) {
            // Public notification extra. The system decides whether to promote the countdown.
            builder.addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
        }
        // Notification permission can be revoked between the check and posting.
        try {
            manager.notify(id, builder.build())
        } catch (_: SecurityException) {
            manager.cancel(id)
        }
    }

    companion object {
        const val CHANNEL = "class_countdown"
        const val ACTION_HIDE = "com.whpupocket.app.HIDE_CLASS_COUNTDOWN"
        const val ACTION_DISMISS = "com.whpupocket.app.DISMISS_CLASS_COUNTDOWN"
        private const val PRE_CLASS_ID = 4320
        private const val IN_CLASS_ID = 4321
    }
}
