package com.habittracker.ui.stock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.habittracker.data.local.entity.StockAutomationEventEntity
import com.habittracker.data.local.entity.StockExitRuleEntity
import com.habittracker.data.local.entity.StockOrderEntity
import com.habittracker.data.local.entity.StockSafetyConfigEntity
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
import com.habittracker.data.stock.StockRebalanceLine
import com.habittracker.data.stock.StockRuleAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime

class StockViewModel(
    private val repository: HabitRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(StockUiState())
    val uiState: StateFlow<StockUiState> = _uiState.asStateFlow()
    private var safetyFormInitialized = false

    init {
        observeTradingData()
        loadConfigCompletion()
    }

    private fun observeTradingData() {
        viewModelScope.launch {
            repository.observeStockOrders().collect { orders ->
                _uiState.update { it.copy(orders = orders) }
                refreshJournalAnalysisOnly()
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
            repository.observeStockAutomationEvents().collect { events ->
                _uiState.update { it.copy(automationEvents = events) }
            }
        }
        viewModelScope.launch {
            repository.observeStockSafetyConfig().collect { config ->
                if (config != null) applySafetyConfig(config)
            }
        }
    }

    fun updateAppKey(value: String) = _uiState.update { it.copy(appKey = value.trim()) }
    fun updateAppSecret(value: String) = _uiState.update { it.copy(appSecret = value.trim()) }
    fun updateAccountNumber(value: String) =
        _uiState.update { it.copy(accountNumber = value.filter(Char::isDigit).take(8)) }
    fun updateAccountProductCode(value: String) =
        _uiState.update { it.copy(accountProductCode = value.filter(Char::isDigit).take(2)) }
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
            runCatching { repository.getKisBalanceStocks() }
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
        _uiState.update { it.copy(orderSide = side, productCode = "", productName = "", statusMessage = null) }
        if (side == KisOrderSide.SELL) loadOwnedStocks() else loadMarketCapStocks()
    }

    fun selectOrderProduct(productCode: String, productName: String) =
        _uiState.update { it.copy(productCode = productCode, productName = productName) }
    fun updateOrderProductCode(value: String) {
        val productCode = value.filter(Char::isLetterOrDigit).uppercase().take(7)
        _uiState.update { it.copy(productCode = productCode, productName = productCode) }
    }
    fun updateOrderDivisionCode(value: String) =
        _uiState.update { it.copy(orderDivisionCode = value.filter(Char::isDigit).take(2)) }
    fun updateOrderQuantity(value: String) = _uiState.update { it.copy(orderQuantity = value.filter(Char::isDigit)) }
    fun updateOrderUnitPrice(value: String) = _uiState.update { it.copy(orderUnitPrice = value.filter(Char::isDigit)) }
    fun updateExchangeIdDivisionCode(value: String) =
        _uiState.update { it.copy(exchangeIdDivisionCode = value.filter(Char::isLetter).uppercase().take(3)) }
    fun updateSellType(value: String) = _uiState.update { it.copy(sellType = value.filter(Char::isDigit).take(2)) }
    fun updateConditionPrice(value: String) = _uiState.update { it.copy(conditionPrice = value.filter(Char::isDigit)) }

    fun submitCashOrder() {
        val state = _uiState.value
        _uiState.update { it.copy(isSubmittingOrder = true) }
        viewModelScope.launch {
            runCatching {
                repository.placeKisCashOrder(
                    draft = state.toCashOrderDraft(),
                    productName = state.productName,
                    source = StockOrderSource.MANUAL,
                )
            }.onSuccess { order ->
                _uiState.update {
                    it.copy(
                        isSubmittingOrder = false,
                        orderQuantity = "",
                        statusMessage = "${order.productName} ${state.orderSide.label} 주문이 접수되었습니다. 주문번호 ${order.orderNumber}",
                    )
                }
            }.onFailure { error ->
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
                        exchangeIdDivisionCode = "KRX",
                        sellType = "01",
                        conditionPrice = "",
                    ),
                    productName = order.productName,
                    source = StockOrderSource.MANUAL,
                )
            }.onSuccess { sellOrder ->
                _uiState.update {
                    it.copy(
                        isSubmittingOrder = false,
                        statusMessage = "${sellOrder.productName} ${quantity}주 매도 주문이 접수되었습니다. 주문번호 ${sellOrder.orderNumber}",
                    )
                }
            }.onFailure { error ->
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
                    _uiState.update {
                        it.copy(
                            isSubmittingOrder = false,
                            statusMessage = error.message ?: "보유 종목 전체 매도에 실패했습니다.",
                        )
                    }
                }
        }
    }

    fun loadPortfolioData() {
        if (!_uiState.value.isConfigSaved) return
        _uiState.update { it.copy(isLoadingPortfolio = true) }
        viewModelScope.launch {
            runCatching {
                repository.syncStockOrderExecutions()
                val balanceStocks = repository.getKisBalanceStocks()
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
                    _uiState.update { it.copy(isSyncingOrders = false, statusMessage = "주문 체결 상태 ${count}건을 확인했습니다.") }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isSyncingOrders = false, statusMessage = "체결 상태 확인에 실패했습니다. ${error.message.orEmpty()}")
                    }
                }
        }
    }

    fun updateMonitoringInterval(value: String) =
        _uiState.update { it.copy(monitorIntervalMinutes = value.filter(Char::isDigit)) }
    fun updateMaxOrderAmount(value: String) =
        _uiState.update { it.copy(maxOrderAmount = value.filter(Char::isDigit)) }
    fun updateDailyBuyLimit(value: String) =
        _uiState.update { it.copy(dailyBuyLimit = value.filter(Char::isDigit)) }
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
                    _uiState.update {
                        it.copy(isRunningAutomation = false, statusMessage = "자동화 확인에 실패했습니다. ${error.message.orEmpty()}")
                    }
                }
        }
    }

    fun selectRuleProduct(code: String, name: String) =
        _uiState.update { it.copy(ruleProductCode = code, ruleProductName = name) }
    fun selectRuleType(type: StockExitRuleType) = _uiState.update { it.copy(ruleType = type) }
    fun selectRuleAction(action: StockRuleAction) = _uiState.update { it.copy(ruleAction = action) }
    fun updateRuleTriggerValue(value: String) =
        _uiState.update { it.copy(ruleTriggerValue = value.filter { char -> char.isDigit() || char == '.' }) }
    fun updateRuleSellPercent(value: String) =
        _uiState.update { it.copy(ruleSellPercent = value.filter { char -> char.isDigit() || char == '.' }) }
    fun selectRuleOrderDivision(code: String) = _uiState.update { it.copy(ruleOrderDivisionCode = code) }

    fun saveExitRule() {
        val state = _uiState.value
        launchAction("자동 매도 규칙 저장에 실패했습니다.") {
            repository.saveStockExitRule(
                ruleId = null,
                productCode = state.ruleProductCode,
                productName = state.ruleProductName,
                ruleType = state.ruleType,
                triggerValue = state.ruleTriggerValue.toDoubleOrNull()
                    ?: throw IllegalArgumentException("발동 기준을 입력해 주세요."),
                sellQuantityPercent = if (state.ruleAction == StockRuleAction.AUTO_SELL) {
                    state.ruleSellPercent.toDoubleOrNull()
                        ?: throw IllegalArgumentException("매도 비율을 입력해 주세요.")
                } else {
                    0.0
                },
                actionMode = state.ruleAction,
                orderDivisionCode = state.ruleOrderDivisionCode,
            )
            _uiState.update {
                it.copy(
                    ruleTriggerValue = "",
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
            runCatching {
                repository.syncStockOrderExecutions()
                repository.getStockJournalAnalysis()
            }.onSuccess { analysis ->
                _uiState.update { it.copy(journalAnalysis = analysis, isLoadingJournal = false) }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(isLoadingJournal = false, statusMessage = "매매일지 분석에 실패했습니다. ${error.message.orEmpty()}")
                }
            }
        }
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

    private fun refreshJournalAnalysisOnly() {
        viewModelScope.launch {
            runCatching { repository.getStockJournalAnalysis() }
                .onSuccess { analysis -> _uiState.update { it.copy(journalAnalysis = analysis) } }
        }
    }

    private fun launchAction(failurePrefix: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onFailure { error ->
                    _uiState.update { it.copy(statusMessage = "$failurePrefix ${error.message.orEmpty()}") }
                }
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
    val exchangeIdDivisionCode: String = "KRX",
    val sellType: String = "01",
    val conditionPrice: String = "",
    val isSubmittingOrder: Boolean = false,
    val orders: List<StockOrderEntity> = emptyList(),
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
    val automationEvents: List<StockAutomationEventEntity> = emptyList(),
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
