package com.habittracker.data.lotto

import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class LottoPriorDesignDraw(
    val roundNo: Int,
    val numbers: List<Int>,
    val bonusNumber: Int? = null,
)

data class LottoPriorDesignModel(
    val modelId: String,
    val priorDraws: Double,
    val configJson: String,
    val configHash: String,
)

class LottoPriorDesignPlan internal constructor(
    val draws: List<LottoPriorDesignDraw>,
    val historyStartRound: Int,
    val targetRounds: List<Int>,
    val commonSeeds: List<Long>,
    val baseline: LottoPriorDesignModel,
    val candidate: LottoPriorDesignModel,
    val datasetHash: String,
    val controlSeeds: List<Long> = emptyList(),
    val experiment: Map<String, Any?>? = null,
    val inputProofs: Map<String, String> = emptyMap(),
) {
    val implementationId: String = "lotto-prior-design-comparison-v5"
    val independentDrawCount: Int get() = targetRounds.size
    val candidateScoreCount: Long get() = targetRounds.size.toLong() * commonSeeds.size * 2 * 6000
    val candidateAttemptUpperBound: Long get() = candidateScoreCount * 20
    val selectedGameCount: Long get() = targetRounds.size.toLong() * commonSeeds.size * (if (controlSeeds.isEmpty()) 2 else 3) * 5
}

data class LottoPriorDesignBatch(
    val games: List<List<Int>>,
    val mainMatchCounts: List<Int>,
    val threePlusMatchRate: Double,
    val meanMainMatchCount: Double,
    val distinctNumberCount: Int,
    val reusedNumberSlots: Int,
    val pairOverlapCounts: List<Int>,
)

data class LottoPriorDesignSeedResult(
    val seed: Long,
    val baseline: LottoPriorDesignBatch,
    val candidate: LottoPriorDesignBatch,
    val controlSeed: Long? = null,
    val control: LottoPriorDesignBatch? = null,
)

data class LottoPriorDesignRoundResult(
    val targetRoundNo: Int,
    val historyThroughRound: Int,
    val historyDrawCount: Int,
    val inputDataHash: String,
    val targetDataHash: String,
    val seeds: List<LottoPriorDesignSeedResult>,
) {
    val baselineRate: Double get() = seeds.map { it.baseline.threePlusMatchRate }.average()
    val candidateRate: Double get() = seeds.map { it.candidate.threePlusMatchRate }.average()
    val pairedRateDifference: Double get() = candidateRate - baselineRate
    val controlRate: Double? get() = if (seeds.all { it.control != null }) seeds.map { requireNotNull(it.control).threePlusMatchRate }.average() else null
    val candidateControlDifference: Double? get() = controlRate?.let { candidateRate - it }
}

data class LottoPriorDesignResult(
    val plan: LottoPriorDesignPlan,
    val rounds: List<LottoPriorDesignRoundResult>,
) {
    val baselineRate: Double get() = rounds.map { it.baselineRate }.average()
    val candidateRate: Double get() = rounds.map { it.candidateRate }.average()
    val pairedRateDifference: Double get() = rounds.map { it.pairedRateDifference }.average()
    val designRoundsExcludedFromFinalEvaluation: List<Int> get() = plan.draws.map { it.roundNo }.sorted()

    fun requireDisjointFinalRounds(finalRounds: Collection<Int>) {
        val overlap = finalRounds.intersect(designRoundsExcludedFromFinalEvaluation.toSet())
        require(overlap.isEmpty()) { "설계에 사용한 회차는 독립 최종 평가에 재사용할 수 없습니다: $overlap" }
    }
}

class LottoPriorDesignFailure(
    val targetRoundNo: Int?,
    val modelId: String?,
    val seed: Long?,
    val stage: String,
    cause: Exception,
) : IllegalStateException(
    "설계 비교 실패: 회차=$targetRoundNo, 모델=$modelId, 시드=$seed, 단계=$stage, " +
        "원인=${cause.message ?: cause.javaClass.simpleName}",
    cause,
)

/** DB 저장·운영 반영 없이 표본 및 구간 설계를 위한 짝지은 관측값만 계산한다. */
object LottoPriorDesignComparison {
    // 제안값이다. 실행에는 호출자가 승인받은 목록을 명시적으로 전달해야 한다.
    const val PROPOSED_SEED_COUNT = 5

    fun prepare(
        draws: List<LottoPriorDesignDraw>,
        historyStartRound: Int,
        targetRounds: List<Int>,
        commonSeeds: List<Long>,
        controlSeeds: List<Long> = emptyList(),
        experiment: Map<String, Any?>? = null,
        inputProofs: Map<String, String> = emptyMap(),
    ): LottoPriorDesignPlan {
        require(historyStartRound > 0) { "설계용 입력 시작 회차는 1 이상이어야 합니다." }
        require(targetRounds.isNotEmpty() && targetRounds.distinct().size == targetRounds.size) {
            "설계용 대상 회차는 비어 있거나 중복될 수 없습니다."
        }
        require(targetRounds.all { it > historyStartRound }) { "모든 대상 회차에 이전 입력이 필요합니다." }
        require(commonSeeds.isNotEmpty() && commonSeeds.distinct().size == commonSeeds.size) {
            "공통 시드를 명시적으로 지정해야 하며 중복 시드는 허용하지 않습니다."
        }
        require(controlSeeds.isEmpty() || (controlSeeds.size == commonSeeds.size &&
            controlSeeds.distinct().size == controlSeeds.size && controlSeeds.intersect(commonSeeds.toSet()).isEmpty())) {
            "대조군은 공통 시드와 구분된 같은 개수의 고정 시드가 필요합니다."
        }
        require(experiment?.get("purpose") != "FINAL" ||
            (inputProofs.keys == targetRounds.map(Int::toString).toSet() && inputProofs.values.all { it.matches(Regex("[0-9a-f]{64}")) })) {
            "최종 실험에는 모든 대상 회차의 추첨 전 입력 증빙이 필요합니다."
        }
        require(draws.map { it.roundNo }.distinct().size == draws.size) { "입력에 중복 회차가 있습니다." }
        val lastTarget = targetRounds.max()
        val snapshot = draws.filter { it.roundNo in historyStartRound..lastTarget }
            .sortedByDescending { it.roundNo }
            .map { draw ->
                require(draw.numbers.size == 6 && draw.numbers.distinct().size == 6 &&
                    draw.numbers.all { it in 1..45 }) { "${draw.roundNo}회차 본번호가 유효하지 않습니다." }
                require(draw.bonusNumber == null ||
                    (draw.bonusNumber in 1..45 && draw.bonusNumber !in draw.numbers)) {
                    "${draw.roundNo}회차 보너스 번호가 유효하지 않습니다."
                }
                draw.copy(numbers = draw.numbers.sorted())
            }
        require(snapshot.size.toLong() == lastTarget.toLong() - historyStartRound + 1) {
            "설계용 입력 ${historyStartRound}~${lastTarget}회차에 누락이 있습니다."
        }
        fun model(prior: Double): LottoPriorDesignModel {
            val json = LottoNumberGenerator.configurationSnapshotForPriorDesign(prior)
            return LottoPriorDesignModel("lotto-balanced-basic-prior${prior.toInt()}", prior, json, hash(json))
        }
        return LottoPriorDesignPlan(
            snapshot, historyStartRound, targetRounds.sorted(), commonSeeds.toList(),
            model(32.0), model(64.0), drawHash(snapshot), controlSeeds.toList(), experiment, inputProofs.toMap(),
        )
    }

    // prepare는 입력 검토·비용 산정만 한다. 이 함수 호출은 별도의 평가 실행이다.
    suspend fun compare(
        plan: LottoPriorDesignPlan,
        onRoundCompleted: (suspend (LottoPriorDesignRoundResult) -> Unit)? = null,
    ): LottoPriorDesignResult {
        var currentRound: Int? = null
        var currentModel: String? = null
        var currentSeed: Long? = null
        var stage = "start"
        try {
            return withContext(Dispatchers.Default) {
                val byRound = plan.draws.associateBy { it.roundNo }
                val results = plan.targetRounds.map { targetRound ->
                    currentRound = targetRound
                    currentModel = null
                    currentSeed = null
                    stage = "round_input"
                    ensureActive()
                    val history = plan.draws.filter { it.roundNo < targetRound }
                    val winning = byRound.getValue(targetRound)
                    val seeds = plan.commonSeeds.mapIndexed { seedIndex, seed ->
                        currentSeed = seed
                        fun generate(model: LottoPriorDesignModel): LottoPriorDesignBatch {
                            currentModel = model.modelId
                            stage = "generate"
                            ensureActive()
                            val tickets = LottoNumberGenerator.generateBalancedForPriorDesign(
                                history = history.map { it.numbers }, seed = seed,
                                historyThroughRound = targetRound - 1, recentPriorDraws = model.priorDraws,
                            )
                            stage = "validate_games"
                            ensureActive()
                            require(tickets.size == 5) { "필요 게임 수=5, 생성 게임 수=${tickets.size}" }
                            stage = "evaluate_batch"
                            return batch(tickets.map { it.numbers }, winning.numbers)
                        }
                        val baseline = generate(plan.baseline)
                        val candidate = generate(plan.candidate)
                        val controlSeed = plan.controlSeeds.getOrNull(seedIndex)
                        val control = controlSeed?.let {
                            currentModel = "uniform-without-replacement-v1"
                            currentSeed = it
                            stage = "generate_control"
                            ensureActive()
                            batch(uniformGames(it), winning.numbers)
                        }
                        LottoPriorDesignSeedResult(seed, baseline, candidate, controlSeed, control)
                    }
                    currentModel = null
                    currentSeed = null
                    stage = "round_snapshot"
                    val completed = LottoPriorDesignRoundResult(
                        targetRound, targetRound - 1, history.size, drawHash(history), drawHash(listOf(winning)), seeds,
                    )
                    if (onRoundCompleted != null) {
                        stage = "round_record"
                        ensureActive()
                        onRoundCompleted(completed.recordingCopy())
                        ensureActive()
                    }
                    completed
                }
                stage = "complete"
                ensureActive()
                LottoPriorDesignResult(plan, results)
            }
        } catch (error: CancellationException) {
            error.addSuppressed(
                IllegalStateException("설계 비교 취소: 회차=$currentRound, 모델=$currentModel, 시드=$currentSeed, 단계=$stage"),
            )
            throw error
        } catch (error: Exception) {
            throw LottoPriorDesignFailure(currentRound, currentModel, currentSeed, stage, error)
        }
    }

    private fun LottoPriorDesignRoundResult.recordingCopy(): LottoPriorDesignRoundResult {
        fun LottoPriorDesignBatch.detached(): LottoPriorDesignBatch = copy(
            games = games.map { it.toList() }, mainMatchCounts = mainMatchCounts.toList(),
            pairOverlapCounts = pairOverlapCounts.toList(),
        )
        return copy(seeds = seeds.map { it.copy(baseline = it.baseline.detached(), candidate = it.candidate.detached(), control = it.control?.detached()) })
    }

    internal fun uniformGames(seed: Long): List<List<Int>> {
        return LottoNumberGenerator.generateRandomControl(gameCount = 5, seed = seed).map { it.numbers }
    }

    private fun batch(games: List<List<Int>>, winning: List<Int>): LottoPriorDesignBatch {
        val matches = games.map { game -> game.count { it in winning } }
        val coverage = games.flatten().distinct().size
        val overlaps = games.indices.flatMap { first ->
            (first + 1 until games.size).map { second -> games[first].count { it in games[second] } }
        }
        return LottoPriorDesignBatch(
            games, matches, matches.count { it >= 3 } / games.size.toDouble(),
            matches.average(), coverage, games.sumOf { it.size } - coverage, overlaps,
        )
    }

    private fun drawHash(draws: List<LottoPriorDesignDraw>): String = hash(
        draws.joinToString("|") { "${it.roundNo}:${it.numbers.joinToString(",")}:${it.bonusNumber}" },
    )

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
