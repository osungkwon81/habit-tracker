package com.habittracker.data.lotto

import android.content.Context
import android.content.ContextWrapper
import android.system.ErrnoException
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 고정된 번호를 기록할 뿐 생성기·compareAndRecord·DB를 호출하지 않는다. */
@RunWith(AndroidJUnit4::class)
class LottoDesignRunStoreFixtureTest {
    private lateinit var temporaryRoot: File
    private lateinit var store: LottoDesignRunStore
    private lateinit var evidence: LottoDesignSourceEvidence
    private lateinit var isolatedContext: Context
    private val plan get() = LottoPriorDesignComparison.prepare(
        (3 downTo 1).map { LottoPriorDesignDraw(it, (1..6).toList(), 7) },
        1, listOf(2, 3), listOf(42L),
    )

    @Before
    fun isolateFiles() {
        val testContext = InstrumentationRegistry.getInstrumentation().targetContext
        temporaryRoot = File(testContext.cacheDir, "lotto-record-fixtures-${UUID.randomUUID()}").canonicalFile
        check(temporaryRoot.mkdir())
        isolatedContext = object : ContextWrapper(testContext) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = temporaryRoot
        }
        store = LottoDesignRunStore(isolatedContext)
        val sourceRoot = File(temporaryRoot, "fixture-source").apply { check(mkdir()) }
        val paths = listOf(
            "app/src/main/java/com/habittracker/data/lotto/LottoNumberGenerator.kt",
            "app/src/main/java/com/habittracker/data/lotto/LottoPriorDesignComparison.kt",
            "app/src/main/java/com/habittracker/data/lotto/LottoDesignRunStore.kt",
            "app/src/main/java/com/habittracker/data/lotto/LottoDesignRecordJson.kt",
            "app/build.gradle.kts", "build.gradle.kts", "gradle/libs.versions.toml",
        ).sorted()
        val files = paths.map { path ->
            val file = File(sourceRoot, path)
            check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
            file.writeText("SYNTHETIC FIXTURE ONLY: $path\n")
            LottoDesignSourceFile(path, LottoDesignRecordJson.hash(file.readBytes()))
        }
        evidence = LottoDesignSourceEvidence(
            sourceRoot, files,
            LottoDesignRecordJson.hash(files.map { mapOf("path" to it.relativePath, "sha256" to it.sha256) }),
            "0".repeat(64), emptyMap(),
            mapOf("kotlin" to "fixture", "kotlinStdlib" to "fixture", "coroutines" to "fixture"),
            "synthetic fixture; not actual source/build evidence", null, false,
        )
        // 진단용 fixture는 대상 앱 cacheDir의 UUID 경로에 남긴다. 실제 실행 폴더는 접근하지 않는다.
        println("LOTTO_RECORD_FIXTURE_ROOT=${temporaryRoot.path}")
    }

    @Test
    fun fixture01_success() = runBlocking {
        val fixture = fixture(success = true)
        val recovery = store.recover()
        val run = recovery.runs.single()
        assertEquals(LottoDesignRunState.SUCCESS, run.state)
        assertTrue(run.planInternallyValid)
        assertEquals(LottoDesignPlanReferenceState.MATCHED, run.planReferenceState)
        assertFalse(run.exclusionCoverageUncertain)
        assertFalse(recovery.hasUnrecoverablePlans)
        assertFalse(recovery.historyPresenceGuaranteed)
        assertEquals(fixture.results, run.diagnosticRounds)
        assertEquals(setOf(1, 2, 3), run.excludedRounds)
        assertEquals(0.2, requireNotNull(run.baselineRate), 0.0)
        assertEquals(0.2, requireNotNull(run.candidateRate), 0.0)
        assertEquals(0.0, requireNotNull(run.pairedRateDifference), 0.0)
    }

    @Test
    fun fixture02_duplicateRunId() {
        val fixture = fixture(success = true)
        val before = snapshot(fixture.directory)
        val error = failure { call(store, "reserve", fixture.id, plan, evidence) }
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message.orEmpty().contains(fixture.id))
        assertEquals(before, snapshot(fixture.directory))
    }

    @Test
    fun fixture03_internallyValidDifferentPlan() = runBlocking {
        val fixture = fixture(success = true)
        val changed = LottoPriorDesignComparison.prepare(plan.draws, 2, listOf(3), listOf(42L))
        val document = objectMap(read(File(fixture.directory, "plan.json"))) +
            objectMap(call(LottoDesignRunStore.Companion, "planJson", changed))
        File(fixture.directory, "plan.json").writeBytes(bytes(document))
        val recovery = store.recover()
        val run = recovery.runs.single()
        assertEquals(LottoDesignRunState.INVALID, run.state)
        assertTrue(run.planInternallyValid)
        assertEquals(LottoDesignPlanReferenceState.CONFLICT, run.planReferenceState)
        assertTrue(run.exclusionCoverageUncertain)
        assertTrue(recovery.hasUnrecoverablePlans)
        assertEquals(setOf(2, 3), recovery.excludedRounds)
        assertNull(run.baselineRate)
    }

    @Test
    fun fixture04_resultDamageAndLaterReferenceConflict() = runBlocking {
        val fixture = fixture(success = true)
        val file = File(fixture.directory, "rounds.jsonl")
        val rows = file.readLines().map { objectMap(LottoDesignRecordJson.decode(it.toByteArray())) }.toMutableList()
        rows[0] = rows[0] + ("recordHash" to "0".repeat(64))
        file.writeText(rows.joinToString("\n", postfix = "\n") { LottoDesignRecordJson.encode(it) })
        var recovery = store.recover()
        assertEquals(LottoDesignRunState.INVALID, recovery.runs.single().state)
        assertEquals(LottoDesignPlanReferenceState.MATCHED, recovery.runs.single().planReferenceState)
        assertFalse(recovery.hasUncertainExclusionCoverage)
        rows[1] = rows[1] + ("planHash" to "f".repeat(64))
        file.writeText(rows.joinToString("\n", postfix = "\n") { LottoDesignRecordJson.encode(it) })
        recovery = store.recover()
        assertEquals(LottoDesignRunState.INVALID, recovery.runs.single().state)
        assertEquals(LottoDesignPlanReferenceState.CONFLICT, recovery.runs.single().planReferenceState)
        assertTrue(recovery.hasUncertainExclusionCoverage)
        assertEquals(setOf(1, 2, 3), recovery.excludedRounds)
    }

    @Test
    fun fixture05_truncatedLastRowAndMissingTerminal() = runBlocking {
        val truncated = fixture(success = true)
        val rounds = File(truncated.directory, "rounds.jsonl")
        val rows = rounds.readLines()
        rounds.writeText(rows.first() + "\n" + rows.last().take(rows.last().length / 2))
        val missing = fixture(success = false)
        val recovery = store.recover()
        val damaged = recovery.runs.single { it.runId == truncated.id }
        assertEquals(LottoDesignRunState.INVALID, damaged.state)
        assertEquals(listOf(2), damaged.diagnosticRounds.map { it.targetRoundNo })
        assertEquals(LottoDesignPlanReferenceState.MATCHED, damaged.planReferenceState)
        assertNull(damaged.baselineRate)
        val unfinished = recovery.runs.single { it.runId == missing.id }
        assertEquals(LottoDesignRunState.INCOMPLETE, unfinished.state)
        assertEquals(listOf(2, 3), unfinished.diagnosticRounds.map { it.targetRoundNo })
        assertNull(unfinished.baselineRate)
        assertEquals(setOf(1, 2, 3), unfinished.excludedRounds)
    }

    @Test
    fun fixture06_failureCancellationAndAbnormalExit() = runBlocking {
        val cause = IOException("fixture original cause")
        val failedError = LottoPriorDesignFailure(3, "fixture-model", 42L, "generate", cause)
        val cancel = CancellationException("fixture cancellation").apply {
            addSuppressed(LottoPriorDesignFailure(3, "fixture-model", 42L, "round_record", IOException("cancel context")))
        }
        val failed = fixture(roundCount = 1, terminal = "FAILURE", error = failedError)
        val cancelled = fixture(roundCount = 1, terminal = "CANCELLED", error = cancel)
        val abnormal = fixture(roundCount = 0)
        val recovery = store.recover()
        val failureRun = recovery.runs.single { it.runId == failed.id }
        assertEquals(LottoDesignRunState.FAILED, failureRun.state)
        val failureJson = objectMap(failureRun.events.last().getValue("error"))
        assertEquals("fixture original cause", objectMap(failureJson.getValue("cause"))["message"])
        assertEquals("fixture-model", failureJson["modelId"])
        assertEquals("generate", failureJson["stage"])
        val cancelledRun = recovery.runs.single { it.runId == cancelled.id }
        assertEquals(LottoDesignRunState.CANCELLED, cancelledRun.state)
        val cancelJson = objectMap(cancelledRun.events.last().getValue("error"))
        assertEquals(CancellationException::class.java.name, cancelJson["type"])
        val suppressed = LottoDesignRecordJson.list(cancelJson.getValue("suppressed")).single()
        assertEquals("round_record", objectMap(suppressed)["stage"])
        assertEquals(LottoDesignRunState.INCOMPLETE, recovery.runs.single { it.runId == abnormal.id }.state)
        recovery.runs.forEach {
            assertEquals(setOf(1, 2, 3), it.excludedRounds)
            assertFalse(it.exclusionCoverageUncertain)
            assertNull(it.baselineRate)
        }
    }

    @Test
    fun fixture07_fileFailurePropagation() = runBlocking {
        val id = store.newRunId()
        val session = call(store, "reserve", id, plan, evidence)!!
        val directory = call(session, "getDirectory") as File
        val pending = File(directory, "plan.json.pending").apply { writeText("blocked fixture") }
        val preparationError = failure { call(session, "prepare", evidence) }
        assertTrue(preparationError is IllegalArgumentException)
        assertEquals("blocked fixture", pending.readText())
        assertFalse(File(directory, "plan.json").exists())
        val fixture = fixture(roundCount = 0)
        val existingPlan = File(fixture.directory, "plan.json")
        val original = existingPlan.readBytes()
        val immutableError = failure { call(LottoDesignRunStore.Companion, "writeImmutable", existingPlan, bytes(emptyMap<String, Any?>())) }
        assertTrue(immutableError is ErrnoException)
        assertArrayEquals(original, existingPlan.readBytes())
        val events = File(fixture.directory, "events.jsonl")
        check(events.delete())
        check(events.mkdir())
        val appendError = failure { append(fixture.events, mapOf("event" to "SUCCESS", "stage" to "fixture_write")) }
        assertTrue(appendError is IOException)
        assertTrue(appendError.cause is ErrnoException)
        assertEquals(android.system.OsConstants.EISDIR, (appendError.cause as ErrnoException).errno)
        assertTrue(events.isDirectory)
        assertTrue(store.recover().runs.all { it.state != LottoDesignRunState.SUCCESS && it.baselineRate == null })
    }

    @Test
    fun fixture08_fullApprovalAndRollbackUseOnlySyntheticRecords() = runBlocking {
        val evaluationTime = lotteryEvaluationDeadline(LotteryProduct.LOTTO_645, 10018).plusDays(1).atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant()
        val experiments = LottoExperimentStore(isolatedContext) { evaluationTime }
        experiments.approvedSetting()
        val draws = (10018 downTo 9997).map { LottoPriorDesignDraw(it, (1..6).toList(), 7) }
        val prepared = LottoPriorDesignComparison.prepare(draws, 9997, (9999..10018).toList(), listOf(42L), listOf(43L))
        val runtimeSource = evidence.copy(
            apkHash = LottoDesignRecordJson.hash(File(isolatedContext.applicationInfo.sourceDir).readBytes()),
            splitApkHashes = isolatedContext.applicationInfo.splitSourceDirs.orEmpty().associate { path -> File(path).name to LottoDesignRecordJson.hash(File(path).readBytes()) },
        )
        val policy = mapOf("confirmedByUser" to true, "confirmedAt" to "2026-10-05T00:00:00Z", "primaryMetric" to "main-three-plus-game-proportion",
            "confidenceLevel" to 0.95, "minimumEffect" to 0.0025, "finalRoundCount" to 20, "familyComparisonCount" to 2,
            "intervalMethod" to LottoExperimentPolicy.METHOD, "assumptions" to "synthetic records; not real inference",
            "sampleSizeRationale" to "fixture only; not sufficient real-world sample", "rejectWhenUpperNonPositive" to true,
            "singleFinalLook" to true, "noEarlyAdoption" to true)
        val frozenInput = prepared.draws.filter { it.roundNo <= 9998 }
        val spec = mapOf("formatVersion" to 1, "purpose" to "FINAL", "historyStartRound" to 9997, "targetRounds" to prepared.targetRounds,
            "commonSeeds" to listOf("42"), "controlSeeds" to listOf("43"), "hypothesis" to "synthetic approval fixture",
            "comparisonFamilyId" to "fixture", "selectionRounds" to emptyList<Int>(), "policy" to policy,
            "historyCompletenessConfirmation" to "fixture only", "registeredAt" to "2026-10-05T00:00:00Z",
            "inputAtRegistration" to frozenInput.map { mapOf("roundNo" to it.roundNo, "numbers" to it.numbers, "bonusNumber" to it.bonusNumber) },
            "inputAtRegistrationHash" to drawHash(frozenInput), "baselineConfigHash" to prepared.baseline.configHash,
            "candidateConfigHash" to prepared.candidate.configHash, "implementationId" to prepared.implementationId,
            "source" to call(LottoDesignRunStore.Companion, "sourceJson", runtimeSource))
        val baselineGames = (0..4).map { offset -> (offset * 6 + 7..offset * 6 + 12).toList() }
        val candidateGames = listOf(listOf(1,2,3,4,5,6), listOf(1,2,3,4,5,7), listOf(1,2,3,4,6,8), listOf(1,2,3,5,6,9), listOf(1,2,4,5,6,10))
        val fixture = fixture(success = true, roundCount = 20, preparedPlan = prepared, spec = spec, source = runtimeSource,
            baselineBatch = batch(baselineGames), candidateBatch = batch(candidateGames))
        val registration = requireNotNull(objectMap(read(File(fixture.directory, "plan.json")))["experiment"]).let(::objectMap)
        val registrationDir = File(temporaryRoot, "lotto-experiments/${fixture.id}")
        val run = store.recover().runs.single()
        val judgment = LottoExperimentPolicy.judge(registration, run)
        assertEquals(LottoExperimentDecision.ADOPTION_CANDIDATE, judgment.decision)
        immutable(File(registrationDir, "execution.json"), mapOf("id" to fixture.id, "startedAt" to evaluationTime.toString(),
            "registrationHash" to LottoDesignRecordJson.hash(registration), "excludedRounds" to run.excludedRounds.sorted()))
        val result = mapOf("id" to fixture.id, "finishedAt" to evaluationTime.toString(), "registrationHash" to LottoDesignRecordJson.hash(registration),
            "planHash" to LottoDesignRecordJson.hash(read(File(fixture.directory, "plan.json"))),
            "judgment" to call(experiments, "judgmentJson", judgment), "excludedRounds" to run.excludedRounds.sorted())
        immutable(File(registrationDir, "result.json"), result + ("recordHash" to LottoDesignRecordJson.hash(result)))
        val before = snapshot(fixture.directory)
        experiments.approve(fixture.id, draws, "synthetic approval; no actual prediction evidence")
        assertEquals(64.0, experiments.approvedSetting().priorDraws, 0.0)
        experiments.rollback("synthetic rollback")
        assertEquals(32.0, experiments.approvedSetting().priorDraws, 0.0)
        assertEquals(before, snapshot(fixture.directory))
    }

    private data class Fixture(val id: String, val directory: File, val events: Any, val results: List<LottoPriorDesignRoundResult>)

    private fun fixture(success: Boolean = false, roundCount: Int = 2, terminal: String? = null, error: Throwable? = null,
        preparedPlan: LottoPriorDesignPlan = plan, spec: Map<String, Any?>? = null, source: LottoDesignSourceEvidence = evidence,
        baselineBatch: LottoPriorDesignBatch? = null, candidateBatch: LottoPriorDesignBatch? = null): Fixture {
        val id = store.newRunId()
        val registered = spec?.plus("id" to id)?.let { it + ("registrationHash" to LottoDesignRecordJson.hash(it)) }
        val inputProofs = if (registered?.get("purpose") == "FINAL") {
            val directory = File(temporaryRoot, "lotto-experiments/$id").apply { check(mkdir()) }
            immutable(File(directory, "registration.json"), registered)
            val folder = File(directory, "input-snapshots").apply { check(mkdir()) }
            preparedPlan.targetRounds.associate { target ->
                val input = preparedPlan.draws.filter { it.roundNo < target }
                val record = mapOf("targetRoundNo" to target,
                    "capturedAt" to lotteryEvaluationDeadline(LotteryProduct.LOTTO_645, target).minusHours(1).atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant().toString(),
                    "registrationHash" to LottoDesignRecordJson.hash(registered),
                    "sourceHash" to LottoDesignRecordJson.hash(registered.getValue("source")),
                    "input" to input.map { mapOf("roundNo" to it.roundNo, "numbers" to it.numbers, "bonusNumber" to it.bonusNumber) },
                    "inputHash" to drawHash(input))
                val hash = LottoDesignRecordJson.hash(record)
                immutable(File(folder, "$target.json"), record + ("recordHash" to hash))
                target.toString() to hash
            }
        } else emptyMap()
        val fixedPlan = LottoPriorDesignComparison.prepare(preparedPlan.draws, preparedPlan.historyStartRound, preparedPlan.targetRounds,
            preparedPlan.commonSeeds, preparedPlan.controlSeeds, registered, inputProofs)
        val session = call(store, "reserve", id, fixedPlan, source)!!
        val directory = call(session, "getDirectory") as File
        immutable(File(directory, "plan.json"), call(session, "getPlan"))
        immutable(File(directory, "source-manifest.json"), call(session, "getSource"))
        source.files.forEach { entry ->
            val output = File(directory, "source/${entry.relativePath}")
            check(output.parentFile!!.mkdirs() || output.parentFile!!.isDirectory)
            immutableBytes(output, File(source.directory, entry.relativePath).readBytes())
        }
        val events = call(session, "getEvents")!!
        val rounds = call(session, "getRounds")!!
        call(rounds, "create")
        append(events, mapOf("event" to "START", "stage" to "fixture_start"))
        val batch = LottoPriorDesignBatch(
            (0..4).map { offset -> (offset * 6 + 1..offset * 6 + 6).toList() },
            listOf(6, 0, 0, 0, 0), 0.2, 1.2, 30, 0, List(10) { 0 },
        )
        val results = fixedPlan.targetRounds.take(roundCount).map { target ->
            LottoPriorDesignRoundResult(target, target - 1, fixedPlan.draws.count { it.roundNo < target },
                drawHash(fixedPlan.draws.filter { it.roundNo < target }),
                drawHash(fixedPlan.draws.filter { it.roundNo == target }),
                listOf(LottoPriorDesignSeedResult(42L, baselineBatch ?: batch, candidateBatch ?: batch,
                    fixedPlan.controlSeeds.firstOrNull(), if (fixedPlan.controlSeeds.isEmpty()) null else baselineBatch ?: batch)))
        }
        results.forEach { append(rounds, mapOf("round" to call(LottoDesignRunStore.Companion, "roundJson", it))) }
        if (success) append(events, mapOf(
            "event" to "SUCCESS", "stage" to "fixture_success", "roundCount" to results.size,
            "lastRoundHash" to call(rounds, "getLastHash"), "baselineRate" to results.map { it.baselineRate }.average(),
            "candidateRate" to results.map { it.candidateRate }.average(), "pairedRateDifference" to results.map { it.pairedRateDifference }.average(),
        ))
        if (terminal != null) {
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            append(events, mapOf("event" to terminal, "stage" to "fixture_failure",
                "error" to call(LottoDesignRunStore.Companion, "exceptionJson", requireNotNull(error), seen)))
        }
        return Fixture(id, directory, events, results)
    }

    private fun batch(games: List<List<Int>>): LottoPriorDesignBatch {
        val matches = games.map { game -> game.count { it in 1..6 } }
        val coverage = games.flatten().distinct().size
        return LottoPriorDesignBatch(games, matches, matches.count { it >= 3 } / 5.0, matches.average(), coverage, 30 - coverage,
            games.indices.flatMap { first -> (first + 1 until 5).map { second -> games[first].count { it in games[second] } } })
    }

    private fun drawHash(draws: List<LottoPriorDesignDraw>) = LottoDesignRecordJson.hash(draws.joinToString("|") {
        "${it.roundNo}:${it.numbers.joinToString(",")}:${it.bonusNumber}"
    }.toByteArray())
    private fun snapshot(directory: File) = directory.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(directory).path to LottoDesignRecordJson.hash(it.readBytes())
    }
    private fun objectMap(value: Any?) = LottoDesignRecordJson.objectValue(value)
    private fun read(file: File) = LottoDesignRecordJson.decode(file.readBytes())
    private fun bytes(value: Any?) = LottoDesignRecordJson.encode(value).toByteArray(Charsets.UTF_8)
    private fun immutable(file: File, value: Any?) = immutableBytes(file, bytes(value))
    private fun immutableBytes(file: File, value: ByteArray) { call(LottoDesignRunStore.Companion, "writeImmutable", file, value) }
    private fun append(journal: Any, value: Map<String, Any?>) { call(journal, "append", value) }

    // 번호 생성 진입점을 사용하지 않고 기존 private 기록 단계만 검증한다.
    private fun call(receiver: Any, name: String, vararg args: Any?): Any? {
        val method = receiver.javaClass.declaredMethods.single { it.name.substringBefore('$') == name && it.parameterCount == args.size }
        method.isAccessible = true
        return try { method.invoke(receiver, *args) } catch (error: InvocationTargetException) { throw error.targetException }
    }

    private fun failure(block: () -> Unit): Throwable {
        try { block() } catch (error: Exception) { return error }
        throw AssertionError("기록 단계가 예외를 전파해야 합니다.")
    }
}
