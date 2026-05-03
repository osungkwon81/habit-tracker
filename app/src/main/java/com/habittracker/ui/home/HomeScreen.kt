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
import com.habittracker.ui.components.AppScreen
import com.habittracker.ui.components.AppSecondaryButton
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppSectionHeader
import com.habittracker.ui.components.AppSpacing
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToInt

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
    onOpenStats: () -> Unit,
    onOpenAdmin: () -> Unit,
    onOpenLotto: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val monthDays = buildCalendarDays(uiState.currentMonth)
    val recordedDates = remember(uiState.summaries, uiState.diarySummaries) {
        (uiState.summaries.keys + uiState.diarySummaries.keys).toSet().sortedDescending()
    }
    val totalRecordDays = uiState.summaries.size
    val totalDiaryDays = uiState.diarySummaries.size
    val completionRate = remember(uiState.summaries) {
        val totalItems = uiState.summaries.values.sumOf(RecordSummaryRow::itemCount)
        val completedItems = uiState.summaries.values.sumOf(RecordSummaryRow::completedCount)
        if (totalItems == 0) 0 else ((completedItems.toDouble() / totalItems.toDouble()) * 100).roundToInt()
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
            InsightSection(
                currentMonth = uiState.currentMonth,
                totalRecordDays = totalRecordDays,
                totalDiaryDays = totalDiaryDays,
                completionRate = completionRate,
                selectedDate = selectedDate,
                onPreviousMonth = viewModel::goToPreviousMonth,
                onNextMonth = viewModel::goToNextMonth,
            )
        }
        item {
            WorkspaceSection(
                onOpenRecord = { onOpenRecord(selectedDate ?: today) },
                onOpenDiary = onOpenDiary,
                onOpenMemo = onOpenMemo,
                onOpenStats = onOpenStats,
                onOpenAdmin = onOpenAdmin,
                onOpenLotto = onOpenLotto,
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
private fun InsightSection(
    currentMonth: YearMonth,
    totalRecordDays: Int,
    totalDiaryDays: Int,
    completionRate: Int,
    selectedDate: LocalDate?,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
) {
    AppSectionCard {
        AppSectionHeader(
            title = "이번 달",
            subtitle = "${currentMonth.year}년 ${currentMonth.monthValue}월",
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
        ) {
            InsightCard(title = "기록일", value = "${totalRecordDays}일", modifier = Modifier.weight(1f))
            InsightCard(title = "일기", value = "${totalDiaryDays}개", modifier = Modifier.weight(1f))
            InsightCard(title = "완료율", value = "$completionRate%", modifier = Modifier.weight(1f))
        }
        selectedDate?.let {
            AppSupportLine("${it.monthValue}월 ${it.dayOfMonth}일 선택")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
        ) {
            AppSecondaryButton(text = "이전 달", onClick = onPreviousMonth, modifier = Modifier.weight(1f))
            AppSecondaryButton(text = "다음 달", onClick = onNextMonth, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun WorkspaceSection(
    onOpenRecord: () -> Unit,
    onOpenDiary: () -> Unit,
    onOpenMemo: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenAdmin: () -> Unit,
    onOpenLotto: () -> Unit,
) {
    AppSectionCard {
        AppSectionHeader(
            title = "기능",
        )
        val actions = listOf(
            HomeQuickAction("기록", "오늘 루틴 빠르게 입력", onOpenRecord),
            HomeQuickAction("메모", "빠른 노트와 잠금 메모", onOpenMemo),
            HomeQuickAction("일기", "사진과 하루 요약", onOpenDiary),
            HomeQuickAction("통계", "건강 흐름 확인", onOpenStats),
            HomeQuickAction("관리", "항목과 템플릿 정리", onOpenAdmin),
            HomeQuickAction("로또", "생성 번호와 이력", onOpenLotto),
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
    val title: String,
    val subtitle: String,
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
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = action.onClick)
            .padding(AppSpacing.sm),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
        ) {
            Text(
                text = action.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = action.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InsightCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(AppSpacing.sm),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun AppSupportLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
