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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.habittracker.data.local.model.DiarySummaryRow
import com.habittracker.data.local.model.RecordDetailRow
import com.habittracker.data.local.model.RecordSummaryRow
import com.habittracker.data.local.ValueType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

private val WeekendColor = Color(0xFFBA4B48)
private val RecordOnlyColor = Color(0xFFDCEEFF)
private val DiaryOnlyColor = Color(0xFFFFEACC)
private val BothContentColor = Color(0xFFDDF3E6)
private val HolidayColor = Color(0xFFFFE0DF)
private val CalendarTextColor = Color(0xFF162025)
private val CalendarSubTextColor = Color(0xFF415861)
private val EmptyDayColor = Color(0xFFF8F3EB)
private val TodayBorderColor = Color(0xFFD4A017)
private val PreviewPrimaryTextColor = Color(0xFF13242A)
private val PreviewSecondaryTextColor = Color(0xFF35474E)
private val HeroInk = Color(0xFF10242A)
private val HeroSoft = Color(0xFFF0E7D1)
private val RecordCardColor = Color(0xFFEAF6FF)
private val DiaryCardColor = Color(0xFFFFF0D8)
private val NeutralCardColor = Color(0xFFF7F5F0)
private val BadgeMint = Color(0xFF2F6B57)
private val BadgeAmber = Color(0xFFD08A2E)
private val CardTitleColor = Color(0xFF182126)
private val CardSubtitleColor = Color(0xFF324249)
private val DateHeadlineColor = Color(0xFF152126)

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
            HomeHeroCard(
                month = uiState.currentMonth,
                selectedDate = selectedDate,
                onOpenStats = onOpenStats,
                onOpenAdmin = onOpenAdmin,
            )
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
                Text(text = "${uiState.currentMonth.year}년 ${uiState.currentMonth.monthValue}월", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = DateHeadlineColor)
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
private fun HomeHeroCard(
    month: YearMonth,
    selectedDate: LocalDate?,
    onOpenStats: () -> Unit,
    onOpenAdmin: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = HeroInk),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "오늘의 루틴 보드",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
            )
            Text(
                text = "${month.year}년 ${month.monthValue}월${selectedDate?.let { " · ${it.monthValue}/${it.dayOfMonth} 선택" } ?: ""}",
                color = HeroSoft,
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                AccentMiniCard(emoji = "📊", title = "통계", subtitle = "월간 흐름 보기", modifier = Modifier.weight(1f), onClick = onOpenStats)
                AccentMiniCard(emoji = "⚙️", title = "관리", subtitle = "항목 정리", modifier = Modifier.weight(1f), onClick = onOpenAdmin)
            }
        }
    }
}

@Composable
private fun AccentMiniCard(emoji: String, title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = Color.White.copy(alpha = 0.1f),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = emoji, style = MaterialTheme.typography.titleLarge)
            Text(text = title, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            Text(text = subtitle, color = HeroSoft, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LearningSpotlightSection(onOpenLearning: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenLearning),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFDDEEE5)),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = "🧠", style = MaterialTheme.typography.headlineMedium)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = "외국어 학습", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = CardTitleColor)
                Text(text = "단어, 암기, 시험, 통계를 한 화면 흐름으로 정리합니다.", color = CardSubtitleColor, style = MaterialTheme.typography.bodyMedium)
            }
            Text(text = "열기", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
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
        Text(
            text = label,
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            fontWeight = FontWeight.Medium,
            color = CalendarTextColor,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun QuickMenuSection(onOpenDiary: () -> Unit, onOpenMemo: () -> Unit, onOpenRecord: () -> Unit, onOpenLotto: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "바로 가기", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            QuickMenuCard(emoji = "📝", title = "메모", subtitle = "잠금 메모와 빠른 정리", modifier = Modifier.weight(1f), onClick = onOpenMemo)
            QuickMenuCard(emoji = "📔", title = "일기", subtitle = "사진과 날씨 기록", modifier = Modifier.weight(1f), onClick = onOpenDiary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            QuickMenuCard(emoji = "🎯", title = "로또", subtitle = "회차별 번호 저장", modifier = Modifier.weight(1f), onClick = onOpenLotto)
            QuickMenuCard(emoji = "✍️", title = "기록", subtitle = "오늘 루틴 입력", modifier = Modifier.weight(1f), onClick = onOpenRecord)
        }
    }
}

@Composable
private fun QuickMenuCard(emoji: String, title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(
            modifier = Modifier
                .heightIn(min = 132.dp)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = emoji, style = MaterialTheme.typography.headlineMedium)
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = CardTitleColor)
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = CardSubtitleColor)
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
                    textAlign = TextAlign.Center,
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
                    val dayColor = if (date != null && (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY)) WeekendColor else CalendarTextColor
                    val backgroundColor = when {
                        summary?.isHoliday == true -> HolidayColor
                        hasRecord && hasDiary -> BothContentColor
                        hasRecord -> RecordOnlyColor
                        hasDiary -> DiaryOnlyColor
                        else -> EmptyDayColor
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(backgroundColor)
                            .border(width = if (isToday) 2.dp else 0.dp, color = if (isToday) TodayBorderColor else Color.Transparent, shape = RoundedCornerShape(18.dp))
                            .border(width = if (isSelected) 2.dp else 0.dp, color = if (isSelected) MaterialTheme.colorScheme.tertiary else Color.Transparent, shape = RoundedCornerShape(18.dp))
                            .let { base -> if (date != null) base.clickable { onSelectDate(date) } else base }
                            .padding(8.dp),
                    ) {
                        if (date != null) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.SpaceBetween,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = date.dayOfMonth.toString(),
                                    modifier = Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (summary?.isHoliday == true) WeekendColor else dayColor,
                                    fontWeight = if (summary?.isHoliday == true || date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) FontWeight.ExtraBold else FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                )
                                if (summary != null) {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        CalendarBadge(
                                            text = if (summary.isHoliday) "휴일" else "${summary.completedCount}/${summary.itemCount}",
                                            color = if (summary.isHoliday) WeekendColor else BadgeMint,
                                        )
                                        if (hasDiary) {
                                            CalendarBadge(text = "일기", color = BadgeAmber)
                                        }
                                    }
                                } else if (diarySummary != null) {
                                    CalendarBadge(text = "일기", color = BadgeAmber)
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
private fun CalendarBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.24f), RoundedCornerShape(999.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
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

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(30.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(text = "기록 미리보기", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (selectedDate == null) {
                Text(text = "달력에서 날짜를 선택해 주세요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(text = "${selectedDate.year}년 ${selectedDate.monthValue}월 ${selectedDate.dayOfMonth}일", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = DateHeadlineColor)
                if (summary == null && diarySummary == null) {
                    Text(text = "선택한 날짜에는 아직 기록이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                diarySummary?.let {
                    Card(colors = CardDefaults.cardColors(containerColor = DiaryCardColor), shape = RoundedCornerShape(24.dp)) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(text = "📔 일기", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = PreviewPrimaryTextColor)
                            Text(text = "일기가 작성되어 있습니다.", style = MaterialTheme.typography.bodyMedium, color = PreviewPrimaryTextColor)
                        }
                    }
                }
                summary?.let {
                    Card(colors = CardDefaults.cardColors(containerColor = if (it.isHoliday) HolidayColor else RecordCardColor), shape = RoundedCornerShape(24.dp)) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(text = if (it.isHoliday) "🗓️ 일일 기록 · 휴일" else "🗓️ 일일 기록", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = PreviewPrimaryTextColor)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(text = "등록 항목", color = PreviewSecondaryTextColor)
                                Text(text = it.itemCount.toString(), fontWeight = FontWeight.Bold, color = PreviewPrimaryTextColor)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(text = "완료 항목", color = PreviewSecondaryTextColor)
                                Text(text = it.completedCount.toString(), fontWeight = FontWeight.Bold, color = PreviewPrimaryTextColor)
                            }
                        }
                    }
                }
                if (exerciseItems.isNotEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE7F3EC)), shape = RoundedCornerShape(24.dp)) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(text = "🏃 운동 내역", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = PreviewPrimaryTextColor)
                            exerciseItems.forEach { item ->
                                Text(text = buildRecordLine(item), style = MaterialTheme.typography.bodyMedium, color = PreviewPrimaryTextColor)
                            }
                        }
                    }
                }
                if (routineItems.isNotEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = NeutralCardColor), shape = RoundedCornerShape(24.dp)) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(text = "✨ 기타 기록", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = PreviewPrimaryTextColor)
                            routineItems.forEach { item ->
                                Text(text = buildRecordLine(item), style = MaterialTheme.typography.bodyMedium, color = PreviewPrimaryTextColor)
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
