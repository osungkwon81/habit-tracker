package com.habittracker.data.lotto

import android.content.Context
import com.habittracker.data.local.entity.PensionLotteryDrawEntity
import com.habittracker.data.local.entity.PensionLotteryGeneratedNumberEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

internal data class PensionExperimentInput(val draws: List<PensionLotteryDrawEntity>, val recommendations: List<PensionLotteryGeneratedNumberEntity>)
internal data class PensionOperatingSetting(val count: Int, val approvalHash: String? = null)
internal data class PensionExperimentSummary(
    val id: String, val specification: Map<String, Any?>?, val state: String, val issues: List<String>,
    val rounds: List<Map<String, Any?>>, val events: List<Map<String, Any?>>, val capturedRounds: List<Int>,
    val excludedRounds: Set<Int>, val exclusionUncertain: Boolean, val judgment: LottoExperimentJudgment,
    val failures: List<Map<String, Any?>> = emptyList(),
)

/** 연금의 파일 경계만 추가한다. 정규 JSON·배타 쓰기·동기화·구간 계산은 기존 로또 구현을 공유한다. */
internal class PensionExperimentStore(context: Context, private val now: () -> Instant = { Instant.now() }) {
    private val app = context.applicationContext
    private val root get() = File(app.filesDir.canonicalFile, "pension-experiments")
    private val operationRoot get() = File(root, "operating-settings")
    private val apk by lazy { hash(File(app.applicationInfo.sourceDir).readBytes()) }
    private fun splits() = app.applicationInfo.splitSourceDirs.orEmpty().associate { File(it).name to hash(File(it).readBytes()) }

    suspend fun setting(): PensionOperatingSetting = locked { readSetting() }
    suspend fun list(): List<PensionExperimentSummary> = locked { directories().map(::recover) }

    suspend fun register(document: ByteArray, input: PensionExperimentInput): String = locked {
        val bundle = LottoDesignRecordJson.decode(document, requireCanonical = false)
        require(bundle["formatVersion"] == 1) { "연금 등록 형식 버전은 1입니다." }
        val supplied = obj(bundle.getValue("experiment"))
        validateSpec(supplied)
        require(input.draws.isNotEmpty()) { "연금 당첨 입력이 없습니다." }
        require(int(supplied.getValue("historyStartRound")) == input.draws.minOf { it.roundNo }) { "운영 생성과 같은 전체 저장 당첨 이력의 시작 회차를 사용해야 합니다." }
        require(readSetting().count == 3) { "첫 연금 실험의 운영 기준은 3개입니다." }
        val targets = rounds(supplied)
        require(targets.first() > input.draws.maxOf { it.roundNo } && now().isBefore(deadline(targets.first()))) { "당시 제외 목록을 사후 추정하지 않습니다. 설계·최종 실험 모두 추첨 전 향후 회차로 등록해야 합니다." }
        val previous = directories().map(::recover)
        require(previous.none { it.exclusionUncertain }) { "과거 연금 기록의 계획·제외 범위를 확인할 수 없습니다." }
        require(previous.none { it.specification?.let(::rounds)?.intersect(targets.toSet())?.isNotEmpty() == true }) { "이미 등록된 대상 회차는 재사용할 수 없습니다." }
        if (supplied["purpose"] == "FINAL") {
            require(targets.first() > input.draws.maxOf { it.roundNo } && now().isBefore(deadline(targets.first()))) { "최종 구간은 추첨 전 향후 회차로 등록해야 합니다." }
            require(text(supplied, "historyCompletenessConfirmation").isNotBlank()) { "내부 기록 부재만으로 과거 미사용을 확인할 수 없습니다. 외부 이력 확인 근거가 필요합니다." }
            require(previous.none { targets.intersect(it.excludedRounds).isNotEmpty() }) { "최종 구간에 이전 성공·실패·취소·비정상 종료의 사용 회차가 있습니다." }
            val confirmed = previous.mapNotNull { it.specification }.filter { it["purpose"] == "FINAL" && it["policy"]?.let(::obj)?.get("confirmedByUser") == true }
            supplied["policy"]?.let(::obj)?.takeIf { it["confirmedByUser"] == true }?.let { policy ->
                require(confirmed.all { it["comparisonFamilyId"] == supplied["comparisonFamilyId"] && obj(it.getValue("policy"))["familyComparisonCount"] == policy["familyComparisonCount"] }) { "반복 비교의 비교군·보정 예산을 초기화하거나 변경할 수 없습니다." }
                require(int(policy.getValue("familyComparisonCount")) >= 2 * (confirmed.size + 1)) { "채택에 쓰는 비교 전체를 보정 예산에 포함해 주세요." }
            }
        }
        val available = input.draws.filter { it.roundNo >= int(supplied.getValue("historyStartRound")) }
        require(available.size >= 17) { "등록할 과거 분석 입력은 최소 17회가 필요합니다." }
        val initial = snapshotInput(available, input.recommendations)
        val source = obj(bundle.getValue("sourceEvidence"))
        validateSource(source)
        val id = "pension-${ID_TIME.format(now())}-${UUID.randomUUID()}"
        val directory = File(root, id)
        require(directory.mkdir()) { "연금 실행 ID가 이미 존재하거나 폴더를 만들 수 없습니다: $id" }
        LottoDesignRunStore.syncDirectory(root)
        val sourceRoot = File(directory, "source").apply { require(mkdir()) }
        list(source.getValue("files")).forEach { raw -> val entry = obj(raw); val path = text(entry, "path")
            val output = File(sourceRoot, path); LottoDesignRunStore.createParents(sourceRoot, output.parentFile!!)
            LottoDesignRunStore.writeImmutable(output, text(entry, "content").toByteArray(Charsets.UTF_8))
        }
        val evidence = source + mapOf("files" to list(source.getValue("files")).map { obj(it) - "content" }, "formatVersion" to LottoDesignRecordJson.VERSION)
        write(File(directory, "source-manifest.json"), sealed(evidence))
        val spec = supplied + mapOf("id" to id, "registeredAt" to now().toString(), "implementationId" to PensionExperimentComparison.IMPLEMENTATION,
            "baselineConfig" to PensionExperimentComparison.configuration(3), "candidateConfig" to PensionExperimentComparison.configuration(4),
            "sourceHash" to hash(evidence), "initialInput" to initial, "initialInputHash" to hash(initial))
        val frozen = sealed(spec)
        write(File(directory, "registration.json"), frozen)
        val snapshots = File(directory, "inputs").apply { require(mkdir()); LottoDesignRunStore.syncDirectory(directory) }
        if (available.maxOf { it.roundNo } == targets.first() - 1) {
            storeInput(snapshots, frozen, targets.first(), initial, "PRE_DRAW")
        }
        id
    }

    suspend fun capture(id: String, target: Int, input: PensionExperimentInput) = locked {
        val directory = directory(id); val spec = registration(directory)
        require(target in rounds(spec) && !File(directory, "execution.json").exists()) { "등록된 미실행 회차의 추첨 전 입력만 추가할 수 있습니다." }
        require(now().isBefore(deadline(target)) && input.draws.none { it.roundNo >= target }) { "대상 당첨 결과가 없는 추첨 전에만 입력을 확정할 수 있습니다." }
        sourceIssue(directory, spec)?.let { error(it) }
        val captured = snapshotInput(input.draws.filter { it.roundNo >= int(spec.getValue("historyStartRound")) }, input.recommendations)
        require(PensionExperimentComparison.draws(obj(captured.getValue("input"))).first().roundNo == target - 1) { "직전 회차까지의 입력이 필요합니다." }
        requireUnchangedPrefix(spec, input.draws)
        storeInput(File(directory, "inputs"), spec, target, captured, "PRE_DRAW")
    }

    suspend fun execute(id: String, live: PensionExperimentInput) {
        var journal: Journal? = null
        var stage = "prepare"
        var targets: List<Int> = emptyList()
        var completedModels = 0
        var activeRound: Int? = null
        var registrationReference: String? = null
        try {
            val prepared = locked {
                val directory = directory(id); val spec = registration(directory); targets = rounds(spec); registrationReference = hash(spec)
                require(!File(directory, "execution.json").exists()) { "이미 실행을 시도했습니다. 자동 재실행·이어 실행하지 않습니다." }
                sourceIssue(directory, spec)?.let { error(it) }
                requireUnchangedPrefix(spec, live.draws)
                require(!now().isBefore(deadline(targets.last()))) { "고정 구간 종료 후 전체 회차를 한 번 평가해야 합니다." }
                val recovery = directories().map(::recover)
                require(recovery.none { it.exclusionUncertain }) { "계획·제외 범위가 불확실한 과거 기록이 있습니다." }
                if (spec["purpose"] == "FINAL") require(recovery.filter { it.id != id }.none { targets.intersect(it.excludedRounds).isNotEmpty() }) { "독립 최종 구간이 과거 사용 회차와 겹칩니다." }
                val inputs = targets.associateWith { readInput(directory, spec, it) }
                inputs.forEach { (target, captured) -> validateLiveInput(captured, live.draws, target) }
                val winners = targets.associateWith { target -> live.draws.singleOrNull { it.roundNo == target } ?: error("$target 회차 당첨 결과가 없거나 중복됐습니다.") }
                val used = (int(spec.getValue("historyStartRound"))..targets.last()).toList()
                write(File(directory, "execution.json"), sealed(mapOf("registrationHash" to hash(spec), "startedAt" to now().toString(),
                    "excludedRounds" to used, "targets" to winners.values.map { PensionExperimentComparison.input(listOf(it), emptySet()) })))
                val active = Journal(directory, hash(spec)); journal = active
                active.append(mapOf("event" to "START", "stage" to "start"))
                Triple(spec, inputs, winners)
            }
            val results = mutableListOf<Map<String, Any?>>()
            for (target in targets) {
                activeRound = target
                kotlinx.coroutines.currentCoroutineContext().ensureActive(); stage = "compare_round"
                val round = PensionExperimentComparison.compare(prepared.third.getValue(target), obj(prepared.second.getValue(target).getValue("input")),
                    seeds(prepared.first, "commonSeeds"), seeds(prepared.first, "controlSeeds")) { model ->
                    withContext(Dispatchers.IO) { requireNotNull(journal).append(mapOf("event" to "MODEL", "modelResult" to model)) }
                    if (model["model"] != "CONTROL") completedModels++
                }
                stage = "round_record"
                withContext(Dispatchers.IO) { requireNotNull(journal).append(mapOf("event" to "ROUND", "roundResult" to round,
                    "inputProofHash" to hash(prepared.second.getValue(target)))) }
                results += round
            }
            stage = "result_record"
            withContext(Dispatchers.IO) {
                val result = sealed(mapOf("registrationHash" to hash(prepared.first), "finishedAt" to now().toString(),
                    "rounds" to results, "plannedRounds" to targets, "successfulModelBatches" to targets.size * seeds(prepared.first, "commonSeeds").size * 2,
                    "failedModelBatches" to 0, "generationFailureRate" to 0.0))
                write(File(directory(id), "result.json"), result)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                requireNotNull(journal).append(mapOf("event" to "SUCCESS", "resultHash" to hash(result)))
            }
        } catch (error: Exception) {
            if (error is CancellationException && error.suppressed.none { it is PensionComparisonFailure }) {
                error.addSuppressed(PensionComparisonFailure(activeRound, null, null, stage, emptyMap(), error))
            }
            try { withContext(NonCancellable + Dispatchers.IO) {
                val directory = directory(id)
                val context = (listOf(error) + error.suppressed).filterIsInstance<PensionComparisonFailure>().map {
                    mapOf("round" to it.round, "model" to it.model, "seed" to it.seed?.toString(), "stage" to it.stage, "diagnostics" to it.diagnostics)
                }
                val failure = mapOf("event" to if (error is CancellationException) "CANCELLED" else "FAILED", "stage" to stage, "targetRound" to activeRound,
                    "registrationHash" to registrationReference,
                    "at" to now().toString(), "error" to LottoDesignRunStore.exceptionJson(error), "generationContexts" to context,
                    "successfulModelBatches" to completedModels,
                    "failedModelBatches" to if (error !is CancellationException && context.any { it["stage"] == "generate" }) 1 else 0,
                    "generationFailureRate" to if (error !is CancellationException && context.any { it["stage"] == "generate" }) 1.0 / (completedModels + 1) else null,
                    "failureRateScope" to "attempted-model-batches-only; unfinished planned batches are not successes")
                if (journal != null) requireNotNull(journal).append(failure)
                val failures = File(directory, "failures").apply { if (!exists()) { require(mkdir()); LottoDesignRunStore.syncDirectory(directory) } }
                write(File(failures, "${now().toEpochMilli()}-${UUID.randomUUID()}.json"), sealed(failure))
            } } catch (recordError: Exception) { if (recordError !== error) error.addSuppressed(recordError) }
            throw error
        }
    }

    suspend fun approve(id: String, input: PensionExperimentInput, reason: String) = locked {
        require(reason.isNotBlank()) { "승인 이유가 필요합니다." }
        val recovered = recover(directory(id)); val spec = requireNotNull(recovered.specification)
        require(recovered.judgment.decision == LottoExperimentDecision.ADOPTION_CANDIDATE) { recovered.judgment.reason }
        require(directories().map(::recover).none { it.exclusionUncertain }) { "과거 제외 회차의 완전성을 확인할 수 없습니다." }
        require(!now().isBefore(deadline(rounds(spec).last()))) { "최종 구간이 종료되지 않았습니다." }
        require(readSetting().count == 3) { "운영 기준과 평가 기준이 다릅니다." }
        requireUnchangedPrefix(spec, input.draws)
        rounds(spec).forEach { target -> validateLiveInput(readInput(directory(id), spec, target), input.draws, target) }
        val result = read(File(directory(id), "result.json"))
        recovered.rounds.forEach { round -> val current = input.draws.singleOrNull { it.roundNo == int(round.getValue("targetRound")) }
            require(current != null && round["targetData"] == PensionExperimentComparison.input(listOf(current), emptySet())) { "당첨 원본이 정정됐습니다. 기존 결과를 덮어쓰지 않습니다." }
        }
        appendSetting(4, "APPLY", reason, mapOf("experimentId" to id, "registrationHash" to hash(spec), "resultHash" to hash(result), "sourceHash" to spec["sourceHash"], "decision" to recovered.judgment.decision.name))
    }

    suspend fun rollback(reason: String) = locked {
        require(reason.isNotBlank()) { "복구 이유가 필요합니다." }
        val previous = operations().lastOrNull() ?: error("복구할 이전 승인 설정이 없습니다.")
        val count = int(previous.getValue("previousCount"))
        require(previous["previousConfigHash"] == hash(PensionExperimentComparison.configuration(count))) { "이전 설정을 현재 구현으로 재현할 수 없습니다." }
        appendSetting(count, "ROLLBACK", reason, mapOf("restoredFrom" to previous.getValue("recordHash")))
    }

    private fun recover(directory: File): PensionExperimentSummary {
        var spec: Map<String, Any?>? = null; var uncertain = false
        val issues = mutableListOf<String>(); val events = mutableListOf<Map<String, Any?>>()
        var state = "REGISTERED"; val used = mutableSetOf<Int>(); var prefixValid = true
        try { spec = registration(directory) } catch (error: Exception) { issues += error.message.orEmpty(); uncertain = true; state = "INVALID" }
        val registrationHash = spec?.let(::hash)
        val file = File(directory, "events.jsonl")
        var sequence = 0; var previous = "0".repeat(64)
        if (file.exists()) try {
            val complete = RandomAccessFile(file, "r").use { reader -> if (reader.length() == 0L) true else { reader.seek(reader.length() - 1); reader.read() == 10 } }
            file.bufferedReader(Charsets.UTF_8).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                val next = reader.readLine()
                try {
                    val event = LottoDesignRecordJson.decode(line.toByteArray(Charsets.UTF_8))
                    if (event["registrationHash"] != registrationHash) { uncertain = true; error("계획 참조 해시 불일치") }
                    require(next != null || complete) { "JSONL 마지막 행이 잘렸습니다." }
                    require(event["formatVersion"] == LottoDesignRecordJson.VERSION) { "JSONL 형식 버전 오류" }
                    when (event["event"]) {
                        "MODEL" -> obj(event.getValue("modelResult"))
                        "ROUND" -> obj(event.getValue("roundResult"))
                        "START", "SUCCESS", "FAILED", "CANCELLED" -> Unit
                        else -> error("JSONL 이벤트 형식 오류")
                    }
                    require(prefixValid && int(event.getValue("sequence")) == sequence + 1 && event["previousHash"] == previous && event["recordHash"] == hash(event - "recordHash")) { "JSONL 순번·해시 연결 오류" }
                    Instant.parse(text(event, "recordedAt")); sequence++; previous = text(event, "recordHash"); events += event
                } catch (error: Exception) { prefixValid = false; issues += error.message.orEmpty() }
                line = next
            }
            }
        } catch (error: Exception) { issues += error.message.orEmpty(); prefixValid = false }
        val results = events.filter { it["event"] == "ROUND" }.map { obj(it.getValue("roundResult")) }
        if (File(directory, "execution.json").exists()) {
            state = "INCOMPLETE"
            try {
                val startFile = File(directory, "execution.json")
                val readableStart = read(startFile); used += ints(readableStart.getValue("excludedRounds"))
                val start = readReferenced(startFile, registrationHash)
                if (start["registrationHash"] != registrationHash) { uncertain = true; error("시작 기록 계획 참조 불일치") }
                require(events.firstOrNull()?.get("event") == "START") { "계산 시작 기록 부재" }
                state = when (events.lastOrNull()?.get("event")) { "FAILED" -> "FAILED"; "CANCELLED" -> "CANCELLED"; "SUCCESS" -> "SUCCESS"; else -> "INCOMPLETE" }
                if (state == "SUCCESS") {
                    val result = readReferenced(File(directory, "result.json"), registrationHash)
                    require(result["registrationHash"] == registrationHash && events.last()["resultHash"] == hash(result) && result["rounds"] == results) { "전체 결과 참조 불일치" }
                    require(results.map { int(it.getValue("targetRound")) } == rounds(requireNotNull(spec))) { "부분·누락·중복 회차 결과" }
                    require(start["targets"] == results.map { it.getValue("targetData") }) { "실행 당시 당첨 원본 참조 오류" }
                    require(events.count { it["event"] in listOf("SUCCESS", "FAILED", "CANCELLED") } == 1 && events.count { it["event"] == "START" } == 1) { "종료·시작 이벤트 중복" }
                    require(!Instant.parse(text(result, "finishedAt")).isBefore(Instant.parse(text(start, "startedAt")))) { "종료 시각 역전" }
                    val plan = requireNotNull(spec)
                    require(!Instant.parse(text(start, "startedAt")).isBefore(Instant.parse(text(plan, "registeredAt")))) { "등록 전 실행 시각" }
                    require(!Instant.parse(text(start, "startedAt")).isBefore(deadline(rounds(plan).last()))) { "고정 구간 종료 전 실행" }
                    validateResults(directory, plan, results)
                    val expectedModels = results.flatMap { round -> list(round.getValue("seeds")).flatMap { raw ->
                        val seed = obj(raw); val batches = obj(seed.getValue("batches"))
                        listOf("BASELINE", "CANDIDATE", "CONTROL").map { role -> mapOf("round" to round.getValue("targetRound"), "model" to role,
                            "seed" to seed.getValue(if (role == "CONTROL") "controlSeed" else "seed"), "batch" to batches.getValue(role)) }
                    } }
                    require(events.filter { it["event"] == "MODEL" }.map { obj(it.getValue("modelResult")) } == expectedModels) { "모델 완료 기록 누락·순서·참조 오류" }
                    events.filter { it["event"] == "ROUND" }.forEach { event ->
                        require(event["inputProofHash"] == hash(readInput(directory, plan, int(obj(event.getValue("roundResult")).getValue("targetRound"))))) { "입력 증빙 참조 오류" }
                    }
                    require(int(result.getValue("successfulModelBatches")) == rounds(plan).size * seeds(plan, "commonSeeds").size * 2 && int(result.getValue("failedModelBatches")) == 0 && LottoDesignRecordJson.decimal(result.getValue("generationFailureRate")) == 0.0) { "전체 생성 실패 집계 오류" }
                }
            } catch (error: Exception) {
                if (error is PlanReferenceFailure || !File(directory, "execution.json").canRead()) uncertain = true
                // 시작 기록 자체를 읽지 못하면 과거 실행의 계획 범위를 확정할 수 없다.
                if (runCatching { read(File(directory, "execution.json"))["registrationHash"] }.getOrNull() == null) uncertain = true
                issues += error.message.orEmpty(); state = "INVALID"
            }
        }
        spec?.let { plan ->
            if (File(directory, "execution.json").exists()) used += int(plan.getValue("historyStartRound"))..rounds(plan).last()
            sourceIssue(directory, plan)?.let { issues += it }
            rounds(plan).filter { File(directory, "inputs/$it.json").exists() }.forEach { target ->
                try { readInput(directory, plan, target) } catch (error: Exception) {
                    if (error is PlanReferenceFailure) uncertain = true
                    issues += "입력 증빙 확인 실패: ${error.message}"
                }
            }
        }
        val failureFolder = File(directory, "failures")
        val failures = if (failureFolder.exists()) requireNotNull(failureFolder.listFiles()).sortedBy { it.name }.mapNotNull { file ->
            try { readReferenced(file, registrationHash) } catch (error: Exception) {
                if (error is PlanReferenceFailure) uncertain = true
                issues += "실패 기록 손상: ${error.message}"; null
            }
        } else emptyList()
        if (failures.isNotEmpty() && !File(directory, "execution.json").exists()) {
            state = "PREPARATION_FAILED"
            spec?.let { used += int(it.getValue("historyStartRound"))..rounds(it).last() }
        }
        if (!prefixValid || issues.isNotEmpty()) state = "INVALID"
        val captured = spec?.let { plan -> rounds(plan).filter { File(directory, "inputs/$it.json").exists() } }.orEmpty()
        val judgment = judge(spec, state, results, issues)
        return PensionExperimentSummary(directory.name, spec, state, issues, results, events, captured, used, uncertain, judgment, failures)
    }

    private fun validateResults(directory: File, spec: Map<String, Any?>, results: List<Map<String, Any?>>) {
        val common = seeds(spec, "commonSeeds"); val control = seeds(spec, "controlSeeds")
        results.forEach { round ->
            val target = int(round.getValue("targetRound")); val proof = readInput(directory, spec, target)
            require(round["inputHash"] == hash(proof.getValue("input"))) { "회차 입력 해시 오류" }
            val targetJson = obj(round.getValue("target")); val winning = PensionLotteryDrawEntity(target, int(targetJson.getValue("groupNo")), text(targetJson, "number"), targetJson["bonus"]?.let(LottoDesignRecordJson::string))
            val targetData = obj(round.getValue("targetData")); val original = PensionExperimentComparison.draws(targetData).single()
            require(original.roundNo == target && original.groupNo == winning.groupNo && original.winningNumber == winning.winningNumber && original.bonusNumber == winning.bonusNumber &&
                PensionExperimentComparison.input(listOf(original), emptySet()) == targetData && round["targetDataHash"] == hash(targetData)) { "당첨 원본 해시·내용 오류" }
            val records = list(round.getValue("seeds")).map(::obj)
            require(records.map { text(it, "seed").toLong() } == common && records.map { text(it, "controlSeed").toLong() } == control) { "실행 시드가 등록과 다릅니다." }
            records.forEach { seed -> listOf("BASELINE", "CANDIDATE", "CONTROL").forEach { role ->
                val batch = obj(obj(seed.getValue("batches")).getValue(role)); val numbers = list(batch.getValue("numbers")).map(::obj)
                require(numbers.map { text(it, "number") }.distinct().size == 4) { "배치 번호 중복" }
                PensionExperimentComparison.evaluate(numbers, winning).forEach { (key, value) -> require(batch[key] == value) { "회차 평가 지표 오류: $key" } }
                if (role == "CONTROL") require(numbers == PensionExperimentComparison.uniform(text(seed, "controlSeed").toLong())) { "대조군 재현 오류" }
                else require(batch["configHash"] == hash(PensionExperimentComparison.configuration(if (role == "BASELINE") 3 else 4))) { "모델 설정 참조 오류" }
                if (role != "CONTROL") {
                    require(numbers.map { text(it, "type") } == listOf("APPEARED", "APPEARED_SECOND", "COLD_MIX", "COLD_MIX_SECOND")) { "출현형 2개·미출현 혼합형 2개 배치 오류" }
                    val frozen = obj(proof.getValue("input"))
                    val excluded = list(frozen.getValue("exclusions")).map(LottoDesignRecordJson::string).toSet() + PensionExperimentComparison.draws(frozen).map { it.winningNumber }
                    require(numbers.none { text(it, "number") in excluded }) { "과거 당첨·동일 추천 제외 위반" }
                    for (index in 0..1) {
                        val appeared = numbers[index]; val cold = numbers[index + 2]
                        require(appeared["groupNo"] != cold["groupNo"] && text(appeared, "number").last() != text(cold, "number").last() &&
                            text(appeared, "number").zip(text(cold, "number")).count { (a, b) -> a != b } >= 3) { "쌍의 조·끝자리·자리 차이 위반" }
                    }
                    val diagnostics = obj(batch.getValue("diagnostics")); val attempts = int(diagnostics.getValue("attempts"))
                    require(attempts in 1..80_000) { "실제 후보 시도 횟수 오류" }
                    val filters = obj(diagnostics.getValue("filters"))
                    require(filters.keys.all { it in listOf("selection", "pastWinner", "recommendationAndBatch", "pairDifference", "pairLastDigit", "duplicateType", "scoreBand", "group") } && "group" in filters) { "필터 진단 항목 오류" }
                    filters.values.forEach { raw -> val filter = obj(raw); val reached = int(filter.getValue("reached")); val passed = int(filter.getValue("passed"))
                        require(reached in 1..attempts && passed in 0..reached && LottoDesignRecordJson.decimal(filter.getValue("conditionalPassRate")) == passed.toDouble() / reached) { "필터 통과율·시도 횟수 오류" }
                    }
                    val accepted = int(obj(filters.getValue("group")).getValue("passed"))
                    require(accepted >= 4 && LottoDesignRecordJson.decimal(diagnostics.getValue("allFiltersPassRate")) == accepted.toDouble() / attempts) { "전체 필터 통과율 오류" }
                }
            } }
            fun rate(role: String) = records.map { LottoDesignRecordJson.decimal(obj(obj(it.getValue("batches")).getValue(role)).getValue("suffix2Rate")) }.average()
            require(round["baselineRate"] == rate("BASELINE") && round["candidateRate"] == rate("CANDIDATE") && round["controlRate"] == rate("CONTROL") &&
                round["candidateBaselineDifference"] == rate("CANDIDATE") - rate("BASELINE") && round["candidateControlDifference"] == rate("CANDIDATE") - rate("CONTROL")) { "회차 평균·짝지은 차이 오류" }
        }
    }

    private fun judge(spec: Map<String, Any?>?, state: String, results: List<Map<String, Any?>>, issues: List<String>): LottoExperimentJudgment {
        val policy = spec?.get("policy")?.let(::obj)
        if (policy == null || policy["confirmedByUser"] != true) return LottoExperimentJudgment(LottoExperimentDecision.POLICY_UNCONFIRMED, "통계 정책 미확정: 결과 조회만 가능하며 채택·기각·적용을 차단합니다.", results.size)
        val plan = requireNotNull(spec)
        if (plan["purpose"] != "FINAL") return LottoExperimentJudgment(LottoExperimentDecision.DESIGN_ONLY, "설계 결과는 최종 채택 근거가 아닙니다.", results.size)
        if (state != "SUCCESS" || issues.isNotEmpty() || results.size != rounds(plan).size) return LottoExperimentJudgment(LottoExperimentDecision.HOLD, "성공한 전체 고정 구간과 무결성이 필요합니다.", results.size)
        val family = int(policy.getValue("familyComparisonCount")); val b = results.map { LottoDesignRecordJson.decimal(it.getValue("candidateBaselineDifference")) }; val c = results.map { LottoDesignRecordJson.decimal(it.getValue("candidateControlDifference")) }
        val b95 = LottoExperimentPolicy.interval(b, 0.05); val c95 = LottoExperimentPolicy.interval(c, 0.05)
        val adjustedB = LottoExperimentPolicy.interval(b, 0.05 / family); val adjustedC = LottoExperimentPolicy.interval(c, 0.05 / family)
        val accepted = adjustedB.estimate >= LottoDesignRecordJson.decimal(policy.getValue("minimumEffect")) && adjustedB.lower > 0 && adjustedC.lower > 0
        val rejected = policy["rejectWhenUpperNonPositive"] == true && (adjustedB.upper <= 0 || adjustedC.upper <= 0)
        return LottoExperimentJudgment(if (accepted) LottoExperimentDecision.ADOPTION_CANDIDATE else if (rejected) LottoExperimentDecision.REJECT else LottoExperimentDecision.HOLD,
            if (accepted) "사전 기준의 개선 추정치와 두 보정 하한을 충족했습니다. 실제 개선 폭 보장은 아니며 사용자 승인 후 적용합니다." else "사전 채택 기준 미충족: 운영 설정을 유지합니다.", results.size, b95, c95, adjustedB, adjustedC)
    }

    private fun snapshotInput(draws: List<PensionLotteryDrawEntity>, rows: List<PensionLotteryGeneratedNumberEntity>): Map<String, Any?> {
        require(draws.all { !now().isBefore(deadline(it.roundNo)) && it.collectedAt?.isAfter(now().atZone(SEOUL).toLocalDateTime()) != true }) { "현재 시점 이후의 추첨·수집 데이터가 입력에 있습니다." }
        val selected = rows.filterNot { it.isControl }.sortedBy { it.id }
        require(selected.all { it.savedAt?.isAfter(now().atZone(SEOUL).toLocalDateTime()) != true }) { "저장 추천의 시각이 미래입니다." }
        return mapOf("input" to PensionExperimentComparison.input(draws, selected.map { it.winningNumber }.toSet()),
            "recommendationEvidence" to selected.map { mapOf("id" to it.id, "generationId" to it.generationId, "number" to it.winningNumber,
                "groupNo" to it.groupNo, "savedAt" to it.savedAt?.toString(), "isHidden" to it.isHidden, "targetRound" to it.targetRoundNo) })
    }
    private fun storeInput(folder: File, spec: Map<String, Any?>, target: Int, input: Map<String, Any?>, kind: String) {
        val history = PensionExperimentComparison.draws(obj(input.getValue("input")))
        require(history.size >= 17 && history.first().roundNo == target - 1 && history.last().roundNo == int(spec.getValue("historyStartRound"))) { "분석 입력 기간·직전 회차 오류" }
        if (kind == "PRE_DRAW") require(now().isBefore(deadline(target))) { "입력 기록 전에 추첨 전 마감이 지났습니다." }
        write(File(folder, "$target.json"), sealed(input + mapOf("targetRound" to target, "kind" to kind, "capturedAt" to now().toString(), "registrationHash" to hash(spec), "sourceHash" to spec["sourceHash"])))
    }
    private fun readInput(directory: File, spec: Map<String, Any?>, target: Int): Map<String, Any?> {
        val proof = readReferenced(File(directory, "inputs/$target.json"), hash(spec))
        require(proof["registrationHash"] == hash(spec) && proof["sourceHash"] == spec["sourceHash"] && int(proof.getValue("targetRound")) == target) { "$target 회차 입력 참조 오류" }
        require(proof["kind"] == "PRE_DRAW" && Instant.parse(text(proof, "capturedAt")).isBefore(deadline(target)) && !Instant.parse(text(proof, "capturedAt")).isBefore(Instant.parse(text(spec, "registeredAt")))) { "추첨 전 입력 확정을 확인할 수 없습니다: $target" }
        val input = obj(proof.getValue("input")); val draws = PensionExperimentComparison.draws(input)
        val excluded = list(input.getValue("exclusions")).map(LottoDesignRecordJson::string).toSet()
        require(PensionExperimentComparison.input(draws, excluded) == input && draws.size >= 17 && draws.first().roundNo == target - 1 && draws.last().roundNo == int(spec.getValue("historyStartRound"))) { "입력 형식·기간 오류" }
        require(!Instant.parse(text(proof, "capturedAt")).isBefore(deadline(draws.first().roundNo))) { "직전 회차 추첨 전에 입력을 확정했습니다." }
        val evidence = list(proof.getValue("recommendationEvidence")).map(::obj)
        require(evidence.map { text(it, "number") }.toSet() == excluded && evidence.map { it["id"] }.distinct().size == evidence.size) { "추천 제외 목록과 증빙이 다릅니다." }
        val captured = Instant.parse(text(proof, "capturedAt")).atZone(SEOUL).toLocalDateTime()
        require(evidence.all { row -> row["savedAt"]?.let { !java.time.LocalDateTime.parse(LottoDesignRecordJson.string(it)).isAfter(captured) } != false }) { "입력 확정 후 저장한 추천이 제외 목록에 섞였습니다." }
        return proof
    }
    private fun validateLiveInput(proof: Map<String, Any?>, draws: List<PensionLotteryDrawEntity>, target: Int) {
        val input = obj(proof.getValue("input")); val history = PensionExperimentComparison.draws(input)
        val excluded = list(input.getValue("exclusions")).map(LottoDesignRecordJson::string).toSet()
        val current = draws.filter { it.roundNo in history.last().roundNo until target }
        require(PensionExperimentComparison.input(current, excluded) == input) { "사전 입력에 누락·중복·원본 정정이 있습니다. 최신 자료로 대체하지 않습니다: $target" }
    }
    private fun requireUnchangedPrefix(spec: Map<String, Any?>, draws: List<PensionLotteryDrawEntity>) {
        val initial = obj(obj(spec.getValue("initialInput")).getValue("input")); val history = PensionExperimentComparison.draws(initial)
        val current = draws.filter { it.roundNo in history.last().roundNo..history.first().roundNo }
        require(PensionExperimentComparison.input(current, list(initial.getValue("exclusions")).map(LottoDesignRecordJson::string).toSet()) == initial) { "등록 원본이 정정됐습니다. 새 실험이 필요합니다." }
    }
    private fun validateSpec(spec: Map<String, Any?>) {
        require(spec["purpose"] in listOf("DESIGN", "FINAL")) { "실험 용도 오류" }
        val targets = rounds(spec); val start = int(spec.getValue("historyStartRound"))
        require(start > 0 && targets.isNotEmpty() && targets == targets.distinct().sorted() && targets.all { it - start >= 17 }) { "평가 대상·입력 시작·분석 기간 오류" }
        require(text(spec, "hypothesis").isNotBlank() && text(spec, "comparisonFamilyId").isNotBlank()) { "가설·비교군 식별자가 필요합니다." }
        val selection = ints(spec.getValue("selectionRounds")); require(selection == selection.distinct().sorted() && selection.all { it in 1 until targets.first() }) { "설정 선택 구간 오류" }
        val common = seeds(spec, "commonSeeds"); val control = seeds(spec, "controlSeeds")
        require(common.isNotEmpty() && common.distinct().size == common.size && control.size == common.size && control.distinct().size == control.size && common.intersect(control.toSet()).isEmpty()) { "공통·대조군 시드는 같은 개수·고유·별도 목록이어야 합니다." }
        spec["policy"]?.let(::obj)?.takeIf { it["confirmedByUser"] == true }?.let { policy ->
            require(policy["primaryMetric"] == "normal-suffix-two-plus-proportion") { "연금 주 지표는 일반 당첨번호 끝 2자리 이상 연속 일치 비율입니다." }
            LottoExperimentPolicy.validate(policy + ("primaryMetric" to "main-three-plus-game-proportion"), targets.size)
            require(!Instant.parse(text(policy, "confirmedAt")).isAfter(now())) { "정책 확정 시각이 미래입니다." }
        }
    }
    private fun registration(directory: File): Map<String, Any?> = readSealed(File(directory, "registration.json")).also {
        validateSpec(it); require(it["id"] == directory.name && it["implementationId"] == PensionExperimentComparison.IMPLEMENTATION && it["initialInputHash"] == hash(it.getValue("initialInput")) &&
            it["baselineConfig"] == PensionExperimentComparison.configuration(3) && it["candidateConfig"] == PensionExperimentComparison.configuration(4)) { "등록 입력·설정·구현 오류" }
        require(Instant.parse(text(it, "registeredAt")).isBefore(deadline(rounds(it).first()))) { "계획의 추첨 전 등록 오류" }
        it["policy"]?.let(::obj)?.takeIf { p -> p["confirmedByUser"] == true }?.let { p -> require(!Instant.parse(text(p, "confirmedAt")).isAfter(Instant.parse(text(it, "registeredAt")))) { "등록 이후 확정한 정책입니다." } }
    }
    private fun validateSource(source: Map<String, Any?>) {
        require(source["apkHash"] == apk && obj(source.getValue("splitApkHashes")) == splits() && text(source, "buildReference").isNotBlank() && source["hasUncommittedChanges"] is Boolean) { "실행 APK·빌드 증빙이 다릅니다." }
        val files = list(source.getValue("files")).map(::obj); val paths = files.map { text(it, "path") }
        require(paths == paths.distinct().sorted() && paths.containsAll(SOURCE_FILES) && paths.all { it in SOURCE_FILES }) { "연금 계산·기록·화면·설정의 소스 원문 목록 오류" }
        files.forEach { require(it["sha256"] == hash(text(it, "content").toByteArray(Charsets.UTF_8))) { "소스 원문 해시 불일치" } }
        require(source["buildSourceHash"] == hash(files.map { it - "content" })) { "소스 목록 해시 오류" }
        val dependencies = obj(source.getValue("dependencyVersions")); require(listOf("kotlin", "kotlinStdlib", "coroutines").all { text(dependencies, it).isNotBlank() } && dependencies["kotlinStdlib"] == KotlinVersion.CURRENT.toString()) { "의존성·Kotlin 런타임 증빙 오류" }
    }
    private fun sourceIssue(directory: File, spec: Map<String, Any?>): String? = try {
        val source = readSealed(File(directory, "source-manifest.json")); require(spec["sourceHash"] == hash(source - "recordHash") && source["apkHash"] == apk && obj(source.getValue("splitApkHashes")) == splits()) { "등록 이후 소스·앱 산출물이 변경됐습니다." }
        val entries = list(source.getValue("files")).map(::obj)
        require(entries.map { text(it, "path") } == SOURCE_FILES) { "소스 경로 목록 손상" }
        entries.forEach { val file = File(directory, "source/${text(it, "path")}"); require(file.canonicalFile == file.absoluteFile && it["sha256"] == hash(file.readBytes())) { "소스 원문 손상" } }
        null
    } catch (error: Exception) { "소스 증빙 확인 실패: ${error.message}" }
    private fun readSetting(): PensionOperatingSetting = operations().lastOrNull()?.let { PensionOperatingSetting(int(it.getValue("count")), text(it, "recordHash")) } ?: PensionOperatingSetting(3)
    private fun operations(): List<Map<String, Any?>> {
        if (!operationRoot.exists()) return emptyList()
        val files = requireNotNull(operationRoot.listFiles()).sortedBy { it.name }
        var previous = "0".repeat(64); var previousCount = 3
        return files.mapIndexed { index, file ->
            require(file.isFile && file.name == String.format(java.util.Locale.ROOT, "%08d.json", index + 1)) { "승인·복구 이력 누락/미완료" }
            val operation = readSealed(file); val count = int(operation.getValue("count"))
            require(operation["previousHash"] == previous && int(operation.getValue("sequence")) == index + 1 && int(operation.getValue("previousCount")) == previousCount && count in 3..4 &&
                operation["configHash"] == hash(PensionExperimentComparison.configuration(count))) { "운영 설정 해시 연결·구현 오류" }
            require(operation["config"] == PensionExperimentComparison.configuration(count) && operation["previousConfig"] == PensionExperimentComparison.configuration(previousCount) &&
                operation["previousConfigHash"] == hash(PensionExperimentComparison.configuration(previousCount)) && operation["action"] in listOf("APPLY", "ROLLBACK") && text(operation, "reason").isNotBlank()) { "승인·복구 설정 원문·근거 오류" }
            Instant.parse(text(operation, "approvedAt"))
            previous = text(operation, "recordHash"); previousCount = count; operation
        }
    }
    private fun appendSetting(count: Int, action: String, reason: String, proof: Map<String, Any?>) {
        val log = operations(); val current = readSetting()
        if (!operationRoot.exists()) { require(operationRoot.mkdir()); LottoDesignRunStore.syncDirectory(root) }
        val operation = mapOf("sequence" to log.size + 1, "count" to count, "previousCount" to current.count,
            "config" to PensionExperimentComparison.configuration(count), "configHash" to hash(PensionExperimentComparison.configuration(count)),
            "previousConfig" to PensionExperimentComparison.configuration(current.count), "previousConfigHash" to hash(PensionExperimentComparison.configuration(current.count)),
            "action" to action, "reason" to reason, "approvedAt" to now().toString(), "proof" to proof, "previousHash" to current.approvalHash.orEmpty().ifEmpty { "0".repeat(64) })
        write(File(operationRoot, String.format(java.util.Locale.ROOT, "%08d.json", log.size + 1)), sealed(operation))
    }
    private inner class Journal(directory: File, private val planHash: String) {
        private val file = File(directory, "events.jsonl")
        private var sequence = 0; private var previous = "0".repeat(64)
        init { require(file.createNewFile()); LottoDesignRunStore.syncDirectory(directory) }
        fun append(value: Map<String, Any?>) {
            val record = sealed(value + mapOf("sequence" to sequence + 1, "previousHash" to previous, "registrationHash" to planHash, "recordedAt" to now().toString()))
            FileOutputStream(file, true).use { it.write((LottoDesignRecordJson.encode(record) + "\n").toByteArray(Charsets.UTF_8)); it.flush(); it.fd.sync() }
            sequence++; previous = text(record, "recordHash")
        }
    }
    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) { gate.withLock {
        if (!root.exists()) { require(root.mkdir()); LottoDesignRunStore.syncDirectory(root.parentFile!!) }
        require(root.canonicalFile == root.absoluteFile)
        RandomAccessFile(File(root, ".lock"), "rw").channel.use { channel -> channel.lock().use { block() } }
    } }
    private fun directories(): List<File> = requireNotNull(root.listFiles()).filter { it.name !in listOf(".lock", "operating-settings") }.sortedBy { it.name }.onEach { require(it.isDirectory && it.name.matches(ID) && it.canonicalFile == it.absoluteFile) { "연금 실험 경로 오류" } }
    private fun directory(id: String): File { require(id.matches(ID)); return File(root, id).also { require(it.isDirectory && it.canonicalFile == it.absoluteFile) } }
    private fun read(file: File): Map<String, Any?> {
        require(file.canonicalFile == file.absoluteFile && file.isFile)
        return LottoDesignRecordJson.decode(file.readBytes()).also { require(it["formatVersion"] == LottoDesignRecordJson.VERSION) { "기록 형식 버전 오류: ${file.name}" } }
    }
    private fun readSealed(file: File) = read(file).also { require(it["recordHash"] == hash(it - "recordHash")) { "기록 해시 오류: ${file.name}" } }
    private fun readReferenced(file: File, expected: String?): Map<String, Any?> {
        val value = try { read(file) } catch (error: Exception) { throw PlanReferenceFailure("계획 참조를 읽을 수 없습니다: ${file.name}", error) }
        if (value["registrationHash"] != expected) throw PlanReferenceFailure("계획 참조 해시 불일치: ${file.name}")
        require(value["recordHash"] == hash(value - "recordHash")) { "기록 해시 오류: ${file.name}" }
        return value
    }
    private class PlanReferenceFailure(message: String, cause: Exception? = null) : IllegalArgumentException(message, cause)
    private fun sealed(value: Map<String, Any?>): Map<String, Any?> {
        val versioned = value + ("formatVersion" to LottoDesignRecordJson.VERSION)
        return versioned + ("recordHash" to hash(versioned))
    }
    private fun write(file: File, value: Map<String, Any?>) = LottoDesignRunStore.writeImmutable(file, LottoDesignRecordJson.encode(value).toByteArray(Charsets.UTF_8))
    private fun hash(value: Any?) = LottoDesignRecordJson.hash(value)
    private fun hash(value: ByteArray) = LottoDesignRecordJson.hash(value)
    private fun obj(value: Any?) = LottoDesignRecordJson.objectValue(value)
    private fun list(value: Any?) = LottoDesignRecordJson.list(value)
    private fun int(value: Any?) = LottoDesignRecordJson.integer(value)
    private fun ints(value: Any?) = list(value).map(::int)
    private fun text(value: Map<String, Any?>, key: String) = LottoDesignRecordJson.string(value.getValue(key))
    private fun rounds(spec: Map<String, Any?>) = ints(spec.getValue("targetRounds"))
    private fun seeds(spec: Map<String, Any?>, key: String) = list(spec.getValue(key)).map { LottoDesignRecordJson.string(it).let { value -> require(value.toLong().toString() == value); value.toLong() } }
    private fun deadlineLocal(round: Int) = lotteryEvaluationDeadline(LotteryProduct.PENSION_720, round)
    private fun deadline(round: Int) = deadlineLocal(round).atZone(SEOUL).toInstant()
    companion object {
        private val gate = Mutex()
        private val SEOUL = ZoneId.of("Asia/Seoul")
        private val ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(java.time.ZoneOffset.UTC)
        private val ID = Regex("pension-[0-9]{8}T[0-9]{9}Z-[0-9a-f-]{36}")
        val SOURCE_FILES = listOf("app/src/main/java/com/habittracker/ui/lotto/PensionLotteryGeneratorViewModel.kt", "app/src/main/java/com/habittracker/ui/lotto/PensionLotteryGeneratorScreen.kt",
            "app/src/main/java/com/habittracker/ui/lotto/PensionExperimentSection.kt", "app/src/main/java/com/habittracker/data/lotto/PensionExperimentStore.kt",
            "app/src/main/java/com/habittracker/data/lotto/PensionExperimentComparison.kt", "app/src/main/java/com/habittracker/data/lotto/LottoExperimentPolicy.kt",
            "app/src/main/java/com/habittracker/data/lotto/LottoDesignRecordJson.kt", "app/src/main/java/com/habittracker/data/lotto/LottoDesignRunStore.kt",
            "app/src/main/java/com/habittracker/data/lotto/LotteryDrawSyncModels.kt", "app/src/main/java/com/habittracker/data/repository/HabitRepository.kt",
            "app/build.gradle.kts", "build.gradle.kts", "gradle/libs.versions.toml").sorted()
    }
}
