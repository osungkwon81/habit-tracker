package com.habittracker.ui.lotto

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import com.habittracker.data.local.entity.LottoDrawEntity
import com.habittracker.data.lotto.LottoGeneratedTicket

@Composable
fun LottoScreen(viewModel: LottoViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "로또 관리",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "1218회차를 최신 데이터로 시드하고, 다음 회차 입력과 생성 결과 확인을 한 화면에서 처리합니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            StatusCard(
                latestRoundNo = uiState.latestSavedRoundNo,
                nextRoundNo = uiState.nextRoundNo,
                message = uiState.statusMessage,
            )
        }
        item {
            GeneratorSection(
                onGenerateChatGpt = viewModel::generateChatGpt,
                onGenerateGemini = viewModel::generateGemini,
            )
        }
        item {
            GeneratedTicketSection(
                title = "챗GPT 생성 결과",
                tickets = uiState.chatGptResults,
                onApply = viewModel::applyGeneratedNumbers,
            )
        }
        item {
            GeneratedTicketSection(
                title = "제미니 생성 결과",
                tickets = uiState.geminiResults,
                onApply = viewModel::applyGeneratedNumbers,
            )
        }
        item {
            SaveSection(
                roundInput = uiState.roundInput,
                numberInputs = uiState.numberInputs,
                onRoundChange = viewModel::updateRoundInput,
                onNumberChange = viewModel::updateNumberInput,
                onSave = viewModel::saveDraw,
            )
        }
        item {
            SearchSection(
                queryRoundInput = uiState.queryRoundInput,
                onQueryChange = viewModel::updateQueryRoundInput,
            )
        }
        item {
            Text(
                text = if (uiState.queryRoundInput.isBlank()) "최근 10건" else "${uiState.queryRoundInput}회차 조회 결과",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        items(uiState.savedDraws, key = { it.roundNo }) { draw ->
            LottoDrawCard(draw = draw)
        }
    }
}

@Composable
private fun StatusCard(latestRoundNo: Int?, nextRoundNo: Int?, message: String?) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "저장 상태", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(text = "최신 저장 회차: ${latestRoundNo ?: "-"}")
            Text(text = "다음 입력 권장 회차: ${nextRoundNo ?: "-"}")
            if (!message.isNullOrBlank()) {
                Text(text = message, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun GeneratorSection(
    onGenerateChatGpt: () -> Unit,
    onGenerateGemini: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "번호 생성", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onGenerateChatGpt, modifier = Modifier.weight(1f)) {
                    Text("챗GPT 생성")
                }
                Button(onClick = onGenerateGemini, modifier = Modifier.weight(1f)) {
                    Text("제미니 생성")
                }
            }
        }
    }
}

@Composable
private fun GeneratedTicketSection(
    title: String,
    tickets: List<LottoGeneratedTicket>,
    onApply: (List<Int>) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (tickets.isEmpty()) {
                Text(text = "아직 생성 결과가 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                tickets.forEachIndexed { index, ticket ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(text = "${index + 1}조합", fontWeight = FontWeight.SemiBold)
                            Text(
                                text = ticket.numbers.joinToString("  "),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            if (!ticket.comment.isNullOrBlank()) {
                                Text(
                                    text = ticket.comment,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Button(onClick = { onApply(ticket.numbers) }, modifier = Modifier.fillMaxWidth()) {
                                Text("입력 칸에 적용")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SaveSection(
    roundInput: String,
    numberInputs: List<String>,
    onRoundChange: (String) -> Unit,
    onNumberChange: (Int, String) -> Unit,
    onSave: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "회차 저장", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = roundInput,
                onValueChange = onRoundChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("회차 번호") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            numberInputs.chunked(3).forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEachIndexed { columnIndex, value ->
                        val inputIndex = rowIndex * 3 + columnIndex
                        OutlinedTextField(
                            value = value,
                            onValueChange = { onNumberChange(inputIndex, it) },
                            modifier = Modifier.weight(1f),
                            label = { Text("${inputIndex + 1}번") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                    }
                }
            }
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                Text("저장")
            }
        }
    }
}

@Composable
private fun SearchSection(
    queryRoundInput: String,
    onQueryChange: (String) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = "회차 조회", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = queryRoundInput,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("회차 번호 입력, 비우면 최근 10건") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
    }
}

@Composable
private fun LottoDrawCard(draw: LottoDrawEntity) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "${draw.roundNo}회차", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                text = draw.numbers().joinToString("  "),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Start,
            )
            Text(
                text = "저장 시각 ${draw.savedAt}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
