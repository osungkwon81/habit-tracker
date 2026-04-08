package com.habittracker.ui.learning

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.habittracker.data.local.entity.VocabularyWordEntity

private val LearningHeroColor = androidx.compose.ui.graphics.Color(0xFF12252B)
private val LearningHeroSubColor = androidx.compose.ui.graphics.Color(0xFFF0E7D1)
private val LearningTitleColor = androidx.compose.ui.graphics.Color(0xFF182126)
private val LearningSubtitleColor = androidx.compose.ui.graphics.Color(0xFF34464D)

@Composable
fun LearningScreen(viewModel: LearningViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = LearningHeroColor)) {
                Column(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = "🧠 외국어 학습", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.White)
                    Text(text = "암기, 시험, 단어 등록, 통계를 한 화면에서 관리합니다.", color = LearningHeroSubColor, style = MaterialTheme.typography.bodyMedium)
                }
            }
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
                Text(text = message, color = MaterialTheme.colorScheme.primary)
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
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun FlashcardSection(viewModel: LearningViewModel, uiState: LearningUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = uiState.flashcardCount,
                        onValueChange = viewModel::updateFlashcardCount,
                        label = { Text("건수") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = viewModel::startFlashcard, modifier = Modifier.weight(1f)) {
                        Text("암기 시작")
                    }
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
            EmptyCard("암기 시작을 누르면 선택한 건수만큼 리스트가 생성됩니다.")
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
            Text(
                text = if (revealed || answer != null || finished) hiddenText else if (isWordBlind) "단어를 가렸습니다" else "뜻을 가렸습니다",
                style = MaterialTheme.typography.bodyMedium,
                color = if (revealed || answer != null || finished) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            answer == true -> Text(text = "암기함", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            answer == false -> Text(text = "헷갈림", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
            finished -> Text(text = "미응답", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            !revealed -> TextButton(onClick = onReveal) { Text("보기") }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onAnswer(true) }) { Text("암기") }
                    TextButton(onClick = { onAnswer(false) }) { Text("헷갈림") }
                }
            }
        }
    }
}

@Composable
private fun TestSection(viewModel: LearningViewModel, uiState: LearningUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = uiState.testCount,
                        onValueChange = viewModel::updateTestCount,
                        label = { Text("건수") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = viewModel::startTest, modifier = Modifier.weight(1f)) {
                        Text("시험 시작")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = uiState.testWeighted, onCheckedChange = viewModel::updateTestWeighted)
                    Text("가중치 반복")
                }
            }
        }

        val session = uiState.testSession
        if (session == null) {
            EmptyCard("시험 시작을 누르면 입력형 문제 리스트가 생성됩니다.")
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
                Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
            OutlinedTextField(
                value = answer,
                onValueChange = onAnswerChange,
                enabled = !finished,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("뜻 입력") },
            )
        }
    }
}

@Composable
private fun WordManageSection(viewModel: LearningViewModel, uiState: LearningUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = viewModel::updateSearchQuery,
                    label = { Text("단어 / 뜻 / 발음 검색") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = viewModel::startNewWord, modifier = Modifier.weight(1f)) { Text("단어 등록") }
                    Button(onClick = viewModel::showBulkMode, modifier = Modifier.weight(1f)) { Text("대량 등록") }
                }
                if (uiState.bulkMode) {
                    OutlinedTextField(
                        value = uiState.bulkInput,
                        onValueChange = viewModel::updateBulkInput,
                        label = { Text("단어,뜻,발음") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                    )
                    Text(text = "예: apple,사과,애플", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = viewModel::bulkInsertWords, modifier = Modifier.fillMaxWidth()) { Text("대량 등록 실행") }
                } else {
                    OutlinedTextField(value = uiState.wordInput, onValueChange = viewModel::updateWordInput, label = { Text("단어") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = uiState.meaningInput, onValueChange = viewModel::updateMeaningInput, label = { Text("뜻") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = uiState.pronunciationInput, onValueChange = viewModel::updatePronunciationInput, label = { Text("발음") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Button(onClick = viewModel::saveWord, modifier = Modifier.fillMaxWidth()) {
                        Text(if (uiState.selectedWordId == null) "단어 저장" else "단어 수정")
                    }
                }
            }
        }
        if (uiState.words.isEmpty()) {
            EmptyCard("등록된 단어가 없습니다.")
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
            TextButton(onClick = onEdit) { Text("수정") }
            TextButton(onClick = onDelete) { Text("삭제") }
        }
    }
}

@Composable
private fun StatsSection(uiState: LearningUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "학습 통계", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(text = "맞춘 횟수 ${uiState.totalCorrect}")
                Text(text = "틀린 횟수 ${uiState.totalWrong}")
                Text(text = "노출 빈도 ${uiState.totalExposure}")
                Text(text = "암기 시간 ${formatDuration(uiState.totalFlashcardStudySeconds)}")
                Text(text = "시험 시간 ${formatDuration(uiState.totalTestStudySeconds)}")
            }
        }
        if (uiState.words.isEmpty()) {
            EmptyCard("통계를 보여 줄 단어가 없습니다.")
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
    Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(text = "진행 시간 ${formatDuration(elapsedSeconds)}")
            Text(text = countText, color = MaterialTheme.colorScheme.onSurfaceVariant)
            finishedLabel?.let { Text(text = it, color = MaterialTheme.colorScheme.primary) }
            if (onPrimaryClick != null && onSecondaryClick != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onPrimaryClick, modifier = Modifier.weight(1f)) { Text(primaryLabel) }
                    Button(onClick = onSecondaryClick, modifier = Modifier.weight(1f)) { Text(secondaryLabel) }
                }
            }
        }
    }
}

@Composable
private fun CompactListCard(content: @Composable ColumnScope.() -> Unit) {
    Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), content = content)
    }
}

@Composable
private fun EmptyCard(message: String) {
    Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Text(text = message, modifier = Modifier.fillMaxWidth().padding(14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
