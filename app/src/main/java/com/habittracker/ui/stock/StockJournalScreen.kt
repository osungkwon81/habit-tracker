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
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppSpacing
import com.habittracker.ui.components.AppStatusText
import com.habittracker.ui.components.AppSupportText

@Composable
fun StockJournalScreen(viewModel: StockViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var unknownToResolve by remember { mutableStateOf<StockOrderEntity?>(null) }
    StockStatusDialog(uiState, viewModel::clearStatusMessage)

    LaunchedEffect(uiState.isConfigSaved) {
        if (uiState.isConfigSaved) viewModel.refreshJournal()
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
                description = "앱에서 접수한 주문, 체결 결과, 자동화 사유와 추정 실현손익을 분석합니다.",
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
        uiState.journalAnalysis?.let { analysis ->
            item {
                AppSectionCard {
                    StockSectionTitle("자동 분석")
                    JournalMetric("체결 매수", "${analysis.filledBuyCount}건")
                    JournalMetric("체결 매도", "${analysis.filledSellCount}건")
                    JournalMetric("수익 매도", "${analysis.profitableTradeCount}/${analysis.realizedTradeCount}건")
                    JournalMetric("승률", analysis.winRatePercent?.toPercent() ?: "-")
                    JournalMetric("추정 실현손익", analysis.estimatedRealizedProfit.toWon())
                    if (analysis.sourceCounts.isNotEmpty()) {
                        Text("주문 사유", style = MaterialTheme.typography.titleSmall)
                        analysis.sourceCounts.forEach { (source, count) ->
                            Text("${source.label} ${count}건", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    AppSupportText("실현손익은 앱에서 체결 동기화한 매수 lot을 FIFO로 배분한 세금·수수료 미반영 추정치입니다.")
                }
            }
        }
        item { StockSectionTitle("주문 기록") }
        if (uiState.orders.isEmpty()) {
            item { AppSectionCard { AppSupportText("앱에서 접수한 주문 기록이 없습니다.") } }
        }
        items(uiState.orders.size) { index ->
            val order = uiState.orders[index]
            val source = StockOrderSource.values().firstOrNull { it.name == order.source }
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
                AppStatusText(order.status.toKoreanOrderStatus())
                order.estimatedRealizedProfit?.let { profit ->
                    Text(
                        "추정 실현손익 ${profit.toWon()}",
                        color = if (profit >= 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleSmall,
                    )
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
