package com.habittracker.data.lotto

import com.habittracker.data.local.entity.PensionLotteryDrawEntity
import com.habittracker.ui.lotto.PensionGenerationTrace
import com.habittracker.ui.lotto.generatePensionExperimentBatch
import com.habittracker.ui.lotto.pensionExperimentConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

internal class PensionComparisonFailure(
    val round: Int?, val model: String?, val seed: Long?, val stage: String,
    val diagnostics: Map<String, Any?>, cause: Exception,
) : IllegalStateException("연금 비교 실패: 회차=$round, 모델=$model, 시드=$seed, 단계=$stage, 원인=${cause.message}", cause)

internal object PensionExperimentComparison {
    const val IMPLEMENTATION = "pension-last-digit-comparison-v1"
    fun configuration(count: Int) = LottoDesignRecordJson.decode(pensionExperimentConfiguration(count).toByteArray(), requireCanonical = false)
    fun input(draws: List<PensionLotteryDrawEntity>, exclusions: Set<String>): Map<String, Any?> {
        require(draws.isNotEmpty() && draws.map { it.roundNo }.distinct().size == draws.size) { "연금 입력 회차가 없거나 중복됩니다." }
        val sorted = draws.sortedByDescending { it.roundNo }
        require(sorted.zipWithNext().all { (a, b) -> a.roundNo == b.roundNo + 1 }) { "연금 입력 회차가 누락됐습니다." }
        sorted.forEach { validateNumber(it.winningNumber); it.bonusNumber?.let(::validateNumber); require(it.groupNo in 1..5) }
        exclusions.forEach(::validateNumber)
        return mapOf("draws" to sorted.map { mapOf("roundNo" to it.roundNo, "groupNo" to it.groupNo,
            "winningNumber" to it.winningNumber, "bonusNumber" to it.bonusNumber,
            "drawDate" to it.drawDate?.toString(), "sourceReference" to it.sourceReference,
            "sourceContentHash" to it.sourceContentHash, "collectedAt" to it.collectedAt?.toString()) }, "exclusions" to exclusions.sorted())
    }
    fun draws(input: Map<String, Any?>): List<PensionLotteryDrawEntity> = list(input.getValue("draws")).map { entry ->
        val value = obj(entry)
        PensionLotteryDrawEntity(int(value.getValue("roundNo")), int(value.getValue("groupNo")), text(value, "winningNumber"),
            value["bonusNumber"]?.let(LottoDesignRecordJson::string),
            drawDate = value["drawDate"]?.let { java.time.LocalDate.parse(LottoDesignRecordJson.string(it)) }, sourceReference = value["sourceReference"]?.let(LottoDesignRecordJson::string),
            sourceContentHash = value["sourceContentHash"]?.let(LottoDesignRecordJson::string),
            collectedAt = value["collectedAt"]?.let { java.time.LocalDateTime.parse(LottoDesignRecordJson.string(it)) })
    }
    suspend fun compare(
        target: PensionLotteryDrawEntity, frozenInput: Map<String, Any?>, common: List<Long>, control: List<Long>,
        onModel: suspend (Map<String, Any?>) -> Unit,
    ): Map<String, Any?> = withContext(Dispatchers.Default) {
        var model: String? = null
        var seed: Long? = null
        var stage = "input"
        var trace: PensionGenerationTrace? = null
        try {
            val history = draws(frozenInput)
            val excluded = list(frozenInput.getValue("exclusions")).map(LottoDesignRecordJson::string).toSet()
            require(input(history, excluded) == frozenInput && history.first().roundNo == target.roundNo - 1 && history.size >= 17) { "당시 입력의 회차·형식·분석 기간 오류" }
            require(common.isNotEmpty() && common.distinct().size == common.size && control.size == common.size && control.distinct().size == control.size && common.intersect(control.toSet()).isEmpty()) { "공통·대조군 시드 오류" }
            val context = coroutineContext
            val seeds = common.mapIndexed { index, commonSeed ->
                val batches = linkedMapOf<String, Any?>()
                for ((role, count) in listOf("BASELINE" to 3, "CANDIDATE" to 4)) {
                    model = role; seed = commonSeed; stage = "generate"
                    trace = PensionGenerationTrace { context.ensureActive() }
                    val numbers = generatePensionExperimentBatch(history, count, commonSeed, excluded, requireNotNull(trace))
                    require(numbers.size == 4 && numbers.map { it.type }.distinct().size == 4 && numbers.map { it.winningNumber }.distinct().size == 4) { "전체 4개 배치가 아닙니다." }
                    val batch = evaluate(numbers.map { mapOf("groupNo" to it.groupNo, "number" to it.winningNumber, "type" to it.type.name, "seed" to it.generationSeed.toString()) }, target) +
                        mapOf("diagnostics" to requireNotNull(trace).snapshot(), "configHash" to LottoDesignRecordJson.hash(configuration(count)))
                    stage = "model_record"
                    onModel(mapOf("round" to target.roundNo, "model" to role, "seed" to commonSeed.toString(), "batch" to batch))
                    batches[role] = batch
                }
                model = "CONTROL"; seed = control[index]; stage = "generate_control"; trace = null
                context.ensureActive()
                val batch = evaluate(uniform(control[index]), target)
                stage = "model_record"
                onModel(mapOf("round" to target.roundNo, "model" to model, "seed" to control[index].toString(), "batch" to batch))
                batches["CONTROL"] = batch
                mapOf("seed" to commonSeed.toString(), "controlSeed" to control[index].toString(), "batches" to batches)
            }
            fun rate(role: String) = seeds.map { LottoDesignRecordJson.decimal(obj(obj(it.getValue("batches")).getValue(role)).getValue("suffix2Rate")) }.average()
            mapOf("targetRound" to target.roundNo, "target" to mapOf("groupNo" to target.groupNo, "number" to target.winningNumber, "bonus" to target.bonusNumber),
                "targetData" to input(listOf(target), emptySet()), "targetDataHash" to LottoDesignRecordJson.hash(input(listOf(target), emptySet())),
                "inputHash" to LottoDesignRecordJson.hash(frozenInput), "seeds" to seeds,
                "baselineRate" to rate("BASELINE"), "candidateRate" to rate("CANDIDATE"), "controlRate" to rate("CONTROL"),
                "candidateBaselineDifference" to rate("CANDIDATE") - rate("BASELINE"), "candidateControlDifference" to rate("CANDIDATE") - rate("CONTROL"))
        } catch (error: Exception) {
            val context = PensionComparisonFailure(target.roundNo, model, seed, stage, trace?.snapshot().orEmpty(), error)
            if (error is CancellationException) { error.addSuppressed(context); throw error }
            throw context
        }
    }
    // 번호 공간을 균등 비복원 추출한다. 조는 각 번호마다 1~5 균등 추출한다.
    fun uniform(seed: Long): List<Map<String, Any?>> {
        val random = Random(seed)
        val remapping = mutableMapOf<Int, Int>()
        return List(4) { index ->
            val remaining = 1_000_000 - index
            val pick = random.nextInt(remaining)
            val number = remapping.getOrDefault(pick, pick)
            remapping[pick] = remapping.getOrDefault(remaining - 1, remaining - 1)
            mapOf("groupNo" to random.nextInt(1, 6), "number" to number.toString().padStart(6, '0'), "type" to "CONTROL", "seed" to seed.toString())
        }
    }
    fun evaluate(numbers: List<Map<String, Any?>>, target: PensionLotteryDrawEntity): Map<String, Any?> {
        require(numbers.size == 4)
        validateNumber(target.winningNumber); target.bonusNumber?.let(::validateNumber)
        val suffix = numbers.map { ticket -> val number = text(ticket, "number"); validateNumber(number); require(int(ticket.getValue("groupNo")) in 1..5)
            number.reversed().zip(target.winningNumber.reversed()).takeWhile { (a, b) -> a == b }.size }
        return mapOf("numbers" to numbers, "suffixMatches" to suffix,
            "suffix1Rate" to suffix.count { it >= 1 } / 4.0, "suffix2Rate" to suffix.count { it >= 2 } / 4.0, "suffix3Rate" to suffix.count { it >= 3 } / 4.0,
            "samePositionMatches" to numbers.map { text(it, "number").zip(target.winningNumber).count { (a, b) -> a == b } },
            "bonusMatches" to numbers.map { target.bonusNumber?.let { bonus -> text(it, "number") == bonus } })
    }
    private fun validateNumber(value: String) { require(value.matches(Regex("[0-9]{6}"))) { "연금 번호는 ASCII 숫자 6자리여야 합니다." } }
    private fun obj(value: Any?) = LottoDesignRecordJson.objectValue(value)
    private fun list(value: Any?) = LottoDesignRecordJson.list(value)
    private fun int(value: Any?) = LottoDesignRecordJson.integer(value)
    private fun text(value: Map<String, Any?>, key: String) = LottoDesignRecordJson.string(value.getValue(key))
}
