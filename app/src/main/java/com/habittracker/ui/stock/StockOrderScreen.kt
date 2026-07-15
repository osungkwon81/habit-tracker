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
import com.habittracker.data.stock.KisOrderSide
import com.habittracker.ui.components.AppPrimaryButton
import com.habittracker.ui.components.AppScreen
import com.habittracker.ui.components.AppSecondaryButton
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppSelectableChip
import com.habittracker.ui.components.AppSpacing
import com.habittracker.ui.components.AppStatusText
import com.habittracker.ui.components.AppSupportText
import com.habittracker.ui.components.AppTextField

@Composable
fun StockOrderScreen(viewModel: StockViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var showConfirmation by remember { mutableStateOf(false) }
    StockStatusDialog(uiState, viewModel::clearStatusMessage)

    LaunchedEffect(uiState.isConfigSaved) {
        if (uiState.isConfigSaved) viewModel.loadReferenceStocks()
    }

    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = { Text("실전 ${uiState.orderSide.label} 주문 확인") },
            text = {
                Text(
                    "${uiState.productName} (${uiState.productCode})\n" +
                        "${uiState.orderQuantity}주 · ${if (uiState.orderDivisionCode == "01") "시장가" else "${uiState.orderUnitPrice.toLongOrNull().toWon()} 지정가"}\n\n" +
                        "실제 계좌에 주문이 전송됩니다. 계속하시겠습니까?",
                )
            },
            confirmButton = {
                AppPrimaryButton(
                    text = "실전 주문 전송",
                    onClick = {
                        showConfirmation = false
                        viewModel.submitCashOrder()
                    },
                )
            },
            dismissButton = {
                AppSecondaryButton(text = "취소", onClick = { showConfirmation = false })
            },
        )
    }

    AppScreen {
        item {
            StockHeroCard(
                icon = "↕",
                eyebrow = "STOCK · ORDER",
                title = "매수·매도",
                description = "매수·매도 가능 수량과 안전 한도를 확인한 뒤 실전 주문합니다.",
            )
        }
        if (!uiState.isConfigSaved) {
            item {
                AppSectionCard {
                    StockSectionTitle("KIS 설정 필요")
                    AppSupportText("주식 홈의 KIS·안전 설정에서 실전 계좌 정보를 먼저 저장해 주세요.")
                }
            }
        }
        if (uiState.safetyConfig.globalOrderBlocked) {
            item {
                AppSectionCard {
                    StockSectionTitle("전체 주문 차단 중")
                    AppStatusText(uiState.safetyConfig.blockReason ?: "사용자 긴급 정지")
                }
            }
        }
        item {
            AppSectionCard {
                StockSectionTitle("주문 입력")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                ) {
                    KisOrderSide.values().forEach { side ->
                        AppSelectableChip(
                            label = side.label,
                            selected = uiState.orderSide == side,
                            onClick = { viewModel.selectOrderSide(side) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                val options = if (uiState.orderSide == KisOrderSide.SELL) {
                    uiState.ownedProductOptions()
                } else {
                    uiState.buyProductOptions()
                }
                StockProductDropdown(
                    label = if (uiState.orderSide == KisOrderSide.SELL) "보유 종목" else "매수 종목",
                    selectedCode = uiState.productCode,
                    options = options,
                    enabled = !uiState.isLoadingOwnedStocks && !uiState.isLoadingMarketCapStocks,
                    onSelect = { viewModel.selectOrderProduct(it.code, it.name) },
                )
                if (options.isEmpty()) {
                    AppSupportText(
                        if (uiState.orderSide == KisOrderSide.SELL) "매도 가능한 보유 종목이 없습니다." else "매수 종목 목록을 불러오는 중이거나 조회 결과가 없습니다.",
                    )
                }
                AppTextField(
                    value = uiState.productCode,
                    onValueChange = viewModel::updateOrderProductCode,
                    label = "종목코드 직접 입력 (6자리, ETN 7자리)",
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                ) {
                    AppSelectableChip(
                        label = "지정가",
                        selected = uiState.orderDivisionCode == "00",
                        onClick = { viewModel.updateOrderDivisionCode("00") },
                        modifier = Modifier.weight(1f),
                    )
                    AppSelectableChip(
                        label = "시장가",
                        selected = uiState.orderDivisionCode == "01",
                        onClick = {
                            viewModel.updateOrderDivisionCode("01")
                            viewModel.updateOrderUnitPrice("0")
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                AppTextField(
                    value = uiState.orderQuantity,
                    onValueChange = viewModel::updateOrderQuantity,
                    label = "${uiState.orderSide.label} 수량",
                    singleLine = true,
                )
                AppTextField(
                    value = uiState.orderUnitPrice,
                    onValueChange = viewModel::updateOrderUnitPrice,
                    label = if (uiState.orderDivisionCode == "01") "시장가 주문단가" else "지정가",
                    enabled = uiState.orderDivisionCode != "01",
                    singleLine = true,
                )
                if (uiState.orderDivisionCode == "01") {
                    Text(
                        "시장가 주문은 체결 가격이 주문 시점의 표시 가격과 달라질 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                AppPrimaryButton(
                    text = if (uiState.isSubmittingOrder) "주문 전송 중" else "실전 ${uiState.orderSide.label} 주문 확인",
                    onClick = { showConfirmation = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.isConfigSaved &&
                        !uiState.safetyConfig.globalOrderBlocked &&
                        !uiState.isSubmittingOrder &&
                        uiState.productCode.length in 6..7 &&
                        uiState.orderQuantity.toLongOrNull()?.let { it > 0L } == true &&
                        (if (uiState.orderDivisionCode == "01") {
                            uiState.orderUnitPrice == "0"
                        } else {
                            uiState.orderUnitPrice.toLongOrNull()?.let { it > 0L } == true
                        }),
                )
            }
        }
        item {
            AppSectionCard {
                StockSectionTitle("체결 상태")
                AppSecondaryButton(
                    text = if (uiState.isSyncingOrders) "확인 중" else "미완료 주문 체결 확인",
                    onClick = viewModel::syncOrders,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.isConfigSaved && !uiState.isSyncingOrders,
                )
                uiState.orders.take(5).forEach { order ->
                    Text(
                        "${order.productName} · ${if (order.side == KisOrderSide.BUY.name) "매수" else "매도"} " +
                            "${order.requestedQuantity}주 · ${order.status}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (uiState.orders.isEmpty()) AppSupportText("앱에서 접수한 주문이 없습니다.")
            }
        }
    }
}
