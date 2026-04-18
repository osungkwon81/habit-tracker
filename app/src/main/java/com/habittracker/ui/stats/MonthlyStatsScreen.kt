package com.habittracker.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.habittracker.ui.components.AppEmptyCard
import com.habittracker.ui.components.AppHeroCard
import com.habittracker.ui.components.AppScreen
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppSectionHeader
import com.habittracker.ui.components.AppSpacing
import com.habittracker.ui.components.AppSecondaryButton
import kotlin.math.roundToInt

@Composable
fun MonthlyStatsScreen(
    viewModel: MonthlyStatsViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    val totalCompleted = uiState.stats.sumOf(TaskStatSummary::completedCount)
    val totalDistance = uiState.stats.filter { it.valueType == "EXERCISE" }.sumOf { it.totalNumber ?: 0.0 }
    val totalDuration = uiState.stats.filter { it.valueType == "EXERCISE" }.sumOf { it.totalDuration ?: 0 }
    val focusStat = uiState.stats.maxByOrNull { it.totalNumber ?: it.completedCount.toDouble() }

    AppScreen {
        item {
            AppHeroCard(
                title = "통계",
                description = null,
                action = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                    ) {
                        AppSecondaryButton(text = "이전", onClick = viewModel::goToPreviousMonth, modifier = Modifier.weight(1f))
                        Text(
                            text = "${uiState.currentMonth.year}년 ${uiState.currentMonth.monthValue}월",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        AppSecondaryButton(text = "다음", onClick = viewModel::goToNextMonth, modifier = Modifier.weight(1f))
                    }
                },
            )
        }
        item {
            AppSectionCard {
                AppSectionHeader(title = "이번 달 핵심 수치")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                ) {
                    StatsHighlightCard(title = "완료", value = "${totalCompleted}회", modifier = Modifier.weight(1f))
                    StatsHighlightCard(title = "걸은거리", value = formatDistance(totalDistance), modifier = Modifier.weight(1f))
                    StatsHighlightCard(title = "걸은시간", value = formatDuration(totalDuration), modifier = Modifier.weight(1f))
                }
                focusStat?.let {
                    Text(
                        text = "가장 많이 기록한 항목: ${it.taskName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (uiState.taskCharts.isEmpty()) {
            item { AppEmptyCard("이번 달 통계가 아직 없습니다.") }
        } else {
            uiState.taskCharts.forEach { chart ->
                item {
                    TaskLineChartCard(chart = chart)
                }
            }
        }
    }
}

@Composable
private fun TaskLineChartCard(
    chart: TaskSeriesChart,
) {
    val lineColor = colorFromHex(chart.colorHex)
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    AppSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = chart.taskName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (chart.valueType == "EXERCISE") {
                        "누적 ${formatDistance(chart.totalNumber)} / ${formatDuration(chart.totalDuration)}"
                    } else {
                        "누적 ${formatSeriesValue(chart.points.sumOf { it.value }, chart.valueType)}"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = "${chart.completedCount}회",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = lineColor,
            )
        }
        if (chart.points.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                Text("기록이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Canvas(modifier = Modifier.fillMaxWidth().height(180.dp)) {
                val maxValue = chart.points.maxOfOrNull { it.value }?.coerceAtLeast(1.0) ?: 1.0
                val leftPadding = 20.dp.toPx()
                val rightPadding = 20.dp.toPx()
                val topPadding = 28.dp.toPx()
                val bottomPadding = 28.dp.toPx()
                val chartWidth = (size.width - leftPadding - rightPadding).coerceAtLeast(0f)
                val chartHeight = (size.height - topPadding - bottomPadding).coerceAtLeast(0f)
                val horizontalStep = if (chart.points.size == 1) 0f else chartWidth / (chart.points.size - 1)
                val points = chart.points.mapIndexed { index, point ->
                    val progress = (point.value / maxValue).toFloat()
                    Offset(
                        x = leftPadding + (horizontalStep * index),
                        y = topPadding + chartHeight - (chartHeight * progress),
                    )
                }
                drawLine(
                    color = outlineColor,
                    start = Offset(leftPadding, size.height - bottomPadding),
                    end = Offset(size.width - rightPadding, size.height - bottomPadding),
                    strokeWidth = 2f,
                )
                val path = Path().apply {
                    points.forEachIndexed { index, point ->
                        if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                    }
                }
                drawPath(path = path, color = lineColor, style = Stroke(width = 6f))
                points.forEach { point ->
                    drawCircle(color = lineColor, radius = 8f, center = point)
                }
                val textPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.DKGRAY
                    textSize = 28f
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                }
                drawIntoCanvas { canvas ->
                    points.forEachIndexed { index, point ->
                        canvas.nativeCanvas.drawText(
                            formatSeriesValue(chart.points[index].value, chart.valueType),
                            point.x,
                            (point.y - 14f).coerceAtLeast(24f),
                            textPaint,
                        )
                        canvas.nativeCanvas.drawText(
                            "${chart.points[index].date.dayOfMonth}일",
                            point.x,
                            size.height - 4f,
                            textPaint,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsHighlightCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
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

private fun formatDistance(distance: Double?): String {
    if (distance == null || distance <= 0.0) return "0km"
    val rounded = (distance * 10).roundToInt() / 10.0
    val asLong = rounded.toLong()
    return if (rounded == asLong.toDouble()) "${asLong}km" else "${rounded}km"
}

private fun formatDuration(duration: Int?): String {
    if (duration == null || duration <= 0) return "0분"
    val hours = duration / 60
    val minutes = duration % 60
    return if (hours > 0) "${hours}시간 ${minutes}분" else "${minutes}분"
}

private fun formatSeriesValue(value: Double, valueType: String): String {
    return if (valueType == "EXERCISE") {
        val rounded = (value * 10).roundToInt() / 10.0
        val asLong = rounded.toLong()
        if (rounded == asLong.toDouble()) "${asLong}km" else "${rounded}km"
    } else {
        "${value.roundToInt()}회"
    }
}

private fun colorFromHex(colorHex: String): Color {
    return runCatching { Color(android.graphics.Color.parseColor(colorHex)) }
        .getOrDefault(Color(0xFF256A52))
}
