package com.habittracker.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.habittracker.data.local.ValueType
import com.habittracker.data.local.model.DiarySummaryRow
import com.habittracker.data.local.model.RecordDetailRow
import com.habittracker.data.local.model.RecordSummaryRow
import com.habittracker.ui.components.AppEmptyCard
import com.habittracker.ui.components.AppHeroCard
import com.habittracker.ui.components.AppScreen
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppSectionHeader
import com.habittracker.ui.components.AppSpacing
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

private val CalendarRecordDiaryTone = Color(0xFFF8F4EA)
private val CalendarRecordTone = Color(0xFFEAF6EE)
private val CalendarDiaryTone = Color(0xFFEDF4FF)
private val CalendarHolidayTone = Color(0xFFFFC7C7)
private val CalendarEmptyTone = Color(0xFFFFFFFF)
private val CalendarWeekendTone = Color(0xFFC62828)
private val CalendarTodayBorder = Color(0xFFD4A017)

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenRecord: (LocalDate) -> Unit,
    onOpenDiary: () -> Unit,
    onOpenMemo: () -> Unit,
    onOpenStock: () -> Unit,
    onOpenLotto: () -> Unit,
    onOpenPlant: () -> Unit,
    onOpenCard: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val monthDays = buildCalendarDays(uiState.currentMonth)
    val recordedDates = remember(uiState.summaries, uiState.diarySummaries) {
        (uiState.summaries.keys + uiState.diarySummaries.keys).toSet().sortedDescending()
    }
    val today = LocalDate.now()
    var selectedDate by remember(uiState.currentMonth) { mutableStateOf<LocalDate?>(null) }

    LaunchedEffect(recordedDates, uiState.currentMonth) {
        val selectedMonthToday = today.takeIf { YearMonth.from(it) == uiState.currentMonth }
        val monthRecordedDate = recordedDates.firstOrNull { YearMonth.from(it) == uiState.currentMonth }
        if (selectedDate == null || YearMonth.from(selectedDate) != uiState.currentMonth) {
            selectedDate = selectedMonthToday ?: monthRecordedDate
        }
    }

    LaunchedEffect(selectedDate) {
        viewModel.selectDate(selectedDate)
    }

    AppScreen {
        item {
            AppHeroCard(
                title = "생활 기록",
                description = "기록과 일정을 한곳에서 관리합니다.",
                icon = "⌂",
                eyebrow = "HABIT · HOME",
                status = "${uiState.currentMonth.year}년 ${uiState.currentMonth.monthValue}월",
            )
        }
        item {
            WorkspaceSection(
                onOpenRecord = { onOpenRecord(selectedDate ?: today) },
                onOpenDiary = onOpenDiary,
                onOpenMemo = onOpenMemo,
                onOpenStock = onOpenStock,
                onOpenLotto = onOpenLotto,
                onOpenPlant = onOpenPlant,
                onOpenCard = onOpenCard,
            )
        }
        item {
            AppSectionCard {
                AppSectionHeader(
                    title = "달력 상태",
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                ) {
                    CalendarLegendItem(label = "기록+일기", color = CalendarRecordDiaryTone, modifier = Modifier.weight(1f))
                    CalendarLegendItem(label = "기록", color = CalendarRecordTone, modifier = Modifier.weight(1f))
                    CalendarLegendItem(label = "일기", color = CalendarDiaryTone, modifier = Modifier.weight(1f))
                }
            }
        }
        item {
            CalendarSection(
                days = monthDays,
                summaries = uiState.summaries,
                diarySummaries = uiState.diarySummaries,
                selectedDate = selectedDate,
                today = today,
                onSelectDate = { date -> selectedDate = date },
            )
        }
        item {
            SelectedDateSection(
                selectedDate = selectedDate,
                summary = selectedDate?.let(uiState.summaries::get),
                diarySummary = selectedDate?.let(uiState.diarySummaries::get),
                recordDetails = uiState.selectedRecordDetails,
            )
        }
    }
}

@Composable
private fun WorkspaceSection(
    onOpenRecord: () -> Unit,
    onOpenDiary: () -> Unit,
    onOpenMemo: () -> Unit,
    onOpenStock: () -> Unit,
    onOpenLotto: () -> Unit,
    onOpenPlant: () -> Unit,
    onOpenCard: () -> Unit,
) {
    AppSectionCard {
        AppSectionHeader(
            title = "바로가기",
        )
        val actions = listOf(
            HomeQuickAction("✎", "메모", "빠른 메모·잠금", Color(0xFF6D4C8E), onOpenMemo),
            HomeQuickAction("☀", "일기", "사진과 하루 기록", Color(0xFFB36B2C), onOpenDiary),
            HomeQuickAction("↗", "주식", "KIS 실전 매매·자동화", Color(0xFF0F6B73), onOpenStock),
            HomeQuickAction("◎", "로또", "번호 생성·이력", Color(0xFF315C9A), onOpenLotto),
            HomeQuickAction("♧", "화분", "물주기 일정", Color(0xFF3C7158), onOpenPlant),
            HomeQuickAction("▤", "카드 이력", "월별 사용·결제액", Color(0xFF665F55), onOpenCard),
        )
        actions.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
            ) {
                rowItems.forEach { action ->
                    QuickActionCard(
                        action = action,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowItems.size == 1) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private data class HomeQuickAction(
    val icon: String,
    val title: String,
    val subtitle: String,
    val accent: Color,
    val onClick: () -> Unit,
)

@Composable
private fun QuickActionCard(
    action: HomeQuickAction,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, action.accent.copy(alpha = 0.22f), MaterialTheme.shapes.large)
            .clickable(onClick = action.onClick)
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(action.accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(action.icon, style = MaterialTheme.typography.titleLarge, color = action.accent)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = action.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = action.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = action.accent)
        }
    }
}

@Composable
private fun CalendarSection(
    days: List<LocalDate?>,
    summaries: Map<LocalDate, RecordSummaryRow>,
    diarySummaries: Map<LocalDate, DiarySummaryRow>,
    selectedDate: LocalDate?,
    today: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
) {
    AppSectionCard {
        AppSectionHeader(
            title = "달력",
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { index, label ->
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (index == 0 || index == 6) CalendarWeekendTone else Color.Black,
                    textAlign = TextAlign.Center,
                )
            }
        }
        days.chunked(7).forEach { week ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
            ) {
                week.forEach { date ->
                    val summary = date?.let(summaries::get)
                    val diarySummary = date?.let(diarySummaries::get)
                    CalendarDayCell(
                        date = date,
                        summary = summary,
                        hasDiary = diarySummary != null,
                        isSelected = date != null && date == selectedDate,
                        isToday = date == today,
                        onClick = { date?.let(onSelectDate) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.CalendarDayCell(
    date: LocalDate?,
    summary: RecordSummaryRow?,
    hasDiary: Boolean,
    isSelected: Boolean,
    isToday: Boolean,
    onClick: () -> Unit,
) {
    val backgroundColor = when {
        summary?.isHoliday == true -> CalendarHolidayTone
        summary != null && hasDiary -> CalendarRecordDiaryTone
        summary != null -> CalendarRecordTone
        hasDiary -> CalendarDiaryTone
        else -> CalendarEmptyTone
    }

    Box(
        modifier = Modifier
            .weight(1f)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(backgroundColor)
            .border(
                width = if (isSelected || isToday) 2.dp else 1.dp,
                color = when {
                    isSelected -> MaterialTheme.colorScheme.primary
                    isToday -> CalendarTodayBorder
                    else -> MaterialTheme.colorScheme.outlineVariant
                },
                shape = RoundedCornerShape(16.dp),
            )
            .let { base -> if (date != null) base.clickable(onClick = onClick) else base }
            .padding(8.dp),
    ) {
        if (date != null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val isWeekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
                Text(
                    text = date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (summary?.isHoliday == true || isWeekend) CalendarWeekendTone else Color.Black,
                )
            }
        }
    }
}

@Composable
private fun CalendarLegendItem(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(color)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(999.dp))
                .padding(horizontal = 8.dp, vertical = 8.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SelectedDateSection(
    selectedDate: LocalDate?,
    summary: RecordSummaryRow?,
    diarySummary: DiarySummaryRow?,
    recordDetails: List<RecordDetailRow>,
) {
    AppSectionCard {
        if (selectedDate == null) {
            AppEmptyCard("달력에서 날짜를 선택해 주세요.")
            return@AppSectionCard
        }

        Text(
            text = "${selectedDate.year}년 ${selectedDate.monthValue}월 ${selectedDate.dayOfMonth}일",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        if (summary == null && diarySummary == null) {
            AppEmptyCard("선택한 날짜에는 아직 기록이 없습니다.")
        } else {
            diarySummary?.let {
                SummaryRowCard(
                    title = "일기",
                    lines = listOf("작성된 일기가 있습니다."),
                )
            }
            summary?.let {
                SummaryRowCard(
                    title = if (it.isHoliday) "일일 기록 · 휴일" else "일일 기록",
                    lines = listOf(
                        "등록 항목 ${it.itemCount}개",
                        "완료 항목 ${it.completedCount}개",
                    ),
                )
            }

            val exerciseItems = recordDetails.filter { it.category == "운동" }
            val routineItems = recordDetails.filter { it.category != "운동" }

            if (exerciseItems.isNotEmpty()) {
                SummaryRowCard(
                    title = "운동 내역",
                    lines = exerciseItems.map(::buildRecordLine),
                )
            }
            if (routineItems.isNotEmpty()) {
                SummaryRowCard(
                    title = "기타 기록",
                    lines = routineItems.map(::buildRecordLine),
                )
            }
        }
    }
}

@Composable
private fun SummaryRowCard(
    title: String,
    lines: List<String>,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(AppSpacing.sm),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun buildRecordLine(item: RecordDetailRow): String {
    val value = when {
        item.valueType == ValueType.EXERCISE -> {
            val distance = item.numberValue?.let { "${trimTrailingZero(it)}km" }
            val duration = item.durationMinutes?.let { "${it}분" }
            listOfNotNull(distance, duration).joinToString(" / ").ifBlank { "기록됨" }
        }
        item.numberValue != null -> if (item.unit.isNullOrBlank()) item.numberValue.toInt().toString() else "${item.numberValue.toInt()} ${item.unit}"
        item.durationMinutes != null -> "${item.durationMinutes}분"
        item.booleanValue == true || item.checked -> "완료"
        !item.textValue.isNullOrBlank() -> item.textValue
        else -> item.note.orEmpty().ifBlank { "기록됨" }
    }
    return "${item.taskName}: $value"
}

private fun trimTrailingZero(value: Double): String {
    val asLong = value.toLong()
    return if (value == asLong.toDouble()) asLong.toString() else value.toString()
}

private fun buildCalendarDays(currentMonth: YearMonth): List<LocalDate?> {
    val firstDay = currentMonth.atDay(1)
    val leadingEmptyDays = firstDay.dayOfWeek.value % 7
    val days = mutableListOf<LocalDate?>()
    repeat(leadingEmptyDays) { days += null }
    for (day in 1..currentMonth.lengthOfMonth()) {
        days += currentMonth.atDay(day)
    }
    while (days.size % 7 != 0) {
        days += null
    }
    return days
}
