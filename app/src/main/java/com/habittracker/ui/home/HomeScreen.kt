package com.habittracker.ui.home

import androidx.compose.foundation.background
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
import com.habittracker.data.local.model.RecordSummaryRow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

private val WeekendColor = Color(0xFFD94F4F)

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenRecord: (LocalDate) -> Unit,
    onOpenDiary: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenAdmin: () -> Unit,
    onOpenLotto: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val monthDays = buildCalendarDays(uiState.currentMonth)
    val recordedDates = remember(uiState.summaries, uiState.diarySummaries) {
        (uiState.summaries.keys + uiState.diarySummaries.keys).toSet().sortedDescending()
    }
    var selectedDate by remember(uiState.currentMonth) { mutableStateOf<LocalDate?>(null) }

    LaunchedEffect(recordedDates, uiState.currentMonth) {
        val monthRecordedDate = recordedDates.firstOrNull { YearMonth.from(it) == uiState.currentMonth }
        if (selectedDate == null || YearMonth.from(selectedDate) != uiState.currentMonth) {
            selectedDate = monthRecordedDate
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = "\uC2B5\uAD00 \uD2B8\uB798\uCEE4", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(text = "\uB2EC\uB825 \uB0A0\uC9DC\uB97C \uB204\uB974\uBA74 \uD574\uB2F9 \uB0A0\uC9DC \uAE30\uB85D\uC774 \uC544\uB798\uC5D0 \uD06C\uAC8C \uBCF4\uC785\uB2C8\uB2E4.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { QuickMenuSection(onOpenDiary = onOpenDiary, onOpenStats = onOpenStats, onOpenAdmin = onOpenAdmin, onOpenLotto = onOpenLotto) }
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = viewModel::goToPreviousMonth) { Text("\uC774\uC804") }
                Text(text = "${uiState.currentMonth.year}\uB144 ${uiState.currentMonth.monthValue}\uC6D4", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                TextButton(onClick = viewModel::goToNextMonth) { Text("\uB2E4\uC74C") }
            }
        }
        item {
            CalendarGrid(
                days = monthDays,
                summaries = uiState.summaries,
                diarySummaries = uiState.diarySummaries,
                selectedDate = selectedDate,
                onSelectDate = { date -> selectedDate = date },
            )
        }
        item {
            SelectedDateSection(
                selectedDate = selectedDate,
                summary = selectedDate?.let(uiState.summaries::get),
                diarySummary = selectedDate?.let(uiState.diarySummaries::get),
                onOpenRecord = onOpenRecord,
            )
        }
    }
}

@Composable
private fun QuickMenuSection(onOpenDiary: () -> Unit, onOpenStats: () -> Unit, onOpenAdmin: () -> Unit, onOpenLotto: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "\uBE60\uB978 \uBA54\uB274", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            QuickMenuCard(title = "\uC77C\uAE30\uC7A5", subtitle = "\uC0AC\uC9C4\uACFC \uB0A0\uC528\uB97C \uD568\uAED8 \uAE30\uB85D", modifier = Modifier.weight(1f), onClick = onOpenDiary)
            QuickMenuCard(title = "\uD1B5\uACC4", subtitle = "\uC6D4\uBCC4 \uBE44\uAD50 \uD655\uC778", modifier = Modifier.weight(1f), onClick = onOpenStats)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            QuickMenuCard(title = "\uAD00\uB9AC\uC790", subtitle = "\uC6B4\uB3D9 \uC885\uBAA9\uACFC \uB8E8\uD2F4 \uCD94\uAC00", modifier = Modifier.weight(1f), onClick = onOpenAdmin)
            QuickMenuCard(title = "\uB85C\uB610", subtitle = "\uCD94\uCCA8 \uBA54\uB274 \uC790\uB9AC\uB9CC \uBA3C\uC800 \uC900\uBE44", modifier = Modifier.weight(1f), onClick = onOpenLotto)
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
    onSelectDate: (LocalDate) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val labels = listOf("\uC77C", "\uC6D4", "\uD654", "\uC218", "\uBAA9", "\uAE08", "\uD1A0")
            labels.forEachIndexed { index, label ->
                Text(text = label, modifier = Modifier.weight(1f), color = if (index == 0 || index == 6) WeekendColor else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
        }
        days.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                week.forEach { date ->
                    val hasRecord = date != null && (summaries.containsKey(date) || diarySummaries.containsKey(date))
                    val isSelected = date != null && date == selectedDate
                    val dayColor = if (date != null && (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY)) WeekendColor else MaterialTheme.colorScheme.onSurface
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                when {
                                    isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
                                    hasRecord -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                    else -> MaterialTheme.colorScheme.surface
                                },
                            )
                            .let { base -> if (date != null) base.clickable { onSelectDate(date) } else base }
                            .padding(8.dp),
                    ) {
                        if (date != null) {
                            val summary = summaries[date]
                            val diarySummary = diarySummaries[date]
                            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(text = date.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium, color = dayColor, fontWeight = FontWeight.Bold)
                                    diarySummary?.let { Text(text = "${it.weather} ${it.title}", style = MaterialTheme.typography.labelSmall, maxLines = 2) }
                                }
                                if (summary != null) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Spacer(modifier = Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary))
                                        Text(text = "${summary.completedCount}/${summary.itemCount}", style = MaterialTheme.typography.labelSmall)
                                    }
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
    onOpenRecord: (LocalDate) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(text = "\uAE30\uB85D \uBBF8\uB9AC\uBCF4\uAE30", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (selectedDate == null) {
                Text(text = "\uC774 \uC6D4\uC5D0 \uD45C\uC2DC\uD560 \uAE30\uB85D\uC774 \uC544\uC9C1 \uC5C6\uC2B5\uB2C8\uB2E4.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(text = "${selectedDate.year}\uB144 ${selectedDate.monthValue}\uC6D4 ${selectedDate.dayOfMonth}\uC77C", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (summary == null && diarySummary == null) {
                    Text(text = "\uC120\uD0DD\uD55C \uB0A0\uC9DC\uC5D0 \uAE30\uB85D\uC774 \uC5C6\uC2B5\uB2C8\uB2E4. \uBC14\uB85C \uC791\uC131\uD558\uB824\uBA74 \uC544\uB798 \uBC84\uD2BC\uC744 \uB204\uB974\uC138\uC694.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                diarySummary?.let {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(text = "\uC77C\uAE30\uC7A5", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(text = "${it.weather} ${it.title}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                summary?.let {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f))) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(text = "\uC77C\uC77C \uAE30\uB85D", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(text = "\uB4F1\uB85D \uD56D\uBAA9")
                                Text(text = it.itemCount.toString(), fontWeight = FontWeight.Bold)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(text = "\uC644\uB8CC \uD56D\uBAA9")
                                Text(text = it.completedCount.toString(), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Button(onClick = { onOpenRecord(selectedDate) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(text = if (summary == null && diarySummary == null) "\uC774 \uB0A0\uC9DC \uAE30\uB85D \uC791\uC131" else "\uC774 \uB0A0\uC9DC \uAE30\uB85D \uC5F4\uAE30")
                }
            }
        }
    }
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
