package com.habittracker.data.lotto

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/** 단기 시험 적용 기록이다. 기존 FINAL 승인 파일과 분리하며 과거 비교를 성과 입증으로 취급하지 않는다. */
internal class LotteryAdaptiveSettings(context: Context) {
    private val root = File(context.applicationContext.filesDir.canonicalFile, "lottery-adaptive-settings")
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    suspend fun setting(product: LotteryProduct): Map<String, Any?>? = locked { records(product).lastOrNull { it["action"] != "HOLD" && it["policy"] == POLICY } }
    suspend fun value(product: LotteryProduct): Double? = setting(product)?.get("after")?.let(LottoDesignRecordJson::decimal)
    suspend fun last(product: LotteryProduct): Map<String, Any?>? = locked { records(product).lastOrNull() }
    suspend fun active(product: LotteryProduct): Map<String, Any?>? = locked {
        val log = records(product)
        log.lastOrNull { it["action"] == "APPLY" }?.takeIf { apply -> log.none { it["action"] == "ROLLBACK" && it["application"] == apply["recordHash"] } }
    }
    suspend fun append(product: LotteryProduct, action: String, before: Double, after: Double, proof: Map<String, Any?>,
        authorized: () -> Boolean = { true }): Map<String, Any?> = locked {
        require(action in listOf("APPLY", "ROLLBACK", "HOLD"))
        require(action != "HOLD" || before == after)
        validate(product, before); validate(product, after)
        val log = records(product)
        require(log.lastOrNull()?.get("after")?.let(LottoDesignRecordJson::decimal)?.let { it == before } != false) { "자동 설정이 계산 중 변경됐습니다: $product" }
        val body = mapOf("sequence" to log.size + 1, "product" to product.name, "policy" to POLICY,
            "action" to action, "before" to before, "after" to after, "at" to Instant.now().toString(),
            "previousHash" to log.lastOrNull()?.get("recordHash"), "proof" to proof,
            "application" to proof["application"], "beforeConfig" to configuration(product, before), "afterConfig" to configuration(product, after),
            "historyPresenceGuaranteed" to false, "performanceProven" to false)
        val record = body + ("recordHash" to LottoDesignRecordJson.hash(body))
        val directory = File(root, product.name)
        require(directory.isDirectory || directory.mkdirs()) { "자동 설정 폴더 생성 실패: $product" }
        require(authorized()) { "자동 개선이 해제되어 설정을 변경하지 않습니다." }
        LottoDesignRunStore.writeImmutable(File(directory, "${(log.size + 1).toString().padStart(8, '0')}.json"), LottoDesignRecordJson.encode(record).toByteArray())
        revision.value += 1
        record
    }
    suspend fun attempted(product: LotteryProduct): Boolean = locked { records(product).any { it["policy"] == POLICY && it["action"] in listOf("APPLY", "HOLD") } }
    private fun records(product: LotteryProduct): List<Map<String, Any?>> {
        val directory = File(root, product.name)
        if (!directory.exists()) return emptyList()
        require(directory.isDirectory && directory.canonicalFile == directory.absoluteFile) { "자동 설정 경로 오류: $product" }
        val files = requireNotNull(directory.listFiles()).sortedBy { it.name }
        var previous: String? = null
        var after: Double? = null
        return files.mapIndexed { index, file ->
            require(file.name == "${(index + 1).toString().padStart(8, '0')}.json" && file.canonicalFile == file.absoluteFile) { "자동 설정 이력 누락: $product" }
            val record = LottoDesignRecordJson.decode(file.readBytes())
            require(record["recordHash"] == LottoDesignRecordJson.hash(record - "recordHash") && record["previousHash"] == previous &&
                LottoDesignRecordJson.integer(record.getValue("sequence")) == index + 1 && record["product"] == product.name && record["policy"] in listOf(POLICY, "final-recommendation-selection-trial-v3", "candidate-stage-trial-6plus6-followup4-v2", "bounded-historical-trial-6plus6-followup4-v1")) { "자동 설정 무결성 오류: $product" }
            val before = LottoDesignRecordJson.decimal(record.getValue("before"))
            val current = LottoDesignRecordJson.decimal(record.getValue("after"))
            validate(product, before); validate(product, current)
            require(after == null || after == before) { "자동 설정 연결 오류: $product" }
            require(record["action"] in listOf("APPLY", "ROLLBACK", "HOLD")) { "자동 설정 동작 오류: $product" }
            if (record["action"] == "HOLD") require(before == current)
            previous = LottoDesignRecordJson.string(record.getValue("recordHash")); after = current
            record
        }
    }
    private fun validate(product: LotteryProduct, value: Double) {
        require(value in if (product == LotteryProduct.LOTTO_645) listOf(32.0, 64.0) else listOf(1.0, 0.5)) { "지원하지 않는 자동 가중치: $product=$value" }
    }
    private fun configuration(product: LotteryProduct, value: Double): String = configurationFor(product, value)
    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) { gate.withLock { block() } }
    companion object {
        private val gate = Mutex()
        const val POLICY = "final-recommendation-selection-trial-v4"
        fun configurationFor(product: LotteryProduct, value: Double): String = org.json.JSONObject(
            if (product == LotteryProduct.LOTTO_645) LottoNumberGenerator.configurationSnapshot(value)
            else com.habittracker.ui.lotto.pensionIndividualWeightConfiguration(value))
            .put("candidatePipelineId", LotteryCandidateArchive.IMPLEMENTATION)
            .apply {
                if (product == LotteryProduct.LOTTO_645) { put("activeMode", "PRECISE"); put("selectionDiversityStrength", if (value == 64.0) 0.5 else 1.0) }
                else { put("candidatePoolPerType", 250); put("finalistsPerType", 50); put("rankingPolicy", if (value == 0.5) "total_historical_score" else "last_digit_weight") }
            }.toString()
        fun acceptsTrial(baseline: List<Double>, candidate: List<Double>, control: List<Double>,
            baselineSecondary: List<Double>, candidateSecondary: List<Double>, controlSecondary: List<Double>): Boolean {
            require(listOf(baseline, candidate, control, baselineSecondary, candidateSecondary, controlSecondary).all { values -> values.size == 12 && values.all { it.isFinite() } })
            require(listOf(baseline, candidate, control).all { it.all { value -> value in 0.0..1.0 } })
            require(listOf(baselineSecondary, candidateSecondary, controlSecondary).all { it.all { value -> value in 0.0..6.0 } })
            return listOf(0 until 6, 6 until 12).all { indices ->
                fun average(values: List<Double>) = indices.map { values[it] }.average()
                average(candidate) > average(baseline) + 1e-9 && average(candidate) > average(control) + 1e-9 &&
                    average(candidateSecondary) - average(baselineSecondary) + 1e-9 >= 0.10 &&
                    average(candidateSecondary) - average(controlSecondary) + 1e-9 >= 0.10
            }
        }
        fun policy(): Map<String, Any?> = mapOf("id" to POLICY, "selectionRounds" to 6, "checkRounds" to 6,
            "followupRounds" to 4, "finalRecommendationStrictlyBetterAgainstBaselineAndControlInBothHalves" to true,
            "secondaryMinimumGainInBothHalves" to 0.10, "repeatedWindowSelection" to false,
            "label" to "과거 탐색 기반 시험 적용", "performanceProven" to false,
            "rollback" to "새 4회 구간에서 주 지표 하락 또는 보조 일치도 평균 0.10 이상 하락; 입력 정정 시 즉시 복구")
    }
}
