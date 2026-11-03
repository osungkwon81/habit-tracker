package com.habittracker.ui.lotto

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.habittracker.R
import com.habittracker.ui.components.AppEmptyCard
import com.habittracker.ui.components.AppConfirmDialog
import com.habittracker.ui.components.AppHeroCard
import com.habittracker.ui.components.AppPrimaryButton
import com.habittracker.ui.components.AppScreen
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppSectionHeader
import com.habittracker.ui.components.AppSecondaryButton
import com.habittracker.ui.components.AppSaveButton
import com.habittracker.ui.components.AppStatusText
import com.habittracker.ui.components.AppLoadingCard
import com.habittracker.ui.components.AppSupportText
import com.habittracker.ui.components.AppMenuGroup
import com.habittracker.ui.components.AppSectionNavigation
import com.habittracker.ui.components.LocalAppNavigationGuard
import java.time.format.DateTimeFormatter

private val PensionGenerationTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss")

@Composable
fun PensionLotteryGeneratorScreen(
    viewModel: PensionLotteryGeneratorViewModel,
    onBack: () -> Unit,
    initialSection: String = "NUMBERS",
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navigationGuard = LocalAppNavigationGuard.current
    SideEffect { navigationGuard.hasUnsavedChanges = uiState.hasUnsavedGeneration }
    DisposableEffect(navigationGuard) { onDispose { navigationGuard.hasUnsavedChanges = false } }
    com.habittracker.ui.components.AppActionNotice(uiState.statusMessage, viewModel::clearStatusMessage)
    var deleteTarget by remember { mutableStateOf<PensionLotteryGenerationHistory?>(null) }
    var backupDeleteTarget by remember { mutableStateOf<PensionLotteryBackupNumber?>(null) }
    var showExperiments by rememberSaveable { mutableStateOf(false) }
    var showPerformance by rememberSaveable { mutableStateOf(false) }
    var section by rememberSaveable { mutableStateOf(initialSection) }
    var selectedGenerationPattern by rememberSaveable { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(section) {
        if (section == "IMPROVEMENT") viewModel.refreshAutoImprovement()
    }
    val autoImprovement = if (section == "IMPROVEMENT") viewModel.autoImprovementState.collectAsStateWithLifecycle().value else null
    val performance = if (section == "IMPROVEMENT" && showPerformance) viewModel.performanceUiState.collectAsStateWithLifecycle().value else null

    deleteTarget?.let { history ->
        AppConfirmDialog(
            title = "저장 번호 숨기기",
            message = "${history.targetRoundNo?.let { "${it}회차 · " }.orEmpty()}${history.numbers.joinToString { it.winningNumber }}를 목록에서 숨깁니다. 평가 기록은 유지됩니다.",
            confirmText = "숨기기",
            onConfirm = {
                viewModel.deleteGeneration(history.generationId)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }

    backupDeleteTarget?.let { backup ->
        AppConfirmDialog(
            title = "예비 번호 삭제",
            message = "${backup.number.winningNumber} 예비 번호를 삭제합니다.",
            confirmText = "삭제",
            onConfirm = {
                viewModel.deleteBackup(backup.generationId)
                backupDeleteTarget = null
            },
            onDismiss = { backupDeleteTarget = null },
        )
    }

    AppScreen {
        item {
            AppHeroCard(
                title = "연금 추천 번호",
                description = "번호 생성·저장 이력·자동 개선을 관리합니다.",
                status = "최신 저장 ${uiState.latestRoundNo ?: "-"}회",
            )
        }
        item {
            AppSectionNavigation(
                title = "연금복권",
                groups = listOf(
                    AppMenuGroup("추천 번호", listOf("NUMBERS" to "번호 생성", "HISTORY" to "저장 이력", "IMPROVEMENT" to "자동 개선")),
                    AppMenuGroup("복권 관리", listOf("MANAGEMENT" to "구입·당첨·추첨 결과")),
                ),
                selectedKey = section,
                onSelect = { key ->
                    if (key == "MANAGEMENT") onBack() else {
                        section = key
                    }
                },
            )
        }
        if (section == "IMPROVEMENT" && autoImprovement != null) item {
            LotteryAutoImprovementSection(
                product = com.habittracker.data.lotto.LotteryProduct.PENSION_720,
                state = autoImprovement,
                onRefresh = { viewModel.refreshAutoImprovement() },
                onEnabledChange = { viewModel.setAutoImprovementEnabled(it) },
                onRollback = { viewModel.rollbackAutoImprovement() },
            )
        }
        if (section == "IMPROVEMENT") item {
            AppSecondaryButton(text = if (showExperiments) "평가 이력 접기" else "평가 이력 보기", onClick = {
                showExperiments = !showExperiments
                if (showExperiments) viewModel.refreshExperiments()
            })
        }
        if (section == "IMPROVEMENT" && showExperiments) item { PensionAutomaticHistory(viewModel) }
        if (section == "NUMBERS") {
        if (uiState.hasGenerationConditionChanged) {
            item {
                AppSectionCard {
                    AppSectionHeader(
                        title = "생성 입력 확인 필요",
                        subtitle = "현재 입력·조건이 생성 당시와 다르거나 과거 생성 입력을 확인할 수 없습니다.",
                    )
                    AppStatusText("조건이 바뀐 번호만 개별 재생성해 주세요. 다른 번호는 유지됩니다.")
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    PensionLotteryNumberPattern.entries.forEach { pattern ->
                        val isSelected = selectedGenerationPattern == pattern.name
                        val label = if (pattern == PensionLotteryNumberPattern.APPEARED) "출현형" else "미출현"
                        val onGenerate = {
                            selectedGenerationPattern = pattern.name
                            viewModel.generate(pattern)
                        }
                        val buttonModifier = Modifier.weight(1f).semantics { selected = isSelected }
                        val enabled = uiState.canGenerate && !uiState.isGenerating && !uiState.isSaving
                        if (isSelected) AppPrimaryButton(label, onGenerate, modifier = buttonModifier, enabled = enabled)
                        else AppSecondaryButton(label, onGenerate, modifier = buttonModifier, enabled = enabled)
                    }
                }
                AppSupportText(when {
                    uiState.isGenerating -> "번호를 생성하고 있습니다."
                    uiState.isSaving -> "저장 내용을 반영하고 있습니다."
                    else -> "한 번 누르면 1개 생성 · 1~5조 동일 번호 구매"
                })
            }
        }
        if (uiState.generatedNumbers.isEmpty() && uiState.currentRoundHistory == null) {
            item {
                AppEmptyCard(
                    if (uiState.canGenerate) {
                        "원하는 유형을 눌러 한 개씩 생성해 주세요."
                    } else {
                        "번호 생성을 위해 저장된 당첨번호가 17회 이상 필요합니다."
                    },
                )
            }
        } else if (uiState.generatedNumbers.isNotEmpty()) {
            item {
                AppSectionHeader(
                    title = "저장 전 번호 ${uiState.generatedNumbers.size}개",
                    subtitle = when {
                        uiState.hasGenerationConditionChanged -> "입력 확인 필요 · 재생성 필요"
                        else -> "출현형 ${uiState.generatedNumbers.count { it.type.pattern == PensionLotteryNumberPattern.APPEARED }}개 · 미출현 혼합형 ${uiState.generatedNumbers.count { it.type.pattern == PensionLotteryNumberPattern.COLD_MIX }}개"
                    },
                )
            }
            items(uiState.generatedNumbers, key = { result -> result.winningNumber }) { result ->
                PensionLotteryGeneratedNumberCard(
                    result = result,
                    isRegenerating = uiState.regeneratingNumber == result.winningNumber,
                    canRegenerate = result.generationSnapshot?.targetRoundNo?.let { it in uiState.regeneratableRounds } == true,
                    actionEnabled = uiState.canGenerate && !uiState.isGenerating && !uiState.isSaving,
                    needsRegeneration = result.winningNumber in uiState.outdatedNumbers,
                    discardEnabled = !uiState.isGenerating && !uiState.isSaving,
                    onRegenerate = { viewModel.regenerate(result.winningNumber) },
                    onSave = { viewModel.saveGeneratedNumber(result.winningNumber) },
                    onDiscard = { viewModel.discardGeneratedNumber(result.winningNumber) },
                )
            }
        }
        uiState.currentRoundHistory?.let { round ->
            item {
                PensionLotteryRoundHistorySection(
                    round = round, uiState = uiState,
                    onRegenerate = viewModel::regenerateSavedNumber,
                    onDelete = { deleteTarget = it },
                    onGenerateBackup = viewModel::generateBackup,
                    onDeleteBackup = { backupDeleteTarget = it },
                )
                AppSecondaryButton("전체 저장 이력 보기", { section = "HISTORY" }, modifier = Modifier.fillMaxWidth())
            }
        }
        item { PensionLotteryGeneratorRuleCard(uiState) }
        }
        if (section == "IMPROVEMENT") item {
            AppSecondaryButton(
                text = if (showPerformance) "수집 자료·추천 성과 접기" else "수집 자료·추천 성과 보기",
                onClick = { showPerformance = !showPerformance },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (section == "IMPROVEMENT" && showPerformance) {
            if (performance == null) {
                item { AppLoadingCard("저장된 평가 자료를 집계하고 있습니다.") }
            } else {
                item { PensionLotteryPerformanceOverview(performance) }
                items(performance.settings, key = { "${it.generationVersion}:${it.configHash}" }) { setting ->
                    PensionLotterySettingPerformanceSection(setting)
                }
            }
        }
        if (section == "HISTORY") {
        if (uiState.generationHistory.isNotEmpty()) {
            item {
                AppSectionHeader(
                    title = "저장한 번호",
                )
            }
            items(uiState.roundHistory, key = { it.targetRoundNo?.toString() ?: "unknown" }) { round ->
                PensionLotteryRoundHistorySection(
                    round = round, uiState = uiState,
                    allowManagement = true,
                    onDelete = { deleteTarget = it },
                    onGenerateBackup = viewModel::generateBackup,
                    onDeleteBackup = { backupDeleteTarget = it },
                )
            }
        } else {
            item { AppEmptyCard("저장한 생성 번호가 없습니다.") }
        }
        }
    }
}

@Composable
private fun PensionLotteryGeneratorRuleCard(uiState: PensionLotteryGeneratorUiState) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    AppSectionCard {
        AppSecondaryButton(text = if (showDetails) "생성 조건 접기" else "생성 조건", onClick = { showDetails = !showDetails })
        if (showDetails) {
        Text("끝자리 후보 · 출현 ${uiState.appearedLastDigitCandidates.joinToString("·").ifEmpty { "계산 대기" }} / 미출현 ${uiState.coldMixLastDigitCandidates.joinToString("·").ifEmpty { "계산 대기" }}")
        AppSupportText("끝자리는 0~9 모두에서 가중 추출합니다. 출현형은 최근 16회 25%·장기 156회 75%의 기존 혼합 점수, 미출현형은 기존 전체 이력 빈도를 사용하며 모든 숫자의 가중치는 최소 1입니다. 앞자리의 미출현 구성·점수 구간 등 필수 조건에 따라 최종 선택 빈도는 달라질 수 있습니다.")
        GeneratorRuleRow(
            label = "16주 점수 구간",
            value = uiState.targetScoreBand?.let { band ->
                "$band · ${uiState.targetScoreBandDrawCount}회"
            } ?: "계산 대기",
        )
        GeneratorRuleRow(
            label = "동일 숫자 유형",
            value = uiState.targetDuplicateLabel?.let { label ->
                "$label · 최근 16주 ${uiState.targetDuplicateDrawCount}회"
            } ?: "계산 대기",
        )
        GeneratorRuleRow(
            label = "0점 자리 수",
            value = uiState.targetZeroScoreCount?.let { zeroScoreCount ->
                "${zeroScoreCount}개 · ${uiState.targetZeroScoreDrawCount}회"
            } ?: "계산 대기",
        )
        AppSupportText("같은 회차의 생성·저장 번호에서 덜 사용한 끝자리를 우선합니다. 반대 유형이 있으면 가장 최근 번호와 끝자리를 다르게 하고 최소 3자리 차이를 유지합니다.")
        }
    }
}

@Composable
private fun PensionLotterySavedConditionsCard(uiState: PensionLotteryGeneratorUiState, result: PensionLotteryGeneratedNumber) {
    val snapshot = result.generationSnapshot
    val conditions = uiState.savedGenerationConditions
    AppSectionCard {
        AppSectionHeader(
            title = snapshot?.let { "${it.targetRoundNo}회차 생성 당시 조건" } ?: "생성 당시 조건",
            subtitle = if (snapshot?.isIndividual == true) "개별 저장한 번호의 회차·조건입니다." else "저장한 번호는 유지하며, 예비 번호도 같은 회차·조건을 사용합니다.",
        )
        if (conditions != null) {
            Text("분석 기준 ${conditions.analysisThroughRound}회 · 점수 ${conditions.scoreBand} · 동일 숫자 ${conditions.duplicateLabel} · 0점 자리 ${conditions.zeroScoreCount}개")
            Text("끝자리 후보: 출현 ${conditions.appearedLastDigits.joinToString("·")} · 혼합 ${conditions.coldMixLastDigits.joinToString("·")}")
        } else {
            AppSupportText("생성 당시 입력·설정을 재현할 수 없습니다. 번호별 저장된 점수는 유지하며 예비 번호 생성은 제한합니다.")
        }
        if (snapshot?.isIndividual != true) uiState.backupUnavailableReason?.let { AppSupportText("예비 번호: $it") }
    }
}

@Composable
private fun GeneratorRuleRow(
    label: String,
    value: String,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun PensionLotteryGeneratedNumberCard(
    result: PensionLotteryGeneratedNumber,
    isRegenerating: Boolean,
    canRegenerate: Boolean,
    actionEnabled: Boolean,
    needsRegeneration: Boolean,
    discardEnabled: Boolean,
    onRegenerate: () -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
    AppSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppSectionHeader(
                title = if (result.type.pattern == PensionLotteryNumberPattern.APPEARED) "출현형" else "미출현",
                modifier = Modifier.weight(1f),
            )
            if (canRegenerate) PensionRegenerateIcon(
                contentDescription = "${result.type.pattern.label} 다시 생성",
                isLoading = isRegenerating, enabled = actionEnabled, onClick = onRegenerate,
            )
        }
        PensionLotteryNumberRow(
            winningNumber = result.winningNumber,
            highlightedPositions = result.coldPositions,
        )
        if (result.replacesSavedNumberId != null) AppSupportText("저장하면 원래 번호를 교체합니다.")
        if (needsRegeneration) AppSupportText(if (canRegenerate) "분석 입력·조건이 바뀌었습니다. 이 번호를 재생성한 뒤 저장해 주세요."
            else "이 번호는 재생성할 수 없습니다. 새 번호를 생성해 주세요.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            AppSaveButton(
                text = "저장",
                onClick = onSave,
                modifier = Modifier.weight(1f),
                enabled = actionEnabled && !needsRegeneration,
            )
            AppSecondaryButton("빼기", onDiscard, enabled = discardEnabled, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun PensionLotteryNumberExplanation(result: PensionLotteryGeneratedNumber) {
    AppSupportText(result.type.description)
    Text("${result.totalScore}점 · ${result.scoreBand} 구간 · 동일 숫자 ${result.duplicateLabel}", style = MaterialTheme.typography.bodyMedium)
    AppSupportText("자리별 점수 ${result.digitScores.joinToString(" · ")}")
    if (result.coldPositions.isNotEmpty()) AppSupportText("미출현 적용 ${result.coldPositions.sorted().joinToString { generatorPositionLabel(it) }}")
    if (result.coldPriorityScores.isNotEmpty()) AppSupportText("미출현 점수 ${result.coldPriorityScores.entries.sortedBy { it.key }.joinToString { "${generatorPositionLabel(it.key)} ${it.value}점" }}")
    result.generationSnapshot?.let { AppSupportText("대상 ${it.targetRoundNo}회차 · 분석 기준 ${it.analysisThroughRound}회차") }
}

@Composable
private fun PensionLotteryBackupNumberCard(
    backup: PensionLotteryBackupNumber,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("예비 번호", fontWeight = FontWeight.Bold)
            PensionLotteryNumberRow(backup.number.winningNumber, compact = true)
            AppSecondaryButton(
                text = "예비 번호 삭제",
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PensionRegenerateIcon(
    contentDescription: String,
    isLoading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(42.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.primary,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(19.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_stock_refresh),
                    contentDescription = contentDescription,
                    modifier = Modifier.size(21.dp),
                )
            }
        }
    }
}

@Composable
private fun PensionLotteryRoundHistorySection(
    round: PensionLotteryRoundHistory,
    uiState: PensionLotteryGeneratorUiState,
    allowManagement: Boolean = false,
    onRegenerate: ((String, String) -> Unit)? = null,
    onDelete: (PensionLotteryGenerationHistory) -> Unit,
    onGenerateBackup: (PensionLotteryGenerationType) -> Unit,
    onDeleteBackup: (PensionLotteryBackupNumber) -> Unit,
) {
    val numbersByPattern = remember(round) {
        round.generations.flatMap { history -> history.numbers.map { history to it } }
            .groupBy { it.second.type.pattern }
    }
    AppSectionCard {
        AppSectionHeader(
            title = round.targetRoundNo?.let { "${it}회차 저장 번호" } ?: "회차 미확인 저장 번호",
            subtitle = "저장 완료 ${round.generations.sumOf { it.numbers.size }}개",
        )
        PensionLotteryNumberPattern.entries.forEach { pattern ->
            val numbers = numbersByPattern[pattern].orEmpty()
            if (numbers.isNotEmpty()) {
                Text("${if (pattern == PensionLotteryNumberPattern.APPEARED) "출현형" else "미출현"} · ${numbers.size}개", style = MaterialTheme.typography.titleSmall)
                numbers.forEach { (history, result) -> key(history.generationId, result.winningNumber) {
                    var expanded by rememberSaveable { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PensionLotteryNumberRow(result.winningNumber, compact = true, modifier = Modifier.weight(1f))
                        if (onRegenerate != null && round.targetRoundNo?.let { it in uiState.regeneratableRounds } == true) AppSecondaryButton("재생성", { onRegenerate(history.generationId, result.winningNumber) },
                            enabled = uiState.canGenerate && !uiState.isGenerating && !uiState.isSaving, compact = true)
                        AppSecondaryButton(if (expanded) "접기" else "해설", { expanded = !expanded }, compact = true)
                    }
                    if (expanded) PensionLotteryNumberExplanation(result)
                } }
            }
        }
        if (allowManagement) {
            var managing by rememberSaveable(round.targetRoundNo) { mutableStateOf(false) }
            AppSecondaryButton(if (managing) "관리 접기" else "번호 관리", { managing = !managing })
            if (managing) round.generations.forEach { history ->
                Text(history.numbers.joinToString(" · ") { it.winningNumber }, fontWeight = FontWeight.SemiBold)
                AppSupportText("${history.generatedAt.format(PensionGenerationTimeFormatter)} 생성")
                AppSecondaryButton(
                    text = if (history.numbers.size == 1) "이 번호 숨기기" else "함께 저장한 ${history.numbers.size}개 숨기기",
                    onClick = { onDelete(history) }, enabled = !uiState.isGenerating && !uiState.isSaving,
                )
                val latest = history.generationId == uiState.generationHistory.firstOrNull()?.generationId
                if (latest) PensionLotterySavedConditionsCard(uiState, history.numbers.first())
                history.numbers.forEach { result ->
                    val backup = uiState.backupNumbers.firstOrNull {
                        it.parentGenerationId == history.generationId && it.number.type == result.type
                    }
                    if (backup != null) PensionLotteryBackupNumberCard(backup, onDelete = { onDeleteBackup(backup) })
                    else if (latest && round.targetRoundNo?.let { it in uiState.regeneratableRounds } == true && result.generationSnapshot?.isIndividual != true) AppSecondaryButton(
                        text = if (uiState.generatingBackupType == result.type) "예비 번호 생성 중" else "${result.type.label} 예비 번호",
                        onClick = { onGenerateBackup(result.type) },
                        enabled = !uiState.hasUnsavedGeneration && uiState.backupUnavailableReason == null && !uiState.isGenerating && !uiState.isSaving,
                    )
                }
            }
        }
    }
}

private fun generatorPositionLabel(position: Int): String = when (position) {
    0 -> "십만 자리"
    1 -> "만 자리"
    2 -> "천 자리"
    3 -> "백 자리"
    4 -> "십 자리"
    else -> "일 자리"
}
