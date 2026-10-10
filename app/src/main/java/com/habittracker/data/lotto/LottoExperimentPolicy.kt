package com.habittracker.data.lotto

import java.time.Instant
import kotlin.math.ln
import kotlin.math.sqrt

enum class LottoExperimentDecision(val label: String) {
    POLICY_UNCONFIRMED("정책 미확정"), DESIGN_ONLY("설계 참고용"),
    HOLD("보류"), REJECT("기각"), ADOPTION_CANDIDATE("채택 후보"),
}

data class LottoDifferenceInterval(val estimate: Double, val lower: Double, val upper: Double)
data class LottoExperimentJudgment(
    val decision: LottoExperimentDecision,
    val reason: String,
    val independentRounds: Int,
    val baseline95: LottoDifferenceInterval? = null,
    val control95: LottoDifferenceInterval? = null,
    val baselineAdjusted: LottoDifferenceInterval? = null,
    val controlAdjusted: LottoDifferenceInterval? = null,
)

/** 수치나 통계 가정을 자동 확정하지 않는다. 정책 문서는 실험 등록 시 사용자가 확인한다. */
internal object LottoExperimentPolicy {
    const val METHOD = "bounded-martingale-hoeffding-v1"

    fun validate(policy: Map<String, Any?>, targetCount: Int) {
        require(policy["confirmedByUser"] == true) { "정책의 사용자 확정이 필요합니다." }
        Instant.parse(text(policy, "confirmedAt"))
        require(policy["primaryMetric"] == "main-three-plus-game-proportion") { "주 지표는 본번호 3개 이상 게임 비율이어야 합니다." }
        require(number(policy, "confidenceLevel") == 0.95) { "지원하는 신뢰수준은 합의된 95%입니다." }
        require(number(policy, "minimumEffect") > 0 && number(policy, "minimumEffect") <= 1) { "최소 개선 폭을 비율 단위로 확정해 주세요." }
        require(integer(policy, "finalRoundCount") == targetCount && targetCount > 0) { "최종 회차 수가 고정 구간과 다릅니다." }
        require(integer(policy, "familyComparisonCount") >= 2) { "채택에 쓰는 비교 전체(최소 2개)의 수를 명시해 주세요." }
        require(policy["intervalMethod"] == METHOD) { "지원하는 구간 방법을 명시적으로 확정해 주세요: $METHOD" }
        require(text(policy, "assumptions").isNotBlank() && text(policy, "sampleSizeRationale").isNotBlank()) { "구간 가정과 최종 표본 수 산정 근거가 필요합니다." }
        require(policy["rejectWhenUpperNonPositive"] is Boolean) { "기각 기준을 명시적으로 확정해 주세요." }
        require(policy["singleFinalLook"] == true && policy["noEarlyAdoption"] == true) { "고정 구간 1회 평가·조기 채택 금지가 필요합니다." }
    }

    fun judge(spec: Map<String, Any?>, run: LottoDesignRecoveredRun?, qualityIssue: String? = null): LottoExperimentJudgment {
        val n = run?.diagnosticRounds?.size ?: 0
        val policy = spec["policy"]?.let(LottoDesignRecordJson::objectValue)
        if (policy == null || policy["confirmedByUser"] != true) return LottoExperimentJudgment(LottoExperimentDecision.POLICY_UNCONFIRMED, "등록 정책이 미확정이므로 채택·기각·운영 적용을 할 수 없습니다.", n)
        validate(policy, LottoDesignRecordJson.list(spec.getValue("targetRounds")).size)
        if (spec["purpose"] != "FINAL") return LottoExperimentJudgment(LottoExperimentDecision.DESIGN_ONLY, "설계용 결과는 최종 채택 근거가 아닙니다.", n)
        if (qualityIssue != null || run == null || run.state != LottoDesignRunState.SUCCESS || run.exclusionCoverageUncertain)
            return LottoExperimentJudgment(LottoExperimentDecision.HOLD, qualityIssue ?: "성공한 전체 기록과 입력·참조 무결성이 필요합니다.", n)
        if (n != integer(policy, "finalRoundCount") || run.diagnosticRounds.any { it.controlRate == null })
            return LottoExperimentJudgment(LottoExperimentDecision.HOLD, "고정된 최종 회차 또는 대조군 자료가 부족합니다.", n)
        val baseline = run.diagnosticRounds.map { it.pairedRateDifference }
        val control = run.diagnosticRounds.map { requireNotNull(it.candidateControlDifference) }
        val family = integer(policy, "familyComparisonCount")
        val b95 = interval(baseline, 0.05)
        val c95 = interval(control, 0.05)
        val b = interval(baseline, 0.05 / family)
        val c = interval(control, 0.05 / family)
        val accepted = b.estimate >= number(policy, "minimumEffect") && b.lower > 0 && c.lower > 0
        val rejected = policy["rejectWhenUpperNonPositive"] == true && (b.upper <= 0 || c.upper <= 0)
        return LottoExperimentJudgment(
            if (accepted) LottoExperimentDecision.ADOPTION_CANDIDATE else if (rejected) LottoExperimentDecision.REJECT else LottoExperimentDecision.HOLD,
            if (accepted) "개선 추정치 문턱과 두 비교의 Bonferroni 보정 하한 조건을 충족했습니다. 실제 개선 폭이 문턱 이상임을 입증한 뜻은 아닙니다. 사용자 승인 전에는 적용하지 않습니다."
            else if (rejected) "사전 확정한 기각 기준(비교 차이의 보정 상한 0 이하)에 해당합니다."
            else "채택 조건 미충족입니다. 기존 설정을 유지하고 같은 구간을 늘려 재검정하지 않습니다.",
            n, b95, c95, b, c,
        )
    }

    // [-1,1] 회차 차이의 조건부 평균에 대한 보수적 마팅게일 경계.
    // 시드·게임은 표본 수에 더하지 않고, 성공 0건이어도 구간 폭을 유지한다.
    internal fun interval(differences: List<Double>, alpha: Double): LottoDifferenceInterval {
        require(differences.isNotEmpty() && differences.all { it.isFinite() && it in -1.0..1.0 } && alpha > 0 && alpha < 1)
        val mean = differences.average()
        val radius = sqrt(2.0 * ln(2.0 / alpha) / differences.size)
        return LottoDifferenceInterval(mean, (mean - radius).coerceAtLeast(-1.0), (mean + radius).coerceAtMost(1.0))
    }

    private fun text(value: Map<String, Any?>, key: String) = LottoDesignRecordJson.string(value.getValue(key))
    private fun number(value: Map<String, Any?>, key: String) = LottoDesignRecordJson.decimal(value.getValue(key))
    private fun integer(value: Map<String, Any?>, key: String) = LottoDesignRecordJson.integer(value.getValue(key))
}
