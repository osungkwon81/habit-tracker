package com.habittracker.data.lotto

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

object LotteryDrawSyncNotifier {
    private const val channelId = "lottery-sync-errors"

    fun showFinalFailure(context: Context, product: LotteryProduct, message: String) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                channelId,
                "복권 당첨번호 동기화 오류",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "공식 당첨번호를 가져오지 못했을 때 원인을 알립니다."
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
        val openAppIntent = PendingIntent.getActivity(
            context,
            product.ordinal + 6100,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            product.ordinal + 6200,
            Notification.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("${product.label} 자동 동기화 실패")
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setContentIntent(openAppIntent)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ERROR)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build(),
        )
    }
}
