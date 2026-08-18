package com.habittracker.ui.card

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.habittracker.R
import com.habittracker.data.local.entity.CardHistoryEntity
import com.habittracker.ui.digitsOnly
import com.habittracker.ui.components.AppHeroCard
import com.habittracker.ui.components.AppSaveButton
import com.habittracker.ui.components.AppScreen
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppSectionHeader
import com.habittracker.ui.components.AppSecondaryButton
import com.habittracker.ui.components.AppStatusText
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

private val CardSeriesColors = listOf(Color(0xFF285A4B), Color(0xFFDA8B45), Color(0xFF4F7CAC))

@Composable
fun CardHistoryScreen(viewModel: CardHistoryViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var inputUseDate by remember { mutableStateOf(LocalDate.now().minusDays(1).toString()) }
    val summaryDate = inputUseDate.toLocalDateOrNull() ?: LocalDate.now().minusDays(1)
    val topSummary = remember(uiState.histories, summaryDate) {
        buildRegistrationDateSummary(uiState.histories, summaryDate)
    }

    AppScreen {
        item {
            AppHeroCard(
                title = "카드 이력",
                description = "카드 사용 내역과 월별 결제 예정액을 관리합니다.",
                iconRes = R.drawable.home_quick_card,
                eyebrow = "MONEY · CARD",
                action = {
                    CardTopSummaryContent(summary = topSummary)
                },
            )
        }
        item {
            CardPaymentSettingsCard(
                paymentDay = uiState.paymentDay,
                onSave = viewModel::updatePaymentDay,
            )
        }
        item {
            CardHistoryInputCard(
                useDate = inputUseDate,
                onUseDateChange = { inputUseDate = it },
                onSave = viewModel::saveHistory,
            )
        }
        item {
            MonthSelectorCard(
                selectedMonthLabel = "${uiState.selectedMonth.year}년 ${uiState.selectedMonth.monthValue}월",
                onPrevious = viewModel::goToPreviousMonth,
                onNext = viewModel::goToNextMonth,
            )
        }
        item {
            val chartScrollState = rememberScrollState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(chartScrollState),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(modifier = Modifier.width(336.dp).height(420.dp)) {
                    CardLineChartCard(
                        title = "최근 3개월 흐름",
                        subtitle = "전월 ${uiState.paymentDay + 1}일 ~ 당월 ${uiState.paymentDay}일",
                        series = uiState.threeMonthSeries,
                    )
                }
                Box(modifier = Modifier.width(336.dp).height(420.dp)) {
                    CardLineChartCard(
                        title = "작년 동일월 비교",
                        subtitle = "선택 월과 전년도 동일 월 비교",
                        series = uiState.yearComparisonSeries,
                    )
                }
            }
        }
        item {
            CardHistoryListCard(
                histories = uiState.recentHistories,
                selectedMonthLabel = "${uiState.selectedMonth.year}년 ${uiState.selectedMonth.monthValue}월",
                onDelete = viewModel::deleteHistory,
            )
        }
        item {
            uiState.statusMessage?.let { AppStatusText(it) }
        }
    }
}

@Composable
private fun CardTopSummaryContent(summary: CardTopSummary) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = "입력 날짜 사용 금액",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = summary.currentDate?.toString() ?: summary.averageDate.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier
                    .background(
                        color = if (summary.currentDate != null) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(99.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    text = if (summary.currentDate != null) "등록됨" else "기록 없음",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (summary.currentDate != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Text(
            text = formatWon(summary.currentAmount),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = "올해 같은 날짜 평균",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (summary.sameDayYearAverageCount > 0) {
                        "${summary.averageDate.year}년 매월 ${summary.averageDate.dayOfMonth}일 · ${summary.sameDayYearAverageCount}개월 기준"
                    } else {
                        "${summary.averageDate.year}년 매월 ${summary.averageDate.dayOfMonth}일 · 기록 없음"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = formatWon(summary.sameDayYearAverage),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun CardPaymentSettingsCard(paymentDay: Int, onSave: (String) -> Unit) {
    var input by remember(paymentDay) { mutableStateOf(paymentDay.toString()) }
    var expanded by remember { mutableStateOf(false) }
    AppSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppSectionHeader(
                title = "설정",
                subtitle = "그래프 기준 결제일",
                modifier = Modifier.weight(1f),
            )
            AppSecondaryButton(
                text = if (expanded) "접기" else "펼치기",
                onClick = { expanded = !expanded },
            )
        }
        if (expanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.digitsOnly().take(2) },
                    modifier = Modifier.weight(1f),
                    label = { Text("결제일") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                AppSecondaryButton(text = "저장", onClick = { onSave(input) })
            }
        }
        Text(
            text = "기준: 전월 ${paymentDay + 1}일 ~ 당월 ${paymentDay}일",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardHistoryInputCard(
    useDate: String,
    onUseDateChange: (String) -> Unit,
    onSave: (String, String, String, () -> Unit) -> Unit,
) {
    var amount by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = androidx.compose.material3.rememberDatePickerState(
        initialSelectedDateMillis = useDate.toLocalDateOrNull()?.toEpochMillis(),
    )

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            onUseDateChange(millis.toLocalDateString())
                        }
                        showDatePicker = false
                    },
                ) { Text("선택") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("취소") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    AppSectionCard {
        AppSectionHeader(title = "카드 사용 등록", subtitle = "결제 예정 금액과 메모")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = useDate,
                onValueChange = onUseDateChange,
                modifier = Modifier.weight(1f),
                label = { Text("사용 일자") },
                singleLine = true,
            )
            AppSecondaryButton(text = "달력", onClick = { showDatePicker = true })
        }
        OutlinedTextField(
            value = amount,
            onValueChange = { amount = it.digitsOnly() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("결제 예정 금액") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )
        if (amount.isNotBlank()) {
            Text(text = formatWon(amount.toLongOrNull() ?: 0L), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            value = memo,
            onValueChange = { memo = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("메모") },
            singleLine = true,
        )
        AppSaveButton(
            text = "카드 이력 저장",
            onClick = {
                onSave(useDate, amount, memo) {
                    amount = ""
                    memo = ""
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MonthSelectorCard(selectedMonthLabel: String, onPrevious: () -> Unit, onNext: () -> Unit) {
    AppSectionCard {
        AppSectionHeader(title = "결제월 선택")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardMonthNavigationButton(
                contentDescription = "이전 달",
                rotation = 180f,
                onClick = onPrevious,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(16.dp),
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = selectedMonthLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            CardMonthNavigationButton(
                contentDescription = "다음 달",
                onClick = onNext,
            )
        }
    }
}

@Composable
private fun CardMonthNavigationButton(
    contentDescription: String,
    rotation: Float = 0f,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = contentDescription,
                modifier = Modifier
                    .size(22.dp)
                    .rotate(rotation),
            )
        }
    }
}

@Composable
private fun CardLineChartCard(title: String, subtitle: String, series: List<CardMonthSeries>) {
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val legendVertical = title == "최근 3개월 흐름"
    AppSectionCard {
        AppSectionHeader(title = title, subtitle = subtitle)
        if (series.isEmpty()) {
            Text(text = "표시할 카드 이력이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@AppSectionCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            if (legendVertical) {
                series.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .background(CardSeriesColors[index % CardSeriesColors.size], RoundedCornerShape(99.dp))
                                    .size(width = 18.dp, height = 10.dp),
                            )
                            Text(text = item.label, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            text = formatWon(item.points.maxOfOrNull(CardChartPoint::amount) ?: 0L),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    series.forEachIndexed { index, item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .background(CardSeriesColors[index % CardSeriesColors.size], RoundedCornerShape(99.dp))
                                        .size(width = 18.dp, height = 10.dp),
                                )
                                Text(text = item.label, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                text = formatWon(item.points.maxOfOrNull(CardChartPoint::amount) ?: 0L),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Canvas(modifier = Modifier.fillMaxWidth().height(240.dp)) {
            val allPoints = series.flatMap(CardMonthSeries::points)
            val maxX = series.maxOfOrNull(CardMonthSeries::cycleLength)?.coerceAtLeast(1) ?: 1
            val maxY = allPoints.maxOfOrNull(CardChartPoint::amount)?.coerceAtLeast(1L) ?: 1L
            val leftPadding = 52.dp.toPx()
            val rightPadding = 20.dp.toPx()
            val topPadding = 24.dp.toPx()
            val bottomPadding = 34.dp.toPx()
            val chartWidth = size.width - leftPadding - rightPadding
            val chartHeight = size.height - topPadding - bottomPadding
            val midpointX = if (maxX <= 1) 1 else ((maxX - 1) / 2) + 1

            drawLine(
                color = outlineColor,
                start = Offset(leftPadding, size.height - bottomPadding),
                end = Offset(size.width - rightPadding, size.height - bottomPadding),
                strokeWidth = 2f,
            )
            repeat(5) { index ->
                val ratio = index / 4f
                val y = topPadding + chartHeight - (chartHeight * ratio)
                drawLine(
                    color = outlineColor.copy(alpha = if (index == 0) 0.7f else 0.45f),
                    start = Offset(leftPadding, y),
                    end = Offset(size.width - rightPadding, y),
                    strokeWidth = 1f,
                )
            }

            series.forEachIndexed { index, item ->
                val points = item.points.map { point ->
                    Offset(
                        x = leftPadding + (chartWidth * (point.dayIndex - 1).toFloat() / maxX.toFloat()),
                        y = topPadding + chartHeight - (chartHeight * point.amount.toFloat() / maxY.toFloat()),
                    )
                }
                if (points.isNotEmpty()) {
                    val fillPath = Path().apply {
                        moveTo(points.first().x, size.height - bottomPadding)
                        points.forEachIndexed { pointIndex, point ->
                            if (pointIndex == 0) lineTo(point.x, point.y) else lineTo(point.x, point.y)
                        }
                        lineTo(points.last().x, size.height - bottomPadding)
                        close()
                    }
                    val path = Path().apply {
                        points.forEachIndexed { pointIndex, point ->
                            if (pointIndex == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                        }
                    }
                    drawPath(
                        path = fillPath,
                        color = CardSeriesColors[index % CardSeriesColors.size].copy(alpha = 0.12f),
                        style = Fill,
                    )
                    drawPath(path = path, color = CardSeriesColors[index % CardSeriesColors.size], style = Stroke(width = 4f))
                    points.forEach { drawCircle(color = CardSeriesColors[index % CardSeriesColors.size], radius = 5f, center = it) }
                }
            }

            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.DKGRAY
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = 24f
                isAntiAlias = true
            }
            val leftPaint = android.graphics.Paint(paint).apply {
                textAlign = android.graphics.Paint.Align.RIGHT
            }
            drawIntoCanvas { canvas ->
                val xLabels = listOf(
                    1 to "시작",
                    midpointX to "${midpointX}일차",
                    maxX to "마감",
                )
                xLabels.forEach { (dayIndex, label) ->
                    val x = leftPadding + (chartWidth * (dayIndex - 1).toFloat() / maxX.toFloat())
                    canvas.nativeCanvas.drawText(label, x, size.height - 4f, paint)
                }

                listOf(1f, 0.75f, 0.5f, 0.25f, 0f).forEach { ratio ->
                    val y = topPadding + chartHeight - (chartHeight * ratio)
                    canvas.nativeCanvas.drawText(
                        formatWon((maxY * ratio).toLong()),
                        leftPadding - 8f,
                        y + 8f,
                        leftPaint,
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun CardHistoryListCard(histories: List<CardHistoryEntity>, selectedMonthLabel: String, onDelete: (Long) -> Unit) {
    AppSectionCard {
        AppSectionHeader(title = "$selectedMonthLabel 사용 내역")
        if (histories.isEmpty()) {
            Text(text = "선택한 결제월의 카드 이력이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@AppSectionCard
        }
        histories.forEach { history ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = history.useDate.toString(), fontWeight = FontWeight.Bold)
                    Text(text = formatWon(history.amount), color = MaterialTheme.colorScheme.onSurface)
                    history.memo?.takeIf(String::isNotBlank)?.let {
                        Text(text = it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                AppSecondaryButton(text = "삭제", onClick = { onDelete(history.id) })
            }
        }
    }
}

private fun formatWon(value: Long): String =
    NumberFormat.getNumberInstance(Locale.KOREA).format(value) + "원"

private fun buildRegistrationDateSummary(histories: List<CardHistoryEntity>, registrationDate: LocalDate): CardTopSummary {
    val latestByDate = histories
        .groupBy(CardHistoryEntity::useDate)
        .values
        .mapNotNull { sameDateHistories -> sameDateHistories.maxByOrNull(CardHistoryEntity::id) }

    val currentHistory = latestByDate.firstOrNull { it.useDate == registrationDate }
    val previousSameDayHistories = latestByDate.filter { history ->
        history.useDate.year == registrationDate.year &&
            history.useDate.dayOfMonth == registrationDate.dayOfMonth &&
            history.useDate.isBefore(registrationDate)
    }
    val average = if (previousSameDayHistories.isEmpty()) {
        0L
    } else {
        previousSameDayHistories.sumOf(CardHistoryEntity::amount) / previousSameDayHistories.size
    }

    return CardTopSummary(
        currentAmount = currentHistory?.amount ?: 0L,
        currentDate = currentHistory?.useDate,
        averageDate = registrationDate,
        sameDayYearAverage = average,
        sameDayYearAverageCount = previousSameDayHistories.size,
    )
}

private fun String.toLocalDateOrNull(): LocalDate? =
    runCatching { LocalDate.parse(this) }.getOrNull()

private fun LocalDate.toEpochMillis(): Long =
    atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun Long.toLocalDateString(): String =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate().toString()
