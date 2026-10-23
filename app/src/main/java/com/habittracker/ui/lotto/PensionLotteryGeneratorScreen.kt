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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
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
import java.time.format.DateTimeFormatter

private val PensionGenerationTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss")

@Composable
fun PensionLotteryGeneratorScreen(
    viewModel: PensionLotteryGeneratorViewModel,
    onBack: () -> Unit,
    initialSection: String = "NUMBERS",
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    com.habittracker.ui.components.AppActionNotice(uiState.statusMessage, viewModel::clearStatusMessage)
    var deleteTarget by remember { mutableStateOf<PensionLotteryGenerationHistory?>(null) }
    var backupDeleteTarget by remember { mutableStateOf<PensionLotteryBackupNumber?>(null) }
    var showExperiments by rememberSaveable { mutableStateOf(false) }
    var showPerformance by rememberSaveable { mutableStateOf(false) }
    var section by rememberSaveable { mutableStateOf(initialSection) }
    androidx.compose.runtime.LaunchedEffect(section) {
        if (section == "IMPROVEMENT") viewModel.refreshAutoImprovement()
    }
    val autoImprovement = if (section == "IMPROVEMENT") viewModel.autoImprovementState.collectAsStateWithLifecycle().value else null
    val performance = if (section == "IMPROVEMENT" && showPerformance) viewModel.performanceUiState.collectAsStateWithLifecycle().value else null

    deleteTarget?.let { history ->
        AppConfirmDialog(
            title = "생성 히스토리 삭제",
            message = "${history.generatedAt.format(PensionGenerationTimeFormatter)} 생성 번호를 삭제합니다.",
            confirmText = "삭제",
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
            message = "${backup.number.groupNo}조 ${backup.number.winningNumber} 예비 번호를 삭제합니다.",
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
        item {
            PensionLotteryGeneratorRuleCard(uiState)
        }
        if (uiState.hasGenerationConditionChanged) {
            item {
                AppSectionCard {
                    AppSectionHeader(
                        title = "생성 입력 확인 필요",
                        subtitle = "현재 입력·조건이 생성 당시와 다르거나 과거 생성 입력을 확인할 수 없습니다.",
                    )
                    AppStatusText("현재 조건으로 네 번호를 다시 생성해 주세요.")
                }
            }
        }
        if (!uiState.hasUnsavedGeneration || uiState.hasGenerationConditionChanged) {
            item {
                AppPrimaryButton(
                    text = when {
                        uiState.isGenerating -> "번호 생성 중"
                        uiState.hasGenerationConditionChanged -> "입력 확인 필요 · 번호 새로 생성"
                        uiState.generatedNumbers.isEmpty() -> "이번 주 고정 번호 4개 생성"
                        else -> "현재 조건으로 새 번호 4개 생성"
                    },
                    onClick = viewModel::generate,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.canGenerate && !uiState.isGenerating && !uiState.isSaving,
                )
            }
        }
        if (uiState.generatedNumbers.isEmpty()) {
            item {
                AppEmptyCard(
                    if (uiState.canGenerate) {
                        "출현형 2개와 미출현 혼합형 2개를 생성해 주세요."
                    } else {
                        "번호 생성을 위해 저장된 당첨번호가 17회 이상 필요합니다."
                    },
                )
            }
        } else {
            item {
                AppSectionHeader(
                    title = if (uiState.hasUnsavedGeneration) "생성한 추천 번호" else "저장한 고정 번호",
                    subtitle = when {
                        uiState.hasGenerationConditionChanged -> "입력 확인 필요 · 재생성 필요"
                        uiState.hasUnsavedGeneration -> "방금 생성됨 · 저장 전"
                        else -> uiState.generationHistory.firstOrNull()?.generatedAt?.let { generatedAt ->
                            "${generatedAt.format(PensionGenerationTimeFormatter)} · 고정 저장"
                        }
                    },
                )
            }
            if (!uiState.hasUnsavedGeneration) {
                item { PensionLotterySavedConditionsCard(uiState) }
            }
            if (uiState.hasUnsavedGeneration) {
                item {
                    AppSaveButton(
                        text = when {
                            uiState.isSaving -> "고정 번호 저장 중"
                            uiState.hasGenerationConditionChanged -> "조건 변경 · 재생성 필요"
                            else -> "4개 번호 고정 저장"
                        },
                        onClick = viewModel::saveGeneratedNumbers,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = uiState.canGenerate && !uiState.hasGenerationConditionChanged &&
                            !uiState.isGenerating &&
                            !uiState.isSaving,
                    )
                }
            }
            items(uiState.generatedNumbers, key = { result -> result.type.name }) { result ->
                val backup = uiState.backupNumbers.firstOrNull { savedBackup ->
                    savedBackup.number.type == result.type
                }
                PensionLotteryGeneratedNumberCard(
                    result = result,
                    isFixed = !uiState.hasUnsavedGeneration,
                    backup = backup,
                    isRegenerating = uiState.regeneratingType == result.type,
                    isGeneratingBackup = uiState.generatingBackupType == result.type,
                    actionEnabled = (if (uiState.hasUnsavedGeneration) {
                        uiState.canGenerate && !uiState.hasGenerationConditionChanged
                    } else {
                        uiState.backupUnavailableReason == null
                    }) &&
                        !uiState.isGenerating &&
                        !uiState.isSaving,
                    backupUnavailableReason = uiState.backupUnavailableReason,
                    onRegenerate = { viewModel.regenerate(result.type) },
                    onGenerateBackup = { viewModel.generateBackup(result.type) },
                    onDeleteBackup = { backupDeleteTarget = backup },
                )
            }
        }
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
            items(uiState.generationHistory, key = PensionLotteryGenerationHistory::generationId) { history ->
                PensionLotteryGenerationHistoryCard(
                    history = history,
                    onDelete = { deleteTarget = history },
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
        AppSectionHeader(
            title = "새 번호 생성 조건",
            subtitle = "출현형 2개 · 미출현 혼합형 2개. 저장 번호에는 생성 당시 조건을 유지합니다.",
        )
        Text("끝자리 후보 · 출현 ${uiState.appearedLastDigitCandidates.joinToString("·").ifEmpty { "계산 대기" }} / 혼합 ${uiState.coldMixLastDigitCandidates.joinToString("·").ifEmpty { "계산 대기" }}")
        AppSecondaryButton(text = if (showDetails) "조건 상세 접기" else "조건 상세", onClick = { showDetails = !showDetails })
        if (showDetails) {
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
        GeneratorRuleRow(label = "예비 번호", value = "고정 번호별 1개 생성 · 삭제 가능")
        }
    }
}

@Composable
private fun PensionLotterySavedConditionsCard(uiState: PensionLotteryGeneratorUiState) {
    val snapshot = uiState.generatedNumbers.firstOrNull()?.generationSnapshot
    val conditions = uiState.savedGenerationConditions
    AppSectionCard {
        AppSectionHeader(
            title = snapshot?.let { "${it.targetRoundNo}회차 생성 당시 조건" } ?: "생성 당시 조건",
            subtitle = "저장한 번호는 유지하며, 예비 번호도 같은 회차·조건을 사용합니다.",
        )
        if (conditions != null) {
            Text("분석 기준 ${conditions.analysisThroughRound}회 · 점수 ${conditions.scoreBand} · 동일 숫자 ${conditions.duplicateLabel} · 0점 자리 ${conditions.zeroScoreCount}개")
            Text("끝자리 후보: 출현 ${conditions.appearedLastDigits.joinToString("·")} · 혼합 ${conditions.coldMixLastDigits.joinToString("·")}")
        } else {
            AppSupportText("생성 당시 입력·설정을 재현할 수 없습니다. 번호별 저장된 점수는 유지하며 예비 번호 생성은 제한합니다.")
        }
        if (uiState.hasLatestConditionsChanged) AppSupportText("최신 입력·조건이 생성 당시와 다릅니다. 저장한 번호를 교체할 필요는 없으며 새 번호 생성은 선택 사항입니다.")
        uiState.backupUnavailableReason?.let { AppSupportText("예비 번호: $it") }
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
    isFixed: Boolean,
    backup: PensionLotteryBackupNumber?,
    isRegenerating: Boolean,
    isGeneratingBackup: Boolean,
    actionEnabled: Boolean,
    backupUnavailableReason: String?,
    onRegenerate: () -> Unit,
    onGenerateBackup: () -> Unit,
    onDeleteBackup: () -> Unit,
) {
    var showExplanation by rememberSaveable(result.winningNumber) { mutableStateOf(false) }
    AppSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppSectionHeader(
                title = result.type.label,
                subtitle = result.type.description,
                modifier = Modifier.weight(1f),
            )
            if (isFixed) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = "고정",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                    )
                }
            } else {
                PensionRegenerateIcon(
                    contentDescription = "${result.type.label} 다시 생성",
                    isLoading = isRegenerating,
                    enabled = actionEnabled,
                    onClick = onRegenerate,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${result.groupNo}조", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            if (showExplanation) Text("동일 숫자 ${result.duplicateLabel}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PensionLotteryNumberRow(
            winningNumber = result.winningNumber,
            highlightedPositions = result.coldPositions,
            scores = if (showExplanation) result.digitScores else emptyList(),
        )
        AppSecondaryButton(text = if (showExplanation) "번호 해설 접기" else "번호 해설 보기", onClick = { showExplanation = !showExplanation })
        if (showExplanation) {
        Text(
            text = "합계 ${result.totalScore}점 · ${result.scoreBand} 구간",
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        if (result.coldPositions.isNotEmpty()) {
            Text(
                text = "미출현 우선 적용: ${result.coldPositions.sorted().joinToString { position -> generatorPositionLabel(position) }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "미출현 우선 점수: ${result.coldPriorityScores.entries.sortedBy { entry -> entry.key }.joinToString { entry ->
                    "${generatorPositionLabel(entry.key)} ${entry.value}점"
                }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        }
        if (isFixed) {
            if (backup == null) {
                AppSecondaryButton(
                    text = when {
                        isGeneratingBackup -> "예비 번호 생성 중"
                        backupUnavailableReason != null -> backupUnavailableReason
                        else -> "예비 번호 생성"
                    },
                    onClick = onGenerateBackup,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = actionEnabled,
                )
            } else {
                PensionLotteryBackupNumberCard(
                    backup = backup,
                    onDelete = onDeleteBackup,
                )
            }
        }
    }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("예비 번호", fontWeight = FontWeight.Bold)
                Text(
                    text = "${backup.number.groupNo}조 ${backup.number.winningNumber}",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
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
private fun PensionLotteryGenerationHistoryCard(
    history: PensionLotteryGenerationHistory,
    onDelete: () -> Unit,
) {
    AppSectionCard {
        AppSectionHeader(
            title = history.generatedAt.format(PensionGenerationTimeFormatter),
            subtitle = "생성 번호 ${history.numbers.size}개",
        )
        history.numbers.forEach { result ->
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
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(result.type.label, style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = "${result.groupNo}조 ${result.winningNumber}",
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text = "${result.totalScore}점",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        AppSecondaryButton(
            text = "이 히스토리 삭제",
            onClick = onDelete,
            modifier = Modifier.fillMaxWidth(),
        )
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
