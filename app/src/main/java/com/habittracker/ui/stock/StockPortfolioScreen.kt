package com.habittracker.ui.stock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.habittracker.data.stock.StockBuyLotRow
import com.habittracker.ui.components.AppLoadingCard
import com.habittracker.ui.components.AppPrimaryButton
import com.habittracker.ui.components.AppScreen
import com.habittracker.ui.components.AppSecondaryButton
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppSpacing
import com.habittracker.ui.components.AppSupportText
import com.habittracker.ui.components.AppTextField

@Composable
fun StockPortfolioScreen(viewModel: StockViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var pendingSellRow by remember { mutableStateOf<StockBuyLotRow?>(null) }
    var sellQuantity by remember { mutableStateOf("") }
    StockStatusDialog(uiState, viewModel::clearStatusMessage)

    LaunchedEffect(uiState.isConfigSaved) {
        if (uiState.isConfigSaved) viewModel.loadPortfolioData()
    }

    pendingSellRow?.let { row ->
        val order = row.order
        val quantity = sellQuantity.toLongOrNull()
        val buyPrice = order.filledAveragePrice ?: order.referencePrice
        val currentPrice = row.currentPrice
        val expectedProfit = if (quantity != null && currentPrice != null) {
            (currentPrice - buyPrice) * quantity
        } else {
            null
        }
        AlertDialog(
            onDismissRequest = {
                pendingSellRow = null
                sellQuantity = ""
            },
            title = { Text("실전 매도 주문 확인") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    Text("${order.productName} (${order.productCode})")
                    Text("현재가 ${currentPrice.toWon()} 지정가로 주문합니다.")
                    AppTextField(
                        value = sellQuantity,
                        onValueChange = { sellQuantity = it.filter(Char::isDigit) },
                        label = "매도 수량 (최대 ${order.remainingQuantity}주)",
                        singleLine = true,
                    )
                    expectedProfit?.let { profit ->
                        Text("추정 손익 ${profit.toWon()}", style = MaterialTheme.typography.bodyMedium)
                    }
                    AppSupportText("실제 계좌에 주문을 전송하며, 체결 후 매수 내역은 오래된 매수 건부터 차감됩니다.")
                }
            },
            confirmButton = {
                AppPrimaryButton(
                    text = if (uiState.isSubmittingOrder) "주문 전송 중" else "실전 매도 주문",
                    onClick = {
                        viewModel.submitBuyLotSell(row, quantity ?: 0L)
                        pendingSellRow = null
                        sellQuantity = ""
                    },
                    enabled = !uiState.isSubmittingOrder &&
                        !uiState.safetyConfig.globalOrderBlocked &&
                        currentPrice != null &&
                        quantity != null &&
                        quantity in 1L..order.remainingQuantity,
                )
            },
            dismissButton = {
                AppSecondaryButton(
                    text = "취소",
                    onClick = {
                        pendingSellRow = null
                        sellQuantity = ""
                    },
                )
            },
        )
    }

    AppScreen {
        item {
            StockHeroCard(
                icon = "▦",
                eyebrow = "STOCK · PORTFOLIO",
                title = "보유·매수 내역",
                description = "매수 주문 1건을 1행으로 묶어 수량·단가·잔여수량·수익률을 표시합니다.",
            )
        }
        item {
            AppSecondaryButton(
                text = if (uiState.isLoadingPortfolio) "갱신 중" else "체결·현재가 갱신",
                onClick = viewModel::loadPortfolioData,
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState.isConfigSaved && !uiState.isLoadingPortfolio,
            )
        }
        if (uiState.isLoadingPortfolio) {
            item { AppLoadingCard("KIS 체결 상태와 현재가를 확인하고 있습니다.") }
        }
        item {
            AppSectionCard {
                StockSectionTitle("현재 보유 종목")
                uiState.ownedStocks.forEach { stock ->
                    Text(
                        "${stock.productName} (${stock.productCode})",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "현재가 ${stock.currentPrice.toWon()}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "${stock.quantity}주 · 평균단가 ${stock.averagePrice.toWon()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (uiState.ownedStocks.isEmpty()) AppSupportText("조회된 보유 종목이 없습니다.")
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                StockSectionTitle("매수 주문별 수익률")
                AppSupportText("행의 잔여 수량을 기준으로 현재가 지정가 매도를 준비합니다.")
            }
        }
        if (uiState.buyLotRows.isEmpty() && !uiState.isLoadingPortfolio) {
            item {
                AppSectionCard {
                    AppSupportText("앱에서 주문하고 체결 동기화가 완료된 매수 건이 없습니다. 기존 보유분은 KIS가 제공하는 평균단가로 위에서 표시됩니다.")
                }
            }
        }
        items(uiState.buyLotRows.size) { index ->
            val row = uiState.buyLotRows[index]
            val order = row.order
            AppSectionCard {
                Text(
                    "${order.productName} (${order.productCode})",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "${order.orderDate} ${order.orderTime} · 주문번호 ${order.orderNumber}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "매수 ${order.filledQuantity}주 · 잔여 ${order.remainingQuantity}주",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "체결단가 ${(order.filledAveragePrice ?: order.referencePrice).toWon()} · 현재가 ${row.currentPrice.toWon()}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "수익률 ${row.estimatedReturnPercent?.toPercent() ?: "-"}",
                    style = MaterialTheme.typography.titleSmall,
                    color = when {
                        (row.estimatedReturnPercent ?: 0.0) > 0.0 -> MaterialTheme.colorScheme.primary
                        (row.estimatedReturnPercent ?: 0.0) < 0.0 -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                AppSecondaryButton(
                    text = when {
                        order.remainingQuantity <= 0L -> "매도할 잔여 수량 없음"
                        row.currentPrice == null -> "현재가 확인 필요"
                        else -> "이 수량 매도"
                    },
                    onClick = {
                        pendingSellRow = row
                        sellQuantity = order.remainingQuantity.toString()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = order.remainingQuantity > 0L &&
                        row.currentPrice != null &&
                        !uiState.isSubmittingOrder &&
                        !uiState.safetyConfig.globalOrderBlocked,
                )
            }
        }
        item {
            AppSupportText("표시 수익률은 체결단가와 현재가 기준이며 세금·수수료를 반영하지 않은 추정치입니다.")
        }
    }
}
