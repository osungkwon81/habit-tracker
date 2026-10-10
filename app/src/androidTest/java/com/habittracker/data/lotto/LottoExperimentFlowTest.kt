package com.habittracker.data.lotto

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LottoExperimentFlowTest {
    private val draws = (62 downTo 1).map { LottoPriorDesignDraw(it, (1..6).toList(), 7) }
    private fun plan(input: List<LottoPriorDesignDraw> = draws) = LottoPriorDesignComparison.prepare(input, 1, listOf(61), listOf(42L), listOf(43L))

    @Test
    fun operatingBasicMatchesBaselineAndFutureChangesDoNotLeak() {
        val baseline = plan()
        val history = baseline.draws.filter { it.roundNo < 61 }.map { it.numbers }
        val operating = LottoNumberGenerator.generateBalanced(history, mode = LottoGenerationMode.BASIC, seed = 42L, historyThroughRound = 60)
        val design = LottoNumberGenerator.generateBalancedForPriorDesign(history, 42L, 60, 32.0)
        assertEquals(5, design.size)
        assertEquals(operating.map { it.numbers }, design.map { it.numbers })
        val changed = plan(draws.map { if (it.roundNo >= 61) it.copy(numbers = (38..43).toList(), bonusNumber = 44) else it })
        val changedHistory = changed.draws.filter { it.roundNo < 61 }.map { it.numbers }
        assertEquals(history, changedHistory)
        assertEquals(design.map { it.numbers }, LottoNumberGenerator.generateBalancedForPriorDesign(changedHistory, 42L, 60, 32.0).map { it.numbers })
    }

    @Test
    fun configurationDiffIsOnlyRecentPriorAndControlIsUniformGameShape() {
        val baseline = plan().baseline.configJson
        val candidate = plan().candidate.configJson
        assertEquals(baseline.replace("\"priorDraws\": 32.0", "\"priorDraws\": 64.0"), candidate)
        val games = LottoPriorDesignComparison.uniformGames(43L)
        assertEquals(5, games.size)
        assertTrue(games.all { it.size == 6 && it == it.distinct().sorted() && it.all { number -> number in 1..45 } })
        assertEquals(games, LottoPriorDesignComparison.uniformGames(43L))
    }

    @Test
    fun invalidInputsAreRejectedBeforeGeneration() {
        expect<IllegalArgumentException> { plan(draws.filter { it.roundNo != 30 }) }
        expect<IllegalArgumentException> { plan(draws + draws.last()) }
        expect<IllegalArgumentException> { plan(draws.map { if (it.roundNo == 30) it.copy(numbers = listOf(1, 1, 2, 3, 4, 5)) else it }) }
    }

    @Test
    fun completedRoundRecordFailureAndCancellationPropagate() = runBlocking {
        val original = IOException("fixture record write failed")
        var completedCount = 0
        try {
            LottoPriorDesignComparison.compare(plan()) { completed ->
                assertEquals(61, completed.targetRoundNo)
                assertNotNull(completed.controlRate)
                completedCount++
                throw original
            }
            fail("부분 결과를 성공으로 반환해서는 안 됩니다.")
        } catch (error: LottoPriorDesignFailure) {
            assertSame(original, error.cause)
            assertEquals(61, error.targetRoundNo)
            assertEquals("round_record", error.stage)
        }
        assertEquals(1, completedCount)
        val cancellation = CancellationException("fixture cancellation")
        try {
            LottoPriorDesignComparison.compare(plan()) { throw cancellation }
            fail("취소를 성공으로 반환해서는 안 됩니다.")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
            assertTrue(error.suppressed.any { it.message.orEmpty().contains("회차=61") && it.message.orEmpty().contains("단계=round_record") })
        }
    }

    @Test
    fun unconfirmedAndDesignPoliciesBlockAdoptionAndZeroSuccessHasUncertainty() {
        val spec = mapOf("purpose" to "FINAL", "targetRounds" to listOf(61), "policy" to null)
        assertEquals(LottoExperimentDecision.POLICY_UNCONFIRMED, LottoExperimentPolicy.judge(spec, null).decision)
        val policy = mapOf("confirmedByUser" to true, "confirmedAt" to "2026-10-05T00:00:00Z",
            "primaryMetric" to "main-three-plus-game-proportion", "confidenceLevel" to 0.95,
            "minimumEffect" to 0.0025, "finalRoundCount" to 1, "familyComparisonCount" to 2,
            "intervalMethod" to LottoExperimentPolicy.METHOD, "assumptions" to "synthetic fixture only",
            "sampleSizeRationale" to "not a real sample-size decision", "rejectWhenUpperNonPositive" to true,
            "singleFinalLook" to true, "noEarlyAdoption" to true)
        assertEquals(LottoExperimentDecision.DESIGN_ONLY, LottoExperimentPolicy.judge(spec + mapOf("purpose" to "DESIGN", "policy" to policy), null).decision)
        assertEquals(LottoExperimentDecision.HOLD, LottoExperimentPolicy.judge(spec + ("policy" to policy), null).decision)
        val interval = LottoExperimentPolicy.interval(List(20) { 0.0 }, 0.025)
        assertTrue(interval.lower < 0 && interval.upper > 0)
    }

    @Test
    fun approvedLedgerIsReadAndRollbackPreservesRecords() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val temp = File(base.cacheDir, "lotto-approval-fixture-${UUID.randomUUID()}").canonicalFile.apply { check(mkdir()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = temp
        }
        val store = LottoExperimentStore(context)
        assertEquals(32.0, store.approvedSetting().priorDraws, 0.0)
        // 실제 채택을 꾸미지 않고 승인 저장 단계만 고립된 fixture에 호출한다.
        invoke(store, "appendSetting", 64.0, "APPLY", "fixture approval", mapOf("fixtureOnly" to true))
        val first = File(temp, "lotto-experiments/operating-settings/00000001.json")
        val original = first.readBytes()
        assertEquals(64.0, store.approvedSetting().priorDraws, 0.0)
        val database = androidx.room.Room.inMemoryDatabaseBuilder(context, com.habittracker.data.local.HabitTrackerDatabase::class.java).build()
        val protector = com.habittracker.data.local.HabitTrackerDatabaseProtector(context)
        try {
            val dao = database.habitDao()
            dao.insertLottoDraws(listOf(com.habittracker.data.local.entity.LottoDrawEntity.from(60, (1..6).toList(), 7)))
            val repository = com.habittracker.data.repository.HabitRepository(context, database, protector, dao)
            val applied = repository.getLottoGenerationSnapshot(forBalancedGeneration = true, mode = LottoGenerationMode.BASIC)
            assertEquals(64.0, applied.balancedRecentPriorDraws, 0.0)
            assertTrue(applied.configJson.contains("operatingApproval"))
            assertEquals(32.0, repository.getLottoGenerationSnapshot(forBalancedGeneration = true, mode = LottoGenerationMode.PRECISE).balancedRecentPriorDraws, 0.0)
            store.rollback("fixture rollback")
            val restored = repository.getLottoGenerationSnapshot(forBalancedGeneration = true, mode = LottoGenerationMode.BASIC)
            assertEquals(32.0, restored.balancedRecentPriorDraws, 0.0)
            assertEquals(applied.inputDataHash, restored.inputDataHash)
            assertEquals(64.0, applied.balancedRecentPriorDraws, 0.0)
        } finally { protector.shutdown(); database.close() }
        assertEquals(32.0, store.approvedSetting().priorDraws, 0.0)
        assertArrayEquals(original, first.readBytes())
        val second = File(first.parentFile, "00000002.json")
        assertTrue(second.isFile)
        first.writeText("damaged fixture")
        try { store.approvedSetting(); fail("손상된 승인 이력을 기본값으로 대체하면 안 됩니다.") }
        catch (_: IllegalArgumentException) { }
        println("LOTTO_APPROVAL_FIXTURE_ROOT=${temp.path}")
    }

    private inline fun <reified T : Exception> expect(block: () -> Unit) {
        try { block() } catch (error: Exception) { assertTrue(error is T); return }
        fail("${T::class.java.simpleName}이 필요합니다.")
    }
    private fun invoke(receiver: Any, name: String, vararg args: Any?): Any? {
        val method = receiver.javaClass.declaredMethods.single { it.name.substringBefore('$') == name && it.parameterCount == args.size }
        method.isAccessible = true
        return try { method.invoke(receiver, *args) } catch (error: InvocationTargetException) { throw error.targetException }
    }
}
