package com.habittracker.ui.stock

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.habittracker.R
import com.habittracker.data.local.entity.StockAssetSnapshotEntity
import com.habittracker.data.local.entity.StockExitRuleEntity
import com.habittracker.data.local.entity.StockTargetAllocationEntity
import com.habittracker.data.stock.KisBalanceStock
import com.habittracker.data.stock.StockExitRuleType
import com.habittracker.data.stock.StockRuleAction
import com.habittracker.data.stock.isCrashGuardOrderBlock
import com.habittracker.ui.components.AppNoticeDialog
import com.habittracker.ui.components.AppHeroCard
import com.habittracker.ui.components.AppScreen
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppSpacing
import com.habittracker.ui.components.AppStatusText
import com.habittracker.ui.components.AppSupportText
import com.habittracker.ui.components.AppTextField
import java.text.NumberFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToLong

private val StockTokenDateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@Composable
fun StockScreen(
    viewModel: StockViewModel,
    onOpenOrder: () -> Unit,
    onOpenPortfolio: () -> Unit,
    onOpenAutomation: () -> Unit,
    onOpenRebalance: () -> Unit,
    onOpenJournal: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val dashboardSummary = remember(uiState.ownedStocks, uiState.exitRules, uiState.targetAllocations) {
        buildStockHomeDashboardSummary(
            ownedStocks = uiState.ownedStocks,
            exitRules = uiState.exitRules,
            targetAllocations = uiState.targetAllocations,
        )
    }
    StockStatusDialog(uiState, viewModel::clearStatusMessage)

    LaunchedEffect(uiState.isConfigSaved) {
        if (uiState.isConfigSaved) {
            viewModel.loadOwnedStocks()
            viewModel.syncOrdersSilently()
        }
    }

    AppScreen {
        item {
            StockHeroCard(
                icon = "📈",
                eyebrow = "KIS · REAL TRADING",
                title = "주식 관리",
                description = if (uiState.isConfigSaved) {
                    uiState.accessTokenExpiredAt.toTokenStatusText()
                } else {
                    "주문 전 KIS 실전 계좌 설정이 필요합니다."
                },
                status = if (uiState.isConfigSaved) "실전 계좌 연결됨" else "계좌 설정 필요",
            )
        }
        if (uiState.safetyConfig.globalOrderBlocked) {
            item {
                StockRiskSummaryCard(
                    uiState = uiState,
                    summary = dashboardSummary,
                )
            }
        }
        item {
            StockPortfolioDashboardCard(
                uiState = uiState,
                summary = dashboardSummary,
                onRefresh = { viewModel.loadOwnedStocks(force = true) },
            )
        }
        if (uiState.assetSnapshots.isNotEmpty()) {
            item { StockAssetHistoryCard(uiState.assetSnapshots) }
        }
        if (!uiState.safetyConfig.globalOrderBlocked) {
            item {
                StockRiskSummaryCard(
                    uiState = uiState,
                    summary = dashboardSummary,
                )
            }
        }
        item { StockSectionTitle("거래·보유") }
        item {
            StockMenuCard("↕", "매수·매도", "KIS 실전 계좌로 주문하고 체결 상태를 확인합니다.", Color(0xFF0F6B73), onOpenOrder)
        }
        item {
            StockMenuCard("▦", "보유·매수 내역", "매수 주문별 수량·단가·잔여수량·수익률을 표시합니다.", Color(0xFF315C9A), onOpenPortfolio)
        }
        item { StockSectionTitle("자동화·전략") }
        item {
            StockMenuCard("🛡", "자동 매매·알림", "손절·익절·당일 상승 조건으로 알림 또는 분할 매매를 실행합니다.", Color(0xFF9A5B1A), onOpenAutomation)
        }
        item {
            StockMenuCard("⚖", "목표 비중 리밸런싱", "현재 비중과 목표 비중을 비교해 종목별 주문 수량을 계산합니다.", Color(0xFF6D4C8E), onOpenRebalance)
        }
        item { StockSectionTitle("기록·설정") }
        item {
            StockMenuCard("✎", "매매일지", "주문·체결·실현손익과 자동화 이력을 한곳에서 분석합니다.", Color(0xFF3C7158), onOpenJournal)
        }
        item {
            StockMenuCard("⚙", "KIS·안전 설정", "실전 계좌와 주문 한도·급락 차단·감시 주기를 설정합니다.", Color(0xFF665F55), onOpenSettings)
        }
    }
}

@Composable
private fun StockAssetHistoryCard(snapshots: List<StockAssetSnapshotEntity>) {
    val latest = snapshots.last()
    AppSectionCard {
        Text(
            text = "일별 주식 자산 흐름",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "${latest.snapshotDate} · 평가 ${latest.valuationAmount.toWon()} · " +
                "손익 ${latest.evaluationProfitLoss.toSignedWon()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (snapshots.size < 2) {
            AppSupportText("잔고를 다른 날짜에 다시 조회하면 일별 변화 차트가 표시됩니다.")
        } else {
            val lineColor = MaterialTheme.colorScheme.primary
            Canvas(modifier = Modifier.fillMaxWidth().height(140.dp)) {
                val minimum = snapshots.minOf(StockAssetSnapshotEntity::valuationAmount)
                val maximum = snapshots.maxOf(StockAssetSnapshotEntity::valuationAmount)
                val range = (maximum - minimum).coerceAtLeast(1L).toFloat()
                val horizontalStep = size.width / (snapshots.lastIndex.coerceAtLeast(1))
                fun point(index: Int): Offset {
                    val normalized = (snapshots[index].valuationAmount - minimum).toFloat() / range
                    return Offset(
                        x = horizontalStep * index,
                        y = size.height - (normalized * size.height),
                    )
                }
                (0 until snapshots.lastIndex).forEach { index ->
                    drawLine(
                        color = lineColor,
                        start = point(index),
                        end = point(index + 1),
                        strokeWidth = 5f,
                    )
                }
            }
            AppSupportText("최근 ${snapshots.size}일의 보유 주식 평가금액입니다. 예수금·세금·수수료는 포함하지 않습니다.")
        }
    }
}

@Composable
private fun StockPortfolioDashboardCard(
    uiState: StockUiState,
    summary: StockHomeDashboardSummary,
    onRefresh: () -> Unit,
) {
    AppSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StockDashboardSectionIcon(
                iconRes = R.drawable.ic_stock_wallet,
                contentDescription = "보유 주식 요약",
                accent = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "보유 주식 요약",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = when {
                        uiState.isLoadingOwnedStocks -> "KIS 잔고를 갱신하고 있습니다."
                        uiState.hasLoadedOwnedStocks -> "KIS 실전 계좌 잔고 기준"
                        else -> "계좌 잔고를 불러오면 요약이 표시됩니다."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StockRefreshIconButton(
                isLoading = uiState.isLoadingOwnedStocks,
                enabled = uiState.isConfigSaved && !uiState.isLoadingOwnedStocks,
                onClick = onRefresh,
            )
        }

        when {
            !uiState.isConfigSaved -> {
                AppSupportText("KIS·안전 설정에서 실전 계좌를 연결하면 평가금액과 수익률을 확인할 수 있습니다.")
            }
            !uiState.hasLoadedOwnedStocks -> {
                AppSupportText(
                    if (uiState.isLoadingOwnedStocks) {
                        "보유 종목의 수량·평균단가·현재가를 확인하고 있습니다."
                    } else {
                        "오른쪽 갱신 아이콘을 눌러 계좌 잔고를 다시 불러와 주세요."
                    },
                )
            }
            summary.positions.isEmpty() -> {
                AppSupportText("현재 계좌에 조회된 보유 주식이 없습니다.")
            }
            else -> {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = "보유 주식 평가금액",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = summary.totalValuationAmount.toWon(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "매입 ${summary.totalPurchaseAmount.toWon()} · ${summary.positions.size}종목",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val profitColor = when {
                    summary.estimatedProfit > 0L -> MaterialTheme.colorScheme.primary
                    summary.estimatedProfit < 0L -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StockDashboardMetric(
                        label = "평가손익",
                        value = summary.estimatedProfit.toSignedWon(),
                        valueColor = profitColor,
                        modifier = Modifier.weight(1f),
                    )
                    StockDashboardMetric(
                        label = "수익률",
                        value = summary.returnPercent?.toPercent() ?: "-",
                        valueColor = profitColor,
                        modifier = Modifier.weight(1f),
                    )
                }
                AppSupportText("현재가 기준 추정치이며 세금과 수수료는 반영하지 않습니다.")
            }
        }
    }
}

@Composable
private fun StockRiskSummaryCard(
    uiState: StockUiState,
    summary: StockHomeDashboardSummary,
) {
    AppSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StockDashboardSectionIcon(
                iconRes = R.drawable.ic_stock_shield,
                contentDescription = "위험 관리 요약",
                accent = MaterialTheme.colorScheme.error,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "위험 관리 요약",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "보유 비중과 손절·자동화 상태",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!uiState.isConfigSaved) {
            StockRiskBanner(
                level = StockRiskLevel.NEUTRAL,
                title = "계좌 설정이 필요합니다.",
                detail = "KIS 계좌 연결 후 보유 종목과 손절 규칙을 함께 확인할 수 있습니다.",
            )
            return@AppSectionCard
        }
        if (!uiState.hasLoadedOwnedStocks) {
            StockRiskBanner(
                level = if (uiState.safetyConfig.globalOrderBlocked) {
                    StockRiskLevel.CRITICAL
                } else {
                    StockRiskLevel.NEUTRAL
                },
                title = if (uiState.safetyConfig.globalOrderBlocked) {
                    "전체 주문이 차단되어 있습니다."
                } else {
                    "잔고 확인이 필요합니다."
                },
                detail = if (uiState.safetyConfig.globalOrderBlocked) {
                    if (uiState.safetyConfig.isCrashGuardOrderBlock()) {
                        "급락 안전장치가 발동했습니다. 일반 주문은 중단되고 확인된 긴급 매도만 허용됩니다."
                    } else {
                        uiState.safetyConfig.blockReason ?: "차단 해제 전까지 모든 주식 주문이 중단됩니다."
                    }
                } else {
                    "보유 주식 요약의 갱신 버튼으로 잔고를 불러오면 위험 상태를 계산합니다."
                },
            )
            return@AppSectionCard
        }

        val monitoringStopped = summary.activeRuleCount > 0 && !uiState.safetyConfig.monitoringEnabled
        val automaticOrderStopped =
            summary.activeAutoSellRuleCount > 0 && !uiState.safetyConfig.automaticOrderEnabled
        val riskLevel: StockRiskLevel
        val riskTitle: String
        val riskDetail: String
        when {
            uiState.safetyConfig.globalOrderBlocked -> {
                riskLevel = StockRiskLevel.CRITICAL
                riskTitle = "전체 주문이 차단되어 있습니다."
                riskDetail = if (uiState.safetyConfig.isCrashGuardOrderBlock()) {
                    "급락 안전장치가 발동했습니다. 일반 주문은 중단되고 확인된 긴급 매도만 허용됩니다."
                } else {
                    uiState.safetyConfig.blockReason ?: "차단 해제 전까지 모든 주식 주문이 중단됩니다."
                }
            }
            monitoringStopped -> {
                riskLevel = StockRiskLevel.CAUTION
                riskTitle = "자동화 모니터링이 중지되어 있습니다."
                riskDetail = "활성 규칙 ${summary.activeRuleCount}개가 현재 가격을 감시하지 않습니다."
            }
            automaticOrderStopped -> {
                riskLevel = StockRiskLevel.CAUTION
                riskTitle = "자동 주문이 꺼져 있습니다."
                riskDetail = "자동 매매 규칙 ${summary.activeAutoSellRuleCount}개가 발동해도 주문하지 않습니다."
            }
            summary.positions.isEmpty() -> {
                riskLevel = StockRiskLevel.NEUTRAL
                riskTitle = "위험을 계산할 보유 주식이 없습니다."
                riskDetail = "보유 종목이 생기면 비중과 손절 설정 상태를 표시합니다."
            }
            summary.unprotectedPositionNames.isNotEmpty() -> {
                riskLevel = StockRiskLevel.CAUTION
                riskTitle = "손절 규칙이 없는 종목이 있습니다."
                riskDetail = summary.unprotectedPositionNames.toCompactStockNames()
            }
            summary.overTargetPositionNames.isNotEmpty() -> {
                riskLevel = StockRiskLevel.CAUTION
                riskTitle = "목표 비중을 초과한 종목이 있습니다."
                riskDetail = summary.overTargetPositionNames.toCompactStockNames(prefix = "초과")
            }
            else -> {
                riskLevel = StockRiskLevel.SAFE
                riskTitle = "모든 보유 종목에 손절 규칙이 있습니다."
                riskDetail = "모니터링과 자동 주문 상태를 함께 확인해 주세요."
            }
        }
        StockRiskBanner(level = riskLevel, title = riskTitle, detail = riskDetail)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StockDashboardMetric(
                label = "손절 미설정",
                value = "${summary.unprotectedPositionNames.size}종목",
                valueColor = if (summary.unprotectedPositionNames.isEmpty()) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.weight(1f),
            )
            StockDashboardMetric(
                label = "목표 초과",
                value = "${summary.overTargetPositionNames.size}종목",
                valueColor = if (summary.overTargetPositionNames.isEmpty()) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.weight(1f),
            )
            StockDashboardMetric(
                label = "활성 규칙",
                value = "${summary.activeRuleCount}개",
                valueColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "1차 자동 손절 예상 손실",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (summary.firstStopCoveredPositionCount > 0) {
                        "${summary.firstStopCoveredPositionCount}종목의 가장 가까운 자동 손절 기준"
                    } else {
                        "활성 자동 손절 규칙 없음"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = summary.firstStopEstimatedLoss.toEstimatedLossText(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if ((summary.firstStopEstimatedLoss ?: 0L) > 0L) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }

        if (summary.positions.isNotEmpty()) {
            Text(
                text = "보유 비중 상위",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            summary.positions.take(3).forEach { position ->
                StockPositionShareRow(position)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StockSafetyIndicator(
                label = "모니터링",
                enabled = uiState.safetyConfig.monitoringEnabled,
                modifier = Modifier.weight(1f),
            )
            StockSafetyIndicator(
                label = "자동주문",
                enabled = uiState.safetyConfig.automaticOrderEnabled,
                modifier = Modifier.weight(1f),
            )
            StockSafetyIndicator(
                label = "급락차단",
                enabled = uiState.safetyConfig.crashGuardEnabled,
                modifier = Modifier.weight(1f),
            )
        }
        AppSupportText("상세 규칙과 안전 설정은 자동 매매·알림 및 KIS·안전 설정에서 변경할 수 있습니다.")
    }
}

@Composable
private fun StockDashboardSectionIcon(
    iconRes: Int,
    contentDescription: String,
    accent: Color,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(accent.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
            tint = accent,
        )
    }
}

@Composable
private fun StockRefreshIconButton(
    isLoading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(38.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.primary,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_stock_refresh),
                    contentDescription = "보유 잔고 갱신",
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

@Composable
private fun StockDashboardMetric(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = valueColor,
        )
    }
}

@Composable
private fun StockRiskBanner(
    level: StockRiskLevel,
    title: String,
    detail: String,
) {
    val containerColor = when (level) {
        StockRiskLevel.SAFE -> MaterialTheme.colorScheme.primaryContainer
        StockRiskLevel.CAUTION -> MaterialTheme.colorScheme.secondaryContainer
        StockRiskLevel.CRITICAL -> MaterialTheme.colorScheme.errorContainer
        StockRiskLevel.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (level) {
        StockRiskLevel.SAFE -> MaterialTheme.colorScheme.onPrimaryContainer
        StockRiskLevel.CAUTION -> MaterialTheme.colorScheme.onSecondaryContainer
        StockRiskLevel.CRITICAL -> MaterialTheme.colorScheme.onErrorContainer
        StockRiskLevel.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(containerColor)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = contentColor,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor,
        )
    }
}

@Composable
private fun StockPositionShareRow(position: StockPositionSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = position.productName,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (position.targetPercent != null) {
                    String.format(
                        Locale.KOREA,
                        "%.1f%% / 목표 %.1f%%",
                        position.sharePercent,
                        position.targetPercent,
                    )
                } else {
                    String.format(Locale.KOREA, "%.1f%%", position.sharePercent)
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (position.isOverTarget) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (position.sharePercent > 0.0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((position.sharePercent / 100.0).toFloat().coerceIn(0f, 1f))
                        .height(6.dp)
                        .background(
                            if (position.isOverTarget) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            RoundedCornerShape(99.dp),
                        ),
                )
            }
        }
    }
}

@Composable
private fun StockSafetyIndicator(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (enabled) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (enabled) "사용" else "중지",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun StockMenuCard(
    icon: String,
    title: String,
    description: String,
    accent: Color,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(icon, style = MaterialTheme.typography.titleMedium, color = accent)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = accent, fontWeight = FontWeight.Light)
        }
    }
}

@Composable
internal fun StockHeroCard(
    title: String,
    description: String,
    icon: String,
    eyebrow: String,
    status: String? = null,
) {
    AppHeroCard(
        title = title,
        description = description,
        icon = icon,
        eyebrow = eyebrow,
        status = status,
    )
}

@Composable
internal fun StockStatusDialog(uiState: StockUiState, onDismiss: () -> Unit) {
    uiState.statusMessage?.let { message ->
        AppNoticeDialog(
            message = message,
            onDismiss = onDismiss,
            title = when {
                message.contains("실패") || message.contains("초과") -> "처리 실패"
                message.contains("차단") || message.contains("확인") -> "확인 필요"
                else -> "처리 결과"
            },
        )
    }
}

@Composable
internal fun StockSectionTitle(title: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private enum class StockRiskLevel {
    SAFE,
    CAUTION,
    CRITICAL,
    NEUTRAL,
}

private data class StockHomeDashboardSummary(
    val positions: List<StockPositionSummary>,
    val totalPurchaseAmount: Long,
    val totalValuationAmount: Long,
    val estimatedProfit: Long,
    val returnPercent: Double?,
    val unprotectedPositionNames: List<String>,
    val overTargetPositionNames: List<String>,
    val activeRuleCount: Int,
    val activeAutoSellRuleCount: Int,
    val firstStopEstimatedLoss: Long?,
    val firstStopCoveredPositionCount: Int,
)

private data class StockPositionSummary(
    val productCode: String,
    val productName: String,
    val quantity: Long,
    val averagePrice: Long,
    val valuationAmount: Long,
    val purchaseAmount: Long,
    val sharePercent: Double = 0.0,
    val targetPercent: Double? = null,
    val isOverTarget: Boolean = false,
)

private fun buildStockHomeDashboardSummary(
    ownedStocks: List<KisBalanceStock>,
    exitRules: List<StockExitRuleEntity>,
    targetAllocations: List<StockTargetAllocationEntity>,
): StockHomeDashboardSummary {
    val rawPositions = ownedStocks.mapNotNull { stock ->
        val quantity = stock.quantity.toStockLongOrNull()?.takeIf { it > 0L } ?: return@mapNotNull null
        val averagePrice = stock.averagePrice.toStockLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val currentPrice = stock.currentPrice.toStockLongOrNull()?.coerceAtLeast(0L) ?: 0L
        StockPositionSummary(
            productCode = stock.productCode,
            productName = stock.productName,
            quantity = quantity,
            averagePrice = averagePrice,
            valuationAmount = multiplyStockAmount(currentPrice, quantity),
            purchaseAmount = multiplyStockAmount(averagePrice, quantity),
        )
    }
    val totalPurchaseAmount = rawPositions.sumOf(StockPositionSummary::purchaseAmount)
    val totalValuationAmount = rawPositions.sumOf(StockPositionSummary::valuationAmount)
    val targetsByCode = targetAllocations
        .filter(StockTargetAllocationEntity::enabled)
        .associateBy(StockTargetAllocationEntity::productCode)
    val positions = rawPositions
        .map { position ->
            val sharePercent = if (totalValuationAmount > 0L) {
                position.valuationAmount.toDouble() / totalValuationAmount.toDouble() * 100.0
            } else {
                0.0
            }
            val targetPercent = targetsByCode[position.productCode]?.targetPercent
            position.copy(
                sharePercent = sharePercent,
                targetPercent = targetPercent,
                isOverTarget = targetPercent != null && sharePercent > targetPercent,
            )
        }
        .sortedByDescending(StockPositionSummary::valuationAmount)

    val activeRules = exitRules.filter(StockExitRuleEntity::enabled)
    val activeStopRules = activeRules.filter { it.ruleType == StockExitRuleType.STOP_LOSS.name }
    val protectedCodes = activeStopRules.map(StockExitRuleEntity::productCode).toSet()
    val unprotectedPositionNames = positions
        .filterNot { it.productCode in protectedCodes }
        .map(StockPositionSummary::productName)

    val activeAutoStopRules = activeStopRules.filter {
        it.actionMode == StockRuleAction.AUTO_SELL.name
    }
    val firstStopLosses = positions.mapNotNull { position ->
        if (position.averagePrice <= 0L) return@mapNotNull null
        val nearestRule = activeAutoStopRules
            .asSequence()
            .filter { it.productCode == position.productCode }
            .mapNotNull { rule ->
                val triggerPrice = rule.triggerPrice ?: floor(
                    position.averagePrice * (1.0 - rule.triggerValue / 100.0),
                ).toLong()
                triggerPrice.takeIf { it > 0L }?.let { it to rule }
            }
            .maxByOrNull { (triggerPrice, _) -> triggerPrice }
            ?: return@mapNotNull null
        val (triggerPrice, rule) = nearestRule
        val sellQuantity = floor(position.quantity * rule.sellQuantityPercent / 100.0)
            .toLong()
            .coerceAtLeast(1L)
            .coerceAtMost(position.quantity)
        val lossPerShare = (position.averagePrice - triggerPrice).coerceAtLeast(0L)
        multiplyStockAmount(lossPerShare, sellQuantity)
    }
    val estimatedProfit = totalValuationAmount - totalPurchaseAmount

    return StockHomeDashboardSummary(
        positions = positions,
        totalPurchaseAmount = totalPurchaseAmount,
        totalValuationAmount = totalValuationAmount,
        estimatedProfit = estimatedProfit,
        returnPercent = if (totalPurchaseAmount > 0L) {
            estimatedProfit.toDouble() / totalPurchaseAmount.toDouble() * 100.0
        } else {
            null
        },
        unprotectedPositionNames = unprotectedPositionNames,
        overTargetPositionNames = positions
            .filter(StockPositionSummary::isOverTarget)
            .map(StockPositionSummary::productName),
        activeRuleCount = activeRules.size,
        activeAutoSellRuleCount = activeRules.count {
            it.actionMode in setOf(StockRuleAction.AUTO_SELL.name, StockRuleAction.AUTO_BUY.name)
        },
        firstStopEstimatedLoss = firstStopLosses.takeIf { it.isNotEmpty() }?.sum(),
        firstStopCoveredPositionCount = firstStopLosses.size,
    )
}

private fun String.toStockLongOrNull(): Long? =
    toDoubleOrNull()?.takeIf { it.isFinite() }?.roundToLong()

private fun multiplyStockAmount(price: Long, quantity: Long): Long =
    runCatching { Math.multiplyExact(price, quantity) }.getOrDefault(0L)

private fun Long.toSignedWon(): String {
    val sign = when {
        this > 0L -> "+"
        this < 0L -> "-"
        else -> ""
    }
    val absoluteAmount = if (this < 0L) -this else this
    return "$sign${NumberFormat.getNumberInstance(Locale.KOREA).format(absoluteAmount)}원"
}

private fun Long?.toEstimatedLossText(): String = when {
    this == null -> "-"
    this <= 0L -> "0원"
    else -> "-${NumberFormat.getNumberInstance(Locale.KOREA).format(this)}원"
}

private fun List<String>.toCompactStockNames(prefix: String = "미설정"): String {
    val visibleNames = take(3).joinToString()
    val hiddenCount = size - 3
    return if (hiddenCount > 0) {
        "$prefix: $visibleNames 외 ${hiddenCount}종목"
    } else {
        "$prefix: $visibleNames"
    }
}

internal data class StockProductOption(
    val code: String,
    val name: String,
    val description: String,
)

@Composable
internal fun StockProductDropdown(
    label: String,
    selectedCode: String,
    options: List<StockProductOption>,
    enabled: Boolean = true,
    onSelect: (StockProductOption) -> Unit,
) {
    var expanded by remember(options, selectedCode) { mutableStateOf(false) }
    val selected = options.firstOrNull { it.code == selectedCode }
    Box(modifier = Modifier.fillMaxWidth()) {
        AppTextField(
            value = selected?.let { "${it.name} (${it.code})" }.orEmpty(),
            onValueChange = {},
            label = label,
            readOnly = true,
            enabled = enabled && options.isNotEmpty(),
            singleLine = true,
            trailingOverlay = {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable(enabled = enabled && options.isNotEmpty()) { expanded = true },
                )
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        androidx.compose.foundation.layout.Column(
                            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                        ) {
                            Text("${option.name} (${option.code})")
                            if (option.description.isNotBlank()) {
                                Text(
                                    option.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

internal fun StockUiState.ownedProductOptions(): List<StockProductOption> = ownedStocks.map { stock ->
    StockProductOption(
        code = stock.productCode,
        name = stock.productName,
        description = "${stock.quantity}주 · 평균단가 ${stock.averagePrice.toWon()}",
    )
}

internal fun StockUiState.buyProductOptions(): List<StockProductOption> = marketCapStocks.map { stock ->
    StockProductOption(
        code = stock.productCode,
        name = stock.productName,
        description = "시가총액 ${stock.rank}위",
    )
}

internal fun StockUiState.allProductOptions(): List<StockProductOption> =
    (ownedProductOptions() + buyProductOptions()).distinctBy(StockProductOption::code)

internal fun Long?.toWon(): String = this?.let { "${NumberFormat.getNumberInstance(Locale.KOREA).format(it)}원" } ?: "-"

internal fun String.toWon(): String {
    val amount = toBigDecimalOrNull() ?: return "-"
    val formatter = NumberFormat.getNumberInstance(Locale.KOREA).apply {
        maximumFractionDigits = 4
    }
    return "${formatter.format(amount)}원"
}

internal fun Double.toPercent(): String = String.format(Locale.KOREA, "%+.2f%%", this)

private fun LocalDateTime?.toTokenStatusText(): String = when (this) {
    null -> "토큰 미발급"
    else -> "토큰 만료 ${format(StockTokenDateTimeFormatter)}"
}
