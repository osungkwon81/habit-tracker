package com.habittracker.ui.lotto

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.habittracker.data.local.entity.PensionLotteryDrawEntity
import com.habittracker.ui.components.AppEmptyCard
import com.habittracker.ui.components.AppHeroCard
import com.habittracker.ui.components.AppSaveButton
import com.habittracker.ui.components.AppScreen
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppSectionHeader
import com.habittracker.ui.components.AppSecondaryButton
import com.habittracker.ui.components.AppSelectableChip
import com.habittracker.ui.components.AppStatusText
import kotlin.math.roundToInt

private val PensionStatHighBackground = Color(0xFFFFE1D6)
private val PensionStatHighText = Color(0xFF8A2D13)
private val PensionStatLowBackground = Color(0xFFDCEBFF)
private val PensionStatLowText = Color(0xFF174E80)

@Composable
fun PensionLotteryScreen(
    viewModel: PensionLotteryViewModel,
    onBackToLotteryHome: () -> Unit,
    onOpenGenerator: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val officialSyncStatus by viewModel.officialSyncStatus.collectAsStateWithLifecycle()
    val isOfficialSyncing by viewModel.isOfficialSyncing.collectAsStateWithLifecycle()

    AppScreen {
        item {
            AppHeroCard(
                title = "연금720+ 분석",
                description = "1등 번호 입력부터 기간별 일치·통계·점수를 확인합니다.",
                icon = "🎟️",
                eyebrow = "LOTTO · PENSION 720+",
                status = "최신 저장 ${uiState.latestRoundNo ?: "-"}회",
                action = {
                    AppSecondaryButton(
                        text = "동행복권 선택으로 돌아가기",
                        onClick = onBackToLotteryHome,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
            )
        }
        item {
            PensionLotteryTabSelector(
                selectedTab = uiState.selectedTab,
                onSelect = viewModel::selectTab,
                onOpenGenerator = onOpenGenerator,
            )
        }

        when (uiState.selectedTab) {
            PensionLotteryTab.INPUT -> {
                item {
                    LotterySyncStatusCard(
                        product = com.habittracker.data.lotto.LotteryProduct.PENSION_720,
                        status = officialSyncStatus,
                        isSyncing = isOfficialSyncing,
                        onSyncNow = viewModel::syncOfficialDrawsNow,
                    )
                }
                item {
                    PensionLotteryInputCard(
                        roundInput = uiState.roundInput,
                        groupInput = uiState.groupInput,
                        numberInputs = uiState.numberInputs,
                        statusMessage = uiState.statusMessage,
                        onRoundChange = viewModel::updateRoundInput,
                        onGroupChange = viewModel::updateGroupInput,
                        onNumberChange = viewModel::updateNumberInput,
                        onSave = viewModel::saveDraw,
                    )
                }
                item {
                    AppSectionHeader(
                        title = "최근 당첨번호",
                        subtitle = "숫자 아래 점수는 각 회차의 직전 ${uiState.selectedRange.label} 가중 점수입니다.",
                    )
                }
                item {
                    PensionLotteryRangeCard(uiState.selectedRange, viewModel::selectRange)
                }
                if (uiState.recentDraws.isEmpty()) {
                    item { AppEmptyCard("저장된 연금복권 당첨번호가 없습니다.") }
                } else {
                    item {
                        PensionLotteryScoreBandStatsCard(
                            stats = uiState.sixteenWeekScoreBandStats,
                            totalDrawCount = uiState.sixteenWeekScoreBandDrawCount,
                        )
                    }
                    item {
                        PensionLotteryZeroScoreCountStatsCard(
                            stats = uiState.sixteenWeekZeroScoreCountStats,
                            totalDrawCount = uiState.sixteenWeekScoreBandDrawCount,
                        )
                    }
                    items(uiState.recentDraws, key = { draw -> draw.roundNo }) { draw ->
                        PensionLotteryDrawCard(
                            draw = draw,
                            digitScores = uiState.recentDigitScores[draw.roundNo].orEmpty(),
                            scoreBandLabel = if (uiState.selectedRange == PensionLotteryRange.SIXTEEN) {
                                uiState.sixteenWeekScoreBandsByRound[draw.roundNo]
                            } else {
                                null
                            },
                        )
                    }
                    if (uiState.hasMoreRecentDraws) {
                        item(key = "recent-draw-page-${uiState.recentDraws.size}") {
                            LaunchedEffect(uiState.recentDraws.size) {
                                viewModel.loadMoreRecentDraws()
                            }
                            Text(
                                text = "다음 회차를 불러오는 중입니다.",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            PensionLotteryTab.MATCH -> {
                item {
                    PensionLotteryMatchInputCard(
                        selectedRange = uiState.selectedRange,
                        numberInput = uiState.matchNumberInput,
                        onRangeSelect = viewModel::selectRange,
                        onNumberChange = viewModel::updateMatchNumberInput,
                    )
                }
                if (uiState.matchNumberInput.length != 6) {
                    item { AppEmptyCard("확인할 6자리 번호를 입력해 주세요.") }
                } else {
                    item {
                        MatchSummaryCard(
                            totalDrawCount = uiState.totalDrawCount,
                            exactMatchRounds = uiState.exactMatchRounds,
                        )
                    }
                    item {
                        PensionLotteryMatchScoreCard(
                            winningNumber = uiState.matchNumberInput,
                            selectedRange = uiState.selectedRange,
                            digitScores = uiState.matchDigitScores,
                        )
                    }
                    items(uiState.matchResults, key = { result -> result.draw.roundNo }) { result ->
                        PensionLotteryMatchCard(result)
                    }
                }
            }

            PensionLotteryTab.STATS -> {
                item { PensionLotteryRangeCard(uiState.selectedRange, viewModel::selectRange) }
                item { PensionLotteryDuplicateStatsCard(uiState.duplicateStats) }
                items(uiState.positionStats, key = { stat -> stat.position }) { stat ->
                    PensionLotteryPositionStatsCard(stat)
                }
            }

            PensionLotteryTab.SCORE -> {
                item { PensionLotteryRangeCard(uiState.selectedRange, viewModel::selectRange) }
                item {
                    AppSectionCard {
                        AppSectionHeader(
                            title = "점수 계산 기준",
                            subtitle = "선택 구간의 최신 회차는 구간 수만큼, 이전 회차는 1점씩 낮춰 가장 오래된 회차를 1점으로 계산합니다.",
                        )
                    }
                }
                items(uiState.positionScores, key = { score -> score.position }) { score ->
                    PensionLotteryPositionScoreCard(score)
                }
            }
        }
    }
}

@Composable
private fun PensionLotteryTabSelector(
    selectedTab: PensionLotteryTab,
    onSelect: (PensionLotteryTab) -> Unit,
    onOpenGenerator: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            PensionLotteryTab.entries.take(2).forEach { tab ->
                AppSelectableChip(label = tab.label, selected = selectedTab == tab, onClick = { onSelect(tab) })
            }
            AppSelectableChip(label = "번호 생성", selected = false, onClick = onOpenGenerator)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            PensionLotteryTab.entries.drop(2).forEach { tab ->
                AppSelectableChip(label = tab.label, selected = selectedTab == tab, onClick = { onSelect(tab) })
            }
        }
    }
}

@Composable
private fun PensionLotteryInputCard(
    roundInput: String,
    groupInput: String,
    numberInputs: List<String>,
    statusMessage: String?,
    onRoundChange: (String) -> Unit,
    onGroupChange: (String) -> Unit,
    onNumberChange: (Int, String) -> Unit,
    onSave: () -> Unit,
) {
    val numberFocusRequesters = remember { List(6) { FocusRequester() } }
    AppSectionCard {
        AppSectionHeader(title = "1등 당첨번호 입력", subtitle = "조는 공식 기록용이며 분석은 6자리 번호만 사용합니다.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = roundInput,
                onValueChange = onRoundChange,
                label = { Text("회차") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
            OutlinedTextField(
                value = groupInput,
                onValueChange = onGroupChange,
                label = { Text("조(기록용)") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            numberInputs.forEachIndexed { index, value ->
                OutlinedTextField(
                    value = value,
                    onValueChange = { input ->
                        onNumberChange(index, input)
                        if (input.any(Char::isDigit) && index < numberFocusRequesters.lastIndex) {
                            numberFocusRequesters[index + 1].requestFocus()
                        }
                    },
                    label = { Text("${index + 1}") },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(numberFocusRequesters[index]),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = if (index < numberFocusRequesters.lastIndex) ImeAction.Next else ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = {
                            if (index < numberFocusRequesters.lastIndex) {
                                numberFocusRequesters[index + 1].requestFocus()
                            }
                        },
                    ),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium.copy(textAlign = TextAlign.Center),
                )
            }
        }
        AppSaveButton(text = "1등 번호 저장", onClick = onSave, modifier = Modifier.fillMaxWidth())
        statusMessage?.let { AppStatusText(it) }
    }
}

@Composable
private fun PensionLotteryMatchInputCard(
    selectedRange: PensionLotteryRange,
    numberInput: String,
    onRangeSelect: (PensionLotteryRange) -> Unit,
    onNumberChange: (String) -> Unit,
) {
    AppSectionCard {
        AppSectionHeader(title = "기존 당첨번호 일치 확인", subtitle = "조와 관계없이 6자리 번호만 비교합니다.")
        PensionLotteryRangeSelector(selectedRange, onRangeSelect)
        OutlinedTextField(
            value = numberInput,
            onValueChange = onNumberChange,
            label = { Text("6자리 번호") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )
    }
}

@Composable
private fun PensionLotteryRangeCard(
    selectedRange: PensionLotteryRange,
    onRangeSelect: (PensionLotteryRange) -> Unit,
) {
    AppSectionCard {
        AppSectionHeader(title = "조회 기간", subtitle = "최근 회차부터 선택한 주 수만큼 계산합니다.")
        PensionLotteryRangeSelector(selectedRange, onRangeSelect)
    }
}

@Composable
private fun PensionLotteryRangeSelector(
    selectedRange: PensionLotteryRange,
    onRangeSelect: (PensionLotteryRange) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            PensionLotteryRange.entries.take(3).forEach { range ->
                AppSelectableChip(label = range.label, selected = selectedRange == range, onClick = { onRangeSelect(range) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            PensionLotteryRange.entries.drop(3).forEach { range ->
                AppSelectableChip(label = range.label, selected = selectedRange == range, onClick = { onRangeSelect(range) })
            }
        }
    }
}

@Composable
private fun MatchSummaryCard(
    totalDrawCount: Int,
    exactMatchRounds: List<Int>,
) {
    val exactMatchExists = exactMatchRounds.isNotEmpty()
    val containerColor = if (exactMatchExists) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    AppSectionCard {
        AppSectionHeader(title = "전체 회차 1등 번호 확인", subtitle = "기간 선택과 관계없이 저장된 전체 회차를 조회합니다.")
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(containerColor, RoundedCornerShape(16.dp))
                .padding(14.dp),
        ) {
            Text(
                text = if (exactMatchExists) {
                    "전체 ${totalDrawCount}개 회차에서 동일한 번호가 있습니다: ${exactMatchRounds.joinToString { round -> "${round}회" }}"
                } else {
                    "전체 ${totalDrawCount}개 회차에서 동일한 1등 번호가 없습니다."
                },
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun PensionLotteryMatchScoreCard(
    winningNumber: String,
    selectedRange: PensionLotteryRange,
    digitScores: List<Int>,
) {
    AppSectionCard {
        AppSectionHeader(
            title = "입력 번호 ${selectedRange.label} 점수",
            subtitle = "선택 기간의 최신 회차부터 과거로 갈수록 1점씩 낮춰 자리별로 계산합니다.",
        )
        PensionLotteryNumberRow(winningNumber, scores = digitScores)
        Text(
            text = "합계 ${digitScores.sum()}점",
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun PensionLotteryDrawCard(
    draw: PensionLotteryDrawEntity,
    digitScores: List<Int>,
    scoreBandLabel: String?,
) {
    AppSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${draw.roundNo}회", fontWeight = FontWeight.Bold)
            Text("${draw.groupNo}조", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        PensionLotteryNumberRow(draw.winningNumber, scores = digitScores)
        Text(
            text = buildString {
                append("합계 ${digitScores.sum()}점")
                scoreBandLabel?.let { label -> append(" · $label 구간") }
            },
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun PensionLotteryScoreBandStatsCard(
    stats: List<PensionLotteryScoreBandStat>,
    totalDrawCount: Int,
) {
    AppSectionCard {
        AppSectionHeader(
            title = "16주 합계 점수 분포",
            subtitle = if (totalDrawCount > 0) {
                "각 회차의 직전 16회가 모두 있는 총 ${totalDrawCount}회 기준입니다."
            } else {
                "통계를 계산하려면 저장된 당첨번호가 16회 이상 필요합니다."
            },
        )
        if (totalDrawCount > 0) {
            stats.forEach { stat ->
                val percentageTenths = (stat.drawCount * 1000.0 / totalDrawCount).roundToInt()
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stat.label, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "${stat.drawCount}회 (${percentageTenths / 10}.${percentageTenths % 10}%)",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PensionLotteryZeroScoreCountStatsCard(
    stats: List<PensionLotteryZeroScoreCountStat>,
    totalDrawCount: Int,
) {
    AppSectionCard {
        AppSectionHeader(
            title = "16주 0점 자리 수 분포",
            subtitle = if (totalDrawCount > 0) {
                "각 회차의 직전 16회 점수에서 0점인 자리 개수 기준입니다."
            } else {
                "통계를 계산하려면 저장된 당첨번호가 17회 이상 필요합니다."
            },
        )
        if (totalDrawCount > 0) {
            stats.forEach { stat ->
                val percentageTenths = (stat.drawCount * 1000.0 / totalDrawCount).roundToInt()
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("0점 ${stat.zeroScoreCount}개", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "${stat.drawCount}회 (${percentageTenths / 10}.${percentageTenths % 10}%)",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PensionLotteryMatchCard(result: PensionLotteryMatchResult) {
    AppSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${result.draw.roundNo}회", fontWeight = FontWeight.Bold)
            Text(
                text = "${result.matchCount}자리 일치",
                color = if (result.matchCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PensionLotteryNumberRow(result.draw.winningNumber, result.matchedPositions)
    }
}

@Composable
internal fun PensionLotteryNumberRow(
    winningNumber: String,
    highlightedPositions: Set<Int> = emptySet(),
    scores: List<Int> = emptyList(),
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        winningNumber.forEachIndexed { index, digit ->
            val highlighted = index in highlightedPositions
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    border = BorderStroke(
                        2.dp,
                        if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(digit.toString(), fontWeight = FontWeight.Bold)
                    }
                }
                scores.getOrNull(index)?.let { score ->
                    Text(
                        text = "${score}점",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun PensionLotteryPositionStatsCard(stat: PensionLotteryPositionStat) {
    val maxCount = stat.digits.maxOfOrNull(PensionLotteryDigitCount::count)
    val minCount = stat.digits.minOfOrNull(PensionLotteryDigitCount::count)
    val hasCountDifference = maxCount != null && minCount != null && maxCount != minCount
    AppSectionCard {
        AppSectionHeader(
            title = "${pensionPositionLabel(stat.position)} 자리 통계",
            subtitle = "괄호 안 숫자는 전체 회차 총횟수이며, 최다·최소 출현은 색상으로 구분합니다.",
        )
        stat.digits.chunked(5).forEach { rowDigits ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                rowDigits.forEach { digit ->
                    PensionStatisticCell(
                        label = digit.digit.toString(),
                        value = "${digit.count}회(${digit.totalCount})",
                        highlight = when {
                            !hasCountDifference -> PensionStatisticHighlight.NONE
                            digit.count == maxCount -> PensionStatisticHighlight.HIGH
                            digit.count == minCount -> PensionStatisticHighlight.LOW
                            else -> PensionStatisticHighlight.NONE
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PensionLotteryDuplicateStatsCard(stats: List<PensionLotteryDuplicateStat>) {
    AppSectionCard {
        AppSectionHeader(
            title = "동일 숫자 통계",
            subtitle = "선택 기간 횟수(전체 횟수)이며, 2자리에는 여러 쌍도 포함합니다.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            stats.forEach { stat ->
                PensionStatisticCell(
                    label = stat.label,
                    value = "${stat.count}회(${stat.totalCount})",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PensionLotteryPositionScoreCard(score: PensionLotteryPositionScore) {
    AppSectionCard {
        AppSectionHeader(title = "${pensionPositionLabel(score.position)} 자리 점수", subtitle = "점수가 높은 번호순")
        score.digits.chunked(5).forEachIndexed { rowIndex, rowDigits ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                rowDigits.forEachIndexed { index, digit ->
                    PensionStatisticCell(
                        label = digit.digit.toString(),
                        value = "${digit.score}점",
                        highlight = if (rowIndex == 0 && index == 0) {
                            PensionStatisticHighlight.HIGH
                        } else {
                            PensionStatisticHighlight.NONE
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PensionStatisticCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlight: PensionStatisticHighlight = PensionStatisticHighlight.NONE,
) {
    val containerColor = when (highlight) {
        PensionStatisticHighlight.HIGH -> PensionStatHighBackground
        PensionStatisticHighlight.LOW -> PensionStatLowBackground
        PensionStatisticHighlight.NONE -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (highlight) {
        PensionStatisticHighlight.HIGH -> PensionStatHighText
        PensionStatisticHighlight.LOW -> PensionStatLowText
        PensionStatisticHighlight.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .background(
                color = containerColor,
                shape = RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, fontWeight = FontWeight.Bold, color = contentColor)
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private enum class PensionStatisticHighlight {
    NONE,
    HIGH,
    LOW,
}

private fun pensionPositionLabel(position: Int): String = when (position) {
    0 -> "십만"
    1 -> "만"
    2 -> "천"
    3 -> "백"
    4 -> "십"
    else -> "일"
}
