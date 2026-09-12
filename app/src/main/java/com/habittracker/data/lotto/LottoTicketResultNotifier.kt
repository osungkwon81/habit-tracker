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
import com.habittracker.data.AppSettingsStore

object LottoTicketResultNotifier {
    private const val channelId = "lotto-ticket-results"
    private const val preferencesName = "lotto-ticket-result-notifications"
    private const val lastNotifiedRoundKey = "last-notified-round"

    fun showIfNeeded(context: Context, result: LottoPurchasedTicketResult) {
        if (!AppSettingsStore.areLotteryResultNotificationsEnabled(context)) return
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
        createChannel(manager)

        val winningCount = result.winningRankCounts.values.sum()
        val detail = if (winningCount > 0) {
            "${result.roundNo}회차 ${result.winningSetCount}세트 ${winningCount}게임 당첨입니다."
        } else {
            "${result.roundNo}회차는 미당첨 회차입니다."
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

    fun showTest(context: Context) {
        if (!AppSettingsStore.areLotteryResultNotificationsEnabled(context)) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        createChannel(manager)
        manager.notify(
            6302,
            Notification.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("복권 결과 알림 테스트")
                .setContentText("복권 결과 알림이 정상적으로 설정되었습니다.")
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build(),
        )
    }

    private fun createChannel(manager: NotificationManager) {
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
    }
}

object PensionLotteryTicketResultNotifier {
    private const val channelId = "pension-lottery-ticket-results"
    private const val preferencesName = "pension-lottery-ticket-result-notifications"
    private const val lastNotifiedRoundKey = "last-notified-round"

    fun showIfNeeded(context: Context, result: PensionLotteryPurchasedNumberResult) {
        if (!AppSettingsStore.areLotteryResultNotificationsEnabled(context)) return
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
                "연금복권 구입번호 당첨 결과",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "저장한 연금복권 구입번호의 당첨 결과를 알립니다."
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )

        val isWinning = result.winningRanks.isNotEmpty()
        val detail = if (isWinning) {
            val rankSummary = result.winningRanks
                .sortedBy { rank -> rank.ordinal }
                .joinToString(" · ") { rank -> rank.label }
            "${result.roundNo}회차 ${result.winningSetCount}세트 $rankSummary 당첨되었습니다."
        } else {
            "${result.roundNo}회차는 미당첨 회차입니다."
        }
        val openAppIntent = PendingIntent.getActivity(
            context,
            6400,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            6401,
            Notification.Builder(context, channelId)
                .setSmallIcon(if (isWinning) android.R.drawable.star_big_on else android.R.drawable.ic_dialog_info)
                .setContentTitle("연금복권 당첨 결과")
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
