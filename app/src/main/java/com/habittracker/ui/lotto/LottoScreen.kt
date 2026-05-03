package com.habittracker.ui.lotto

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.habittracker.data.local.entity.LottoDrawEntity
import com.habittracker.data.local.entity.LottoPurchaseEntity
import com.habittracker.data.local.entity.LottoTicketEntity
import com.habittracker.data.local.entity.LottoWinningEntity
import com.habittracker.data.local.model.LottoMonthlyStatRow
import com.habittracker.data.lotto.LottoGeneratedTicket
import com.habittracker.data.lotto.LottoGenerationMode
import com.habittracker.ui.components.AppButtonRow
import com.habittracker.ui.components.AppEmptyCard
import com.habittracker.ui.components.AppHeroCard
import com.habittracker.ui.components.AppLoadingCard
import com.habittracker.ui.components.AppNoticeDialog
import com.habittracker.ui.components.AppPrimaryButton
import com.habittracker.ui.components.AppScreen
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppSectionHeader
import com.habittracker.ui.components.AppSecondaryButton
import com.habittracker.ui.components.AppSelectableChip
import com.habittracker.ui.components.AppStatusText
import com.habittracker.ui.components.actionNoticeDialogTitle
import com.habittracker.ui.components.shouldShowActionNoticeDialog
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

private val LottoHeroColor = Color(0xFFFFFFFF)
private val LottoHeroSubColor = Color(0xFF5C6661)
private val LottoCardColor = Color(0xFFFFFFFF)
private val LottoTextStrongColor = Color(0xFF171C19)
private val LottoTextMutedColor = Color(0xFF5C6661)
private val ChatGptAccent = Color(0xFF256A52)
private val GeminiAccent = Color(0xFFD6DDDA)

@Composable
fun LottoScreen(viewModel: LottoViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var noticeMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uiState.statusMessage) {
        val message = uiState.statusMessage.orEmpty()
        if (message.shouldShowActionNoticeDialog()) {
            noticeMessage = message
        }
    }

    if (uiState.pendingOverwriteRoundNo != null && !uiState.pendingOverwriteSourceLabel.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = viewModel::dismissOverwriteGeneratedBatch,
            title = { Text("저장 데이터 확인") },
            text = { Text("${uiState.pendingOverwriteRoundNo}회차 ${uiState.pendingOverwriteSourceLabel} 번호가 이미 있습니다. 기존 5게임을 새 번호로 바꿀까요?") },
            confirmButton = {
                AppPrimaryButton(text = "업데이트", onClick = viewModel::confirmOverwriteGeneratedBatch)
            },
            dismissButton = {
                AppSecondaryButton(text = "취소", onClick = viewModel::dismissOverwriteGeneratedBatch)
            },
        )
    }
    AppScreen {
        noticeMessage?.let { message ->
            item {
                AppNoticeDialog(
                    message = message,
                    onDismiss = { noticeMessage = null },
                    title = message.actionNoticeDialogTitle(),
                )
            }
        }
        item {
            AppHeroCard(
                title = "로또 관리",
                description = null,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                AppSelectableChip(label = "번호 생성", selected = uiState.selectedTab == "generator", onClick = viewModel::selectGeneratorTab)
                AppSelectableChip(label = "구입 이력", selected = uiState.selectedTab == "purchase", onClick = viewModel::selectPurchaseTab)
                AppSelectableChip(label = "추첨결과", selected = uiState.selectedTab == "draw", onClick = viewModel::selectDrawTab)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                AppSelectableChip(label = "저장번호", selected = uiState.selectedTab == "saved", onClick = viewModel::selectSavedTab)
                AppSelectableChip(label = "당첨 이력", selected = uiState.selectedTab == "winning", onClick = viewModel::selectWinningTab)
                AppSelectableChip(label = "통계", selected = uiState.selectedTab == "stats", onClick = viewModel::selectStatsTab)
            }
        }
        item { StatusCard(latestRoundNo = uiState.latestSavedRoundNo, nextRoundNo = uiState.nextRoundNo, message = uiState.statusMessage) }
        when (uiState.selectedTab) {
            "generator" -> {
                item {
                    GeneratorSection(
                        selectedMode = uiState.generationMode,
                        lastGeneratedSource = uiState.lastGeneratedSource,
                        isGenerating = uiState.isGenerating,
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
                item { Text(text = "최근 회차 저장 번호", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                if (uiState.savedTickets.isEmpty()) {
                    item { AppEmptyCard("최근 회차에 저장된 생성 번호가 없습니다.") }
                } else {
                    item { SavedTicketGroupCard(roundNo = uiState.latestSavedRoundNo, tickets = uiState.savedTickets) }
                }
            }
            "draw" -> {
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
                item { Text(text = if (uiState.queryRoundInput.isBlank()) "최신 추첨결과" else "${uiState.queryRoundInput}회차 추첨결과", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                if (uiState.isHistoryLoading) {
                    item { AppLoadingCard("추첨결과를 조회하고 있습니다.") }
                } else if (uiState.savedDraws.isEmpty()) {
                    item { AppEmptyCard("저장된 추첨결과가 없습니다.") }
                } else {
                    items(uiState.savedDraws, key = { it.roundNo }) { draw -> LottoDrawCard(draw = draw) }
                }
            }
            "purchase" -> {
                item { PurchaseSection(onSave = viewModel::savePurchase) }
                if (uiState.purchases.isEmpty()) item { AppEmptyCard("구입 이력이 없습니다.") } else items(uiState.purchases, key = { it.id }) { purchase ->
                    LottoPurchaseCard(purchase = purchase, onDelete = viewModel::deletePurchase)
                }
            }
            "winning" -> {
                item { WinningSection(onSave = viewModel::saveWinning) }
                if (uiState.winnings.isEmpty()) item { AppEmptyCard("내 당첨 이력이 없습니다.") } else items(uiState.winnings, key = { it.id }) { winning ->
                    LottoWinningCard(winning = winning, onDelete = viewModel::deleteWinning)
                }
            }
            "saved" -> {
                item { SavedEvaluationSection(tickets = uiState.allSavedTickets, draws = uiState.allDraws) }
            }
            "stats" -> {
                item { LottoStatsSection(totalPurchase = uiState.totalPurchaseAmount, totalWinning = uiState.totalWinningAmount, stats = uiState.monthlyStats) }
            }
        }
    }
}

@Composable
private fun StatusCard(latestRoundNo: Int?, nextRoundNo: Int?, message: String?) {
    AppSectionCard {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "저장 상태", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = LottoTextStrongColor)
            Text(text = "최신 저장 회차: ${latestRoundNo ?: "-"}", color = LottoTextMutedColor)
            Text(text = "다음 입력 권장 회차: ${nextRoundNo ?: "-"}", color = LottoTextMutedColor)
            if (!message.isNullOrBlank()) {
                AppStatusText(message)
            }
        }
    }
}

@Composable
private fun GeneratorSection(
    selectedMode: LottoGenerationMode,
    lastGeneratedSource: String?,
    isGenerating: Boolean,
    onModeSelected: (LottoGenerationMode) -> Unit,
    onGenerateChatGpt: () -> Unit,
    onGenerateGemini: () -> Unit,
) {
    AppSectionCard {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "번호 생성", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(text = "기본값은 정밀 모드입니다.", color = LottoTextMutedColor, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                LottoGenerationMode.entries.forEach { mode ->
                    AppSelectableChip(label = mode.label, selected = selectedMode == mode, onClick = { onModeSelected(mode) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                LottoSourceButton(
                    text = "ChatGPT 생성",
                    selected = lastGeneratedSource == "ChatGPT",
                    color = ChatGptAccent,
                    enabled = !isGenerating,
                    onClick = onGenerateChatGpt,
                    modifier = Modifier.weight(1f),
                )
                LottoSourceButton(
                    text = "Gemini 생성",
                    selected = lastGeneratedSource == "Gemini",
                    color = GeminiAccent,
                    enabled = !isGenerating,
                    onClick = onGenerateGemini,
                    modifier = Modifier.weight(1f),
                )
            }
            if (isGenerating) {
                AppLoadingCard("추천 번호를 계산하고 있습니다.")
            }
        }
    }
}

@Composable
private fun LottoSourceButton(
    text: String,
    selected: Boolean,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (selected) color else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (selected && color == ChatGptAccent) Color.White else MaterialTheme.colorScheme.onSurface
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) color else MaterialTheme.colorScheme.outline),
        elevation = androidx.compose.material3.ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
    ) {
        Text(text = text, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
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
            }
            groupedBySource.forEach { (source, sourceTickets) ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(text = source, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        sourceTickets.forEach { ticket ->
                            SavedTicketCard(ticket = ticket)
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
private fun SavedTicketCard(ticket: LottoTicketEntity) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LottoNumberRow(numbers = ticket.numbers())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PurchaseSection(onSave: (String, String, String, String) -> Unit) {
    var purchaseDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var lottoType by remember { mutableStateOf("로또") }
    var amount by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = LocalDate.parse(purchaseDate).toEpochMillis())

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            purchaseDate = millis.toLocalDateString()
                        }
                        showDatePicker = false
                    },
                ) { Text("선택") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("취소") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    AppSectionCard {
        AppSectionHeader(title = "구입 이력 입력")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            AppSelectableChip(
                label = "로또",
                selected = lottoType == "로또",
                onClick = { lottoType = "로또" },
                modifier = Modifier.weight(1f),
            )
            AppSelectableChip(
                label = "연금",
                selected = lottoType == "연금",
                onClick = { lottoType = "연금" },
                modifier = Modifier.weight(1f),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = purchaseDate, onValueChange = { purchaseDate = it }, modifier = Modifier.weight(1f), label = { Text("구입일") }, singleLine = true)
            AppSecondaryButton(text = "달력", onClick = { showDatePicker = true })
        }
        OutlinedTextField(value = amount, onValueChange = { amount = it.filter(Char::isDigit) }, modifier = Modifier.fillMaxWidth(), label = { Text("구입 금액 (원)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
        if (amount.isNotBlank()) {
            Text(text = formatWon(amount.toLongOrNull() ?: 0L), color = LottoTextMutedColor, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(value = memo, onValueChange = { memo = it }, modifier = Modifier.fillMaxWidth(), label = { Text("메모") }, singleLine = true)
        Button(
            onClick = {
                onSave(purchaseDate, lottoType, amount, memo)
                amount = ""
                memo = ""
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("구입 이력 저장") }
    }
}

@Composable
private fun LottoPurchaseCard(purchase: LottoPurchaseEntity, onDelete: (Long) -> Unit) {
    AppSectionCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "${purchase.purchaseDate} · ${purchase.lottoType}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(text = formatWon(purchase.amount.toLong()), color = LottoTextStrongColor, fontWeight = FontWeight.SemiBold)
                purchase.memo?.let { Text(text = it, color = LottoTextMutedColor) }
            }
            AppSecondaryButton(text = "삭제", onClick = { onDelete(purchase.id) })
        }
    }
}

@Composable
private fun WinningSection(onSave: (String, String, String) -> Unit) {
    var roundNo by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") }

    AppSectionCard {
        AppSectionHeader(title = "내 당첨 이력 입력")
        OutlinedTextField(value = roundNo, onValueChange = { roundNo = it.filter(Char::isDigit) }, modifier = Modifier.fillMaxWidth(), label = { Text("회차") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
        OutlinedTextField(value = amount, onValueChange = { amount = it.filter(Char::isDigit) }, modifier = Modifier.fillMaxWidth(), label = { Text("당첨 금액 (원)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
        if (amount.isNotBlank()) {
            Text(text = formatWon(amount.toLongOrNull() ?: 0L), color = LottoTextMutedColor, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(value = memo, onValueChange = { memo = it }, modifier = Modifier.fillMaxWidth(), label = { Text("메모") }, singleLine = true)
        Button(
            onClick = {
                onSave(roundNo, amount, memo)
                roundNo = ""
                amount = ""
                memo = ""
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("당첨 이력 저장") }
    }
}

@Composable
private fun LottoWinningCard(winning: LottoWinningEntity, onDelete: (Long) -> Unit) {
    AppSectionCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "${winning.roundNo}회차", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(text = formatWon(winning.amount), color = LottoTextStrongColor, fontWeight = FontWeight.SemiBold)
                winning.memo?.let { Text(text = it, color = LottoTextMutedColor) }
            }
            AppSecondaryButton(text = "삭제", onClick = { onDelete(winning.id) })
        }
    }
}

@Composable
private fun SavedEvaluationSection(tickets: List<LottoTicketEntity>, draws: List<LottoDrawEntity>) {
    val drawMap = draws.associateBy(LottoDrawEntity::roundNo)
    AppSectionCard {
        AppSectionHeader(title = "저장번호 당첨 판정")
        Text(text = "회차별 저장번호와 판정을 한 번에 확인합니다.", color = LottoTextMutedColor)
    }
    if (tickets.isEmpty()) {
        AppEmptyCard("저장된 번호가 없습니다.")
    } else {
        tickets.groupBy(::extractRoundNo).forEach { (roundNo, roundTickets) ->
            SavedTicketEvaluationGroup(roundNo = roundNo, tickets = roundTickets, draw = roundNo?.let(drawMap::get))
        }
    }
}

@Composable
private fun SavedTicketEvaluationGroup(roundNo: Int?, tickets: List<LottoTicketEntity>, draw: LottoDrawEntity?) {
    val groupedBySource = tickets.groupBy(::normalizeSourceLabel)
    AppSectionCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = if (roundNo != null) "${roundNo}회차" else "회차 미지정", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(text = if (draw == null) "추첨결과 대기" else "당첨번호 입력됨", color = LottoTextMutedColor, style = MaterialTheme.typography.bodySmall)
        }
        draw?.let { LottoNumberRow(numbers = it.numbers()) }
        groupedBySource.forEach { (source, sourceTickets) ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background)) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(text = source, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    sourceTickets.take(5).forEachIndexed { index, ticket ->
                        val matchCount = draw?.numbers()?.let { winningNumbers -> ticket.numbers().count(winningNumbers::contains) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = "${index + 1}", color = LottoTextMutedColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.16f))
                            CompactLottoNumberRow(numbers = ticket.numbers(), modifier = Modifier.weight(1f))
                            Text(
                                text = formatWinningStatusWithMatchCount(matchCount),
                                color = if ((matchCount ?: 0) >= 3) ChatGptAccent else LottoTextMutedColor,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(0.52f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactLottoNumberRow(numbers: List<Int>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        numbers.forEach { number ->
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(lottoBallColor(number)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = number.toString(),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun LottoStatsSection(totalPurchase: Long, totalWinning: Long, stats: List<LottoMonthlyStatRow>) {
    val net = totalWinning - totalPurchase
    AppSectionCard {
        AppSectionHeader(title = "구입/당첨 요약")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatMiniCard(title = "구입", value = formatWon(totalPurchase), modifier = Modifier.weight(1f))
            StatMiniCard(title = "당첨", value = formatWon(totalWinning), modifier = Modifier.weight(1f))
            StatMiniCard(title = "손익", value = formatWon(net), modifier = Modifier.weight(1f))
        }
    }
    AppSectionCard {
        AppSectionHeader(title = "월별 흐름")
        if (stats.isEmpty()) {
            Text(text = "통계 데이터가 없습니다.", color = LottoTextMutedColor)
        } else {
            val maxValue = stats.maxOf { maxOf(it.purchaseAmount, it.winningAmount, 1L) }.toFloat()
            stats.sortedBy(LottoMonthlyStatRow::month).forEach { row ->
                Text(text = row.month, fontWeight = FontWeight.SemiBold)
                AmountBar(label = "구입 ${formatWon(row.purchaseAmount)}", ratio = row.purchaseAmount / maxValue, color = Color(0xFFB8C7D9))
                AmountBar(label = "당첨 ${formatWon(row.winningAmount)}", ratio = row.winningAmount / maxValue, color = ChatGptAccent)
            }
        }
    }
}

@Composable
private fun StatMiniCard(title: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = title, color = LottoTextMutedColor, style = MaterialTheme.typography.bodySmall)
        Text(text = value, color = LottoTextStrongColor, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AmountBar(label: String, ratio: Float, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, color = LottoTextMutedColor, style = MaterialTheme.typography.bodySmall)
        Box(modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.outlineVariant)) {
            Box(modifier = Modifier.fillMaxWidth(ratio.coerceIn(0f, 1f)).height(10.dp).clip(RoundedCornerShape(8.dp)).background(color))
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

private fun formatWinningStatus(matchCount: Int?): String {
    return when (matchCount) {
        null -> "대기"
        6 -> "1등 당첨"
        5 -> "3등 당첨"
        4 -> "4등 당첨"
        3 -> "5등 당첨"
        else -> "미당첨"
    }
}

private fun formatWinningStatusWithMatchCount(matchCount: Int?): String {
    return matchCount?.let { "${formatWinningStatus(it)} ${it}개" } ?: formatWinningStatus(null)
}

private fun formatWon(amount: Long): String {
    val sign = if (amount < 0) "-" else ""
    val formatted = NumberFormat.getNumberInstance(Locale.KOREA).format(kotlin.math.abs(amount))
    return "$sign${formatted}원"
}

private fun LocalDate.toEpochMillis(): Long {
    return atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

private fun Long.toLocalDateString(): String {
    return Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate().toString()
}

private fun extractRoundNo(ticket: LottoTicketEntity): Int? {
    return ticket.note
        ?.takeIf { it.startsWith("ROUND:") }
        ?.removePrefix("ROUND:")
        ?.toIntOrNull()
}

private fun lottoBallColor(number: Int) = when (number) {
    in 1..10 -> Color(0xFFFBC400)
    in 11..20 -> Color(0xFF69A5FF)
    in 21..30 -> Color(0xFFFF6B6B)
    in 31..40 -> Color(0xFFA0A0A0)
    else -> Color(0xFF76B947)
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
