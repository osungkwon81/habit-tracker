package com.habittracker.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.habittracker.R
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import com.habittracker.ui.components.AppPrimaryButton

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
    onOpenLotto: () -> Unit,
    onOpenPlant: () -> Unit,
    onOpenCard: () -> Unit,
) {
    // 화면이 STARTED 이상일 때만 Flow를 수집해 백그라운드의 불필요한 작업을 막는다.
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val monthDays = remember(uiState.currentMonth) { buildCalendarDays(uiState.currentMonth) }
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
                iconRes = R.drawable.ic_launcher_art_v3,
                eyebrow = "HABIT · HOME",
                status = "${uiState.currentMonth.year}년 ${uiState.currentMonth.monthValue}월",
                action = {
                    AppPrimaryButton(text = "오늘 기록하기", onClick = { onOpenRecord(today) }, modifier = Modifier.fillMaxWidth())
                },
            )
        }
        item {
            CalendarSection(
                month = uiState.currentMonth,
                onPrevious = viewModel::goToPreviousMonth,
                onNext = viewModel::goToNextMonth,
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
                recordDetails = uiState.selectedRecordDetails.takeIf { uiState.selectedDate == selectedDate }.orEmpty(),
            )
        }
        item {
            WorkspaceSection(
                onOpenRecord = { onOpenRecord(selectedDate ?: today) },
                onOpenDiary = onOpenDiary,
                onOpenMemo = onOpenMemo,
                onOpenLotto = onOpenLotto,
                onOpenPlant = onOpenPlant,
                onOpenCard = onOpenCard,
            )
        }
    }
}

@Composable
private fun WorkspaceSection(
    onOpenRecord: () -> Unit,
    onOpenDiary: () -> Unit,
    onOpenMemo: () -> Unit,
    onOpenLotto: () -> Unit,
    onOpenPlant: () -> Unit,
    onOpenCard: () -> Unit,
) {
    AppSectionCard {
        AppSectionHeader(
            title = "바로가기",
        )
        val actions = listOf(
            HomeQuickAction("기록", "선택한 날짜 기록", onOpenRecord),
            HomeQuickAction("일기", "사진과 하루 기록", onOpenDiary),
            HomeQuickAction("메모", "빠른 메모·잠금", onOpenMemo),
            HomeQuickAction("카드 이력", "사용·결제액", onOpenCard),
            HomeQuickAction("화분", "물주기 일정", onOpenPlant),
            HomeQuickAction("동행복권", "로또·연금복권", onOpenLotto),
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
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = action.onClick)
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = action.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = action.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CalendarSection(
    month: YearMonth,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    days: List<LocalDate?>,
    summaries: Map<LocalDate, RecordSummaryRow>,
    diarySummaries: Map<LocalDate, DiarySummaryRow>,
    selectedDate: LocalDate?,
    today: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
) {
    AppSectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onPrevious, modifier = Modifier.semantics { contentDescription = "이전 달" }) { Text("‹") }
            Text("${month.year}년 ${month.monthValue}월", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onNext, modifier = Modifier.semantics { contentDescription = "다음 달" }) { Text("›") }
        }
        Text("● 기록  ·  ━ 일기", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                horizontalArrangement = Arrangement.spacedBy(0.dp),
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
    val backgroundColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent

    Box(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 56.dp)
            .clip(MaterialTheme.shapes.small)
            .background(backgroundColor)
            .border(
                width = 1.dp,
                color = when {
                    isSelected -> MaterialTheme.colorScheme.primary
                    isToday -> MaterialTheme.colorScheme.primary
                    else -> Color.Transparent
                },
                shape = MaterialTheme.shapes.small,
            )
            .let { base -> if (date != null) base.selectable(selected = isSelected, role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) {
                    contentDescription = "${date.year}년 ${date.monthValue}월 ${date.dayOfMonth}일"
                    stateDescription = listOfNotNull(if (isToday) "오늘" else null, if (summary?.isHoliday == true) "휴일" else null, if (summary != null) "기록 있음" else null, if (hasDiary) "일기 있음" else null).joinToString(", ")
                } else base }
            .padding(vertical = 4.dp),
    ) {
        if (date != null) {
            Column(
                modifier = Modifier.fillMaxWidth(),
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
                Text(
                    text = (if (summary != null) "●" else " ") + (if (hasDiary) "━" else " "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
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
