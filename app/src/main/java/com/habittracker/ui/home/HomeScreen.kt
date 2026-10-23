package com.habittracker.ui.home

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
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
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.YearMonth
import com.habittracker.ui.navigation.AppDestination
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.lazy.items
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role

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
    onOpenDestination: (AppDestination) -> Unit,
) {
    // 화면이 STARTED 이상일 때만 Flow를 수집해 백그라운드의 불필요한 작업을 막는다.
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val monthDays = remember(uiState.currentMonth) { buildCalendarDays(uiState.currentMonth) }
    val recordedDates = remember(uiState.summaries, uiState.diarySummaries) {
        (uiState.summaries.keys + uiState.diarySummaries.keys).toSet().sortedDescending()
    }
    var menuRefresh by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshToday()
        menuRefresh++
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
                title = "홈",
                status = "${today.year}년 ${today.monthValue}월 ${today.dayOfMonth}일",
            )
        }
        item {
            WorkspaceSection(refresh = menuRefresh, onOpenDestination = onOpenDestination)
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    AppSectionHeader(title = "오늘 해야 할 일")
                }
                TextButton(onClick = { onOpenDestination(AppDestination.REMINDER) }) { Text("전체 보기") }
            }
        }
        val remindersError = uiState.remindersError
        when {
            uiState.remindersLoading -> item {
                com.habittracker.ui.components.AppLoadingCard("오늘 할 일을 불러오고 있습니다.")
            }
            remindersError != null -> item {
                com.habittracker.ui.components.AppStatusText(remindersError)
            }
            uiState.reminders.isEmpty() -> item {
                AppEmptyCard("오늘 예정된 미완료 리마인더가 없습니다.")
            }
            else -> items(uiState.reminders, key = { "reminder-${it.id}" }) { reminder ->
                androidx.compose.material3.ListItem(
                    headlineContent = { Text(reminder.title, style = MaterialTheme.typography.titleMedium) },
                    supportingContent = {
                        Text("${reminder.remindAt.format(DateTimeFormatter.ofPattern("HH:mm"))} · ${reminder.repeatIntervalMinutes}분 간격으로 알림")
                    },
                    trailingContent = { com.habittracker.ui.components.AppChevron() },
                    modifier = Modifier.clip(MaterialTheme.shapes.large)
                        .clickable { onOpenDestination(AppDestination.REMINDER) },
                )
            }
        }
        item {
            TodayRecordButton(onClick = { onOpenRecord(today) })
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
    }
}

@Composable
private fun TodayRecordButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(role = Role.Button, onClickLabel = "오늘 기록 남기기", onClick = onClick)
            .padding(AppSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("오늘 기록", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text("습관 · 운동", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Image(
            painter = painterResource(R.drawable.ill_daily_empty),
            contentDescription = null,
            modifier = Modifier.size(52.dp),
            contentScale = ContentScale.Fit,
        )
        com.habittracker.ui.components.AppChevron()
    }
}

@Composable
private fun WorkspaceSection(
    refresh: Int,
    onOpenDestination: (AppDestination) -> Unit,
) {
    val context = LocalContext.current
    val defaultOrder = remember {
        val preferred = listOf(AppDestination.STOCK, AppDestination.CARD, AppDestination.LOTTO, AppDestination.PLANT, AppDestination.MEMO)
        preferred + AppDestination.entries.filter {
            it !in preferred && it != AppDestination.HOME && it != AppDestination.MORE &&
                !AppDestination.NOTIFICATION_ASSISTANT.matches(it.route)
        }
    }
    var menuOrder by remember { mutableStateOf(defaultOrder.take(5)) }
    LaunchedEffect(context, refresh) {
        menuOrder = withContext(Dispatchers.IO) { HomeMenuUsageStore.currentOrder(context, defaultOrder).take(5) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
        val actions = menuOrder.map { destination ->
            HomeQuickAction(
                id = destination.route,
                iconRes = destination.iconRes,
                title = destination.homeLabel,
                onClick = { onOpenDestination(destination) },
            )
        }
        val columnCount = if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f) 2 else 3
        actions.chunked(columnCount).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                rowItems.forEach { action ->
                    QuickActionCard(action, modifier = Modifier.weight(1f))
                }
                if (rowItems.size < columnCount) Box(Modifier.weight(1f))
            }
        }
    }
}

private data class HomeQuickAction(
    val id: String,
    @DrawableRes val iconRes: Int,
    val title: String,
    val onClick: () -> Unit,
)

internal object HomeMenuUsageStore {
    private const val preferencesName = "home-quick-menu-usage"
    private const val dayPrefix = "screen-day-"

    fun currentOrder(context: Context, defaultOrder: List<AppDestination>): List<AppDestination> {
        val values = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).all
        val today = LocalDate.now()
        val counts = defaultOrder.associateWith { destination ->
            (0L..6L).sumOf { offset ->
                (values["$dayPrefix${today.minusDays(offset)}|${destination.route}"] as? Int ?: 0).toLong()
            }
        }
        return defaultOrder.sortedByDescending { counts.getValue(it) }
    }

    fun recordVisit(context: Context, destination: AppDestination) {
        if (destination == AppDestination.HOME || destination == AppDestination.MORE) return
        val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        val today = LocalDate.now()
        val key = "$dayPrefix$today|${destination.route}"
        val editor = preferences.edit().putInt(key, preferences.getInt(key, 0) + 1)
        val cutoff = today.minusDays(6).toString()
        preferences.all.keys.filter {
            it.startsWith(dayPrefix) && it.removePrefix(dayPrefix).substringBefore('|') < cutoff
        }.forEach(editor::remove)
        editor.apply()
    }
}

@Composable
private fun QuickActionCard(
    action: HomeQuickAction,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = action.onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
    ) {
        FeatureIcon(action = action, size = 48)
        Text(action.title, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
    }
}

@Composable
private fun FeatureIcon(action: HomeQuickAction, size: Int) {
    com.habittracker.ui.components.AppFeatureIcon(action.id, action.iconRes, Modifier.size(size.dp))
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
                    color = if (index == 0 || index == 6) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
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
                    color = if (summary?.isHoliday == true || isWeekend) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
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
            .padding(vertical = AppSpacing.xs),
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
