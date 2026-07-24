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
import android.util.Log
import androidx.core.content.ContextCompat
import com.habittracker.HabitTrackerApplication
import com.habittracker.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class StockAutomationService : Service() {
    companion object {
        const val actionStart = "com.habittracker.stock.START"
        const val foregroundChannelId = "stock-monitoring"
        const val alertChannelId = "stock-alerts"
        const val foregroundNotificationId = 4200
        const val requestCodeOpenApp = 4201
        const val servicePrefsName = "stock-monitoring-service-prefs"
        const val finalSyncDateKey = "final-sync-date"
        const val idlePollMillis = 60_000L
        const val realtimeReconnectDelayMillis = 5_000L
        const val maxRealtimeReconnectDelayMillis = 60_000L
        const val realtimeErrorAlertCooldownMillis = 15 * 60_000L
        const val realtimeEvaluationCooldownMillis = 100L
        const val approvalKeyRefreshHours = 23L
        const val logTag = "StockAutomation"

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
    private val realtimeClient = KisRealtimeStockClient()
    private var cachedApprovalKey: CachedApprovalKey? = null
    private var lastRealtimeErrorAlertAtMillis: Long = 0L
    private var lastFinalSyncErrorAlertAtMillis: Long = 0L
    private var realtimeReconnectAttempt: Int = 0
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
        startInForeground("실시간 시세 연결을 준비하고 있습니다.")
        if (monitoringJob?.isActive != true) {
            monitoringJob = serviceScope.launch { monitorStocks() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        realtimeClient.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun monitorStocks() {
        while (serviceScope.isActive) {
            val safety = runCatching { repository.getStockSafetyConfig() }.getOrElse {
                showErrorAlert(
                    eventType = "MONITORING_CONFIG_FAILED",
                    title = "주식 모니터링 오류",
                    message = it.message ?: "안전 설정을 읽지 못했습니다.",
                )
                stopSelf()
                return
            }
            if (!safety.monitoringEnabled) {
                stopSelf()
                return
            }
            val intervalMinutes = safety.monitorIntervalMinutes
            if (intervalMinutes == null || intervalMinutes <= 0) {
                showErrorAlert(
                    eventType = "MONITORING_CONFIG_FAILED",
                    title = "주식 모니터링 중지",
                    message = "모니터링 주기가 설정되지 않았습니다.",
                )
                stopSelf()
                return
            }
            try {
                val now = ZonedDateTime.now(koreaZone)
                val marketWindow = resolveMarketWindow(now)
                if (!marketWindow.isOpen) {
                    val finalSyncSucceeded = marketWindow.finalSyncKey
                        ?.let { runFinalOrderSyncOnce(it) }
                        ?: true
                    updateForegroundNotification(marketWindow.statusMessage)
                    val waitMillis = waitMillis(now, marketWindow.nextCheckAt)
                    delay(
                        if (finalSyncSucceeded) {
                            waitMillis
                        } else {
                            minOf(idlePollMillis, waitMillis)
                        },
                    )
                    continue
                }
                runRealtimeMarketSession(
                    marketWindow = marketWindow,
                    refreshIntervalMillis = intervalMinutes * 60_000L,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportRealtimeFailure(error)
                delay(nextRealtimeReconnectDelay())
            }
        }
    }

    private suspend fun runRealtimeMarketSession(
        marketWindow: MarketWindow,
        refreshIntervalMillis: Long,
    ) {
        val market = marketWindow.market ?: throw IllegalStateException("실시간 감시 시장을 확인하지 못했습니다.")
        var snapshot = repository.prepareStockRealtimeMonitoring(market)
        snapshot.notices.forEach { notice -> showAlert(notice.title, notice.message) }
        if (snapshot.globalOrderBlocked) {
            updateForegroundNotification("전체 주문 차단 중 · ${snapshot.blockReason.orEmpty()}")
            delay(refreshIntervalMillis.coerceAtMost(idlePollMillis))
            return
        }
        if (snapshot.positions.isEmpty()) {
            updateForegroundNotification("실시간 감시 대기 중 · 활성 규칙이 있는 보유 종목이 없습니다.")
            delay(refreshIntervalMillis.coerceAtMost(idlePollMillis))
            return
        }

        val productCodes = snapshot.positions.map(StockRealtimePosition::productCode).toSet()
        val approvalKey = getRealtimeApprovalKey()
        val signalChannel = Channel<RealtimeSessionSignal>(Channel.UNLIMITED)
        val pendingPrices = ConcurrentHashMap<String, Long>()
        val priceSignalPending = AtomicBoolean(false)
        val ignoredRuleIds = mutableSetOf<Long>()
        val referenceHighPrices = mutableMapOf<Long, Long>()
        var positionsByCode = snapshot.positions.associateBy(StockRealtimePosition::productCode)
        var nextRefreshAt = System.currentTimeMillis() + refreshIntervalMillis
        val closeAt = ZonedDateTime.now(koreaZone).toLocalDate()
            .atTime(marketWindow.closeTime ?: marketCloseTime)
            .atZone(koreaZone)

        suspend fun evaluatePrice(productCode: String, currentPrice: Long) {
            val position = positionsByCode[productCode] ?: return
            val result = repository.runStockAutomationRealtimeTick(
                position = position,
                currentPrice = currentPrice,
                market = market,
                ignoredRuleIds = ignoredRuleIds,
                referenceHighPrices = referenceHighPrices,
            )
            ignoredRuleIds += result.triggeredRuleIds
            result.referenceHighPrices.forEach { (ruleId, high) ->
                referenceHighPrices[ruleId] = maxOf(referenceHighPrices[ruleId] ?: 0L, high)
            }
            result.notices.forEach { notice -> showAlert(notice.title, notice.message) }
        }

        snapshot.positions.forEach { position ->
            position.initialPrice?.let { price -> evaluatePrice(position.productCode, price) }
        }

        val connection = realtimeClient.connect(
            approvalKey = approvalKey,
            market = market,
            productCodes = productCodes,
        ) { event ->
            when (event) {
                is KisRealtimeEvent.Price -> {
                    pendingPrices[event.value.productCode] = event.value.currentPrice
                    if (priceSignalPending.compareAndSet(false, true)) {
                        signalChannel.trySend(RealtimeSessionSignal.PricesAvailable)
                    }
                }
                else -> signalChannel.trySend(RealtimeSessionSignal.Connection(event))
            }
        }

        try {
            while (serviceScope.isActive) {
                val now = ZonedDateTime.now(koreaZone)
                if (!now.isBefore(closeAt)) return
                if (System.currentTimeMillis() >= nextRefreshAt) {
                    repository.persistStockRealtimeHighPrices(referenceHighPrices)
                    snapshot = repository.prepareStockRealtimeMonitoring(market)
                    snapshot.notices.forEach { notice -> showAlert(notice.title, notice.message) }
                    if (snapshot.globalOrderBlocked) {
                        updateForegroundNotification("전체 주문 차단 중 · ${snapshot.blockReason.orEmpty()}")
                        return
                    }
                    val refreshedProductCodes = snapshot.positions.map(StockRealtimePosition::productCode).toSet()
                    if (refreshedProductCodes != productCodes) return
                    positionsByCode = snapshot.positions.associateBy(StockRealtimePosition::productCode)
                    ignoredRuleIds.clear()
                    nextRefreshAt = System.currentTimeMillis() + refreshIntervalMillis
                    continue
                }
                val untilRefresh = (nextRefreshAt - System.currentTimeMillis()).coerceAtLeast(1L)
                val untilClose = Duration.between(now, closeAt).toMillis().coerceAtLeast(1L)
                val signal = withTimeoutOrNull(minOf(untilRefresh, untilClose)) {
                    signalChannel.receive()
                }
                if (signal == null) {
                    continue
                }
                when (signal) {
                    RealtimeSessionSignal.PricesAvailable -> {
                        priceSignalPending.set(false)
                        val prices = pendingPrices.entries.associate { it.key to it.value }
                        prices.forEach { (productCode, currentPrice) ->
                            pendingPrices.remove(productCode, currentPrice)
                            evaluatePrice(productCode, currentPrice)
                        }
                        delay(realtimeEvaluationCooldownMillis)
                    }
                    is RealtimeSessionSignal.Connection -> when (val event = signal.event) {
                        KisRealtimeEvent.Connected -> {
                            realtimeReconnectAttempt = 0
                            updateForegroundNotification("${marketWindow.statusMessage} · 실시간 시세 연결됨")
                        }
                        is KisRealtimeEvent.Failure -> throw IOException(
                            event.cause.message ?: "KIS 실시간 시세 연결에 실패했습니다.",
                            event.cause,
                        )
                        is KisRealtimeEvent.Closed -> throw IOException(event.reason)
                        is KisRealtimeEvent.Price -> Unit
                    }
                }
            }
        } finally {
            connection.close()
            signalChannel.close()
            repository.persistStockRealtimeHighPrices(referenceHighPrices)
        }
    }

    private suspend fun getRealtimeApprovalKey(): String {
        val now = ZonedDateTime.now(koreaZone)
        val cached = cachedApprovalKey
        if (cached != null && Duration.between(cached.issuedAt, now).toHours() < approvalKeyRefreshHours) {
            return cached.value
        }
        return repository.issueKisRealtimeApprovalKey().also { value ->
            cachedApprovalKey = CachedApprovalKey(value, now)
        }
    }

    private suspend fun reportRealtimeFailure(error: Exception) {
        val message = error.message ?: "실시간 시세 연결 중 오류가 발생했습니다."
        updateForegroundNotification("실시간 연결 재시도 중 · 앱에서 KIS 연결을 확인해 주세요.")
        val now = System.currentTimeMillis()
        if (now - lastRealtimeErrorAlertAtMillis >= realtimeErrorAlertCooldownMillis) {
            showErrorAlert(
                eventType = "REALTIME_MONITORING_FAILED",
                title = "주식 실시간 모니터링 오류",
                message = message,
            )
            lastRealtimeErrorAlertAtMillis = now
        }
        if (
            message.contains("접속키") ||
            message.contains("approval", ignoreCase = true) ||
            message.contains("auth", ignoreCase = true)
        ) {
            cachedApprovalKey = null
        }
    }

    private fun nextRealtimeReconnectDelay(): Long {
        val multiplier = 1L shl realtimeReconnectAttempt.coerceAtMost(4)
        realtimeReconnectAttempt = (realtimeReconnectAttempt + 1).coerceAtMost(4)
        return (realtimeReconnectDelayMillis * multiplier).coerceAtMost(maxRealtimeReconnectDelayMillis)
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
            return MarketWindow(
                isOpen = true,
                statusMessage = "KRX·NXT 통합장 감시 중",
                nextCheckAt = now,
                closeTime = marketCloseTime,
                market = KisStockMarket.UNIFIED,
            )
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
            return MarketWindow(
                isOpen = true,
                statusMessage = "NXT 애프터마켓 감시 중",
                nextCheckAt = now,
                closeTime = nxtAfterMarketCloseTime,
                market = KisStockMarket.NXT,
            )
        }
        val next = nextWeekdayPrepare(date).atZone(koreaZone)
        return MarketWindow(
            isOpen = false,
            statusMessage = "NXT 애프터마켓 종료 · 다음 장 확인 ${next.format(statusTimeFormatter)}",
            nextCheckAt = next,
            finalSyncKey = "$date-NXT",
        )
    }

    private suspend fun runFinalOrderSyncOnce(syncKey: String): Boolean {
        val prefs = getSharedPreferences(servicePrefsName, MODE_PRIVATE)
        if (prefs.getString(finalSyncDateKey, null) == syncKey || finalSyncAttemptKey == syncKey) return true
        finalSyncAttemptKey = syncKey
        return try {
            repository.syncStockOrderExecutions()
            prefs.edit().putString(finalSyncDateKey, syncKey).apply()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            finalSyncAttemptKey = null
            val now = System.currentTimeMillis()
            if (now - lastFinalSyncErrorAlertAtMillis >= realtimeErrorAlertCooldownMillis) {
                showErrorAlert(
                    eventType = "ORDER_SYNC_FAILED",
                    title = "장 종료 체결 확인 실패",
                    message = error.message ?: "체결 내역을 확인하지 못했습니다.",
                )
                lastFinalSyncErrorAlertAtMillis = now
            }
            false
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
        val market: KisStockMarket? = null,
    )

    private sealed interface RealtimeSessionSignal {
        data object PricesAvailable : RealtimeSessionSignal
        data class Connection(val event: KisRealtimeEvent) : RealtimeSessionSignal
    }

    private data class CachedApprovalKey(
        val value: String,
        val issuedAt: ZonedDateTime,
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

    private suspend fun showErrorAlert(
        eventType: String,
        title: String,
        message: String,
    ) {
        runCatching {
            repository.saveStockErrorEvent(
                eventType = eventType,
                title = title,
                message = message,
            )
        }.onFailure { error ->
            Log.e(logTag, "Failed to persist stock error event. (eventType=$eventType)", error)
        }
        showAlert(title, message)
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
