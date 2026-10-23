package com.habittracker.data.lotto

import android.content.Context
import android.util.Log
import com.habittracker.data.local.entity.LottoDrawEntity
import com.habittracker.data.local.entity.PensionLotteryDrawEntity
import com.habittracker.data.repository.HabitRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.util.UUID

data class LotteryAutoImprovementState(
    val audit: LotteryHistoryAudit? = null,
    val enabled: Boolean = false,
    val running: Boolean = false,
    val firstTarget: Int? = null,
    val lastTarget: Int? = null,
    val targetCount: Int = 0,
    val completedCount: Int = 0,
    val reusedCount: Int = 0,
    val candidateWorkCount: Long = 0,
    val result: String? = null,
    val evaluationStatus: String? = null,
    val issue: String? = null,
)

internal data class LotteryResearchInput(
    val product: LotteryProduct,
    val lotto: List<LottoDrawEntity> = emptyList(),
    val pension: List<PensionLotteryDrawEntity> = emptyList(),
) {
    fun audit() = when (product) {
        LotteryProduct.LOTTO_645 -> LotteryHistoryAuditor.lotto(lotto)
        LotteryProduct.PENSION_720 -> LotteryHistoryAuditor.pension(pension)
    }
    fun rounds(): List<Int> = when (product) {
        LotteryProduct.LOTTO_645 -> lotto.map { it.roundNo }
        LotteryProduct.PENSION_720 -> pension.map { it.roundNo }
    }
    fun through(round: Int) = copy(lotto = lotto.filter { it.roundNo <= round }, pension = pension.filter { it.roundNo <= round })
    fun json(): Map<String, Any?> = when (product) {
        LotteryProduct.LOTTO_645 -> mapOf("draws" to lotto.sortedByDescending { it.roundNo }.map { draw ->
            mapOf("roundNo" to draw.roundNo, "numbers" to draw.numbers(), "bonusNumber" to draw.bonusNumber,
                "sourceReference" to draw.sourceReference, "sourceContentHash" to draw.sourceContentHash)
        })
        LotteryProduct.PENSION_720 -> mapOf("draws" to pension.sortedByDescending { it.roundNo }.map { draw ->
            mapOf("roundNo" to draw.roundNo, "groupNo" to draw.groupNo, "winningNumber" to draw.winningNumber,
                "bonusNumber" to draw.bonusNumber, "drawDate" to draw.drawDate?.toString(),
                "sourceReference" to draw.sourceReference, "sourceContentHash" to draw.sourceContentHash,
                "collectedAt" to draw.collectedAt?.toString())
        }, "exclusions" to emptyList<String>())
    }

    fun generationJson(): Map<String, Any?> = when (product) {
        LotteryProduct.LOTTO_645 -> mapOf("draws" to lotto.sortedByDescending { it.roundNo }.map {
            mapOf("roundNo" to it.roundNo, "numbers" to it.numbers(), "bonusNumber" to it.bonusNumber)
        })
        LotteryProduct.PENSION_720 -> mapOf("draws" to pension.sortedByDescending { it.roundNo }.map {
            mapOf("roundNo" to it.roundNo, "groupNo" to it.groupNo, "winningNumber" to it.winningNumber, "bonusNumber" to it.bonusNumber)
        }, "exclusions" to emptyList<String>())
    }
}

/** 과거 비교로 이미 살펴본 결과를 독립 최종 평가의 근거로 사용하지 않는다. */
internal class LotteryAutoImprovement(context: Context, private val repository: HabitRepository) {
    private val app = context.applicationContext
    private val root get() = File(app.filesDir.canonicalFile, "lottery-auto-improvement")
    private val states = LotteryProduct.entries.associateWith { MutableStateFlow(LotteryAutoImprovementState()) }
    private val registration = LotteryAutomaticRegistration(app, repository)
    private val apkHash by lazy { LottoDesignRecordJson.hash(File(app.applicationInfo.sourceDir).readBytes()) }
    private val splitHashes by lazy {
        app.applicationInfo.splitSourceDirs.orEmpty().associate { File(it).name to LottoDesignRecordJson.hash(File(it).readBytes()) }
    }

    fun state(product: LotteryProduct): StateFlow<LotteryAutoImprovementState> = states.getValue(product)

    suspend fun refresh(product: LotteryProduct) = withContext(Dispatchers.IO) {
        try {
            val input = repository.getLotteryResearchInput(product)
            val preview = preview(input)
            val old = states.getValue(product).value
            val summary = readLastSummary(product, preview)
            states.getValue(product).value = old.copy(
                audit = input.audit(), enabled = LotteryAutoImprovementWorker.isEnabled(app, product),
                firstTarget = preview.targets.firstOrNull(), lastTarget = preview.targets.lastOrNull(),
                targetCount = preview.targets.size, candidateWorkCount = preview.workCount,
                result = if (old.running) old.result else summary,
                evaluationStatus = registration.update(input, registerIfNeeded = false),
                issue = if (old.running) old.issue else preview.issue,
            )
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            Log.e(TAG, "product=$product phase=refresh failed", error)
            states.getValue(product).value = states.getValue(product).value.copy(issue = error.message ?: "개선 자료를 읽지 못했습니다.")
        }
    }

    suspend fun setEnabled(product: LotteryProduct, enabled: Boolean) {
        withContext(Dispatchers.IO) { LotteryAutoImprovementWorker.setEnabled(app, product, enabled) }
        refresh(product)
    }

    fun startHistorical(product: LotteryProduct) = LotteryAutoImprovementWorker.enqueue(app, product, automatic = false)

    suspend fun run(product: LotteryProduct, automatic: Boolean) = gate.withLock {
        withContext(Dispatchers.IO) {
            if (automatic && !LotteryAutoImprovementWorker.isEnabled(app, product)) return@withContext
            val folder = productFolder(product)
            val lockFile = File(folder, ".lock")
            require(lockFile.canonicalFile == lockFile.absoluteFile) { "자동 개선 잠금 경로 오류" }
            RandomAccessFile(lockFile, "rw").use { handle ->
                val lock = handle.channel.lock()
                var failure: Throwable? = null
                try { runLocked(product, automatic, folder) }
                catch (error: Throwable) { failure = error; throw error }
                finally {
                    try { lock.release() } catch (error: Exception) {
                        if (failure == null) throw error else failure.addSuppressed(error)
                    }
                }
            }
        }
    }

    private suspend fun runLocked(product: LotteryProduct, automatic: Boolean, folder: File) {
        val state = states.getValue(product)
        state.value = state.value.copy(running = true, completedCount = 0, reusedCount = 0, issue = null)
        var runFolder: File? = null
        try {
            val input = repository.getLotteryResearchInput(product)
            require(input.audit().canResearch) { "회차 누락·중복·번호 오류 또는 최신 결과 미수집을 먼저 해결해 주세요." }
            if (automatic) {
                state.value = state.value.copy(evaluationStatus = registration.update(input, registerIfNeeded = true))
                maintainRegisteredFinals(input)
                currentCoroutineContext().ensureActive()
                if (!LotteryAutoImprovementWorker.isEnabled(app, product)) return
                state.value = state.value.copy(evaluationStatus = registration.update(input, registerIfNeeded = true))
            }
            val plan = preview(input)
            require(plan.issue == null) { requireNotNull(plan.issue) }
            state.value = state.value.copy(audit = input.audit(), firstTarget = plan.targets.first(),
                lastTarget = plan.targets.last(), targetCount = plan.targets.size, candidateWorkCount = plan.workCount)
            reusableSummary(folder, plan, automatic)?.let { summary ->
                state.value = state.value.copy(result = summary, completedCount = plan.targets.size, reusedCount = plan.targets.size)
                return
            }
            val run = child(folder, "runs", "${Instant.now().toEpochMilli()}-${UUID.randomUUID()}")
            runFolder = run
            write(run, "plan.json", plan.json + mapOf("createdAt" to Instant.now().toString()))
            val results = mutableListOf<Map<String, Any?>>()
            var reused = 0
            for (round in plan.targets) {
                currentCoroutineContext().ensureActive()
                if (automatic) require(LotteryAutoImprovementWorker.isEnabled(app, product)) { "자동 개선이 해제되어 계산을 중단했습니다." }
                val frozen = plan.input.through(round)
                val roundPlan = roundPlan(frozen, round)
                val key = LottoDesignRecordJson.hash(roundPlan)
                val (result, wasReused) = roundResult(folder, roundPlan, frozen, round, automatic, run)
                if (wasReused) reused++
                write(run, "round-${round}.json", mapOf("round" to round, "planHash" to key,
                    "resultHash" to LottoDesignRecordJson.hash(result), "reused" to wasReused))
                results += result
                state.value = state.value.copy(completedCount = results.size, reusedCount = reused)
            }
            // 현재 입력이 정정되었으면 오래된 결과를 최신 자료의 성공으로 표시하지 않는다.
            val current = repository.getLotteryResearchInput(product).through(plan.targets.last())
            require(current.json() == plan.input.json()) { "계산 중 입력이 변경됐습니다. 이전 결과는 보존하며 새 입력으로 다시 계획해야 합니다." }
            fun rate(key: String) = results.map { LottoDesignRecordJson.decimal(it.getValue(key)) }.average()
            val baseline = rate("baselineRate"); val candidate = rate("candidateRate"); val control = rate("controlRate")
            val summary = "과거 탐색 ${results.size}회차 · 기준 ${percent(baseline)} · 후보 ${percent(candidate)} · 무작위 ${percent(control)} · 후보−기준 ${percent(candidate - baseline)}p. 독립 최종 평가·운영 적용 근거가 아닙니다."
            write(run, "success.json", mapOf("planHash" to LottoDesignRecordJson.hash(plan.json), "summary" to summary,
                "roundCount" to results.size, "baselineRate" to baseline, "candidateRate" to candidate, "controlRate" to control,
                "decision" to "DESIGN_ONLY", "independentFinalEvidence" to false))
            state.value = state.value.copy(result = summary)
        } catch (error: Exception) {
            runFolder?.let { preserveFailure(it, error) }
            if (error is CancellationException) {
                state.value = state.value.copy(issue = "비교가 취소·중단되었습니다. 기록은 보존하며 자동으로 이어 실행하지 않습니다.")
            } else {
                Log.e(TAG, "product=$product phase=automatic_improvement failed", error)
                state.value = state.value.copy(issue = error.message ?: "자동 개선 처리 실패")
            }
            throw error
        } finally { state.value = state.value.copy(running = false) }
    }

    private suspend fun preview(input: LotteryResearchInput): Preview {
        val audit = input.audit()
        val protectedStart = when (input.product) {
            LotteryProduct.LOTTO_645 -> repository.lottoExperiments.list(input.lotto.map { LottoPriorDesignDraw(it.roundNo, it.numbers(), it.bonusNumber) })
                .filter { it.specification["purpose"] == "FINAL" && it.run?.state != LottoDesignRunState.SUCCESS }
                .flatMap { it.targetRounds }.minOrNull()
            LotteryProduct.PENSION_720 -> repository.pensionExperiments.list()
                .filter { it.specification?.get("purpose") == "FINAL" && it.state != "SUCCESS" }
                .flatMap { summary -> LottoDesignRecordJson.list(requireNotNull(summary.specification).getValue("targetRounds")).map(LottoDesignRecordJson::integer) }.minOrNull()
        }
        val end = minOf(audit.lastRound ?: 0, protectedStart?.minus(1) ?: Int.MAX_VALUE)
        val snapshot = input.through(end)
        val first = snapshot.rounds().minOrNull() ?: 0
        val minimumHistory = if (input.product == LotteryProduct.PENSION_720) 17 else 1
        val eligible = snapshot.rounds().filter { it >= first + minimumHistory }.sorted()
        val targets = eligible.takeLast(DESIGN_ROUNDS)
        val issue = when {
            !audit.canResearch -> "데이터 누락·중복·번호 오류 또는 최신 결과 미수집으로 비교를 보류합니다."
            targets.size < DESIGN_ROUNDS -> "필요한 이전 이력을 갖춘 탐색용 ${DESIGN_ROUNDS}회차가 필요합니다. 등록된 최종 구간은 탐색에서 보호합니다."
            else -> null
        }
        val json = mapOf("implementationId" to IMPLEMENTATION, "product" to input.product.name,
            "purpose" to "HISTORICAL_DESIGN_ONLY", "targets" to targets, "input" to snapshot.json(),
            "settings" to settings(input.product), "seedPolicy" to "fixed-round-only-v1", "seedCount" to SEED_COUNT,
            "apkHash" to apkHash, "splitApkHashes" to splitHashes, "historyPresenceGuaranteed" to false,
            "historicalAvailabilityVerified" to false, "sourceEvidenceComplete" to false)
        return Preview(snapshot, targets, json, targets.size.toLong() * SEED_COUNT * 2 *
            (if (input.product == LotteryProduct.LOTTO_645) 6000 else 80000), issue)
    }

    private suspend fun calculate(input: LotteryResearchInput, round: Int, folder: File): Map<String, Any?> = when (input.product) {
        LotteryProduct.LOTTO_645 -> {
            val draws = input.lotto.map { LottoPriorDesignDraw(it.roundNo, it.numbers(), it.bonusNumber) }
            val plan = LottoPriorDesignComparison.prepare(draws, draws.minOf { it.roundNo }, listOf(round), commonSeeds(round), controlSeeds(round))
            val result = LottoPriorDesignComparison.compare(plan).rounds.single()
            mapOf("round" to round, "baselineRate" to result.baselineRate, "candidateRate" to result.candidateRate,
                "controlRate" to requireNotNull(result.controlRate), "inputHash" to result.inputDataHash,
                "targetHash" to result.targetDataHash, "seeds" to result.seeds.map { seed ->
                    mapOf("seed" to seed.seed.toString(), "controlSeed" to seed.controlSeed.toString(),
                        "baseline" to seed.baseline.games, "candidate" to seed.candidate.games,
                        "control" to requireNotNull(seed.control).games,
                        "baselineMatches" to seed.baseline.mainMatchCounts, "candidateMatches" to seed.candidate.mainMatchCounts,
                        "controlMatches" to requireNotNull(seed.control).mainMatchCounts)
                })
        }
        LotteryProduct.PENSION_720 -> {
            val history = input.pension.filter { it.roundNo < round }
            var modelIndex = 0
            PensionExperimentComparison.compare(input.pension.single { it.roundNo == round },
                PensionExperimentComparison.input(history, emptySet()), commonSeeds(round), controlSeeds(round)) { model ->
                write(folder, "model-${modelIndex++}.json", model)
            }
        }
    }

    private suspend fun roundResult(
        folder: File, plan: Map<String, Any?>, input: LotteryResearchInput, round: Int, automatic: Boolean,
        originRun: File? = null,
    ): Pair<Map<String, Any?>, Boolean> {
        val key = LottoDesignRecordJson.hash(plan)
        val base = File(File(folder, "rounds"), key)
        val attempts = if (!base.exists()) emptyList() else {
            val retries = File(base, "retries")
            val extra = if (!retries.exists()) emptyList() else {
                require(retries.isDirectory && retries.canonicalFile == retries.absoluteFile) { "재시도 기록 경로 오류" }
                requireNotNull(retries.listFiles()).sortedBy { it.name }.toList()
            }
            listOf(base) + extra
        }
        attempts.forEach {
            require(read(it, "plan.json") == plan) { "$round 회차 캐시 입력·설정 오류" }
            val origin = read(it, "origin.json")
            val runId = LottoDesignRecordJson.string(origin.getValue("runId"))
            require(runId.matches(Regex("[0-9]+-[0-9a-f-]{36}"))) { "원본 실행 참조 경로 오류" }
            require(origin["planHash"] == LottoDesignRecordJson.hash(read(File(File(folder, "runs"), runId), "plan.json"))) {
                "$round 회차 원본 입력·출처 스냅샷 참조 오류"
            }
            if (File(it, "failure.json").exists()) {
                read(it, "failure.json")
                require(!File(it, "success.json").exists()) { "$round 회차 성공·실패 종료 기록 충돌" }
            }
        }
        val completed = attempts.filter { File(it, "success.json").exists() && !File(it, "failure.json").exists() }
        require(completed.size <= 1) { "$round 회차 동일 입력의 완료 결과가 여러 개입니다." }
        completed.singleOrNull()?.let {
            val result = read(it, "success.json")
            require(result["planHash"] == key) { "$round 회차 결과의 계획 참조 오류" }
            return result to true
        }
        require(attempts.isEmpty() || !automatic) {
            "$round 회차 실패·취소·중단 기록을 보존하고 자동 재실행을 보류했습니다. 원인을 확인한 뒤 과거 비교 버튼으로 새 시도를 시작할 수 있습니다."
        }
        val attempt = if (attempts.isEmpty()) child(folder, "rounds", key)
            else child(base, "retries", "${Instant.now().toEpochMilli()}-${UUID.randomUUID()}")
        write(attempt, "plan.json", plan)
        val originalRun = requireNotNull(originRun) { "새 계산의 입력 스냅샷 기록이 없습니다." }
        write(attempt, "origin.json", mapOf("runId" to originalRun.name,
            "planHash" to LottoDesignRecordJson.hash(read(originalRun, "plan.json"))))
        if (attempts.isNotEmpty()) write(attempt, "retry.json", mapOf("previousPlanHash" to key,
            "previousAttempt" to attempts.last().relativeTo(folder).path, "authorization" to "EXPLICIT_HISTORICAL_START",
            "previousState" to if (File(attempts.last(), "failure.json").exists()) read(attempts.last(), "failure.json")["state"] else "INCOMPLETE"))
        return try {
            val calculated = calculate(input, round, attempt)
            currentCoroutineContext().ensureActive()
            val result = calculated + ("planHash" to key)
            write(attempt, "success.json", result)
            result to false
        } catch (error: Exception) { preserveFailure(attempt, error); throw error }
    }

    private fun roundPlan(input: LotteryResearchInput, round: Int): Map<String, Any?> = mapOf(
        "implementationId" to IMPLEMENTATION, "product" to input.product.name,
        "purpose" to "HISTORICAL_DESIGN_ONLY", "sourceEvidenceComplete" to false,
        "input" to input.generationJson(), "targetRound" to round, "apkHash" to apkHash, "splitApkHashes" to splitHashes,
        "settings" to settings(input.product), "commonSeeds" to commonSeeds(round).map(Long::toString),
        "controlSeeds" to controlSeeds(round).map(Long::toString),
        "exclusionPolicy" to if (input.product == LotteryProduct.PENSION_720) "EMPTY_RESEARCH_ONLY_NOT_OPERATION_REPLAY" else "GENERATOR_DESIGN_PATH",
    )

    private suspend fun reusableSummary(folder: File, plan: Preview, automatic: Boolean): String? {
        val runs = File(folder, "runs")
        if (!runs.exists()) return null
        require(runs.isDirectory && runs.canonicalFile == runs.absoluteFile) { "실행 기록 경로 오류" }
        val last = requireNotNull(runs.listFiles()).maxByOrNull { it.name } ?: return null
        if (read(last, "plan.json") - "createdAt" != plan.json) return null
        val success = File(last, "success.json")
        if (!success.exists() || File(last, "failure.json").exists()) {
            require(!automatic) { "동일 계획의 실패·취소·중단 기록을 자동 재실행하지 않습니다. 원인을 확인한 뒤 과거 비교를 명시적으로 시작해 주세요." }
            return null
        }
        val summary = read(last, "success.json")
        require(summary["planHash"] == LottoDesignRecordJson.hash(plan.json)) { "완료 기록의 계획 참조 오류" }
        for (round in plan.targets) {
            val frozen = plan.input.through(round)
            val expected = roundPlan(frozen, round)
            val key = LottoDesignRecordJson.hash(expected)
            val reference = read(last, "round-$round.json")
            require(reference["planHash"] == key && File(File(folder, "rounds"), key).isDirectory) { "$round 회차의 완료 원본 기록이 없습니다." }
            val (result, reused) = roundResult(folder, expected, frozen, round, automatic = true)
            require(reused && reference["resultHash"] == LottoDesignRecordJson.hash(result)) { "$round 회차 결과 참조 오류" }
        }
        return LottoDesignRecordJson.string(summary.getValue("summary"))
    }

    private suspend fun maintainRegisteredFinals(input: LotteryResearchInput) {
        val product = input.product
        fun authorized() = LotteryAutoImprovementWorker.isEnabled(app, product)
        val latest = input.rounds().maxOrNull() ?: return
        if (product == LotteryProduct.LOTTO_645) {
            val draws = input.lotto.map { LottoPriorDesignDraw(it.roundNo, it.numbers(), it.bonusNumber) }
            val store = repository.lottoExperiments
            store.rollbackInvalidAutomaticInput(draws, ::authorized)?.let {
                LotteryAutoImprovementWorker.showChange(app, product, it)
                return
            }
            for (entry in store.list(draws)) {
                currentCoroutineContext().ensureActive()
                if (!authorized()) return
                if (entry.specification["purpose"] != "FINAL" || !confirmed(entry.specification)) continue
                if (entry.issue != null) {
                    states.getValue(product).value = states.getValue(product).value.copy(issue = "등록 최종 실험 ${entry.id} 보류: ${entry.issue}")
                    Log.w(TAG, "product=$product experiment=${entry.id} phase=final reason=${entry.issue}")
                    continue
                }
                if (!entry.executionStarted && latest + 1 in entry.targetRounds && latest + 1 !in entry.capturedInputRounds &&
                    lotteryEvaluationNow().isBefore(lotteryEvaluationDeadline(product, latest + 1))) {
                    store.captureInputs(entry.id, latest + 1, draws)
                }
                val refreshed = store.list(draws).single { it.id == entry.id }
                if (!refreshed.executionStarted && refreshed.targetRounds.all { it <= latest && it in refreshed.capturedInputRounds } && authorized()) {
                    store.execute(entry.id, draws)
                }
                val freshDraws = repository.getLottoExperimentDraws()
                val evaluated = store.list(freshDraws).single { it.id == entry.id }
                if (evaluated.judgment.decision == LottoExperimentDecision.ADOPTION_CANDIDATE && store.approvedSetting().priorDraws == 32.0 &&
                    !store.hasAppliedExperiment(entry.id) && authorized()) {
                    store.approve(entry.id, freshDraws, "자동 개선: 사용자가 허용한 자동 적용, 사전 확정 FINAL 기준 통과", ::authorized)
                    LotteryAutoImprovementWorker.showChange(app, product, "균형형 BASIC 5게임 priorDraws 32 → 64 · 독립 ${evaluated.judgment.independentRounds}회차 기준 통과 · ${lotteryEvaluationNow()} · ${entry.id}")
                }
            }
        } else {
            val store = repository.pensionExperiments
            store.rollbackInvalidAutomaticInput(repository.getPensionExperimentInput(), ::authorized)?.let {
                LotteryAutoImprovementWorker.showChange(app, product, it)
                return
            }
            for (entry in store.list()) {
                currentCoroutineContext().ensureActive()
                if (!authorized()) return
                val spec = entry.specification ?: continue
                if (spec["purpose"] != "FINAL" || !confirmed(spec)) continue
                if (entry.issues.isNotEmpty() || entry.failures.isNotEmpty()) {
                    states.getValue(product).value = states.getValue(product).value.copy(issue = "등록 최종 실험 ${entry.id}의 기록·실패 사유를 확인해 주세요.")
                    Log.w(TAG, "product=$product experiment=${entry.id} phase=final reason=record_issues"); continue
                }
                val targets = LottoDesignRecordJson.list(spec.getValue("targetRounds")).map(LottoDesignRecordJson::integer)
                if (entry.state == "REGISTERED" && latest + 1 in targets && latest + 1 !in entry.capturedRounds &&
                    lotteryEvaluationNow().isBefore(lotteryEvaluationDeadline(product, latest + 1))) {
                    store.capture(entry.id, latest + 1, repository.getPensionExperimentInput())
                }
                val refreshed = store.list().single { it.id == entry.id }
                if (refreshed.state == "REGISTERED" && targets.all { it <= latest && it in refreshed.capturedRounds } && authorized()) {
                    store.execute(entry.id, repository.getPensionExperimentInput())
                }
                val evaluated = store.list().single { it.id == entry.id }
                if (evaluated.judgment.decision == LottoExperimentDecision.ADOPTION_CANDIDATE && store.setting().count == 3 &&
                    !store.hasAppliedExperiment(entry.id) && authorized()) {
                    store.approve(entry.id, repository.getPensionExperimentInput(), "자동 개선: 사용자가 허용한 자동 적용, 사전 확정 FINAL 기준 통과", ::authorized)
                    LotteryAutoImprovementWorker.showChange(app, product, "출현형 끝자리 후보 최대 3 → 4 · 독립 ${evaluated.judgment.independentRounds}회차 기준 통과 · ${lotteryEvaluationNow()} · ${entry.id}")
                }
            }
        }
    }

    private fun confirmed(spec: Map<String, Any?>) = spec["policy"]?.let {
        LottoDesignRecordJson.objectValue(it)["confirmedByUser"] == true
    } == true

    private fun readLastSummary(product: LotteryProduct, preview: Preview): String? {
        val runs = File(File(root, product.name), "runs")
        if (!runs.exists()) return null
        require(runs.isDirectory && runs.canonicalFile == runs.absoluteFile) { "과거 비교 기록 경로 오류" }
        val last = requireNotNull(runs.listFiles()).maxByOrNull { it.name } ?: return null
        val plan = read(last, "plan.json")
        if (plan - "createdAt" != preview.json) return "입력·설정 또는 앱 구현이 바뀌었습니다. 이전 탐색 기록은 보존되어 있습니다."
        if (File(last, "failure.json").exists()) return "마지막 탐색이 실패·취소되었습니다. 자동 이어 실행하지 않습니다."
        if (!File(last, "success.json").exists()) return "마지막 탐색의 정상 종료 기록이 없습니다."
        val result = read(last, "success.json")
        require(result["planHash"] == LottoDesignRecordJson.hash(preview.json)) { "과거 비교 계획 참조 오류" }
        return LottoDesignRecordJson.string(result.getValue("summary"))
    }

    private suspend fun preserveFailure(folder: File, error: Exception) {
        try {
            withContext(NonCancellable + Dispatchers.IO) {
                write(folder, "failure.json", mapOf("at" to Instant.now().toString(),
                    "state" to if (error is CancellationException) "CANCELLED" else "FAILED",
                    "error" to LottoDesignRunStore.exceptionJson(error),
                    "pensionDiagnostics" to (error as? PensionComparisonFailure)?.let {
                        mapOf("round" to it.round, "model" to it.model, "seed" to it.seed?.toString(), "stage" to it.stage, "diagnostics" to it.diagnostics)
                    }))
            }
        } catch (recordError: Exception) { if (recordError !== error) error.addSuppressed(recordError) }
    }

    private fun productFolder(product: LotteryProduct): File {
        if (!root.exists()) { require(root.mkdir()); LottoDesignRunStore.syncDirectory(root.parentFile!!) }
        require(root.isDirectory && root.canonicalFile == root.absoluteFile) { "자동 개선 저장 경로 오류" }
        return child(root, product.name)
    }

    private fun child(parent: File, vararg names: String): File {
        var result = parent
        for (name in names) {
            result = File(result, name)
            if (!result.exists()) { require(result.mkdir()) { "자동 개선 기록 폴더 생성 실패" }; LottoDesignRunStore.syncDirectory(requireNotNull(result.parentFile)) }
            require(result.isDirectory && result.canonicalFile == result.absoluteFile) { "자동 개선 기록 경로 오류" }
        }
        return result
    }

    private fun write(folder: File, name: String, value: Map<String, Any?>) {
        val record = mapOf("formatVersion" to 1, "record" to value)
        val sealed = record + ("recordHash" to LottoDesignRecordJson.hash(record))
        LottoDesignRunStore.writeImmutable(File(folder, name), LottoDesignRecordJson.encode(sealed).toByteArray(Charsets.UTF_8))
    }

    private fun read(folder: File, name: String): Map<String, Any?> {
        require(folder.isDirectory && folder.canonicalFile == folder.absoluteFile) { "자동 개선 폴더 경로 오류" }
        val file = File(folder, name)
        require(file.isFile && file.canonicalFile == file.absoluteFile) { "자동 개선 기록 누락·경로 오류: $name" }
        val value = LottoDesignRecordJson.decode(file.readBytes())
        require(value["formatVersion"] == 1 && value["recordHash"] == LottoDesignRecordJson.hash(value - "recordHash")) { "자동 개선 기록 버전·해시 오류: $name" }
        return LottoDesignRecordJson.objectValue(value.getValue("record"))
    }

    private fun settings(product: LotteryProduct): Map<String, Any?> = when (product) {
        LotteryProduct.LOTTO_645 -> mapOf("baseline" to LottoNumberGenerator.configurationSnapshotForPriorDesign(32.0),
            "candidate" to LottoNumberGenerator.configurationSnapshotForPriorDesign(64.0), "comparisonImplementation" to "lotto-prior-design-comparison-v5")
        LotteryProduct.PENSION_720 -> mapOf("baseline" to PensionExperimentComparison.configuration(3),
            "candidate" to PensionExperimentComparison.configuration(4), "comparisonImplementation" to PensionExperimentComparison.IMPLEMENTATION)
    }

    private fun commonSeeds(round: Int) = (0 until SEED_COUNT).map { 0x145B2L + round.toLong() * 1_000_003L + it * 97L }
    private fun controlSeeds(round: Int) = (0 until SEED_COUNT).map { -0x145B2L - round.toLong() * 1_000_003L - it * 97L }
    private fun percent(value: Double) = String.format(java.util.Locale.ROOT, "%.3f", value * 100) + "%"
    private data class Preview(val input: LotteryResearchInput, val targets: List<Int>, val json: Map<String, Any?>, val workCount: Long, val issue: String?)

    companion object {
        private val gate = Mutex()
        private const val TAG = "LotteryAutoImprovement"
        private const val IMPLEMENTATION = "lottery-historical-research-v1"
        private const val DESIGN_ROUNDS = 52
        private const val SEED_COUNT = 5
        val SOURCE_FILES = listOf(
            "app/src/main/java/com/habittracker/data/AppContainer.kt",
            "app/src/main/java/com/habittracker/data/lotto/LotteryAutoImprovement.kt",
            "app/src/main/java/com/habittracker/data/lotto/LotteryAutoImprovementWorker.kt",
            "app/src/main/java/com/habittracker/data/lotto/LotteryAutomaticRegistration.kt",
            "app/src/main/java/com/habittracker/data/lotto/LotteryHistoryAudit.kt",
            "app/src/main/java/com/habittracker/data/lotto/LotteryDrawSyncModels.kt",
            "app/src/main/java/com/habittracker/ui/lotto/LotteryAutoImprovementSection.kt",
            "app/src/main/java/com/habittracker/ui/components/DesignSystem.kt",
        )
    }
}
