package com.habittracker.data.lotto

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.habittracker.data.local.entity.PensionLotteryDrawEntity
import com.habittracker.ui.lotto.PensionGenerationTrace
import com.habittracker.ui.lotto.generatePensionExperimentBatch
import com.habittracker.ui.lotto.generatePensionOperatingBatch
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PensionExperimentFlowTest {
    private fun draws(last: Int = 60) = (last downTo 1).map { PensionLotteryDrawEntity(it, it % 5 + 1, (it * 7919 % 1_000_000).toString().padStart(6, '0')) }
    private val target get() = draws(61).first()

    @Test fun operatingAndUninstrumentedGeneratorMatchBaseline() = runBlocking {
        val fixture = PensionRecordFixture()
        val history = draws(); val excluded = setOf("123456")
        val operating = generatePensionOperatingBatch(fixture.store, history, excluded, Random(42L))
        val baseline = generatePensionExperimentBatch(history, 3, 42L, excluded, PensionGenerationTrace())
        assertEquals(operating.map { it.groupNo to it.winningNumber }, baseline.map { it.groupNo to it.winningNumber })
        val owner = Class.forName("com.habittracker.ui.lotto.PensionLotteryGeneratorViewModelKt")
        val analysisMethod = owner.declaredMethods.single { it.name == "buildGeneratorAnalysis" }.apply { isAccessible = true }
        val analysis = analysisMethod.invoke(null, history, 3, null)
        val generate = owner.declaredMethods.single { it.name == "generateCandidateSet" }.apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val original = generate.invoke(null, analysis, excluded, Random(42L), null) as List<com.habittracker.ui.lotto.PensionLotteryGeneratedNumber>
        assertEquals(original.map { it.groupNo to it.winningNumber }, baseline.map { it.groupNo to it.winningNumber })
        assertEquals(4, baseline.size)
        assertEquals(PensionExperimentComparison.configuration(3), LottoDesignRecordJson.decode(requireNotNull(baseline.first().generationSnapshot).configJson.toByteArray(), false))
    }

    @Test fun onlyLastDigitCountChangesAndControlIsReproducible() {
        val baseline = PensionExperimentComparison.configuration(3); val candidate = PensionExperimentComparison.configuration(4)
        assertEquals(baseline - "appearedLastDigitCandidateCount", candidate - "appearedLastDigitCandidateCount")
        assertEquals(3, baseline["appearedLastDigitCandidateCount"]); assertEquals(4, candidate["appearedLastDigitCandidateCount"])
        val numbers = PensionExperimentComparison.uniform(43L)
        assertEquals(numbers, PensionExperimentComparison.uniform(43L)); assertEquals(4, numbers.size)
        assertEquals(4, numbers.map { it["number"] }.distinct().size)
        assertTrue(numbers.all { it["number"].toString().matches(Regex("[0-9]{6}")) && (it["groupNo"] as Int) in 1..5 })
    }

    @Test fun frozenInputPreventsFutureLeakAndModelExclusionsAreIndependent() = runBlocking {
        val frozen = PensionExperimentComparison.input(draws(), setOf("123456", "987654"))
        val altered = draws(62).map { if (it.roundNo >= 61) it.copy(winningNumber = "999999") else it }
        assertEquals(frozen, PensionExperimentComparison.input(altered.filter { it.roundNo < 61 }, setOf("123456", "987654")))
        val models = mutableListOf<Map<String, Any?>>()
        val result = PensionExperimentComparison.compare(target, frozen, listOf(42L), listOf(43L)) { models += it }
        val candidate = generatePensionExperimentBatch(draws(), 4, 42L, setOf("123456", "987654"), PensionGenerationTrace())
        val recorded = LottoDesignRecordJson.objectValue(models.single { it["model"] == "CANDIDATE" }.getValue("batch"))
        assertEquals(candidate.map { it.winningNumber }, LottoDesignRecordJson.list(recorded.getValue("numbers")).map { LottoDesignRecordJson.objectValue(it)["number"] })
        assertEquals(3, models.size); assertEquals(61, result["targetRound"])
        assertEquals(frozen, PensionExperimentComparison.input(draws(), setOf("123456", "987654")))
    }

    @Test fun invalidHistoryAndNumbersAreRejectedBeforeGeneration() {
        expect { PensionExperimentComparison.input(draws().filter { it.roundNo != 30 }, emptySet()) }
        expect { PensionExperimentComparison.input(draws() + draws().last(), emptySet()) }
        expect { PensionExperimentComparison.input(draws(), setOf("１２３４５６")) }
    }

    @Test fun recordFailureAndCancellationPreserveContextAndNeverReturnPartialSuccess() = runBlocking {
        val input = PensionExperimentComparison.input(draws(), emptySet())
        val writeError = IOException("fixture recording failure")
        try { PensionExperimentComparison.compare(target, input, listOf(42L), listOf(43L)) { throw writeError }; fail("부분 성공 금지") }
        catch (error: PensionComparisonFailure) { assertSame(writeError, error.cause); assertEquals(61, error.round); assertEquals("BASELINE", error.model); assertEquals(42L, error.seed); assertEquals("model_record", error.stage); assertTrue(error.diagnostics.isNotEmpty()) }
        val cancelled = CancellationException("fixture cancellation")
        try { PensionExperimentComparison.compare(target, input, listOf(42L), listOf(43L)) { throw cancelled }; fail("취소를 성공으로 반환하면 안 됩니다.") }
        catch (error: CancellationException) { assertSame(cancelled, error); assertTrue(error.suppressed.any { it is PensionComparisonFailure && it.stage == "model_record" && it.round == 61 }) }
    }

    @Test fun generationFailureIsNotReducedBatchAndIncludesAttempts() = runBlocking {
        val impossible = (20 downTo 1).map { PensionLotteryDrawEntity(it, 1, "111111") }
        try { PensionExperimentComparison.compare(PensionLotteryDrawEntity(21, 2, "222222"), PensionExperimentComparison.input(impossible, emptySet()), listOf(42L), listOf(43L)) { fail("실패한 배치를 완료 기록하면 안 됩니다.") }; fail("부분 추천 금지") }
        catch (error: PensionComparisonFailure) { assertEquals("generate", error.stage); assertEquals("BASELINE", error.model); assertEquals(21, error.round); assertTrue(error.diagnostics.containsKey("attempts")); assertNotNull(error.cause) }
    }

    @Test fun threeOrFewerLastDigitsAreRetainedEvenWhenSettingsCoincide() = runBlocking {
        val fixture = PensionRecordFixture()
        val history = draws().map { it.copy(winningNumber = it.winningNumber.dropLast(1) + (it.roundNo % 3).toString()) }
        val id = fixture.register("DESIGN", listOf(61), history)
        assertEquals(listOf(61), fixture.store.list().single().capturedRounds)
        val inputFile = File(fixture.root, "pension-experiments/$id/inputs/61.json")
        assertEquals(history.map { it.winningNumber }, PensionExperimentComparison.draws(LottoDesignRecordJson.objectValue(LottoDesignRecordJson.decode(inputFile.readBytes()).getValue("input"))).map { it.winningNumber })
        val owner = Class.forName("com.habittracker.ui.lotto.PensionLotteryGeneratorViewModelKt")
        val method = owner.declaredMethods.single { it.name == "buildGeneratorAnalysis" }.apply { isAccessible = true }
        val a = method.invoke(null, history, 3, null); val b = method.invoke(null, history, 4, null)
        val field = requireNotNull(a).javaClass.getDeclaredField("topAppearedLastDigits").apply { isAccessible = true }
        assertEquals(field.get(a), field.get(b))
    }

    @Test fun unknownPolicyAndDesignResultsBlockApproval() = runBlocking {
        val fixture = PensionRecordFixture()
        val id = fixture.register("DESIGN", listOf(61), draws())
        assertEquals(LottoExperimentDecision.POLICY_UNCONFIRMED, fixture.store.list().single().judgment.decision)
        try { fixture.store.approve(id, PensionExperimentInput(draws(61), emptyList()), "fixture"); fail("미확정 정책 적용 금지") } catch (_: IllegalArgumentException) { }
        val design = PensionRecordFixture()
        design.register("DESIGN", listOf(61), draws(), policy = design.policy(1))
        assertEquals(LottoExperimentDecision.DESIGN_ONLY, design.store.list().single().judgment.decision)
        assertEquals(3, fixture.store.setting().count)
        val zero = LottoExperimentPolicy.interval(List(20) { 0.0 }, 0.025)
        assertTrue(zero.lower < 0 && zero.upper > 0)
    }

    @Test fun approvalAffectsOperatingGeneratorAndRollbackPreservesLottoAndFiles() = runBlocking {
        val fixture = PensionRecordFixture()
        val lotto = LottoExperimentStore(fixture.context)
        assertEquals(32.0, lotto.approvedSetting().priorDraws, 0.0)
        assertEquals(3, fixture.store.setting().count)
        fixture.invoke(lotto, "appendSetting", 64.0, "APPLY", "isolated fixture", mapOf("fixtureOnly" to true))
        val lottoFile = File(fixture.root, "lotto-experiments/operating-settings/00000001.json"); val lottoBytes = lottoFile.readBytes()
        fixture.invoke(fixture.store, "appendSetting", 4, "APPLY", "isolated fixture", mapOf("fixtureOnly" to true))
        val first = File(fixture.root, "pension-experiments/operating-settings/00000001.json"); val bytes = first.readBytes()
        val applied = generatePensionOperatingBatch(fixture.store, draws(), emptySet(), Random(42L))
        val config = LottoDesignRecordJson.decode(requireNotNull(applied.first().generationSnapshot).configJson.toByteArray(), false)
        assertEquals(4, config["appearedLastDigitCandidateCount"]); assertEquals(fixture.store.setting().approvalHash, config["operatingApprovalHash"])
        fixture.store.rollback("fixture rollback")
        val restored = generatePensionOperatingBatch(fixture.store, draws(), emptySet(), Random(42L))
        assertEquals(3, LottoDesignRecordJson.decode(requireNotNull(restored.first().generationSnapshot).configJson.toByteArray(), false)["appearedLastDigitCandidateCount"])
        assertArrayEquals(bytes, first.readBytes()); assertArrayEquals(lottoBytes, lottoFile.readBytes()); assertEquals(64.0, lotto.approvedSetting().priorDraws, 0.0)
        first.writeText("damaged fixture")
        try { fixture.store.setting(); fail("손상된 승인 이력 기본값 대체 금지") } catch (_: IllegalArgumentException) { }
    }

    @Test fun fileExperimentsDoNotMutateExistingPurchasesOrGenerationHistory() = runBlocking {
        val fixture = PensionRecordFixture()
        val database = androidx.room.Room.inMemoryDatabaseBuilder(fixture.context, com.habittracker.data.local.HabitTrackerDatabase::class.java).build()
        val protector = com.habittracker.data.local.HabitTrackerDatabaseProtector(fixture.context)
        try {
            val dao = database.habitDao()
            val repository = com.habittracker.data.repository.HabitRepository(fixture.context, database, protector, dao)
            val row = com.habittracker.data.local.entity.PensionLotteryGeneratedNumberEntity(generationId = "existing fixture", generationType = "APPEARED", groupNo = 1,
                winningNumber = "123456", digitScores = "", totalScore = 0, scoreBand = "fixture", duplicateLabel = "fixture", coldPositions = "", coldPriorityScores = "",
                generatedAt = java.time.LocalDateTime.of(2021, 1, 1, 0, 0), isHidden = true)
            dao.insertPensionLotteryGeneratedNumbers(listOf(row))
            dao.insertLottoPurchase(com.habittracker.data.local.entity.LottoPurchaseEntity(purchaseDate = java.time.LocalDate.of(2021, 1, 1), lottoType = "연금", roundNo = 61, pensionNumber = "123456", amount = 5000))
            val oldRows = dao.observePensionLotteryGeneratedNumbers().first(); val oldPurchases = dao.getPensionLotteryPurchasesByRound(61)
            val oldLotto = repository.lottoExperiments.approvedSetting()
            fixture.register("DESIGN", listOf(61), draws(), oldRows)
            fixture.invoke(repository.pensionExperiments, "appendSetting", 4, "APPLY", "isolated fixture", mapOf("fixtureOnly" to true))
            repository.pensionExperiments.rollback("isolated fixture")
            assertEquals(oldRows, dao.observePensionLotteryGeneratedNumbers().first())
            assertEquals(oldPurchases, dao.getPensionLotteryPurchasesByRound(61))
            assertEquals(oldLotto, repository.lottoExperiments.approvedSetting())
        } finally { protector.shutdown(); database.close() }
    }

    private fun expect(block: () -> Unit) { try { block(); fail("오류 입력 거절 필요") } catch (_: IllegalArgumentException) { } }
}

internal class PensionRecordFixture {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    val root = File(base.cacheDir, "pension-experiment-fixture-${UUID.randomUUID()}").canonicalFile.apply { check(mkdir()) }
    val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = root
        override fun getDatabasePath(name: String): File = File(root, name)
    }
    var clock: Instant = Instant.parse("2021-06-30T00:00:00Z")
    val store = PensionExperimentStore(context) { clock }
    fun policy(count: Int) = mapOf("confirmedByUser" to true, "confirmedAt" to clock.minusSeconds(60).toString(),
        "primaryMetric" to "normal-suffix-two-plus-proportion", "confidenceLevel" to 0.95, "minimumEffect" to 0.002,
        "finalRoundCount" to count, "familyComparisonCount" to 2, "intervalMethod" to LottoExperimentPolicy.METHOD,
        "assumptions" to "synthetic fixture only", "sampleSizeRationale" to "not a real sample-size decision",
        "rejectWhenUpperNonPositive" to true, "singleFinalLook" to true, "noEarlyAdoption" to true)
    suspend fun register(purpose: String, targets: List<Int>, history: List<PensionLotteryDrawEntity>,
                         recommendations: List<com.habittracker.data.local.entity.PensionLotteryGeneratedNumberEntity> = emptyList(), policy: Map<String, Any?>? = null): String {
        val files = PensionExperimentStore.SOURCE_FILES.map { mapOf("path" to it, "content" to "synthetic fixture source", "sha256" to LottoDesignRecordJson.hash("synthetic fixture source".toByteArray())) }
        val source = mapOf("files" to files, "buildSourceHash" to LottoDesignRecordJson.hash(files.map { it - "content" }),
            "apkHash" to LottoDesignRecordJson.hash(File(base.applicationInfo.sourceDir).readBytes()),
            "splitApkHashes" to base.applicationInfo.splitSourceDirs.orEmpty().associate { File(it).name to LottoDesignRecordJson.hash(File(it).readBytes()) },
            "buildReference" to "synthetic isolated fixture", "hasUncommittedChanges" to true, "gitHead" to null,
            "dependencyVersions" to mapOf("kotlin" to KotlinVersion.CURRENT.toString(), "kotlinStdlib" to KotlinVersion.CURRENT.toString(), "coroutines" to "fixture"))
        val spec = mapOf("purpose" to purpose, "targetRounds" to targets, "historyStartRound" to history.minOf { it.roundNo },
            "selectionRounds" to emptyList<Int>(), "commonSeeds" to listOf("42"), "controlSeeds" to listOf("43"),
            "hypothesis" to "fixture only", "comparisonFamilyId" to "fixture-only", "historyCompletenessConfirmation" to "isolated synthetic history", "policy" to policy)
        return store.register(LottoDesignRecordJson.encode(mapOf("formatVersion" to 1, "sourceEvidence" to source, "experiment" to spec)).toByteArray(), PensionExperimentInput(history.filter { it.roundNo < targets.first() }, recommendations))
    }
    fun invoke(receiver: Any, name: String, vararg args: Any?): Any? {
        val method = receiver.javaClass.declaredMethods.single { it.name.substringBefore('$') == name && it.parameterCount == args.size }.apply { isAccessible = true }
        return try { method.invoke(receiver, *args) } catch (error: InvocationTargetException) { throw error.targetException }
    }
    fun seal(value: Map<String, Any?>): Map<String, Any?> {
        val versioned = value + ("formatVersion" to LottoDesignRecordJson.VERSION)
        return versioned + ("recordHash" to LottoDesignRecordJson.hash(versioned))
    }
    fun write(file: File, value: Map<String, Any?>) = LottoDesignRunStore.writeImmutable(file, LottoDesignRecordJson.encode(value).toByteArray())
    fun journal(directory: File, registrationHash: String): Any {
        val type = store.javaClass.declaredClasses.single { it.simpleName == "Journal" }
        return type.declaredConstructors.single().apply { isAccessible = true }.newInstance(store, directory, registrationHash)
    }
}
