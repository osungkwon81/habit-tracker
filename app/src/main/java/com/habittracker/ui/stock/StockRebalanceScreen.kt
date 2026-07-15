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
import com.habittracker.data.stock.StockRebalanceLine
import com.habittracker.ui.components.AppPrimaryButton
import com.habittracker.ui.components.AppScreen
import com.habittracker.ui.components.AppSecondaryButton
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppSpacing
import com.habittracker.ui.components.AppStatusText
import com.habittracker.ui.components.AppSupportText
import com.habittracker.ui.components.AppTextField

@Composable
fun StockRebalanceScreen(viewModel: StockViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var confirmationLine by remember { mutableStateOf<StockRebalanceLine?>(null) }
    StockStatusDialog(uiState, viewModel::clearStatusMessage)

    LaunchedEffect(uiState.isConfigSaved) {
        if (uiState.isConfigSaved) viewModel.loadReferenceStocks()
    }

    confirmationLine?.let { line ->
        AlertDialog(
            onDismissRequest = { confirmationLine = null },
            title = { Text("실전 리밸런싱 주문") },
            text = {
                Text(
                    "${line.productName} (${line.productCode})\n" +
                        "${line.orderSide?.label} ${line.orderQuantity}주 · ${line.referencePrice.toWon()} 지정가\n\n" +
                        "실제 계좌에 주문을 전송합니다.",
                )
            },
            confirmButton = {
                AppPrimaryButton(
                    text = "주문 전송",
                    onClick = {
                        viewModel.executeRebalanceLine(line)
                        confirmationLine = null
                    },
                )
            },
            dismissButton = {
                AppSecondaryButton(text = "취소", onClick = { confirmationLine = null })
            },
        )
    }

    AppScreen {
        item {
            StockHeroCard(
                icon = "⚖",
                eyebrow = "STOCK · REBALANCE",
                title = "목표 비중 리밸런싱",
                description = "목표로 등록한 종목들의 보유 평가액 안에서 목표 수량을 계산합니다. 현금과 미등록 종목은 제외합니다.",
            )
        }
        if (uiState.safetyConfig.globalOrderBlocked) {
            item {
                AppSectionCard {
                    StockSectionTitle("주문 차단 중")
                    AppStatusText(uiState.safetyConfig.blockReason ?: "전체 주문 차단")
                }
            }
        }
        item {
            AppSectionCard {
                StockSectionTitle("목표 비중 추가")
                StockProductDropdown(
                    label = "종목",
                    selectedCode = uiState.targetProductCode,
                    options = uiState.allProductOptions(),
                    enabled = !uiState.isLoadingOwnedStocks && !uiState.isLoadingMarketCapStocks,
                    onSelect = { viewModel.selectTargetProduct(it.code, it.name) },
                )
                AppTextField(
                    value = uiState.targetPercent,
                    onValueChange = viewModel::updateTargetPercent,
                    label = "목표 비중 (%)",
                    singleLine = true,
                )
                AppPrimaryButton(
                    text = "목표 비중 저장",
                    onClick = viewModel::saveTargetAllocation,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.targetProductCode.isNotBlank() &&
                        uiState.targetPercent.toDoubleOrNull()?.let { it > 0.0 } == true,
                )
                AppSupportText("목표 비중 합계는 100% 이하여야 하며, 남는 비중은 현금 여유분으로 볼 수 있습니다.")
            }
        }
        item {
            AppSectionCard {
                StockSectionTitle("저장된 목표")
                val total = uiState.targetAllocations.filter { it.enabled }.sumOf { it.targetPercent }
                AppStatusText("목표 합계 ${String.format("%.2f", total)}%")
                uiState.targetAllocations.forEach { target ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("${target.productName} (${target.productCode})", modifier = Modifier.weight(1f))
                        Text("${target.targetPercent}%")
                    }
                    AppSecondaryButton(
                        text = "${target.productName} 목표 삭제",
                        onClick = { viewModel.deleteTargetAllocation(target.productCode) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (uiState.targetAllocations.isEmpty()) AppSupportText("저장된 목표 비중이 없습니다.")
            }
        }
        item {
            AppPrimaryButton(
                text = if (uiState.isCalculatingRebalance) "계산 중" else "현재가 기준 리밸런싱 계산",
                onClick = viewModel::calculateRebalance,
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState.isConfigSaved &&
                    uiState.targetAllocations.isNotEmpty() &&
                    !uiState.isCalculatingRebalance,
            )
        }
        if (uiState.rebalancePlan.isNotEmpty()) {
            item { StockSectionTitle("계산 결과") }
        }
        items(uiState.rebalancePlan.size) { index ->
            val line = uiState.rebalancePlan[index]
            AppSectionCard {
                Text("${line.productName} (${line.productCode})", style = MaterialTheme.typography.titleMedium)
                Text(
                    "현재 ${String.format("%.2f", line.currentPercent)}% · 목표 ${line.targetPercent}%",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "현재 ${line.currentQuantity}주 → 목표 ${line.targetQuantity}주",
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (line.orderSide == null) {
                    AppStatusText("주문 필요 없음")
                } else {
                    AppStatusText("${line.orderSide.label} ${line.orderQuantity}주 · ${line.referencePrice.toWon()} 지정가")
                    AppPrimaryButton(
                        text = "${line.orderSide.label} 주문 확인",
                        onClick = { confirmationLine = line },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.safetyConfig.globalOrderBlocked,
                    )
                }
            }
        }
        item {
            AppSupportText("리밸런싱 계산은 현재가 스냅샷 기준입니다. 각 주문은 매수가능금액·매도가능수량·급락 차단·주문 한도를 다시 검사합니다.")
        }
    }
}
