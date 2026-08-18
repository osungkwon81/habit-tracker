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

object LottoTicketResultNotifier {
    private const val channelId = "lotto-ticket-results"
    private const val preferencesName = "lotto-ticket-result-notifications"
    private const val lastNotifiedRoundKey = "last-notified-round"

    fun showIfNeeded(context: Context, result: LottoPurchasedTicketResult) {
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        if (preferences.getInt(lastNotifiedRoundKey, -1) >= result.roundNo) return
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
                "로또 구매번호 당첨 결과",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "QR 등록표와 구매완료 저장번호의 당첨 결과를 알립니다."
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )

        val winningCount = result.winningRankCounts.values.sum()
        val sourceSummary = buildList {
            if (result.physicalQrTicketCount > 0) add("QR ${result.physicalQrTicketCount}게임")
            val savedTicketCount = result.totalTicketCount - result.physicalQrTicketCount
            if (savedTicketCount > 0) add("저장번호 ${savedTicketCount}게임")
        }.joinToString(" · ")
        val detail = if (winningCount > 0) {
            val rankSummary = result.winningRankCounts.entries
                .sortedBy { entry -> entry.key }
                .joinToString(" · ") { (rank, count) -> "${rank}등 ${count}게임" }
            "$sourceSummary 중 $rankSummary 당첨입니다."
        } else {
            "$sourceSummary 번호를 확인했습니다. 최고 ${result.maximumMatchCount}개 일치로 미당첨입니다."
        }
        val openAppIntent = PendingIntent.getActivity(
            context,
            6300,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            6301,
            Notification.Builder(context, channelId)
                .setSmallIcon(if (winningCount > 0) android.R.drawable.star_big_on else android.R.drawable.ic_dialog_info)
                .setContentTitle("${result.roundNo}회 로또 구매번호 확인")
                .setContentText(detail)
                .setStyle(Notification.BigTextStyle().bigText(detail))
                .setContentIntent(openAppIntent)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build(),
        )
        preferences.edit().putInt(lastNotifiedRoundKey, result.roundNo).apply()
    }
}
