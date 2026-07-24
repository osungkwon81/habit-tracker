package com.habittracker.data.stock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.habittracker.HabitTrackerApplication
import com.habittracker.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class StockAutomationService : Service() {
    companion object {
        const val actionStart = "com.habittracker.stock.START"
        const val foregroundChannelId = "stock-monitoring"
        const val alertChannelId = "stock-alerts"
        const val foregroundNotificationId = 4200
        const val requestCodeOpenApp = 4201
        const val servicePrefsName = "stock-monitoring-service-prefs"
        const val finalSyncDateKey = "final-sync-date"
        const val waitingPollMillis = 15 * 60_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, StockAutomationService::class.java).setAction(actionStart),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StockAutomationService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitoringJob: Job? = null
    private var finalSyncAttemptKey: String? = null
    private val koreaZone: ZoneId = ZoneId.of("Asia/Seoul")
    private val marketPrepareTime: LocalTime = LocalTime.of(8, 55)
    private val marketOpenTime: LocalTime = LocalTime.of(9, 0)
    private val marketCloseTime: LocalTime = LocalTime.of(15, 30)
    private val nxtAfterMarketOpenTime: LocalTime = LocalTime.of(15, 40)
    private val nxtAfterMarketCloseTime: LocalTime = LocalTime.of(20, 0)
    private val statusTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    private val repository by lazy {
        (application as HabitTrackerApplication).appContainer.habitRepository
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground("주식 자동화 설정을 확인하고 있습니다.")
        if (monitoringJob?.isActive != true) {
            monitoringJob = serviceScope.launch { monitorStocks() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun monitorStocks() {
        while (serviceScope.isActive) {
            val safety = runCatching { repository.getStockSafetyConfig() }.getOrElse {
                showAlert("주식 모니터링 오류", it.message ?: "안전 설정을 읽지 못했습니다.")
                stopSelf()
                return
            }
            if (!safety.monitoringEnabled) {
                stopSelf()
                return
            }
            val intervalMinutes = safety.monitorIntervalMinutes
            if (intervalMinutes == null || intervalMinutes <= 0) {
                showAlert("주식 모니터링 중지", "모니터링 주기가 설정되지 않았습니다.")
                stopSelf()
                return
            }
            try {
                val now = ZonedDateTime.now(koreaZone)
                val marketWindow = resolveMarketWindow(now)
                if (!marketWindow.isOpen) {
                    marketWindow.finalSyncKey?.let { runFinalOrderSyncOnce(it) }
                    updateForegroundNotification(marketWindow.statusMessage)
                    delay(waitMillis(now, marketWindow.nextCheckAt))
                    continue
                }

                val result = repository.runStockAutomationCycle()
                if (result.skippedReason != null) {
                    updateForegroundNotification(result.skippedReason)
                    delay(1_000L)
                    continue
                }
                result.notices.forEach { notice -> showAlert(notice.title, notice.message) }
                val updatedSafety = repository.getStockSafetyConfig()
                updateForegroundNotification(
                    if (updatedSafety.globalOrderBlocked) {
                        "전체 주문 차단 중 · ${updatedSafety.blockReason.orEmpty()}"
                    } else {
                        "${marketWindow.statusMessage}\n" +
                            "감시 ${result.monitoredProductCount}종목 · 활성 규칙 ${result.activeRuleCount}개 · ${intervalMinutes}분 간격\n" +
                            "최근 확인 ${result.checkedAt.toLocalTime().withNano(0)}"
                    },
                )
                val closeAt = now.toLocalDate().atTime(marketWindow.closeTime ?: marketCloseTime).atZone(koreaZone)
                val regularDelay = intervalMinutes * 60_000L
                val untilClose = Duration.between(now, closeAt).toMillis().coerceAtLeast(1_000L)
                delay(minOf(regularDelay, untilClose))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showAlert("주식 모니터링 오류", error.message ?: "자동화 확인 중 오류가 발생했습니다.")
                updateForegroundNotification("최근 확인 실패 · 앱에서 설정과 KIS 연결을 확인해 주세요.")
                delay(minOf(intervalMinutes * 60_000L, waitingPollMillis))
            }
        }
    }

    private suspend fun resolveMarketWindow(now: ZonedDateTime): MarketWindow {
        val date = now.toLocalDate()
        val time = now.toLocalTime()
        if (date.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) {
            val next = nextWeekdayPrepare(date).atZone(koreaZone)
            return MarketWindow(false, "주말 대기 중 · 다음 장 확인 ${next.format(statusTimeFormatter)}", next)
        }
        if (time < marketPrepareTime) {
            val next = date.atTime(marketPrepareTime).atZone(koreaZone)
            return MarketWindow(false, "장 시작 확인 대기 중 · ${next.format(statusTimeFormatter)}", next)
        }
        if (!repository.isKisMarketOpenDay(date)) {
            val next = nextWeekdayPrepare(date).atZone(koreaZone)
            return MarketWindow(false, "휴장일 대기 중 · 다음 장 확인 ${next.format(statusTimeFormatter)}", next)
        }
        if (time < marketOpenTime) {
            val next = date.atTime(marketOpenTime).atZone(koreaZone)
            return MarketWindow(false, "개장 대기 중 · ${next.format(statusTimeFormatter)}", next)
        }
        if (time < marketCloseTime) {
            return MarketWindow(true, "KRX·NXT 통합장 감시 중", now, closeTime = marketCloseTime)
        }
        if (time < nxtAfterMarketOpenTime) {
            val next = date.atTime(nxtAfterMarketOpenTime).atZone(koreaZone)
            return MarketWindow(
                isOpen = false,
                statusMessage = "정규장 종료 · NXT 애프터마켓 대기 ${next.format(statusTimeFormatter)}",
                nextCheckAt = next,
                finalSyncKey = "$date-KRX",
            )
        }
        if (time < nxtAfterMarketCloseTime) {
            return MarketWindow(true, "NXT 애프터마켓 감시 중", now, closeTime = nxtAfterMarketCloseTime)
        }
        val next = nextWeekdayPrepare(date).atZone(koreaZone)
        return MarketWindow(
            isOpen = false,
            statusMessage = "NXT 애프터마켓 종료 · 다음 장 확인 ${next.format(statusTimeFormatter)}",
            nextCheckAt = next,
            finalSyncKey = "$date-NXT",
        )
    }

    private suspend fun runFinalOrderSyncOnce(syncKey: String) {
        val prefs = getSharedPreferences(servicePrefsName, MODE_PRIVATE)
        if (prefs.getString(finalSyncDateKey, null) == syncKey || finalSyncAttemptKey == syncKey) return
        finalSyncAttemptKey = syncKey
        runCatching { repository.syncStockOrderExecutions() }
            .onSuccess { prefs.edit().putString(finalSyncDateKey, syncKey).apply() }
            .onFailure { error ->
                showAlert("장 종료 체결 확인 실패", error.message ?: "체결 내역을 확인하지 못했습니다.")
            }
    }

    private fun nextWeekdayPrepare(date: LocalDate): java.time.LocalDateTime {
        var nextDate = date.plusDays(1)
        while (nextDate.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) {
            nextDate = nextDate.plusDays(1)
        }
        return nextDate.atTime(marketPrepareTime)
    }

    private fun waitMillis(now: ZonedDateTime, nextCheckAt: ZonedDateTime): Long =
        Duration.between(now, nextCheckAt).toMillis().coerceAtLeast(1_000L)

    private data class MarketWindow(
        val isOpen: Boolean,
        val statusMessage: String,
        val nextCheckAt: ZonedDateTime,
        val closeTime: LocalTime? = null,
        val finalSyncKey: String? = null,
    )

    private fun startInForeground(message: String) {
        val notification = buildNotification(
            channelId = foregroundChannelId,
            title = "주식 자동화 모니터링",
            message = message,
            ongoing = true,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                foregroundNotificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(foregroundNotificationId, notification)
        }
    }

    private fun updateForegroundNotification(message: String) {
        notificationManager().notify(
            foregroundNotificationId,
            buildNotification(foregroundChannelId, "주식 자동화 모니터링", message, ongoing = true),
        )
    }

    private fun showAlert(title: String, message: String) {
        notificationManager().notify(
            (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
            buildNotification(alertChannelId, title, message, ongoing = false),
        )
    }

    private fun buildNotification(
        channelId: String,
        title: String,
        message: String,
        ongoing: Boolean,
    ): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            requestCodeOpenApp,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(openAppIntent)
            .setOngoing(ongoing)
            .setCategory(if (ongoing) Notification.CATEGORY_SERVICE else Notification.CATEGORY_ALARM)
            .setOnlyAlertOnce(ongoing)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
    }

    private fun createNotificationChannels() {
        notificationManager().createNotificationChannel(
            NotificationChannel(
                foregroundChannelId,
                "주식 자동화 상태",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
        notificationManager().createNotificationChannel(
            NotificationChannel(
                alertChannelId,
                "주식 손절·익절 알림",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

}
