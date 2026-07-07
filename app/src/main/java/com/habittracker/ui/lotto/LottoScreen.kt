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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.habittracker.data.local.model.LottoPeriodStatRow
import com.habittracker.data.lotto.LottoGeneratedTicket
import com.habittracker.data.lotto.LottoGenerationMode
import com.habittracker.ui.components.AppEmptyCard
import com.habittracker.ui.components.AppHeroCard
import com.habittracker.ui.components.AppLoadingCard
import com.habittracker.ui.components.AppNoticeDialog
import com.habittracker.ui.components.AppPrimaryButton
import com.habittracker.ui.components.AppSaveButton
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
import java.time.temporal.TemporalAdjusters
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

    noticeMessage?.let { message ->
        AppNoticeDialog(
            message = message,
            onDismiss = {
                noticeMessage = null
                viewModel.clearStatusMessage()
            },
            title = message.actionNoticeDialogTitle(),
        )
    }

    AppScreen {
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
                if (uiState.lastGeneratedSource == "분산형") {
                    item {
                        GeneratedTicketSection(
                            title = "분산형 추천 번호",
                            sourceLabel = "분산형",
                            tickets = uiState.geminiResults,
                            isLatest = true,
                            onApply = viewModel::applyGeneratedNumbers,
                            onSaveBatch = viewModel::saveGeneratedBatch,
                        )
                    }
                    item {
                        GeneratedTicketSection(
                            title = "균형형 추천 번호",
                            sourceLabel = "균형형",
                            tickets = uiState.chatGptResults,
                            isLatest = false,
                            onApply = viewModel::applyGeneratedNumbers,
                            onSaveBatch = viewModel::saveGeneratedBatch,
                        )
                    }
                } else {
                    item {
                        GeneratedTicketSection(
                            title = "균형형 추천 번호",
                            sourceLabel = "균형형",
                            tickets = uiState.chatGptResults,
                            isLatest = uiState.lastGeneratedSource == "균형형",
                            onApply = viewModel::applyGeneratedNumbers,
                            onSaveBatch = viewModel::saveGeneratedBatch,
                        )
                    }
                    item {
                        GeneratedTicketSection(
                            title = "분산형 추천 번호",
                            sourceLabel = "분산형",
                            tickets = uiState.geminiResults,
                            isLatest = false,
                            onApply = viewModel::applyGeneratedNumbers,
                            onSaveBatch = viewModel::saveGeneratedBatch,
                        )
                    }
                }
                if (uiState.savedTickets.isEmpty()) {
                    item { AppEmptyCard("다음 회차에 저장된 생성 번호가 없습니다.") }
                } else {
                    item { SavedTicketGroupCard(roundNo = uiState.nextRoundNo, tickets = uiState.savedTickets) }
                }
            }
            "draw" -> {
                item {
                    SaveSection(
                        roundInput = uiState.roundInput,
                        numberInputs = uiState.numberInputs,
                        bonusNumberInput = uiState.bonusNumberInput,
                        onRoundChange = viewModel::updateRoundInput,
                        onNumberChange = viewModel::updateNumberInput,
                        onBonusNumberChange = viewModel::updateBonusNumberInput,
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
                item {
                    SavedNumbersSection(
                        currentRoundNo = uiState.nextRoundNo,
                        currentRoundTickets = uiState.savedTickets,
                        historyTickets = uiState.allSavedTickets,
                        draws = uiState.allDraws,
                        savedRoundQueryInput = uiState.savedRoundQueryInput,
                        onSavedRoundQueryChange = viewModel::updateSavedRoundQueryInput,
                        onMarkSetPurchased = viewModel::markSavedSetPurchased,
                        onDeleteSet = viewModel::deleteSavedSet,
                    )
                }
            }
            "stats" -> {
                item {
                    LottoStatsSection(
                        totalPurchase = uiState.totalPurchaseAmount,
                        totalWinning = uiState.totalWinningAmount,
                        selectedRange = uiState.selectedStatsRange,
                        stats = uiState.stats,
                        winningTypeStats = uiState.winningTypeStats,
                        onSelectRange = viewModel::selectStatsRange,
                    )
                }
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
                    text = "균형형 생성",
                    selected = lastGeneratedSource == "균형형",
                    color = ChatGptAccent,
                    enabled = !isGenerating,
                    onClick = onGenerateChatGpt,
                    modifier = Modifier.weight(1f),
                )
                LottoSourceButton(
                    text = "분산형 생성",
                    selected = lastGeneratedSource == "분산형",
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
    val accentColor = if (sourceLabel == "분산형") GeminiAccent else ChatGptAccent
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
                    Text(text = "상위 5게임 저장")
                }
                tickets.take(5).forEachIndexed { index, ticket ->
                    Card(colors = CardDefaults.cardColors(containerColor = accentColor.copy(alpha = 0.08f))) {
                        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(text = "${index + 1}번 조합", fontWeight = FontWeight.SemiBold, color = LottoTextStrongColor)
                            LottoNumbersCard(numbers = ticket.numbers)
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
private fun SaveSection(
    roundInput: String,
    numberInputs: List<String>,
    bonusNumberInput: String,
    onRoundChange: (String) -> Unit,
    onNumberChange: (Int, String) -> Unit,
    onBonusNumberChange: (String) -> Unit,
    onSave: () -> Unit,
) {
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
            OutlinedTextField(
                value = bonusNumberInput,
                onValueChange = onBonusNumberChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("보너스 번호") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
            AppSaveButton(text = "당첨 번호 저장", onClick = onSave, modifier = Modifier.fillMaxWidth())
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
            LottoNumberRow(numbers = draw.numbers(), bonusNumber = draw.bonusNumber)
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
                        sourceTickets
                            .groupBy(LottoTicketEntity::note)
                            .entries
                            .sortedBy { entry -> extractSetNo(entry.key) ?: 1 }
                            .forEach { entry ->
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "${extractSetNo(entry.key) ?: 1}세트",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = LottoTextMutedColor,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    entry.value.sortedBy(LottoTicketEntity::id).forEach { ticket ->
                                        SavedTicketCard(ticket = ticket)
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
private fun SavedNumbersSection(
    currentRoundNo: Int?,
    currentRoundTickets: List<LottoTicketEntity>,
    historyTickets: List<LottoTicketEntity>,
    draws: List<LottoDrawEntity>,
    savedRoundQueryInput: String,
    onSavedRoundQueryChange: (String) -> Unit,
    onMarkSetPurchased: (String, String) -> Unit,
    onDeleteSet: (String, String) -> Unit,
) {
    val drawMap = remember(draws) { draws.associateBy(LottoDrawEntity::roundNo) }
    val previousRoundGroups = remember(historyTickets, currentRoundNo) {
        historyTickets
            .groupBy(::extractRoundNo)
            .filterKeys { it != null && it != currentRoundNo }
            .toList()
            .sortedByDescending { it.first }
    }
    val searchRoundNo = savedRoundQueryInput.toIntOrNull()
    val searchedTickets = when {
        searchRoundNo == null -> emptyList()
        searchRoundNo == currentRoundNo -> currentRoundTickets
        else -> previousRoundGroups.firstOrNull { it.first == searchRoundNo }?.second.orEmpty()
    }
    val pagerState = rememberPagerState(pageCount = { previousRoundGroups.size })

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppSectionCard {
            AppSectionHeader(title = if (currentRoundNo != null) "${currentRoundNo}회차 저장 번호" else "이번 회차 저장 번호")
            Text(text = "기본으로 이번 회차 저장 번호를 최대 3세트까지 바로 보여줍니다. 세트당 5게임입니다.", color = LottoTextMutedColor)
            if (currentRoundTickets.isEmpty()) {
                AppEmptyCard("이번 회차에 저장된 생성 번호가 없습니다.")
            } else {
                RoundSavedTicketDeck(
                    roundNo = currentRoundNo,
                    tickets = currentRoundTickets,
                    draw = currentRoundNo?.let(drawMap::get),
                    onMarkSetPurchased = onMarkSetPurchased,
                    onDeleteSet = onDeleteSet,
                )
            }
        }

        AppSectionCard {
            AppSectionHeader(title = "이전 회차 보기")
            OutlinedTextField(
                value = savedRoundQueryInput,
                onValueChange = onSavedRoundQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("회차 검색") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
            if (savedRoundQueryInput.isNotBlank()) {
                Text(text = "정확한 회차 번호로 검색합니다.", color = LottoTextMutedColor, style = MaterialTheme.typography.bodySmall)
                if (searchedTickets.isEmpty()) {
                    AppEmptyCard("해당 회차 저장 번호가 없습니다.")
                } else {
                    RoundSavedTicketDeck(
                        roundNo = searchRoundNo,
                        tickets = searchedTickets,
                        draw = searchRoundNo?.let(drawMap::get),
                        onMarkSetPurchased = onMarkSetPurchased,
                        onDeleteSet = onDeleteSet,
                    )
                }
            } else if (previousRoundGroups.isEmpty()) {
                AppEmptyCard("이전 회차 저장 번호가 없습니다.")
            } else {
                Text(text = "좌우로 넘겨 이전 회차를 확인하세요.", color = LottoTextMutedColor, style = MaterialTheme.typography.bodySmall)
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
                    val (roundNo, tickets) = previousRoundGroups[page]
                    RoundSavedTicketDeck(
                        roundNo = roundNo,
                        tickets = tickets,
                        draw = roundNo?.let(drawMap::get),
                        onMarkSetPurchased = onMarkSetPurchased,
                        onDeleteSet = onDeleteSet,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "${pagerState.currentPage + 1} / ${previousRoundGroups.size}",
                        color = LottoTextMutedColor,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun normalizeSourceLabel(ticket: LottoTicketEntity): String {
    val source = ticket.sourceLabel.lowercase()
    return when {
        source.contains("분산형") || source.contains("gemini") -> "분산형"
        source.contains("균형형") || source.contains("chatgpt") || source.contains("gpt") -> "균형형"
        else -> ticket.sourceLabel
    }
}

@Composable
private fun SavedTicketCard(ticket: LottoTicketEntity) {
    LottoNumbersCard(numbers = ticket.numbers())
}

@Composable
private fun RoundSavedTicketDeck(
    roundNo: Int?,
    tickets: List<LottoTicketEntity>,
    draw: LottoDrawEntity?,
    onMarkSetPurchased: (String, String) -> Unit,
    onDeleteSet: (String, String) -> Unit,
) {
    val groupedBySource = tickets.groupBy(::normalizeSourceLabel)
    val hasWinningTicket = draw?.let { winningDraw ->
        tickets.any { ticket -> calculateWinningRank(ticket, winningDraw) != null }
    } ?: false
    val roundStatusText = when {
        draw == null -> "추첨 대기"
        hasWinningTicket -> "당첨 번호 있음"
        else -> "당첨 번호 없음"
    }

    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = LottoCardColor)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (roundNo != null) "${roundNo}회차" else "회차 미지정",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = roundStatusText,
                    color = LottoTextMutedColor,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            draw?.let {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "당첨 번호", color = LottoTextMutedColor, style = MaterialTheme.typography.bodySmall)
                    LottoNumbersCard(numbers = it.numbers(), bonusNumber = it.bonusNumber)
                }
            }
            groupedBySource.forEach { (source, sourceTickets) ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(text = source, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        sourceTickets
                            .groupBy(LottoTicketEntity::note)
                            .entries
                            .sortedBy { entry -> extractSetNo(entry.key) ?: 1 }
                            .forEach { entry ->
                                val setNote = entry.key.orEmpty()
                                val setTickets = entry.value.sortedBy(LottoTicketEntity::id)
                                val isPurchased = setTickets.all(LottoTicketEntity::isPurchased)
                                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "${extractSetNo(entry.key) ?: 1}세트",
                                            fontWeight = FontWeight.SemiBold,
                                            color = LottoTextStrongColor,
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            AppSecondaryButton(
                                                text = if (isPurchased) "구매완료" else "구매",
                                                onClick = { if (setNote.isNotBlank() && !isPurchased) onMarkSetPurchased(source, setNote) },
                                                enabled = setNote.isNotBlank() && !isPurchased,
                                            )
                                            AppSecondaryButton(
                                                text = "삭제",
                                                onClick = { if (setNote.isNotBlank()) onDeleteSet(source, setNote) },
                                                enabled = setNote.isNotBlank() && !isPurchased,
                                            )
                                        }
                                    }
                                    setTickets.forEachIndexed { ticketIndex, ticket ->
                                        val winningRank = draw?.let { winningDraw -> calculateWinningRank(ticket, winningDraw) }
                                        val matchCount = draw?.numbers()?.let { winningNumbers -> ticket.numbers().count(winningNumbers::contains) }
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text(text = "${ticketIndex + 1}번 번호", fontWeight = FontWeight.SemiBold, color = LottoTextStrongColor)
                                                Text(
                                                    text = formatWinningStatusWithMatchCount(winningRank, matchCount),
                                                    color = if (winningRank != null) ChatGptAccent else LottoTextMutedColor,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                            }
                                            LottoNumbersCard(numbers = ticket.numbers())
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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PurchaseSection(onSave: (String, String, String, String, () -> Unit) -> Unit) {
    var purchaseDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var lottoType by remember { mutableStateOf("로또") }
    var amount by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = LocalDate.parse(purchaseDate).toEpochMillis())
    fun addAmount(value: Long) {
        amount = ((amount.toLongOrNull() ?: 0L) + value).toString()
    }

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
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Text(text = "구입 금액 ${formatWon(amount.toLongOrNull() ?: 0L)}", color = LottoTextStrongColor, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                AppSecondaryButton(text = "5천원", onClick = { addAmount(5_000L) }, modifier = Modifier.weight(1f))
                AppSecondaryButton(text = "1만원", onClick = { addAmount(10_000L) }, modifier = Modifier.weight(1f))
                AppSecondaryButton(text = "지우기", onClick = { amount = "" }, modifier = Modifier.weight(1f), enabled = amount.isNotBlank())
            }
        }
        OutlinedTextField(value = memo, onValueChange = { memo = it }, modifier = Modifier.fillMaxWidth(), label = { Text("메모") }, singleLine = true)
        AppSaveButton(
            text = "구입 이력 저장",
            onClick = {
                onSave(
                    purchaseDate,
                    lottoType,
                    amount,
                    memo,
                ) {
                    amount = ""
                    memo = ""
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
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
private fun WinningSection(onSave: (String, String, String, () -> Unit) -> Unit) {
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
        OutlinedTextField(value = memo, onValueChange = { memo = it }, modifier = Modifier.fillMaxWidth(), label = { Text("메모 (예: 5등)") }, singleLine = true)
        AppSaveButton(
            text = "당첨 이력 저장",
            onClick = {
                onSave(roundNo, amount, memo) {
                    roundNo = ""
                    amount = ""
                    memo = ""
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
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
private fun LottoStatsSection(
    totalPurchase: Long,
    totalWinning: Long,
    selectedRange: LottoStatsRange,
    stats: List<LottoPeriodStatRow>,
    winningTypeStats: List<LottoWinningTypeStat>,
    onSelectRange: (LottoStatsRange) -> Unit,
) {
    val net = totalWinning - totalPurchase
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AppSectionCard {
            AppSectionHeader(title = "구입/당첨 요약")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatMiniCard(title = "구입", value = formatWon(totalPurchase), modifier = Modifier.weight(1f))
                StatMiniCard(title = "당첨", value = formatWon(totalWinning), modifier = Modifier.weight(1f))
                StatMiniCard(title = "손익", value = formatWon(net), modifier = Modifier.weight(1f))
            }
        }
        AppSectionCard {
            AppSectionHeader(title = "균형형/분산형 당첨 이력")
            WinningTypeTable(winningTypeStats = winningTypeStats)
        }
        AppSectionCard {
            AppSectionHeader(title = "${selectedRange.label} 흐름")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                LottoStatsRange.entries.forEach { range ->
                    AppSelectableChip(
                        label = range.label,
                        selected = selectedRange == range,
                        onClick = { onSelectRange(range) },
                    )
                }
            }
            if (stats.isEmpty()) {
                Text(text = "통계 데이터가 없습니다.", color = LottoTextMutedColor)
            } else {
                val maxValue = stats.maxOf { maxOf(it.purchaseAmount, it.winningAmount, 1L) }.toFloat()
                stats.sortedByDescending(LottoPeriodStatRow::period).forEach { row ->
                    Text(text = formatStatsPeriod(row.period, selectedRange), fontWeight = FontWeight.SemiBold)
                    AmountBar(label = "구입 ${formatWon(row.purchaseAmount)}", ratio = row.purchaseAmount / maxValue, color = Color(0xFFB8C7D9))
                    AmountBar(label = "당첨 ${formatWon(row.winningAmount)}", ratio = row.winningAmount / maxValue, color = ChatGptAccent)
                }
            }
        }
    }
}

@Composable
private fun WinningTypeTable(winningTypeStats: List<LottoWinningTypeStat>) {
    val ranks = listOf("5등", "4등", "3등", "2등", "1등")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "유형", modifier = Modifier.weight(1.2f), fontWeight = FontWeight.Bold)
            ranks.forEach { rank ->
                Text(text = rank, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
        }
        winningTypeStats.forEach { stat ->
            val passRate = if (stat.evaluatedTicketCount == 0) 0 else (stat.stylePassCount * 100) / stat.evaluatedTicketCount
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text = stat.sourceLabel, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "적합률 ${passRate}% · 평균 ${stat.averageStyleScore}점",
                        color = LottoTextMutedColor,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                ranks.forEach { rank ->
                    Text(text = "${stat.counts[rank] ?: 0}", modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                }
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
private fun LottoNumbersCard(numbers: List<Int>, bonusNumber: Int? = null) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LottoNumberRow(numbers = numbers, bonusNumber = bonusNumber)
        }
    }
}

@Composable
private fun LottoNumberRow(numbers: List<Int>, bonusNumber: Int? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
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
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        bonusNumber?.let { bonus ->
            Text(
                text = "+",
                color = LottoTextMutedColor,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .background(lottoBallColor(bonus)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "%02d".format(bonus),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

private fun calculateWinningRank(ticket: LottoTicketEntity, draw: LottoDrawEntity): Int? {
    val winningNumbers = draw.numbers()
    val matchCount = ticket.numbers().count(winningNumbers::contains)
    val bonusMatched = draw.bonusNumber?.let(ticket.numbers()::contains) == true
    return when {
        matchCount == 6 -> 1
        matchCount == 5 && bonusMatched -> 2
        matchCount == 5 -> 3
        matchCount == 4 -> 4
        matchCount == 3 -> 5
        else -> null
    }
}

private fun formatWinningStatus(rank: Int?, matchCount: Int?): String {
    return when {
        matchCount == null -> "대기"
        rank != null -> "${rank}등 당첨"
        else -> "미당첨"
    }
}

private fun formatWinningStatusWithMatchCount(rank: Int?, matchCount: Int?): String {
    return matchCount?.let { "${formatWinningStatus(rank, it)} ${it}개" } ?: formatWinningStatus(null, null)
}

private fun formatWon(amount: Long): String {
    val sign = if (amount < 0) "-" else ""
    val formatted = NumberFormat.getNumberInstance(Locale.KOREA).format(kotlin.math.abs(amount))
    return "$sign${formatted}원"
}

private fun formatStatsPeriod(period: String, range: LottoStatsRange): String {
    return when (range) {
        LottoStatsRange.WEEKLY -> formatWeeklyPeriod(period)
        LottoStatsRange.MONTHLY -> period
        LottoStatsRange.YEARLY -> "${period}년"
    }
}

private fun formatWeeklyPeriod(period: String): String {
    val saturday = runCatching { LocalDate.parse(period) }.getOrNull() ?: return period
    val sunday = saturday.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.SUNDAY))
    return "${sunday.monthValue}/${sunday.dayOfMonth} ~ ${saturday.monthValue}/${saturday.dayOfMonth}"
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
        ?.substringBefore("|SET:")
        ?.toIntOrNull()
}

private fun extractSetNo(note: String?): Int? {
    return note
        ?.substringAfter("|SET:", missingDelimiterValue = "")
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
