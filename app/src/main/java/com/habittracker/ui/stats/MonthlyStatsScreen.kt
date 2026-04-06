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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = viewModel::goToPreviousMonth) {
                    Text("\uC774\uC804")
                }
                Text(
                    text = "${uiState.currentMonth.year}\uB144 ${uiState.currentMonth.monthValue}\uC6D4",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = viewModel::goToNextMonth) {
                    Text("\uB2E4\uC74C")
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
    ) {
        if (stats.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("\uC774\uBC88 \uB2EC \uD1B5\uACC4\uAC00 \uC544\uC9C1 \uC5C6\uC2B5\uB2C8\uB2E4.")
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
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = stat.taskName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(text = "\uD0C0\uC785: ${stat.valueType}")
            Text(text = "\uCD1D \uC218\uB7C9: ${stat.totalNumber ?: 0.0}")
            Text(text = "\uC644\uB8CC \uD69F\uC218: ${stat.completedCount}")
        }
    }
}