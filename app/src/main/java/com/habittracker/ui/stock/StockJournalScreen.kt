package com.habittracker.ui.stock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.habittracker.data.local.entity.StockOrderEntity
import com.habittracker.data.stock.KisOrderSide
import com.habittracker.data.stock.StockOrderSource
import com.habittracker.data.stock.StockOrderStatus
import com.habittracker.ui.components.AppPrimaryButton
import com.habittracker.ui.components.AppScreen
import com.habittracker.ui.components.AppSecondaryButton
import com.habittracker.ui.components.AppSelectableChip
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppSpacing
import com.habittracker.ui.components.AppStatusText
import com.habittracker.ui.components.AppSupportText
import com.habittracker.ui.components.AppTextField

@Composable
fun StockJournalScreen(viewModel: StockViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var unknownToResolve by remember { mutableStateOf<StockOrderEntity?>(null) }
    var isManualTradeExpanded by remember { mutableStateOf(false) }
    val allocationsBySellOrder = remember(uiState.sellAllocations) {
        uiState.sellAllocations.groupBy { it.sellOrderId }
    }
    val allocatedOrderIds = remember(uiState.sellAllocations) {
        buildSet {
            uiState.sellAllocations.forEach { allocation ->
                add(allocation.sellOrderId)
                add(allocation.buyOrderId)
            }
        }
    }
    val openBuyLotsByProduct = remember(uiState.orders) {
        uiState.orders
            .asSequence()
            .filter { it.side == KisOrderSide.BUY.name && it.remainingQuantity > 0L }
            .sortedWith(compareBy(StockOrderEntity::orderDate, StockOrderEntity::orderTime))
            .groupBy(StockOrderEntity::productCode)
    }
    StockStatusDialog(uiState, viewModel::clearStatusMessage)

    LaunchedEffect(uiState.isConfigSaved) {
        if (uiState.isConfigSaved) {
            viewModel.loadOwnedStocks()
            viewModel.refreshJournal()
        }
    }

    LaunchedEffect(uiState.manualTradeEditingOrderId) {
        if (uiState.manualTradeEditingOrderId != null) {
            isManualTradeExpanded = true
        }
    }

    unknownToResolve?.let { order ->
        AlertDialog(
            onDismissRequest = { unknownToResolve = null },
            title = { Text("KIS 주문내역 확인 완료") },
            text = {
                Text(
                    "${order.productName} ${order.requestedQuantity}주 주문이 KIS 주문내역에 접수되지 않은 것을 직접 확인한 경우에만 처리하세요. " +
                        "실제로 접수된 주문이면 중복 주문 위험이 있습니다.",
                )
            },
            confirmButton = {
                AppPrimaryButton(
                    text = "미접수로 처리",
                    onClick = {
                        viewModel.resolveUnknownOrder(order.id)
                        unknownToResolve = null
                    },
                )
            },
            dismissButton = {
                AppSecondaryButton(text = "취소", onClick = { unknownToResolve = null })
            },
        )
    }

    AppScreen {
        item {
            StockHeroCard(
                icon = "✎",
                eyebrow = "STOCK · JOURNAL",
                title = "매매일지",
                description = "오늘부터 KIS 체결을 자동으로 저장하고 매도 건을 실제 매수 lot과 연결합니다.",
            )
        }
        item {
            AppSecondaryButton(
                text = if (uiState.isLoadingJournal) "분석 중" else "체결 동기화·다시 분석",
                onClick = viewModel::refreshJournal,
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState.isConfigSaved && !uiState.isLoadingJournal,
            )
        }
        item {
            AppSectionCard {
                StockSectionTitle(
                    if (uiState.manualTradeEditingOrderId == null) "수동 체결 기록" else "수동 체결 기록 수정",
                )
                AppSecondaryButton(
                    text = if (isManualTradeExpanded) "입력 닫기" else "입력 펼치기",
                    onClick = { isManualTradeExpanded = !isManualTradeExpanded },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isManualTradeExpanded) {
                    StockProductDropdown(
                        label = "종목",
                        selectedCode = uiState.manualTradeProductCode,
                        options = uiState.manualTradeProductOptions(),
                        enabled = !uiState.isLoadingOwnedStocks,
                        onSelect = { viewModel.selectManualTradeProduct(it.code, it.name) },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                    ) {
                        KisOrderSide.values().forEach { side ->
                            AppSelectableChip(
                                label = side.label,
                                selected = uiState.manualTradeSide == side,
                                onClick = { viewModel.selectManualTradeSide(side) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    AppTextField(
                        value = uiState.manualTradeDate,
                        onValueChange = viewModel::updateManualTradeDate,
                        label = "실제 체결일 (YYYY-MM-DD)",
                        singleLine = true,
                    )
                    AppTextField(
                        value = uiState.manualTradeQuantity,
                        onValueChange = viewModel::updateManualTradeQuantity,
                        label = "실제 체결 수량",
                        singleLine = true,
                    )
                    AppTextField(
                        value = uiState.manualTradePrice,
                        onValueChange = viewModel::updateManualTradePrice,
                        label = "실제 체결 단가 (원)",
                        singleLine = true,
                    )
                    AppPrimaryButton(
                        text = if (uiState.manualTradeEditingOrderId == null) "수동 체결 저장" else "수정 저장",
                        onClick = viewModel::saveManualTrade,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = uiState.manualTradeProductCode.isNotBlank() &&
                            uiState.manualTradeQuantity.toLongOrNull()?.let { it > 0L } == true &&
                            uiState.manualTradePrice.toLongOrNull()?.let { it > 0L } == true,
                    )
                    if (uiState.manualTradeEditingOrderId != null) {
                        AppSecondaryButton(
                            text = "수정 취소",
                            onClick = viewModel::cancelManualTradeEditing,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    AppSupportText("손익은 입력하지 않습니다. 매도 기록에서 매수 건을 연결하면 앱이 자동 계산합니다.")
                }
            }
        }
        uiState.journalAnalysis?.let { analysis ->
            item {
                AppSectionCard {
                    StockSectionTitle("자동 분석")
                    JournalMetric("체결 매수", "${analysis.filledBuyCount}건")
                    JournalMetric("체결 매도", "${analysis.filledSellCount}건")
                    JournalMetric("수익 매도", "${analysis.profitableTradeCount}/${analysis.realizedTradeCount}건")
                    JournalMetric("승률", analysis.winRatePercent?.toPercent() ?: "-")
                    JournalMetric("실현손익(수수료 전)", analysis.estimatedRealizedProfit.toWon())
                    if (analysis.sourceCounts.isNotEmpty()) {
                        Text("주문 출처", style = MaterialTheme.typography.titleSmall)
                        analysis.sourceCounts.forEach { (source, count) ->
                            Text("${source.label} ${count}건", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    AppSupportText(
                        "실현손익은 사용자가 연결한 매수 lot과 실제 매도 체결가로 계산한 세금·수수료 미반영 금액입니다.",
                    )
                }
            }
        }
        item { StockSectionTitle("주문 기록") }
        if (uiState.orders.isEmpty()) {
            item { AppSectionCard { AppSupportText("저장된 주문 기록이 없습니다.") } }
        }
        item {
            AppSupportText("KIS 외부 주문은 KIS 앱·HTS 등 이 앱 밖의 체결이며, 세부 주문 채널은 구분하지 않습니다.")
        }
        items(uiState.orders.size) { index ->
            val order = uiState.orders[index]
            val source = StockOrderSource.values().firstOrNull { it.name == order.source }
            val allocations = allocationsBySellOrder[order.id].orEmpty()
            val hasAllocation = order.id in allocatedOrderIds
            val canEditManualOrder = order.source == StockOrderSource.MANUAL_ENTRY.name &&
                !hasAllocation &&
                if (order.side == KisOrderSide.BUY.name) {
                    order.remainingQuantity == order.filledQuantity
                } else {
                    order.appliedFilledQuantity == 0L
                }
            val unallocatedQuantity = (order.filledQuantity - order.appliedFilledQuantity).coerceAtLeast(0L)
            var allocationQuantity by remember(order.id, unallocatedQuantity) {
                mutableStateOf(unallocatedQuantity.toString())
            }
            val availableBuyLots = openBuyLotsByProduct[order.productCode]
                .orEmpty()
                .filter { buyOrder -> !buyOrder.orderDate.isAfter(order.orderDate) }
            AppSectionCard {
                Text("${order.productName} (${order.productCode})", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${order.orderDate} ${order.orderTime} · ${source?.label ?: order.source}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${if (order.side == KisOrderSide.BUY.name) "매수" else "매도"} 주문 ${order.requestedQuantity}주 · 체결 ${order.filledQuantity}주",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "주문 ${order.requestedUnitPrice.toWon()} · 체결 ${order.filledAveragePrice.toWon()}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (order.side == KisOrderSide.BUY.name && order.filledQuantity > 0L) {
                    AppStatusText("매수 lot 남은 수량 ${order.remainingQuantity}주")
                }
                AppStatusText(order.status.toKoreanOrderStatus())
                if (order.source == StockOrderSource.MANUAL_ENTRY.name) {
                    AppSecondaryButton(
                        text = "수동 기록 수정",
                        onClick = { viewModel.startEditingManualTrade(order) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = canEditManualOrder,
                    )
                    if (!canEditManualOrder) {
                        AppSupportText("매수 건 연결을 먼저 취소하면 이 기록을 수정할 수 있습니다.")
                    }
                }
                order.estimatedRealizedProfit?.let { profit ->
                    Text(
                        "연결 손익 ${profit.toWon()}",
                        color = if (profit >= 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                if (order.side == KisOrderSide.SELL.name && order.filledQuantity > 0L) {
                    allocations.forEach { allocation ->
                        Text(
                            "${allocation.quantity}주 · 매수 ${allocation.buyUnitPrice.toWon()} → " +
                                "매도 ${allocation.sellUnitPrice.toWon()} · 손익 ${allocation.realizedProfit.toWon()}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (allocation.realizedProfit >= 0L) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        AppSecondaryButton(
                            text = "매수 건 연결 취소",
                            onClick = { viewModel.deleteSellAllocation(allocation) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (unallocatedQuantity > 0L) {
                        AppStatusText("매수 건 미연결 ${unallocatedQuantity}주")
                        AppTextField(
                            value = allocationQuantity,
                            onValueChange = { allocationQuantity = it.filter(Char::isDigit) },
                            label = "연결할 수량",
                            singleLine = true,
                        )
                        availableBuyLots.forEach { buyLot ->
                            val buyPrice = buyLot.filledAveragePrice ?: buyLot.referencePrice
                            AppSecondaryButton(
                                text = "${buyLot.orderDate} · ${buyPrice.toWon()} · " +
                                    "남은 ${buyLot.remainingQuantity}주에서 선택",
                                onClick = {
                                    allocationQuantity.toLongOrNull()?.let { quantity ->
                                        viewModel.allocateSellToBuyLot(order.id, buyLot.id, quantity)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = allocationQuantity.toLongOrNull()?.let { quantity ->
                                    quantity > 0L &&
                                        quantity <= unallocatedQuantity &&
                                        quantity <= buyLot.remainingQuantity
                                } == true,
                            )
                        }
                        if (availableBuyLots.isEmpty()) {
                            AppSupportText("연결 가능한 매수 기록이 없습니다. 필요한 매수 체결을 수동으로 먼저 입력해 주세요.")
                        }
                    } else {
                        AppStatusText("매수 건 연결 완료")
                    }
                }
                order.message?.takeIf(String::isNotBlank)?.let { AppSupportText(it) }
                if (order.status == StockOrderStatus.UNKNOWN.name) {
                    AppPrimaryButton(
                        text = "KIS 주문내역 확인 후 처리",
                        onClick = { unknownToResolve = order },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        item { StockSectionTitle("자동화 이벤트") }
        if (uiState.automationEvents.isEmpty()) {
            item { AppSectionCard { AppSupportText("자동화 이벤트가 없습니다.") } }
        }
        items(uiState.automationEvents.size.coerceAtMost(50)) { index ->
            val event = uiState.automationEvents[index]
            AppSectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(event.eventType, style = MaterialTheme.typography.labelLarge)
                    Text(
                        event.createdAt.toString().replace('T', ' ').take(19),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(event.message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun StockUiState.manualTradeProductOptions(): List<StockProductOption> =
    (ownedProductOptions() + orders.map { order ->
        StockProductOption(
            code = order.productCode,
            name = order.productName,
            description = "",
        )
    }).distinctBy(StockProductOption::code)

@Composable
private fun JournalMetric(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

private fun String.toKoreanOrderStatus(): String = when (this) {
    StockOrderStatus.SUBMITTED.name -> "접수·체결 확인 대기"
    StockOrderStatus.PARTIALLY_FILLED.name -> "일부 체결"
    StockOrderStatus.FILLED.name -> "전량 체결"
    StockOrderStatus.CANCELED.name -> "취소"
    StockOrderStatus.UNKNOWN.name -> "응답 불명확·KIS 직접 확인 필요"
    StockOrderStatus.REJECTED.name -> "미접수 또는 거절"
    else -> this
}
