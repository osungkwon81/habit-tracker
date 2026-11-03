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
    val canRollback: Boolean = false,
    val candidateReport: String? = null,
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
    private val candidates = LotteryCandidateArchive(app)
    private val root get() = File(app.filesDir.canonicalFile, "lottery-auto-improvement")
    private val states = LotteryProduct.entries.associateWith { MutableStateFlow(LotteryAutoImprovementState()) }
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
                evaluationStatus = adaptiveStatus(product), canRollback = repository.adaptiveSettings.active(product) != null,
                candidateReport = candidates.summary(product),
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

    suspend fun rollback(product: LotteryProduct) = gate.withLock {
        try {
            val active = requireNotNull(repository.adaptiveSettings.active(product)) { "복구할 시험 적용 설정이 없습니다." }
            rollbackTrial(product, active, "사용자 요청으로 이전 가중치를 복구했습니다.", automatic = false)
            refresh(product)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            Log.e(TAG, "product=$product phase=manual-rollback failed", error)
            states.getValue(product).value = states.getValue(product).value.copy(issue = error.message ?: "설정 복구 실패")
        }
    }

    private suspend fun adaptiveStatus(product: LotteryProduct): String {
        val last = repository.adaptiveSettings.last(product) ?: return "보유 이력으로 가중치 선택 · 조건 충족 시 시험 적용"
        return when {
            repository.adaptiveSettings.active(product) != null -> "시험 적용 중 · 가중치 ${last["after"]} · 새 4회마다 복구 여부 점검"
            last["action"] == "ROLLBACK" -> "이전 가중치 ${last["after"]}로 복구됨"
            else -> "비교 조건 미충족 · 기존 가중치 ${last["after"]} 유지"
        }
    }

    private suspend fun monitoringThrough(product: LotteryProduct, active: Map<String, Any?>): Int {
        val last = repository.adaptiveSettings.last(product)
        val checked = last?.get("proof")?.let(LottoDesignRecordJson::objectValue)?.get("monitorThrough")?.let(LottoDesignRecordJson::integer)
        val proof = LottoDesignRecordJson.objectValue(active.getValue("proof"))
        return checked ?: LottoDesignRecordJson.integer(proof["appliedAfterRound"] ?: proof.getValue("through"))
    }

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
            if (automatic && rollbackCorrectedInput(input)) return
            require(input.audit().canResearch) { "회차 누락·중복·번호 오류 또는 최신 결과 미수집을 먼저 해결해 주세요." }
            if (automatic) {
                val previous = repository.adaptiveSettings.active(product)
                if (previous != null && previous["policy"] != LotteryAdaptiveSettings.POLICY) {
                    rollbackTrial(product, previous, "후보 평가 구조 변경으로 이전 시험 가중치를 복구했습니다.")
                }
                val held = candidates.evaluatePending(input)
                state.value = state.value.copy(candidateReport = candidates.summary(product),
                    issue = held.takeIf { it.isNotEmpty() }?.let { "입력·구현 변경으로 ${it.joinToString()}회 평가 보류 · 이후 회차는 계속 수집합니다." })
                val next = requireNotNull(input.rounds().maxOrNull()) + 1
                if (lotteryEvaluationNow().isBefore(lotteryEvaluationDeadline(product, next))) {
                    if (candidates.canFreeze(input, next)) candidates.freeze(input, next)
                    else state.value = state.value.copy(issue = "$next 회 후보는 중단·입력 변경으로 보류합니다. 다음 회차부터 새로 수집합니다.")
                }
            }
            if (automatic) {
                val active = repository.adaptiveSettings.active(product)
                if (active == null && repository.adaptiveSettings.attempted(product)) {
                    state.value = state.value.copy(evaluationStatus = adaptiveStatus(product), canRollback = false)
                    return
                }
                if (active != null) {
                    val checked = monitoringThrough(product, active)
                    val fresh = input.rounds().count { it > checked }
                    if (fresh < 4) { state.value = state.value.copy(evaluationStatus = "시험 적용 중 · 새 회차 $fresh/4 확인", canRollback = true); return }
                    val window = input.rounds().filter { it > checked }.sorted().take(4)
                    if (window.any { !candidates.eligible(input, it) }) {
                        rollbackTrial(product, active, "후속 4회 중 추첨 전 고정 후보가 없거나 입력이 변경되어 시험을 중지하고 이전 설정으로 복구했습니다.",
                            evidence = mapOf("excludedWindow" to window))
                        return
                    }
                }
                currentCoroutineContext().ensureActive()
                if (!LotteryAutoImprovementWorker.isEnabled(app, product)) return
                state.value = state.value.copy(evaluationStatus = adaptiveStatus(product))
            }
            val plan = preview(input)
            require(plan.issue == null) { requireNotNull(plan.issue) }
            if (plan.targets.isEmpty()) { state.value = state.value.copy(evaluationStatus = "시험 적용 후 새 회차를 확인하고 있습니다."); return }
            state.value = state.value.copy(audit = input.audit(), firstTarget = plan.targets.first(),
                lastTarget = plan.targets.last(), targetCount = plan.targets.size, candidateWorkCount = plan.workCount)
            reusableSummary(folder, plan, automatic)?.let { summary ->
                state.value = state.value.copy(result = summary, completedCount = plan.targets.size, reusedCount = plan.targets.size)
                if (automatic && LotteryAutoImprovementWorker.isEnabled(app, product)) {
                    val results = plan.targets.map { round ->
                        val frozen = plan.input.through(round)
                        roundResult(folder, roundPlan(frozen, round), frozen, round, automatic = true).first
                    }
                    state.value = state.value.copy(evaluationStatus = selectOrRecover(plan.input, results, folder))
                    state.value = state.value.copy(canRollback = repository.adaptiveSettings.active(product) != null)
                }
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
            val summary = "최종 추천 탐색 ${results.size}회차 · 기준 ${percent(baseline)} · 변경 ${percent(candidate)} · 무작위 ${percent(control)} · 차이 ${percent(candidate - baseline)}p. 과거 탐색 결과입니다."
            write(run, "success.json", mapOf("planHash" to LottoDesignRecordJson.hash(plan.json), "summary" to summary,
                "roundCount" to results.size, "baselineRate" to baseline, "candidateRate" to candidate, "controlRate" to control,
                "decision" to "DESIGN_ONLY", "independentFinalEvidence" to false))
            state.value = state.value.copy(result = summary)
            if (automatic && LotteryAutoImprovementWorker.isEnabled(app, product)) {
                state.value = state.value.copy(evaluationStatus = selectOrRecover(plan.input, results, run))
                state.value = state.value.copy(canRollback = repository.adaptiveSettings.active(product) != null)
            }
        } catch (error: Exception) {
            runFolder?.let { preserveFailure(it, error) }
            if (error is CancellationException) {
                state.value = state.value.copy(issue = "비교가 취소·중단되었습니다. 기록은 보존하며 자동으로 이어 실행하지 않습니다.")
            } else {
                Log.e(TAG, "product=$product phase=automatic_improvement failed", error)
                state.value = state.value.copy(issue = error.message ?: "자동 개선 처리 실패")
            }
            throw error
        } finally {
            repository.requestLotteryRecordBackup()
            state.value = state.value.copy(running = false)
        }
    }

    private suspend fun rollbackCorrectedInput(input: LotteryResearchInput): Boolean {
        val active = repository.adaptiveSettings.active(input.product) ?: return false
        val proof = LottoDesignRecordJson.objectValue(active.getValue("proof"))
        val through = LottoDesignRecordJson.integer(proof.getValue("through"))
        val lastProof = repository.adaptiveSettings.last(input.product)?.get("proof")?.let(LottoDesignRecordJson::objectValue)
        val monitorThrough = lastProof?.get("monitorThrough")?.let(LottoDesignRecordJson::integer)
        val initialValid = LottoDesignRecordJson.hash(input.through(through).generationJson()) == proof["inputHash"]
        val monitorValid = monitorThrough == null || LottoDesignRecordJson.hash(input.through(monitorThrough).generationJson()) == lastProof?.get("inputHash")
        if (initialValid && monitorValid) return false
        rollbackTrial(input.product, active, "적용 근거 입력이 정정되거나 누락되어 이전 설정으로 복구했습니다.")
        return true
    }

    private suspend fun rollbackTrial(product: LotteryProduct, active: Map<String, Any?>, reason: String, automatic: Boolean = true,
        evidence: Map<String, Any?> = emptyMap()) {
        require(!automatic || LotteryAutoImprovementWorker.isEnabled(app, product)) { "자동 개선이 해제되어 설정을 변경하지 않습니다." }
        val after = LottoDesignRecordJson.decimal(active.getValue("after"))
        val before = LottoDesignRecordJson.decimal(active.getValue("before"))
        repository.adaptiveSettings.append(product, "ROLLBACK", after, before,
            mapOf("application" to active.getValue("recordHash"), "reason" to reason) + evidence) { !automatic || LotteryAutoImprovementWorker.isEnabled(app, product) }
        states.getValue(product).value = states.getValue(product).value.copy(evaluationStatus = reason, canRollback = false)
        Log.i(TAG, "product=$product phase=rollback application=${active["recordHash"]} reason=$reason")
        LotteryAutoImprovementWorker.showChange(app, product, reason)
        repository.requestLotteryRecordBackup()
    }

    private suspend fun selectOrRecover(input: LotteryResearchInput, results: List<Map<String, Any?>>, run: File): String {
        val product = input.product
        val store = repository.adaptiveSettings
        val active = store.active(product)
        val latest = requireNotNull(input.rounds().maxOrNull())
        fun number(row: Map<String, Any?>, role: String, secondary: Boolean = false) =
            LottoDesignRecordJson.decimal(row.getValue(role + if (secondary) "Secondary" else "Rate"))
        fun role(value: Double) = if (value == if (product == LotteryProduct.LOTTO_645) 64.0 else 0.5) "candidate" else "baseline"
        fun mean(rows: List<Map<String, Any?>>, role: String, secondary: Boolean = false) = rows.map { number(it, role, secondary) }.average()
        val rounds = results.map { LottoDesignRecordJson.integer(it["round"] ?: it["targetRound"] ?: error("결과 회차 없음")) }
        require(rounds == rounds.sorted() && rounds.distinct().size == rounds.size && rounds.zipWithNext().all { (a, b) -> b == a + 1 }) { "시험 평가 회차 누락·중복" }
        if (active != null) {
            val proof = LottoDesignRecordJson.objectValue(active.getValue("proof"))
            val appliedAt = LottoDesignRecordJson.integer(proof["appliedAfterRound"] ?: proof.getValue("through"))
            val last = store.last(product)
            val checkedThrough = if (last?.get("action") == "HOLD") LottoDesignRecordJson.objectValue(last.getValue("proof"))["monitorThrough"]?.let(LottoDesignRecordJson::integer) ?: appliedAt else appliedAt
            val fresh = results.filterIndexed { index, _ -> rounds[index] > checkedThrough }.take(4)
            if (fresh.size < 4) return "시험 적용 중 · 새 회차 ${fresh.size}/4 확인"
            require(fresh.all { it["preDraw"] == true }) { "추첨 전 후보가 없는 후속 회차는 재생성으로 대체하지 않습니다. 자동 판정을 보류합니다." }
            val oldRole = role(LottoDesignRecordJson.decimal(active.getValue("before")))
            val newRole = role(LottoDesignRecordJson.decimal(active.getValue("after")))
            val primary = mean(fresh, newRole) - mean(fresh, oldRole)
            val secondary = mean(fresh, newRole, true) - mean(fresh, oldRole, true)
            if (primary < -1e-9 || secondary <= -0.10 + 1e-9) {
                val reason = "새 4회 비교에서 설정 악화가 관찰되어 이전 가중치로 복구했습니다."
                rollbackTrial(product, active, reason, evidence = mapOf("primaryDifference" to primary, "secondaryDifference" to secondary,
                    "resultHashes" to fresh.map(LottoDesignRecordJson::hash), "run" to run.relativeTo(root).path))
                return reason
            }
            val value = LottoDesignRecordJson.decimal(active.getValue("after"))
            require(LotteryAutoImprovementWorker.isEnabled(app, product))
            val monitorEnd = rounds[results.indexOf(fresh.last())]
            store.append(product, "HOLD", value, value, mapOf("application" to active.getValue("recordHash"), "monitorThrough" to monitorEnd,
                "inputHash" to LottoDesignRecordJson.hash(input.through(monitorEnd).generationJson()), "resultHashes" to fresh.map(LottoDesignRecordJson::hash),
                "primaryDifference" to primary, "secondaryDifference" to secondary, "run" to run.relativeTo(root).path)) { LotteryAutoImprovementWorker.isEnabled(app, product) }
            return "새 4회 점검 완료 · 시험 가중치 유지"
        }
        if (store.attempted(product)) return "이번 가중치 후보의 판단 이력을 유지합니다. 같은 후보를 자동 재채택하지 않습니다."
        require(results.size == 12) { "시험 적용은 앞 6회·뒤 6회 전체 비교가 필요합니다." }
        val before = if (product == LotteryProduct.LOTTO_645) 32.0 else 1.0
        val after = if (product == LotteryProduct.LOTTO_645) { if (before == 32.0) 64.0 else 32.0 } else 0.5
        val oldRole = role(before); val newRole = role(after)
        val accepted = LotteryAdaptiveSettings.acceptsTrial(results.map { number(it, oldRole) }, results.map { number(it, newRole) }, results.map { number(it, "control") },
            results.map { number(it, oldRole, true) }, results.map { number(it, newRole, true) }, results.map { number(it, "control", true) })
        require(LotteryAutoImprovementWorker.isEnabled(app, product))
        val live = repository.getLotteryResearchInput(product)
        require(live.through(latest).generationJson() == input.generationJson()) { "설정 적용 직전 입력이 변경됐습니다." }
        val proof = mapOf("through" to latest, "appliedAfterRound" to live.rounds().maxOrNull(), "inputHash" to LottoDesignRecordJson.hash(input.generationJson()), "rounds" to rounds,
            "policy" to LotteryAdaptiveSettings.policy(), "pipeline" to LotteryCandidateArchive.IMPLEMENTATION, "run" to run.relativeTo(root).path,
            "resultHashes" to results.map(LottoDesignRecordJson::hash), "historicallyExplored" to true,
            "reason" to if (accepted) "두 구간 모두 최종 추천 당첨 비율 및 보조 일치도 개선" else "최종 추천 시험 적용 조건 미충족")
        val record = store.append(product, if (accepted) "APPLY" else "HOLD", before, if (accepted) after else before, proof) { LotteryAutoImprovementWorker.isEnabled(app, product) }
        val message = if (accepted) "가중치 $before → $after 시험 적용 · 과거 비교 기반이며 당첨 성과 입증은 아닙니다." else "비교 조건 미충족 · 기존 가중치 유지"
        Log.i(TAG, "product=$product phase=decision record=${record["recordHash"]} accepted=$accepted")
        if (accepted) LotteryAutoImprovementWorker.showChange(app, product, message)
        return message
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
        val active = repository.adaptiveSettings.active(input.product)
        val checkedThrough = active?.let { monitoringThrough(input.product, it) }
        val end = if (active != null) audit.lastRound ?: 0 else minOf(audit.lastRound ?: 0, protectedStart?.minus(1) ?: Int.MAX_VALUE)
        val snapshot = input.through(end)
        val first = snapshot.rounds().minOrNull() ?: 0
        val minimumHistory = if (input.product == LotteryProduct.PENSION_720) 17 else 1
        val eligible = snapshot.rounds().filter { it >= first + minimumHistory }.sorted()
        val targets = if (checkedThrough != null) eligible.filter { it > checkedThrough }.take(4) else eligible.takeLast(DESIGN_ROUNDS)
        val issue = when {
            !audit.canResearch -> "데이터 누락·중복·번호 오류 또는 최신 결과 미수집으로 비교를 보류합니다."
            active == null && targets.size < DESIGN_ROUNDS -> "이전 이력을 갖춘 보유 12회차가 필요합니다."
            else -> null
        }
        val evaluationInput = snapshot.through(targets.lastOrNull() ?: end)
        val json = mapOf("implementationId" to IMPLEMENTATION, "product" to input.product.name,
            "purpose" to "BOUNDED_EXPLORATORY_TRIAL", "targets" to targets, "input" to evaluationInput.json(),
            "application" to active?.get("recordHash"), "monitorThrough" to checkedThrough,
            "settings" to settings(input.product), "trialPolicy" to LotteryAdaptiveSettings.policy(), "seedPolicy" to "fixed-round-only-v1", "seedCount" to SEED_COUNT,
            "apkHash" to apkHash, "splitApkHashes" to splitHashes, "historyPresenceGuaranteed" to false,
            "historicalAvailabilityVerified" to false, "sourceEvidenceComplete" to false)
        return Preview(evaluationInput, targets, json, targets.size.toLong() * SEED_COUNT * 2 *
            (if (input.product == LotteryProduct.LOTTO_645) 12000 else 40000), issue)
    }

    private suspend fun calculate(input: LotteryResearchInput, round: Int, folder: File): Map<String, Any?> {
        val frozen = candidates.recorded(input, round) ?: candidates.freeze(input.through(round - 1), round, File(folder, "candidates.json.gz"))
        write(folder, "candidate-reference.json", mapOf("candidateHash" to LottoDesignRecordJson.hash(frozen), "preDraw" to frozen["preDraw"]))
        return candidates.evaluate(frozen, input, round)
    }

    private suspend fun legacyCalculate(input: LotteryResearchInput, round: Int, folder: File): Map<String, Any?> = when (input.product) {
        LotteryProduct.LOTTO_645 -> {
            val draws = input.lotto.map { LottoPriorDesignDraw(it.roundNo, it.numbers(), it.bonusNumber) }
            val plan = LottoPriorDesignComparison.prepare(draws, draws.minOf { it.roundNo }, listOf(round), commonSeeds(round), controlSeeds(round))
            val result = LottoPriorDesignComparison.compare(plan).rounds.single()
            mapOf("round" to round, "baselineRate" to result.baselineRate, "candidateRate" to result.candidateRate,
                "baselineSecondary" to result.seeds.map { it.baseline.meanMainMatchCount }.average(),
                "candidateSecondary" to result.seeds.map { it.candidate.meanMainMatchCount }.average(),
                "controlSecondary" to result.seeds.map { requireNotNull(it.control).meanMainMatchCount }.average(),
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
                PensionExperimentComparison.input(history, emptySet()), commonSeeds(round), controlSeeds(round), weightComparison = true) { model ->
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
        "trialPolicy" to LotteryAdaptiveSettings.policy(),
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
            "candidate" to LottoNumberGenerator.configurationSnapshotForPriorDesign(64.0), "mode" to "PRECISE", "pool" to 12000, "finalists" to 260, "comparisonImplementation" to LotteryCandidateArchive.IMPLEMENTATION)
        LotteryProduct.PENSION_720 -> mapOf("baseline" to LottoDesignRecordJson.decode(com.habittracker.ui.lotto.pensionIndividualWeightConfiguration(1.0).toByteArray(), requireCanonical = false),
            "candidate" to LottoDesignRecordJson.decode(com.habittracker.ui.lotto.pensionIndividualWeightConfiguration(0.5).toByteArray(), requireCanonical = false), "poolPerType" to 250, "finalistsPerType" to 50, "comparisonImplementation" to LotteryCandidateArchive.IMPLEMENTATION)
    }

    private fun commonSeeds(round: Int) = (0 until SEED_COUNT).map { 0x145B2L + round.toLong() * 1_000_003L + it * 97L }
    private fun controlSeeds(round: Int) = (0 until SEED_COUNT).map { -0x145B2L - round.toLong() * 1_000_003L - it * 97L }
    private fun percent(value: Double) = String.format(java.util.Locale.ROOT, "%.3f", value * 100) + "%"
    private data class Preview(val input: LotteryResearchInput, val targets: List<Int>, val json: Map<String, Any?>, val workCount: Long, val issue: String?)

    companion object {
        private val gate = Mutex()
        internal suspend fun <T> withRecordLock(action: suspend () -> T): T = gate.withLock { action() }
        private const val TAG = "LotteryAutoImprovement"
        private const val IMPLEMENTATION = "lottery-candidate-stage-adaptive-v4"
        // 탐색 비용의 상한이며 채택에 충분한 표본 수를 뜻하지 않는다.
        private const val DESIGN_ROUNDS = 12
        private const val SEED_COUNT = 1
        val SOURCE_FILES = listOf(
            "app/src/main/java/com/habittracker/data/AppContainer.kt",
            "app/src/main/java/com/habittracker/data/lotto/LotteryAutoImprovement.kt",
            "app/src/main/java/com/habittracker/data/lotto/LotteryAutoImprovementWorker.kt",
            "app/src/main/java/com/habittracker/data/lotto/LotteryAutomaticRegistration.kt",
            "app/src/main/java/com/habittracker/data/lotto/LotteryAdaptiveSettings.kt",
            "app/src/main/java/com/habittracker/data/lotto/LotteryCandidateArchive.kt",
            "app/src/main/java/com/habittracker/data/lotto/LotteryHistoryAudit.kt",
            "app/src/main/java/com/habittracker/data/lotto/LotteryDrawSyncModels.kt",
            "app/src/main/java/com/habittracker/ui/lotto/LotteryAutoImprovementSection.kt",
            "app/src/main/java/com/habittracker/ui/components/DesignSystem.kt",
        )
    }
}
