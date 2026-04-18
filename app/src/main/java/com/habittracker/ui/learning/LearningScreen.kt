package com.habittracker.ui.learning

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.habittracker.data.local.entity.VocabularyWordEntity
import com.habittracker.ui.components.AppButtonRow
import com.habittracker.ui.components.AppEmptyCard
import com.habittracker.ui.components.AppHeroCard
import com.habittracker.ui.components.AppNoticeDialog
import com.habittracker.ui.components.AppPrimaryButton
import com.habittracker.ui.components.AppScreen
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppSelectableChip
import com.habittracker.ui.components.AppStatusText
import com.habittracker.ui.components.AppSupportText
import com.habittracker.ui.components.AppTextField
import com.habittracker.ui.components.actionNoticeDialogTitle
import com.habittracker.ui.components.shouldShowActionNoticeDialog

private val LearningHeroColor = androidx.compose.ui.graphics.Color(0xFFFFFFFF)
private val LearningHeroSubColor = androidx.compose.ui.graphics.Color(0xFF5C6661)
private val LearningTitleColor = androidx.compose.ui.graphics.Color(0xFF171C19)
private val LearningSubtitleColor = androidx.compose.ui.graphics.Color(0xFF5C6661)

@Composable
fun LearningScreen(viewModel: LearningViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var noticeMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uiState.statusMessage) {
        val message = uiState.statusMessage.orEmpty()
        if (message.shouldShowActionNoticeDialog()) {
            noticeMessage = message
        }
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
                title = "외국어 학습",
                description = null,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                LearningTabChip(label = "암기", selected = uiState.selectedTab == "flashcard") { viewModel.selectTab("flashcard") }
                LearningTabChip(label = "시험", selected = uiState.selectedTab == "test") { viewModel.selectTab("test") }
                LearningTabChip(label = "단어", selected = uiState.selectedTab == "word") { viewModel.selectTab("word") }
                LearningTabChip(label = "통계", selected = uiState.selectedTab == "stats") { viewModel.selectTab("stats") }
            }
        }
        item {
            uiState.statusMessage?.let { message ->
                AppStatusText(message)
            }
        }
        item {
            when (uiState.selectedTab) {
                "flashcard" -> FlashcardSection(viewModel = viewModel, uiState = uiState)
                "test" -> TestSection(viewModel = viewModel, uiState = uiState)
                "word" -> WordManageSection(viewModel = viewModel, uiState = uiState)
                else -> StatsSection(uiState = uiState)
            }
        }
    }
}

@Composable
private fun LearningTabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    AppSelectableChip(label = label, selected = selected, onClick = onClick)
}

@Composable
private fun FlashcardSection(viewModel: LearningViewModel, uiState: LearningUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppSectionCard {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    AppTextField(value = uiState.flashcardCount, onValueChange = viewModel::updateFlashcardCount, label = "건수", singleLine = true, modifier = Modifier.weight(1f))
                    AppPrimaryButton(text = "암기 시작", onClick = viewModel::startFlashcard, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = uiState.flashcardShowPronunciation, onCheckedChange = viewModel::updateFlashcardShowPronunciation)
                    Text("발음 보기")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LearningTabChip(label = "단어 가리기", selected = uiState.flashcardBlindMode == BlindMode.WORD) {
                        viewModel.updateFlashcardBlindMode(BlindMode.WORD)
                    }
                    LearningTabChip(label = "뜻 가리기", selected = uiState.flashcardBlindMode == BlindMode.MEANING) {
                        viewModel.updateFlashcardBlindMode(BlindMode.MEANING)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = uiState.flashcardWeighted, onCheckedChange = viewModel::updateFlashcardWeighted)
                    Text("가중치 반복")
                }
            }
        }

        val session = uiState.flashcardSession
        if (session == null) {
            AppEmptyCard("암기 시작을 누르면 선택한 건수만큼 리스트가 생성됩니다.")
        } else {
            SessionHeaderCard(
                title = "암기 진행",
                elapsedSeconds = uiState.flashcardElapsedSeconds,
                countText = "${session.answers.size} / ${session.words.size} 응답",
                finishedLabel = session.finishedLabel,
                onPrimaryClick = if (session.finished) null else viewModel::finishFlashcard,
                primaryLabel = "암기 완료",
                onSecondaryClick = if (session.finished) null else viewModel::endFlashcard,
                secondaryLabel = "종료",
            )
            CompactListCard {
                session.words.forEachIndexed { index, word ->
                    FlashcardRow(
                        index = index,
                        word = word,
                        blindMode = uiState.flashcardBlindMode,
                        showPronunciation = uiState.flashcardShowPronunciation,
                        revealed = index in session.revealedIndices,
                        answer = session.answers[index],
                        finished = session.finished,
                        onReveal = { viewModel.revealFlashcard(index) },
                        onAnswer = { correct -> viewModel.answerFlashcard(index, correct) },
                    )
                    if (index != session.words.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun FlashcardRow(
    index: Int,
    word: VocabularyWordEntity,
    blindMode: BlindMode,
    showPronunciation: Boolean,
    revealed: Boolean,
    answer: Boolean?,
    finished: Boolean,
    onReveal: () -> Unit,
    onAnswer: (Boolean) -> Unit,
) {
    val isWordBlind = blindMode == BlindMode.WORD
    val promptText = if (isWordBlind) word.meaning else word.word
    val hiddenText = if (isWordBlind) word.word else word.meaning

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "${index + 1}", modifier = Modifier.width(24.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = promptText, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = LearningTitleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showPronunciation && !word.pronunciation.isNullOrBlank()) {
                Text(text = word.pronunciation.orEmpty(), style = MaterialTheme.typography.bodySmall, color = LearningSubtitleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (revealed || answer != null || finished) {
                Text(
                    text = hiddenText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.66f)
                        .background(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                        .height(18.dp),
                )
            }
        }
        when {
            answer == true -> Text(text = "암기함", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            answer == false -> Text(text = "헷갈림", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
            finished -> Text(text = "미응답", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            !revealed -> AppSelectableChip(label = "보기", selected = false, onClick = onReveal)
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppSelectableChip(label = "암기", selected = false, onClick = { onAnswer(true) })
                    AppSelectableChip(label = "헷갈림", selected = false, onClick = { onAnswer(false) })
                }
            }
        }
    }
}

@Composable
private fun TestSection(viewModel: LearningViewModel, uiState: LearningUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppSectionCard {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    AppTextField(value = uiState.testCount, onValueChange = viewModel::updateTestCount, label = "건수", singleLine = true, modifier = Modifier.weight(1f))
                    AppPrimaryButton(text = "시험 시작", onClick = viewModel::startTest, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = uiState.testWeighted, onCheckedChange = viewModel::updateTestWeighted)
                    Text("가중치 반복")
                }
            }
        }

        val session = uiState.testSession
        if (session == null) {
            AppEmptyCard("시험 시작을 누르면 입력형 문제 리스트가 생성됩니다.")
        } else {
            SessionHeaderCard(
                title = "시험 진행",
                elapsedSeconds = uiState.testElapsedSeconds,
                countText = "${session.answers.values.count { it.isNotBlank() }} / ${session.questions.size} 입력",
                finishedLabel = session.finishedLabel,
                onPrimaryClick = if (session.finished) null else viewModel::finishTest,
                primaryLabel = "시험 완료",
                onSecondaryClick = if (session.finished) null else viewModel::endTest,
                secondaryLabel = "종료",
            )
            CompactListCard {
                session.questions.forEachIndexed { index, word ->
                    TestRow(
                        index = index,
                        word = word,
                        answer = session.answers[index].orEmpty(),
                        result = session.results[index],
                        finished = session.finished,
                        onAnswerChange = { value -> viewModel.updateTestAnswer(index, value) },
                    )
                    if (index != session.questions.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }
            if (session.finished) {
                AppSectionCard {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = "시험 결과", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(text = "정답 ${session.results.values.count { it == true }}개 / 전체 ${session.questions.size}개")
                    }
                }
            }
        }
    }
}

@Composable
private fun TestRow(
    index: Int,
    word: VocabularyWordEntity,
    answer: String,
    result: Boolean?,
    finished: Boolean,
    onAnswerChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "${index + 1}", modifier = Modifier.width(24.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = word.word, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = LearningTitleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!word.pronunciation.isNullOrBlank()) {
                Text(text = word.pronunciation.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (finished) {
                Text(
                    text = when (result) {
                        true -> "정답: ${word.meaning}"
                        false -> "오답, 정답: ${word.meaning}"
                        null -> "미응답"
                    },
                    color = when (result) {
                        true -> MaterialTheme.colorScheme.primary
                        false -> MaterialTheme.colorScheme.error
                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            AppTextField(value = answer, onValueChange = onAnswerChange, enabled = !finished, modifier = Modifier.fillMaxWidth(), singleLine = true, label = "뜻 입력")
        }
    }
}

@Composable
private fun WordManageSection(viewModel: LearningViewModel, uiState: LearningUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppSectionCard {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTextField(value = uiState.searchQuery, onValueChange = viewModel::updateSearchQuery, label = "단어 / 뜻 / 발음 검색", singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    AppSelectableChip(label = "일반 입력", selected = !uiState.bulkMode, onClick = viewModel::startNewWord, modifier = Modifier.weight(1f))
                    AppSelectableChip(label = "대량 입력", selected = uiState.bulkMode, onClick = viewModel::showBulkMode, modifier = Modifier.weight(1f))
                }
                if (uiState.bulkMode) {
                    AppTextField(value = uiState.bulkInput, onValueChange = viewModel::updateBulkInput, label = "단어,뜻,발음", minLines = 4)
                    AppSupportText("예: apple,사과,애플")
                    AppPrimaryButton(text = "대량 등록 실행", onClick = viewModel::bulkInsertWords, modifier = Modifier.fillMaxWidth())
                } else {
                    AppTextField(value = uiState.wordInput, onValueChange = viewModel::updateWordInput, label = "단어", singleLine = true)
                    AppTextField(value = uiState.meaningInput, onValueChange = viewModel::updateMeaningInput, label = "뜻", singleLine = true)
                    AppTextField(value = uiState.pronunciationInput, onValueChange = viewModel::updatePronunciationInput, label = "발음", singleLine = true)
                    AppPrimaryButton(text = if (uiState.selectedWordId == null) "단어 저장" else "단어 수정", onClick = viewModel::saveWord, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        if (uiState.words.isEmpty()) {
            AppEmptyCard("등록된 단어가 없습니다.")
        } else {
            CompactListCard {
                uiState.words.forEachIndexed { index, word ->
                    VocabularyRow(word = word, onEdit = { viewModel.editWord(word) }, onDelete = { viewModel.deleteWord(word.id) })
                    if (index != uiState.words.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun VocabularyRow(word: VocabularyWordEntity, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = word.word, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text = word.meaning, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!word.pronunciation.isNullOrBlank()) {
                Text(text = word.pronunciation.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            AppSelectableChip(label = "수정", selected = false, onClick = onEdit)
            AppSelectableChip(label = "삭제", selected = false, onClick = onDelete)
        }
    }
}

@Composable
private fun StatsSection(uiState: LearningUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppSectionCard {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "학습 통계", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(text = "맞춘 횟수 ${uiState.totalCorrect}")
                Text(text = "틀린 횟수 ${uiState.totalWrong}")
                Text(text = "노출 빈도 ${uiState.totalExposure}")
                Text(text = "암기 시간 ${formatDuration(uiState.totalFlashcardStudySeconds)}")
                Text(text = "시험 시간 ${formatDuration(uiState.totalTestStudySeconds)}")
            }
        }
        if (uiState.words.isEmpty()) {
            AppEmptyCard("통계를 보여 줄 단어가 없습니다.")
        } else {
            CompactListCard {
                uiState.words.sortedByDescending { it.exposureCount }.forEachIndexed { index, word ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(text = word.word, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(text = word.meaning, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(
                            text = "정답 ${word.correctCount} / 오답 ${word.wrongCount} / 노출 ${word.exposureCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (index != uiState.words.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionHeaderCard(
    title: String,
    elapsedSeconds: Int,
    countText: String,
    finishedLabel: String?,
    onPrimaryClick: (() -> Unit)?,
    primaryLabel: String,
    onSecondaryClick: (() -> Unit)?,
    secondaryLabel: String,
) {
    AppSectionCard {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(text = "진행 시간 ${formatDuration(elapsedSeconds)}")
            Text(text = countText, color = MaterialTheme.colorScheme.onSurfaceVariant)
            finishedLabel?.let { Text(text = it, color = MaterialTheme.colorScheme.primary) }
            if (onPrimaryClick != null && onSecondaryClick != null) {
                AppButtonRow(primaryText = primaryLabel, onPrimaryClick = onPrimaryClick, secondaryText = secondaryLabel, onSecondaryClick = onSecondaryClick)
            }
        }
    }
}

@Composable
private fun CompactListCard(content: @Composable ColumnScope.() -> Unit) {
    AppSectionCard {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

private fun formatDuration(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) {
        "${minutes}분 ${seconds}초"
    } else {
        "${seconds}초"
    }
}
