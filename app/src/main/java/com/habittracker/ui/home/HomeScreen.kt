package com.habittracker.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.habittracker.data.local.model.DiarySummaryRow
import com.habittracker.data.local.model.RecordDetailRow
import com.habittracker.data.local.model.RecordSummaryRow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

private val WeekendColor = Color(0xFFD94F4F)
private val RecordOnlyColor = Color(0xFFDCEBFF)
private val DiaryOnlyColor = Color(0xFFFFE9C7)
private val BothContentColor = Color(0xFFDDF4E4)
private val HolidayColor = Color(0xFFFFD9D9)

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenRecord: (LocalDate) -> Unit,
    onOpenDiary: () -> Unit,
    onOpenMemo: () -> Unit,
    onOpenLearning: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenAdmin: () -> Unit,
    onOpenLotto: () -> Unit,
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

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = "습관 트래커", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = "오늘 날짜를 강조하고, 입력된 내용은 날짜 색상으로 구분합니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            QuickMenuSection(
                onOpenDiary = onOpenDiary,
                onOpenMemo = onOpenMemo,
                onOpenRecord = { selectedDate?.let(onOpenRecord) ?: onOpenRecord(LocalDate.now()) },
                onOpenLotto = onOpenLotto,
            )
        }
        item { LearningSpotlightSection(onOpenLearning = onOpenLearning) }
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = viewModel::goToPreviousMonth) { Text("이전") }
                Text(text = "${uiState.currentMonth.year}년 ${uiState.currentMonth.monthValue}월", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                TextButton(onClick = viewModel::goToNextMonth) { Text("다음") }
            }
        }
        item { CalendarLegend() }
        item {
            CalendarGrid(
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
                onOpenRecord = onOpenRecord,
            )
        }
    }
}

@Composable
private fun LearningSpotlightSection(onOpenLearning: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenLearning),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "외국어 학습", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(text = "단어 관리, 암기 카드, 시험, 통계를 한 번에 관리합니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = "학습 시작", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CalendarLegend() {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = "달력 표시", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                LegendChip("기록", RecordOnlyColor, Modifier.weight(1f))
                LegendChip("일기", DiaryOnlyColor, Modifier.weight(1f))
                LegendChip("둘 다", BothContentColor, Modifier.weight(1f))
                LegendChip("휴일", HolidayColor, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LegendChip(label: String, color: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = color), shape = RoundedCornerShape(16.dp)) {
        Text(text = label, modifier = Modifier.padding(vertical = 10.dp), fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun QuickMenuSection(onOpenDiary: () -> Unit, onOpenMemo: () -> Unit, onOpenRecord: () -> Unit, onOpenLotto: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "빠른 메뉴", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            QuickMenuCard(title = "메모", subtitle = "메모 목록과 작성", modifier = Modifier.weight(1f), onClick = onOpenMemo)
            QuickMenuCard(title = "일기", subtitle = "사진과 날씨 기록", modifier = Modifier.weight(1f), onClick = onOpenDiary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            QuickMenuCard(title = "로또", subtitle = "번호 생성과 이력", modifier = Modifier.weight(1f), onClick = onOpenLotto)
            QuickMenuCard(title = "기록", subtitle = "선택 날짜 기록 작성", modifier = Modifier.weight(1f), onClick = onOpenRecord)
        }
    }
}

@Composable
private fun QuickMenuCard(title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(modifier = modifier.clickable(onClick = onClick), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CalendarGrid(
    days: List<LocalDate?>,
    summaries: Map<LocalDate, RecordSummaryRow>,
    diarySummaries: Map<LocalDate, DiarySummaryRow>,
    selectedDate: LocalDate?,
    today: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val labels = listOf("일", "월", "화", "수", "목", "금", "토")
            labels.forEachIndexed { index, label ->
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    color = if (index == 0 || index == 6) WeekendColor else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        days.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                week.forEach { date ->
                    val summary = date?.let(summaries::get)
                    val diarySummary = date?.let(diarySummaries::get)
                    val hasRecord = summary != null
                    val hasDiary = diarySummary != null
                    val isSelected = date != null && date == selectedDate
                    val isToday = date == today
                    val dayColor = if (date != null && (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY)) WeekendColor else MaterialTheme.colorScheme.onSurface
                    val backgroundColor = when {
                        summary?.isHoliday == true -> HolidayColor
                        hasRecord && hasDiary -> BothContentColor
                        hasRecord -> RecordOnlyColor
                        hasDiary -> DiaryOnlyColor
                        else -> MaterialTheme.colorScheme.surface
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(backgroundColor)
                            .border(width = if (isToday) 2.dp else 0.dp, color = if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent, shape = RoundedCornerShape(18.dp))
                            .border(width = if (isSelected) 2.dp else 0.dp, color = if (isSelected) MaterialTheme.colorScheme.tertiary else Color.Transparent, shape = RoundedCornerShape(18.dp))
                            .let { base -> if (date != null) base.clickable { onSelectDate(date) } else base }
                            .padding(8.dp),
                    ) {
                        if (date != null) {
                            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    text = date.dayOfMonth.toString(),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (summary?.isHoliday == true) WeekendColor else dayColor,
                                    fontWeight = FontWeight.Bold,
                                )
                                if (summary != null) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Spacer(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (summary.isHoliday) WeekendColor else MaterialTheme.colorScheme.secondary))
                                        Text(text = if (summary.isHoliday) "휴일" else "${summary.completedCount}/${summary.itemCount}", style = MaterialTheme.typography.labelSmall)
                                    }
                                } else if (diarySummary != null) {
                                    Text(text = "일기", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectedDateSection(
    selectedDate: LocalDate?,
    summary: RecordSummaryRow?,
    diarySummary: DiarySummaryRow?,
    recordDetails: List<RecordDetailRow>,
    onOpenRecord: (LocalDate) -> Unit,
) {
    val exerciseItems = recordDetails.filter { it.category == "운동" }
    val routineItems = recordDetails.filter { it.category != "운동" }

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(text = "기록 미리보기", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (selectedDate == null) {
                Text(text = "달력에서 날짜를 선택해 주세요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(text = "${selectedDate.year}년 ${selectedDate.monthValue}월 ${selectedDate.dayOfMonth}일", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (summary == null && diarySummary == null) {
                    Text(text = "선택한 날짜에는 아직 기록이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                diarySummary?.let {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(text = "일기장", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(text = "일기가 작성되어 있습니다.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                summary?.let {
                    Card(colors = CardDefaults.cardColors(containerColor = if (it.isHoliday) HolidayColor else MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f))) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(text = if (it.isHoliday) "일일 기록 및 휴일" else "일일 기록", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(text = "등록 항목")
                                Text(text = it.itemCount.toString(), fontWeight = FontWeight.Bold)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(text = "완료 항목")
                                Text(text = it.completedCount.toString(), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                if (exerciseItems.isNotEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = RecordOnlyColor)) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(text = "운동 내역", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            exerciseItems.forEach { item ->
                                Text(text = buildRecordLine(item), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                if (routineItems.isNotEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(text = "기타 기록", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            routineItems.forEach { item ->
                                Text(text = buildRecordLine(item), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                Button(onClick = { onOpenRecord(selectedDate) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(text = if (summary == null && diarySummary == null) "이 날짜 기록 작성" else "이 날짜 기록 열기")
                }
            }
        }
    }
}

private fun buildRecordLine(item: RecordDetailRow): String {
    val value = when {
        item.numberValue != null -> if (item.unit.isNullOrBlank()) item.numberValue.toInt().toString() else "${item.numberValue.toInt()} ${item.unit}"
        item.durationMinutes != null -> "${item.durationMinutes}분"
        item.booleanValue == true || item.checked -> "완료"
        !item.textValue.isNullOrBlank() -> item.textValue
        else -> item.note.orEmpty().ifBlank { "기록됨" }
    }
    return "${item.taskName}: $value"
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
