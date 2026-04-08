package com.habittracker.ui.lotto

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.habittracker.data.local.entity.LottoDrawEntity
import com.habittracker.data.local.entity.LottoTicketEntity
import com.habittracker.data.lotto.LottoGeneratedTicket
import com.habittracker.data.lotto.LottoGenerationMode

private val LottoHeroColor = Color(0xFF14242A)
private val LottoHeroSubColor = Color(0xFFE6D9BF)
private val LottoCardColor = Color(0xFFFFFBF5)
private val LottoTextStrongColor = Color(0xFF172126)
private val LottoTextMutedColor = Color(0xFF45575D)
private val ChatGptAccent = Color(0xFF2F6B57)
private val GeminiAccent = Color(0xFF3B67B1)

@Composable
fun LottoScreen(viewModel: LottoViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.pendingOverwriteRoundNo != null && !uiState.pendingOverwriteSourceLabel.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = viewModel::dismissOverwriteGeneratedBatch,
            title = { Text("저장 데이터 확인") },
            text = { Text("${uiState.pendingOverwriteRoundNo}회차 ${uiState.pendingOverwriteSourceLabel} 번호가 이미 있습니다. 기존 5게임을 새 번호로 바꿀까요?") },
            confirmButton = {
                TextButton(onClick = viewModel::confirmOverwriteGeneratedBatch) {
                    Text("업데이트")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissOverwriteGeneratedBatch) {
                    Text("취소")
                }
            },
        )
    }
    if (uiState.pendingDeleteRoundNo != null || uiState.pendingDeleteTicketId != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteRequest,
            title = { Text("삭제 확인") },
            text = {
                Text(
                    text = if (uiState.pendingDeleteRoundNo != null) {
                        "${uiState.pendingDeleteRoundNo}회차 저장 번호를 모두 삭제할까요?"
                    } else {
                        "선택한 번호를 삭제할까요?"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDeleteRequest) {
                    Text("삭제")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDeleteRequest) {
                    Text("취소")
                }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Card(shape = RoundedCornerShape(30.dp), colors = CardDefaults.cardColors(containerColor = LottoHeroColor)) {
                Column(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "🎯 로또 관리", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(text = "번호 생성, 저장 목록, 실제 당첨 번호 이력을 분리해서 관리합니다.", color = LottoHeroSubColor)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(selected = uiState.selectedTab == "generator", onClick = viewModel::selectGeneratorTab, label = { Text("번호 생성") })
                FilterChip(selected = uiState.selectedTab == "history", onClick = viewModel::selectHistoryTab, label = { Text("당첨 이력") })
            }
        }
        item { StatusCard(latestRoundNo = uiState.latestSavedRoundNo, nextRoundNo = uiState.nextRoundNo, message = uiState.statusMessage) }
        if (uiState.selectedTab == "generator") {
            item {
                GeneratorSection(
                    selectedMode = uiState.generationMode,
                    isGenerating = uiState.isGenerating,
                    targetRoundNo = uiState.roundInput.toIntOrNull() ?: uiState.nextRoundNo,
                    onModeSelected = viewModel::updateGenerationMode,
                    onGenerateChatGpt = viewModel::generateChatGpt,
                    onGenerateGemini = viewModel::generateGemini,
                )
            }
            item {
                GeneratedTicketSection(
                    title = "ChatGPT 추천 번호",
                    sourceLabel = "ChatGPT",
                    tickets = uiState.chatGptResults,
                    isLatest = uiState.lastGeneratedSource == "ChatGPT",
                    onApply = viewModel::applyGeneratedNumbers,
                    onSaveBatch = viewModel::saveGeneratedBatch,
                )
            }
            item {
                GeneratedTicketSection(
                    title = "Gemini 추천 번호",
                    sourceLabel = "Gemini",
                    tickets = uiState.geminiResults,
                    isLatest = uiState.lastGeneratedSource == "Gemini",
                    onApply = viewModel::applyGeneratedNumbers,
                    onSaveBatch = viewModel::saveGeneratedBatch,
                )
            }
            item { Text(text = "저장한 생성 번호", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            if (uiState.savedTickets.isEmpty()) {
                item { EmptyCard(message = "저장된 생성 번호가 없습니다.") }
            } else {
                val groupedTickets = uiState.savedTickets.groupBy(::extractRoundNo)
                groupedTickets.forEach { (roundNo, ticketsOnRound) ->
                    item {
                        SavedTicketGroupCard(
                            roundNo = roundNo,
                            tickets = ticketsOnRound,
                            onDeleteRound = { targetRoundNo -> viewModel.requestDeleteSavedRound(targetRoundNo) },
                            onDeleteTicket = { ticketId -> viewModel.requestDeleteSavedTicket(ticketId) },
                        )
                    }
                }
            }
        } else {
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
                    isLoading = uiState.isHistoryLoading,
                    onQueryChange = viewModel::updateQueryRoundInput,
                    onSearch = viewModel::submitDrawQuery,
                )
            }
            item {
                Text(
                    text = if (uiState.queryRoundInput.isBlank()) "최신 당첨 번호" else "${uiState.queryRoundInput}회차 조회 결과",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (uiState.isHistoryLoading) {
                item { LoadingCard(message = "당첨 번호를 조회하고 있습니다.") }
            } else if (uiState.savedDraws.isEmpty()) {
                item { EmptyCard(message = "저장된 당첨 번호가 없습니다.") }
            } else {
                items(uiState.savedDraws, key = { it.roundNo }) { draw ->
                    LottoDrawCard(draw = draw)
                }
            }
        }
    }
}

@Composable
private fun StatusCard(latestRoundNo: Int?, nextRoundNo: Int?, message: String?) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = LottoCardColor)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "저장 상태", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = LottoTextStrongColor)
            Text(text = "최신 저장 회차: ${latestRoundNo ?: "-"}", color = LottoTextMutedColor)
            Text(text = "다음 입력 권장 회차: ${nextRoundNo ?: "-"}", color = LottoTextMutedColor)
            if (!message.isNullOrBlank()) {
                Text(text = message, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun GeneratorSection(
    selectedMode: LottoGenerationMode,
    isGenerating: Boolean,
    targetRoundNo: Int?,
    onModeSelected: (LottoGenerationMode) -> Unit,
    onGenerateChatGpt: () -> Unit,
    onGenerateGemini: () -> Unit,
) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = LottoCardColor)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "번호 생성", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                text = if (targetRoundNo != null) "${targetRoundNo}회차 저장용 번호를 생성합니다." else "저장할 회차를 먼저 확인해 주세요.",
                color = LottoTextMutedColor,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                LottoGenerationMode.entries.forEach { mode ->
                    FilterChip(
                        selected = selectedMode == mode,
                        onClick = { onModeSelected(mode) },
                        label = { Text(mode.label) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onGenerateChatGpt, modifier = Modifier.weight(1f), enabled = !isGenerating) {
                    Text("ChatGPT 생성")
                }
                Button(onClick = onGenerateGemini, modifier = Modifier.weight(1f), enabled = !isGenerating) {
                    Text("Gemini 생성")
                }
            }
            if (isGenerating) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text(text = "추천 번호를 계산하고 있습니다.")
                }
            }
        }
    }
}

@Composable
private fun GeneratedTicketSection(
    title: String,
    sourceLabel: String,
    tickets: List<LottoGeneratedTicket>,
    isLatest: Boolean,
    onApply: (List<Int>) -> Unit,
    onSaveBatch: (String, List<LottoGeneratedTicket>) -> Unit,
) {
    val accentColor = if (sourceLabel == "Gemini") GeminiAccent else ChatGptAccent
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = LottoCardColor),
        border = androidx.compose.foundation.BorderStroke(
            width = if (isLatest) 2.dp else 1.dp,
            color = if (isLatest) accentColor else accentColor.copy(alpha = 0.18f),
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = LottoTextStrongColor)
                if (isLatest) {
                    Text(
                        text = "방금 생성됨",
                        color = accentColor,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (tickets.isEmpty()) {
                Text(text = "아직 생성된 번호가 없습니다.", color = LottoTextMutedColor)
            } else {
                Button(onClick = { onSaveBatch(sourceLabel, tickets.take(5)) }, modifier = Modifier.fillMaxWidth()) {
                    Text(text = "5게임 묶음 저장")
                }
                tickets.take(5).forEachIndexed { index, ticket ->
                    Card(colors = CardDefaults.cardColors(containerColor = accentColor.copy(alpha = 0.08f))) {
                        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(text = "${index + 1}번 조합", fontWeight = FontWeight.SemiBold, color = LottoTextStrongColor)
                            LottoNumberRow(numbers = ticket.numbers)
                            ticket.comment?.let { Text(text = it, color = LottoTextMutedColor) }
                            Button(onClick = { onApply(ticket.numbers) }, modifier = Modifier.fillMaxWidth()) { Text("입력칸 반영") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SaveSection(roundInput: String, numberInputs: List<String>, onRoundChange: (String) -> Unit, onNumberChange: (Int, String) -> Unit, onSave: () -> Unit) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = LottoCardColor)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "당첨 번호 저장", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(value = roundInput, onValueChange = onRoundChange, modifier = Modifier.fillMaxWidth(), label = { Text("회차 번호") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            numberInputs.chunked(3).forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEachIndexed { columnIndex, value ->
                        val inputIndex = rowIndex * 3 + columnIndex
                        OutlinedTextField(value = value, onValueChange = { onNumberChange(inputIndex, it) }, modifier = Modifier.weight(1f), label = { Text("${inputIndex + 1}번") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                    }
                }
            }
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("당첨 번호 저장") }
        }
    }
}

@Composable
private fun SearchSection(
    queryRoundInput: String,
    isLoading: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = LottoCardColor)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = "당첨 번호 조회", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(value = queryRoundInput, onValueChange = onQueryChange, modifier = Modifier.fillMaxWidth(), label = { Text("회차 번호 입력, 비우면 최신 목록") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            Button(onClick = onSearch, modifier = Modifier.fillMaxWidth(), enabled = !isLoading) {
                Text(if (isLoading) "조회 중..." else "조회")
            }
        }
    }
}

@Composable
private fun LottoDrawCard(draw: LottoDrawEntity) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = LottoCardColor)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "${draw.roundNo}회차", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            LottoNumberRow(numbers = draw.numbers())
            Text(text = "저장 시각 ${draw.savedAt}", color = LottoTextMutedColor, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SavedTicketGroupCard(
    roundNo: Int?,
    tickets: List<LottoTicketEntity>,
    onDeleteRound: (Int) -> Unit,
    onDeleteTicket: (Long) -> Unit,
) {
    val groupedBySource = tickets.groupBy(::normalizeSourceLabel)
    val savedDate = tickets.maxByOrNull(LottoTicketEntity::createdAt)?.createdAt?.toLocalDate()

    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = LottoCardColor)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (roundNo != null) "${roundNo}회차" else "회차 미지정",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    savedDate?.let {
                        Text(
                            text = "${it.year}년 ${it.monthValue}월 ${it.dayOfMonth}일 저장",
                            color = LottoTextMutedColor,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (roundNo != null) {
                    TextButton(onClick = { onDeleteRound(roundNo) }) {
                        Text("회차 삭제")
                    }
                }
            }
            groupedBySource.forEach { (source, sourceTickets) ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(text = source, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        sourceTickets.forEach { ticket ->
                            SavedTicketCard(ticket = ticket, onDelete = { onDeleteTicket(ticket.id) })
                        }
                    }
                }
            }
        }
    }
}

private fun normalizeSourceLabel(ticket: LottoTicketEntity): String {
    val source = ticket.sourceLabel.lowercase()
    return when {
        source.contains("gemini") -> "Gemini"
        source.contains("chatgpt") || source.contains("gpt") -> "ChatGPT"
        else -> ticket.sourceLabel
    }
}

@Composable
private fun SavedTicketCard(ticket: LottoTicketEntity, onDelete: () -> Unit) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LottoNumberRow(numbers = ticket.numbers())
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDelete) {
                    Text("개별 삭제")
                }
            }
        }
    }
}

@Composable
private fun LottoNumberRow(numbers: List<Int>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        numbers.forEach { number ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .background(lottoBallColor(number)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "%02d".format(number),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = Color.White,
                )
            }
        }
    }
}

private fun extractRoundNo(ticket: LottoTicketEntity): Int? {
    return ticket.note
        ?.takeIf { it.startsWith("ROUND:") }
        ?.removePrefix("ROUND:")
        ?.toIntOrNull()
}

private fun lottoBallColor(number: Int) = when (number) {
    in 1..10 -> Color(0xFFF6C445)
    in 11..20 -> Color(0xFF5AA9FF)
    in 21..30 -> Color(0xFFE25555)
    in 31..40 -> Color(0xFF8D8D8D)
    else -> Color(0xFF67B76A)
}

@Composable
private fun EmptyCard(message: String) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Text(text = message, modifier = Modifier.fillMaxWidth().padding(16.dp), color = LottoTextMutedColor)
    }
}

@Composable
private fun LoadingCard(message: String) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator()
            Text(text = message, color = LottoTextMutedColor)
        }
    }
}
