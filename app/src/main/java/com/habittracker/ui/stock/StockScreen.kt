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
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

private val StockTokenDateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val StockAssetChartDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM.dd")

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
                title = "주식",
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
        item {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                StockSectionTitle("거래·보유")
                StockMenuCard("매수·매도", "실전 주문과 체결 상태", onOpenOrder)
                StockMenuCard("보유·매수 내역", "종목별 보유와 매수 건별 수익률", onOpenPortfolio)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                StockSectionTitle("자동화·전략")
                StockMenuCard("자동 매매·알림", "손절·익절·분할 매매 설정", onOpenAutomation)
                StockMenuCard("목표 비중 리밸런싱", "현재 비중과 목표 비중 비교", onOpenRebalance)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                StockSectionTitle("기록·설정")
                StockMenuCard("매매일지", "주문·체결·실현손익 기록", onOpenJournal)
                StockMenuCard("KIS·안전 설정", "계좌 연결과 주문 안전 한도", onOpenSettings)
            }
        }
    }
}

@Composable
private fun StockAssetHistoryCard(snapshots: List<StockAssetSnapshotEntity>) {
    val orderedSnapshots = remember(snapshots) { snapshots.sortedBy(StockAssetSnapshotEntity::snapshotDate) }
    val first = orderedSnapshots.first()
    val latest = orderedSnapshots.last()
    val valuationChange = latest.valuationAmount - first.valuationAmount
    val valuationChangePercent = if (first.valuationAmount > 0L) {
        valuationChange.toDouble() / first.valuationAmount.toDouble() * 100.0
    } else {
        null
    }
    val periodDays = ChronoUnit.DAYS.between(first.snapshotDate, latest.snapshotDate).coerceAtLeast(1L)
    val middleDate = first.snapshotDate.plusDays(periodDays / 2L)
    var selectedSnapshotDate by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedIndex = orderedSnapshots.indexOfFirst { it.snapshotDate.toString() == selectedSnapshotDate }
        .takeIf { it >= 0 } ?: orderedSnapshots.lastIndex
    val selectedSnapshot = orderedSnapshots[selectedIndex]
    val chartHeight = (160 * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
    AppSectionCard {
        Text(
            text = "일별 주식 자산 흐름",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "${first.snapshotDate} ~ ${latest.snapshotDate}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = latest.valuationAmount.toWon(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        if (orderedSnapshots.size < 2) {
            AppSupportText("잔고를 다른 날짜에 다시 조회하면 일별 변화 차트가 표시됩니다.")
        } else {
            val valuationColor = MaterialTheme.colorScheme.primary
            val purchaseColor = MaterialTheme.colorScheme.onSurfaceVariant
            val gridColor = MaterialTheme.colorScheme.outlineVariant
            val markerSurface = MaterialTheme.colorScheme.surface
            val minimum = orderedSnapshots.minOf { snapshot ->
                minOf(snapshot.purchaseAmount, snapshot.valuationAmount)
            }.toDouble()
            val maximum = orderedSnapshots.maxOf { snapshot ->
                maxOf(snapshot.purchaseAmount, snapshot.valuationAmount)
            }.toDouble()
            val domainPadding = ((maximum - minimum) * 0.1)
                .coerceAtLeast((maximum * 0.02).coerceAtLeast(1.0))
            val chartMinimum = (minimum - domainPadding).coerceAtLeast(0.0)
            val chartMaximum = maximum + domainPadding
            val chartRange = (chartMaximum - chartMinimum).coerceAtLeast(1.0)
            val chartMiddle = (chartMinimum + chartMaximum) / 2.0

            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                StockAssetChartLegendItem(color = valuationColor, label = "평가금액")
                StockAssetChartLegendItem(color = purchaseColor, label = "매입금액", dashed = true)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.TextButton(
                    onClick = { selectedSnapshotDate = orderedSnapshots[selectedIndex - 1].snapshotDate.toString() },
                    enabled = selectedIndex > 0,
                ) { Text("이전 기록") }
                Text(selectedSnapshot.snapshotDate.toString(), modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge)
                androidx.compose.material3.TextButton(
                    onClick = { selectedSnapshotDate = orderedSnapshots[selectedIndex + 1].snapshotDate.toString() },
                    enabled = selectedIndex < orderedSnapshots.lastIndex,
                ) { Text("다음 기록") }
            }
            Text(
                "평가 ${selectedSnapshot.valuationAmount.toWon()} · 매입 ${selectedSnapshot.purchaseAmount.toWon()}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
            ) {
                Column(
                    modifier = Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max).height(chartHeight),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(chartMaximum.roundToLong().toCompactWon(), style = MaterialTheme.typography.labelSmall)
                    Text(chartMiddle.roundToLong().toCompactWon(), style = MaterialTheme.typography.labelSmall)
                    Text(chartMinimum.roundToLong().toCompactWon(), style = MaterialTheme.typography.labelSmall)
                }
                Canvas(modifier = Modifier.weight(1f).height(chartHeight)
                    .semantics {
                        contentDescription = "주식 자산 추이. ${selectedSnapshot.snapshotDate}, 평가 ${selectedSnapshot.valuationAmount.toWon()}, 매입 ${selectedSnapshot.purchaseAmount.toWon()}. 이전 기록과 다음 기록 버튼으로 날짜를 선택할 수 있습니다."
                    }
                    .pointerInput(orderedSnapshots) {
                        detectTapGestures { touch ->
                            val inset = 6.dp.toPx()
                            val plotWidth = (size.width - inset * 2).coerceAtLeast(1f)
                            val fraction = ((touch.x - inset) / plotWidth).coerceIn(0f, 1f)
                            val nearest = orderedSnapshots.minByOrNull {
                                abs(ChronoUnit.DAYS.between(first.snapshotDate, it.snapshotDate).toDouble() / periodDays - fraction)
                            }
                            selectedSnapshotDate = nearest?.snapshotDate?.toString()
                        }
                    }) {
                    val inset = 6.dp.toPx()
                    val plotWidth = (size.width - inset * 2).coerceAtLeast(1f)
                    val plotHeight = (size.height - inset * 2).coerceAtLeast(1f)
                    listOf(0f, 0.5f, 1f).forEach { ratio ->
                        val y = inset + plotHeight * ratio
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    fun point(index: Int, amount: Long): Offset {
                        val normalized = ((amount.toDouble() - chartMinimum) / chartRange).toFloat()
                            .coerceIn(0f, 1f)
                        val elapsedDays = ChronoUnit.DAYS.between(
                            first.snapshotDate,
                            orderedSnapshots[index].snapshotDate,
                        )
                        return Offset(
                            x = inset + plotWidth * (elapsedDays.toFloat() / periodDays.toFloat()),
                            y = inset + plotHeight - (normalized * plotHeight),
                        )
                    }
                    val purchasePath = Path()
                    val valuationPath = Path()
                    orderedSnapshots.forEachIndexed { index, snapshot ->
                        val purchasePoint = point(index, snapshot.purchaseAmount)
                        val valuationPoint = point(index, snapshot.valuationAmount)
                        if (index == 0) {
                            purchasePath.moveTo(purchasePoint.x, purchasePoint.y)
                            valuationPath.moveTo(valuationPoint.x, valuationPoint.y)
                        } else {
                            purchasePath.lineTo(purchasePoint.x, purchasePoint.y)
                            valuationPath.lineTo(valuationPoint.x, valuationPoint.y)
                        }
                    }
                    drawPath(purchasePath, purchaseColor, style = Stroke(width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
                    drawPath(valuationPath, valuationColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                    val selection = point(selectedIndex, selectedSnapshot.valuationAmount)
                    drawLine(gridColor, Offset(selection.x, inset), Offset(selection.x, inset + plotHeight),
                        strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
                    listOf(0, orderedSnapshots.lastIndex, selectedIndex).distinct().forEach { index ->
                        val radius = if (index == selectedIndex) 4.dp.toPx() else 2.5.dp.toPx()
                        drawCircle(
                            color = markerSurface,
                            radius = radius + 2.dp.toPx(),
                            center = point(index, orderedSnapshots[index].valuationAmount),
                        )
                        drawCircle(
                            color = purchaseColor,
                            radius = radius,
                            center = point(index, orderedSnapshots[index].purchaseAmount),
                        )
                        drawCircle(
                            color = valuationColor,
                            radius = radius,
                            center = point(index, orderedSnapshots[index].valuationAmount),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(first.snapshotDate.toStockChartDate(), style = MaterialTheme.typography.labelSmall)
                Text(middleDate.toStockChartDate(), style = MaterialTheme.typography.labelSmall)
                Text(latest.snapshotDate.toStockChartDate(), style = MaterialTheme.typography.labelSmall)
            }
            Text(
                text = buildString {
                    append("기간 평가금액 변화 ${valuationChange.toSignedWon()}")
                    valuationChangePercent?.let { append(" (${it.toPercent()})") }
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = when {
                    valuationChange > 0L -> MaterialTheme.colorScheme.primary
                    valuationChange < 0L -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            AppSupportText("그래프를 누르거나 이전·다음 기록으로 날짜별 금액을 확인하세요.")
            Text(
                text = "현재 평가손익 ${latest.evaluationProfitLoss.toSignedWon()} · " +
                    "누적 실현손익 ${latest.realizedProfitLoss.toSignedWon()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AppSupportText("최근 ${orderedSnapshots.size}일의 평가금액과 매입금액입니다. 예수금·세금·수수료는 포함하지 않습니다.")
        }
    }
}

@Composable
private fun StockAssetChartLegendItem(color: Color, label: String, dashed: Boolean = false) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(modifier = Modifier.width(24.dp).height(10.dp)) {
            drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null)
        }
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StockPortfolioDashboardCard(
    uiState: StockUiState,
    summary: StockHomeDashboardSummary,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = AppSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "내 주식 자산",
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
                        style = MaterialTheme.typography.displaySmall,
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
                Text(
                    text = "평가손익 ${summary.estimatedProfit.toSignedWon()} (${summary.returnPercent?.toPercent() ?: "-"})",
                    style = MaterialTheme.typography.titleMedium,
                    color = profitColor,
                )
                androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    com.habittracker.ui.components.AppNavigationRow(
        title = title,
        subtitle = description,
        onClick = onClick,
    )
}

@Composable
internal fun StockHeroCard(
    title: String,
    description: String,
    status: String? = null,
) {
    AppHeroCard(
        title = title,
        description = description,
        status = status,
    )
}

@Composable
internal fun StockStatusDialog(uiState: StockUiState, onDismiss: () -> Unit) {
    com.habittracker.ui.components.AppActionNotice(uiState.statusMessage, onDismiss)
}

@Composable
internal fun StockSectionTitle(title: String) {
    com.habittracker.ui.components.AppSectionHeader(title = title)
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

private fun Long.toCompactWon(): String {
    val sign = if (this < 0L) "-" else ""
    val absoluteAmount = abs(this)
    return when {
        absoluteAmount >= 100_000_000L ->
            "$sign${String.format(Locale.KOREA, "%.1f", absoluteAmount / 100_000_000.0)}억"
        absoluteAmount >= 10_000L ->
            "$sign${NumberFormat.getNumberInstance(Locale.KOREA).format(absoluteAmount / 10_000L)}만"
        else -> "$sign${NumberFormat.getNumberInstance(Locale.KOREA).format(absoluteAmount)}원"
    }
}

private fun LocalDate.toStockChartDate(): String = format(StockAssetChartDateFormatter)

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
