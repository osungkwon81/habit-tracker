package com.habittracker.data.lotto

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LotteryAdaptiveSettingsTest {
    private lateinit var directory: File
    private lateinit var context: Context
    @Before fun setup() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(base.cacheDir, "adaptive-${UUID.randomUUID()}").apply { check(mkdirs()) }
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
                base.getSharedPreferences("${directory.name}-$name", mode)
        }
    }
    @After fun cleanup() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteSharedPreferences("${directory.name}-lottery-auto-improvement")
        directory.deleteRecursively()
    }
    @Test fun oneGoodHalfDoesNotPermitApplication() {
        val zero = List(12) { 0.0 }
        val base = List(12) { 1.0 }
        val candidate = List(6) { 1.5 } + List(6) { 0.9 }
        assertFalse(LotteryAdaptiveSettings.acceptsTrial(zero, zero, zero, base, candidate, base))
    }
    @Test fun primaryLossCannotBeHiddenBySecondaryGain() {
        val baseline = List(12) { 0.2 }
        val candidate = List(12) { 0.0 }
        assertFalse(LotteryAdaptiveSettings.acceptsTrial(baseline, candidate, candidate, List(12) { 1.0 }, List(12) { 2.0 }, List(12) { 1.0 }))
    }
    @Test fun noFinalWinsCannotBeHiddenBySecondaryGain() {
        val zero = List(12) { 0.0 }
        assertFalse(LotteryAdaptiveSettings.acceptsTrial(zero, zero, zero, List(12) { 1.0 }, List(12) { 1.2 }, List(12) { 1.0 }))
    }
    @Test fun finalWinRateMustImproveInBothHalves() {
        assertTrue(LotteryAdaptiveSettings.acceptsTrial(List(12) { 0.1 }, List(12) { 0.2 }, List(12) { 0.1 }, List(12) { 1.0 }, List(12) { 1.2 }, List(12) { 1.0 }))
        assertFalse(LotteryAdaptiveSettings.acceptsTrial(List(12) { 0.1 }, List(6) { 0.2 } + List(6) { 0.1 }, List(12) { 0.1 }, List(12) { 1.0 }, List(12) { 1.2 }, List(12) { 1.0 }))
    }
    @Test fun winningPoolDoesNotMakeLosingFinalRecommendationSuccessful() {
        val product = LotteryProduct.LOTTO_645
        val draw = com.habittracker.data.local.entity.LottoDrawEntity(roundNo = 1, number1 = 1, number2 = 2, number3 = 3, number4 = 4, number5 = 5, number6 = 6, bonusNumber = 7)
        val input = LotteryResearchInput(product, lotto = listOf(draw))
        val stage = mapOf("pool" to listOf("1,2,3,4,5,6", "10,11,12,13,14,15"), "ranked" to listOf(0, 1), "selected" to listOf(1))
        val frozen = mapOf("product" to product.name, "target" to 1, "inputHash" to LottoDesignRecordJson.hash(input.through(0).generationJson()),
            "preDraw" to true, "models" to listOf("baseline", "candidate", "control").associateWith { mapOf("stages" to listOf(stage)) })
        val result = LotteryCandidateArchive(context).evaluate(frozen, input, 1)
        assertEquals(0.0, LottoDesignRecordJson.decimal(result.getValue("candidateRate")), 0.0)
    }
    @Test fun holdDoesNotOverrideExistingOperatingPolicy() = runBlocking {
        val store = LotteryAdaptiveSettings(context)
        store.append(LotteryProduct.LOTTO_645, "HOLD", 32.0, 32.0, emptyMap())
        assertNull(store.value(LotteryProduct.LOTTO_645))
    }
    @Test fun pensionPoolWinnerDoesNotCountWhenFinalNumberLoses() {
        val product = LotteryProduct.PENSION_720
        val draw = com.habittracker.data.local.entity.PensionLotteryDrawEntity(1, 1, "123456", null)
        val input = LotteryResearchInput(product, pension = listOf(draw))
        val stage = mapOf("pool" to listOf("123456", "654321"), "ranked" to listOf(0, 1), "selected" to listOf(1))
        val frozen = mapOf("product" to product.name, "target" to 1, "inputHash" to LottoDesignRecordJson.hash(input.through(0).generationJson()),
            "preDraw" to true, "models" to listOf("baseline", "candidate", "control").associateWith { mapOf("stages" to listOf(stage)) })
        assertEquals(0.0, LottoDesignRecordJson.decimal(LotteryCandidateArchive(context).evaluate(frozen, input, 1).getValue("candidateRate")), 0.0)
    }
    @Test fun applicationSurvivesReloadAndRollbackPreservesHistory() = runBlocking {
        val product = LotteryProduct.PENSION_720
        val store = LotteryAdaptiveSettings(context)
        val applied = store.append(product, "APPLY", 1.0, 0.5, mapOf("through" to 100))
        val reloaded = LotteryAdaptiveSettings(context)
        assertEquals(0.5, requireNotNull(reloaded.value(product)), 0.0)
        reloaded.append(product, "ROLLBACK", 0.5, 1.0, mapOf("application" to applied.getValue("recordHash")))
        assertNull(reloaded.active(product))
        assertEquals(1.0, requireNotNull(reloaded.value(product)), 0.0)
        assertEquals(2, File(directory, "lottery-adaptive-settings/${product.name}").listFiles()!!.size)
    }
    @Test fun corruptedRecordDoesNotSilentlyUseDefault() = runBlocking {
        val product = LotteryProduct.LOTTO_645
        val store = LotteryAdaptiveSettings(context)
        store.append(product, "APPLY", 32.0, 64.0, emptyMap())
        File(directory, "lottery-adaptive-settings/${product.name}/00000001.json").writeText("{}")
        try { store.value(product); fail("손상된 설정을 기본값으로 대체하면 안 됩니다.") }
        catch (error: Exception) { assertTrue(error.message?.isNotBlank() == true) }
    }

    @Test fun omittedWinnerHasOriginalRankAndScoreBreakdown() {
        val product = LotteryProduct.LOTTO_645
        val input = patternInput(1)
        val stage = mapOf("pool" to listOf("1,2,3,4,5,6", "10,11,12,13,14,15"),
            "ranked" to listOf(1), "selected" to listOf(1), "order" to listOf(1, 0),
            "allScores" to listOf(listOf(40.0, 20.0), listOf(60.0, 50.0)), "scoreColumns" to listOf("종합", "데이터"))
        val frozen = mapOf("product" to product.name, "target" to 1,
            "inputHash" to LottoDesignRecordJson.hash(input.through(0).generationJson()), "preDraw" to true,
            "models" to listOf("baseline", "candidate", "control").associateWith { mapOf("stages" to listOf(stage)) })
        val result = LotteryCandidateArchive(context).evaluate(frozen, input, 1)
        val models = LottoDesignRecordJson.objectValue(result.getValue("models"))
        val candidate = LottoDesignRecordJson.objectValue(models.getValue("candidate"))
        val evaluatedStage = LottoDesignRecordJson.objectValue(LottoDesignRecordJson.list(candidate.getValue("stages")).single())
        assertEquals("RANKING", evaluatedStage["exactLoss"])
        val diagnostic = LottoDesignRecordJson.objectValue(evaluatedStage.getValue("diagnostics"))
        val example = LottoDesignRecordJson.objectValue(LottoDesignRecordJson.list(diagnostic.getValue("examples")).single())
        assertEquals(2, example["rank"])
        assertEquals(listOf(40.0, 20.0), example["scores"])
        assertEquals(60.0, example["cutoffScore"])
    }

    @Test fun patternProposalNeedsFutureRoundsAndReloadsAppliedModel() = runBlocking {
        for (product in LotteryProduct.entries) {
            val value = if (product == LotteryProduct.LOTTO_645) 32.0 else 1.0
            context.getSharedPreferences("lottery-auto-improvement", Context.MODE_PRIVATE).edit().putBoolean(product.name, true).commit()
            val store = LotteryPatternEvolution(context)
            store.update(patternInput(2, product), emptyList(), value)
            assertTrue(store.model(product).weights.isEmpty())
            val proposal = requireNotNull(store.trial(product))
            store.update(patternInput(14, product), patternEvaluations(proposal, 3..14), value)
            assertFalse(store.model(product).weights.isEmpty())
            assertEquals(store.model(product), LotteryPatternEvolution(context).model(product))
            store.rollback(product)
            assertTrue(LotteryPatternEvolution(context).model(product).weights.isEmpty())
        }
    }

    @Test fun retrospectivePatternResultsCannotApplyModel() = runBlocking {
        val product = LotteryProduct.LOTTO_645
        context.getSharedPreferences("lottery-auto-improvement", Context.MODE_PRIVATE).edit().putBoolean(product.name, true).commit()
        val store = LotteryPatternEvolution(context)
        store.update(patternInput(2), emptyList(), 32.0)
        val proposal = requireNotNull(store.trial(product))
        store.update(patternInput(14), patternEvaluations(proposal, 3..14).map { it + ("preDraw" to false) }, 32.0)
        assertTrue(store.model(product).weights.isEmpty())
        assertNull(store.trial(product))
    }

    @Test fun correctedTrainingInputRestoresPreviousPatternModel() = runBlocking {
        val product = LotteryProduct.LOTTO_645
        context.getSharedPreferences("lottery-auto-improvement", Context.MODE_PRIVATE).edit().putBoolean(product.name, true).commit()
        val store = LotteryPatternEvolution(context)
        store.update(patternInput(2), emptyList(), 32.0)
        val proposal = requireNotNull(store.trial(product))
        val input = patternInput(14)
        store.update(input, patternEvaluations(proposal, 3..14), 32.0)
        assertFalse(store.model(product).weights.isEmpty())
        val corrected = input.copy(lotto = input.lotto.map { if (it.roundNo == 1) it.copy(number6 = 8) else it })
        store.update(corrected, emptyList(), 32.0)
        assertTrue(store.model(product).weights.isEmpty())
    }

    @Test fun userPoolsAreRecordedWithoutPurchaseAndEvaluatedOnceForBothProducts() = runBlocking {
        for (product in LotteryProduct.entries) {
            val before = patternInput(1, product)
            val clock = lotteryEvaluationDeadline(product, 2).minusHours(1)
            val archive = LotteryCandidateArchive(context) { clock }
            val first = archive.recordUserGeneration(before, "fixture", "{}", "직접 생성", 42L, clock,
                listOf(userStage(product)), resultAlreadyKnown = false)
            val second = archive.recordUserGeneration(before, "fixture", "{}", "직접 재생성", 43L, clock,
                listOf(userStage(product)), resultAlreadyKnown = false)
            assertNotEquals(first, second)
            val input = patternInput(2, product)
            assertTrue(archive.evaluateUserPending(input))
            assertFalse(archive.evaluateUserPending(input))
            val results = archive.evaluations(input)
            assertEquals(2, results.size)
            assertEquals(setOf(first, second), results.map { it["userRunId"] }.toSet())
            for (result in results) {
                assertEquals(true, result["preDraw"])
                val user = LottoDesignRecordJson.objectValue(LottoDesignRecordJson.objectValue(result.getValue("models")).getValue("user"))
                val stage = LottoDesignRecordJson.objectValue(LottoDesignRecordJson.list(user.getValue("stages")).single())
                assertEquals("RANKING", stage["exactLoss"])
                assertEquals(1, LottoDesignRecordJson.objectValue(stage.getValue("diagnostics"))["rankingLostHits"])
            }
            assertTrue(requireNotNull(archive.summary(product)).contains("직접 생성 후보"))
        }
    }

    @Test fun userRegistrationFinishingAfterDeadlineIsExcludedFromLearning() = runBlocking {
        val product = LotteryProduct.LOTTO_645
        val deadline = lotteryEvaluationDeadline(product, 2)
        var reads = 0
        val archive = LotteryCandidateArchive(context) { if (reads++ == 0) deadline.minusSeconds(1) else deadline.plusSeconds(1) }
        archive.recordUserGeneration(patternInput(1), "fixture", "{}", "직접 생성", 42L, deadline.minusSeconds(2),
            listOf(userStage(product)), resultAlreadyKnown = false)
        archive.evaluateUserPending(patternInput(2))
        assertEquals(false, archive.evaluations(patternInput(2)).single()["preDraw"])
    }

    @Test fun uncommittedUserPoolCannotBecomeLearningEvidence() = runBlocking {
        val product = LotteryProduct.PENSION_720
        val clock = lotteryEvaluationDeadline(product, 2).minusMinutes(1)
        val archive = LotteryCandidateArchive(context) { clock }
        val id = archive.recordUserGeneration(patternInput(1, product), "fixture", "{}", "직접 생성", 42L, clock,
            listOf(userStage(product)), resultAlreadyKnown = false)
        assertTrue(File(directory, "lottery-candidates/${product.name}/user/$id.committed.gz").delete())
        assertFalse(archive.evaluateUserPending(patternInput(2, product)))
        assertTrue(archive.evaluations(patternInput(2, product)).isEmpty())
    }

    @Test fun repeatedUserRunsAndAutomaticResultsStillCountAsOneRound() = runBlocking {
        val product = LotteryProduct.LOTTO_645
        context.getSharedPreferences("lottery-auto-improvement", Context.MODE_PRIVATE).edit().putBoolean(product.name, true).commit()
        val stage = mapOf("generated" to mapOf("rate" to 0.1, "count" to 100),
            "patternOutcomes" to mapOf("홀수=3" to mapOf("hits" to 20, "count" to 100)))
        val evaluations = (1..3).map { index -> mapOf("round" to 1, "preDraw" to true, "candidateHash" to "fixture-$index",
            "observationSource" to if (index == 3) "automatic" else "user",
            "models" to mapOf((if (index == 3) "baseline" else "user") to mapOf("stages" to listOf(stage)))) }
        val store = LotteryPatternEvolution(context)
        store.update(patternInput(2), evaluations, 32.0)
        val proposal = requireNotNull(store.trial(product))
        assertEquals(1, LottoDesignRecordJson.objectValue(proposal.getValue("outcomeRoundCounts"))["홀수=3"])
        assertEquals(listOf(1), proposal["userObservationRounds"])
        assertEquals(3, LottoDesignRecordJson.list(proposal.getValue("trainingEvaluationHashes")).size)
    }

    @Test fun userResultCorrectionPreservesEarlierEvaluation() = runBlocking {
        val product = LotteryProduct.LOTTO_645
        val clock = lotteryEvaluationDeadline(product, 2).minusHours(1)
        val archive = LotteryCandidateArchive(context) { clock }
        archive.recordUserGeneration(patternInput(1), "fixture", "{}", "직접 생성", 42L, clock,
            listOf(userStage(product)), resultAlreadyKnown = false)
        val input = patternInput(2)
        archive.evaluateUserPending(input)
        val original = archive.evaluations(input).single()
        val corrected = input.copy(lotto = input.lotto.map { if (it.roundNo == 2) it.copy(number6 = 8) else it })
        assertTrue(archive.evaluateUserPending(corrected))
        assertNotEquals(original["targetDataHash"], archive.evaluations(corrected).single()["targetDataHash"])
        assertEquals(2, File(directory, "lottery-candidates/${product.name}/user").listFiles()!!.count { it.name.endsWith(".evaluation.gz") })
    }

    @Test fun userPatternOutcomeRatesChangeProposedWeights() = runBlocking {
        val product = LotteryProduct.LOTTO_645
        context.getSharedPreferences("lottery-auto-improvement", Context.MODE_PRIVATE).edit().putBoolean(product.name, true).commit()
        val stage = mapOf("generated" to mapOf("rate" to 0.1, "count" to 100),
            "patternOutcomes" to mapOf("홀수=3" to mapOf("hits" to 20, "count" to 100)))
        val results = (1..2).map { round -> mapOf("round" to round, "preDraw" to true,
            "candidateHash" to "user-$round", "observationSource" to "user",
            "models" to mapOf("user" to mapOf("stages" to listOf(stage)))) }
        val store = LotteryPatternEvolution(context)
        store.update(patternInput(3), results, 32.0)
        val proposal = requireNotNull(store.trial(product))
        val model = LotteryPatternModel.from(proposal.getValue("after"))
        assertEquals(10.0 * ((0.2 - 0.1) / 0.11) * 2.0 / 4.0, requireNotNull(model.weights["홀수=3"]), 1e-9)
        assertEquals(listOf(1, 2), proposal["userObservationRounds"])
    }

    @Test fun candidateAndPatternStoresAcceptFilesDirectoryAlias() = runBlocking {
        val physical = File(directory, "physical-files").apply { check(mkdirs()) }
        val alias = File(directory, "files-alias")
        android.system.Os.symlink(physical.canonicalPath, alias.absolutePath)
        val aliasedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = alias
        }
        for (product in LotteryProduct.entries) {
            val clock = lotteryEvaluationDeadline(product, 2).minusHours(1)
            val archive = LotteryCandidateArchive(aliasedContext) { clock }
            val id = archive.recordUserGeneration(patternInput(1, product), "fixture", "{}", "직접 생성", 42L,
                clock, listOf(userStage(product)), resultAlreadyKnown = false)
            assertTrue(File(physical, "lottery-candidates/${product.name}/user/$id.json.gz").isFile)
            assertTrue(archive.evaluateUserPending(patternInput(2, product)))
            assertEquals(1, archive.evaluations(patternInput(2, product)).size)
            aliasedContext.getSharedPreferences("lottery-auto-improvement", Context.MODE_PRIVATE)
                .edit().putBoolean(product.name, true).commit()
            val store = LotteryPatternEvolution(aliasedContext)
            store.update(patternInput(2, product), archive.evaluations(patternInput(2, product)),
                if (product == LotteryProduct.LOTTO_645) 32.0 else 1.0)
            assertNotNull(LotteryPatternEvolution(aliasedContext).trial(product))
        }
    }

    @Test fun candidateStoreStillRejectsRedirectedSubdirectory() = runBlocking {
        val redirected = File(directory, "redirected").apply { check(mkdirs()) }
        android.system.Os.symlink(redirected.canonicalPath, File(directory, "lottery-candidates").absolutePath)
        val product = LotteryProduct.LOTTO_645
        val clock = lotteryEvaluationDeadline(product, 2).minusHours(1)
        try {
            LotteryCandidateArchive(context) { clock }.recordUserGeneration(patternInput(1), "fixture", "{}",
                "직접 생성", 42L, clock, listOf(userStage(product)), resultAlreadyKnown = false)
            fail("저장소 아래에서 다른 경로로 연결된 폴더를 허용하면 안 됩니다.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("후보 기록 저장 경로"))
        }
    }

    private fun userStage(product: LotteryProduct): Map<String, Any?> = mapOf(
        "pool" to if (product == LotteryProduct.LOTTO_645) listOf("1,2,3,4,5,6", "10,11,12,13,14,15") else listOf("123456", "654321"),
        "ranked" to listOf(1), "selected" to listOf(1), "order" to listOf(1, 0),
        "scoreColumns" to listOf("종합", "패턴"), "allScores" to listOf(listOf(40.0, 20.0), listOf(60.0, 50.0)))

    private fun patternInput(through: Int, product: LotteryProduct = LotteryProduct.LOTTO_645): LotteryResearchInput =
        if (product == LotteryProduct.LOTTO_645) LotteryResearchInput(product,
            lotto = (1..through).map { round -> com.habittracker.data.local.entity.LottoDrawEntity(roundNo = round,
                number1 = 1, number2 = 2, number3 = 3, number4 = 4, number5 = 5, number6 = 6, bonusNumber = 7) })
        else LotteryResearchInput(product, pension = (1..through).map { round ->
            com.habittracker.data.local.entity.PensionLotteryDrawEntity(round, 1, "123456", null) })

    private fun patternEvaluations(proposal: Map<String, Any?>, rounds: IntRange): List<Map<String, Any?>> = rounds.map { round ->
        mapOf("round" to round, "preDraw" to true, "patternTrialId" to proposal.getValue("trialId"),
            "models" to listOf("patternBaseline", "patternCandidate", "patternDiversifiedBaseline", "patternDiversifiedCandidate", "control").associateWith { role ->
                mapOf("rate" to if (role.endsWith("Candidate")) 0.2 else 0.1,
                    "secondary" to if (role.endsWith("Candidate")) 1.2 else 1.0)
            })
    }
}
