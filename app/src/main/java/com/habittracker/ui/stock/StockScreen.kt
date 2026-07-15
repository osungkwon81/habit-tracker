package com.habittracker.ui.stock

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    val uiState by viewModel.uiState.collectAsState()
    StockStatusDialog(uiState, viewModel::clearStatusMessage)

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
                AppSectionCard {
                    StockSectionTitle("전체 주문 차단 중")
                    AppStatusText(uiState.safetyConfig.blockReason ?: "사용자 긴급 정지")
                    AppSupportText("차단 해제 전까지 직접 주문, 자동 매도, 리밸런싱 주문이 모두 중단됩니다.")
                }
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
            StockMenuCard("🛡", "자동 매도·알림", "손절·익절·고점 추적 조건으로 알림 또는 매도를 실행합니다.", Color(0xFF9A5B1A), onOpenAutomation)
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
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(icon, style = MaterialTheme.typography.titleLarge, color = accent)
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
