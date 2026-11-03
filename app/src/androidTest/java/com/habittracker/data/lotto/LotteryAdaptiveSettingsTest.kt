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
        }
    }
    @After fun cleanup() { directory.deleteRecursively() }
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
}
