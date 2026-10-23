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
) {
    var confirmation by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AppSectionHeader(title = "자동 개선", subtitle = if (state.enabled) "켜짐 · 검증 기준을 통과하면 새 번호에 적용하고 알립니다." else "꺼짐 · 수집한 데이터로 개선 후보를 비교합니다.")
        val audit = state.audit
        if (audit == null) Text("데이터 범위·누락을 확인해 주세요.")
        else {
            Text(if (audit.count == 0) "당첨 이력이 없습니다. 추첨 결과를 먼저 동기화해 주세요."
                else "수집 ${audit.count}회차 · ${audit.firstRound}~${audit.lastRound}회")
            if (audit.count > 0) Text(if (audit.canResearch) "보유 범위와 최신 회차에 누락·번호 오류가 없습니다."
                else "데이터 확인 필요 · 누락 ${audit.missingRounds.size}회 / 중복 ${audit.duplicateRounds.size}회 / 번호 오류 ${audit.invalidRounds.size}회",
                color = if (audit.canResearch) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        }
        Text("평가 준비부터 적용까지 앱이 처리합니다. 근거가 부족하면 현재 번호 생성 방식을 유지합니다.", style = MaterialTheme.typography.bodySmall)
        state.evaluationStatus?.let { Text(it) }
        if (state.running) AppLoadingCard("데이터와 평가 기록을 확인하고 있습니다.")
        state.issue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        AppSecondaryButton(text = if (state.enabled || state.running) "중단·자동 개선 끄기" else "자동 개선 켜기",
            onClick = { if (state.enabled || state.running) onEnabledChange(false) else confirmation = true })
        AppSecondaryButton(text = "상태 새로고침", enabled = !state.running, onClick = onRefresh)
        AppSecondaryButton(text = if (showDetails) "상세 접기" else "데이터·평가 기준 보기", onClick = { showDetails = !showDetails })
        if (showDetails) {
            audit?.let {
                Text("추첨 일정 기준 ${it.expectedLastRound}회까지 · 시작 이전 미보유 ${it.missingBeforeStart}회차")
                if (it.missingRounds.isNotEmpty()) Text("누락 회차: ${it.missingRounds.take(20).joinToString()}${if (it.missingRounds.size > 20) " 외" else ""}")
                Text("보너스 미기록 ${it.missingBonusCount}개 · 원본 출처 해시 미기록 ${it.missingSourceHashCount}개")
                Text("원본 해시와 당시 가용 시각이 없는 기록은 추정해 채우지 않습니다.", style = MaterialTheme.typography.bodySmall)
            }
            Text(if (product == LotteryProduct.LOTTO_645) "비교 대상: 균형형 기본 모드 5게임에서 최근 빈도의 영향을 완화한 방식"
                else "비교 대상: 출현형 끝자리 후보를 최대 3개에서 4개로 늘린 방식")
            Text("과거 자료는 탐색에 사용합니다. 적용 평가는 새 52회차가 끝나면 한 번 진행하며, 미충족 시 새 구간으로 최대 4번까지 비교합니다. 약 1년 단위이며 효과 입증을 보장하지 않습니다.")
            Text("적용 기준: 기존 방식 대비 ${if (product == LotteryProduct.LOTTO_645) "0.25" else "0.20"}%p 이상 개선 추정치와 기존 방식·무작위 대비 보정 구간 하한 > 0. 두 제품의 반복 비교 16개를 미리 보정합니다.")
            state.result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (state.running) Text("과거 비교 ${state.completedCount}/${state.targetCount}회 · 완료 결과 재사용 ${state.reusedCount}회")
        }
    }
    if (confirmation) {
        AppConfirmDialog(
            title = "자동 개선 켜기",
            message = "앱이 비교 조건과 평가를 자동 준비합니다. 새 52회차마다 고정 기준으로 한 번 평가하고, 두 제품의 반복 비교를 보정해 기준을 통과한 설정만 적용·알림합니다. 미충족 시 새 구간으로 최대 4번까지 비교합니다. 과거 자료의 비교 결과만으로는 적용하지 않습니다. 백그라운드 계산은 배터리를 사용하며 언제든 끌 수 있습니다.",
            confirmText = "켜기",
            onDismiss = { confirmation = false },
            onConfirm = { confirmation = false; onEnabledChange(true) },
        )
    }
}
