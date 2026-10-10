package com.habittracker.data.lotto

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.IdentityHashMap
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class LottoDesignSourceFile(val relativePath: String, val sha256: String)

/** 승인된 빌드에서 확보한 증빙이다. Git HEAD만으로 소스 대응을 대신할 수 없다. */
data class LottoDesignSourceEvidence(
    val directory: File,
    val files: List<LottoDesignSourceFile>,
    val buildSourceHash: String,
    val apkHash: String,
    val splitApkHashes: Map<String, String>,
    val dependencyVersions: Map<String, String>,
    val buildReference: String,
    val gitHead: String?,
    val hasUncommittedChanges: Boolean,
)

enum class LottoDesignRunState { SUCCESS, FAILED, CANCELLED, INCOMPLETE, INVALID }
enum class LottoDesignPlanReferenceState { MATCHED, CONFLICT, UNVERIFIED }

data class LottoDesignRecoveredRun(
    val runId: String,
    val state: LottoDesignRunState,
    val planJson: String?,
    val diagnosticRounds: List<LottoPriorDesignRoundResult>,
    val events: List<Map<String, Any?>>,
    val excludedRounds: Set<Int>,
    val planReferenceState: LottoDesignPlanReferenceState,
    val issues: List<String>,
) {
    val planInternallyValid: Boolean get() = planJson != null
    val exclusionCoverageUncertain: Boolean get() = !planInternallyValid || planReferenceState != LottoDesignPlanReferenceState.MATCHED
    val baselineRate: Double? get() = if (state == LottoDesignRunState.SUCCESS) diagnosticRounds.map { it.baselineRate }.average() else null
    val candidateRate: Double? get() = if (state == LottoDesignRunState.SUCCESS) diagnosticRounds.map { it.candidateRate }.average() else null
    val pairedRateDifference: Double? get() = if (state == LottoDesignRunState.SUCCESS) diagnosticRounds.map { it.pairedRateDifference }.average() else null
}

data class LottoDesignRecovery(val runs: List<LottoDesignRecoveredRun>, val issues: List<String>) {
    val excludedRounds: Set<Int> get() = runs.flatMap { it.excludedRounds }.toSet()
    // 개별 파일의 연결이 유효해도 삭제된 실행 디렉터리의 존재를 알아낼 수는 없다.
    val historyPresenceGuaranteed: Boolean get() = false
    val hasUncertainExclusionCoverage: Boolean get() = runs.any { it.exclusionCoverageUncertain } || issues.isNotEmpty()
    val hasUnrecoverablePlans: Boolean get() = hasUncertainExclusionCoverage
}

/** 자동 실행·이어 실행 없이 승인된 설계 실행을 기록하고 기존 파일을 읽는다. */
class LottoDesignRunStore(context: Context) {
    private val appContext = context.applicationContext
    private val filesDirectory by lazy { appContext.filesDir.canonicalFile }
    private val root by lazy { File(filesDirectory, "lotto-design-runs") }

    fun newRunId(): String = "design-${runTimeFormat.format(Instant.now())}-${UUID.randomUUID()}"

    // 이 진입점 호출 자체가 평가 실행이다. 구현 승인은 호출 승인이 아니다.
    suspend fun compareAndRecord(
        runId: String,
        plan: LottoPriorDesignPlan,
        source: LottoDesignSourceEvidence,
    ): LottoPriorDesignResult {
        val frozenPlan = LottoPriorDesignComparison.prepare(
            plan.draws, plan.historyStartRound, plan.targetRounds, plan.commonSeeds, plan.controlSeeds, plan.experiment, plan.inputProofs,
        )
        require(frozenPlan.datasetHash == plan.datasetHash && frozenPlan.baseline == plan.baseline &&
            frozenPlan.candidate == plan.candidate) { "기록할 설계 계획이 현재 계산 설정과 다릅니다." }
        val frozenSource = source.copy(files = source.files.toList(), splitApkHashes = source.splitApkHashes.toMap(),
            dependencyVersions = source.dependencyVersions.toMap())
        var session: Session? = null
        var stage = "prepare_records"
        try {
            withContext(Dispatchers.IO) {
                val reserved = reserve(runId, frozenPlan, frozenSource)
                session = reserved
                reserved.prepare(frozenSource)
                ensureActive()
                reserved.events.append(mapOf("event" to "START", "stage" to "start"))
            }
            val active = requireNotNull(session)
            stage = "compare"
            val result = LottoPriorDesignComparison.compare(frozenPlan) { round ->
                withContext(Dispatchers.IO) {
                    ensureActive()
                    active.rounds.append(mapOf("round" to roundJson(round)))
                    active.completedRounds += round.targetRoundNo
                }
            }
            stage = "success_record"
            withContext(Dispatchers.IO) {
                ensureActive()
                require(active.completedRounds == frozenPlan.targetRounds) { "완료 회차가 계획과 다릅니다." }
                active.events.append(mapOf(
                    "event" to "SUCCESS", "stage" to stage,
                    "roundCount" to active.completedRounds.size, "lastRoundHash" to active.rounds.lastHash,
                    "baselineRate" to result.baselineRate, "candidateRate" to result.candidateRate,
                    "pairedRateDifference" to result.pairedRateDifference,
                ))
            }
            return result
        } catch (error: Exception) {
            val failedSession = session ?: throw error
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    failedSession.events.append(mapOf(
                        "event" to if (error is CancellationException) "CANCELLED" else "FAILURE",
                        "stage" to stage, "targetRoundNo" to (error as? LottoPriorDesignFailure)?.targetRoundNo,
                        "lastRecordedRoundNo" to failedSession.completedRounds.lastOrNull(), "error" to exceptionJson(error),
                    ))
                }
            } catch (recordError: Exception) {
                if (recordError !== error) error.addSuppressed(recordError)
            }
            throw error
        }
    }

    suspend fun recover(): LottoDesignRecovery = withContext(Dispatchers.IO) {
        if (!root.exists()) return@withContext LottoDesignRecovery(emptyList(), listOf(
            "실행 기록 폴더가 없습니다. 데이터 삭제·재설치·이력 유실 가능성이 있어 과거 회차를 미사용으로 판정할 수 없습니다.",
        ))
        checkedRoot()
        val children = requireNotNull(root.listFiles()) { "설계 실행 기록 목록을 읽을 수 없습니다." }
        val issues = mutableListOf<String>()
        val runs = children.sortedBy { it.name }.mapNotNull { directory ->
            ensureActive()
            if (!directory.isDirectory || !runIdPattern.matches(directory.name) || directory.canonicalFile != directory.absoluteFile) {
                issues += "확인할 수 없는 실행 경로: ${directory.name}"
                null
            } else recoverRun(directory)
        }
        if (children.isEmpty()) issues += "보존된 실행이 없습니다. 과거 회차 미사용의 증거가 아닙니다."
        LottoDesignRecovery(runs, issues)
    }

    private fun reserve(id: String, plan: LottoPriorDesignPlan, source: LottoDesignSourceEvidence): Session {
        require(runIdPattern.matches(id)) { "설계 실행 ID 형식이 올바르지 않습니다: $id" }
        if (!root.exists()) {
            require(root.mkdir()) { "설계 실행 저장 폴더를 생성할 수 없습니다." }
            syncDirectory(filesDirectory)
        }
        checkedRoot()
        val directory = File(root, id)
        require(directory.mkdir()) { "설계 실행 ID가 이미 있거나 디렉터리 생성에 실패했습니다: $id" }
        syncDirectory(root)
        val planDocument = envelope(id, "PLAN") + planJson(plan)
        val sourceDocument = envelope(id, "SOURCE") + sourceJson(source)
        return Session(directory, planDocument, sourceDocument)
    }

    private fun checkedRoot() {
        require(root.isDirectory && root.canonicalFile == root.absoluteFile) { "설계 기록 저장 경로가 유효하지 않습니다." }
    }

    private inner class Session(
        val directory: File,
        val plan: Map<String, Any?>,
        val source: Map<String, Any?>,
    ) {
        val events = Journal(File(directory, "events.jsonl"), "EVENT", plan, source)
        val rounds = Journal(File(directory, "rounds.jsonl"), "ROUND", plan, source)
        val completedRounds = mutableListOf<Int>()

        fun prepare(evidence: LottoDesignSourceEvidence) {
            writeImmutable(File(directory, "plan.json"), jsonBytes(plan))
            writeImmutable(File(directory, "source-manifest.json"), jsonBytes(source))
            validateSourceManifest(source)
            require(fileHash(File(appContext.applicationInfo.sourceDir)) == evidence.apkHash) { "소스 증빙의 APK 해시가 실행 앱과 다릅니다." }
            val actualSplits = appContext.applicationInfo.splitSourceDirs.orEmpty().associate { path ->
                File(path).name to fileHash(File(path))
            }
            require(actualSplits == evidence.splitApkHashes) { "소스 증빙의 split APK 해시가 실행 앱과 다릅니다." }
            require(evidence.dependencyVersions.getValue("kotlinStdlib") == KotlinVersion.CURRENT.toString()) {
                "소스 증빙의 Kotlin 표준 라이브러리 버전이 실행 환경과 다릅니다."
            }
            val sourceRoot = File(directory, "source")
            require(sourceRoot.mkdir()) { "소스 보존 디렉터리 생성에 실패했습니다." }
            syncDirectory(directory)
            for (entry in evidence.files.sortedBy { it.relativePath }) {
                val input = safeChild(evidence.directory.canonicalFile, entry.relativePath)
                require(input.isFile && fileHash(input) == entry.sha256) { "소스 증빙 파일의 해시가 다릅니다: ${entry.relativePath}" }
                val target = safeChild(sourceRoot, entry.relativePath)
                createParents(sourceRoot, target.parentFile!!)
                writeImmutable(target, input.readBytes())
                require(fileHash(target) == entry.sha256) { "보존한 소스의 해시가 다릅니다: ${entry.relativePath}" }
            }
            require(LottoDesignRecordJson.hash(File(directory, "plan.json").readBytes()) == events.planHash &&
                LottoDesignRecordJson.hash(File(directory, "source-manifest.json").readBytes()) == events.sourceHash) {
                "고정 기록 저장 후 해시가 다릅니다."
            }
            rounds.create()
        }
    }

    private class Journal(
        private val file: File,
        private val kind: String,
        private val plan: Map<String, Any?>,
        source: Map<String, Any?>,
    ) {
        val planHash = LottoDesignRecordJson.hash(plan)
        val sourceHash = LottoDesignRecordJson.hash(source)
        var lastHash: String? = null
            private set
        private var sequence = 0
        private var created = false

        fun create() {
            if (created) return
            require(file.createNewFile()) { "기록 파일이 이미 존재합니다: ${file.name}" }
            FileOutputStream(file).use { it.fd.sync() }
            syncDirectory(file.parentFile!!)
            created = true
        }

        fun append(payload: Map<String, Any?>) {
            create()
            val record = envelope(string(plan.getValue("runId")), kind) + payload + mapOf(
                "sequence" to sequence + 1, "previousRecordHash" to lastHash,
                "planHash" to planHash, "sourceManifestHash" to sourceHash, "recordedAt" to Instant.now().toString(),
            )
            val hash = LottoDesignRecordJson.hash(record)
            FileOutputStream(file, true).use { output ->
                output.write(jsonBytes(record + ("recordHash" to hash)))
                output.write(10)
                output.flush()
                output.fd.sync()
            }
            sequence++
            lastHash = hash
        }
    }

    private fun recoverRun(directory: File): LottoDesignRecoveredRun {
        val issues = mutableListOf<String>()
        var plan: Map<String, Any?>? = null
        var source: Map<String, Any?>? = null
        var excluded = emptySet<Int>()
        val diagnostic = mutableListOf<LottoPriorDesignRoundResult>()
        val eventRecords = mutableListOf<Map<String, Any?>>()
        fun inspect(stage: String, block: () -> Unit) {
            try { block() } catch (error: Exception) {
                if (error is CancellationException) throw error
                issues += "$stage: ${error.message ?: error.javaClass.simpleName}"
            }
        }
        inspect("plan") {
            val document = readDocument(directory, "plan.json", "PLAN")
            validatePlan(document)
            plan = document
            excluded = integerList(document.getValue("excludedRounds")).toSet()
        }
        inspect("source") {
            val document = readDocument(directory, "source-manifest.json", "SOURCE")
            source = document
            validateSourceManifest(document)
            for (value in list(document.getValue("files"))) {
                val entry = obj(value)
                val file = safeChild(File(directory, "source"), string(entry.getValue("path")))
                require(file.isFile && fileHash(file) == string(entry.getValue("sha256"))) { "소스 사본 해시 불일치: ${file.name}" }
            }
        }
        val rounds = readJournal(directory, "rounds.jsonl", "ROUND", plan, source, issues)
        val events = readJournal(directory, "events.jsonl", "EVENT", plan, source, issues)
        eventRecords += events.records
        if (plan != null) inspect("rounds") {
            val fixed = requireNotNull(plan)
            val targets = integerList(fixed.getValue("targetRounds"))
            for ((index, record) in rounds.records.withIndex()) {
                val round = parseRound(obj(record.getValue("round")))
                require(index < targets.size && round.targetRoundNo == targets[index]) { "회차 기록 순서·중복·계획이 다릅니다." }
                validateRound(round, fixed)
                diagnostic += round
            }
        }
        var state = LottoDesignRunState.INCOMPLETE
        inspect("events") {
            val names = events.records.map { string(it.getValue("event")) }
            for (record in events.records) {
                require(string(record.getValue("stage")).isNotBlank()) { "사건 처리 단계가 없습니다." }
                if (record.getValue("event") in listOf("FAILURE", "CANCELLED")) validateException(obj(record.getValue("error")))
            }
            val allowedSequences = setOf(
                emptyList(), listOf("START"), listOf("FAILURE"), listOf("CANCELLED"),
                listOf("START", "SUCCESS"), listOf("START", "FAILURE"), listOf("START", "CANCELLED"),
                listOf("START", "SUCCESS", "FAILURE"), listOf("START", "SUCCESS", "CANCELLED"),
            )
            require(names in allowedSequences) {
                "시작/종료 사건 구성에 오류가 있습니다."
            }
            require(names.count { it == "START" } <= 1 && ("START" !in names || names.first() == "START")) { "시작 사건 위치가 다릅니다." }
            val terminal = events.records.lastOrNull()
            state = when (names.lastOrNull()) {
                "SUCCESS" -> {
                    require(names == listOf("START", "SUCCESS") && plan != null && source != null) { "성공의 시작·고정 기록이 없습니다." }
                    require(diagnostic.map { it.targetRoundNo } == integerList(requireNotNull(plan).getValue("targetRounds"))) { "성공 회차가 계획과 다릅니다." }
                    val ending = requireNotNull(terminal)
                    require(integer(ending.getValue("roundCount")) == diagnostic.size && ending.getValue("lastRoundHash") == rounds.lastHash) {
                        "종료 사건의 회차 수/마지막 해시가 다릅니다."
                    }
                    require(decimal(ending.getValue("baselineRate")) == diagnostic.map { it.baselineRate }.average() &&
                        decimal(ending.getValue("candidateRate")) == diagnostic.map { it.candidateRate }.average() &&
                        decimal(ending.getValue("pairedRateDifference")) == diagnostic.map { it.pairedRateDifference }.average()) {
                        "성공 평균이 회차 기록과 다릅니다."
                    }
                    LottoDesignRunState.SUCCESS
                }
                "FAILURE" -> LottoDesignRunState.FAILED
                "CANCELLED" -> LottoDesignRunState.CANCELLED
                else -> LottoDesignRunState.INCOMPLETE
            }
        }
        if (issues.isNotEmpty()) state = LottoDesignRunState.INVALID
        val planReferenceState = when {
            rounds.hasConflictingPlanReference || events.hasConflictingPlanReference -> LottoDesignPlanReferenceState.CONFLICT
            plan != null && (rounds.hasMatchedPlanReference || events.hasMatchedPlanReference) -> LottoDesignPlanReferenceState.MATCHED
            else -> LottoDesignPlanReferenceState.UNVERIFIED
        }
        return LottoDesignRecoveredRun(directory.name, state, plan?.let(LottoDesignRecordJson::encode),
            diagnostic, eventRecords, excluded, planReferenceState, issues)
    }

    private data class ReadJournal(
        val records: List<Map<String, Any?>>,
        val lastHash: String?,
        val hasMatchedPlanReference: Boolean,
        val hasConflictingPlanReference: Boolean,
    )

    private fun readJournal(
        directory: File, name: String, kind: String, plan: Map<String, Any?>?, source: Map<String, Any?>?, issues: MutableList<String>,
    ): ReadJournal {
        val records = mutableListOf<Map<String, Any?>>()
        var previous: String? = null
        var prefixValid = true
        var hasMatchedPlanReference = false
        var hasConflictingPlanReference = false
        val expectedPlanHash = plan?.let { LottoDesignRecordJson.hash(it) }
        val expectedSourceHash = source?.let { LottoDesignRecordJson.hash(it) }
        fun inspectLine(bytes: ByteArray, terminated: Boolean) {
            try {
                val record = LottoDesignRecordJson.decode(bytes)
                validateEnvelope(record, directory.name, kind)
                if (expectedPlanHash != null && record["planHash"] != expectedPlanHash) {
                    hasConflictingPlanReference = true
                    error("$name 계획 참조 해시 불일치: 제외 회차의 완전성을 확인할 수 없습니다.")
                }
                if (!terminated) { prefixValid = false; return }
                // 손상 이후 행도 계획 참조는 살피되 유효한 진단 접두부에 추가하지 않는다.
                if (!prefixValid) return
                require(integer(record.getValue("sequence")) == records.size + 1 && record.getValue("previousRecordHash") == previous) {
                    "$name 순번/해시 연결 오류"
                }
                val hash = string(record.getValue("recordHash"))
                require(hash == LottoDesignRecordJson.hash(record - "recordHash")) { "$name 행 해시 불일치" }
                Instant.parse(string(record.getValue("recordedAt")))
                if (expectedPlanHash != null) hasMatchedPlanReference = true
                require(expectedPlanHash != null && expectedSourceHash != null && record.getValue("sourceManifestHash") == expectedSourceHash) {
                    "$name 고정 기록 참조 오류"
                }
                records += record
                previous = hash
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                prefixValid = false
                issues += "$name: ${error.message ?: error.javaClass.simpleName}"
            }
        }
        try {
            val file = safeChild(directory, name)
            require(file.isFile) { "$name 파일이 없습니다." }
            FileInputStream(file).buffered().use { input ->
                val line = ByteArrayOutputStream()
                while (true) {
                    val byte = input.read()
                    if (byte == -1) {
                        if (line.size() != 0) {
                            issues += "$name 마지막 행이 완전히 기록되지 않았습니다(LF 없음)."
                            inspectLine(line.toByteArray(), terminated = false)
                        }
                        break
                    }
                    if (byte != 10) { line.write(byte); continue }
                    inspectLine(line.toByteArray(), terminated = true)
                    line.reset()
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            issues += "$name: ${error.message ?: error.javaClass.simpleName}"
        }
        return ReadJournal(records, previous, hasMatchedPlanReference, hasConflictingPlanReference)
    }

    private fun readDocument(directory: File, name: String, kind: String): Map<String, Any?> {
        val file = safeChild(directory, name)
        val document = LottoDesignRecordJson.decode(file.readBytes())
        validateEnvelope(document, directory.name, kind)
        return document
    }

    private fun validatePlan(plan: Map<String, Any?>) {
        require(plan.getValue("purpose") == "DESIGN" && plan.getValue("strategy") == "BALANCED" &&
            plan.getValue("mode") == "BASIC" && integer(plan.getValue("gameCount")) == 5) { "설계 계획 조건이 다릅니다." }
        val draws = parseDraws(plan)
        val targets = integerList(plan.getValue("targetRounds"))
        val start = integer(plan.getValue("historyStartRound"))
        require(start > 0 && targets.isNotEmpty() && targets == targets.distinct().sorted() && targets.all { it > start }) { "대상 회차/입력 경계 오류" }
        require(draws.size.toLong() == targets.last().toLong() - start + 1 && draws.first().roundNo == targets.last() &&
            draws.last().roundNo == start && draws.zipWithNext().all { (newer, older) -> newer.roundNo - older.roundNo == 1 }) {
            "입력 회차 누락/중복/순서 오류"
        }
        require(integerList(plan.getValue("excludedRounds")) == draws.map { it.roundNo }.sorted()) { "제외 회차가 전체 입력과 다릅니다." }
        val seeds = seedList(plan)
        require(seeds.isNotEmpty() && seeds.distinct().size == seeds.size) { "공통 시드 오류" }
        val controlSeeds = plan["controlSeeds"]?.let { value -> list(value).map { string(it).toLong() } }.orEmpty()
        require(controlSeeds.isEmpty() || (controlSeeds.size == seeds.size && controlSeeds.distinct().size == seeds.size &&
            controlSeeds.intersect(seeds.toSet()).isEmpty())) { "대조군 시드 오류" }
        if (controlSeeds.isNotEmpty()) require(plan["controlImplementationId"] == "uniform-without-replacement-v1") { "대조군 구현 오류" }
        if (plan["experiment"]?.let(::obj)?.get("purpose") == "FINAL") {
            val proofs = obj(plan.getValue("inputProofs"))
            require(proofs.keys == targets.map(Int::toString).toSet() && proofs.values.all { hashPattern.matches(string(it)) }) { "추첨 전 입력 증빙 참조 오류" }
        }
        require(plan.getValue("datasetHash") == drawHash(draws)) { "입력 데이터 해시 불일치" }
        val models = list(plan.getValue("models")).map(::obj)
        require(models.size == 2 && models.map { decimal(it.getValue("priorDraws")) } == listOf(32.0, 64.0)) { "기준/후보 조건 오류" }
        for (model in models) require(model.getValue("configHash") == LottoDesignRecordJson.hash(string(model.getValue("configJson")).toByteArray(Charsets.UTF_8))) {
            "모델 설정 해시 불일치"
        }
        require(string(plan.getValue("implementationId")).isNotBlank() && string(plan.getValue("primaryMetric")).isNotBlank()) { "계산 구현/지표 식별이 없습니다." }
        Instant.parse(string(plan.getValue("createdAt")))
        require(plan.getValue("inputHashAlgorithm") == inputHashAlgorithm && plan.getValue("configHashAlgorithm") == configHashAlgorithm) {
            "지원하지 않는 입력/설정 해시 알고리즘입니다."
        }
        require(LottoDesignRecordJson.longInteger(plan.getValue("candidateScoreCount")) == targets.size.toLong() * seeds.size * 12000 &&
            LottoDesignRecordJson.longInteger(plan.getValue("candidateAttemptUpperBound")) == targets.size.toLong() * seeds.size * 240000 &&
            LottoDesignRecordJson.longInteger(plan.getValue("selectedGameCount")) == targets.size.toLong() * seeds.size *
                (if (controlSeeds.isEmpty()) 10 else 15)) { "계획 계산량이 조건과 다릅니다." }
    }

    private fun validateSourceManifest(source: Map<String, Any?>) {
        val files = list(source.getValue("files")).map(::obj)
        val paths = files.map { string(it.getValue("path")) }
        require(paths.isNotEmpty() && paths == paths.distinct().sorted()) { "소스 목록 중복/순서 오류" }
        val required = setOf("LottoNumberGenerator.kt", "LottoPriorDesignComparison.kt", "LottoDesignRunStore.kt", "LottoDesignRecordJson.kt")
        require(paths.map { it.substringAfterLast('/') }.toSet().containsAll(required) &&
            paths.containsAll(listOf("app/build.gradle.kts", "build.gradle.kts", "gradle/libs.versions.toml"))) { "생성·기록 소스 또는 빌드 설정 증빙이 누락되었습니다." }
        for (file in files) {
            validateRelativePath(string(file.getValue("path")))
            require(hashPattern.matches(string(file.getValue("sha256")))) { "소스 SHA-256 형식 오류" }
        }
        require(source.getValue("buildSourceHash") == LottoDesignRecordJson.hash(files)) { "빌드 소스 목록의 전체 해시 불일치" }
        require(hashPattern.matches(string(source.getValue("apkHash")))) { "APK 해시 형식 오류" }
        require(string(source.getValue("buildReference")).isNotBlank()) { "소스와 산출물의 빌드 증빙이 필요합니다." }
        val versions = obj(source.getValue("dependencyVersions"))
        require(versions.keys.containsAll(listOf("kotlin", "kotlinStdlib", "coroutines")) && versions.values.all { string(it).isNotBlank() }) {
            "실제 의존성 버전 증빙이 필요합니다."
        }
        require(source.getValue("hasUncommittedChanges") is Boolean &&
            (source.getValue("gitHead") == null || string(source.getValue("gitHead")).isNotBlank())) { "Git 참고 정보가 유효하지 않습니다." }
        require(obj(source.getValue("splitApkHashes")).values.all { hashPattern.matches(string(it)) }) { "split APK 해시 형식 오류" }
    }

    private fun validateRound(round: LottoPriorDesignRoundResult, plan: Map<String, Any?>) {
        val draws = parseDraws(plan)
        val history = draws.filter { it.roundNo < round.targetRoundNo }
        val target = draws.single { it.roundNo == round.targetRoundNo }
        require(round.historyThroughRound == round.targetRoundNo - 1 && round.historyDrawCount == history.size &&
            round.inputDataHash == drawHash(history) && round.targetDataHash == drawHash(listOf(target))) { "회차 입력/정답 해시 오류" }
        require(round.seeds.map { it.seed } == seedList(plan)) { "회차 시드가 계획과 다릅니다." }
        val controlSeeds = plan["controlSeeds"]?.let { value -> list(value).map { string(it).toLong() } }.orEmpty()
        require(if (controlSeeds.isEmpty()) round.seeds.all { it.control == null && it.controlSeed == null }
            else round.seeds.map { it.controlSeed } == controlSeeds && round.seeds.all { it.control != null }) { "대조군 기록이 계획과 다릅니다." }
        for (seed in round.seeds) for (batch in listOfNotNull(seed.baseline, seed.candidate, seed.control)) {
            require(batch.games.size == 5 && batch.games.distinct().size == 5 && batch.games.all {
                it.size == 6 && it == it.distinct().sorted() && it.all { number -> number in 1..45 }
            }) { "회차 게임 유효성 오류" }
            val matches = batch.games.map { game -> game.count { it in target.numbers } }
            val coverage = batch.games.flatten().distinct().size
            val overlaps = batch.games.indices.flatMap { first -> (first + 1 until 5).map { second ->
                batch.games[first].count { it in batch.games[second] }
            } }
            require(batch.mainMatchCounts == matches && batch.threePlusMatchRate == matches.count { it >= 3 } / 5.0 &&
                batch.meanMainMatchCount == matches.average() && batch.distinctNumberCount == coverage &&
                batch.reusedNumberSlots == 30 - coverage && batch.pairOverlapCounts == overlaps) { "회차 지표가 선택 번호와 다릅니다." }
        }
    }

    private fun parseDraws(plan: Map<String, Any?>): List<LottoPriorDesignDraw> = list(plan.getValue("draws")).map { value ->
        val draw = obj(value)
        val numbers = integerList(draw.getValue("numbers"))
        val bonus = draw.getValue("bonusNumber")?.let(::integer)
        require(numbers.size == 6 && numbers == numbers.distinct().sorted() && numbers.all { it in 1..45 } &&
            (bonus == null || (bonus in 1..45 && bonus !in numbers))) { "입력 번호 유효성 오류" }
        LottoPriorDesignDraw(integer(draw.getValue("roundNo")), numbers, bonus)
    }

    private fun seedList(plan: Map<String, Any?>): List<Long> = list(plan.getValue("commonSeeds")).map { value ->
        val text = string(value)
        val seed = text.toLong()
        require(seed.toString() == text) { "시드 정규화 오류" }
        seed
    }

    private fun validateException(error: Map<String, Any?>) {
        require(string(error.getValue("type")).isNotBlank() && (error.getValue("message") == null || error.getValue("message") is String)) {
            "오류 종류/메시지 기록이 유효하지 않습니다."
        }
        if (error["cycle"] == true) return
        list(error.getValue("stackTrace")).forEach { string(it) }
        error.getValue("cause")?.let { validateException(obj(it)) }
        list(error.getValue("suppressed")).forEach { validateException(obj(it)) }
    }

    private fun parseRound(value: Map<String, Any?>): LottoPriorDesignRoundResult = LottoPriorDesignRoundResult(
        integer(value.getValue("targetRoundNo")), integer(value.getValue("historyThroughRound")),
        integer(value.getValue("historyDrawCount")), string(value.getValue("inputDataHash")), string(value.getValue("targetDataHash")),
        list(value.getValue("seeds")).map { seed ->
            val entry = obj(seed)
            LottoPriorDesignSeedResult(string(entry.getValue("seed")).toLong(), parseBatch(obj(entry.getValue("baseline"))), parseBatch(obj(entry.getValue("candidate"))),
                entry["controlSeed"]?.let { string(it).toLong() }, entry["control"]?.let { parseBatch(obj(it)) })
        },
    )

    private fun parseBatch(value: Map<String, Any?>): LottoPriorDesignBatch = LottoPriorDesignBatch(
        list(value.getValue("games")).map(::integerList), integerList(value.getValue("mainMatchCounts")),
        decimal(value.getValue("threePlusMatchRate")), decimal(value.getValue("meanMainMatchCount")),
        integer(value.getValue("distinctNumberCount")), integer(value.getValue("reusedNumberSlots")), integerList(value.getValue("pairOverlapCounts")),
    )

    companion object {
        private val runTimeFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC)
        private val runIdPattern = Regex("design-[0-9]{8}T[0-9]{9}Z-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val hashPattern = Regex("[0-9a-f]{64}")
        private const val inputHashAlgorithm = "SHA-256 UTF-8 round:numbers:bonus joined by |, rounds descending; null bonus=null"
        private const val configHashAlgorithm = "SHA-256 original configJson UTF-8 bytes"
        private fun obj(value: Any?) = LottoDesignRecordJson.objectValue(value)
        private fun list(value: Any?) = LottoDesignRecordJson.list(value)
        private fun string(value: Any?) = LottoDesignRecordJson.string(value)
        private fun integer(value: Any?) = LottoDesignRecordJson.integer(value)
        private fun decimal(value: Any?) = LottoDesignRecordJson.decimal(value)
        private fun integerList(value: Any?) = list(value).map(::integer)
        private fun jsonBytes(value: Any?) = LottoDesignRecordJson.encode(value).toByteArray(Charsets.UTF_8)
        private fun envelope(id: String, kind: String) = mapOf("formatVersion" to LottoDesignRecordJson.VERSION, "runId" to id, "kind" to kind)
        private fun validateEnvelope(value: Map<String, Any?>, id: String, kind: String) {
            require(integer(value.getValue("formatVersion")) == LottoDesignRecordJson.VERSION &&
                value.getValue("runId") == id && value.getValue("kind") == kind) { "실행 기록 버전/ID/종류가 다릅니다." }
        }

        internal fun planJson(plan: LottoPriorDesignPlan): Map<String, Any?> = mapOf(
            "purpose" to "DESIGN", "strategy" to "BALANCED", "mode" to "BASIC", "gameCount" to 5,
            "implementationId" to plan.implementationId, "historyStartRound" to plan.historyStartRound,
            "targetRounds" to plan.targetRounds, "excludedRounds" to plan.draws.map { it.roundNo }.sorted(),
            "commonSeeds" to plan.commonSeeds.map(Long::toString), "datasetHash" to plan.datasetHash,
            "inputHashAlgorithm" to inputHashAlgorithm,
            "configHashAlgorithm" to configHashAlgorithm,
            "draws" to plan.draws.map { mapOf("roundNo" to it.roundNo, "numbers" to it.numbers, "bonusNumber" to it.bonusNumber) },
            "models" to listOf(plan.baseline, plan.candidate).map { mapOf(
                "modelId" to it.modelId, "priorDraws" to it.priorDraws, "configJson" to it.configJson, "configHash" to it.configHash,
            ) },
            "primaryMetric" to "equal-round mean of per-seed five-game main-number three-plus match proportion; no bonus",
            "candidateScoreCount" to plan.candidateScoreCount, "candidateAttemptUpperBound" to plan.candidateAttemptUpperBound,
            "selectedGameCount" to plan.selectedGameCount, "createdAt" to Instant.now().toString(),
        ) + if (plan.controlSeeds.isEmpty()) emptyMap() else mapOf(
            "controlSeeds" to plan.controlSeeds.map(Long::toString), "controlImplementationId" to "uniform-without-replacement-v1",
            "experiment" to plan.experiment,
            "inputProofs" to plan.inputProofs,
        )

        internal fun sourceJson(source: LottoDesignSourceEvidence): Map<String, Any?> = mapOf(
            "files" to source.files.sortedBy { it.relativePath }.map { mapOf("path" to it.relativePath, "sha256" to it.sha256) },
            "buildSourceHash" to source.buildSourceHash, "apkHash" to source.apkHash, "splitApkHashes" to source.splitApkHashes,
            "dependencyVersions" to source.dependencyVersions, "buildReference" to source.buildReference,
            "gitHead" to source.gitHead, "hasUncommittedChanges" to source.hasUncommittedChanges,
            "androidApi" to Build.VERSION.SDK_INT,
        )

        private fun roundJson(round: LottoPriorDesignRoundResult): Map<String, Any?> = mapOf(
            "targetRoundNo" to round.targetRoundNo, "historyThroughRound" to round.historyThroughRound,
            "historyDrawCount" to round.historyDrawCount, "inputDataHash" to round.inputDataHash, "targetDataHash" to round.targetDataHash,
            "seeds" to round.seeds.map { mapOf("seed" to it.seed.toString(), "baseline" to batchJson(it.baseline), "candidate" to batchJson(it.candidate)) +
                if (it.control == null) emptyMap() else mapOf("controlSeed" to it.controlSeed.toString(), "control" to batchJson(it.control)) },
        )

        private fun batchJson(batch: LottoPriorDesignBatch): Map<String, Any?> = mapOf(
            "games" to batch.games, "mainMatchCounts" to batch.mainMatchCounts, "threePlusMatchRate" to batch.threePlusMatchRate,
            "meanMainMatchCount" to batch.meanMainMatchCount, "distinctNumberCount" to batch.distinctNumberCount,
            "reusedNumberSlots" to batch.reusedNumberSlots, "pairOverlapCounts" to batch.pairOverlapCounts,
        )

        internal fun exceptionJson(error: Throwable, seen: MutableSet<Throwable> = java.util.Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())): Map<String, Any?> {
            if (!seen.add(error)) return mapOf("type" to error.javaClass.name, "message" to error.message, "cycle" to true)
            return mapOf(
                "type" to error.javaClass.name, "message" to error.message, "stackTrace" to error.stackTrace.map { it.toString() },
                "cause" to error.cause?.let { exceptionJson(it, seen) }, "suppressed" to error.suppressed.map { exceptionJson(it, seen) },
                "targetRoundNo" to (error as? LottoPriorDesignFailure)?.targetRoundNo,
                "modelId" to (error as? LottoPriorDesignFailure)?.modelId, "seed" to (error as? LottoPriorDesignFailure)?.seed?.toString(),
                "stage" to (error as? LottoPriorDesignFailure)?.stage,
            )
        }

        private fun drawHash(draws: List<LottoPriorDesignDraw>) = LottoDesignRecordJson.hash(draws.joinToString("|") {
            "${it.roundNo}:${it.numbers.joinToString(",")}:${it.bonusNumber}"
        }.toByteArray(Charsets.UTF_8))

        private fun validateRelativePath(path: String) {
            require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path &&
                path.split('/').all { it.isNotBlank() && it != "." && it != ".." }) { "소스 상대 경로가 유효하지 않습니다: $path" }
        }

        private fun safeChild(parent: File, path: String): File {
            validateRelativePath(path)
            val file = File(parent, path).absoluteFile
            require(parent.canonicalFile == parent.absoluteFile && file.canonicalFile == file &&
                file.path.startsWith(parent.path + File.separator)) { "기록 경로 이탈/심볼릭 링크: $path" }
            return file
        }

        internal fun createParents(root: File, parent: File) {
            if (parent == root) return
            createParents(root, parent.parentFile!!)
            if (!parent.exists()) {
                require(parent.mkdir()) { "소스 상위 경로 생성 실패: ${parent.name}" }
                syncDirectory(parent.parentFile!!)
            }
            require(parent.isDirectory && parent.canonicalFile == parent.absoluteFile) { "소스 상위 경로가 유효하지 않습니다." }
        }

        internal fun writeImmutable(file: File, bytes: ByteArray) {
            val temporary = File(file.parentFile, "${file.name}.pending")
            require(temporary.createNewFile()) { "임시 기록 파일이 이미 존재합니다: ${temporary.name}" }
            FileOutputStream(temporary).use { output -> output.write(bytes); output.flush(); output.fd.sync() }
            // Android 앱의 하드 링크가 차단될 수 있어 배타 생성한다. 중단된 쓰기는 복구 시 손상으로 판별한다.
            val descriptor = Os.open(file.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL, 384)
            FileOutputStream(descriptor).use { output -> output.write(bytes); output.flush(); output.fd.sync() }
            Os.remove(temporary.path)
            syncDirectory(file.parentFile!!)
        }

        internal fun syncDirectory(directory: File) {
            require(directory.isDirectory && directory.canonicalFile == directory.absoluteFile) { "동기화할 디렉터리가 유효하지 않습니다." }
            val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
            var failure: Exception? = null
            try {
                Os.fsync(descriptor)
            } catch (error: Exception) {
                failure = error
                throw error
            } finally {
                try { Os.close(descriptor) } catch (closeError: Exception) {
                    val original = failure
                    if (original == null) throw closeError else original.addSuppressed(closeError)
                }
            }
        }

        private fun fileHash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
