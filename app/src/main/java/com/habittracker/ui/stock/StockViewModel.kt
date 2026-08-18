package com.habittracker.ui.stock

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.habittracker.data.local.entity.StockAutomationEventEntity
import com.habittracker.data.local.entity.StockExitRuleEntity
import com.habittracker.data.local.entity.StockOrderEntity
import com.habittracker.data.local.entity.StockSafetyConfigEntity
import com.habittracker.data.local.entity.StockSellAllocationEntity
import com.habittracker.data.local.entity.StockTargetAllocationEntity
import com.habittracker.data.repository.HabitRepository
import com.habittracker.data.stock.KisApiConfig
import com.habittracker.data.stock.KisBalanceStock
import com.habittracker.data.stock.KisCashOrderDraft
import com.habittracker.data.stock.KisEnvironment
import com.habittracker.data.stock.KisMarketCapStock
import com.habittracker.data.stock.KisOrderSide
import com.habittracker.data.stock.StockBuyLotRow
import com.habittracker.data.stock.StockExitRuleType
import com.habittracker.data.stock.StockJournalAnalysis
import com.habittracker.data.stock.StockOrderSource
import com.habittracker.data.stock.StockOrderAvailability
import com.habittracker.data.stock.StockRebalanceLine
import com.habittracker.data.stock.StockRuleAction
import com.habittracker.ui.digitsOnly
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

private const val stockAutomationEventPageSize = 50
private const val stockViewModelLogTag = "StockViewModel"
private const val orderInputAutomationDelayMillis = 600L

/**
 * 주식 화면 묶음이 공유하는 상태 보유자다.
 * 외부에는 읽기 전용 [StateFlow]를 노출하고, 내부에서는 data class의 copy로 새 상태를 만든다.
 */
class StockViewModel(
    private val repository: HabitRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(StockUiState())
    val uiState: StateFlow<StockUiState> = _uiState.asStateFlow()
    private val automationEventLimit = MutableStateFlow(stockAutomationEventPageSize)
    private var safetyFormInitialized = false
    private var orderAvailabilityRequestId = 0L
    private var orderPriceRequestId = 0L
    private var orderInputAutomationJob: Job? = null

    init {
        observeTradingData()
        loadConfigCompletion()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeTradingData() {
        viewModelScope.launch {
            repository.observeStockOrders().collect { orders ->
                _uiState.update {
                    it.copy(
                        orders = orders,
                        journalAnalysis = repository.calculateStockJournalAnalysis(orders),
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.observeStockSellAllocations().collect { allocations ->
                _uiState.update { it.copy(sellAllocations = allocations) }
            }
        }
        viewModelScope.launch {
            repository.observeStockExitRules().collect { rules ->
                _uiState.update { it.copy(exitRules = rules) }
            }
        }
        viewModelScope.launch {
            repository.observeStockTargetAllocations().collect { targets ->
                _uiState.update { it.copy(targetAllocations = targets) }
            }
        }
        viewModelScope.launch {
            automationEventLimit
                .flatMapLatest { limit -> repository.observeStockAutomationEvents(limit) }
                .collect { events ->
                    _uiState.update {
                        it.copy(
                            automationEvents = events,
                            canLoadMoreAutomationEvents = events.size >= automationEventLimit.value,
                        )
                    }
                }
        }
        viewModelScope.launch {
            repository.observeStockSafetyConfig().collect { config ->
                if (config != null) applySafetyConfig(config)
            }
        }
        viewModelScope.launch {
            repository.observeKisAccessTokenExpiredAt(KisEnvironment.REAL).collect { expiresAt ->
                _uiState.update { it.copy(accessTokenExpiredAt = expiresAt) }
            }
        }
    }

    fun updateAppKey(value: String) = _uiState.update { it.copy(appKey = value.trim()) }
    fun updateAppSecret(value: String) = _uiState.update { it.copy(appSecret = value.trim()) }
    fun updateAccountNumber(value: String) =
        _uiState.update { it.copy(accountNumber = value.digitsOnly().take(8)) }
    fun updateAccountProductCode(value: String) =
        _uiState.update { it.copy(accountProductCode = value.digitsOnly().take(2)) }
    fun toggleConfigExpanded() = _uiState.update { it.copy(isConfigExpanded = !it.isConfigExpanded) }

    fun saveConfig() {
        val state = _uiState.value
        launchAction("KIS 설정 저장에 실패했습니다.") {
            repository.saveKisApiConfig(
                KisApiConfig(
                    environment = KisEnvironment.REAL,
                    appKey = state.appKey,
                    appSecret = state.appSecret,
                    accountNumber = state.accountNumber,
                    accountProductCode = state.accountProductCode,
                ),
            )
            _uiState.update {
                it.copy(
                    appKey = "",
                    appSecret = "",
                    accountNumber = "",
                    accountProductCode = "01",
                    isConfigSaved = true,
                    isConfigExpanded = false,
                    statusMessage = "KIS 실전투자 설정이 저장되었습니다.",
                )
            }
        }
    }

    fun loadReferenceStocks(force: Boolean = false) {
        loadOwnedStocks(force)
        loadMarketCapStocks(force)
    }

    fun loadOwnedStocks(force: Boolean = false) {
        val state = _uiState.value
        if (!state.isConfigSaved || state.isLoadingOwnedStocks || (!force && state.hasLoadedOwnedStocks)) return
        _uiState.update { it.copy(isLoadingOwnedStocks = true) }
        viewModelScope.launch {
            runCatching { repository.getKisBalanceStocks(forceRefresh = force) }
                .onSuccess { stocks ->
                    _uiState.update {
                        it.copy(
                            ownedStocks = stocks,
                            isLoadingOwnedStocks = false,
                            hasLoadedOwnedStocks = true,
                        )
                    }
                }
                .onFailure { error ->
                    recordStockError(
                        eventType = "KIS_BALANCE_QUERY_FAILED",
                        title = "보유 종목 조회 실패",
                        error = error,
                        fallbackMessage = "보유 종목을 조회하지 못했습니다.",
                    )
                    _uiState.update {
                        it.copy(
                            isLoadingOwnedStocks = false,
                            statusMessage = "보유 종목 조회에 실패했습니다. ${error.message.orEmpty()}",
                        )
                    }
                }
        }
    }

    fun loadMarketCapStocks(force: Boolean = false) {
        val state = _uiState.value
        if (!state.isConfigSaved || state.isLoadingMarketCapStocks || (!force && state.marketCapStocks.isNotEmpty())) return
        _uiState.update { it.copy(isLoadingMarketCapStocks = true) }
        viewModelScope.launch {
            runCatching { repository.getKisMarketCapStocks() }
                .onSuccess { stocks ->
                    _uiState.update { it.copy(marketCapStocks = stocks, isLoadingMarketCapStocks = false) }
                }
                .onFailure { error ->
                    recordStockError(
                        eventType = "KIS_MARKET_CAP_QUERY_FAILED",
                        title = "매수 종목 조회 실패",
                        error = error,
                        fallbackMessage = "매수 종목을 조회하지 못했습니다.",
                    )
                    _uiState.update {
                        it.copy(
                            isLoadingMarketCapStocks = false,
                            statusMessage = "매수 종목 조회에 실패했습니다. ${error.message.orEmpty()}",
                        )
                    }
                }
        }
    }

    fun selectOrderSide(side: KisOrderSide) {
        cancelOrderInputAutomation()
        _uiState.update {
            it.copy(
                orderSide = side,
                productCode = "",
                productName = "",
                orderQuantity = "",
                orderUnitPrice = if (it.orderDivisionCode == "01") "0" else "",
                orderCalculationAmount = "",
                orderQuantityPercent = null,
                orderCurrentPrice = null,
                orderAvailability = null,
                isLoadingOrderPrice = false,
                isLoadingOrderAvailability = false,
                statusMessage = null,
            )
        }
        if (side == KisOrderSide.SELL) loadOwnedStocks() else loadMarketCapStocks()
    }

    fun selectOrderProduct(productCode: String, productName: String) {
        cancelOrderInputAutomation()
        _uiState.update {
            it.copy(
                productCode = productCode,
                productName = productName,
                orderQuantity = "",
                orderUnitPrice = if (it.orderDivisionCode == "01") "0" else "",
                orderCalculationAmount = "",
                orderQuantityPercent = null,
                orderCurrentPrice = null,
                orderAvailability = null,
                isLoadingOrderPrice = false,
                isLoadingOrderAvailability = false,
            )
        }
        loadCurrentOrderPrice()
    }

    fun updateOrderProductCode(value: String) {
        val productCode = value.filter(Char::isLetterOrDigit).uppercase().take(7)
        cancelOrderInputAutomation()
        _uiState.update {
            it.copy(
                productCode = productCode,
                productName = productCode,
                orderQuantity = "",
                orderUnitPrice = if (it.orderDivisionCode == "01") "0" else "",
                orderCalculationAmount = "",
                orderQuantityPercent = null,
                orderCurrentPrice = null,
                orderAvailability = null,
                isLoadingOrderPrice = false,
                isLoadingOrderAvailability = false,
            )
        }
        if (productCode.length in 6..7) scheduleCurrentOrderPriceLoad(productCode)
    }

    fun updateOrderDivisionCode(value: String) {
        val orderDivisionCode = value.digitsOnly().take(2)
        cancelOrderInputAutomation()
        _uiState.update {
            it.copy(
                orderDivisionCode = orderDivisionCode,
                orderQuantity = "",
                orderUnitPrice = if (orderDivisionCode == "01") "0" else it.orderCurrentPrice?.toString().orEmpty(),
                orderCalculationAmount = "",
                orderQuantityPercent = null,
                orderAvailability = null,
                isLoadingOrderPrice = false,
                isLoadingOrderAvailability = false,
            )
        }
        val state = _uiState.value
        if (state.productCode.length in 6..7) {
            if (state.orderCurrentPrice != null) loadOrderAvailability() else loadCurrentOrderPrice()
        }
    }

    fun updateOrderQuantity(value: String) = _uiState.update {
        it.copy(
            orderQuantity = value.digitsOnly(),
            orderCalculationAmount = "",
            orderQuantityPercent = null,
        )
    }

    fun updateOrderUnitPrice(value: String) {
        val orderUnitPrice = value.digitsOnly()
        cancelOrderInputAutomation()
        _uiState.update {
            it.copy(
                orderUnitPrice = orderUnitPrice,
                orderQuantity = "",
                orderCalculationAmount = "",
                orderQuantityPercent = null,
                orderAvailability = null,
                isLoadingOrderPrice = false,
                isLoadingOrderAvailability = false,
            )
        }
        if (orderUnitPrice.toLongOrNull()?.let { it > 0L } == true) scheduleOrderAvailabilityLoad()
    }

    fun loadCurrentOrderPrice() {
        orderInputAutomationJob?.cancel()
        loadCurrentOrderPriceNow()
    }

    private fun loadCurrentOrderPriceNow() {
        val state = _uiState.value
        if (!state.isConfigSaved || state.productCode.length !in 6..7) return
        val productCode = state.productCode
        val requestId = ++orderPriceRequestId
        orderAvailabilityRequestId += 1
        _uiState.update {
            it.copy(
                isLoadingOrderPrice = true,
                isLoadingOrderAvailability = false,
                orderAvailability = null,
            )
        }
        viewModelScope.launch {
            runCatching { repository.getKisCurrentStockPrice(productCode) }
                .onSuccess { currentPrice ->
                    if (requestId != orderPriceRequestId) return@onSuccess
                    val current = _uiState.value
                    if (current.productCode != productCode) return@onSuccess
                    _uiState.update {
                        it.copy(
                            orderCurrentPrice = currentPrice,
                            orderUnitPrice = if (it.orderDivisionCode == "01") "0" else currentPrice.toString(),
                            isLoadingOrderPrice = false,
                        )
                    }
                    loadOrderAvailability()
                }
                .onFailure { error ->
                    if (requestId != orderPriceRequestId) return@onFailure
                    recordStockError(
                        eventType = "ORDER_CURRENT_PRICE_QUERY_FAILED",
                        title = "주문 현재가 조회 실패",
                        error = error,
                        fallbackMessage = "주문 현재가를 조회하지 못했습니다.",
                    )
                    _uiState.update {
                        it.copy(
                            isLoadingOrderPrice = false,
                            statusMessage = error.message ?: "현재가 조회에 실패했습니다.",
                        )
                    }
                }
        }
    }

    fun updateOrderCalculationAmount(value: String) {
        val calculationAmount = value.digitsOnly()
        _uiState.update { state ->
            val amount = calculationAmount.toLongOrNull()
            val unitPrice = state.orderCalculationUnitPrice()
            val availableQuantity = state.orderAvailability?.availableQuantity ?: 0L
            val calculatedQuantity = if (amount != null && unitPrice != null && unitPrice > 0L) {
                (amount / unitPrice).coerceAtMost(availableQuantity)
            } else {
                0L
            }
            state.copy(
                orderCalculationAmount = calculationAmount,
                orderQuantity = calculatedQuantity.takeIf { it > 0L }?.toString().orEmpty(),
                orderQuantityPercent = null,
            )
        }
    }

    fun applyOrderQuantityPercent(percent: Int) {
        require(percent in 1..100) { "주문 비율은 1~100% 범위여야 합니다." }
        _uiState.update { state ->
            val availableQuantity = state.orderAvailability?.availableQuantity ?: 0L
            val calculatedQuantity = when {
                availableQuantity <= 0L -> 0L
                percent == 100 -> availableQuantity
                else -> (availableQuantity * percent / 100L).coerceAtLeast(1L)
            }
            val calculatedAmount = state.orderCalculationUnitPrice()?.let { unitPrice ->
                runCatching { Math.multiplyExact(calculatedQuantity, unitPrice) }.getOrNull()
            }
            state.copy(
                orderQuantity = calculatedQuantity.takeIf { it > 0L }?.toString().orEmpty(),
                orderCalculationAmount = calculatedAmount?.toString().orEmpty(),
                orderQuantityPercent = percent,
            )
        }
    }

    private fun scheduleCurrentOrderPriceLoad(productCode: String) {
        orderInputAutomationJob = viewModelScope.launch {
            delay(orderInputAutomationDelayMillis)
            if (_uiState.value.productCode == productCode) loadCurrentOrderPriceNow()
        }
    }

    private fun scheduleOrderAvailabilityLoad() {
        orderInputAutomationJob = viewModelScope.launch {
            delay(orderInputAutomationDelayMillis)
            loadOrderAvailability()
        }
    }

    private fun cancelOrderInputAutomation() {
        orderInputAutomationJob?.cancel()
        orderPriceRequestId += 1
        orderAvailabilityRequestId += 1
    }
    fun updateExchangeIdDivisionCode(value: String) =
        _uiState.update { it.copy(exchangeIdDivisionCode = value.filter(Char::isLetter).uppercase().take(3)) }
    fun updateSellType(value: String) = _uiState.update { it.copy(sellType = value.digitsOnly().take(2)) }
    fun updateConditionPrice(value: String) = _uiState.update { it.copy(conditionPrice = value.digitsOnly()) }

    fun loadOrderAvailability() {
        val state = _uiState.value
        if (state.isLoadingOrderAvailability) return
        val requestId = ++orderAvailabilityRequestId
        _uiState.update { it.copy(isLoadingOrderAvailability = true, orderAvailability = null) }
        viewModelScope.launch {
            runCatching {
                repository.getStockOrderAvailability(
                    state.toCashOrderDraft().copy(
                        exchangeIdDivisionCode = repository.getCurrentStockOrderExchangeCode(),
                    ),
                    verifiedCurrentPrice = state.orderCurrentPrice,
                )
            }.onSuccess { availability ->
                if (requestId != orderAvailabilityRequestId) return@onSuccess
                _uiState.update { current ->
                    if (
                        current.orderSide == state.orderSide &&
                        current.productCode == state.productCode &&
                        current.orderDivisionCode == state.orderDivisionCode &&
                        current.orderUnitPrice == state.orderUnitPrice
                    ) {
                        current.copy(
                            orderCurrentPrice = availability.currentPrice,
                            isLoadingOrderAvailability = false,
                            orderAvailability = availability,
                        )
                    } else {
                        current.copy(isLoadingOrderAvailability = false)
                    }
                }
            }.onFailure { error ->
                if (requestId != orderAvailabilityRequestId) return@onFailure
                val current = _uiState.value
                val requestIsCurrent =
                    current.orderSide == state.orderSide &&
                        current.productCode == state.productCode &&
                        current.orderDivisionCode == state.orderDivisionCode &&
                        current.orderUnitPrice == state.orderUnitPrice
                if (!requestIsCurrent) {
                    _uiState.update { it.copy(isLoadingOrderAvailability = false) }
                    return@onFailure
                }
                recordStockError(
                    eventType = "ORDER_AVAILABILITY_QUERY_FAILED",
                    title = "주문 가능 수량 조회 실패",
                    error = error,
                    fallbackMessage = "주문 가능 수량을 조회하지 못했습니다.",
                )
                _uiState.update {
                    it.copy(
                        isLoadingOrderAvailability = false,
                        statusMessage = error.message ?: "주문 가능 수량 조회에 실패했습니다.",
                    )
                }
            }
        }
    }

    fun submitCashOrder() {
        val state = _uiState.value
        val requestedQuantity = state.orderQuantity.toLongOrNull()
        val availability = state.orderAvailability
        if (availability == null || availability.side != state.orderSide) {
            _uiState.update { it.copy(statusMessage = "주문 가능 수량을 먼저 조회해 주세요.") }
            return
        }
        if (requestedQuantity == null || requestedQuantity !in 1L..availability.availableQuantity) {
            _uiState.update {
                it.copy(statusMessage = "${state.orderSide.label} 수량은 1주 이상 ${availability.availableQuantity}주 이하로 입력해 주세요.")
            }
            return
        }
        _uiState.update { it.copy(isSubmittingOrder = true) }
        viewModelScope.launch {
            runCatching {
                repository.placeKisCashOrder(
                    draft = state.toCashOrderDraft().copy(
                        exchangeIdDivisionCode = repository.getCurrentStockOrderExchangeCode(),
                    ),
                    productName = state.productName,
                    source = StockOrderSource.MANUAL,
                )
            }.onSuccess { order ->
                _uiState.update {
                    it.copy(
                        isSubmittingOrder = false,
                        orderQuantity = "",
                        orderCalculationAmount = "",
                        orderQuantityPercent = null,
                        orderAvailability = null,
                        statusMessage = "${order.productName} ${state.orderSide.label} 주문이 접수되었습니다. 주문번호 ${order.orderNumber}",
                    )
                }
            }.onFailure { error ->
                recordStockError(
                    eventType = "ORDER_SUBMIT_FAILED",
                    title = "주문 접수 실패",
                    error = error,
                    fallbackMessage = "주문을 접수하지 못했습니다.",
                )
                _uiState.update {
                    it.copy(isSubmittingOrder = false, statusMessage = error.message ?: "주문 접수에 실패했습니다.")
                }
            }
        }
    }

    fun submitBuyLotSell(row: StockBuyLotRow, quantity: Long) {
        val order = row.order
        val currentPrice = row.currentPrice
        if (quantity !in 1L..order.remainingQuantity) {
            _uiState.update { it.copy(statusMessage = "매도 수량은 1주 이상 ${order.remainingQuantity}주 이하로 입력해 주세요.") }
            return
        }
        if (currentPrice == null || currentPrice <= 0L) {
            _uiState.update { it.copy(statusMessage = "${order.productName} 현재가를 확인한 뒤 다시 시도해 주세요.") }
            return
        }
        if (_uiState.value.isSubmittingOrder) return

        _uiState.update { it.copy(isSubmittingOrder = true) }
        viewModelScope.launch {
            runCatching {
                repository.placeKisCashOrder(
                    draft = KisCashOrderDraft(
                        side = KisOrderSide.SELL,
                        productCode = order.productCode,
                        orderDivisionCode = "00",
                        orderQuantity = quantity.toString(),
                        orderUnitPrice = currentPrice.toString(),
                        exchangeIdDivisionCode = repository.getCurrentStockOrderExchangeCode(),
                        sellType = "01",
                        conditionPrice = "",
                    ),
                    productName = order.productName,
                    source = StockOrderSource.MANUAL,
                    intendedBuyOrderId = order.id,
                )
            }.onSuccess { sellOrder ->
                _uiState.update {
                    it.copy(
                        isSubmittingOrder = false,
                        statusMessage = "${sellOrder.productName} ${quantity}주 매도 주문이 접수되었습니다. 주문번호 ${sellOrder.orderNumber}",
                    )
                }
            }.onFailure { error ->
                recordStockError(
                    eventType = "ORDER_SUBMIT_FAILED",
                    title = "매도 주문 접수 실패",
                    error = error,
                    fallbackMessage = "매도 주문을 접수하지 못했습니다.",
                )
                _uiState.update {
                    it.copy(isSubmittingOrder = false, statusMessage = error.message ?: "매도 주문 접수에 실패했습니다.")
                }
            }
        }
    }

    fun submitAllHoldingsSell() {
        if (_uiState.value.isSubmittingOrder) return
        _uiState.update { it.copy(isSubmittingOrder = true) }
        viewModelScope.launch {
            runCatching { repository.sellAllKisHoldings() }
                .onSuccess { result ->
                    val failureSummary = result.failures.take(3).joinToString("\n") { failure ->
                        "${failure.productName} (${failure.productCode}): ${failure.reason}"
                    }
                    val remainingFailureCount = (result.failures.size - 3).coerceAtLeast(0)
                    _uiState.update {
                        it.copy(
                            isSubmittingOrder = false,
                            statusMessage = buildString {
                                append("전체 매도 주문 ${result.submittedOrders.size}건을 접수했습니다.")
                                if (result.failures.isNotEmpty()) {
                                    append(" 실패 ${result.failures.size}건")
                                    if (failureSummary.isNotBlank()) append("\n$failureSummary")
                                    if (remainingFailureCount > 0) append("\n외 ${remainingFailureCount}건")
                                }
                            },
                        )
                    }
                }
                .onFailure { error ->
                    recordStockError(
                        eventType = "ORDER_SUBMIT_FAILED",
                        title = "보유 종목 전체 매도 실패",
                        error = error,
                        fallbackMessage = "보유 종목 전체 매도에 실패했습니다.",
                    )
                    _uiState.update {
                        it.copy(
                            isSubmittingOrder = false,
                            statusMessage = error.message ?: "보유 종목 전체 매도에 실패했습니다.",
                        )
                    }
                }
        }
    }

    fun loadPortfolioData(forceRefresh: Boolean = false) {
        val state = _uiState.value
        if (!state.isConfigSaved || state.isLoadingPortfolio) return
        _uiState.update { it.copy(isLoadingPortfolio = true) }
        viewModelScope.launch {
            runCatching {
                repository.syncStockOrderExecutions()
                val balanceStocks = repository.getKisBalanceStocks(forceRefresh = forceRefresh)
                balanceStocks to repository.getStockBuyLotRows(balanceStocks)
            }.onSuccess { (balanceStocks, rows) ->
                _uiState.update {
                    it.copy(
                        ownedStocks = balanceStocks,
                        buyLotRows = rows,
                        isLoadingPortfolio = false,
                        hasLoadedOwnedStocks = true,
                    )
                }
            }.onFailure { error ->
                recordStockError(
                    eventType = "KIS_PORTFOLIO_REFRESH_FAILED",
                    title = "매수 내역 갱신 실패",
                    error = error,
                    fallbackMessage = "매수 내역을 갱신하지 못했습니다.",
                )
                _uiState.update {
                    it.copy(isLoadingPortfolio = false, statusMessage = "매수 내역 갱신에 실패했습니다. ${error.message.orEmpty()}")
                }
            }
        }
    }

    fun syncOrders() {
        _uiState.update { it.copy(isSyncingOrders = true) }
        viewModelScope.launch {
            runCatching { repository.syncStockOrderExecutions() }
                .onSuccess { count ->
                    _uiState.update { it.copy(isSyncingOrders = false, statusMessage = "체결 기록 ${count}건을 동기화했습니다.") }
                }
                .onFailure { error ->
                    recordStockError(
                        eventType = "ORDER_SYNC_FAILED",
                        title = "체결 상태 확인 실패",
                        error = error,
                        fallbackMessage = "체결 상태를 확인하지 못했습니다.",
                    )
                    _uiState.update {
                        it.copy(isSyncingOrders = false, statusMessage = "체결 상태 확인에 실패했습니다. ${error.message.orEmpty()}")
                    }
                }
        }
    }

    fun selectManualTradeProduct(code: String, name: String) =
        _uiState.update { it.copy(manualTradeProductCode = code, manualTradeProductName = name) }
    fun selectManualTradeSide(side: KisOrderSide) = _uiState.update { it.copy(manualTradeSide = side) }
    fun updateManualTradeDate(value: String) =
        _uiState.update { it.copy(manualTradeDate = value.filter { char -> char.isDigit() || char == '-' }.take(10)) }
    fun updateManualTradeQuantity(value: String) =
        _uiState.update { it.copy(manualTradeQuantity = value.digitsOnly()) }
    fun updateManualTradePrice(value: String) =
        _uiState.update { it.copy(manualTradePrice = value.digitsOnly()) }

    fun startEditingManualTrade(order: StockOrderEntity) {
        val side = KisOrderSide.values().firstOrNull { it.name == order.side } ?: KisOrderSide.SELL
        _uiState.update {
            it.copy(
                manualTradeEditingOrderId = order.id,
                manualTradeProductCode = order.productCode,
                manualTradeProductName = order.productName,
                manualTradeSide = side,
                manualTradeDate = order.orderDate.toString(),
                manualTradeQuantity = order.filledQuantity.toString(),
                manualTradePrice = (order.filledAveragePrice ?: order.referencePrice).toString(),
                statusMessage = "상단 수동 체결 기록에서 내용을 수정해 주세요.",
            )
        }
    }

    fun cancelManualTradeEditing() {
        _uiState.update {
            it.copy(
                manualTradeEditingOrderId = null,
                manualTradeQuantity = "",
                manualTradePrice = "",
                statusMessage = "수동 체결 수정을 취소했습니다.",
            )
        }
    }

    fun saveManualTrade() {
        val state = _uiState.value
        launchAction("수동 체결 기록 저장에 실패했습니다.") {
            val orderDate = runCatching { LocalDate.parse(state.manualTradeDate) }
                .getOrElse { throw IllegalArgumentException("체결일을 YYYY-MM-DD 형식으로 입력해 주세요.") }
            val quantity = state.manualTradeQuantity.toLongOrNull()
                ?: throw IllegalArgumentException("체결 수량을 입력해 주세요.")
            val unitPrice = state.manualTradePrice.toLongOrNull()
                ?: throw IllegalArgumentException("실제 체결가를 입력해 주세요.")
            state.manualTradeEditingOrderId?.let { orderId ->
                repository.updateManualStockExecution(
                    orderId = orderId,
                    productCode = state.manualTradeProductCode,
                    productName = state.manualTradeProductName,
                    side = state.manualTradeSide,
                    orderDate = orderDate,
                    quantity = quantity,
                    unitPrice = unitPrice,
                )
            } ?: repository.saveManualStockExecution(
                productCode = state.manualTradeProductCode,
                productName = state.manualTradeProductName,
                side = state.manualTradeSide,
                orderDate = orderDate,
                quantity = quantity,
                unitPrice = unitPrice,
            )
            _uiState.update {
                it.copy(
                    manualTradeEditingOrderId = null,
                    manualTradeQuantity = "",
                    manualTradePrice = "",
                    statusMessage = if (state.manualTradeEditingOrderId == null) {
                        "수동 ${state.manualTradeSide.label} 기록을 저장했습니다."
                    } else {
                        "수동 ${state.manualTradeSide.label} 기록을 수정했습니다."
                    },
                )
            }
        }
    }

    fun allocateSellToBuyLot(sellOrderId: Long, buyOrderId: Long, quantity: Long) =
        launchAction("매수 건 연결에 실패했습니다.") {
            repository.allocateStockSellToBuyLot(sellOrderId, buyOrderId, quantity)
            _uiState.update { it.copy(statusMessage = "매도 기록에 매수 건을 연결했습니다.") }
        }

    fun deleteSellAllocation(allocation: StockSellAllocationEntity) =
        launchAction("매수 건 연결 취소에 실패했습니다.") {
            repository.deleteStockSellAllocation(allocation)
            _uiState.update { it.copy(statusMessage = "매수 건 연결을 취소했습니다.") }
        }

    fun updateMonitoringInterval(value: String) =
        _uiState.update { it.copy(monitorIntervalMinutes = value.digitsOnly()) }
    fun updateMaxOrderAmount(value: String) =
        _uiState.update { it.copy(maxOrderAmount = value.digitsOnly()) }
    fun updateDailyBuyLimit(value: String) =
        _uiState.update { it.copy(dailyBuyLimit = value.digitsOnly()) }
    fun updateCrashThreshold(value: String) =
        _uiState.update { it.copy(crashThresholdPercent = value.filter { char -> char.isDigit() || char == '.' }) }
    fun selectCrashBenchmark(code: String) = _uiState.update { it.copy(crashBenchmarkCode = code) }
    fun setCrashGuardEnabled(enabled: Boolean) = _uiState.update { it.copy(crashGuardEnabled = enabled) }
    fun setAutomaticOrderEnabled(enabled: Boolean) = _uiState.update { it.copy(automaticOrderEnabled = enabled) }

    fun saveSafetySettings() {
        val state = _uiState.value
        launchAction("안전 설정 저장에 실패했습니다.") {
            repository.saveStockSafetyConfig(
                state.safetyConfig.copy(
                    automaticOrderEnabled = state.automaticOrderEnabled,
                    crashGuardEnabled = state.crashGuardEnabled,
                    crashBenchmarkCode = state.crashBenchmarkCode.takeIf(String::isNotBlank),
                    crashThresholdPercent = state.crashThresholdPercent.toDoubleOrNull(),
                    monitorIntervalMinutes = state.monitorIntervalMinutes.toIntOrNull(),
                    maxOrderAmount = state.maxOrderAmount.toLongOrNull(),
                    dailyBuyLimit = state.dailyBuyLimit.toLongOrNull(),
                ),
            )
            _uiState.update { it.copy(statusMessage = "주식 자동화 안전 설정이 저장되었습니다.") }
        }
    }

    fun setMonitoringEnabled(enabled: Boolean, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            runCatching { repository.setStockMonitoringEnabled(enabled) }
                .onSuccess {
                    _uiState.update { it.copy(statusMessage = if (enabled) "주식 모니터링을 시작했습니다." else "주식 모니터링을 중지했습니다.") }
                    onResult(true)
                }
                .onFailure { error ->
                    recordStockError(
                        eventType = "MONITORING_STATE_CHANGE_FAILED",
                        title = "모니터링 상태 변경 실패",
                        error = error,
                        fallbackMessage = "모니터링 상태를 변경하지 못했습니다.",
                    )
                    _uiState.update { it.copy(statusMessage = error.message ?: "모니터링 상태 변경에 실패했습니다.") }
                    onResult(false)
                }
        }
    }

    fun setGlobalOrderBlock(blocked: Boolean) {
        launchAction("주문 차단 상태 변경에 실패했습니다.") {
            repository.setGlobalStockOrderBlock(blocked, if (blocked) "사용자 긴급 정지" else null)
        }
    }

    fun runAutomationOnce() {
        _uiState.update { it.copy(isRunningAutomation = true) }
        viewModelScope.launch {
            runCatching { repository.runStockAutomationCycle() }
                .onSuccess { result ->
                    _uiState.update {
                        it.copy(
                            isRunningAutomation = false,
                            statusMessage = result.skippedReason
                                ?: "자동화 규칙을 확인했습니다. 발생 알림 ${result.notices.size}건",
                        )
                    }
                }
                .onFailure { error ->
                    recordStockError(
                        eventType = "AUTOMATION_CHECK_FAILED",
                        title = "자동화 확인 실패",
                        error = error,
                        fallbackMessage = "자동화 규칙을 확인하지 못했습니다.",
                    )
                    _uiState.update {
                        it.copy(isRunningAutomation = false, statusMessage = "자동화 확인에 실패했습니다. ${error.message.orEmpty()}")
                    }
                }
        }
    }

    fun selectRuleProduct(code: String, name: String) =
        _uiState.update { it.copy(ruleProductCode = code, ruleProductName = name) }
    fun updateRuleProductCode(value: String) {
        val code = value.filter(Char::isLetterOrDigit).uppercase().take(7)
        _uiState.update { it.copy(ruleProductCode = code, ruleProductName = code) }
    }
    fun updateRuleProductName(value: String) =
        _uiState.update { it.copy(ruleProductName = value.take(40)) }
    fun selectRuleType(type: StockExitRuleType) =
        _uiState.update {
            it.copy(
                ruleType = type,
                ruleTriggerValue = "",
                ruleTriggerPrice = "",
                ruleAction = if (type != StockExitRuleType.INTRADAY_RISE && it.ruleAction == StockRuleAction.AUTO_BUY) {
                    StockRuleAction.NOTIFY_ONLY
                } else {
                    it.ruleAction
                },
                ruleProductCode = "",
                ruleProductName = "",
            )
        }
    fun selectRuleAction(action: StockRuleAction) =
        _uiState.update {
            it.copy(
                ruleAction = action,
                ruleProductCode = "",
                ruleProductName = "",
            )
        }
    fun updateRuleTriggerValue(value: String) =
        _uiState.update {
            val filtered = value.filter { char -> char.isDigit() || char == '.' }
            it.copy(ruleTriggerValue = filtered, ruleTriggerPrice = if (filtered.isNotBlank()) "" else it.ruleTriggerPrice)
        }
    fun updateRuleTriggerPrice(value: String) =
        _uiState.update {
            val filtered = value.digitsOnly()
            it.copy(ruleTriggerPrice = filtered, ruleTriggerValue = if (filtered.isNotBlank()) "" else it.ruleTriggerValue)
        }
    fun updateRuleSellPercent(value: String) =
        _uiState.update { it.copy(ruleSellPercent = value.filter { char -> char.isDigit() || char == '.' }) }
    fun selectRuleOrderDivision(code: String) = _uiState.update { it.copy(ruleOrderDivisionCode = code) }

    fun saveExitRule() {
        val state = _uiState.value
        launchAction("자동화 규칙 저장에 실패했습니다.") {
            repository.saveStockExitRule(
                ruleId = null,
                productCode = state.ruleProductCode,
                productName = state.ruleProductName,
                ruleType = state.ruleType,
                triggerValue = state.ruleTriggerValue.toDoubleOrNull(),
                triggerPrice = state.ruleTriggerPrice.toLongOrNull(),
                sellQuantityPercent = if (state.ruleAction != StockRuleAction.NOTIFY_ONLY) {
                    state.ruleSellPercent.toDoubleOrNull()
                        ?: throw IllegalArgumentException("주문 비율을 입력해 주세요.")
                } else {
                    0.0
                },
                actionMode = state.ruleAction,
                orderDivisionCode = state.ruleOrderDivisionCode,
            )
            _uiState.update {
                it.copy(
                    ruleTriggerValue = "",
                    ruleTriggerPrice = "",
                    ruleSellPercent = "",
                    statusMessage = "${state.ruleProductName} ${state.ruleType.label} 규칙이 저장되었습니다.",
                )
            }
        }
    }

    fun toggleExitRule(rule: StockExitRuleEntity) =
        launchAction("규칙 상태 변경에 실패했습니다.") { repository.setStockExitRuleEnabled(rule, !rule.enabled) }
    fun deleteExitRule(ruleId: Long) =
        launchAction("규칙 삭제에 실패했습니다.") { repository.deleteStockExitRule(ruleId) }

    fun selectTargetProduct(code: String, name: String) =
        _uiState.update { it.copy(targetProductCode = code, targetProductName = name) }
    fun updateTargetPercent(value: String) =
        _uiState.update { it.copy(targetPercent = value.filter { char -> char.isDigit() || char == '.' }) }

    fun saveTargetAllocation() {
        val state = _uiState.value
        launchAction("목표 비중 저장에 실패했습니다.") {
            repository.saveStockTargetAllocation(
                productCode = state.targetProductCode,
                productName = state.targetProductName,
                targetPercent = state.targetPercent.toDoubleOrNull()
                    ?: throw IllegalArgumentException("목표 비중을 입력해 주세요."),
            )
            _uiState.update { it.copy(targetPercent = "", statusMessage = "목표 비중이 저장되었습니다.") }
        }
    }

    fun deleteTargetAllocation(productCode: String) =
        launchAction("목표 비중 삭제에 실패했습니다.") { repository.deleteStockTargetAllocation(productCode) }

    fun calculateRebalance() {
        _uiState.update { it.copy(isCalculatingRebalance = true) }
        viewModelScope.launch {
            runCatching { repository.calculateStockRebalance() }
                .onSuccess { plan ->
                    _uiState.update { it.copy(rebalancePlan = plan, isCalculatingRebalance = false) }
                }
                .onFailure { error ->
                    recordStockError(
                        eventType = "REBALANCE_CALCULATION_FAILED",
                        title = "리밸런싱 계산 실패",
                        error = error,
                        fallbackMessage = "리밸런싱을 계산하지 못했습니다.",
                    )
                    _uiState.update {
                        it.copy(isCalculatingRebalance = false, statusMessage = "리밸런싱 계산에 실패했습니다. ${error.message.orEmpty()}")
                    }
                }
        }
    }

    fun executeRebalanceLine(line: StockRebalanceLine) {
        launchAction("리밸런싱 주문에 실패했습니다.") {
            val order = repository.executeStockRebalanceLine(line)
            _uiState.update { it.copy(statusMessage = "${order.productName} 리밸런싱 주문이 접수되었습니다. (${order.orderNumber})") }
        }
    }

    fun refreshJournal() {
        _uiState.update { it.copy(isLoadingJournal = true) }
        viewModelScope.launch {
            runCatching { repository.syncStockOrderExecutions() }
                .onSuccess {
                    _uiState.update { it.copy(isLoadingJournal = false) }
                }.onFailure { error ->
                    recordStockError(
                        eventType = "ORDER_SYNC_FAILED",
                        title = "매매일지 분석 실패",
                        error = error,
                        fallbackMessage = "매매일지를 분석하지 못했습니다.",
                    )
                    _uiState.update {
                        it.copy(isLoadingJournal = false, statusMessage = "매매일지 분석에 실패했습니다. ${error.message.orEmpty()}")
                    }
                }
        }
    }

    fun loadMoreStockAutomationEvents() {
        if (!_uiState.value.canLoadMoreAutomationEvents) return
        automationEventLimit.value += stockAutomationEventPageSize
    }

    fun clearStockAutomationEvents() =
        launchAction("주식 알림·오류 기록 삭제에 실패했습니다.") {
            repository.clearStockAutomationEvents()
            automationEventLimit.value = stockAutomationEventPageSize
            _uiState.update { it.copy(statusMessage = "주식 알림·오류 기록을 모두 삭제했습니다.") }
        }

    fun resolveUnknownOrder(orderId: Long) =
        launchAction("확인 대기 주문 처리에 실패했습니다.") {
            repository.resolveUnknownStockOrder(orderId)
            _uiState.update { it.copy(statusMessage = "KIS 확인 대기 주문을 미접수 상태로 처리했습니다.") }
        }

    fun clearStatusMessage() = _uiState.update { it.copy(statusMessage = null) }

    private fun loadConfigCompletion() {
        viewModelScope.launch {
            runCatching {
                repository.hasKisApiConfig(KisEnvironment.REAL) to
                    repository.getKisAccessTokenExpiredAt(KisEnvironment.REAL)
            }.onSuccess { (saved, expiresAt) ->
                _uiState.update { it.copy(isConfigSaved = saved, accessTokenExpiredAt = expiresAt) }
            }.onFailure { error ->
                recordStockError(
                    eventType = "KIS_CONFIG_CHECK_FAILED",
                    title = "KIS 설정 상태 확인 실패",
                    error = error,
                    fallbackMessage = "KIS 설정 상태를 확인하지 못했습니다.",
                )
                _uiState.update { it.copy(statusMessage = error.message ?: "KIS 설정 상태 확인에 실패했습니다.") }
            }
        }
    }

    private fun applySafetyConfig(config: StockSafetyConfigEntity) {
        _uiState.update { current ->
            val base = current.copy(safetyConfig = config)
            if (safetyFormInitialized) {
                base
            } else {
                safetyFormInitialized = true
                base.copy(
                    automaticOrderEnabled = config.automaticOrderEnabled,
                    crashGuardEnabled = config.crashGuardEnabled,
                    crashBenchmarkCode = config.crashBenchmarkCode.orEmpty(),
                    crashThresholdPercent = config.crashThresholdPercent?.toString().orEmpty(),
                    monitorIntervalMinutes = config.monitorIntervalMinutes?.toString().orEmpty(),
                    maxOrderAmount = config.maxOrderAmount?.toString().orEmpty(),
                    dailyBuyLimit = config.dailyBuyLimit?.toString().orEmpty(),
                )
            }
        }
    }

    /** 반복되는 코루틴 실행과 오류 기록을 고차 함수 하나로 공통화한다. */
    private fun launchAction(failurePrefix: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onFailure { error ->
                    recordStockError(
                        eventType = "STOCK_ACTION_FAILED",
                        title = failurePrefix,
                        error = error,
                        fallbackMessage = failurePrefix,
                    )
                    _uiState.update { it.copy(statusMessage = "$failurePrefix ${error.message.orEmpty()}") }
                }
        }
    }

    private suspend fun recordStockError(
        eventType: String,
        title: String,
        error: Throwable,
        fallbackMessage: String,
    ) {
        val message = error.message?.takeIf(String::isNotBlank) ?: fallbackMessage
        runCatching {
            repository.saveStockErrorEvent(
                eventType = eventType,
                title = title,
                message = message,
            )
        }.onFailure { saveError ->
            Log.e(
                stockViewModelLogTag,
                "Failed to persist stock error event. (eventType=$eventType)",
                saveError,
            )
        }
    }
}

data class StockUiState(
    val environment: KisEnvironment = KisEnvironment.REAL,
    val appKey: String = "",
    val appSecret: String = "",
    val accountNumber: String = "",
    val accountProductCode: String = "01",
    val isConfigSaved: Boolean = false,
    val isConfigExpanded: Boolean = false,
    val accessTokenExpiredAt: LocalDateTime? = null,
    val ownedStocks: List<KisBalanceStock> = emptyList(),
    val isLoadingOwnedStocks: Boolean = false,
    val hasLoadedOwnedStocks: Boolean = false,
    val marketCapStocks: List<KisMarketCapStock> = emptyList(),
    val isLoadingMarketCapStocks: Boolean = false,
    val orderSide: KisOrderSide = KisOrderSide.BUY,
    val productCode: String = "",
    val productName: String = "",
    val orderDivisionCode: String = "00",
    val orderQuantity: String = "",
    val orderUnitPrice: String = "",
    val orderCalculationAmount: String = "",
    val orderQuantityPercent: Int? = null,
    val orderCurrentPrice: Long? = null,
    val exchangeIdDivisionCode: String = "KRX",
    val sellType: String = "01",
    val conditionPrice: String = "",
    val isSubmittingOrder: Boolean = false,
    val orderAvailability: StockOrderAvailability? = null,
    val isLoadingOrderPrice: Boolean = false,
    val isLoadingOrderAvailability: Boolean = false,
    val orders: List<StockOrderEntity> = emptyList(),
    val sellAllocations: List<StockSellAllocationEntity> = emptyList(),
    val buyLotRows: List<StockBuyLotRow> = emptyList(),
    val isLoadingPortfolio: Boolean = false,
    val isSyncingOrders: Boolean = false,
    val safetyConfig: StockSafetyConfigEntity = StockSafetyConfigEntity(),
    val automaticOrderEnabled: Boolean = false,
    val crashGuardEnabled: Boolean = false,
    val crashBenchmarkCode: String = "",
    val crashThresholdPercent: String = "",
    val monitorIntervalMinutes: String = "",
    val maxOrderAmount: String = "",
    val dailyBuyLimit: String = "",
    val exitRules: List<StockExitRuleEntity> = emptyList(),
    val ruleProductCode: String = "",
    val ruleProductName: String = "",
    val ruleType: StockExitRuleType = StockExitRuleType.STOP_LOSS,
    val ruleTriggerValue: String = "",
    val ruleTriggerPrice: String = "",
    val ruleSellPercent: String = "",
    val ruleAction: StockRuleAction = StockRuleAction.NOTIFY_ONLY,
    val ruleOrderDivisionCode: String = "00",
    val isRunningAutomation: Boolean = false,
    val targetAllocations: List<StockTargetAllocationEntity> = emptyList(),
    val targetProductCode: String = "",
    val targetProductName: String = "",
    val targetPercent: String = "",
    val rebalancePlan: List<StockRebalanceLine> = emptyList(),
    val isCalculatingRebalance: Boolean = false,
    val journalAnalysis: StockJournalAnalysis? = null,
    val manualTradeEditingOrderId: Long? = null,
    val manualTradeProductCode: String = "",
    val manualTradeProductName: String = "",
    val manualTradeSide: KisOrderSide = KisOrderSide.SELL,
    val manualTradeDate: String = LocalDate.now().toString(),
    val manualTradeQuantity: String = "",
    val manualTradePrice: String = "",
    val automationEvents: List<StockAutomationEventEntity> = emptyList(),
    val canLoadMoreAutomationEvents: Boolean = false,
    val isLoadingJournal: Boolean = false,
    val statusMessage: String? = null,
) {
    fun toCashOrderDraft(): KisCashOrderDraft = KisCashOrderDraft(
        side = orderSide,
        productCode = productCode,
        orderDivisionCode = orderDivisionCode,
        orderQuantity = orderQuantity,
        orderUnitPrice = orderUnitPrice,
        exchangeIdDivisionCode = exchangeIdDivisionCode,
        sellType = sellType,
        conditionPrice = conditionPrice,
    )

}

private fun StockUiState.orderCalculationUnitPrice(): Long? = if (orderDivisionCode == "01") {
    orderCurrentPrice ?: orderAvailability?.currentPrice
} else {
    orderUnitPrice.toLongOrNull()
}
