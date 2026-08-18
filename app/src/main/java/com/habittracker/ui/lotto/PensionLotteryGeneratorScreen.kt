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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.habittracker.R
import com.habittracker.ui.components.AppEmptyCard
import com.habittracker.ui.components.AppHeroCard
import com.habittracker.ui.components.AppPrimaryButton
import com.habittracker.ui.components.AppScreen
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppSectionHeader
import com.habittracker.ui.components.AppSecondaryButton
import com.habittracker.ui.components.AppSaveButton
import com.habittracker.ui.components.AppStatusText
import java.time.format.DateTimeFormatter

private val PensionGenerationTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss")

@Composable
fun PensionLotteryGeneratorScreen(
    viewModel: PensionLotteryGeneratorViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    var deleteTarget by remember { mutableStateOf<PensionLotteryGenerationHistory?>(null) }

    deleteTarget?.let { history ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            confirmButton = {
                AppPrimaryButton(
                    text = "삭제",
                    onClick = {
                        viewModel.deleteGeneration(history.generationId)
                        deleteTarget = null
                    },
                )
            },
            dismissButton = {
                AppSecondaryButton(text = "취소", onClick = { deleteTarget = null })
            },
            title = { Text("생성 히스토리 삭제") },
            text = {
                Text("${history.generatedAt.format(PensionGenerationTimeFormatter)} 생성 번호를 삭제합니다.")
            },
        )
    }

    AppScreen {
        item {
            AppHeroCard(
                title = "연금번호 생성",
                description = "직전 16주 점수와 동일 숫자·자리별 출현 통계를 조합합니다.",
                icon = "🎰",
                eyebrow = "PENSION 720+ · GENERATOR",
                status = "최신 저장 ${uiState.latestRoundNo ?: "-"}회",
                action = {
                    AppSecondaryButton(
                        text = "연금720+ 분석으로 돌아가기",
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
            )
        }
        item {
            PensionLotteryGeneratorRuleCard(uiState)
        }
        if (uiState.generatedNumbers.isEmpty()) {
            item {
                AppPrimaryButton(
                    text = if (uiState.isGenerating) "번호 생성 중" else "두 가지 번호 생성",
                    onClick = viewModel::generate,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.canGenerate && !uiState.isGenerating && !uiState.isSaving,
                )
            }
        }
        uiState.statusMessage?.let { message ->
            item { AppStatusText(message) }
        }
        if (uiState.generatedNumbers.isEmpty()) {
            item {
                AppEmptyCard(
                    if (uiState.canGenerate) {
                        "출현형과 미출현 혼합형 번호를 생성해 주세요."
                    } else {
                        "번호 생성을 위해 저장된 당첨번호가 17회 이상 필요합니다."
                    },
                )
            }
        } else {
            item {
                AppSectionHeader(
                    title = "현재 추천 번호",
                    subtitle = if (uiState.hasUnsavedGeneration) {
                        "방금 생성됨 · 저장 전"
                    } else {
                        uiState.generationHistory.firstOrNull()?.generatedAt?.format(PensionGenerationTimeFormatter)
                    },
                )
            }
            item {
                AppSaveButton(
                    text = when {
                        uiState.isSaving -> "생성 번호 저장 중"
                        uiState.hasUnsavedGeneration -> "생성 번호 저장"
                        else -> "저장 완료"
                    },
                    onClick = viewModel::saveGeneratedNumbers,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.hasUnsavedGeneration && !uiState.isGenerating && !uiState.isSaving,
                )
            }
            items(uiState.generatedNumbers, key = { result -> result.type.name }) { result ->
                PensionLotteryGeneratedNumberCard(
                    result = result,
                    isRegenerating = uiState.regeneratingType == result.type,
                    regenerateEnabled = !uiState.isGenerating && !uiState.isSaving,
                    onRegenerate = { viewModel.regenerate(result.type) },
                )
            }
            if (!uiState.hasUnsavedGeneration) {
                uiState.generationHistory.firstOrNull()?.let { latestHistory ->
                    item {
                        AppSecondaryButton(
                            text = "현재 추천 번호 삭제",
                            onClick = { deleteTarget = latestHistory },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        val previousHistory = if (uiState.hasUnsavedGeneration) {
            uiState.generationHistory
        } else {
            uiState.generationHistory.drop(1)
        }
        if (previousHistory.isNotEmpty()) {
            item {
                AppSectionHeader(
                    title = if (uiState.hasUnsavedGeneration) "저장된 생성 히스토리" else "이전 생성 히스토리",
                )
            }
            items(previousHistory, key = PensionLotteryGenerationHistory::generationId) { history ->
                PensionLotteryGenerationHistoryCard(
                    history = history,
                    onDelete = { deleteTarget = history },
                )
            }
        }
    }
}

@Composable
private fun PensionLotteryGeneratorRuleCard(uiState: PensionLotteryGeneratorUiState) {
    AppSectionCard {
        AppSectionHeader(
            title = "적용 조건",
            subtitle = "과거 각 회차는 해당 회차를 제외한 직전 16회로 계산합니다.",
        )
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
        GeneratorRuleRow(
            label = "마지막 숫자",
            value = "출현 상위 3개·미출현 우선 / 서로 다르게",
        )
        GeneratorRuleRow(label = "추천 구성", value = "출현형 1개 · 미출현 혼합형 1개")
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
    regenerateEnabled: Boolean,
    onRegenerate: () -> Unit,
) {
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
            PensionRegenerateIcon(
                contentDescription = "${result.type.label} 다시 생성",
                isLoading = isRegenerating,
                enabled = regenerateEnabled,
                onClick = onRegenerate,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${result.groupNo}조", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("동일 숫자 ${result.duplicateLabel}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PensionLotteryNumberRow(
            winningNumber = result.winningNumber,
            highlightedPositions = result.coldPositions,
            scores = result.digitScores,
        )
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
