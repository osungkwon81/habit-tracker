package com.habittracker.ui.lotto

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppSectionHeader
import com.habittracker.ui.components.AppSupportText
import com.habittracker.ui.components.AppSecondaryButton
import java.util.Locale

@Composable
internal fun PensionLotteryPerformanceOverview(summary: PensionLotteryPerformanceSummary) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    AppSectionCard {
        AppSectionHeader("수집 자료·추천 성과", "저장된 운영 추천과 같은 배치의 대조군을 비교합니다.")
        Text("저장된 대상 ${summary.recordedRoundCount}회차 · 비교 완료 ${summary.pairedRoundCount}회차")
        Text("저장 추천 ${summary.recommendationCount}개 · 대조군 ${summary.controlCount}개")
        AppSecondaryButton(text = if (showDetails) "집계 기준 접기" else "집계 기준 보기", onClick = { showDetails = !showDetails })
        if (showDetails) {
        AppSupportText("평가 대상 표시와 추첨 전 저장 시각을 확인한 기록: 추천 ${summary.preDrawRecommendationCount}개 · 대조군 ${summary.preDrawControlCount}개. 비교 집계에는 배치 구성·입력·평가 결과 확인도 필요합니다.")
        AppSupportText("숨긴 기록도 포함합니다. 같은 회차의 반복 배치는 회차 안에서 평균내며, 회차별 비중은 같습니다. 설정별 회차는 서로 겹칠 수 있습니다.")
        AppSupportText("예비 번호 ${summary.backupCount}개는 사전 추천 성과에서 제외합니다. 실제 구매 손익과 별도이며 실험 탭의 3개/4개 비교 결과는 합산하지 않습니다.")
        }
        if (summary.issues.isNotEmpty()) {
            AppSectionHeader("집계 보류·제외 사유", "각 배치의 첫 확인 사유를 표시합니다.")
            summary.issues.forEach { (reason, count) -> Text("$reason · ${count}배치") }
        }
        if (summary.settings.isEmpty()) Text("추천 4개와 대조군 4개의 평가가 모두 완료된 비교 회차가 없습니다.")
        AppSupportText("아래 수치는 완료 자료의 관찰값입니다. 누락·제외가 있으면 해석에 주의해야 하며, 무작위 대비 우위나 설정 채택을 판정하지 않습니다.")
    }
}

@Composable
internal fun PensionLotterySettingPerformanceSection(performance: PensionLotterySettingPerformance) {
    var showDetails by rememberSaveable(performance.configHash) { mutableStateOf(false) }
    AppSectionCard {
        AppSectionHeader("설정별 관찰 성과", "${performance.roundCount}회차 · ${performance.batchCount}배치 · 배치마다 추천 4개·대조군 4개")
        PerformanceRow("지표", "추천", "대조군")
        val recommended = performance.recommendation
        val control = performance.control
        PerformanceRow("끝 2자리 이상", percent(recommended.suffix2Rate), percent(control.suffix2Rate))
        Text("주 지표 차이(추천−대조군): ${String.format(Locale.ROOT, "%+.2f%%p", (recommended.suffix2Rate - control.suffix2Rate) * 100)}")
        AppSecondaryButton(text = if (showDetails) "보조 지표·설정 접기" else "보조 지표·설정 보기", onClick = { showDetails = !showDetails })
        if (showDetails) {
        AppSupportText("생성 버전 ${performance.generationVersion}\n설정 ${performance.configHash}")
        PerformanceRow("끝 1자리 이상", percent(recommended.suffix1Rate), percent(control.suffix1Rate))
        PerformanceRow("끝 3자리 이상", percent(recommended.suffix3Rate), percent(control.suffix3Rate))
        PerformanceRow("보너스 6자리", percent(recommended.bonusRate), percent(control.bonusRate))
        AppSupportText("끝자리 종류 수는 배치 4개 번호의 평균 커버리지이며 당첨 확률 개선율이 아닙니다.")
        PerformanceRow("끝 1자리 종류", coverage(recommended.lastDigitCoverage), coverage(control.lastDigitCoverage))
        PerformanceRow("끝 2자리 종류", coverage(recommended.lastTwoDigitsCoverage), coverage(control.lastTwoDigitsCoverage))
        }
    }
}

@Composable
private fun PerformanceRow(label: String, recommended: String, control: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1.4f))
        Text(recommended, Modifier.weight(1f), textAlign = TextAlign.End)
        Text(control, Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

private fun percent(value: Double) = String.format(Locale.ROOT, "%.2f%%", value * 100)
private fun coverage(value: Double) = String.format(Locale.ROOT, "%.2f / 4", value)
