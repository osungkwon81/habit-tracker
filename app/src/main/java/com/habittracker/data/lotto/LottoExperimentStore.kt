package com.habittracker.data.lotto

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class LottoExperimentSummary(
    val id: String,
    val specification: Map<String, Any?>,
    val run: LottoDesignRecoveredRun?,
    val judgment: LottoExperimentJudgment,
    val issue: String?,
    val executionStarted: Boolean = false,
    val capturedInputRounds: List<Int> = emptyList(),
) {
    val targetRounds: List<Int> get() = LottoDesignRecordJson.list(specification.getValue("targetRounds")).map(LottoDesignRecordJson::integer)
    val seedCount: Int get() = LottoDesignRecordJson.list(specification.getValue("commonSeeds")).size
    val scoreCount: Long get() = targetRounds.size.toLong() * seedCount * 12000
    val selectedGameCount: Long get() = targetRounds.size.toLong() * seedCount * 15
}

data class LottoApprovedSetting(val priorDraws: Double, val approval: Map<String, Any?>?) {
    val configJson: String get() = LottoNumberGenerator.configurationSnapshot(priorDraws)
}

/** 기존 실행 기록과 분리한 등록·승인 이력. 자동 재실행·자동 적용은 하지 않는다. */
class LottoExperimentStore(context: Context, private val now: () -> Instant = { Instant.now() }) {
    private val app = context.applicationContext
    private val records = LottoDesignRunStore(app)
    private val root get() = File(app.filesDir.canonicalFile, "lotto-experiments")
    private val operations get() = File(root, "operating-settings")
    private val runtimeApkHash by lazy { hashFile(File(app.applicationInfo.sourceDir)) }

    suspend fun register(document: ByteArray, draws: List<LottoPriorDesignDraw>): String = locked {
        val input = LottoDesignRecordJson.decode(document, requireCanonical = false)
        require(input["formatVersion"] == 1) { "실험 등록 문서 버전은 1이어야 합니다." }
        val spec = obj(input.getValue("experiment"))
        validateSpec(spec)
        require(readSetting().priorDraws == 32.0) { "첫 실험 기준은 승인 기본값 32입니다. 다른 설정이 적용되어 있습니다." }
        val targets = ints(spec.getValue("targetRounds"))
        val start = int(spec.getValue("historyStartRound"))
        val available = draws.filter { it.roundNo in start..targets.last() }.sortedByDescending { it.roundNo }
        require(available.isNotEmpty() && available.first().roundNo >= start + 1) { "등록할 분석 입력이 부족합니다." }
        // prepare는 설정·입력 검증만 하며 번호를 생성하지 않는다.
        val preview = LottoPriorDesignComparison.prepare(available, start, listOf(available.first().roundNo), seeds(spec, "commonSeeds"), seeds(spec, "controlSeeds"))
        val recovered = records.recover()
        if (spec["purpose"] == "FINAL") {
            require(targets.first() > draws.maxOf { it.roundNo }) { "최종 실험은 결과를 보기 전 향후 회차로 등록해야 합니다." }
            require(now().isBefore(deadline(targets.first()))) { "최종 구간은 등록 시점에 추첨 전 확정 가능한 향후 회차여야 합니다." }
            require(!recovered.runs.any { it.exclusionCoverageUncertain } &&
                recovered.issues.all { it.startsWith("실행 기록 폴더가 없습니다.") || it.startsWith("보존된 실행이 없습니다.") }) { "과거 기록의 제외 회차가 불확실합니다." }
            require(text(spec, "historyCompletenessConfirmation").isNotBlank()) { "내부 파일만으로 이력 전체의 존재를 보증할 수 없습니다. 외부 이력 확인 근거를 남겨 주세요." }
            require(targets.intersect(recovered.excludedRounds).isEmpty()) { "이전 실행 사용 회차와 최종 구간이 겹칩니다." }
            val registered = registrations()
            require(registered.none { (_, old) -> ints(old.getValue("targetRounds")).intersect(targets.toSet()).isNotEmpty() }) { "이미 등록한 실험의 대상 회차를 재사용할 수 없습니다." }
            spec["policy"]?.let { value ->
                val policy = obj(value)
                if (policy["confirmedByUser"] != true) return@let
                val family = text(spec, "comparisonFamilyId")
                val priorFinals = registered.filter { (_, old) -> old["purpose"] == "FINAL" && old["baselineConfigHash"] == preview.baseline.configHash &&
                    old["candidateConfigHash"] == preview.candidate.configHash && old["policy"]?.let(::obj)?.get("confirmedByUser") == true }
                require(priorFinals.all { (_, old) -> old["comparisonFamilyId"] == family }) { "같은 설정 비교의 반복 평가를 다른 비교군으로 바꿔 보정을 초기화할 수 없습니다." }
                val sameFamily = priorFinals.size
                require(priorFinals.all { (_, old) ->
                    old["policy"]?.let(::obj)?.get("familyComparisonCount") == policy["familyComparisonCount"]
                }) { "같은 실험군의 보정 비교 수를 실행 결과에 따라 변경할 수 없습니다." }
                require(int(policy.getValue("familyComparisonCount")) >= (sameFamily + 1) * 2) { "실험군의 비교 전체를 Bonferroni 보정에 포함해야 합니다." }
            }
        }
        val id = records.newRunId()
        val directory = File(root, id)
        require(directory.mkdir()) { "실험 등록 ID가 이미 있습니다: $id" }
        LottoDesignRunStore.syncDirectory(root)
        val sourceInput = obj(input.getValue("sourceEvidence"))
        val source = importSource(directory, sourceInput)
        require(runtimeApkHash == source.apkHash && actualSplits() == source.splitApkHashes) { "소스 증빙과 실행 APK가 다릅니다." }
        val registration = spec + mapOf(
            "formatVersion" to 1, "id" to id, "registeredAt" to now().toString(),
            "inputAtRegistration" to preview.draws.map(::drawJson), "inputAtRegistrationHash" to preview.datasetHash,
            "baselineConfigHash" to preview.baseline.configHash, "candidateConfigHash" to preview.candidate.configHash,
            "implementationId" to preview.implementationId, "source" to LottoDesignRunStore.sourceJson(source),
        )
        write(File(directory, "registration.json"), registration + ("registrationHash" to LottoDesignRecordJson.hash(registration)))
        id
    }

    suspend fun list(draws: List<LottoPriorDesignDraw>): List<LottoExperimentSummary> = locked {
        val recovered = records.recover()
        requireNotNull(root.listFiles()).filter { it.name != "operating-settings" && it.name != ".lock" }.sortedBy { it.name }.map { dir ->
            val id = dir.name
            val spec = try { readRegistration(experimentDirectory(id)) } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                return@map LottoExperimentSummary(id, mapOf("targetRounds" to emptyList<Int>(), "commonSeeds" to emptyList<String>(), "purpose" to "INVALID"), null,
                    LottoExperimentJudgment(LottoExperimentDecision.HOLD, "등록 기록 손상·미완료: ${error.message}", 0), "등록 기록을 복구할 수 없습니다. 사용 회차의 완전성도 미확정입니다.")
            }
            val run = recovered.runs.singleOrNull { it.runId == id }
            val failure = File(dir, "failure.json").takeIf { it.isFile }?.let { read(it) }
            val preparationFailures = File(dir, "preparation-failures").takeIf { it.exists() }?.let { folder ->
                require(folder.isDirectory && folder.canonicalFile == folder.absoluteFile) { "준비 실패 경로 손상" }
                requireNotNull(folder.listFiles()).sortedBy { it.name }.map { file ->
                    val record = read(file)
                    require(record["recordHash"] == LottoDesignRecordJson.hash(record - "recordHash")) { "준비 실패 기록 해시 불일치" }
                    record
                }
            }.orEmpty()
            val issue = when {
                failure != null -> "${failure["state"]}: ${obj(failure.getValue("error"))["message"]}"
                run != null && run.state != LottoDesignRunState.SUCCESS -> "실행 상태: ${run.state}; ${run.issues.joinToString()}"
                run != null -> resultIssue(dir, spec, run) ?: currentInputIssue(run, draws)
                File(dir, "execution.json").exists() -> "시작 표식 이후 정상 종료 기록이 없습니다. 자동 재실행하지 않습니다."
                preparationFailures.isNotEmpty() -> "비교 계산 전 준비 실패 ${preparationFailures.size}건: ${obj(preparationFailures.last().getValue("error"))["message"]}. 계산은 시작하지 않았습니다. 자료 보완 후 동일한 고정 조건으로 수동 확인할 수 있습니다."
                else -> null
            }
            LottoExperimentSummary(id, spec, run, LottoExperimentPolicy.judge(spec, run, issue), issue,
                File(dir, "execution.json").exists(), capturedRounds(spec))
        }
    }

    suspend fun execute(id: String, draws: List<LottoPriorDesignDraw>): LottoPriorDesignResult {
        val prepared = try { locked {
            val directory = experimentDirectory(id)
            val spec = readRegistration(directory)
            require(!File(directory, "execution.json").exists()) { "이미 실행을 시도한 실험입니다. 실패·취소도 자동 재실행하지 않습니다." }
            val plan = prepare(spec, draws)
            if (spec["purpose"] == "FINAL") require(!now().isBefore(deadline(plan.targetRounds.last()))) { "고정 최종 구간의 추첨 종료 이후 전체 자료로 한 번 평가해야 합니다." }
            val recovery = records.recover()
            require(!recovery.runs.any { it.exclusionCoverageUncertain }) { "기존 실행의 제외 회차가 불확실합니다." }
            require(recovery.issues.all { it.startsWith("실행 기록 폴더가 없습니다.") || it.startsWith("보존된 실행이 없습니다.") }) { "기존 실행 폴더의 무결성을 확인할 수 없습니다." }
            val attemptedRounds = registrations().filter { it.first != id }.flatMap { (oldId, _) ->
                File(File(root, oldId), "execution.json").takeIf { it.exists() }?.let { ints(read(it).getValue("excludedRounds")) }.orEmpty()
            }.toSet()
            require(plan.targetRounds.intersect(recovery.excludedRounds + attemptedRounds).isEmpty() || spec["purpose"] == "DESIGN") { "최종 구간이 이전 성공·실패·취소·비정상 종료 실행의 사용 회차와 겹칩니다." }
            write(File(directory, "execution.json"), mapOf("id" to id, "startedAt" to now().toString(),
                "registrationHash" to LottoDesignRecordJson.hash(spec), "excludedRounds" to plan.draws.map { it.roundNo }.sorted()))
            plan to evidence(directory, obj(spec.getValue("source")))
        } } catch (error: Exception) {
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    val directory = experimentDirectory(id)
                    val spec = readRegistration(directory)
                    val folder = File(directory, "preparation-failures")
                    if (!folder.exists()) { require(folder.mkdir()); LottoDesignRunStore.syncDirectory(directory) }
                    val record = mapOf("id" to id, "at" to now().toString(), "stage" to "prepare_comparison",
                        "state" to if (error is CancellationException) "CANCELLED" else "FAILED",
                        "registrationHash" to LottoDesignRecordJson.hash(spec), "error" to LottoDesignRunStore.exceptionJson(error))
                    write(File(folder, "${now().toEpochMilli()}-${java.util.UUID.randomUUID()}.json"), record + ("recordHash" to LottoDesignRecordJson.hash(record)))
                }
            } catch (recordError: Exception) { if (recordError !== error) error.addSuppressed(recordError) }
            throw error
        }
        return try {
            val result = records.compareAndRecord(id, prepared.first, prepared.second)
            withContext(Dispatchers.IO) {
                val run = records.recover().runs.single { it.runId == id }
                val judgment = LottoExperimentPolicy.judge(requireNotNull(prepared.first.experiment), run)
                val record = mapOf("id" to id, "finishedAt" to now().toString(),
                    "registrationHash" to LottoDesignRecordJson.hash(requireNotNull(prepared.first.experiment)),
                    "planHash" to LottoDesignRecordJson.hash(LottoDesignRecordJson.decode(requireNotNull(run.planJson).toByteArray())),
                    "judgment" to judgmentJson(judgment), "excludedRounds" to result.designRoundsExcludedFromFinalEvaluation)
                write(File(experimentDirectory(id), "result.json"), record + ("recordHash" to LottoDesignRecordJson.hash(record)))
            }
            result
        } catch (error: Exception) {
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    write(File(experimentDirectory(id), "failure.json"), mapOf("id" to id,
                        "state" to if (error is CancellationException) "CANCELLED" else "FAILED", "endedAt" to now().toString(),
                        "error" to LottoDesignRunStore.exceptionJson(error)))
                }
            } catch (recordError: Exception) { if (recordError !== error) error.addSuppressed(recordError) }
            throw error
        }
    }

    suspend fun approvedSetting(): LottoApprovedSetting = locked { readSetting() }

    suspend fun captureInputs(id: String, target: Int, draws: List<LottoPriorDesignDraw>) = locked {
        val directory = experimentDirectory(id)
        val spec = readRegistration(directory)
        require(spec["purpose"] == "FINAL" && target in ints(spec.getValue("targetRounds"))) { "최종 실험의 대상 회차를 확인해 주세요." }
        require(!File(directory, "execution.json").exists()) { "비교가 시작된 실험의 입력을 추가할 수 없습니다." }
        require(now().isBefore(deadline(target)) && draws.none { it.roundNo == target }) { "당첨 결과를 보기 전 추첨 전에만 입력을 확정할 수 있습니다." }
        require(runtimeApkHash == obj(spec.getValue("source"))["apkHash"]) { "등록 이후 구현이 바뀌었습니다." }
        val history = draws.filter { it.roundNo in int(spec.getValue("historyStartRound")) until target }.sortedByDescending { it.roundNo }
        require(history.firstOrNull()?.roundNo == target - 1) { "직전 회차까지의 입력이 필요합니다." }
        LottoPriorDesignComparison.prepare(history, int(spec.getValue("historyStartRound")), listOf(target - 1), seeds(spec, "commonSeeds"), seeds(spec, "controlSeeds"))
        val registered = LottoDesignRecordJson.list(spec.getValue("inputAtRegistration")).map { parseDraw(obj(it)) }
        require(registered.all { it in history }) { "등록 원본이 정정되었습니다. 새 실험이 필요합니다." }
        val folder = File(directory, "input-snapshots")
        if (!folder.exists()) { require(folder.mkdir()); LottoDesignRunStore.syncDirectory(directory) }
        val record = mapOf("targetRoundNo" to target, "capturedAt" to now().toString(), "registrationHash" to LottoDesignRecordJson.hash(spec),
            "input" to history.map(::drawJson), "inputHash" to drawHash(history), "sourceHash" to LottoDesignRecordJson.hash(spec.getValue("source")))
        write(File(folder, "$target.json"), record + ("recordHash" to LottoDesignRecordJson.hash(record)))
    }

    suspend fun approve(id: String, draws: List<LottoPriorDesignDraw>, approvalReason: String) = locked {
        require(approvalReason.isNotBlank()) { "승인 이유가 필요합니다." }
        val directory = experimentDirectory(id)
        val spec = readRegistration(directory)
        val run = records.recover().runs.single { it.runId == id }
        val prepared = prepare(spec, draws)
        require(!now().isBefore(deadline(prepared.targetRounds.last()))) { "최종 구간 추첨 전에는 적용할 수 없습니다." }
        val runPlan = obj(LottoDesignRecordJson.decode(requireNotNull(run.planJson).toByteArray()))
        require(runPlan["experiment"] == spec) { "실행과 등록 명세가 다릅니다." }
        val models = LottoDesignRecordJson.list(runPlan.getValue("models")).map(::obj)
        require(models.map { it["configHash"] } == listOf(prepared.baseline.configHash, prepared.candidate.configHash) &&
            runPlan["commonSeeds"] == spec["commonSeeds"] && runPlan["controlSeeds"] == spec["controlSeeds"] &&
            runPlan["targetRounds"] == spec["targetRounds"] && runPlan["implementationId"] == prepared.implementationId) { "평가 조건과 등록 조건이 다릅니다." }
        require(prepared.datasetHash == runPlan["datasetHash"]) { "원본 정정 또는 입력 변경으로 승인할 수 없습니다." }
        require(!File(directory, "failure.json").exists()) { "실패 이력이 있는 실행은 적용할 수 없습니다." }
        val judgment = LottoExperimentPolicy.judge(spec, run, currentInputIssue(run, draws))
        require(resultIssue(directory, spec, run) == null) { "전체 결과·판정 기록을 확인할 수 없습니다." }
        require(judgment.decision == LottoExperimentDecision.ADOPTION_CANDIDATE) { judgment.reason }
        require(readSetting().priorDraws == 32.0) { "평가 기준과 현재 운영 설정이 다릅니다." }
        val source = obj(spec.getValue("source"))
        val runSource = read(File(app.filesDir, "lotto-design-runs/$id/source-manifest.json"))
        require(runSource - setOf("formatVersion", "runId", "kind") == source) { "평가 소스 증빙과 등록 증빙이 다릅니다." }
        require(runtimeApkHash == source["apkHash"] && actualSplits() == stringMap(source.getValue("splitApkHashes"))) { "평가 이후 앱 구현이 바뀌었습니다. 새 실험이 필요합니다." }
        appendSetting(64.0, "APPLY", approvalReason, mapOf(
            "experimentId" to id, "registrationHash" to LottoDesignRecordJson.hash(spec),
            "evaluationPlanHash" to LottoDesignRecordJson.hash(runPlan),
            "evaluationEventsHash" to hashFile(File(File(app.filesDir, "lotto-design-runs/$id"), "events.jsonl")),
            "source" to source, "judgment" to judgmentJson(judgment),
        ))
    }

    suspend fun rollback(approvalReason: String) = locked {
        require(approvalReason.isNotBlank()) { "복구 이유가 필요합니다." }
        val log = readOperations()
        require(log.isNotEmpty()) { "복구할 이전 승인 설정이 없습니다." }
        val previous = LottoDesignRecordJson.decimal(log.last().getValue("previousPriorDraws"))
        require(log.last()["previousConfigJson"] == LottoNumberGenerator.configurationSnapshot(previous)) { "이전 설정을 현재 구현으로 재현할 수 없습니다. 복구를 보류합니다." }
        appendSetting(previous, "ROLLBACK", approvalReason, mapOf("restoredFromOperation" to log.last().getValue("sequence")))
    }

    private fun prepare(spec: Map<String, Any?>, draws: List<LottoPriorDesignDraw>): LottoPriorDesignPlan {
        validateSpec(spec)
        val proofs = if (spec["purpose"] == "FINAL") inputProofs(spec, draws) else emptyMap()
        val plan = LottoPriorDesignComparison.prepare(draws, int(spec.getValue("historyStartRound")), ints(spec.getValue("targetRounds")),
            seeds(spec, "commonSeeds"), seeds(spec, "controlSeeds"), spec, proofs)
        require(plan.baseline.configHash == spec["baselineConfigHash"] && plan.candidate.configHash == spec["candidateConfigHash"] &&
            plan.implementationId == spec["implementationId"]) { "등록 이후 구현·설정이 변경되었습니다. 새 실험이 필요합니다." }
        val registered = LottoDesignRecordJson.list(spec.getValue("inputAtRegistration")).map { value -> parseDraw(obj(value)) }
        require(registered.all { draw -> plan.draws.singleOrNull { it.roundNo == draw.roundNo } == draw }) { "등록 원본이 정정되었습니다. 기존 등록을 보존하고 새 실험을 등록해 주세요." }
        require(LottoDesignRecordJson.hash(registered.joinToString("|") { "${it.roundNo}:${it.numbers.joinToString(",")}:${it.bonusNumber}" }.toByteArray()) == spec["inputAtRegistrationHash"]) { "등록 입력 해시가 다릅니다." }
        return plan
    }

    private fun validateSpec(spec: Map<String, Any?>) {
        require(spec["purpose"] in listOf("DESIGN", "FINAL")) { "실험 용도는 DESIGN 또는 FINAL이어야 합니다." }
        val targets = ints(spec.getValue("targetRounds"))
        val start = int(spec.getValue("historyStartRound"))
        require(start > 0 && targets.isNotEmpty() && targets == targets.distinct().sorted() && targets.all { it > start }) { "대상 회차·입력 시작 회차 오류" }
        require(text(spec, "hypothesis").isNotBlank() && text(spec, "comparisonFamilyId").isNotBlank()) { "가설·비교군 식별자가 필요합니다." }
        val selection = ints(spec.getValue("selectionRounds"))
        require(selection == selection.distinct().sorted() && selection.all { it > 0 && it < targets.first() } && selection.intersect(targets.toSet()).isEmpty()) { "설정 선택 구간은 평가 구간보다 앞서야 합니다." }
        val common = seeds(spec, "commonSeeds")
        val control = seeds(spec, "controlSeeds")
        require(common.isNotEmpty() && common.distinct().size == common.size && control.size == common.size &&
            control.distinct().size == control.size && common.intersect(control.toSet()).isEmpty()) { "모델 공통 시드와 별도 대조군 시드를 같은 개수로 고정해 주세요." }
        spec["policy"]?.let { value -> obj(value).takeIf { it["confirmedByUser"] == true }?.let {
            LottoExperimentPolicy.validate(it, targets.size)
            require(!Instant.parse(text(it, "confirmedAt")).isAfter(now())) { "정책 확정 시각이 미래입니다." }
        } }
    }

    private fun currentInputIssue(run: LottoDesignRecoveredRun, draws: List<LottoPriorDesignDraw>): String? {
        val document = run.planJson?.let { LottoDesignRecordJson.decode(it.toByteArray()) } ?: return "계획을 복구할 수 없습니다."
        val snapshots = LottoDesignRecordJson.list(document.getValue("draws")).map { parseDraw(obj(it)) }
        return if (snapshots.any { snapshot -> draws.singleOrNull { it.roundNo == snapshot.roundNo } != snapshot })
            "실행 입력에 누락·중복·원본 정정이 있습니다. 기존 기록은 보존하지만 채택 근거로 사용할 수 없습니다." else null
    }

    private fun resultIssue(directory: File, spec: Map<String, Any?>, run: LottoDesignRecoveredRun): String? = try {
        val result = read(File(directory, "result.json"))
        val execution = read(File(directory, "execution.json"))
        val plan = LottoDesignRecordJson.decode(requireNotNull(run.planJson).toByteArray())
        require(plan["experiment"] == spec && plan["targetRounds"] == spec["targetRounds"] &&
            plan["commonSeeds"] == spec["commonSeeds"] && plan["controlSeeds"] == spec["controlSeeds"] &&
            LottoDesignRecordJson.list(plan.getValue("models")).map { obj(it)["configHash"] } == listOf(spec["baselineConfigHash"], spec["candidateConfigHash"])) { "등록·실행 모델과 구간·시드가 다릅니다." }
        if (spec["purpose"] == "FINAL") require(plan["inputProofs"] == inputProofs(spec, LottoDesignRecordJson.list(plan.getValue("draws")).map { parseDraw(obj(it)) })) { "추첨 전 입력 참조가 다릅니다." }
        val source = read(File(app.filesDir, "lotto-design-runs/${directory.name}/source-manifest.json"))
        require(source - setOf("formatVersion", "runId", "kind") == spec["source"]) { "등록·실행 소스 증빙이 다릅니다." }
        require(result["id"] == directory.name && result["registrationHash"] == LottoDesignRecordJson.hash(spec) &&
            result["recordHash"] == LottoDesignRecordJson.hash(result - "recordHash") && result["planHash"] == LottoDesignRecordJson.hash(plan) &&
            result["judgment"] == judgmentJson(LottoExperimentPolicy.judge(spec, run)) &&
            ints(result.getValue("excludedRounds")).toSet() == run.excludedRounds) { "최종 결과 참조·판정·제외 회차 불일치" }
        require(execution["id"] == directory.name && execution["registrationHash"] == LottoDesignRecordJson.hash(spec) &&
            ints(execution.getValue("excludedRounds")).toSet() == run.excludedRounds &&
            !Instant.parse(text(execution, "startedAt")).isBefore(Instant.parse(text(spec, "registeredAt")))) { "시작 기록 참조·사용 회차·등록 순서 불일치" }
        require(!Instant.parse(text(result, "finishedAt")).isBefore(Instant.parse(text(execution, "startedAt")))) { "종료 시각이 시작보다 앞섭니다." }
        null
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { "최종 결과 기록 손상·미완료: ${error.message}" }

    private fun registrations(): List<Pair<String, Map<String, Any?>>> = requireNotNull(root.listFiles()).filter { it.name != "operating-settings" && it.name != ".lock" }.sortedBy { it.name }.map { directory ->
        require(directory.isDirectory && directory.canonicalFile == directory.absoluteFile && directory.name.matches(ID)) { "실험 등록 경로가 손상되었습니다: ${directory.name}" }
        directory.name to readRegistration(directory)
    }

    private fun inputProofs(spec: Map<String, Any?>, draws: List<LottoPriorDesignDraw>): Map<String, String> {
        val initial = LottoDesignRecordJson.list(spec.getValue("inputAtRegistration")).map { parseDraw(obj(it)) }
        val registeredAt = Instant.parse(text(spec, "registeredAt"))
        val directory = experimentDirectory(text(spec, "id"))
        return ints(spec.getValue("targetRounds")).associate { target ->
            val file = File(directory, "input-snapshots/$target.json")
            val input: List<LottoPriorDesignDraw>
            val proofHash: String
            if (!file.exists() && initial.first().roundNo == target - 1 && registeredAt.isBefore(deadline(target))) {
                input = initial
                proofHash = LottoDesignRecordJson.hash(spec)
            } else {
                val proof = read(file)
                val capturedAt = Instant.parse(text(proof, "capturedAt"))
                require(int(proof.getValue("targetRoundNo")) == target && proof["registrationHash"] == LottoDesignRecordJson.hash(spec) &&
                    proof["recordHash"] == LottoDesignRecordJson.hash(proof - "recordHash") && capturedAt.isBefore(deadline(target)) &&
                    !capturedAt.isBefore(registeredAt) && proof["sourceHash"] == LottoDesignRecordJson.hash(spec.getValue("source"))) { "추첨 전 입력 증빙이 손상되었거나 추첨 이후 확정됐습니다: $target" }
                input = LottoDesignRecordJson.list(proof.getValue("input")).map { parseDraw(obj(it)) }
                require(proof["inputHash"] == drawHash(input)) { "추첨 전 입력 해시 오류: $target" }
                proofHash = text(proof, "recordHash")
            }
            val expected = draws.filter { it.roundNo in int(spec.getValue("historyStartRound")) until target }.sortedByDescending { it.roundNo }
            require(input == expected) { "${target}회차 사전 입력이 없거나 원본이 정정됐습니다. 최신 자료로 대체하지 않습니다." }
            target.toString() to proofHash
        }
    }

    private fun capturedRounds(spec: Map<String, Any?>): List<Int> {
        if (spec["purpose"] != "FINAL") return emptyList()
        val initial = LottoDesignRecordJson.list(spec.getValue("inputAtRegistration")).map { parseDraw(obj(it)) }
        val directory = experimentDirectory(text(spec, "id"))
        return ints(spec.getValue("targetRounds")).filter { target ->
            initial.first().roundNo == target - 1 || File(directory, "input-snapshots/$target.json").exists()
        }
    }

    private fun importSource(directory: File, input: Map<String, Any?>): LottoDesignSourceEvidence {
        val sourceRoot = File(directory, "source")
        require(sourceRoot.mkdir()) { "소스 증빙 폴더 생성 실패" }
        LottoDesignRunStore.syncDirectory(directory)
        val files = LottoDesignRecordJson.list(input.getValue("files")).map { value ->
            val entry = obj(value)
            val path = text(entry, "path")
            require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path && path.split('/').all { it.isNotBlank() && it != "." && it != ".." }) { "소스 경로 오류" }
            val bytes = text(entry, "content").toByteArray(Charsets.UTF_8)
            val hash = text(entry, "sha256")
            require(LottoDesignRecordJson.hash(bytes) == hash) { "소스 원문 해시 불일치: $path" }
            val target = File(sourceRoot, path)
            LottoDesignRunStore.createParents(sourceRoot, target.parentFile!!)
            LottoDesignRunStore.writeImmutable(target, bytes)
            LottoDesignSourceFile(path, hash)
        }.sortedBy { it.relativePath }
        require(files.map { it.relativePath }.distinct().size == files.size) { "소스 파일 경로가 중복됩니다." }
        val required = setOf("LottoNumberGenerator.kt", "LottoPriorDesignComparison.kt", "LottoDesignRunStore.kt", "LottoDesignRecordJson.kt",
            "LottoExperimentStore.kt", "LottoExperimentPolicy.kt", "LottoViewModel.kt", "LottoScreen.kt", "LottoExperimentSection.kt", "HabitRepository.kt")
        require(files.map { File(it.relativePath).name }.toSet().containsAll(required) && files.map { it.relativePath }.containsAll(listOf("app/build.gradle.kts", "build.gradle.kts", "gradle/libs.versions.toml"))) { "계산·정책·적용·화면·빌드 관련 실제 소스를 모두 보존해 주세요." }
        val sourceMap = input + ("files" to files.map { mapOf("path" to it.relativePath, "sha256" to it.sha256) })
        val source = evidence(directory, sourceMap)
        require(source.buildSourceHash == LottoDesignRecordJson.hash(sourceMap.getValue("files"))) { "소스 목록 전체 해시 불일치" }
        require(source.dependencyVersions["kotlinStdlib"] == KotlinVersion.CURRENT.toString()) { "표준 라이브러리 버전이 다릅니다." }
        require(source.buildReference.isNotBlank() && sourceMap["hasUncommittedChanges"] is Boolean &&
            source.dependencyVersions.keys.containsAll(listOf("kotlin", "kotlinStdlib", "coroutines"))) { "빌드·의존성 식별과 미커밋 변경 여부를 명시해 주세요." }
        return source
    }

    private fun evidence(directory: File, value: Map<String, Any?>) = LottoDesignSourceEvidence(
        File(directory, "source"), LottoDesignRecordJson.list(value.getValue("files")).map { entry -> obj(entry).let { LottoDesignSourceFile(text(it, "path"), text(it, "sha256")) } },
        text(value, "buildSourceHash"), text(value, "apkHash"), stringMap(value.getValue("splitApkHashes")), stringMap(value.getValue("dependencyVersions")),
        text(value, "buildReference"), value["gitHead"]?.let(LottoDesignRecordJson::string), value["hasUncommittedChanges"] == true,
    )

    private fun readSetting(): LottoApprovedSetting {
        val log = readOperations()
        val last = log.lastOrNull() ?: return LottoApprovedSetting(32.0, null)
        val prior = LottoDesignRecordJson.decimal(last.getValue("priorDraws"))
        require(last["configJson"] == LottoNumberGenerator.configurationSnapshot(prior)) { "승인 설정과 현재 구현이 다릅니다. 자동 대체하지 않습니다." }
        return LottoApprovedSetting(prior, last)
    }

    private fun readOperations(): List<Map<String, Any?>> {
        if (!operations.exists()) return emptyList()
        require(operations.isDirectory && operations.canonicalFile == operations.absoluteFile) { "운영 승인 경로 손상" }
        val files = requireNotNull(operations.listFiles()).sortedBy { it.name }
        require(files.isNotEmpty()) { "운영 승인 기록이 비어 있습니다. 유실 여부를 확인해 주세요." }
        var previousHash: String? = null
        var previousPrior = 32.0
        return files.mapIndexed { index, file ->
            require(file.name == "%08d.json".format(java.util.Locale.ROOT, index + 1)) { "운영 승인 기록 누락·미완료 파일" }
            val item = read(file)
            require(item["formatVersion"] == 1 && int(item.getValue("sequence")) == index + 1 && item["previousHash"] == previousHash &&
                LottoDesignRecordJson.decimal(item.getValue("previousPriorDraws")) == previousPrior &&
                item["recordHash"] == LottoDesignRecordJson.hash(item - "recordHash")) { "운영 승인 기록 해시·순서 불일치" }
            require(item["action"] in listOf("APPLY", "ROLLBACK") && item["userApproved"] == true && text(item, "reason").isNotBlank()) { "승인/복구 근거 누락" }
            require(item["strategy"] == "BALANCED" && item["mode"] == "BASIC" && int(item.getValue("gameCount")) == 5) { "승인 적용 범위 오류" }
            Instant.parse(text(item, "approvedAt"))
            previousPrior = LottoDesignRecordJson.decimal(item.getValue("priorDraws"))
            require(previousPrior == 32.0 || previousPrior == 64.0) { "지원하지 않는 승인 설정" }
            require(item["configHash"] == LottoDesignRecordJson.hash(text(item, "configJson").toByteArray())) { "승인 설정 해시 불일치" }
            previousHash = text(item, "recordHash")
            item
        }
    }

    private fun appendSetting(prior: Double, action: String, reason: String, evidence: Map<String, Any?>) {
        val log = readOperations()
        val previous = log.lastOrNull()
        val config = LottoNumberGenerator.configurationSnapshot(prior)
        val item = mapOf("formatVersion" to 1, "sequence" to log.size + 1, "previousHash" to previous?.get("recordHash"),
            "strategy" to "BALANCED", "mode" to "BASIC", "gameCount" to 5,
            "previousPriorDraws" to (previous?.get("priorDraws") ?: 32.0), "previousConfigJson" to (previous?.get("configJson") ?: LottoNumberGenerator.configurationSnapshot()),
            "priorDraws" to prior, "configJson" to config, "configHash" to LottoDesignRecordJson.hash(config.toByteArray()),
            "action" to action, "reason" to reason, "approvedAt" to now().toString(), "userApproved" to true, "evidence" to evidence)
        if (!operations.exists()) { require(operations.mkdir()); LottoDesignRunStore.syncDirectory(root) }
        write(File(operations, "%08d.json".format(java.util.Locale.ROOT, log.size + 1)), item + ("recordHash" to LottoDesignRecordJson.hash(item)))
    }

    private suspend fun <T> locked(block: suspend () -> T): T = gate.withLock {
        withContext(Dispatchers.IO) {
            if (!root.exists()) { require(root.mkdir()) { "실험 폴더 생성 실패" }; LottoDesignRunStore.syncDirectory(root.parentFile!!) }
            require(root.isDirectory && root.canonicalFile == root.absoluteFile) { "실험 경로 손상" }
            val lockFile = File(root, ".lock")
            require(lockFile.canonicalFile == lockFile.absoluteFile) { "실험 잠금 경로 손상" }
            RandomAccessFile(lockFile, "rw").use { file ->
                val lock = file.channel.lock()
                var failure: Exception? = null
                try { block() } catch (error: Exception) { failure = error; throw error }
                finally { try { lock.release() } catch (releaseError: Exception) {
                    val original = failure
                    if (original == null) throw releaseError else original.addSuppressed(releaseError)
                } }
            }
        }
    }

    private fun experimentDirectory(id: String): File {
        require(ID.matches(id)) { "실험 ID 오류" }
        return File(root, id).also { require(it.isDirectory && it.canonicalFile == it.absoluteFile) { "등록 실험을 찾을 수 없습니다." } }
    }
    private fun readRegistration(directory: File): Map<String, Any?> = read(File(directory, "registration.json")).also {
        require(it["id"] == directory.name && it["formatVersion"] == 1 &&
            it["registrationHash"] == LottoDesignRecordJson.hash(it - "registrationHash")) { "실험 ID/버전/등록 해시 불일치" }
        validateSpec(it)
        val registeredAt = Instant.parse(text(it, "registeredAt"))
        it["policy"]?.let { policy -> obj(policy).takeIf { p -> p["confirmedByUser"] == true }?.let { p ->
            require(!Instant.parse(text(p, "confirmedAt")).isAfter(registeredAt)) { "등록 이후 확정한 정책입니다. 새 실험이 필요합니다." }
        } }
        if (it["purpose"] == "FINAL") require(registeredAt.isBefore(deadline(ints(it.getValue("targetRounds")).first()))) { "최종 계획의 추첨 전 등록을 확인할 수 없습니다." }
    }
    private fun actualSplits() = app.applicationInfo.splitSourceDirs.orEmpty().associate { File(it).name to hashFile(File(it)) }
    private fun deadline(round: Int) = lotteryEvaluationDeadline(LotteryProduct.LOTTO_645, round).atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant()
    private fun write(file: File, value: Any?) = LottoDesignRunStore.writeImmutable(file, LottoDesignRecordJson.encode(value).toByteArray(Charsets.UTF_8))
    private fun read(file: File): Map<String, Any?> {
        require(file.isFile && file.canonicalFile == file.absoluteFile) { "기록 파일이 없거나 경로가 손상되었습니다: ${file.name}" }
        return LottoDesignRecordJson.decode(file.readBytes())
    }
    private fun obj(value: Any?) = LottoDesignRecordJson.objectValue(value)
    private fun int(value: Any?) = LottoDesignRecordJson.integer(value)
    private fun ints(value: Any?) = LottoDesignRecordJson.list(value).map(::int)
    private fun text(value: Map<String, Any?>, key: String) = LottoDesignRecordJson.string(value.getValue(key))
    private fun seeds(value: Map<String, Any?>, key: String) = LottoDesignRecordJson.list(value.getValue(key)).map { entry ->
        LottoDesignRecordJson.string(entry).let { require(it.toLong().toString() == it); it.toLong() }
    }
    private fun stringMap(value: Any?) = obj(value).mapValues { LottoDesignRecordJson.string(it.value) }
    private fun drawJson(draw: LottoPriorDesignDraw) = mapOf("roundNo" to draw.roundNo, "numbers" to draw.numbers, "bonusNumber" to draw.bonusNumber)
    private fun drawHash(draws: List<LottoPriorDesignDraw>) = LottoDesignRecordJson.hash(draws.joinToString("|") { "${it.roundNo}:${it.numbers.joinToString(",")}:${it.bonusNumber}" }.toByteArray(Charsets.UTF_8))
    private fun parseDraw(value: Map<String, Any?>) = LottoPriorDesignDraw(int(value.getValue("roundNo")), ints(value.getValue("numbers")), value["bonusNumber"]?.let(::int))
    private fun judgmentJson(value: LottoExperimentJudgment) = mapOf("decision" to value.decision.name, "reason" to value.reason, "independentRounds" to value.independentRounds,
        "baseline95" to value.baseline95?.let { mapOf("estimate" to it.estimate, "lower" to it.lower, "upper" to it.upper) },
        "control95" to value.control95?.let { mapOf("estimate" to it.estimate, "lower" to it.lower, "upper" to it.upper) },
        "baselineAdjusted" to value.baselineAdjusted?.let { mapOf("estimate" to it.estimate, "lower" to it.lower, "upper" to it.upper) },
        "controlAdjusted" to value.controlAdjusted?.let { mapOf("estimate" to it.estimate, "lower" to it.lower, "upper" to it.upper) })
    private fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { stream -> val buffer = ByteArray(8192); while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    companion object {
        private val gate = Mutex()
        private val ID = Regex("design-[0-9]{8}T[0-9]{9}Z-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
