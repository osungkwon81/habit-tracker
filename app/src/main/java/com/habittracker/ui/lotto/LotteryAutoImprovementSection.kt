package com.habittracker.ui.lotto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
internal fun LotteryAutomaticRunHistory(state: LotteryAutoImprovementState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppSectionHeader(title = "자동 평가 이력")
        state.issue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.history.isEmpty() && state.issue == null) {
            Text(if (state.audit == null) "자동 평가 이력을 읽고 있습니다." else "아직 자동 개선 실행 기록이 없습니다.")
        }
        state.history.forEach { entry ->
            key(entry.id) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppSectionHeader(title = if (entry.targets.isEmpty()) "대상 회차 확인 필요"
                        else "${entry.targets.first()}~${entry.targets.last()}회 평가")
                    entry.startedAt?.let { Text("시작 $it · 한국 시간") }
                    Text(when (entry.state) {
                        "SUCCESS" -> "비교 완료"
                        "FAILED" -> "실패"
                        "CANCELLED" -> "취소"
                        "INCOMPLETE" -> "정상 종료 기록 없음"
                        else -> "기록 확인 필요"
                    })
                    if (entry.targets.isNotEmpty()) Text("회차 결과 기록 ${entry.recordedRounds.size}/${entry.targets.size}")
                    entry.summary?.let { Text(it) }
                    entry.issue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable
fun LotteryAutoImprovementSection(
    product: LotteryProduct,
    state: LotteryAutoImprovementState,
    onRefresh: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onRollback: (() -> Unit)? = null,
    onStartHistorical: (() -> Unit)? = null,
) {
    var confirmation by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var rollbackConfirmation by rememberSaveable { mutableStateOf(false) }
    var historicalConfirmation by rememberSaveable { mutableStateOf(false) }
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
        if (onStartHistorical != null) AppSecondaryButton(text = "과거 비교 시작·재시도",
            enabled = !state.running && audit?.canResearch == true && state.targetCount > 0,
            onClick = { historicalConfirmation = true })
        if (state.canRollback && onRollback != null) AppSecondaryButton(text = "이전 설정으로 복구", enabled = !state.running, onClick = { rollbackConfirmation = true })
        AppSecondaryButton(text = if (showDetails) "상세 접기" else "데이터·평가 기준 보기", onClick = { showDetails = !showDetails })
        if (showDetails) {
            Text("직접 생성·재생성한 후보도 저장·구매 여부와 관계없이 추첨 후 분석합니다. 같은 회차의 반복 생성은 한 회차로 묶어 패턴 관찰에 반영하며, 구매 성과는 구매 내역에서 확인하세요.", style = MaterialTheme.typography.bodySmall)
            state.result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            state.candidateReport?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("반복 패턴은 별도로 관찰합니다. 새 패턴 점수는 제안 이후 추첨 전에 고정한 12회로 비교하고, 적용 후 새 4회에서 악화를 점검합니다.")
            audit?.let {
                Text("추첨 일정 기준 ${it.expectedLastRound}회까지 · 시작 이전 미보유 ${it.missingBeforeStart}회차")
                if (it.missingRounds.isNotEmpty()) Text("누락 회차: ${it.missingRounds.take(20).joinToString()}${if (it.missingRounds.size > 20) " 외" else ""}")
                Text("보너스 미기록 ${it.missingBonusCount}개 · 원본 출처 해시 미기록 ${it.missingSourceHashCount}개")
                Text("원본 해시와 당시 가용 시각이 없는 기록은 추정해 채우지 않습니다.", style = MaterialTheme.typography.bodySmall)
            }
            Text(if (product == LotteryProduct.LOTTO_645) "12,000개 → 260개 → 최종 5게임 · 5등 이상이 나올 묶음 확률로 선택"
                else "유형별 250개 → 50개 → 1개 · 최종 4번호는 끝 두 자리를 다르게 선택")
            Text("추첨 전에 기록한 12회를 6회씩 비교합니다. 두 구간 모두 당첨 회차가 기존 설정·무작위보다 많고 평균 일치도도 각각 0.10 이상 높을 때 시험 적용합니다.")
            Text(if (product == LotteryProduct.LOTTO_645) "보조 지표: 게임당 본번호 평균 일치 수" else "보조 지표: 번호당 끝자리부터 연속으로 맞은 평균 자리 수")
            Text(if (product == LotteryProduct.LOTTO_645) "최종 5게임 중 5등 이상이 하나라도 있으면 당첨 1회로 셉니다."
                else "최종 4번호 중 일반 6등 이상이 하나라도 있으면 당첨 1회로 셉니다. 7등과 보너스는 따로 기록합니다.")
            Text("적용 후 추첨 전에 고정한 새 4회 추천으로 점검하고 악화 시 복구합니다. 후보에만 있는 당첨은 채택 근거로 사용하지 않습니다. 실제 구매 손익은 별도로 확인합니다.")
            if (state.running) Text("과거 비교 ${state.completedCount}/${state.targetCount}회 · 완료 결과 재사용 ${state.reusedCount}회")
        }
    }
    if (confirmation) {
        AppConfirmDialog(
            title = "자동 개선 켜기",
            message = "추첨 전에 기록한 추천이 기존 설정과 무작위 추천보다 좋은 성과를 내고 비교 조건을 충족하면 시험 적용을 알립니다. 이후 4회 성과가 악화되면 이전 설정으로 복구합니다. 언제든 끄거나 직접 복구할 수 있습니다.",
            confirmText = "켜기",
            onDismiss = { confirmation = false },
            onConfirm = { confirmation = false; onEnabledChange(true) },
        )
    }
    if (rollbackConfirmation) AppConfirmDialog(title = "이전 설정으로 복구", message = "진행 중인 패턴 제안을 중지하고 최근 시험 적용의 패턴 점수·선별 기준과 가중치를 이전 설정으로 되돌립니다.", confirmText = "복구",
        onDismiss = { rollbackConfirmation = false }, onConfirm = { rollbackConfirmation = false; onRollback?.invoke() })
    if (historicalConfirmation) AppConfirmDialog(
        title = "과거 비교 시작·재시도",
        message = "${state.firstTarget}~${state.lastTarget}회, 총 ${state.targetCount}개 회차를 고정 시드로 비교합니다. " +
            (if (product == LotteryProduct.LOTTO_645) "회차별 기존 설정·변경 설정·무작위 대조군은 각각 12,000개 후보입니다. "
                else "회차별 기존 설정·변경 설정은 각각 4유형×250개, 무작위 대조군은 1,000개 후보입니다. ") +
            "완료된 동일 입력 결과를 재사용하고, 실패·취소·중단된 회차는 기존 기록을 보존한 새 시도로 계산합니다. 필터 탐색 시도는 후보 수보다 많을 수 있습니다.",
        confirmText = "비교 시작",
        onDismiss = { historicalConfirmation = false },
        onConfirm = {
            historicalConfirmation = false
            if (!state.running && state.audit?.canResearch == true && state.targetCount > 0) onStartHistorical?.invoke()
        },
    )
}
