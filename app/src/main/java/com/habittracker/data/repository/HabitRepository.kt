package com.habittracker.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.habittracker.data.TaskColorPalette
import com.habittracker.data.card.CardHistorySeedData
import com.habittracker.data.local.HabitDao
import com.habittracker.data.local.HabitTrackerDatabase
import com.habittracker.data.local.HabitTrackerDatabaseProtector
import com.habittracker.data.local.ValueType
import com.habittracker.data.local.entity.DailyDiaryEntity
import com.habittracker.data.local.entity.DailyRecordEntity
import com.habittracker.data.local.entity.DailyRecordItemEntity
import com.habittracker.data.local.entity.CardHistoryEntity
import com.habittracker.data.local.entity.LottoDrawEntity
import com.habittracker.data.local.entity.LottoGenerationConfigEntity
import com.habittracker.data.local.entity.LottoPurchaseEntity
import com.habittracker.data.local.entity.LottoTicketEntity
import com.habittracker.data.local.entity.LottoWinningEntity
import com.habittracker.data.local.entity.LottoWinningStatEntity
import com.habittracker.data.local.entity.LottoWinningStatRoundEntity
import com.habittracker.data.local.entity.KisApiConfigEntity
import com.habittracker.data.local.entity.MemoNoteEntity
import com.habittracker.data.local.entity.PlantEntity
import com.habittracker.data.local.entity.StockAutomationEventEntity
import com.habittracker.data.local.entity.StockExitRuleEntity
import com.habittracker.data.local.entity.StockOrderEntity
import com.habittracker.data.local.entity.StockSafetyConfigEntity
import com.habittracker.data.local.entity.StockSellAllocationEntity
import com.habittracker.data.local.entity.StockTargetAllocationEntity
import com.habittracker.data.local.entity.TaskItemMasterEntity
import com.habittracker.data.local.entity.VocabularyWordEntity
import com.habittracker.data.local.model.DiarySearchRow
import com.habittracker.data.local.model.DiarySummaryRow
import com.habittracker.data.local.model.DailyTaskStatRow
import com.habittracker.data.local.model.LottoPeriodStatRow
import com.habittracker.data.local.model.MonthlyStatRow
import com.habittracker.data.local.model.RecordDetailRow
import com.habittracker.data.local.model.RecordSummaryRow
import com.habittracker.data.lotto.LottoSeedData
import com.habittracker.data.lotto.LottoGeneratedTicket
import com.habittracker.data.lotto.LottoControlComparison
import com.habittracker.data.lotto.LottoNumberGenerator
import com.habittracker.data.lotto.LottoPerformanceAnalyzer
import com.habittracker.data.lotto.LottoPerformanceSample
import com.habittracker.data.lotto.LottoScorePerformance
import com.habittracker.data.security.AndroidKeystoreStringCipher
import com.habittracker.data.stock.KisApiConfig
import com.habittracker.data.stock.KisBalanceStock
import com.habittracker.data.stock.KisCashOrderDraft
import com.habittracker.data.stock.KisDomesticStockClient
import com.habittracker.data.stock.KisEnvironment
import com.habittracker.data.stock.KisMarketCapStock
import com.habittracker.data.stock.KisOrderExecution
import com.habittracker.data.stock.KisOrderSide
import com.habittracker.data.stock.KisStockMarket
import com.habittracker.data.stock.StockAutomationCycleResult
import com.habittracker.data.stock.StockAutomationNotice
import com.habittracker.data.stock.StockBulkSellFailure
import com.habittracker.data.stock.StockBulkSellResult
import com.habittracker.data.stock.StockBuyLotRow
import com.habittracker.data.stock.StockExitRuleType
import com.habittracker.data.stock.StockJournalAnalysis
import com.habittracker.data.stock.StockOrderSource
import com.habittracker.data.stock.StockOrderStatus
import com.habittracker.data.stock.StockRebalanceLine
import com.habittracker.data.stock.StockRealtimeMonitoringSnapshot
import com.habittracker.data.stock.StockRealtimePosition
import com.habittracker.data.stock.StockRealtimeTickResult
import com.habittracker.data.stock.StockRuleAction
import com.habittracker.data.stock.STOCK_CRASH_GUARD_BLOCK_PREFIX
import com.habittracker.data.stock.isCrashGuardOrderBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.net.SocketTimeoutException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

class HabitRepository(
    context: Context,
    private val database: HabitTrackerDatabase,
    private val databaseProtector: HabitTrackerDatabaseProtector,
    private val habitDao: HabitDao,
) {
    private companion object {
        const val lottoRoundNotePrefix = "ROUND:"
        const val lottoSetNoteSeparator = "|SET:"
        const val randomControlSource = "무작위 대조군"
        const val randomControlSetId = "CONTROL"
        const val lottoDrawSourceSeed = "SEED"
        const val lottoDrawSourceManual = "MANUAL"
        const val maxSavedLottoSetCount = 3
        const val taskColorPrefsName = "task-color-prefs"
        const val cardPrefsName = "card-prefs"
        const val lottoPrefsName = "lotto-prefs"
        const val kisMarketCapPrefsName = "kis-market-cap-prefs"
        const val kisMarketCalendarPrefsName = "kis-market-calendar-prefs"
        const val stockExecutionPrefsName = "stock-execution-prefs"
        const val kisMarketCapCacheDateKey = "cache-date"
        const val kisMarketCapCacheItemsKey = "cache-items"
        const val kisMarketOpenDateKey = "market-open-date"
        const val kisMarketOpenValueKey = "market-open-value"
        const val lastStockExecutionSyncDateKey = "last-sync-date"
        const val cardPaymentDayKey = "card-payment-day"
        const val cardSeedVersionKey = "card-seed-version"
        const val currentCardSeedVersion = 2
        const val lottoStatVersionKey = "lotto-winning-stat-version"
        const val currentLottoStatVersion = 6
        const val stockSafetyConfigId = 1
        const val stockLimitOrderCode = "00"
        const val stockMarketOrderCode = "01"
        val generatedLottoSources = setOf("균형형", "분산형")
    }

    val managedTaskValueTypes: List<ValueType> = listOf(ValueType.NUMBER, ValueType.EXERCISE)

    private val taskColorPrefs = context.getSharedPreferences(taskColorPrefsName, Context.MODE_PRIVATE)
    private val cardPrefs = context.getSharedPreferences(cardPrefsName, Context.MODE_PRIVATE)
    private val lottoPrefs = context.getSharedPreferences(lottoPrefsName, Context.MODE_PRIVATE)
    private val kisMarketCapPrefs = context.getSharedPreferences(kisMarketCapPrefsName, Context.MODE_PRIVATE)
    private val kisMarketCalendarPrefs = context.getSharedPreferences(kisMarketCalendarPrefsName, Context.MODE_PRIVATE)
    private val stockExecutionPrefs = context.getSharedPreferences(stockExecutionPrefsName, Context.MODE_PRIVATE)
    private val kisMarketCapCacheMutex = Mutex()
    private val kisAccessTokenMutex = Mutex()
    private val stockOrderMutex = Mutex()
    private val stockAutomationMutex = Mutex()
    private val kisConfigCipher = AndroidKeystoreStringCipher()
    private val kisDomesticStockClient = KisDomesticStockClient()
    private val regularMarketOpenTime = LocalTime.of(9, 0)
    private val regularMarketCloseTime = LocalTime.of(15, 30)
    private val nxtAfterMarketOpenTime = LocalTime.of(15, 40)
    private val nxtAfterMarketCloseTime = LocalTime.of(20, 0)

    private suspend fun <T> persistChange(block: suspend () -> T): T = withContext(NonCancellable + Dispatchers.IO) {
        val result = block()
        databaseProtector.requestBackup(database)
        result
    }

    fun observeLottoDraws(roundNo: Int?, limit: Int): Flow<List<LottoDrawEntity>> =
        habitDao.observeLottoDraws(roundNo, limit)

    fun observeCardHistories(): Flow<List<CardHistoryEntity>> =
        habitDao.observeCardHistories()

    fun observeSavedLottoTickets(limit: Int): Flow<List<LottoTicketEntity>> =
        habitDao.observeSavedLottoTickets(limit)

    fun observeAllSavedLottoTickets(): Flow<List<LottoTicketEntity>> =
        habitDao.observeAllSavedLottoTickets()

    fun observeLottoScorePerformances(): Flow<List<LottoScorePerformance>> = combine(
        habitDao.observeScoredPurchasedLottoTickets(),
        habitDao.observeAllLottoDraws(),
    ) { tickets, draws ->
        val drawMap = draws.associateBy(LottoDrawEntity::roundNo)
        val samples = tickets.mapNotNull { ticket ->
            val roundNo = ticket.roundNo ?: ticket.note?.let(::extractLottoRoundNo) ?: return@mapNotNull null
            val draw = drawMap[roundNo] ?: return@mapNotNull null
            val totalScore = ticket.analysisScore ?: return@mapNotNull null
            LottoPerformanceSample(
                roundNo = roundNo,
                sourceLabel = normalizeWinningSource(ticket.sourceLabel),
                generationVersion = ticket.generationVersion,
                totalScore = totalScore,
                dataScore = ticket.dataScore,
                patternScore = ticket.patternScore,
                distributionScore = ticket.distributionScore,
                avoidanceScore = ticket.avoidanceScore,
                validationScore = ticket.validationScore,
                matchCount = ticket.numbers().count(draw.numbers()::contains),
            )
        }
        LottoPerformanceAnalyzer.analyze(samples)
    }

    fun observeSavedLottoTicketsByRound(roundNo: Int): Flow<List<LottoTicketEntity>> =
        habitDao.observeSavedLottoTicketsByRound(roundNo)

    fun observeLottoPurchases(limit: Int): Flow<List<LottoPurchaseEntity>> =
        habitDao.observeLottoPurchases(limit)

    fun observeLottoWinnings(limit: Int): Flow<List<LottoWinningEntity>> =
        habitDao.observeLottoWinnings(limit)

    fun observeTotalLottoPurchaseAmount(): Flow<Long> =
        habitDao.observeTotalLottoPurchaseAmount()

    fun observeTotalLottoWinningAmount(): Flow<Long> =
        habitDao.observeTotalLottoWinningAmount()

    fun observeLottoWinningStats(): Flow<List<LottoWinningStatEntity>> =
        habitDao.observeLottoWinningStats()

    fun observeLottoControlComparisons(): Flow<List<LottoControlComparison>> =
        habitDao.observeAllLottoWinningStatRounds().map(::buildLottoControlComparisons)

    fun observeLottoWeeklyStats(limit: Int): Flow<List<LottoPeriodStatRow>> =
        habitDao.observeLottoWeeklyStats(limit)

    fun observeLottoMonthlyStats(limit: Int): Flow<List<LottoPeriodStatRow>> =
        habitDao.observeLottoMonthlyStats(limit)

    fun observeLottoYearlyStats(limit: Int): Flow<List<LottoPeriodStatRow>> =
        habitDao.observeLottoYearlyStats(limit)

    fun observeMemoNotes(limit: Int): Flow<List<MemoNoteEntity>> =
        habitDao.observeMemoNotes(limit)

    fun observeMemoNotesByQuery(query: String, limit: Int): Flow<List<MemoNoteEntity>> =
        habitDao.observeMemoNotesByQuery(query.trim(), limit)

    fun observePlants(): Flow<List<PlantEntity>> =
        habitDao.observePlants()

    fun observeVocabularyWords(limit: Int): Flow<List<VocabularyWordEntity>> =
        habitDao.observeVocabularyWords(limit)

    fun observeVocabularyWordsByQuery(query: String, limit: Int): Flow<List<VocabularyWordEntity>> =
        habitDao.observeVocabularyWordsByQuery(query.trim(), limit)

    fun observeActiveTaskItems(): Flow<List<TaskItemMasterEntity>> = habitDao.observeActiveTaskItems()

    fun observeMonthlySummaries(startDate: LocalDate, endDate: LocalDate): Flow<List<RecordSummaryRow>> =
        habitDao.observeMonthlySummaries(startDate, endDate)

    fun observeMonthlyDiarySummaries(startDate: LocalDate, endDate: LocalDate): Flow<List<DiarySummaryRow>> =
        habitDao.observeMonthlyDiarySummaries(startDate, endDate)

    fun observeMonthlyStats(startDate: LocalDate, endDate: LocalDate): Flow<List<MonthlyStatRow>> =
        habitDao.observeMonthlyStats(startDate, endDate)

    fun observeDailyTaskStats(startDate: LocalDate, endDate: LocalDate): Flow<List<DailyTaskStatRow>> =
        habitDao.observeDailyTaskStats(startDate, endDate)

    suspend fun getDailyRecord(recordDate: LocalDate): DailyRecordEntity? =
        habitDao.getDailyRecordByDate(recordDate)

    suspend fun getDiary(recordDate: LocalDate): DailyDiaryEntity? =
        habitDao.getDiaryByDate(recordDate)

    fun observeDiaryList(limit: Int): Flow<List<DiarySearchRow>> =
        habitDao.observeDiaryList(limit)

    fun observeDiaryListByQuery(query: String, limit: Int): Flow<List<DiarySearchRow>> =
        habitDao.observeDiaryListByQuery(query.trim(), limit)

    suspend fun getMemoNote(memoId: Long): MemoNoteEntity? =
        habitDao.getMemoNoteById(memoId)

    suspend fun getPlant(plantId: Long): PlantEntity? =
        habitDao.getPlantById(plantId)

    suspend fun getKisApiConfig(environment: KisEnvironment): KisApiConfig? = withContext(Dispatchers.IO) {
        habitDao.getKisApiConfig(environment.apiValue)?.toKisApiConfig(kisConfigCipher)
    }

    suspend fun hasKisApiConfig(environment: KisEnvironment): Boolean = withContext(Dispatchers.IO) {
        habitDao.hasKisApiConfig(environment.apiValue)
    }

    suspend fun getKisAccessTokenExpiredAt(environment: KisEnvironment): LocalDateTime? = withContext(Dispatchers.IO) {
        habitDao.getKisAccessTokenExpiredAt(environment.apiValue)
    }

    fun observeKisAccessTokenExpiredAt(environment: KisEnvironment): Flow<LocalDateTime?> =
        habitDao.observeKisAccessTokenExpiredAt(environment.apiValue)

    suspend fun getKisBalanceStocks(): List<KisBalanceStock> = withContext(Dispatchers.IO) {
        val (config, accessToken) = getKisConfigAndAccessToken()
        val marketTime = ZonedDateTime.now(ZoneId.of("Asia/Seoul")).toLocalTime()
        val market = resolveActiveStockMarket(marketTime) ?: KisStockMarket.KRX
        withKisAccessTokenRetry(config, accessToken) { retryConfig, retryToken ->
            kisDomesticStockClient.getBalance(retryConfig, retryToken, market)
        }
    }

    suspend fun getKisMarketCapStocks(): List<KisMarketCapStock> = withContext(Dispatchers.IO) {
        kisMarketCapCacheMutex.withLock {
            val today = LocalDate.now()
            readKisMarketCapCache(today).takeIf { it.isNotEmpty() }
                ?: run {
                    val (config, accessToken) = getKisConfigAndAccessToken()
                    withKisAccessTokenRetry(config, accessToken) { retryConfig, retryToken ->
                        kisDomesticStockClient.getMarketCapRanking(retryConfig, retryToken)
                    }.also { stocks ->
                        if (stocks.isNotEmpty()) saveKisMarketCapCache(today, stocks)
                    }
                }
        }
    }

    suspend fun isKisMarketOpenDay(date: LocalDate): Boolean = withContext(Dispatchers.IO) {
        if (kisMarketCalendarPrefs.getString(kisMarketOpenDateKey, null) == date.toString()) {
            return@withContext kisMarketCalendarPrefs.getBoolean(kisMarketOpenValueKey, false)
        }
        val (config, accessToken) = getKisConfigAndAccessToken()
        withKisAccessTokenRetry(config, accessToken) { retryConfig, retryToken ->
            kisDomesticStockClient.isMarketOpenDay(retryConfig, retryToken, date)
        }.also { isOpen ->
            kisMarketCalendarPrefs.edit()
                .putString(kisMarketOpenDateKey, date.toString())
                .putBoolean(kisMarketOpenValueKey, isOpen)
                .apply()
        }
    }

    fun observeStockOrders(): Flow<List<StockOrderEntity>> = habitDao.observeStockOrders()

    fun observeStockSellAllocations(): Flow<List<StockSellAllocationEntity>> =
        habitDao.observeStockSellAllocations()

    fun observeStockExitRules(): Flow<List<StockExitRuleEntity>> = habitDao.observeStockExitRules()

    fun observeStockTargetAllocations(): Flow<List<StockTargetAllocationEntity>> =
        habitDao.observeStockTargetAllocations()

    fun observeStockSafetyConfig(): Flow<StockSafetyConfigEntity?> = habitDao.observeStockSafetyConfig()

    fun observeStockAutomationEvents(limit: Int = 100): Flow<List<StockAutomationEventEntity>> =
        habitDao.observeStockAutomationEvents(limit)

    suspend fun saveStockErrorEvent(
        eventType: String,
        title: String,
        message: String,
        productCode: String? = null,
    ) {
        saveStockAutomationEvent(
            level = "ERROR",
            eventType = eventType,
            productCode = productCode,
            message = "$title\n$message",
        )
    }

    suspend fun clearStockAutomationEvents() {
        persistChange { habitDao.deleteAllStockAutomationEvents() }
    }

    suspend fun getStockSafetyConfig(): StockSafetyConfigEntity = withContext(Dispatchers.IO) {
        habitDao.getStockSafetyConfig() ?: StockSafetyConfigEntity(id = stockSafetyConfigId)
    }

    suspend fun saveStockSafetyConfig(config: StockSafetyConfigEntity) {
        if (config.monitoringEnabled) {
            require(config.monitorIntervalMinutes?.let { it > 0 } == true) { "잔고·안전설정 동기화 주기를 1분 이상 입력해 주세요." }
        }
        if (config.crashGuardEnabled) {
            require(config.crashBenchmarkCode in setOf("0001", "1001", "2001")) { "급락 감시 기준 지수를 선택해 주세요." }
            require(config.crashThresholdPercent?.let { it > 0.0 } == true) { "급락 차단 기준을 0보다 크게 입력해 주세요." }
        }
        require(config.maxOrderAmount == null || config.maxOrderAmount > 0L) { "1회 최대 주문금액은 0보다 커야 합니다." }
        require(config.dailyBuyLimit == null || config.dailyBuyLimit > 0L) { "하루 최대 매수금액은 0보다 커야 합니다." }
        persistChange {
            habitDao.upsertStockSafetyConfig(config.copy(id = stockSafetyConfigId, updatedAt = LocalDateTime.now()))
        }
    }

    suspend fun setStockMonitoringEnabled(enabled: Boolean) {
        val current = getStockSafetyConfig()
        saveStockSafetyConfig(current.copy(monitoringEnabled = enabled))
    }

    suspend fun setGlobalStockOrderBlock(blocked: Boolean, reason: String? = null) {
        val current = getStockSafetyConfig()
        persistChange {
            habitDao.upsertStockSafetyConfig(
                current.copy(
                    globalOrderBlocked = blocked,
                    blockReason = reason?.trim()?.takeIf(String::isNotBlank),
                    updatedAt = LocalDateTime.now(),
                ),
            )
        }
        saveStockAutomationEvent(
            level = if (blocked) "WARN" else "INFO",
            eventType = if (blocked) "ORDER_BLOCKED" else "ORDER_BLOCK_RELEASED",
            message = if (blocked) "전체 주문을 차단했습니다. ${reason.orEmpty()}" else "사용자가 전체 주문 차단을 해제했습니다.",
        )
    }

    suspend fun saveStockExitRule(
        ruleId: Long?,
        productCode: String,
        productName: String,
        ruleType: StockExitRuleType,
        triggerValue: Double?,
        triggerPrice: Long?,
        sellQuantityPercent: Double,
        actionMode: StockRuleAction,
        orderDivisionCode: String,
    ) {
        val sanitizedCode = productCode.trim()
        require(sanitizedCode.length in 6..7) { "종목코드를 선택해 주세요." }
        if (ruleType == StockExitRuleType.TIME_EXIT) {
            require(triggerValue != null && triggerValue > 0.0) { "보유 기간은 0보다 커야 합니다." }
        } else {
            require((triggerValue != null) xor (triggerPrice != null)) { "% 또는 직접 가격 중 하나만 입력해 주세요." }
            require(triggerValue == null || triggerValue > 0.0) { "% 기준은 0보다 커야 합니다." }
            require(triggerPrice == null || triggerPrice > 0L) { "직접 가격은 0보다 커야 합니다." }
        }
        if (actionMode == StockRuleAction.AUTO_SELL) {
            require(sellQuantityPercent > 0.0 && sellQuantityPercent <= 100.0) {
                "매도 비율은 0 초과 100 이하로 입력해 주세요."
            }
        }
        require(orderDivisionCode in setOf(stockLimitOrderCode, stockMarketOrderCode)) { "자동 매도 주문 방식은 지정가 또는 시장가만 사용할 수 있습니다." }
        val now = LocalDateTime.now()
        persistChange {
            habitDao.upsertStockExitRule(
                StockExitRuleEntity(
                    id = ruleId ?: 0,
                    productCode = sanitizedCode,
                    productName = productName.trim().ifBlank { sanitizedCode },
                    ruleType = ruleType.name,
                    triggerValue = triggerValue ?: 0.0,
                    triggerPrice = if (ruleType == StockExitRuleType.TIME_EXIT) null else triggerPrice,
                    sellQuantityPercent = if (actionMode == StockRuleAction.AUTO_SELL) sellQuantityPercent else 0.0,
                    actionMode = actionMode.name,
                    orderDivisionCode = orderDivisionCode,
                    referenceHighPrice = null,
                    enabled = true,
                    lastTriggeredAt = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    suspend fun setStockExitRuleEnabled(rule: StockExitRuleEntity, enabled: Boolean) {
        persistChange {
            habitDao.updateStockExitRule(
                rule.copy(
                    enabled = enabled,
                    referenceHighPrice = if (enabled) null else rule.referenceHighPrice,
                    lastTriggeredAt = if (enabled) null else rule.lastTriggeredAt,
                    updatedAt = LocalDateTime.now(),
                ),
            )
        }
    }

    suspend fun deleteStockExitRule(ruleId: Long) {
        persistChange { habitDao.deleteStockExitRule(ruleId) }
    }

    suspend fun saveStockTargetAllocation(productCode: String, productName: String, targetPercent: Double) {
        val sanitizedCode = productCode.trim()
        require(sanitizedCode.length in 6..7) { "종목코드를 선택해 주세요." }
        require(targetPercent > 0.0 && targetPercent <= 100.0) { "목표 비중은 0 초과 100 이하로 입력해 주세요." }
        val others = habitDao.getEnabledStockTargetAllocations().filterNot { it.productCode == sanitizedCode }
        require(others.sumOf(StockTargetAllocationEntity::targetPercent) + targetPercent <= 100.0001) {
            "목표 비중 합계는 100%를 초과할 수 없습니다."
        }
        persistChange {
            habitDao.upsertStockTargetAllocation(
                StockTargetAllocationEntity(
                    productCode = sanitizedCode,
                    productName = productName.trim().ifBlank { sanitizedCode },
                    targetPercent = targetPercent,
                    enabled = true,
                    updatedAt = LocalDateTime.now(),
                ),
            )
        }
    }

    suspend fun deleteStockTargetAllocation(productCode: String) {
        persistChange { habitDao.deleteStockTargetAllocation(productCode) }
    }

    suspend fun placeKisCashOrder(
        draft: KisCashOrderDraft,
        productName: String,
        source: StockOrderSource = StockOrderSource.MANUAL,
        requireAutomaticEnabled: Boolean = false,
        isEmergencyLiquidation: Boolean = false,
        verifiedCurrentPrice: Long? = null,
        skipCrashGuardRefresh: Boolean = false,
        intendedBuyOrderId: Long? = null,
    ): StockOrderEntity = withContext(Dispatchers.IO) {
        stockOrderMutex.withLock {
            require(!isEmergencyLiquidation || draft.side == KisOrderSide.SELL) {
                "긴급 청산은 매도 주문에만 사용할 수 있습니다."
            }
            val quantity = draft.orderQuantity.toLongOrNull()
            require(quantity?.let { it > 0L } == true) { "주문 수량은 1주 이상이어야 합니다." }
            val requestedPrice = draft.orderUnitPrice.toLongOrNull()
            require(requestedPrice?.let { it >= 0L } == true) { "주문단가를 확인해 주세요." }
            require(draft.productCode.length in 6..7) { "종목코드를 확인해 주세요." }
            require(draft.orderDivisionCode in setOf(stockLimitOrderCode, stockMarketOrderCode)) {
                "주문 방식은 지정가 또는 시장가만 사용할 수 있습니다."
            }
            intendedBuyOrderId?.let { buyOrderId ->
                require(draft.side == KisOrderSide.SELL) { "매수 lot 연결은 매도 주문에만 사용할 수 있습니다." }
                val buyOrder = habitDao.getStockOrderById(buyOrderId)
                    ?: throw IllegalArgumentException("연결할 매수 기록을 찾을 수 없습니다. (id=$buyOrderId)")
                require(buyOrder.side == KisOrderSide.BUY.name) { "매수 기록을 선택해 주세요." }
                require(buyOrder.productCode == draft.productCode) { "같은 종목의 매수 기록만 선택할 수 있습니다." }
                require(quantity <= buyOrder.remainingQuantity) {
                    "매도 수량이 선택한 매수 기록의 남은 수량을 초과합니다."
                }
            }
            val quoteMarket = KisStockMarket.values()
                .firstOrNull { it.orderExchangeCode == draft.exchangeIdDivisionCode }
                ?: throw IllegalArgumentException("거래소는 KRX, NXT 또는 SOR만 사용할 수 있습니다.")
            if (draft.orderDivisionCode == stockLimitOrderCode) {
                require(requestedPrice > 0L) { "지정가는 1원 이상으로 입력해 주세요." }
            } else {
                require(requestedPrice == 0L) { "시장가 주문단가는 0이어야 합니다." }
            }

            var safety = getStockSafetyConfig()
            require(!isStockOrderBlocked(safety, isEmergencyLiquidation)) {
                "전체 주문이 차단되어 있습니다. ${safety.blockReason.orEmpty()}"
            }
            if (requireAutomaticEnabled) {
                require(safety.automaticOrderEnabled) { "자동 주문 실행이 꺼져 있습니다." }
            }
            require(habitDao.countUnfinishedStockOrders(draft.productCode, draft.side.name) == 0) {
                "같은 종목의 ${draft.side.label} 미완료 주문이 있습니다. 체결 상태를 먼저 동기화해 주세요."
            }

            val (config, accessToken) = getKisConfigAndAccessToken()
            if (!isEmergencyLiquidation && !skipCrashGuardRefresh) {
                safety = refreshCrashGuard(safety, config, accessToken)
            }
            require(!isStockOrderBlocked(safety, isEmergencyLiquidation)) {
                "급락 안전장치로 전체 주문이 차단되었습니다. ${safety.blockReason.orEmpty()}"
            }

            val currentPrice = verifiedCurrentPrice
                ?: withKisAccessTokenRetry(config, accessToken) { retryConfig, retryToken ->
                    kisDomesticStockClient.getCurrentPrice(retryConfig, retryToken, draft.productCode, quoteMarket)
                }.currentPrice.toLongOrNull()
                ?: throw IllegalStateException("현재가를 확인하지 못했습니다. (종목=${draft.productCode})")
            require(currentPrice > 0L) { "현재가를 확인하지 못했습니다. (종목=${draft.productCode})" }
            val estimatedUnitPrice = if (draft.orderDivisionCode == stockLimitOrderCode) requestedPrice else currentPrice
            val estimatedAmount = Math.multiplyExact(quantity, estimatedUnitPrice)
            if (!isEmergencyLiquidation) {
                safety.maxOrderAmount?.let { limit ->
                    require(estimatedAmount <= limit) { "예상 주문금액이 1회 최대 주문금액을 초과합니다. ($estimatedAmount > $limit)" }
                }
            }

            if (draft.side == KisOrderSide.BUY) {
                val dailyUsed = habitDao.getSubmittedBuyAmount(LocalDate.now())
                safety.dailyBuyLimit?.let { limit ->
                    require(dailyUsed + estimatedAmount <= limit) {
                        "오늘 누적 예상 매수금액이 하루 한도를 초과합니다. (${dailyUsed + estimatedAmount} > $limit)"
                    }
                }
                val buyable = withKisAccessTokenRetry(config, accessToken) { retryConfig, retryToken ->
                    kisDomesticStockClient.getBuyableQuantity(
                        config = retryConfig,
                        accessToken = retryToken,
                        productCode = draft.productCode,
                        unitPrice = draft.orderUnitPrice,
                        orderDivisionCode = if (draft.orderDivisionCode == stockLimitOrderCode) {
                            stockMarketOrderCode
                        } else {
                            draft.orderDivisionCode
                        },
                    )
                }
                require(quantity <= buyable.quantity) {
                    "매수가능수량을 초과합니다. (요청=${quantity}주, 가능=${buyable.quantity}주)"
                }
            } else {
                val sellable = withKisAccessTokenRetry(config, accessToken) { retryConfig, retryToken ->
                    kisDomesticStockClient.getSellableQuantity(retryConfig, retryToken, draft.productCode)
                }.quantity.toLongOrNull() ?: 0L
                require(quantity <= sellable) {
                    "매도가능수량을 초과합니다. (요청=${quantity}주, 가능=${sellable}주)"
                }
            }

            val now = LocalDateTime.now()
            val result = try {
                withKisAccessTokenRetry(config, accessToken) { retryConfig, retryToken ->
                    kisDomesticStockClient.placeCashOrder(retryConfig, retryToken, draft)
                }
            } catch (timeout: SocketTimeoutException) {
                val unknownOrder = StockOrderEntity(
                    orderNumber = "UNKNOWN-${System.currentTimeMillis()}",
                    orderDate = now.toLocalDate(),
                    orderTime = now.format(java.time.format.DateTimeFormatter.ofPattern("HHmmss")),
                    side = draft.side.name,
                    productCode = draft.productCode,
                    productName = productName.trim().ifBlank { draft.productCode },
                    requestedQuantity = quantity,
                    requestedUnitPrice = requestedPrice,
                    referencePrice = currentPrice,
                    orderDivisionCode = draft.orderDivisionCode,
                    exchangeCode = draft.exchangeIdDivisionCode,
                    source = source.name,
                    intendedBuyOrderId = intendedBuyOrderId,
                    status = StockOrderStatus.UNKNOWN.name,
                    message = "주문 응답 시간 초과. KIS 주문내역 확인 전 재주문 금지",
                    createdAt = now,
                    updatedAt = now,
                )
                persistChange { habitDao.insertStockOrder(unknownOrder) }
                saveStockAutomationEvent(
                    level = "ERROR",
                    eventType = "ORDER_UNKNOWN",
                    productCode = draft.productCode,
                    message = "${productName.ifBlank { draft.productCode }} ${draft.side.label} 주문 응답이 시간 초과되었습니다. KIS 주문내역을 직접 확인해 주세요.",
                )
                throw IllegalStateException("주문 응답이 시간 초과되었습니다. 중복 주문 방지를 위해 KIS 주문내역을 확인하기 전에는 재주문하지 마세요.", timeout)
            }
            val order = StockOrderEntity(
                orderNumber = result.orderNumber,
                orderDate = now.toLocalDate(),
                orderTime = result.orderTime,
                side = draft.side.name,
                productCode = draft.productCode,
                productName = productName.trim().ifBlank { draft.productCode },
                requestedQuantity = quantity,
                requestedUnitPrice = requestedPrice,
                referencePrice = currentPrice,
                orderDivisionCode = draft.orderDivisionCode,
                exchangeCode = draft.exchangeIdDivisionCode,
                source = source.name,
                intendedBuyOrderId = intendedBuyOrderId,
                status = StockOrderStatus.SUBMITTED.name,
                message = result.message,
                createdAt = now,
                updatedAt = now,
            )
            persistChange { habitDao.insertStockOrder(order) }
            saveStockAutomationEvent(
                level = "INFO",
                eventType = "ORDER_SUBMITTED",
                productCode = draft.productCode,
                message = "${order.productName} ${draft.side.label} ${quantity}주 주문을 접수했습니다. (주문번호=${result.orderNumber})",
            )
            order
        }
    }

    suspend fun sellAllKisHoldings(): StockBulkSellResult = withContext(Dispatchers.IO) {
        val marketNow = ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
        require(marketNow.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) {
            "주말에는 보유 종목 전체 매도를 실행할 수 없습니다."
        }
        val market = resolveActiveStockMarket(marketNow.toLocalTime())
            ?: throw IllegalStateException("전체 매도는 정규장 09:00~15:30 또는 NXT 애프터마켓 15:40~20:00에 실행해 주세요.")
        require(isKisMarketOpenDay(marketNow.toLocalDate())) { "KIS 휴장일에는 보유 종목 전체 매도를 실행할 수 없습니다." }

        val safety = getStockSafetyConfig()
        require(!isStockOrderBlocked(safety, isEmergencyLiquidation = true)) {
            "사용자가 전체 주문을 긴급 차단한 상태입니다. 차단을 해제한 뒤 다시 시도해 주세요."
        }
        val (config, accessToken) = getKisConfigAndAccessToken()
        val balances = withKisAccessTokenRetry(config, accessToken) { retryConfig, retryToken ->
            kisDomesticStockClient.getBalance(retryConfig, retryToken, market)
        }
            .filter { it.quantity.toLongOrNull()?.let { quantity -> quantity > 0L } == true }
        require(balances.isNotEmpty()) { "매도할 보유 종목이 없습니다." }

        val submittedOrders = mutableListOf<StockOrderEntity>()
        val failures = mutableListOf<StockBulkSellFailure>()
        balances.forEachIndexed { index, balance ->
            val quantity = balance.quantity.toLongOrNull() ?: 0L
            runCatching {
                placeKisCashOrder(
                    draft = KisCashOrderDraft(
                        side = KisOrderSide.SELL,
                        productCode = balance.productCode,
                        orderDivisionCode = stockMarketOrderCode,
                        orderQuantity = quantity.toString(),
                        orderUnitPrice = "0",
                        exchangeIdDivisionCode = market.orderExchangeCode,
                        sellType = "01",
                        conditionPrice = "",
                    ),
                    productName = balance.productName,
                    source = StockOrderSource.MANUAL,
                    isEmergencyLiquidation = true,
                    verifiedCurrentPrice = balance.currentPrice.toLongOrNull()?.takeIf { it > 0L },
                )
            }.onSuccess(submittedOrders::add)
                .onFailure { error ->
                    failures += StockBulkSellFailure(
                        productCode = balance.productCode,
                        productName = balance.productName,
                        reason = error.message ?: "주문 접수에 실패했습니다.",
                    )
                }
            if (index < balances.lastIndex) delay(250L)
        }
        StockBulkSellResult(submittedOrders, failures)
    }

    suspend fun syncStockOrderExecutions(): Int = withContext(Dispatchers.IO) {
        stockOrderMutex.withLock {
            val (config, accessToken) = getKisConfigAndAccessToken()
            val today = LocalDate.now()
            val startDate = stockExecutionPrefs.getString(lastStockExecutionSyncDateKey, null)
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?.coerceAtMost(today)
                ?.coerceAtLeast(today.minusMonths(3))
                ?: today
            val executions = withKisAccessTokenRetry(config, accessToken) { retryConfig, retryToken ->
                kisDomesticStockClient.getOrderExecutions(
                    config = retryConfig,
                    accessToken = retryToken,
                    startDate = startDate,
                    endDate = today,
                )
            }.sortedWith(compareBy(KisOrderExecution::orderDate, KisOrderExecution::orderTime))
            var updatedCount = 0
            if (executions.isNotEmpty()) {
                persistChange {
                    database.withTransaction {
                        executions.forEach { execution ->
                            val storedOrder = habitDao.getStockOrder(execution.orderDate, execution.orderNumber)
                            if (storedOrder != null) {
                                applyStockExecutionInTransaction(
                                    order = storedOrder,
                                    filledQuantity = execution.filledQuantity,
                                    filledAveragePrice = execution.filledAveragePrice,
                                    canceledQuantity = execution.canceledQuantity,
                                    rejectedQuantity = execution.rejectedQuantity,
                                    isCanceled = execution.isCanceled,
                                )
                                updatedCount += 1
                                return@forEach
                            }
                            if (execution.filledQuantity <= 0L) return@forEach

                            val filledPrice = execution.filledAveragePrice ?: execution.orderedUnitPrice
                            habitDao.insertStockOrder(
                                StockOrderEntity(
                                    orderNumber = execution.orderNumber,
                                    orderDate = execution.orderDate,
                                    orderTime = execution.orderTime,
                                    side = execution.side.name,
                                    productCode = execution.productCode,
                                    productName = execution.productName.ifBlank { execution.productCode },
                                    requestedQuantity = execution.orderedQuantity.coerceAtLeast(execution.filledQuantity),
                                    requestedUnitPrice = execution.orderedUnitPrice,
                                    referencePrice = filledPrice,
                                    orderDivisionCode = execution.orderDivisionCode,
                                    exchangeCode = execution.exchangeCode,
                                    source = StockOrderSource.EXTERNAL.name,
                                    status = StockOrderStatus.FILLED.name,
                                    filledQuantity = execution.filledQuantity,
                                    filledAveragePrice = execution.filledAveragePrice,
                                    remainingQuantity = if (execution.side == KisOrderSide.BUY) {
                                        execution.filledQuantity
                                    } else {
                                        0L
                                    },
                                    message = "KIS 계좌 체결내역에서 가져온 외부 주문",
                                    createdAt = execution.orderDate.atStartOfDay(),
                                    updatedAt = LocalDateTime.now(),
                                ),
                            )
                            updatedCount += 1
                        }
                    }
                }
            }
            stockExecutionPrefs.edit().putString(lastStockExecutionSyncDateKey, today.toString()).apply()
            updatedCount
        }
    }

    suspend fun saveManualStockExecution(
        productCode: String,
        productName: String,
        side: KisOrderSide,
        orderDate: LocalDate,
        quantity: Long,
        unitPrice: Long,
    ) {
        val sanitizedCode = productCode.trim()
        require(sanitizedCode.length in 6..7) { "종목을 선택해 주세요." }
        require(quantity > 0L) { "수량은 1주 이상이어야 합니다." }
        require(unitPrice > 0L) { "실제 체결가는 0보다 커야 합니다." }
        val now = LocalDateTime.now()
        val orderNumber = "MANUAL-${now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"))}"
        persistChange {
            habitDao.insertStockOrder(
                StockOrderEntity(
                    orderNumber = orderNumber,
                    orderDate = orderDate,
                    orderTime = now.format(java.time.format.DateTimeFormatter.ofPattern("HHmmss")),
                    side = side.name,
                    productCode = sanitizedCode,
                    productName = productName.trim().ifBlank { sanitizedCode },
                    requestedQuantity = quantity,
                    requestedUnitPrice = unitPrice,
                    referencePrice = unitPrice,
                    orderDivisionCode = "",
                    exchangeCode = "",
                    source = StockOrderSource.MANUAL_ENTRY.name,
                    status = StockOrderStatus.FILLED.name,
                    filledQuantity = quantity,
                    filledAveragePrice = unitPrice,
                    remainingQuantity = if (side == KisOrderSide.BUY) quantity else 0L,
                    message = "사용자가 직접 입력한 체결 기록",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    suspend fun updateManualStockExecution(
        orderId: Long,
        productCode: String,
        productName: String,
        side: KisOrderSide,
        orderDate: LocalDate,
        quantity: Long,
        unitPrice: Long,
    ) {
        val sanitizedCode = productCode.trim()
        require(sanitizedCode.length in 6..7) { "종목을 선택해 주세요." }
        require(quantity > 0L) { "수량은 1주 이상이어야 합니다." }
        require(unitPrice > 0L) { "실제 체결가는 0보다 커야 합니다." }
        persistChange {
            database.withTransaction {
                val order = habitDao.getStockOrderById(orderId)
                    ?: throw IllegalArgumentException("수동 체결 기록을 찾을 수 없습니다. (id=$orderId)")
                require(order.source == StockOrderSource.MANUAL_ENTRY.name) { "수동 입력 기록만 수정할 수 있습니다." }
                require(habitDao.countStockSellAllocations(orderId) == 0) {
                    "매수 lot 연결을 먼저 취소한 후 수정해 주세요."
                }
                require(
                    (order.side == KisOrderSide.BUY.name && order.remainingQuantity == order.filledQuantity) ||
                        (order.side == KisOrderSide.SELL.name && order.appliedFilledQuantity == 0L),
                ) {
                    "다른 매매에 사용된 기록은 연결을 해제한 후 수정해 주세요."
                }
                habitDao.updateStockOrder(
                    order.copy(
                        orderDate = orderDate,
                        side = side.name,
                        productCode = sanitizedCode,
                        productName = productName.trim().ifBlank { sanitizedCode },
                        requestedQuantity = quantity,
                        requestedUnitPrice = unitPrice,
                        referencePrice = unitPrice,
                        filledQuantity = quantity,
                        appliedFilledQuantity = 0L,
                        filledAveragePrice = unitPrice,
                        remainingQuantity = if (side == KisOrderSide.BUY) quantity else 0L,
                        estimatedRealizedProfit = null,
                        message = "사용자가 수정한 수동 체결 기록",
                        updatedAt = LocalDateTime.now(),
                    ),
                )
            }
        }
    }

    suspend fun allocateStockSellToBuyLot(sellOrderId: Long, buyOrderId: Long, quantity: Long) {
        persistChange {
            database.withTransaction {
                val sellOrder = habitDao.getStockOrderById(sellOrderId)
                    ?: throw IllegalArgumentException("매도 기록을 찾을 수 없습니다. (id=$sellOrderId)")
                val buyOrder = habitDao.getStockOrderById(buyOrderId)
                    ?: throw IllegalArgumentException("매수 기록을 찾을 수 없습니다. (id=$buyOrderId)")
                require(sellOrder.side == KisOrderSide.SELL.name) { "매도 기록만 매수 건에 연결할 수 있습니다." }
                require(buyOrder.side == KisOrderSide.BUY.name) { "매수 기록을 선택해 주세요." }
                require(sellOrder.productCode == buyOrder.productCode) { "같은 종목의 매수 건만 연결할 수 있습니다." }
                require(!buyOrder.orderDate.isAfter(sellOrder.orderDate)) { "매도일 이후의 매수 건은 연결할 수 없습니다." }

                val unallocatedSellQuantity =
                    (sellOrder.filledQuantity - sellOrder.appliedFilledQuantity).coerceAtLeast(0L)
                require(unallocatedSellQuantity > 0L) { "이미 모든 매도 수량이 매수 건에 연결되었습니다." }
                require(buyOrder.remainingQuantity > 0L) { "선택한 매수 건에 남은 수량이 없습니다." }
                require(quantity > 0L && quantity <= unallocatedSellQuantity) {
                    "연결 수량은 남은 매도 수량 이하여야 합니다."
                }
                require(quantity <= buyOrder.remainingQuantity) { "연결 수량이 선택한 매수 건의 남은 수량을 초과합니다." }
                val buyPrice = buyOrder.filledAveragePrice ?: buyOrder.referencePrice
                val sellPrice = sellOrder.filledAveragePrice ?: sellOrder.referencePrice
                require(buyPrice > 0L && sellPrice > 0L) { "매수·매도 체결가를 확인해 주세요." }
                val realizedProfit = Math.multiplyExact(sellPrice - buyPrice, quantity)
                habitDao.insertStockSellAllocation(
                    StockSellAllocationEntity(
                        sellOrderId = sellOrder.id,
                        buyOrderId = buyOrder.id,
                        quantity = quantity,
                        buyUnitPrice = buyPrice,
                        sellUnitPrice = sellPrice,
                        realizedProfit = realizedProfit,
                    ),
                )
                habitDao.updateStockOrder(
                    buyOrder.copy(
                        remainingQuantity = buyOrder.remainingQuantity - quantity,
                        updatedAt = LocalDateTime.now(),
                    ),
                )
                habitDao.updateStockOrder(
                    sellOrder.copy(
                        appliedFilledQuantity = sellOrder.appliedFilledQuantity + quantity,
                        estimatedRealizedProfit =
                            (sellOrder.estimatedRealizedProfit ?: 0L) + realizedProfit,
                        updatedAt = LocalDateTime.now(),
                    ),
                )
            }
        }
    }

    suspend fun deleteStockSellAllocation(allocation: StockSellAllocationEntity) {
        persistChange {
            database.withTransaction {
                val storedAllocation = habitDao.getStockSellAllocationById(allocation.id)
                    ?: throw IllegalArgumentException("이미 취소되었거나 존재하지 않는 매수 건 연결입니다. (id=${allocation.id})")
                val sellOrder = habitDao.getStockOrderById(storedAllocation.sellOrderId)
                    ?: throw IllegalArgumentException("매도 기록을 찾을 수 없습니다. (id=${storedAllocation.sellOrderId})")
                val buyOrder = habitDao.getStockOrderById(storedAllocation.buyOrderId)
                    ?: throw IllegalArgumentException("매수 기록을 찾을 수 없습니다. (id=${storedAllocation.buyOrderId})")
                require(habitDao.deleteStockSellAllocationById(storedAllocation.id) == 1) {
                    "매수 건 연결이 이미 취소되었습니다. (id=${storedAllocation.id})"
                }
                val remainingApplied =
                    (sellOrder.appliedFilledQuantity - storedAllocation.quantity).coerceAtLeast(0L)
                habitDao.updateStockOrder(
                    buyOrder.copy(
                        remainingQuantity = buyOrder.remainingQuantity + storedAllocation.quantity,
                        updatedAt = LocalDateTime.now(),
                    ),
                )
                habitDao.updateStockOrder(
                    sellOrder.copy(
                        appliedFilledQuantity = remainingApplied,
                        intendedBuyOrderId = if (sellOrder.intendedBuyOrderId == storedAllocation.buyOrderId) {
                            null
                        } else {
                            sellOrder.intendedBuyOrderId
                        },
                        estimatedRealizedProfit = if (remainingApplied == 0L) {
                            null
                        } else {
                            (sellOrder.estimatedRealizedProfit ?: 0L) - storedAllocation.realizedProfit
                        },
                        updatedAt = LocalDateTime.now(),
                    ),
                )
            }
        }
    }

    suspend fun resolveUnknownStockOrder(orderId: Long) {
        val order = habitDao.getStockOrderById(orderId)
            ?: throw IllegalArgumentException("주문 기록을 찾을 수 없습니다. (id=$orderId)")
        require(order.status == StockOrderStatus.UNKNOWN.name) { "확인 대기 주문이 아닙니다." }
        persistChange {
            habitDao.updateStockOrder(
                order.copy(
                    status = StockOrderStatus.REJECTED.name,
                    message = "사용자가 KIS 주문내역 확인 후 미접수로 처리",
                    updatedAt = LocalDateTime.now(),
                ),
            )
        }
    }

    suspend fun getStockBuyLotRows(balanceStocks: List<KisBalanceStock>): List<StockBuyLotRow> = withContext(Dispatchers.IO) {
        val orders = habitDao.getFilledStockBuyOrders()
        if (orders.isEmpty()) return@withContext emptyList()
        val currentPrices = balanceStocks.associate { balance ->
            balance.productCode to balance.currentPrice.toLongOrNull()
        }
        orders.map { order ->
            val buyPrice = order.filledAveragePrice ?: order.referencePrice
            val currentPrice = currentPrices[order.productCode]
            val returnPercent = currentPrice?.takeIf { buyPrice > 0L }
                ?.let { (it - buyPrice).toDouble() / buyPrice.toDouble() * 100.0 }
            StockBuyLotRow(order, currentPrice, returnPercent)
        }
    }

    suspend fun calculateStockRebalance(): List<StockRebalanceLine> = withContext(Dispatchers.IO) {
        val targets = habitDao.getEnabledStockTargetAllocations()
        require(targets.isNotEmpty()) { "목표 비중을 먼저 등록해 주세요." }
        require(targets.sumOf(StockTargetAllocationEntity::targetPercent) <= 100.0001) { "목표 비중 합계가 100%를 초과합니다." }
        val (config, accessToken) = getKisConfigAndAccessToken()
        val market = currentStockMarket()
        val balances = withKisAccessTokenRetry(config, accessToken) { retryConfig, retryToken ->
            kisDomesticStockClient.getBalance(retryConfig, retryToken, market)
        }
        val managedCodes = targets.map(StockTargetAllocationEntity::productCode).toSet()
        val balanceMap = balances.associateBy(KisBalanceStock::productCode)
        val prices = managedCodes.associateWith { code ->
            balanceMap[code]?.currentPrice?.toLongOrNull()?.takeIf { it > 0L }
                ?: withKisAccessTokenRetry(config, accessToken) { retryConfig, retryToken ->
                    kisDomesticStockClient.getCurrentPrice(retryConfig, retryToken, code, market)
                }.currentPrice.toLongOrNull()
                ?: throw IllegalStateException("현재가를 확인하지 못했습니다. (종목=$code)")
        }
        val totalValue = balances.filter { it.productCode in managedCodes }.sumOf { balance ->
            (balance.quantity.toLongOrNull() ?: 0L) * prices.getValue(balance.productCode)
        }
        require(totalValue > 0L) { "목표로 등록한 종목의 보유 평가액이 없어 리밸런싱을 계산할 수 없습니다." }
        targets.map { target ->
            val price = prices.getValue(target.productCode)
            val currentQuantity = balanceMap[target.productCode]?.quantity?.toLongOrNull() ?: 0L
            val currentValue = currentQuantity * price
            val currentPercent = currentValue.toDouble() / totalValue.toDouble() * 100.0
            val targetQuantity = floor((totalValue * target.targetPercent / 100.0) / price.toDouble()).toLong()
            val difference = targetQuantity - currentQuantity
            StockRebalanceLine(
                productCode = target.productCode,
                productName = target.productName,
                targetPercent = target.targetPercent,
                currentPercent = currentPercent,
                currentQuantity = currentQuantity,
                targetQuantity = targetQuantity,
                orderSide = when {
                    difference > 0L -> KisOrderSide.BUY
                    difference < 0L -> KisOrderSide.SELL
                    else -> null
                },
                orderQuantity = abs(difference),
                referencePrice = price,
            )
        }
    }

    suspend fun executeStockRebalanceLine(line: StockRebalanceLine): StockOrderEntity {
        val side = line.orderSide ?: throw IllegalArgumentException("주문이 필요하지 않은 리밸런싱 항목입니다.")
        require(line.orderQuantity > 0L) { "리밸런싱 주문 수량이 없습니다." }
        return placeKisCashOrder(
            draft = KisCashOrderDraft(
                side = side,
                productCode = line.productCode,
                orderDivisionCode = stockLimitOrderCode,
                orderQuantity = line.orderQuantity.toString(),
                orderUnitPrice = line.referencePrice.toString(),
                exchangeIdDivisionCode = currentStockMarket().orderExchangeCode,
                sellType = "01",
                conditionPrice = "",
            ),
            productName = line.productName,
            source = StockOrderSource.REBALANCE,
        )
    }

    fun calculateStockJournalAnalysis(orders: List<StockOrderEntity>): StockJournalAnalysis {
        val buys = orders.filter { it.side == KisOrderSide.BUY.name && it.filledQuantity > 0L }
        val sells = orders.filter { it.side == KisOrderSide.SELL.name && it.filledQuantity > 0L }
        val analyzedSells = sells.filter {
            it.estimatedRealizedProfit != null && it.appliedFilledQuantity == it.filledQuantity
        }
        val profitable = analyzedSells.count { (it.estimatedRealizedProfit ?: 0L) > 0L }
        val sourceCounts = orders.filter { it.filledQuantity > 0L }
            .mapNotNull { order -> StockOrderSource.values().firstOrNull { it.name == order.source } }
            .groupingBy { it }
            .eachCount()
        return StockJournalAnalysis(
            filledBuyCount = buys.size,
            filledSellCount = sells.size,
            realizedTradeCount = analyzedSells.size,
            profitableTradeCount = profitable,
            estimatedRealizedProfit = analyzedSells.sumOf { it.estimatedRealizedProfit ?: 0L },
            winRatePercent = if (analyzedSells.isNotEmpty()) {
                profitable.toDouble() / analyzedSells.size.toDouble() * 100.0
            } else {
                null
            },
            sourceCounts = sourceCounts,
        )
    }

    suspend fun issueKisRealtimeApprovalKey(): String = withContext(Dispatchers.IO) {
        val entity = habitDao.getKisApiConfig(KisEnvironment.REAL.apiValue)
            ?: throw IllegalStateException("KIS 설정을 먼저 저장해 주세요.")
        kisDomesticStockClient.issueWebSocketApprovalKey(entity.toKisApiConfig(kisConfigCipher))
    }

    suspend fun prepareStockRealtimeMonitoring(
        market: KisStockMarket,
    ): StockRealtimeMonitoringSnapshot = withContext(Dispatchers.IO) {
        stockAutomationMutex.withLock {
            val notices = mutableListOf<StockAutomationNotice>()
            var safety = getStockSafetyConfig()
            if (!safety.monitoringEnabled) {
                return@withLock StockRealtimeMonitoringSnapshot(
                    positions = emptyList(),
                    notices = emptyList(),
                    globalOrderBlocked = false,
                    blockReason = null,
                )
            }
            if (
                habitDao.getUnfinishedStockOrders().any {
                    it.status == StockOrderStatus.SUBMITTED.name ||
                        it.status == StockOrderStatus.PARTIALLY_FILLED.name
                }
            ) {
                syncStockOrderExecutions()
            }
            val (config, accessToken) = getKisConfigAndAccessToken()
            val wasBlocked = safety.globalOrderBlocked
            safety = refreshCrashGuard(safety, config, accessToken)
            if (safety.globalOrderBlocked) {
                if (!wasBlocked) {
                    notices += StockAutomationNotice(
                        title = "급락 안전장치 발동",
                        message = "${safety.blockReason.orEmpty()} 전체 주문을 차단했습니다.",
                    )
                }
                return@withLock StockRealtimeMonitoringSnapshot(
                    positions = emptyList(),
                    notices = notices,
                    globalOrderBlocked = true,
                    blockReason = safety.blockReason,
                )
            }

            val rules = habitDao.getEnabledStockExitRules()
            if (rules.isEmpty()) {
                return@withLock StockRealtimeMonitoringSnapshot(
                    positions = emptyList(),
                    notices = notices,
                    globalOrderBlocked = false,
                    blockReason = null,
                )
            }
            val ruleProductCodes = rules.map(StockExitRuleEntity::productCode).toSet()
            val positions = withKisAccessTokenRetry(config, accessToken) { retryConfig, retryToken ->
                kisDomesticStockClient.getBalance(retryConfig, retryToken, market)
            }.mapNotNull { balance ->
                if (balance.productCode !in ruleProductCodes) return@mapNotNull null
                val holdingQuantity = balance.quantity.toLongOrNull()?.takeIf { it > 0L } ?: return@mapNotNull null
                val averagePrice = balance.averagePrice.toDoubleOrNull()?.takeIf { it > 0.0 } ?: return@mapNotNull null
                StockRealtimePosition(
                    productCode = balance.productCode,
                    productName = balance.productName,
                    holdingQuantity = holdingQuantity,
                    averagePrice = averagePrice,
                    initialPrice = balance.currentPrice.toLongOrNull()?.takeIf { it > 0L },
                )
            }
            StockRealtimeMonitoringSnapshot(
                positions = positions,
                notices = notices,
                globalOrderBlocked = false,
                blockReason = null,
            )
        }
    }

    suspend fun runStockAutomationRealtimeTick(
        position: StockRealtimePosition,
        currentPrice: Long,
        market: KisStockMarket,
        ignoredRuleIds: Set<Long>,
        referenceHighPrices: Map<Long, Long>,
    ): StockRealtimeTickResult = withContext(Dispatchers.IO) {
        stockAutomationMutex.withLock {
            val safety = getStockSafetyConfig()
            if (!safety.monitoringEnabled || safety.globalOrderBlocked || currentPrice <= 0L) {
                return@withLock StockRealtimeTickResult(emptyList())
            }
            val rules = habitDao.getEnabledStockExitRules(position.productCode)
            if (rules.isEmpty()) return@withLock StockRealtimeTickResult(emptyList())
            val balance = KisBalanceStock(
                productCode = position.productCode,
                productName = position.productName,
                quantity = position.holdingQuantity.toString(),
                averagePrice = position.averagePrice.toString(),
                currentPrice = currentPrice.toString(),
            )
            val result = evaluateStockExitRules(
                safety = safety,
                market = market,
                balance = balance,
                holdingQuantity = position.holdingQuantity,
                averagePrice = position.averagePrice,
                currentPrice = currentPrice,
                rules = rules,
                ignoredRuleIds = ignoredRuleIds,
                referenceHighPrices = referenceHighPrices,
                persistReferenceHighPrices = false,
            )
            StockRealtimeTickResult(
                notices = result.notices,
                triggeredRuleIds = result.triggeredRuleIds,
                referenceHighPrices = result.referenceHighPrices,
            )
        }
    }

    suspend fun persistStockRealtimeHighPrices(referenceHighPrices: Map<Long, Long>) {
        if (referenceHighPrices.isEmpty()) return
        withContext(Dispatchers.IO) {
            stockAutomationMutex.withLock {
                val updates = habitDao.getEnabledStockExitRules().mapNotNull { rule ->
                    if (
                        rule.ruleType != StockExitRuleType.TRAILING_STOP.name ||
                        rule.triggerPrice != null
                    ) {
                        return@mapNotNull null
                    }
                    val high = referenceHighPrices[rule.id] ?: return@mapNotNull null
                    if (high <= (rule.referenceHighPrice ?: 0L)) return@mapNotNull null
                    rule.copy(referenceHighPrice = high, updatedAt = LocalDateTime.now())
                }
                if (updates.isNotEmpty()) {
                    persistChange {
                        database.withTransaction {
                            updates.forEach { rule -> habitDao.updateStockExitRule(rule) }
                        }
                    }
                }
            }
        }
    }

    suspend fun runStockAutomationCycle(): StockAutomationCycleResult = withContext(Dispatchers.IO) {
        stockAutomationMutex.withLock {
            val notices = mutableListOf<StockAutomationNotice>()
            var safety = getStockSafetyConfig()
            if (!safety.monitoringEnabled) {
                return@withLock StockAutomationCycleResult(LocalDateTime.now(), emptyList())
            }
            val marketNow = ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
            val marketDate = marketNow.toLocalDate()
            val marketTime = marketNow.toLocalTime()
            val market = resolveActiveStockMarket(marketTime)
            if (
                marketDate.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) ||
                market == null
            ) {
                return@withLock StockAutomationCycleResult(
                    checkedAt = marketNow.toLocalDateTime(),
                    notices = emptyList(),
                    skippedReason = "정규장 09:00~15:30 및 NXT 애프터마켓 15:40~20:00 외에는 자동화 규칙을 확인하지 않습니다.",
                )
            }
            if (!isKisMarketOpenDay(marketDate)) {
                return@withLock StockAutomationCycleResult(
                    checkedAt = marketNow.toLocalDateTime(),
                    notices = emptyList(),
                    skippedReason = "KIS 휴장일이므로 자동화 규칙을 확인하지 않습니다.",
                )
            }
            if (
                habitDao.getUnfinishedStockOrders().any {
                    it.status == StockOrderStatus.SUBMITTED.name ||
                        it.status == StockOrderStatus.PARTIALLY_FILLED.name
                }
            ) {
                syncStockOrderExecutions()
            }
            val (config, accessToken) = getKisConfigAndAccessToken()
            val wasBlocked = safety.globalOrderBlocked
            safety = refreshCrashGuard(safety, config, accessToken)
            if (safety.globalOrderBlocked) {
                if (!wasBlocked) {
                    notices += StockAutomationNotice(
                        title = "급락 안전장치 발동",
                        message = "${safety.blockReason.orEmpty()} 전체 주문을 차단했습니다.",
                    )
                }
                return@withLock StockAutomationCycleResult(LocalDateTime.now(), notices)
            }

            val rulesByProduct = habitDao.getEnabledStockExitRules().groupBy(StockExitRuleEntity::productCode)
            if (rulesByProduct.isEmpty()) {
                return@withLock StockAutomationCycleResult(LocalDateTime.now(), emptyList())
            }
            val activeRuleCount = rulesByProduct.values.sumOf { rules -> rules.size }
            var monitoredProductCount = 0
            val balanceMap = withKisAccessTokenRetry(config, accessToken) { retryConfig, retryToken ->
                kisDomesticStockClient.getBalance(retryConfig, retryToken, market)
            }.associateBy(KisBalanceStock::productCode)
            for ((productCode, rules) in rulesByProduct) {
                val balance = balanceMap[productCode] ?: continue
                val holdingQuantity = balance.quantity.toLongOrNull() ?: continue
                if (holdingQuantity <= 0L) continue
                val averagePrice = balance.averagePrice.toDoubleOrNull()?.takeIf { it > 0.0 } ?: continue
                val currentPrice = balance.currentPrice.toLongOrNull()?.takeIf { it > 0L }
                    ?: try {
                        withKisAccessTokenRetry(config, accessToken) { retryConfig, retryToken ->
                            kisDomesticStockClient.getCurrentPrice(retryConfig, retryToken, productCode, market)
                        }.currentPrice.toLong()
                    } catch (error: Exception) {
                        saveStockAutomationEvent(
                            level = "ERROR",
                            eventType = "PRICE_CHECK_FAILED",
                            productCode = productCode,
                            message = "${balance.productName} 현재가 확인에 실패했습니다. ${error.message.orEmpty()}",
                        )
                        continue
                }
                monitoredProductCount += 1
                notices += evaluateStockExitRules(
                    safety = safety,
                    market = market,
                    balance = balance,
                    holdingQuantity = holdingQuantity,
                    averagePrice = averagePrice,
                    currentPrice = currentPrice,
                    rules = rules,
                    persistReferenceHighPrices = true,
                ).notices
            }
            StockAutomationCycleResult(
                checkedAt = LocalDateTime.now(),
                notices = notices,
                monitoredProductCount = monitoredProductCount,
                activeRuleCount = activeRuleCount,
            )
        }
    }

    private suspend fun evaluateStockExitRules(
        safety: StockSafetyConfigEntity,
        market: KisStockMarket,
        balance: KisBalanceStock,
        holdingQuantity: Long,
        averagePrice: Double,
        currentPrice: Long,
        rules: List<StockExitRuleEntity>,
        ignoredRuleIds: Set<Long> = emptySet(),
        referenceHighPrices: Map<Long, Long> = emptyMap(),
        persistReferenceHighPrices: Boolean,
    ): StockRuleEvaluationResult {
        val notices = mutableListOf<StockAutomationNotice>()
        val triggeredRuleIds = mutableSetOf<Long>()
        val updatedReferenceHighPrices = mutableMapOf<Long, Long>()
        val returnPercent = (currentPrice.toDouble() - averagePrice) / averagePrice * 100.0
        var autoOrderSubmitted = false

        for (storedRule in rules.sortedBy(StockExitRuleEntity::createdAt)) {
            var rule = storedRule
            val ruleType = StockExitRuleType.values().firstOrNull { it.name == rule.ruleType } ?: continue
            if (ruleType == StockExitRuleType.TRAILING_STOP && rule.triggerPrice == null) {
                val storedHigh = max(
                    rule.referenceHighPrice ?: currentPrice,
                    referenceHighPrices[rule.id] ?: currentPrice,
                )
                val newHigh = max(storedHigh, currentPrice)
                updatedReferenceHighPrices[rule.id] = newHigh
                if (persistReferenceHighPrices && newHigh != rule.referenceHighPrice) {
                    rule = rule.copy(referenceHighPrice = newHigh, updatedAt = LocalDateTime.now())
                    persistChange { habitDao.updateStockExitRule(rule) }
                } else {
                    rule = rule.copy(referenceHighPrice = newHigh)
                }
            }
            if (rule.id in ignoredRuleIds) continue

            val triggered = when (ruleType) {
                StockExitRuleType.STOP_LOSS -> rule.triggerPrice?.let { currentPrice <= it }
                    ?: (returnPercent <= -rule.triggerValue)
                StockExitRuleType.TAKE_PROFIT -> rule.triggerPrice?.let { currentPrice >= it }
                    ?: (returnPercent >= rule.triggerValue)
                StockExitRuleType.TRAILING_STOP -> {
                    rule.triggerPrice?.let { currentPrice <= it } ?: run {
                        val high = rule.referenceHighPrice ?: currentPrice
                        currentPrice.toDouble() <= high.toDouble() * (1.0 - rule.triggerValue / 100.0)
                    }
                }
                StockExitRuleType.TIME_EXIT -> {
                    val oldestBuyDate = habitDao.getOldestOpenStockBuyDate(balance.productCode)
                    oldestBuyDate != null && ChronoUnit.DAYS.between(oldestBuyDate, LocalDate.now()) >= rule.triggerValue.toLong()
                }
            }
            if (!triggered) continue

            triggeredRuleIds += rule.id
            val triggerMessage = when (ruleType) {
                StockExitRuleType.STOP_LOSS -> rule.triggerPrice?.let { "발동 조건: ${formatStockPrice(it)} 이하" }
                    ?: "발동 조건: 손절 -${formatStockRuleValue(rule.triggerValue)}% 이하"
                StockExitRuleType.TAKE_PROFIT -> rule.triggerPrice?.let { "발동 조건: ${formatStockPrice(it)} 이상" }
                    ?: "발동 조건: 익절 +${formatStockRuleValue(rule.triggerValue)}% 이상"
                StockExitRuleType.TRAILING_STOP -> rule.triggerPrice?.let { "발동 조건: ${formatStockPrice(it)} 이하" }
                    ?: "발동 조건: 고점 ${formatStockPrice(rule.referenceHighPrice ?: currentPrice)} 대비 -${formatStockRuleValue(rule.triggerValue)}%"
                StockExitRuleType.TIME_EXIT -> "발동 조건: 보유 ${rule.triggerValue.toLong()}일 도달"
            }
            val action = StockRuleAction.values().firstOrNull { it.name == rule.actionMode }
                ?: StockRuleAction.NOTIFY_ONLY
            var shouldDisableRule = action == StockRuleAction.NOTIFY_ONLY
            val noticeLines = mutableListOf(
                "${balance.productName} (${balance.productCode}) · ${market.stockNotificationLabel()}",
                "현재가 ${formatStockPrice(currentPrice)} · 평균가 ${formatStockPrice(averagePrice.toLong())} · 수익률 ${formatStockReturn(returnPercent)}",
                "보유 ${holdingQuantity}주",
                triggerMessage,
            )
            if (action == StockRuleAction.NOTIFY_ONLY) {
                noticeLines += "처리: 알림만 · 주문 없음"
            } else {
                val sellQuantity = floor(holdingQuantity * rule.sellQuantityPercent / 100.0)
                    .toLong()
                    .coerceAtLeast(1L)
                    .coerceAtMost(holdingQuantity)
                val orderTypeLabel = if (rule.orderDivisionCode == stockMarketOrderCode) "시장가" else "현재가 지정가"
                if (!safety.automaticOrderEnabled) {
                    noticeLines += "처리: 자동매도 꺼짐 · ${orderTypeLabel} ${sellQuantity}주 주문 없음"
                } else if (sellQuantity <= 0L) {
                    noticeLines += "처리: 계산된 매도 수량 0주 · 주문 없음"
                } else {
                    val source = when (ruleType) {
                        StockExitRuleType.STOP_LOSS -> StockOrderSource.STOP_LOSS
                        StockExitRuleType.TAKE_PROFIT -> StockOrderSource.TAKE_PROFIT
                        StockExitRuleType.TRAILING_STOP -> StockOrderSource.TRAILING_STOP
                        StockExitRuleType.TIME_EXIT -> StockOrderSource.TIME_EXIT
                    }
                    runCatching {
                        placeKisCashOrder(
                            draft = KisCashOrderDraft(
                                side = KisOrderSide.SELL,
                                productCode = balance.productCode,
                                orderDivisionCode = rule.orderDivisionCode,
                                orderQuantity = sellQuantity.toString(),
                                orderUnitPrice = if (rule.orderDivisionCode == stockMarketOrderCode) "0" else currentPrice.toString(),
                                exchangeIdDivisionCode = market.orderExchangeCode,
                                sellType = "01",
                                conditionPrice = "",
                            ),
                            productName = balance.productName,
                            source = source,
                            requireAutomaticEnabled = true,
                            verifiedCurrentPrice = currentPrice,
                            skipCrashGuardRefresh = true,
                        )
                    }.onSuccess { order ->
                        noticeLines += "처리: ${orderTypeLabel} ${sellQuantity}주 매도 접수 · 주문번호 ${order.orderNumber}"
                        autoOrderSubmitted = true
                        shouldDisableRule = true
                    }.onFailure { error ->
                        noticeLines += "처리: ${orderTypeLabel} ${sellQuantity}주 매도 실패 · ${error.message.orEmpty()}"
                    }
                }
            }
            val noticeMessage = noticeLines.joinToString("\n")
            notices += StockAutomationNotice(
                title = "[${balance.productName}] ${ruleType.label} 조건 충족",
                message = noticeMessage,
                productCode = balance.productCode,
            )
            saveStockAutomationEvent(
                level = if (noticeMessage.contains("실패")) "ERROR" else "WARN",
                eventType = "EXIT_RULE_TRIGGERED",
                productCode = balance.productCode,
                message = noticeMessage,
            )
            if (shouldDisableRule) {
                persistChange {
                    habitDao.updateStockExitRule(
                        rule.copy(
                            enabled = false,
                            lastTriggeredAt = LocalDateTime.now(),
                            updatedAt = LocalDateTime.now(),
                        ),
                    )
                }
            }
            if (autoOrderSubmitted) break
        }
        return StockRuleEvaluationResult(
            notices = notices,
            triggeredRuleIds = triggeredRuleIds,
            referenceHighPrices = updatedReferenceHighPrices,
        )
    }

    private data class StockRuleEvaluationResult(
        val notices: List<StockAutomationNotice>,
        val triggeredRuleIds: Set<Long>,
        val referenceHighPrices: Map<Long, Long>,
    )

    private fun resolveActiveStockMarket(time: LocalTime): KisStockMarket? = when {
        time >= regularMarketOpenTime && time < regularMarketCloseTime -> KisStockMarket.UNIFIED
        time >= nxtAfterMarketOpenTime && time < nxtAfterMarketCloseTime -> KisStockMarket.NXT
        else -> null
    }

    fun getCurrentStockOrderExchangeCode(): String = currentStockMarket().orderExchangeCode

    private fun currentStockMarket(): KisStockMarket {
        val marketTime = ZonedDateTime.now(ZoneId.of("Asia/Seoul")).toLocalTime()
        return resolveActiveStockMarket(marketTime) ?: KisStockMarket.KRX
    }

    private fun KisStockMarket.stockNotificationLabel(): String = when (this) {
        KisStockMarket.KRX -> "KRX"
        KisStockMarket.NXT -> "NXT 애프터마켓"
        KisStockMarket.UNIFIED -> "KRX·NXT 통합장"
    }

    private fun formatStockPrice(price: Long): String = "%,d원".format(price)

    private fun formatStockReturn(returnPercent: Double): String = "%+.2f%%".format(returnPercent)

    private fun formatStockRuleValue(value: Double): String = "%.2f".format(value).trimEnd('0').trimEnd('.')

    private fun isStockOrderBlocked(
        safety: StockSafetyConfigEntity,
        isEmergencyLiquidation: Boolean,
    ): Boolean = safety.globalOrderBlocked &&
        !(isEmergencyLiquidation && safety.isCrashGuardOrderBlock())

    private suspend fun refreshCrashGuard(
        safety: StockSafetyConfigEntity,
        config: KisApiConfig,
        accessToken: String,
    ): StockSafetyConfigEntity {
        if (safety.globalOrderBlocked || !safety.crashGuardEnabled) return safety
        val benchmarkCode = safety.crashBenchmarkCode ?: return safety
        val threshold = safety.crashThresholdPercent ?: return safety
        val index = withKisAccessTokenRetry(config, accessToken) { retryConfig, retryToken ->
            kisDomesticStockClient.getIndexPrice(retryConfig, retryToken, benchmarkCode)
        }
        if (index.changeRatePercent > -threshold) return safety
        val reason = "$STOCK_CRASH_GUARD_BLOCK_PREFIX ${index.name} 전일 대비 ${"%.2f".format(index.changeRatePercent)}%로 급락 기준 -${threshold}% 도달"
        val blocked = safety.copy(
            globalOrderBlocked = true,
            blockReason = reason,
            updatedAt = LocalDateTime.now(),
        )
        persistChange { habitDao.upsertStockSafetyConfig(blocked) }
        saveStockAutomationEvent(
            level = "WARN",
            eventType = "CRASH_GUARD_TRIGGERED",
            message = "$reason. 전체 주문을 차단했습니다.",
        )
        return blocked
    }

    private suspend fun applyStockExecutionInTransaction(
        order: StockOrderEntity,
        filledQuantity: Long,
        filledAveragePrice: Long?,
        canceledQuantity: Long,
        rejectedQuantity: Long,
        isCanceled: Boolean,
    ) {
        val normalizedFilledQuantity = filledQuantity
            .coerceAtLeast(order.filledQuantity)
            .coerceAtMost(order.requestedQuantity)
        val status = when {
            rejectedQuantity > 0L && normalizedFilledQuantity == 0L -> StockOrderStatus.REJECTED
            isCanceled || canceledQuantity > 0L -> StockOrderStatus.CANCELED
            normalizedFilledQuantity == 0L -> StockOrderStatus.SUBMITTED
            normalizedFilledQuantity < order.requestedQuantity -> StockOrderStatus.PARTIALLY_FILLED
            else -> StockOrderStatus.FILLED
        }
        val effectiveFilledAveragePrice = filledAveragePrice ?: order.filledAveragePrice
        if (order.side == KisOrderSide.BUY.name) {
            val alreadyAllocatedQuantity =
                (order.filledQuantity - order.remainingQuantity).coerceAtLeast(0L)
            habitDao.updateStockOrder(
                order.copy(
                    status = status.name,
                    filledQuantity = normalizedFilledQuantity,
                    filledAveragePrice = effectiveFilledAveragePrice,
                    remainingQuantity =
                        (normalizedFilledQuantity - alreadyAllocatedQuantity).coerceAtLeast(0L),
                    updatedAt = LocalDateTime.now(),
                ),
            )
            return
        }

        var appliedQuantity = order.appliedFilledQuantity.coerceAtMost(normalizedFilledQuantity)
        var realizedProfit = order.estimatedRealizedProfit
        val isFinalExecution = status == StockOrderStatus.FILLED ||
            status == StockOrderStatus.CANCELED ||
            status == StockOrderStatus.REJECTED
        val intendedBuyOrder = if (isFinalExecution) {
            order.intendedBuyOrderId?.let { habitDao.getStockOrderById(it) }
        } else {
            null
        }
        if (
            intendedBuyOrder != null &&
            intendedBuyOrder.side == KisOrderSide.BUY.name &&
            intendedBuyOrder.productCode == order.productCode
        ) {
            val quantityToAllocate = minOf(
                (normalizedFilledQuantity - appliedQuantity).coerceAtLeast(0L),
                intendedBuyOrder.remainingQuantity,
            )
            val buyPrice = intendedBuyOrder.filledAveragePrice ?: intendedBuyOrder.referencePrice
            val sellPrice = effectiveFilledAveragePrice ?: order.referencePrice
            if (quantityToAllocate > 0L && buyPrice > 0L && sellPrice > 0L) {
                val profitToAdd = Math.multiplyExact(sellPrice - buyPrice, quantityToAllocate)
                habitDao.insertStockSellAllocation(
                    StockSellAllocationEntity(
                        sellOrderId = order.id,
                        buyOrderId = intendedBuyOrder.id,
                        quantity = quantityToAllocate,
                        buyUnitPrice = buyPrice,
                        sellUnitPrice = sellPrice,
                        realizedProfit = profitToAdd,
                    ),
                )
                habitDao.updateStockOrder(
                    intendedBuyOrder.copy(
                        remainingQuantity = intendedBuyOrder.remainingQuantity - quantityToAllocate,
                        updatedAt = LocalDateTime.now(),
                    ),
                )
                appliedQuantity += quantityToAllocate
                realizedProfit = Math.addExact(realizedProfit ?: 0L, profitToAdd)
            }
        }

        habitDao.updateStockOrder(
            order.copy(
                status = status.name,
                filledQuantity = normalizedFilledQuantity,
                appliedFilledQuantity = appliedQuantity,
                filledAveragePrice = effectiveFilledAveragePrice,
                estimatedRealizedProfit = realizedProfit,
                updatedAt = LocalDateTime.now(),
            ),
        )
    }

    private suspend fun saveStockAutomationEvent(
        level: String,
        eventType: String,
        productCode: String? = null,
        message: String,
    ) {
        persistChange {
            habitDao.insertStockAutomationEvent(
                StockAutomationEventEntity(
                    level = level,
                    eventType = eventType,
                    productCode = productCode,
                    message = message.take(500),
                    createdAt = LocalDateTime.now(),
                ),
            )
        }
    }

    private fun readKisMarketCapCache(date: LocalDate): List<KisMarketCapStock> {
        if (kisMarketCapPrefs.getString(kisMarketCapCacheDateKey, null) != date.toString()) return emptyList()
        val rawItems = kisMarketCapPrefs.getString(kisMarketCapCacheItemsKey, null) ?: return emptyList()
        val items = runCatching { JSONArray(rawItems) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val rank = item.optInt("rank").takeIf { it > 0 } ?: continue
                val productCode = item.optString("productCode")
                val productName = item.optString("productName")
                if (productCode.isNotBlank() && productName.isNotBlank()) {
                    add(KisMarketCapStock(rank, productCode, productName))
                }
            }
        }
    }

    private fun saveKisMarketCapCache(date: LocalDate, stocks: List<KisMarketCapStock>) {
        val items = JSONArray()
        stocks.forEach { stock ->
            items.put(
                JSONObject()
                    .put("rank", stock.rank)
                    .put("productCode", stock.productCode)
                    .put("productName", stock.productName),
            )
        }
        kisMarketCapPrefs.edit()
            .putString(kisMarketCapCacheDateKey, date.toString())
            .putString(kisMarketCapCacheItemsKey, items.toString())
            .apply()
    }

    private suspend fun getKisConfigAndAccessToken(): Pair<KisApiConfig, String> = kisAccessTokenMutex.withLock {
        val environment = KisEnvironment.REAL
        val entity = habitDao.getKisApiConfig(environment.apiValue)
            ?: throw IllegalStateException("KIS 설정을 먼저 저장해 주세요.")
        val config = entity.toKisApiConfig(kisConfigCipher)
        val cachedToken = entity.encryptedAccessToken
            ?.takeIf { entity.accessTokenExpiredAt?.isAfter(LocalDateTime.now().plusMinutes(5)) == true }
            ?.let(kisConfigCipher::decrypt)
        val accessToken = cachedToken ?: kisDomesticStockClient.issueAccessToken(config).also { token ->
            habitDao.updateKisAccessToken(
                environment = environment.apiValue,
                encryptedAccessToken = kisConfigCipher.encrypt(token.value),
                accessTokenExpiredAt = token.expiresAt,
                updatedAt = LocalDateTime.now(),
            )
        }.value
        config to accessToken
    }

    private suspend fun <T> withKisAccessTokenRetry(
        config: KisApiConfig,
        accessToken: String,
        request: (KisApiConfig, String) -> T,
    ): T = try {
        request(config, accessToken)
    } catch (error: Exception) {
        if (!kisDomesticStockClient.isAccessTokenError(error)) throw error
        val (refreshedConfig, refreshedToken) = refreshKisConfigAndAccessToken(accessToken)
        request(refreshedConfig, refreshedToken)
    }

    private suspend fun refreshKisConfigAndAccessToken(failedAccessToken: String): Pair<KisApiConfig, String> =
        kisAccessTokenMutex.withLock {
            val environment = KisEnvironment.REAL
            val entity = habitDao.getKisApiConfig(environment.apiValue)
                ?: throw IllegalStateException("KIS 설정을 먼저 저장해 주세요.")
            val config = entity.toKisApiConfig(kisConfigCipher)
            val storedToken = entity.encryptedAccessToken
                ?.let(kisConfigCipher::decrypt)
                ?.takeIf(String::isNotBlank)
            if (
                storedToken != null &&
                storedToken != failedAccessToken &&
                entity.accessTokenExpiredAt?.isAfter(LocalDateTime.now().plusMinutes(5)) == true
            ) {
                return@withLock config to storedToken
            }
            val token = kisDomesticStockClient.issueAccessToken(config)
            habitDao.updateKisAccessToken(
                environment = environment.apiValue,
                encryptedAccessToken = kisConfigCipher.encrypt(token.value),
                accessTokenExpiredAt = token.expiresAt,
                updatedAt = LocalDateTime.now(),
            )
            config to token.value
        }

    suspend fun getVocabularyWord(wordId: Long): VocabularyWordEntity? =
        habitDao.getVocabularyWordById(wordId)

    suspend fun getAllVocabularyWords(): List<VocabularyWordEntity> =
        habitDao.getAllVocabularyWords()

    suspend fun verifyMemoPassword(memoId: Long, password: String): MemoNoteEntity {
        val memoNote = habitDao.getMemoNoteById(memoId) ?: throw IllegalArgumentException("메모를 찾을 수 없습니다.")
        require(memoNote.isLocked) { "잠금 메모가 아닙니다." }
        require(password.matches(Regex("\\d{4,10}"))) { "비밀번호는 4~10자리 숫자로 입력해 주세요." }
        require(memoNote.passwordHash == hashPin(password)) { "비밀번호가 올바르지 않습니다." }
        return memoNote
    }

    suspend fun searchDiaries(query: String, limit: Int = 20): List<DiarySearchRow> =
        habitDao.searchDiaries(query.trim(), limit)

    suspend fun getRecordDetails(recordDate: LocalDate): List<RecordDetailRow> =
        habitDao.getRecordDetails(recordDate)

    suspend fun getLatestLottoRoundNo(): Int? =
        habitDao.getLatestLottoRoundNo()

    suspend fun getAllLottoHistory(): List<List<Int>> {
        val draws = habitDao.getAllLottoDrawsDesc()
        val gapIndex = draws.zipWithNext().indexOfFirst { (newer, older) ->
            newer.roundNo - older.roundNo != 1
        }
        if (gapIndex < 0) return draws.map(LottoDrawEntity::numbers)

        val newer = draws[gapIndex]
        val older = draws[gapIndex + 1]
        val oldestBundledRoundNo = LottoSeedData.draws.minOfOrNull { draw -> draw.roundNo }
        require(oldestBundledRoundNo != null && newer.roundNo <= oldestBundledRoundNo) {
            "${older.roundNo + 1}회차부터 ${newer.roundNo - 1}회차까지 추첨 데이터가 누락되었습니다. 누락 회차를 먼저 저장해 주세요."
        }
        return draws.take(gapIndex + 1).map(LottoDrawEntity::numbers)
    }

    suspend fun syncBundledLottoDraws(): Boolean {
        val seedRoundNos = LottoSeedData.draws.map { it.roundNo }
        val existingDraws = habitDao.getLottoDrawsByRoundNos(seedRoundNos)
            .associateBy(LottoDrawEntity::roundNo)
        val missingDraws = LottoSeedData.draws
            .filterNot { seed -> seed.roundNo in existingDraws }
            .map { seed ->
                LottoDrawEntity.from(
                    roundNo = seed.roundNo,
                    numbers = seed.numbers,
                    bonusNumber = seed.bonusNumber,
                    dataSource = lottoDrawSourceSeed,
                )
            }
        val supplementedDraws = LottoSeedData.draws.mapNotNull { seed ->
            val existing = existingDraws[seed.roundNo] ?: return@mapNotNull null
            val seedBonusNumber = seed.bonusNumber ?: return@mapNotNull null
            if (
                existing.dataSource == lottoDrawSourceManual ||
                existing.bonusNumber != null ||
                existing.numbers() != seed.numbers.sorted()
            ) {
                return@mapNotNull null
            }
            existing.copy(
                bonusNumber = seedBonusNumber,
                dataSource = lottoDrawSourceSeed,
            )
        }
        if (missingDraws.isEmpty() && supplementedDraws.isEmpty()) return false
        persistChange {
            database.withTransaction {
                if (missingDraws.isNotEmpty()) {
                    habitDao.insertLottoDraws(missingDraws)
                }
                supplementedDraws.forEach { draw ->
                    habitDao.upsertLottoDraw(draw)
                }
            }
        }
        return true
    }

    suspend fun ensureLottoWinningStatsInitialized(force: Boolean = false) {
        val statVersion = lottoPrefs.getInt(lottoStatVersionKey, 0)
        if (!force && statVersion >= currentLottoStatVersion) return
        persistChange {
            database.withTransaction {
                recalculateAllLottoWinningStats()
            }
        }
        lottoPrefs.edit().putInt(lottoStatVersionKey, currentLottoStatVersion).apply()
    }

    suspend fun saveLottoDraw(roundNo: Int?, numbers: List<Int>, bonusNumber: Int?): Int {
        require(roundNo != null && roundNo > 0) { "회차 번호를 입력해 주세요." }
        require(numbers.size == 6) { "번호 6개를 모두 입력해 주세요." }
        require(bonusNumber != null) { "보너스 번호를 입력해 주세요." }
        val safeRoundNo = roundNo

        val sanitizedNumbers = numbers.map { number ->
            require(number in 1..45) { "번호는 1부터 45 사이여야 합니다." }
            number
        }.sorted()

        require(sanitizedNumbers.distinct().size == 6) { "번호는 중복 없이 입력해 주세요." }

        val sanitizedBonusNumber = bonusNumber.also { number ->
            require(number in 1..45) { "보너스 번호는 1부터 45 사이여야 합니다." }
            require(number !in sanitizedNumbers) { "보너스 번호는 당첨 번호와 중복될 수 없습니다." }
        }
        val newDraw = LottoDrawEntity.from(
            roundNo = safeRoundNo,
            numbers = sanitizedNumbers,
            bonusNumber = sanitizedBonusNumber,
            dataSource = lottoDrawSourceManual,
        )

        return persistChange {
            database.withTransaction {
                val existingDraw = habitDao.getLottoDrawByRoundNo(safeRoundNo)
                val latestRoundNo = habitDao.getLatestLottoRoundNo()
                if (existingDraw == null && latestRoundNo != null) {
                    require(safeRoundNo <= latestRoundNo + 1) {
                        "${latestRoundNo + 1}회차부터 순서대로 저장해 주세요."
                    }
                }

                val drawToSave = if (existingDraw == null) {
                    newDraw
                } else {
                    require(existingDraw.numbers() == newDraw.numbers()) {
                        "${safeRoundNo}회차 추첨 번호가 이미 저장되어 있어 덮어쓸 수 없습니다."
                    }
                    require(existingDraw.bonusNumber == null || existingDraw.bonusNumber == sanitizedBonusNumber) {
                        "${safeRoundNo}회차 보너스 번호가 이미 저장되어 있어 덮어쓸 수 없습니다."
                    }
                    existingDraw.copy(
                        bonusNumber = sanitizedBonusNumber,
                        dataSource = if (existingDraw.dataSource == lottoDrawSourceSeed) {
                            existingDraw.dataSource
                        } else {
                            lottoDrawSourceManual
                        },
                    )
                }

                habitDao.upsertLottoDraw(drawToSave)
                updateLottoWinningStatsForRound(roundNo = safeRoundNo, draw = drawToSave)
            }
            safeRoundNo
        }
    }

    suspend fun saveLottoTicket(numbers: List<Int>, sourceLabel: String, note: String? = null) {
        require(numbers.size == 6) { "저장할 번호는 6개여야 합니다." }
        val sanitizedNumbers = numbers.map { number ->
            require(number in 1..45) { "번호는 1부터 45 사이여야 합니다." }
            number
        }.sorted()
        require(sanitizedNumbers.distinct().size == 6) { "번호는 중복 없이 저장해 주세요." }
        val sanitizedNote = note?.trim()?.takeIf(String::isNotEmpty)
        persistChange {
            habitDao.insertLottoTicket(
                LottoTicketEntity.from(
                    sourceLabel = sourceLabel,
                    numbers = sanitizedNumbers,
                    note = sanitizedNote,
                    roundNo = sanitizedNote?.let(::extractLottoRoundNo),
                ),
            )
        }
    }

    suspend fun saveLottoPurchase(
        purchaseDate: LocalDate,
        lottoType: String,
        roundNo: Int?,
        amount: Int,
        memo: String?,
    ) {
        val safeType = lottoType.trim().ifEmpty { "로또" }
        require(safeType in listOf("로또", "연금")) { "로또 형태는 로또 또는 연금 중 선택해 주세요." }
        if (safeType == "로또") {
            require(roundNo != null && roundNo > 0) { "구입한 로또 회차를 입력해 주세요." }
        }
        require(amount > 0) { "구입 금액을 입력해 주세요." }
        persistChange {
            habitDao.insertLottoPurchase(
                LottoPurchaseEntity(
                    purchaseDate = purchaseDate,
                    lottoType = safeType,
                    roundNo = roundNo.takeIf { safeType == "로또" },
                    amount = amount,
                    memo = memo?.trim()?.takeIf(String::isNotEmpty),
                ),
            )
        }
    }

    suspend fun deleteLottoPurchase(purchaseId: Long) {
        persistChange {
            habitDao.deleteLottoPurchaseById(purchaseId)
        }
    }

    suspend fun saveLottoWinning(roundNo: Int, amount: Long, memo: String?) {
        saveLottoWinning(roundNo = roundNo, amount = amount, sourceLabel = "기타", memo = memo)
    }

    suspend fun saveLottoWinning(roundNo: Int, amount: Long, sourceLabel: String, memo: String?) {
        require(roundNo > 0) { "회차 번호를 입력해 주세요." }
        require(amount > 0L) { "당첨 금액을 입력해 주세요." }
        val safeSourceLabel = sourceLabel.trim().ifBlank { "기타" }
        require(safeSourceLabel == "기타" || safeSourceLabel in generatedLottoSources) {
            "지원하지 않는 당첨 번호 출처입니다."
        }
        persistChange {
            habitDao.insertLottoWinning(
                LottoWinningEntity(
                    roundNo = roundNo,
                    amount = amount,
                    sourceLabel = safeSourceLabel,
                    memo = memo?.trim()?.takeIf(String::isNotEmpty),
                ),
            )
        }
    }

    suspend fun saveCardHistory(useDate: LocalDate, amount: Long, memo: String?) {
        require(amount != 0L) { "결제 예정 금액을 입력해 주세요." }
        persistChange {
            habitDao.insertCardHistory(
                CardHistoryEntity(
                    useDate = useDate,
                    amount = amount,
                    memo = memo?.trim()?.takeIf(String::isNotEmpty),
                ),
            )
        }
    }

    suspend fun deleteCardHistory(historyId: Long) {
        persistChange {
            habitDao.deleteCardHistoryById(historyId)
        }
    }

    suspend fun seedCardHistoriesIfEmpty() {
        val historyCount = habitDao.getCardHistoryCount()
        val seedVersion = cardPrefs.getInt(cardSeedVersionKey, 0)
        if (historyCount > 0 && seedVersion >= currentCardSeedVersion) return
        persistChange {
            if (historyCount > 0) {
                habitDao.deleteAllCardHistories()
            }
            habitDao.insertCardHistories(
                CardHistorySeedData.entries.map { seed ->
                    CardHistoryEntity(
                        useDate = seed.useDate,
                        amount = seed.amount,
                    )
                },
            )
        }
        cardPrefs.edit().putInt(cardSeedVersionKey, currentCardSeedVersion).apply()
        if (!cardPrefs.contains(cardPaymentDayKey)) {
            cardPrefs.edit().putInt(cardPaymentDayKey, 9).apply()
        }
    }

    fun getCardPaymentDay(): Int =
        cardPrefs.getInt(cardPaymentDayKey, 9)

    fun saveCardPaymentDay(day: Int) {
        require(day in 1..28) { "결제일은 1일부터 28일 사이로 입력해 주세요." }
        cardPrefs.edit().putInt(cardPaymentDayKey, day).apply()
    }

    suspend fun deleteLottoWinning(winningId: Long) {
        persistChange {
            habitDao.deleteLottoWinningById(winningId)
        }
    }

    suspend fun getSavedLottoBatchCount(roundNo: Int, sourceLabel: String): Int {
        require(roundNo > 0) { "회차 번호가 올바르지 않습니다." }
        return habitDao.getLottoTicketsBySourceAndRound(
            sourceLabel = sourceLabel,
            roundNo = roundNo,
        ).mapNotNull(LottoTicketEntity::note).distinct().size
    }

    suspend fun deleteLottoTicket(ticketId: Long) {
        persistChange {
            database.withTransaction {
                val ticket = habitDao.getLottoTicketById(ticketId)
                habitDao.deleteLottoTicketById(ticketId)
                if (ticket?.isPurchased == true || ticket?.isEvaluationTarget == true) {
                    refreshLottoWinningStats(
                        roundNo = ticket.roundNo,
                        note = ticket.note,
                    )
                }
            }
        }
    }

    suspend fun deleteLottoSet(sourceLabel: String, note: String) {
        require(sourceLabel.isNotBlank()) { "삭제할 세트 출처를 찾을 수 없습니다." }
        require(note.isNotBlank()) { "삭제할 세트를 찾을 수 없습니다." }
        persistChange {
            database.withTransaction {
                val tickets = habitDao.getLottoTicketsBySourceAndNote(sourceLabel, note)
                habitDao.deleteLottoTicketsBySourceAndNoteExact(sourceLabel, note)
                if (tickets.any { ticket -> ticket.isPurchased || ticket.isEvaluationTarget }) {
                    refreshLottoWinningStats(
                        roundNo = tickets.firstNotNullOfOrNull(LottoTicketEntity::roundNo),
                        note = note,
                    )
                }
            }
        }
    }

    suspend fun markLottoSetPurchased(sourceLabel: String, note: String) {
        require(sourceLabel.isNotBlank()) { "구매 처리할 세트 출처를 찾을 수 없습니다." }
        require(note.isNotBlank()) { "구매 처리할 세트를 찾을 수 없습니다." }
        persistChange {
            database.withTransaction {
                val tickets = habitDao.getLottoTicketsBySourceAndNote(sourceLabel, note)
                require(tickets.isNotEmpty()) { "구매 처리할 세트를 찾을 수 없습니다." }
                require(tickets.none(LottoTicketEntity::isPurchased)) { "이미 구매 처리된 세트입니다." }
                val roundNo = tickets.firstNotNullOfOrNull(LottoTicketEntity::roundNo)
                    ?: extractLottoRoundNo(note)
                    ?: throw IllegalArgumentException("구매 처리할 회차를 찾을 수 없습니다.")
                require(tickets.all { ticket -> (ticket.roundNo ?: roundNo) == roundNo }) {
                    "서로 다른 회차의 번호가 한 세트에 포함되어 있습니다."
                }
                val latestRoundNo = habitDao.getLatestLottoRoundNo()
                require(latestRoundNo != null && roundNo == latestRoundNo + 1) {
                    "구매 평가는 다음 회차인 ${(latestRoundNo ?: 0) + 1}회차 번호만 확정할 수 있습니다."
                }
                require(habitDao.getLottoDrawByRoundNo(roundNo) == null) {
                    "이미 추첨 결과가 저장된 회차는 성과 평가용 구매로 처리할 수 없습니다."
                }
                val updatedCount = habitDao.markLottoTicketsPurchasedBySourceAndNote(
                    sourceLabel = sourceLabel,
                    note = note,
                    confirmedAt = LocalDateTime.now(),
                )
                require(updatedCount > 0) { "구매 처리할 세트를 찾을 수 없습니다." }
                ensureRandomControlSet(roundNo)
            }
        }
    }

    suspend fun deleteLottoRound(roundNo: Int) {
        require(roundNo > 0) { "삭제할 회차를 확인해 주세요." }
        persistChange {
            database.withTransaction {
                habitDao.deleteLottoTicketsByRound(roundNo)
                habitDao.deleteLottoWinningStatRoundsByRound(roundNo)
                replaceLottoWinningStatTotals(habitDao.getAllLottoWinningStatRounds())
            }
        }
    }

    suspend fun saveLottoBatch(roundNo: Int, sourceLabel: String, tickets: List<LottoGeneratedTicket>): Int {
        require(roundNo > 0) { "저장할 회차를 확인해 주세요." }
        require(sourceLabel in generatedLottoSources) { "지원하지 않는 번호 생성 방식입니다." }
        require(tickets.size >= 5) { "저장할 생성 번호 5게임이 필요합니다." }

        val limitedTickets = tickets.take(5)

        return persistChange {
            database.withTransaction {
                val latestRoundNo = habitDao.getLatestLottoRoundNo()
                require(latestRoundNo != null && roundNo == latestRoundNo + 1) {
                    "생성 번호는 다음 회차인 ${(latestRoundNo ?: 0) + 1}회차에만 저장할 수 있습니다."
                }
                require(habitDao.getLottoDrawByRoundNo(roundNo) == null) {
                    "이미 추첨 결과가 저장된 회차에는 생성 번호를 저장할 수 없습니다."
                }
                val generationConfigHash = saveCurrentLottoGenerationConfig()
                val existingTickets = habitDao.getLottoTicketsBySourceAndRound(
                    sourceLabel = sourceLabel,
                    roundNo = roundNo,
                )
                val groupedNotes = existingTickets.mapNotNull(LottoTicketEntity::note).distinct()
                require(groupedNotes.size < maxSavedLottoSetCount) {
                    "${roundNo}회차 ${sourceLabel} 번호는 이미 ${maxSavedLottoSetCount}세트 저장되어 있습니다. 1세트를 삭제한 뒤 다시 저장해 주세요."
                }
                val nextSetIndex = nextLottoSetIndex(groupedNotes)
                val setNote = buildLottoSetNote(roundNo, nextSetIndex)
                limitedTickets.forEachIndexed { index, ticket ->
                    habitDao.insertLottoTicket(
                        LottoTicketEntity.from(
                            sourceLabel = sourceLabel,
                            numbers = ticket.numbers,
                            note = setNote,
                            roundNo = roundNo,
                            setNo = nextSetIndex,
                            generationVersion = LottoNumberGenerator.CURRENT_GENERATION_VERSION,
                            generationConfigHash = generationConfigHash,
                            historyThroughRound = latestRoundNo,
                            generationSeed = ticket.generationSeed,
                            analysisScore = ticket.score?.totalScore,
                            dataScore = ticket.score?.dataScore,
                            patternScore = ticket.score?.patternScore,
                            distributionScore = ticket.score?.distributionScore,
                            avoidanceScore = ticket.score?.avoidanceScore,
                            validationScore = ticket.score?.validationScore,
                            generationMode = ticket.generationMode,
                            recommendationRank = index + 1,
                        ),
                    )
                }
            }
            limitedTickets.size
        }
    }

    suspend fun saveMemoNote(memoId: Long?, title: String, content: String, isLocked: Boolean, password: String?) {
        val sanitizedTitle = title.trim()
        val sanitizedContent = content.trim()
        require(sanitizedTitle.isNotEmpty() || sanitizedContent.isNotEmpty()) { "제목 또는 내용을 입력해 주세요." }

        val passwordHash = if (isLocked) {
            require(!password.isNullOrBlank()) { "잠금 메모는 4~10자리 비밀번호를 입력해 주세요." }
            require(password.matches(Regex("\\d{4,10}"))) { "비밀번호는 4~10자리 숫자로 입력해 주세요." }
            hashPin(password)
        } else {
            null
        }

        val now = LocalDateTime.now()
        val existingMemo = if (memoId != null) {
            habitDao.getMemoNoteById(memoId)
        } else {
            null
        }
        persistChange {
            if (existingMemo == null) {
                habitDao.insertMemoNote(
                    MemoNoteEntity(
                        title = sanitizedTitle.ifEmpty { "제목 없음" },
                        content = sanitizedContent,
                        isLocked = isLocked,
                        passwordHash = passwordHash,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            } else {
                habitDao.updateMemoNote(
                    existingMemo.copy(
                        title = sanitizedTitle.ifEmpty { "제목 없음" },
                        content = sanitizedContent,
                        isLocked = isLocked,
                        passwordHash = passwordHash,
                        updatedAt = now,
                    ),
                )
            }
        }
    }

    suspend fun deleteMemoNote(memoId: Long) {
        require(memoId > 0) { "삭제할 메모를 찾을 수 없습니다." }
        persistChange {
            habitDao.deleteMemoNoteById(memoId)
        }
    }

    suspend fun updateMemoPinned(memoId: Long, isPinned: Boolean) {
        persistChange {
            habitDao.updateMemoPinned(memoId = memoId, isPinned = isPinned, updatedAt = LocalDateTime.now())
        }
    }

    suspend fun savePlant(
        plantId: Long?,
        name: String,
        imageUri: String?,
        memo: String?,
        wateringMonths: Int,
        wateringDays: Int,
        lastWateredDate: LocalDate,
    ) {
        val sanitizedName = name.trim()
        val sanitizedMemo = memo?.trim()?.takeIf(String::isNotEmpty)
        val sanitizedImageUri = imageUri?.trim()?.takeIf(String::isNotEmpty)
        require(sanitizedName.isNotEmpty()) { "화분 이름을 입력해 주세요." }
        require(wateringMonths >= 0 && wateringDays >= 0) { "물주기 주기는 0 이상이어야 합니다." }

        val intervalDays = (wateringMonths * 30) + wateringDays
        require(intervalDays > 0) { "물주기 주기를 입력해 주세요." }

        val now = LocalDateTime.now()
        val nextWateringDate = lastWateredDate.plusDays(intervalDays.toLong())
        val existingPlant = if (plantId != null) habitDao.getPlantById(plantId) else null

        persistChange {
            if (existingPlant == null) {
                habitDao.insertPlant(
                    PlantEntity(
                        name = sanitizedName,
                        imageUri = sanitizedImageUri,
                        memo = sanitizedMemo,
                        wateringIntervalDays = intervalDays,
                        lastWateredDate = lastWateredDate,
                        nextWateringDate = nextWateringDate,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            } else {
                habitDao.updatePlant(
                    existingPlant.copy(
                        name = sanitizedName,
                        imageUri = sanitizedImageUri,
                        memo = sanitizedMemo,
                        wateringIntervalDays = intervalDays,
                        lastWateredDate = lastWateredDate,
                        nextWateringDate = nextWateringDate,
                        updatedAt = now,
                    ),
                )
            }
        }
    }

    suspend fun completePlantWatering(plantId: Long, wateredDate: LocalDate = LocalDate.now()) {
        val existingPlant = habitDao.getPlantById(plantId) ?: throw IllegalArgumentException("화분 정보를 찾을 수 없습니다.")
        persistChange {
            habitDao.updatePlant(
                existingPlant.copy(
                    lastWateredDate = wateredDate,
                    nextWateringDate = wateredDate.plusDays(existingPlant.wateringIntervalDays.toLong()),
                    updatedAt = LocalDateTime.now(),
                ),
            )
        }
    }

    suspend fun deletePlant(plantId: Long) {
        val existingPlant = habitDao.getPlantById(plantId) ?: throw IllegalArgumentException("삭제할 화분을 찾을 수 없습니다.")
        persistChange {
            habitDao.deletePlant(existingPlant)
        }
    }

    suspend fun saveKisApiConfig(config: KisApiConfig) {
        val sanitizedAppKey = config.appKey.trim()
        val sanitizedAppSecret = config.appSecret.trim()
        val sanitizedAccountNumber = config.accountNumber.filter(Char::isDigit)
        val sanitizedAccountProductCode = config.accountProductCode.filter(Char::isDigit)

        require(sanitizedAppKey.isNotEmpty()) { "App Key를 입력해 주세요." }
        require(sanitizedAppSecret.isNotEmpty()) { "App Secret을 입력해 주세요." }
        require(sanitizedAccountNumber.length == 8) { "계좌번호 앞 8자리를 입력해 주세요." }
        require(sanitizedAccountProductCode.length == 2) { "계좌상품코드 2자리를 입력해 주세요." }

        persistChange {
            habitDao.upsertKisApiConfig(
                KisApiConfigEntity(
                    environment = config.environment.apiValue,
                    encryptedAppKey = kisConfigCipher.encrypt(sanitizedAppKey),
                    encryptedAppSecret = kisConfigCipher.encrypt(sanitizedAppSecret),
                    encryptedAccountNumber = kisConfigCipher.encrypt(sanitizedAccountNumber),
                    encryptedAccountProductCode = kisConfigCipher.encrypt(sanitizedAccountProductCode),
                    encryptedHtsId = null,
                    encryptedAccessToken = null,
                    accessTokenExpiredAt = null,
                    updatedAt = LocalDateTime.now(),
                ),
            )
        }
    }

    suspend fun saveVocabularyWord(wordId: Long?, word: String, meaning: String, pronunciation: String?) {
        val sanitizedWord = word.trim()
        val sanitizedMeaning = meaning.trim()
        val sanitizedPronunciation = pronunciation?.trim()?.takeIf(String::isNotEmpty)
        require(sanitizedWord.isNotEmpty()) { "단어를 입력해 주세요." }
        require(sanitizedMeaning.isNotEmpty()) { "뜻을 입력해 주세요." }
        require(habitDao.countDuplicateVocabulary(sanitizedWord, sanitizedMeaning, wordId) == 0) { "이미 같은 단어와 뜻이 등록되어 있습니다." }

        val now = LocalDateTime.now()
        val existingWord = if (wordId != null) habitDao.getVocabularyWordById(wordId) else null
        persistChange {
            if (existingWord == null) {
                habitDao.insertVocabularyWord(
                    VocabularyWordEntity(
                        word = sanitizedWord,
                        meaning = sanitizedMeaning,
                        pronunciation = sanitizedPronunciation,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            } else {
                habitDao.updateVocabularyWord(
                    existingWord.copy(
                        word = sanitizedWord,
                        meaning = sanitizedMeaning,
                        pronunciation = sanitizedPronunciation,
                        updatedAt = now,
                    ),
                )
            }
        }
    }

    suspend fun deleteVocabularyWord(wordId: Long) {
        val existingWord = habitDao.getVocabularyWordById(wordId) ?: throw IllegalArgumentException("삭제할 단어를 찾을 수 없습니다.")
        persistChange {
            habitDao.deleteVocabularyWord(existingWord)
        }
    }

    suspend fun bulkInsertVocabulary(rawInput: String): BulkVocabularyInsertResult {
        val lines = rawInput.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        require(lines.isNotEmpty()) { "등록할 단어를 입력해 주세요." }

        val now = LocalDateTime.now()
        val existingPairs = habitDao.getAllVocabularyWords()
            .asSequence()
            .map { it.word.trim().lowercase() to it.meaning.trim().lowercase() }
            .toMutableSet()
        val seenPairs = mutableSetOf<Pair<String, String>>()

        var skippedDuplicateCount = 0
        val entries = lines.mapNotNull { line ->
            val parts = line.split('\t', ',', '|').map(String::trim)
            require(parts.size >= 2) { "대량 등록 형식은 단어,뜻,발음 입니다." }
            val word = parts[0]
            val meaning = parts[1]
            val pronunciation = parts.getOrNull(2)?.takeIf(String::isNotBlank)
            require(word.isNotBlank() && meaning.isNotBlank()) { "단어와 뜻은 비워둘 수 없습니다." }
            val pair = word.lowercase() to meaning.lowercase()
            if (pair in existingPairs || !seenPairs.add(pair)) {
                skippedDuplicateCount += 1
                null
            } else {
                VocabularyWordEntity(
                word = word,
                meaning = meaning,
                pronunciation = pronunciation,
                createdAt = now,
                updatedAt = now,
            )
            }
        }

        require(entries.isNotEmpty()) { "이미 등록된 단어만 포함되어 있습니다." }
        return persistChange {
            val insertResults = habitDao.insertVocabularyWords(entries)
            val insertedCount = insertResults.count { it > 0L }
            val duplicateCount = skippedDuplicateCount + (insertResults.size - insertedCount)
            BulkVocabularyInsertResult(insertedCount = insertedCount, duplicateCount = duplicateCount)
        }
    }

    suspend fun recordVocabularyExposure(wordId: Long, isCorrect: Boolean) {
        recordVocabularyStudySession(
            records = listOf(VocabularyStudyRecord(wordId = wordId, isCorrect = isCorrect)),
        )
    }

    suspend fun recordVocabularyStudySession(
        records: List<VocabularyStudyRecord>,
        flashcardSeconds: Int = 0,
        testSeconds: Int = 0,
    ) {
        if (records.isEmpty()) return

        val flashcardShares = distributeStudySeconds(records.size, flashcardSeconds)
        val testShares = distributeStudySeconds(records.size, testSeconds)
        val aggregates = linkedMapOf<Long, VocabularyStudyAggregate>()

        records.forEachIndexed { index, record ->
            val aggregate = aggregates.getOrPut(record.wordId) { VocabularyStudyAggregate() }
            when (record.isCorrect) {
                true -> {
                    aggregate.correctCount += 1
                    aggregate.exposureCount += 1
                }
                false -> {
                    aggregate.wrongCount += 1
                    aggregate.exposureCount += 1
                }
                null -> Unit
            }
            aggregate.flashcardStudySeconds += flashcardShares[index]
            aggregate.testStudySeconds += testShares[index]
        }

        val now = LocalDateTime.now()
        persistChange {
            database.withTransaction {
                aggregates.forEach { (wordId, aggregate) ->
                    val existingWord = habitDao.getVocabularyWordById(wordId) ?: return@forEach
                    habitDao.updateVocabularyWord(
                        existingWord.copy(
                            correctCount = existingWord.correctCount + aggregate.correctCount,
                            wrongCount = existingWord.wrongCount + aggregate.wrongCount,
                            exposureCount = existingWord.exposureCount + aggregate.exposureCount,
                            flashcardStudySeconds = existingWord.flashcardStudySeconds + aggregate.flashcardStudySeconds,
                            testStudySeconds = existingWord.testStudySeconds + aggregate.testStudySeconds,
                            updatedAt = now,
                        ),
                    )
                }
            }
        }
    }

    suspend fun seedDefaultTaskItemsIfEmpty() {
        val defaultTaskItems = listOf(
            TaskItemMasterEntity(code = "PUSH_UP", name = "푸시업", category = "운동", valueType = ValueType.NUMBER, unit = "회", description = "푸시업 횟수를 기록합니다.", sortOrder = 10),
        )
        persistChange {
            habitDao.insertTaskItems(defaultTaskItems)
        }
    }

    suspend fun syncManagedTaskItems() {
        val allowedValueTypes = managedTaskValueTypes.map(ValueType::name)
        persistChange {
            habitDao.deactivateTaskItemsByUnsupportedValueTypes(allowedValueTypes)
        }
        seedDefaultTaskItemsIfEmpty()
    }

    suspend fun addTaskItem(name: String, category: String, valueType: ValueType, unit: String?, description: String?, colorHex: String) {
        val sanitizedName = name.trim()
        val sanitizedCategory = category.trim().ifEmpty { "기타" }
        require(sanitizedName.isNotEmpty()) { "항목 이름을 입력해 주세요." }
        val codeBase = sanitizedName.uppercase().replace(" ", "_").replace(Regex("[^A-Z0-9_가-힣]"), "").take(24).ifEmpty { "TASK" }
        val code = "${codeBase}_${System.currentTimeMillis() % 100000}"
        val nextSortOrder = habitDao.getMaxSortOrder() + 10
        val normalizedUnit = when (valueType) {
            ValueType.NUMBER -> unit?.trim()?.takeIf(String::isNotEmpty)
            ValueType.EXERCISE -> "km/min"
            else -> null
        }
        persistChange {
            val insertedId = habitDao.insertTaskItem(
                TaskItemMasterEntity(
                    code = code,
                    name = sanitizedName,
                    category = sanitizedCategory,
                    valueType = valueType,
                    unit = normalizedUnit,
                    description = description?.trim()?.takeIf(String::isNotEmpty),
                    sortOrder = nextSortOrder,
                ),
            )
            if (insertedId > 0L) {
                saveTaskColor(taskItemId = insertedId, taskName = sanitizedName, colorHex = colorHex)
            }
        }
    }

    suspend fun updateTaskItem(taskItemId: Long, name: String, category: String, valueType: ValueType, unit: String?, description: String?) {
        require(name.trim().isNotEmpty()) { "항목 이름을 입력해 주세요." }
        val safeCurrentItem = habitDao.getTaskItemById(taskItemId) ?: throw IllegalArgumentException("수정할 항목을 찾지 못했습니다.")
        persistChange {
            habitDao.updateTaskItem(
                safeCurrentItem.copy(
                    name = name.trim(),
                    category = category.trim().ifEmpty { "기타" },
                    valueType = valueType,
                    unit = when (valueType) {
                        ValueType.NUMBER -> unit?.trim()?.takeIf(String::isNotEmpty)
                        ValueType.EXERCISE -> "km/min"
                        else -> null
                    },
                    description = description?.trim()?.takeIf(String::isNotEmpty),
                ),
            )
        }
    }

    suspend fun deleteTaskItem(taskItemId: Long) {
        persistChange {
            habitDao.deactivateTaskItem(taskItemId)
        }
    }

    fun getTaskColorHex(taskItemId: Long, taskName: String): String {
        val stored = taskColorPrefs.getString(taskColorPrefKey(taskItemId), null)
        return TaskColorPalette.sanitize(stored, fallbackSeed = "$taskItemId-$taskName")
    }

    fun sanitizeTaskColor(colorHex: String?, taskName: String): String =
        TaskColorPalette.sanitize(colorHex, fallbackSeed = taskName)

    private fun saveTaskColor(taskItemId: Long, taskName: String, colorHex: String) {
        taskColorPrefs.edit().putString(
            taskColorPrefKey(taskItemId),
            TaskColorPalette.sanitize(colorHex, fallbackSeed = "$taskItemId-$taskName"),
        ).apply()
    }

    private fun taskColorPrefKey(taskItemId: Long): String = "task_color_$taskItemId"

    suspend fun saveDiary(diaryDate: LocalDate, title: String, body: String, weather: String, imageUris: List<String>) {
        require(title.isNotBlank() || body.isNotBlank()) { "일기 제목 또는 내용을 입력해 주세요." }
        val currentDiary = habitDao.getDiaryByDate(diaryDate)
        persistChange {
            habitDao.upsertDiary(
                DailyDiaryEntity(
                    id = currentDiary?.id ?: 0L,
                    diaryDate = diaryDate,
                    title = title.trim().ifEmpty { "무제" },
                    body = body,
                    weather = weather,
                    imageUris = imageUris.joinToString("\n"),
                    updatedAt = LocalDateTime.now(),
                ),
            )
        }
    }

    suspend fun saveDailyRecord(recordDate: LocalDate, memo: String?, isHoliday: Boolean, itemInputs: List<DailyRecordItemInput>) {
        persistChange {
            database.withTransaction {
                val existingRecord = habitDao.getDailyRecordByDate(recordDate)
                val now = LocalDateTime.now()
                val recordId = if (existingRecord == null) {
                    habitDao.insertDailyRecord(
                        DailyRecordEntity(
                            recordDate = recordDate,
                            memo = memo,
                            isHoliday = isHoliday,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                } else {
                    habitDao.updateDailyRecord(existingRecord.copy(memo = memo, isHoliday = isHoliday, updatedAt = now))
                    existingRecord.id
                }
                val safeRecordId = if (recordId > 0L) recordId else habitDao.getDailyRecordByDate(recordDate)?.id ?: throw IllegalStateException("Daily record was not persisted for $recordDate")
                val sanitizedItems = itemInputs.filter { input -> input.hasMeaningfulValue() }.map { input ->
                    DailyRecordItemEntity(
                        dailyRecordId = safeRecordId,
                        taskItemMasterId = input.taskItemMasterId,
                        numberValue = input.numberValue,
                        booleanValue = input.booleanValue,
                        textValue = input.textValue?.trim()?.takeIf(String::isNotEmpty),
                        durationMinutes = input.durationMinutes,
                        checked = input.checked,
                        note = input.note?.trim()?.takeIf(String::isNotEmpty),
                    )
                }
                habitDao.deleteItemsByRecordId(safeRecordId)
                if (sanitizedItems.isNotEmpty()) {
                    habitDao.upsertDailyRecordItems(sanitizedItems)
                }
            }
        }
    }

    private fun distributeStudySeconds(itemCount: Int, totalSeconds: Int): List<Int> {
        if (itemCount <= 0 || totalSeconds <= 0) return List(itemCount.coerceAtLeast(0)) { 0 }
        val baseSeconds = totalSeconds / itemCount
        val remainder = totalSeconds % itemCount
        return List(itemCount) { index ->
            baseSeconds + if (index < remainder) 1 else 0
        }
    }

    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(pin.toByteArray()).joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun buildLottoRoundNote(roundNo: Int): String = "$lottoRoundNotePrefix$roundNo"

    private fun buildLottoSetNote(roundNo: Int, setIndex: Int): String =
        "${buildLottoRoundNote(roundNo)}$lottoSetNoteSeparator$setIndex"

    private fun buildRandomControlNote(roundNo: Int): String =
        "${buildLottoRoundNote(roundNo)}$lottoSetNoteSeparator$randomControlSetId:${LottoNumberGenerator.CURRENT_GENERATION_VERSION}"

    private fun extractLottoRoundNo(note: String): Int? =
        note
            .takeIf { it.startsWith(lottoRoundNotePrefix) }
            ?.removePrefix(lottoRoundNotePrefix)
            ?.substringBefore(lottoSetNoteSeparator)
            ?.toIntOrNull()

    private suspend fun saveCurrentLottoGenerationConfig(): String {
        val generationVersion = LottoNumberGenerator.CURRENT_GENERATION_VERSION
        val configJson = LottoNumberGenerator.configurationSnapshot()
        val configHash = MessageDigest.getInstance("SHA-256")
            .digest(configJson.toByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        val existingConfig = habitDao.getLottoGenerationConfig(
            generationVersion = generationVersion,
            configHash = configHash,
        )
        val currentConfig = LottoGenerationConfigEntity(
            generationVersion = generationVersion,
            configJson = configJson,
            configHash = configHash,
        )
        if (existingConfig == null) {
            habitDao.upsertLottoGenerationConfig(currentConfig)
        }
        return configHash
    }

    private suspend fun ensureRandomControlSet(roundNo: Int) {
        val existingControls = habitDao.getLottoTicketsBySourceAndRound(
            sourceLabel = randomControlSource,
            roundNo = roundNo,
        )
        if (existingControls.any { ticket -> ticket.generationVersion == LottoNumberGenerator.CURRENT_GENERATION_VERSION }) return

        val generationConfigHash = saveCurrentLottoGenerationConfig()
        val setNote = buildRandomControlNote(roundNo)
        LottoNumberGenerator.generateRandomControl().forEachIndexed { index, ticket ->
            habitDao.insertLottoTicket(
                LottoTicketEntity.from(
                    sourceLabel = randomControlSource,
                    numbers = ticket.numbers,
                    note = setNote,
                    roundNo = roundNo,
                    isEvaluationTarget = true,
                    generationVersion = LottoNumberGenerator.CURRENT_GENERATION_VERSION,
                    generationConfigHash = generationConfigHash,
                    historyThroughRound = roundNo - 1,
                    generationSeed = ticket.generationSeed,
                    generationMode = ticket.generationMode,
                    recommendationRank = index + 1,
                ),
            )
        }
    }

    private suspend fun refreshLottoWinningStats(roundNo: Int?, note: String?) {
        val resolvedRoundNo = roundNo ?: note?.let(::extractLottoRoundNo) ?: return
        val draw = habitDao.getLottoDrawByRoundNo(resolvedRoundNo) ?: return
        updateLottoWinningStatsForRound(resolvedRoundNo, draw)
    }

    private suspend fun updateLottoWinningStatsForRound(roundNo: Int, draw: LottoDrawEntity) {
        val roundTickets = habitDao.getPurchasedLottoTicketsByRound(roundNo)
        val roundStats = buildLottoRoundStats(roundNo, draw, roundTickets)

        habitDao.deleteLottoWinningStatRoundsByRound(roundNo)
        if (roundStats.isNotEmpty()) {
            habitDao.upsertLottoWinningStatRounds(roundStats)
        }

        replaceLottoWinningStatTotals(habitDao.getAllLottoWinningStatRounds())
    }

    private suspend fun recalculateAllLottoWinningStats() {
        val draws = habitDao.getAllLottoDrawsDesc()
        val roundStats = draws.flatMap { draw ->
            val roundTickets = habitDao.getPurchasedLottoTicketsByRound(draw.roundNo)
            buildLottoRoundStats(draw.roundNo, draw, roundTickets)
        }

        habitDao.deleteAllLottoWinningStatRounds()
        if (roundStats.isNotEmpty()) {
            habitDao.upsertLottoWinningStatRounds(roundStats)
        }

        replaceLottoWinningStatTotals(roundStats)
    }

    private fun buildLottoRoundStats(
        roundNo: Int,
        draw: LottoDrawEntity,
        tickets: List<LottoTicketEntity>,
    ): List<LottoWinningStatRoundEntity> = tickets
        .groupBy { ticket -> normalizeWinningSource(ticket.sourceLabel) to ticket.generationVersion }
        .map { (sourceAndVersion, sourceTickets) ->
            val (sourceLabel, generationVersion) = sourceAndVersion
            val counts = IntArray(5)
            val styleEvaluations = sourceTickets.map { ticket -> evaluateTicketStyle(sourceLabel, ticket.numbers()) }
            val winningNumbers = draw.numbers()
            val matchCounts = sourceTickets.map { ticket -> ticket.numbers().count(winningNumbers::contains) }
            sourceTickets.forEach { ticket ->
                when (calculateWinningRank(ticket, draw)) {
                    5 -> counts[0] += 1
                    4 -> counts[1] += 1
                    3 -> counts[2] += 1
                    2 -> counts[3] += 1
                    1 -> counts[4] += 1
                }
            }
            val analysisScores = sourceTickets.mapNotNull(LottoTicketEntity::analysisScore)
            LottoWinningStatRoundEntity(
                roundNo = roundNo,
                sourceLabel = sourceLabel,
                generationVersion = generationVersion,
                rank5Count = counts[0],
                rank4Count = counts[1],
                rank3Count = counts[2],
                rank2Count = counts[3],
                rank1Count = counts[4],
                evaluatedTicketCount = sourceTickets.size,
                stylePassCount = styleEvaluations.count(TicketStyleEvaluation::passed),
                styleScoreTotal = styleEvaluations.sumOf(TicketStyleEvaluation::score),
                scoredTicketCount = analysisScores.size,
                analysisScoreTotal = analysisScores.sum(),
                matchCountTotal = matchCounts.sum(),
            )
        }

    private suspend fun replaceLottoWinningStatTotals(roundStats: List<LottoWinningStatRoundEntity>) {
        val totals = roundStats
            .groupBy { stat -> stat.sourceLabel to stat.generationVersion }
            .map { (sourceAndVersion, sourceRounds) ->
                val (sourceLabel, generationVersion) = sourceAndVersion
                LottoWinningStatEntity(
                    sourceLabel = sourceLabel,
                    generationVersion = generationVersion,
                    rank5Count = sourceRounds.sumOf(LottoWinningStatRoundEntity::rank5Count),
                    rank4Count = sourceRounds.sumOf(LottoWinningStatRoundEntity::rank4Count),
                    rank3Count = sourceRounds.sumOf(LottoWinningStatRoundEntity::rank3Count),
                    rank2Count = sourceRounds.sumOf(LottoWinningStatRoundEntity::rank2Count),
                    rank1Count = sourceRounds.sumOf(LottoWinningStatRoundEntity::rank1Count),
                    evaluatedTicketCount = sourceRounds.sumOf(LottoWinningStatRoundEntity::evaluatedTicketCount),
                    stylePassCount = sourceRounds.sumOf(LottoWinningStatRoundEntity::stylePassCount),
                    styleScoreTotal = sourceRounds.sumOf(LottoWinningStatRoundEntity::styleScoreTotal),
                    scoredTicketCount = sourceRounds.sumOf(LottoWinningStatRoundEntity::scoredTicketCount),
                    analysisScoreTotal = sourceRounds.sumOf(LottoWinningStatRoundEntity::analysisScoreTotal),
                    matchCountTotal = sourceRounds.sumOf(LottoWinningStatRoundEntity::matchCountTotal),
                )
            }
        habitDao.deleteAllLottoWinningStats()
        if (totals.isNotEmpty()) {
            habitDao.upsertLottoWinningStats(totals)
        }
    }

    private fun buildLottoControlComparisons(
        roundStats: List<LottoWinningStatRoundEntity>,
    ): List<LottoControlComparison> {
        val controls = roundStats
            .filter { stat -> stat.sourceLabel == randomControlSource && stat.evaluatedTicketCount > 0 }
            .associateBy { stat -> stat.roundNo to stat.generationVersion }

        return roundStats
            .filter { stat ->
                stat.sourceLabel in listOf("균형형", "분산형") &&
                    stat.evaluatedTicketCount > 0
            }
            .groupBy { stat -> stat.sourceLabel to stat.generationVersion }
            .mapNotNull comparison@ { (sourceAndVersion, sourceStats) ->
                val pairedMatches = sourceStats.mapNotNull sample@ { stat ->
                    val control = controls[stat.roundNo to stat.generationVersion] ?: return@sample null
                    val strategyAverage = stat.matchCountTotal.toDouble() / stat.evaluatedTicketCount
                    val controlAverage = control.matchCountTotal.toDouble() / control.evaluatedTicketCount
                    strategyAverage to controlAverage
                }
                if (pairedMatches.isEmpty()) return@comparison null

                val differences = pairedMatches.map { (strategy, control) -> strategy - control }
                val averageDifference = differences.average()
                val confidenceMargin = if (differences.size >= 2) {
                    val sampleVariance = differences.sumOf { difference ->
                        val deviation = difference - averageDifference
                        deviation * deviation
                    } / (differences.size - 1)
                    1.96 * sqrt(sampleVariance / differences.size)
                } else {
                    null
                }
                LottoControlComparison(
                    sourceLabel = sourceAndVersion.first,
                    generationVersion = sourceAndVersion.second,
                    pairedRoundCount = pairedMatches.size,
                    strategyAverageMatchCount = pairedMatches.map { pair -> pair.first }.average(),
                    controlAverageMatchCount = pairedMatches.map { pair -> pair.second }.average(),
                    averageMatchDifference = averageDifference,
                    differenceConfidenceLow = confidenceMargin?.let { averageDifference - it },
                    differenceConfidenceHigh = confidenceMargin?.let { averageDifference + it },
                    betterRoundCount = differences.count { difference -> difference > 0.000_001 },
                    tiedRoundCount = differences.count { difference -> kotlin.math.abs(difference) <= 0.000_001 },
                    worseRoundCount = differences.count { difference -> difference < -0.000_001 },
                )
            }
            .sortedWith(
                compareBy<LottoControlComparison> { comparison ->
                    if (comparison.sourceLabel == "균형형") 0 else 1
                }.thenByDescending(LottoControlComparison::generationVersion),
            )
    }

    private fun normalizeWinningSource(sourceLabel: String): String = when {
        sourceLabel.contains("분산형") ||
            sourceLabel.contains("gemini", ignoreCase = true) ||
            sourceLabel.contains("제미나이") -> "분산형"
        sourceLabel.contains("균형형") || sourceLabel.contains("chatgpt", ignoreCase = true) || sourceLabel.contains("gpt", ignoreCase = true) -> "균형형"
        else -> sourceLabel
    }

    private fun calculateWinningRank(ticket: LottoTicketEntity, draw: LottoDrawEntity): Int? {
        val winningNumbers = draw.numbers()
        val matchCount = ticket.numbers().count(winningNumbers::contains)
        val bonusMatched = draw.bonusNumber?.let(ticket.numbers()::contains) == true
        return when {
            matchCount == 6 -> 1
            matchCount == 5 && bonusMatched -> 2
            matchCount == 5 -> 3
            matchCount == 4 -> 4
            matchCount == 3 -> 5
            else -> null
        }
    }

    private fun evaluateTicketStyle(sourceLabel: String, numbers: List<Int>): TicketStyleEvaluation = when (sourceLabel) {
        "균형형" -> evaluateBalancedTicket(numbers)
        "분산형" -> evaluateDiversifiedTicket(numbers)
        else -> TicketStyleEvaluation(score = 0, passed = false)
    }

    private fun evaluateBalancedTicket(numbers: List<Int>): TicketStyleEvaluation {
        if (numbers.size != 6) return TicketStyleEvaluation(score = 0, passed = false)
        val sorted = numbers.sorted()
        val spread = sorted.last() - sorted.first()
        val oddCount = sorted.count { it % 2 != 0 }
        val lowCount = sorted.count { it <= 22 }
        val highCount = sorted.count { it >= 32 }
        val bucketCounts = sorted.groupingBy { (it - 1) / 10 }.eachCount()
        val variance = ticketVariance(sorted)
        val lowMiddleHighGap = countGap(
            sorted.count { it <= 15 },
            sorted.count { it in 16..30 },
            sorted.count { it >= 31 },
        )

        var score = 0
        if (oddCount in 2..4) score += 20
        if (lowCount in 2..4) score += 20
        if (highCount in 1..3) score += 15
        if (spread in 24..38) score += 15
        if (bucketCounts.size >= 4 && bucketCounts.values.none { it > 2 }) score += 15
        if (variance in 70.0..185.0) score += 10
        if (lowMiddleHighGap <= 2) score += 5

        return TicketStyleEvaluation(score = score, passed = score >= 70)
    }

    private fun evaluateDiversifiedTicket(numbers: List<Int>): TicketStyleEvaluation {
        if (numbers.size != 6) return TicketStyleEvaluation(score = 0, passed = false)
        val sorted = numbers.sorted()
        val spread = sorted.last() - sorted.first()
        val highCount = sorted.count { it >= 32 }
        val bucketCount = sorted.map { (it - 1) / 10 }.distinct().size
        val tailDuplicates = sorted.groupBy { it % 10 }.values.maxOfOrNull(List<Int>::size) ?: 1
        val variance = ticketVariance(sorted)
        val ac = acValue(sorted)

        var score = 0
        if (highCount >= 2) score += 25
        if (bucketCount >= 4) score += 20
        if (spread >= 30) score += 20
        if (tailDuplicates <= 2) score += 10
        if (ac >= 6) score += 15
        if (variance >= 120.0) score += 10

        return TicketStyleEvaluation(score = score, passed = score >= 70)
    }

    private fun ticketVariance(numbers: List<Int>): Double {
        val average = numbers.average()
        return numbers.sumOf { number ->
            val diff = number - average
            diff * diff
        } / numbers.size
    }

    private fun acValue(numbers: List<Int>): Int {
        val sorted = numbers.sorted()
        val diffs = mutableSetOf<Int>()
        for (firstIndex in sorted.indices) {
            for (secondIndex in firstIndex + 1 until sorted.size) {
                diffs += abs(sorted[secondIndex] - sorted[firstIndex])
            }
        }
        return diffs.size - (sorted.size - 1)
    }

    private fun countGap(first: Int, second: Int, third: Int): Int {
        val maxValue = max(first, max(second, third))
        val minValue = minOf(first, second, third)
        return maxValue - minValue
    }

    private data class TicketStyleEvaluation(
        val score: Int,
        val passed: Boolean,
    )

    private fun nextLottoSetIndex(notes: List<String>): Int {
        val usedIndexes = notes.map { note ->
            note.substringAfter(lottoSetNoteSeparator, missingDelimiterValue = "1").toIntOrNull() ?: 1
        }.toSet()
        return (1..maxSavedLottoSetCount).firstOrNull { it !in usedIndexes } ?: (usedIndexes.maxOrNull() ?: 0) + 1
    }
}

private fun KisApiConfigEntity.toKisApiConfig(cipher: AndroidKeystoreStringCipher): KisApiConfig {
    val kisEnvironment = KisEnvironment.values().firstOrNull { it.apiValue == environment }
        ?: throw IllegalStateException("지원하지 않는 KIS 환경입니다: $environment")

    return KisApiConfig(
        environment = kisEnvironment,
        appKey = cipher.decrypt(encryptedAppKey),
        appSecret = cipher.decrypt(encryptedAppSecret),
        accountNumber = cipher.decrypt(encryptedAccountNumber),
        accountProductCode = cipher.decrypt(encryptedAccountProductCode),
    )
}

data class BulkVocabularyInsertResult(
    val insertedCount: Int,
    val duplicateCount: Int,
)

data class DailyRecordItemInput(
    val taskItemMasterId: Long,
    val numberValue: Double? = null,
    val booleanValue: Boolean? = null,
    val textValue: String? = null,
    val durationMinutes: Int? = null,
    val checked: Boolean = false,
    val note: String? = null,
) {
    fun hasMeaningfulValue(): Boolean {
        return checked || booleanValue == true || (numberValue != null && numberValue > 0) || (durationMinutes != null && durationMinutes > 0) || !textValue.isNullOrBlank() || !note.isNullOrBlank()
    }
}

data class VocabularyStudyRecord(
    val wordId: Long,
    val isCorrect: Boolean?,
)

private data class VocabularyStudyAggregate(
    var correctCount: Int = 0,
    var wrongCount: Int = 0,
    var exposureCount: Int = 0,
    var flashcardStudySeconds: Int = 0,
    var testStudySeconds: Int = 0,
)
