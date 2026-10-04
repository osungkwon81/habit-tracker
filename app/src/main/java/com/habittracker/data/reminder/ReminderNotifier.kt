package com.habittracker.data.reminder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.habittracker.MainActivity
import com.habittracker.data.local.entity.ReminderEntity

object ReminderNotifier {
    private const val channelId = "reminders"
    private const val notificationIdBase = 10_000

    fun isVisible(context: Context, reminderId: Long): Boolean {
        val notificationId = notificationId(reminderId)
        return context.getSystemService(NotificationManager::class.java)
            .activeNotifications
            .any { notification -> notification.id == notificationId }
    }

    fun show(context: Context, reminder: ReminderEntity) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, "리마인더", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "완료 체크 전까지 설정한 간격으로 다시 알립니다."
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
        val openApp = PendingIntent.getActivity(
            context,
            notificationId(reminder.id),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val complete = PendingIntent.getBroadcast(
            context,
            notificationId(reminder.id),
            Intent(context, ReminderCompleteReceiver::class.java)
                .putExtra(ReminderWorker.reminderIdKey, reminder.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismissed = PendingIntent.getBroadcast(
            context,
            notificationId(reminder.id),
            Intent(context, ReminderDismissReceiver::class.java)
                .putExtra(ReminderWorker.reminderIdKey, reminder.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            notificationId(reminder.id),
            Notification.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("리마인더")
                .setContentText(reminder.title)
                .setStyle(Notification.BigTextStyle().bigText(reminder.title))
                .setContentIntent(openApp)
                .setDeleteIntent(dismissed)
                .addAction(Notification.Action.Builder(null, "☐ 완료 체크", complete).build())
                .setAutoCancel(false)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build(),
        )
    }

    fun cancel(context: Context, reminderId: Long) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(reminderId))
    }

    private fun notificationId(reminderId: Long): Int =
        notificationIdBase + (reminderId % (Int.MAX_VALUE - notificationIdBase)).toInt()
}
