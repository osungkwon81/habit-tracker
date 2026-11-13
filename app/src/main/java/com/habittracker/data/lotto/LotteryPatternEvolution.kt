package com.habittracker.data.lotto

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/** 회차별 관찰에서 만든 보조 점수. 같은 회차의 여러 게임은 지지 회차를 늘리지 않는다. */
data class LotteryPatternModel(val weights: Map<String, Double> = emptyMap(), val throughRound: Int = 0) {
    fun adjustment(number: String, lotto: Boolean): Double =
        keys(number, lotto).map { weights[it] ?: 0.0 }.average().takeIf { it.isFinite() } ?: 0.0

    internal fun json(): Map<String, Any?> = mapOf("weights" to weights.toSortedMap(), "throughRound" to throughRound,
        "implementation" to IMPLEMENTATION)

    internal fun admits(number: String): Boolean = keys(number, false).any {
        it.startsWith("반복구성=") && (weights[it] ?: 0.0) > 0.0
    }

    companion object {
        const val IMPLEMENTATION = "pattern-observation-v1"
        internal fun from(raw: Any?): LotteryPatternModel {
            if (raw == null) return LotteryPatternModel()
            val value = LottoDesignRecordJson.objectValue(raw)
            require(value["implementation"] == IMPLEMENTATION) { "패턴 구현을 확인할 수 없습니다." }
            val weights = LottoDesignRecordJson.objectValue(value.getValue("weights")).mapValues {
                LottoDesignRecordJson.decimal(it.value).also { score -> require(score.isFinite() && score in -10.0..10.0) }
            }
            return LotteryPatternModel(weights, LottoDesignRecordJson.integer(value.getValue("throughRound")))
        }

        internal fun keys(number: String, lotto: Boolean): List<String> {
            val digits = if (lotto) number.split(',').map(String::toInt).sorted() else number.map(Char::digitToInt)
            val repeats = digits.groupingBy { it }.eachCount().values.sortedDescending().joinToString("+")
            return if (lotto) listOf("홀수=${digits.count { it % 2 == 1 }}", "합계=${digits.sum()}",
                "연속=${digits.zipWithNext().count { (a, b) -> b == a + 1 }}",
                "번호대=${digits.map { (it - 1) / 10 }.distinct().size}", "32이상=${digits.count { it >= 32 }}")
            else listOf("반복구성=$repeats", "끝자리=${digits.last()}",
                "끝두자리=${number.takeLast(2)}", "인접반복=${digits.zipWithNext().count { (a, b) -> a == b }}")
        }
    }
}

/** 제안 고정 → 이후 12회 비교 → 적용 → 새 4회 감시를 불변 사건으로 보존한다. */
internal class LotteryPatternEvolution(context: Context) {
    private val app = context.applicationContext
    private val root = File(app.filesDir.canonicalFile, "lottery-adaptive-settings/patterns")
    val changes = MutableStateFlow(0L)

    suspend fun model(product: LotteryProduct): LotteryPatternModel = locked { current(records(product)) }

    suspend fun trial(product: LotteryProduct): Map<String, Any?>? = locked {
        records(product).lastOrNull()?.takeIf { it["action"] in listOf("PROPOSE", "APPLY", "MONITOR") }
    }

    suspend fun canRollback(product: LotteryProduct): Boolean = locked {
        records(product).lastOrNull()?.get("action") in listOf("PROPOSE", "APPLY", "MONITOR", "COMMIT")
    }

    suspend fun rollback(product: LotteryProduct) = locked {
        val last = records(product).lastOrNull() ?: return@locked
        if (last["action"] in listOf("APPLY", "MONITOR", "COMMIT", "PROPOSE")) append(product, "ROLLBACK", last,
            mapOf("reason" to "사용자 요청으로 패턴 점수와 선별 기준을 복구했습니다."))
    }

    suspend fun update(input: LotteryResearchInput, evaluations: List<Map<String, Any?>>, operatingValue: Double) = locked {
        if (!LotteryAutoImprovementWorker.isEnabled(app, input.product)) return@locked
        val log = records(input.product)
        val last = log.lastOrNull()
        val through = requireNotNull(input.rounds().maxOrNull())
        val latestHash = LottoDesignRecordJson.hash(input.generationJson())
        if (last != null && last["action"] in listOf("PROPOSE", "APPLY", "MONITOR", "COMMIT")) {
            val trainedThrough = LottoDesignRecordJson.integer(last.getValue("trainedThrough"))
            val checkedThrough = LottoDesignRecordJson.integer(last.getValue("checkedThrough"))
            val checkedHash = last["checkedHash"]
            if (LottoDesignRecordJson.hash(input.through(trainedThrough).generationJson()) != last["trainingHash"] ||
                (checkedHash != null && LottoDesignRecordJson.hash(input.through(checkedThrough).generationJson()) != checkedHash) ||
                LottoDesignRecordJson.decimal(last.getValue("operatingValue")) != operatingValue) {
                append(input.product, "ROLLBACK", last, mapOf("reason" to "패턴 관찰·평가 입력 또는 운영 가중치가 변경되었습니다."))
                return@locked
            }
        }
        if (last != null && last["action"] in listOf("PROPOSE", "APPLY", "MONITOR")) {
            val trialId = last.getValue("trialId")
            val after = LottoDesignRecordJson.integer(last.getValue("checkedThrough"))
            val needed = if (last["action"] == "PROPOSE") 12 else 4
            val rounds = ((after + 1)..through).take(needed)
            if (rounds.size < needed) return@locked
            val fresh = rounds.map { round -> evaluations.singleOrNull { it["round"] == round && it["patternTrialId"] == trialId && it["preDraw"] == true } }
            if (fresh.any { it == null }) {
                append(input.product, "ROLLBACK", last, mapOf("reason" to "사전 고정 패턴 평가 누락 또는 입력 변경", "rounds" to rounds))
                return@locked
            }
            val results = fresh.filterNotNull()
            fun values(role: String, key: String) = results.map {
                val models = LottoDesignRecordJson.objectValue(it.getValue("models"))
                LottoDesignRecordJson.decimal(LottoDesignRecordJson.objectValue(models.getValue(role)).getValue(key))
            }
            val prefixes = if (input.product == LotteryProduct.LOTTO_645) listOf("pattern", "patternDiversified") else listOf("pattern")
            val accepted = prefixes.all { prefix ->
                val base = values("${prefix}Baseline", "rate")
                val next = values("${prefix}Candidate", "rate")
                val baseSecondary = values("${prefix}Baseline", "secondary")
                val nextSecondary = values("${prefix}Candidate", "secondary")
                if (last["action"] == "PROPOSE") LotteryAdaptiveSettings.acceptsTrial(base, next,
                    values("control", "rate"), baseSecondary, nextSecondary, values("control", "secondary"))
                else next.average() + 1e-9 >= base.average() && nextSecondary.average() - baseSecondary.average() > -0.10
            }
            val action = if (last["action"] == "PROPOSE") { if (accepted) "APPLY" else "HOLD" }
                else if (accepted) "COMMIT" else "ROLLBACK"
            append(input.product, action, last, mapOf("checkedThrough" to rounds.last(), "rounds" to rounds,
                "checkedHash" to LottoDesignRecordJson.hash(input.through(rounds.last()).generationJson()),
                "evaluationHashes" to results.map(LottoDesignRecordJson::hash), "reason" to "패턴 후보의 최종 추천 비교 $action"))
            return@locked
        }

        // 과거 자료는 패턴 발견에만 쓴다. 적용 판단은 제안 이후 사전 고정 회차로 한다.
        if (last?.get("observedThrough") == through) return@locked
        val lotto = input.product == LotteryProduct.LOTTO_645
        val observations = if (lotto) input.lotto.map { it.roundNo to it.numbers().sorted().joinToString(",") }
            else input.pension.map { it.roundNo to it.winningNumber }
        val counts = observations.flatMap { (round, number) -> LotteryPatternModel.keys(number, lotto).map { it to round } }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.distinct().size }
        val performance = linkedMapOf<String, MutableList<Double>>()
        val trainingEvaluations = evaluations.filter { it["preDraw"] == true }
            .distinctBy { it["candidateHash"] ?: LottoDesignRecordJson.hash(it) }
        for ((_, roundEvaluations) in trainingEvaluations.groupBy { LottoDesignRecordJson.integer(it.getValue("round")) }) {
            val roundSignals = linkedMapOf<String, MutableList<Double>>()
            for (evaluation in roundEvaluations) {
                val models = LottoDesignRecordJson.objectValue(evaluation.getValue("models"))
                val baseline = LottoDesignRecordJson.objectValue(models["user"] ?: models["patternBaseline"] ?: models.getValue("baseline"))
                for (raw in LottoDesignRecordJson.list(baseline.getValue("stages"))) {
                    val stage = LottoDesignRecordJson.objectValue(raw)
                    val generated = LottoDesignRecordJson.objectValue(stage.getValue("generated"))
                    val reference = LottoDesignRecordJson.decimal(generated.getValue("rate"))
                    val outcomes = stage["patternOutcomes"]?.let(LottoDesignRecordJson::objectValue).orEmpty()
                    for ((key, rawOutcome) in outcomes) {
                        val outcome = LottoDesignRecordJson.objectValue(rawOutcome)
                        val rate = LottoDesignRecordJson.integer(outcome.getValue("hits")).toDouble() /
                            LottoDesignRecordJson.integer(outcome.getValue("count"))
                        val difference = (rate - reference) / (reference + 1.0 / LottoDesignRecordJson.integer(generated.getValue("count")))
                        roundSignals.getOrPut(key) { mutableListOf() }.add(difference.coerceIn(-1.0, 1.0))
                    }
                }
            }
            for ((key, values) in roundSignals) performance.getOrPut(key) { mutableListOf() }.add(values.average())
        }
        val weights = counts.filterValues { it >= 2 }.mapValues { (key, count) ->
            val evidence = performance[key].orEmpty()
            // 배치·시드 수가 커져도 한 회차의 영향은 한 번만 반영한다.
            if (evidence.size >= 2) (10.0 * evidence.sum() / (evidence.size + 2.0)).coerceIn(-10.0, 10.0)
            else 10.0 * count / (observations.size + 2.0)
        }
        val proposal = LotteryPatternModel(weights, through)
        val before = current(log)
        if (proposal.weights == before.weights) return@locked
        append(input.product, "PROPOSE", mapOf("before" to before.json(), "after" to proposal.json(),
            "trainedThrough" to through, "checkedThrough" to through, "observedThrough" to through,
            "trainingHash" to latestHash, "observations" to counts.toSortedMap(),
            "outcomeRoundCounts" to performance.mapValues { it.value.size }.toSortedMap(),
            "trainingEvaluationHashes" to trainingEvaluations.map(LottoDesignRecordJson::hash).sorted(),
            "userObservationRounds" to trainingEvaluations.filter { it["observationSource"] == "user" }
                .map { LottoDesignRecordJson.integer(it.getValue("round")) }.distinct().sorted(),
            "operatingValue" to operatingValue,
            "trialId" to LottoDesignRecordJson.hash(mapOf("model" to proposal.json(), "input" to latestHash))), emptyMap())
    }

    suspend fun summary(product: LotteryProduct): String = locked {
        val last = records(product).lastOrNull() ?: return@locked "패턴 관찰 대기"
        val counts = LottoDesignRecordJson.objectValue(last.getValue("observations"))
        val repeated = counts.count { LottoDesignRecordJson.integer(it.value) >= 2 }
        val action = when (last["action"]) {
            "PROPOSE" -> "새 패턴 비교 중"
            "APPLY", "MONITOR" -> "시험 적용 후 점검 중"
            "COMMIT" -> "점검 통과 · 다음 패턴 관찰"
            "HOLD" -> "조건 미충족 · 적용 보류"
            else -> "이전 패턴 설정 복구"
        }
        val model = LotteryPatternModel.from(last.getValue("after"))
        val details = counts.entries.sortedByDescending { LottoDesignRecordJson.integer(it.value) }.take(6).joinToString("\n") {
            "${it.key} · ${it.value}회 관찰 · 제안 보정 ${model.weights[it.key] ?: 0.0}"
        }
        val userRounds = last["userObservationRounds"]?.let(LottoDesignRecordJson::list)?.size ?: 0
        "반복 패턴 ${repeated}개 · $action · 직접 생성 관찰 ${userRounds}회차 · 평가 기준 ${last["checkedThrough"]}회 · 추첨 전 고정 12회 비교/적용 후 4회 점검\n$details"
    }

    private fun current(log: List<Map<String, Any?>>): LotteryPatternModel {
        val last = log.lastOrNull() ?: return LotteryPatternModel()
        return LotteryPatternModel.from(last[if (last["action"] in listOf("APPLY", "MONITOR", "COMMIT")) "after" else "before"])
    }

    private fun records(product: LotteryProduct): List<Map<String, Any?>> {
        val folder = File(root, product.name)
        if (!folder.exists()) return emptyList()
        require(folder.canonicalFile == folder.absoluteFile && folder.isDirectory) { "패턴 기록 폴더 경로가 유효하지 않습니다: $product" }
        var previous: String? = null
        return requireNotNull(folder.listFiles()).sortedBy { it.name }.mapIndexed { index, file ->
            require(file.name == "${(index + 1).toString().padStart(8, '0')}.json") { "패턴 기록 누락: $product" }
            require(file.isFile && file.canonicalFile == file.absoluteFile) { "패턴 기록 경로 오류: $product" }
            val record = LottoDesignRecordJson.decode(file.readBytes())
            require(record["previousHash"] == previous && record["recordHash"] == LottoDesignRecordJson.hash(record - "recordHash")) { "패턴 기록 손상: $product" }
            require(record["product"] == product.name && record["implementation"] == LotteryPatternModel.IMPLEMENTATION &&
                record["action"] in listOf("PROPOSE", "APPLY", "MONITOR", "COMMIT", "HOLD", "ROLLBACK")) { "패턴 기록 형식 오류: $product" }
            LotteryPatternModel.from(record["before"])
            LotteryPatternModel.from(record["after"])
            previous = record["recordHash"] as String
            record
        }
    }

    private fun append(product: LotteryProduct, action: String, prior: Map<String, Any?>, evidence: Map<String, Any?>) {
        val log = records(product)
        val body = (prior - setOf("recordHash", "previousHash")) + evidence + mapOf("action" to action,
            "product" to product.name, "implementation" to LotteryPatternModel.IMPLEMENTATION,
            "at" to Instant.now().toString(), "previousHash" to log.lastOrNull()?.get("recordHash"),
            "performanceProven" to false, "historyPresenceGuaranteed" to false)
        val folder = File(root, product.name)
        require(folder.isDirectory || folder.mkdirs()) { "패턴 기록 폴더 생성 실패: $product" }
        require(action == "ROLLBACK" || LotteryAutoImprovementWorker.isEnabled(app, product)) { "자동 개선이 해제되었습니다." }
        LottoDesignRunStore.writeImmutable(File(folder, "${(log.size + 1).toString().padStart(8, '0')}.json"),
            LottoDesignRecordJson.encode(body + ("recordHash" to LottoDesignRecordJson.hash(body))).toByteArray())
        changes.value += 1
    }

    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) { gate.withLock { block() } }
    companion object { private val gate = Mutex() }
}
