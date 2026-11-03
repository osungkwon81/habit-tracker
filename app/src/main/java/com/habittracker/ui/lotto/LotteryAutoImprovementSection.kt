package com.habittracker.ui.lotto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.habittracker.data.lotto.LotteryAutoImprovementState
import com.habittracker.data.lotto.LotteryProduct
import com.habittracker.ui.components.AppConfirmDialog
import com.habittracker.ui.components.AppLoadingCard
import com.habittracker.ui.components.AppSecondaryButton
import com.habittracker.ui.components.AppSectionHeader

@Composable
fun LotteryAutoImprovementSection(
    product: LotteryProduct,
    state: LotteryAutoImprovementState,
    onRefresh: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onRollback: (() -> Unit)? = null,
) {
    var confirmation by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var rollbackConfirmation by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AppSectionHeader(title = "자동 개선", subtitle = if (state.enabled) "켜짐 · 보유 데이터로 후보 비교" else "꺼짐")
        val audit = state.audit
        if (audit == null) Text("데이터 범위·누락을 확인해 주세요.")
        else {
            Text(if (audit.count == 0) "당첨 이력이 없습니다. 추첨 결과를 먼저 동기화해 주세요."
                else "수집 ${audit.count}회차 · ${audit.firstRound}~${audit.lastRound}회")
            if (audit.count > 0) Text(if (audit.canResearch) "보유 범위와 최신 회차에 누락·번호 오류가 없습니다."
                else "데이터 확인 필요 · 누락 ${audit.missingRounds.size}회 / 중복 ${audit.duplicateRounds.size}회 / 번호 오류 ${audit.invalidRounds.size}회",
                color = if (audit.canResearch) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        }
        state.evaluationStatus?.let { Text(it) }
        if (state.running) AppLoadingCard("데이터와 평가 기록을 확인하고 있습니다.")
        state.issue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        AppSecondaryButton(text = if (state.enabled || state.running) "중단·자동 개선 끄기" else "자동 개선 켜기",
            onClick = { if (state.enabled || state.running) onEnabledChange(false) else confirmation = true })
        AppSecondaryButton(text = "상태 새로고침", enabled = !state.running, onClick = onRefresh)
        if (state.canRollback && onRollback != null) AppSecondaryButton(text = "이전 가중치로 복구", enabled = !state.running, onClick = { rollbackConfirmation = true })
        AppSecondaryButton(text = if (showDetails) "상세 접기" else "데이터·평가 기준 보기", onClick = { showDetails = !showDetails })
        if (showDetails) {
            Text("고정 추천 묶음의 전략 비교입니다. 화면에서 별도로 생성·구매한 번호의 결과는 구매 성과에서 확인하세요.", style = MaterialTheme.typography.bodySmall)
            state.result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            state.candidateReport?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            audit?.let {
                Text("추첨 일정 기준 ${it.expectedLastRound}회까지 · 시작 이전 미보유 ${it.missingBeforeStart}회차")
                if (it.missingRounds.isNotEmpty()) Text("누락 회차: ${it.missingRounds.take(20).joinToString()}${if (it.missingRounds.size > 20) " 외" else ""}")
                Text("보너스 미기록 ${it.missingBonusCount}개 · 원본 출처 해시 미기록 ${it.missingSourceHashCount}개")
                Text("원본 해시와 당시 가용 시각이 없는 기록은 추정해 채우지 않습니다.", style = MaterialTheme.typography.bodySmall)
            }
            Text(if (product == LotteryProduct.LOTTO_645) "12,000개 → 260개 → 최종 5게임 · 최근 빈도와 최종 분산 가중치 비교"
                else "유형별 250개 → 50개 → 1개 · 끝자리 가중치와 후보 순위 기준 비교")
            Text("보유 12회를 6회씩 비교합니다. 두 구간 모두 최종 추천 당첨 비율이 기존 설정·무작위보다 높고 보조 일치도가 평균 0.10 이상 높아야 시험 적용합니다. 전부 미당첨이면 채택하지 않습니다.")
            Text(if (product == LotteryProduct.LOTTO_645) "보조 지표: 게임당 본번호 평균 일치 수" else "보조 지표: 번호당 끝자리부터 연속으로 맞은 평균 자리 수")
            Text(if (product == LotteryProduct.LOTTO_645) "주 지표: 최종 5게임의 본번호 3개 이상 일치 비율" else "주 지표: 최종 4번호의 일반 당첨번호 끝 2자리 이상 일치 비율")
            Text("적용 후 추첨 전에 고정한 새 4회 추천으로 점검하고 악화 시 복구합니다. 후보에만 있는 당첨은 채택 근거로 사용하지 않습니다. 실제 구매 손익은 별도로 확인합니다.")
            if (state.running) Text("과거 비교 ${state.completedCount}/${state.targetCount}회 · 완료 결과 재사용 ${state.reusedCount}회")
        }
    }
    if (confirmation) {
        AppConfirmDialog(
            title = "자동 개선 켜기",
            message = "보유 이력의 비교 조건을 충족하면 가중치를 시험 적용하고 변경 사실을 알립니다. 새 4회마다 악화를 점검해 이전 설정으로 복구합니다. 언제든 끄거나 직접 복구할 수 있습니다.",
            confirmText = "켜기",
            onDismiss = { confirmation = false },
            onConfirm = { confirmation = false; onEnabledChange(true) },
        )
    }
    if (rollbackConfirmation) AppConfirmDialog(title = "이전 가중치로 복구", message = "시험 적용 직전의 가중치로 되돌립니다. 저장된 번호와 비교 기록은 유지합니다.", confirmText = "복구",
        onDismiss = { rollbackConfirmation = false }, onConfirm = { rollbackConfirmation = false; onRollback?.invoke() })
}
