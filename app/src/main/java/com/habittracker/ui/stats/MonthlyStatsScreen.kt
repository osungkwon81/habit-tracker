package com.habittracker.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.habittracker.data.local.model.MonthlyStatRow

private val StatsHeroColor = Color(0xFF12252B)
private val StatsHeroSubColor = Color(0xFFE4D8BD)
private val StatsCardColor = Color(0xFFFFFBF4)
private val StatsTextStrongColor = Color(0xFF172126)
private val StatsTextMutedColor = Color(0xFF45575E)

@Composable
fun MonthlyStatsScreen(
    viewModel: MonthlyStatsViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    val maxValue = uiState.stats.maxOfOrNull { stat -> stat.totalNumber ?: stat.completedCount.toDouble() } ?: 1.0

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Card(shape = RoundedCornerShape(30.dp), colors = CardDefaults.cardColors(containerColor = StatsHeroColor)) {
                Column(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = "📊 월간 통계", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(text = "반복 기록과 운동 흐름을 한 달 단위로 확인합니다.", color = StatsHeroSubColor, style = MaterialTheme.typography.bodyMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(onClick = viewModel::goToPreviousMonth) { Text("이전") }
                        Text(
                            text = "${uiState.currentMonth.year}년 ${uiState.currentMonth.monthValue}월",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        TextButton(onClick = viewModel::goToNextMonth) { Text("다음") }
                    }
                }
            }
        }

        item {
            StatsChart(
                stats = uiState.stats,
                maxValue = maxValue,
                primaryColor = MaterialTheme.colorScheme.primary,
                secondaryColor = MaterialTheme.colorScheme.secondary,
            )
        }

        items(uiState.stats) { stat ->
            StatsCard(stat = stat)
        }
    }
}

@Composable
private fun StatsChart(
    stats: List<MonthlyStatRow>,
    maxValue: Double,
    primaryColor: Color,
    secondaryColor: Color,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = StatsCardColor),
    ) {
        if (stats.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("\uC774\uBC88 \uB2EC \uD1B5\uACC4\uAC00 \uC544\uC9C1 \uC5C6\uC2B5\uB2C8\uB2E4.", color = StatsTextStrongColor)
            }
        } else {
            Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                val barWidth = size.width / (stats.size * 1.8f)
                stats.forEachIndexed { index, stat ->
                    val rawValue = stat.totalNumber ?: stat.completedCount.toDouble()
                    val barHeight = (rawValue / maxValue).toFloat() * size.height
                    val left = index * barWidth * 1.8f
                    drawRoundRect(
                        color = if (index % 2 == 0) primaryColor else secondaryColor,
                        topLeft = Offset(left, size.height - barHeight),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(18f, 18f),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsCard(
    stat: MonthlyStatRow,
) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = StatsCardColor)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = stat.taskName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = StatsTextStrongColor)
            Text(text = "타입: ${stat.valueType}", color = StatsTextMutedColor)
            if (stat.valueType == "EXERCISE") {
                Text(text = "\uCD1D \uAC70\uB9AC: ${formatDistance(stat.totalNumber)}", color = StatsTextStrongColor)
                Text(text = "\uCD1D \uC2DC\uAC04: ${formatDuration(stat.totalDuration)}", color = StatsTextStrongColor)
                Text(text = "\uD3C9\uADE0 \uD398\uC774\uC2A4: ${formatPace(stat.totalDuration, stat.totalNumber)}", color = StatsTextStrongColor)
                Text(text = "\uAE30\uB85D \uD69F\uC218: ${stat.completedCount}", color = StatsTextStrongColor)
            } else {
                Text(text = "\uCD1D \uC218\uB7C9: ${stat.totalNumber ?: 0.0}", color = StatsTextStrongColor)
                Text(text = "\uC644\uB8CC \uD69F\uC218: ${stat.completedCount}", color = StatsTextStrongColor)
            }
        }
    }
}

private fun formatDistance(distance: Double?): String {
    if (distance == null) return "0km"
    val asLong = distance.toLong()
    return if (distance == asLong.toDouble()) "${asLong}km" else "${distance}km"
}

private fun formatDuration(duration: Int?): String {
    if (duration == null || duration <= 0) return "0분"
    val hours = duration / 60
    val minutes = duration % 60
    return if (hours > 0) "${hours}시간 ${minutes}분" else "${minutes}분"
}

private fun formatPace(totalDuration: Int?, totalDistance: Double?): String {
    if (totalDuration == null || totalDuration <= 0 || totalDistance == null || totalDistance <= 0.0) return "-"
    val totalSeconds = totalDuration * 60
    val secondsPerKm = (totalSeconds / totalDistance).toInt()
    val minutes = secondsPerKm / 60
    val seconds = secondsPerKm % 60
    return "${minutes}분 ${seconds.toString().padStart(2, '0')}초/km"
}
