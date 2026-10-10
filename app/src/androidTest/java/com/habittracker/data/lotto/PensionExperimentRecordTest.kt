package com.habittracker.data.lotto

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.habittracker.data.local.entity.PensionLotteryDrawEntity
import com.habittracker.data.local.entity.PensionLotteryGeneratedNumberEntity
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PensionExperimentRecordTest {
    private fun history(last: Int = 60, suffix: String = "99") = (last downTo 1).map { PensionLotteryDrawEntity(it, 1, if (it >= 61) "1111$suffix" else (it * 7919).toString().padStart(6, '0')) }
    private suspend fun start(fixture: PensionRecordFixture): Pair<File, Any> {
        val id = fixture.register("DESIGN", listOf(61), history(61))
        val directory = File(fixture.root, "pension-experiments/$id")
        fixture.clock = lotteryEvaluationDeadline(LotteryProduct.PENSION_720, 61).atZone(ZoneId.of("Asia/Seoul")).toInstant().plusSeconds(60)
        val spec = LottoDesignRecordJson.decode(File(directory, "registration.json").readBytes())
        fixture.write(File(directory, "execution.json"), fixture.seal(mapOf("registrationHash" to LottoDesignRecordJson.hash(spec), "startedAt" to fixture.clock.toString(), "excludedRounds" to (1..61).toList(), "targets" to emptyList<Any>())))
        val journal = fixture.journal(directory, LottoDesignRecordJson.hash(spec))
        fixture.invoke(journal, "append", mapOf("event" to "START", "stage" to "start"))
        return directory to journal
    }

    @Test fun registrationPreservesHiddenExclusionsAndRejectsOverwrites() = runBlocking {
        val fixture = PensionRecordFixture()
        val row = PensionLotteryGeneratedNumberEntity(id = 1L, generationId = "fixture", generationType = "APPEARED", groupNo = 1,
            winningNumber = "123456", digitScores = "", totalScore = 0, scoreBand = "fixture", duplicateLabel = "fixture", coldPositions = "", coldPriorityScores = "",
            generatedAt = LocalDateTime.of(2021, 1, 1, 0, 0), isHidden = true)
        val id = fixture.register("DESIGN", listOf(61), history(), listOf(row))
        val file = File(fixture.root, "pension-experiments/$id/inputs/61.json"); val original = file.readBytes()
        val proof = LottoDesignRecordJson.decode(original)
        assertEquals(listOf("123456"), LottoDesignRecordJson.objectValue(proof.getValue("input"))["exclusions"])
        val evidence = LottoDesignRecordJson.objectValue(LottoDesignRecordJson.list(proof.getValue("recommendationEvidence")).single())
        assertEquals(true, evidence["isHidden"]); assertNull(evidence["savedAt"])
        try { fixture.write(file, fixture.seal(mapOf("overwrite" to true))); fail("기존 파일 덮어쓰기 금지") } catch (_: Exception) { }
        assertArrayEquals(original, file.readBytes())
        try { fixture.register("DESIGN", listOf(61), history()); fail("동일 회차 중복 등록 금지") } catch (_: IllegalArgumentException) { }
    }

    @Test fun validButDifferentRegistrationMakesExclusionCoverageUncertain() = runBlocking {
        val fixture = PensionRecordFixture(); val (directory, _) = start(fixture)
        val file = File(directory, "registration.json"); val spec = LottoDesignRecordJson.decode(file.readBytes())
        // 손상 fixture만 수정한다. 앱의 기존 실행 폴더는 접근하지 않는다.
        file.writeText(LottoDesignRecordJson.encode(fixture.seal((spec - "recordHash") + ("hypothesis" to "different valid plan"))))
        val recovered = fixture.store.list().single()
        assertEquals("INVALID", recovered.state); assertTrue(recovered.exclusionUncertain)
        assertTrue(recovered.excludedRounds.containsAll((1..61).toList()))
    }

    @Test fun truncatedTailAndAbsentEndKeepReadablePrefixAndExcludedRounds() = runBlocking {
        val fixture = PensionRecordFixture(); val (directory, _) = start(fixture)
        val incomplete = fixture.store.list().single()
        assertEquals("INCOMPLETE", incomplete.state); assertFalse(incomplete.exclusionUncertain)
        File(directory, "events.jsonl").appendText("{\"event\":")
        val damaged = fixture.store.list().single()
        assertEquals("INVALID", damaged.state); assertEquals(1, damaged.events.size)
        assertTrue(damaged.issues.any { it.contains("JSON") || it.isNotBlank() })
        assertTrue(damaged.excludedRounds.containsAll((1..61).toList())); assertFalse(damaged.exclusionUncertain)
    }

    @Test fun failedAndCancelledExecutionsRecoverSuppressedContextAndExcludedRounds() = runBlocking {
        for (event in listOf("FAILED", "CANCELLED")) {
            val fixture = PensionRecordFixture(); val (_, journal) = start(fixture)
            val error = CancellationException("fixture cancellation").apply {
                addSuppressed(PensionComparisonFailure(61, "CANDIDATE", 42L, "generate", mapOf("attempts" to 7), IllegalStateException("fixture cause")))
            }
            fixture.invoke(journal, "append", mapOf("event" to event, "error" to LottoDesignRunStore.exceptionJson(error)))
            val recovered = fixture.store.list().single()
            assertEquals(event, recovered.state); assertTrue(recovered.excludedRounds.containsAll((1..61).toList()))
            assertTrue(LottoDesignRecordJson.encode(recovered.events.last()["error"]).contains("회차=61"))
            assertEquals(3, fixture.store.setting().count)
        }
    }

    @Test fun startRecordWriteFailureStopsBeforeAnyGeneration() = runBlocking {
        val fixture = PensionRecordFixture(); val id = fixture.register("DESIGN", listOf(61), history(61))
        val directory = File(fixture.root, "pension-experiments/$id")
        assertTrue(File(directory, "events.jsonl").mkdir())
        fixture.clock = lotteryEvaluationDeadline(LotteryProduct.PENSION_720, 61).atZone(ZoneId.of("Asia/Seoul")).toInstant().plusSeconds(60)
        var originalFailure: Exception? = null
        try { fixture.store.execute(id, PensionExperimentInput(history(61), emptyList())); fail("기록 실패를 성공으로 반환하면 안 됩니다.") }
        catch (error: Exception) { originalFailure = error }
        assertFalse(File(directory, "result.json").exists())
        val recovered = fixture.store.list().single()
        assertEquals("INVALID", recovered.state); assertTrue(recovered.events.isEmpty()); assertTrue(recovered.failures.isNotEmpty())
        assertEquals(requireNotNull(originalFailure).javaClass.name, LottoDesignRecordJson.objectValue(recovered.failures.single().getValue("error"))["type"])
        assertTrue(recovered.excludedRounds.containsAll((1..61).toList()))
    }

    @Test fun syntheticCompleteFinalEvidenceAllowsManualApprovalAndCorrectionBlocksReuse() = runBlocking {
        val fixture = PensionRecordFixture(); val targets = (61..100).toList()
        val controlNumbers = PensionExperimentComparison.uniform(43L).map { it["number"].toString().takeLast(2) }
        val suffix = (0..99).map { it.toString().padStart(2, '0') }.first { it !in controlNumbers }
        val coldDigit = (suffix.last().digitToInt() + 1) % 10
        val otherDigit = (coldDigit + 1) % 10
        val coldSuffix = "$coldDigit$coldDigit"; val otherSuffix = "$otherDigit$otherDigit"
        fixture.clock = java.time.Instant.parse("2021-06-30T00:00:00Z")
        val id = fixture.register("FINAL", targets, history(), policy = fixture.policy(targets.size))
        for (round in targets.drop(1)) {
            fixture.clock = lotteryEvaluationDeadline(LotteryProduct.PENSION_720, round).atZone(ZoneId.of("Asia/Seoul")).toInstant().minusSeconds(60)
            fixture.store.capture(id, round, PensionExperimentInput(history(round - 1, suffix), emptyList()))
        }
        fixture.clock = lotteryEvaluationDeadline(LotteryProduct.PENSION_720, targets.last()).atZone(ZoneId.of("Asia/Seoul")).toInstant().plusSeconds(60)
        val directory = File(fixture.root, "pension-experiments/$id")
        val spec = LottoDesignRecordJson.decode(File(directory, "registration.json").readBytes()); val registrationHash = LottoDesignRecordJson.hash(spec)
        val winners = history(targets.last(), suffix).filter { it.roundNo in targets }.sortedBy { it.roundNo }
        fixture.write(File(directory, "execution.json"), fixture.seal(mapOf("registrationHash" to registrationHash, "startedAt" to fixture.clock.toString(),
            "excludedRounds" to (1..targets.last()).toList(), "targets" to winners.map { PensionExperimentComparison.input(listOf(it), emptySet()) })))
        val journal = fixture.journal(directory, registrationHash); fixture.invoke(journal, "append", mapOf("event" to "START"))
        val results = winners.map { winner ->
            val proof = LottoDesignRecordJson.decode(File(directory, "inputs/${winner.roundNo}.json").readBytes())
            val types = listOf("APPEARED", "APPEARED_SECOND", "COLD_MIX", "COLD_MIX_SECOND")
            fun model(candidate: Boolean): Map<String, Any?> {
                val endings = if (candidate) listOf(suffix, suffix, coldSuffix, coldSuffix) else listOf(coldSuffix, coldSuffix, otherSuffix, otherSuffix)
                val fronts = listOf("1234", "5678", "9087", "4321")
                val numbers = types.mapIndexed { index, type -> mapOf("groupNo" to index + 1, "number" to fronts[index] + endings[index], "type" to type, "seed" to "42") }
                val filters = listOf("selection", "pastWinner", "recommendationAndBatch", "pairDifference", "pairLastDigit", "duplicateType", "scoreBand", "group").associateWith { mapOf("reached" to 4, "passed" to 4, "conditionalPassRate" to 1.0) }
                return PensionExperimentComparison.evaluate(numbers, winner) + mapOf("configHash" to LottoDesignRecordJson.hash(PensionExperimentComparison.configuration(if (candidate) 4 else 3)),
                    "diagnostics" to mapOf("attempts" to 4, "filters" to filters, "allFiltersPassRate" to 1.0))
            }
            val baseline = model(false); val candidate = model(true); val control = PensionExperimentComparison.evaluate(PensionExperimentComparison.uniform(43L), winner)
            val batches = linkedMapOf("BASELINE" to baseline, "CANDIDATE" to candidate, "CONTROL" to control)
            batches.forEach { (role, batch) -> fixture.invoke(journal, "append", mapOf("event" to "MODEL", "modelResult" to mapOf("round" to winner.roundNo, "model" to role, "seed" to if (role == "CONTROL") "43" else "42", "batch" to batch))) }
            val b = LottoDesignRecordJson.decimal(baseline.getValue("suffix2Rate")); val c = LottoDesignRecordJson.decimal(candidate.getValue("suffix2Rate")); val u = LottoDesignRecordJson.decimal(control.getValue("suffix2Rate"))
            val targetData = PensionExperimentComparison.input(listOf(winner), emptySet())
            val result = mapOf("targetRound" to winner.roundNo, "target" to mapOf("groupNo" to winner.groupNo, "number" to winner.winningNumber, "bonus" to winner.bonusNumber),
                "targetData" to targetData, "targetDataHash" to LottoDesignRecordJson.hash(targetData), "inputHash" to LottoDesignRecordJson.hash(proof.getValue("input")),
                "seeds" to listOf(mapOf("seed" to "42", "controlSeed" to "43", "batches" to batches)),
                "baselineRate" to b, "candidateRate" to c, "controlRate" to u, "candidateBaselineDifference" to c - b, "candidateControlDifference" to c - u)
            fixture.invoke(journal, "append", mapOf("event" to "ROUND", "roundResult" to result, "inputProofHash" to LottoDesignRecordJson.hash(proof)))
            result
        }
        val result = fixture.seal(mapOf("registrationHash" to registrationHash, "finishedAt" to fixture.clock.toString(), "rounds" to results, "plannedRounds" to targets,
            "successfulModelBatches" to targets.size * 2, "failedModelBatches" to 0, "generationFailureRate" to 0.0))
        fixture.write(File(directory, "result.json"), result); fixture.invoke(journal, "append", mapOf("event" to "SUCCESS", "resultHash" to LottoDesignRecordJson.hash(result)))
        val recovered = fixture.store.list().single(); assertEquals(recovered.issues.toString(), "SUCCESS", recovered.state)
        assertEquals(LottoExperimentDecision.ADOPTION_CANDIDATE, recovered.judgment.decision)
        assertEquals(3, fixture.store.setting().count)
        val original = File(directory, "result.json").readBytes()
        fixture.store.approve(id, PensionExperimentInput(history(targets.last(), suffix), emptyList()), "synthetic fixture only")
        assertEquals(4, fixture.store.setting().count)
        fixture.store.rollback("synthetic rollback"); assertEquals(3, fixture.store.setting().count)
        val corrected = history(targets.last(), suffix).map { if (it.roundNo == 70) it.copy(sourceContentHash = "corrected source") else it }
        try { fixture.store.approve(id, PensionExperimentInput(corrected, emptyList()), "fixture correction"); fail("정정 원본 재사용 금지") } catch (_: IllegalArgumentException) { }
        assertArrayEquals(original, File(directory, "result.json").readBytes())
    }
}
