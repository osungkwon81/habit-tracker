package com.habittracker.data.lotto

import android.content.Context
import com.habittracker.data.repository.HabitRepository
import java.io.File
import java.time.Instant
import java.util.Properties

/** 후보·기간·비교 예산은 결과를 보기 전에 고정하며 기존 수동 등록을 덮어쓰지 않는다. */
internal class LotteryAutomaticRegistration(context: Context, private val repository: HabitRepository) {
    private val app = context.applicationContext

    suspend fun update(input: LotteryResearchInput, registerIfNeeded: Boolean): String {
        val product = input.product
        val latest = input.rounds().maxOrNull() ?: return "당첨 이력을 수집하고 있습니다."
        val records: List<Entry>
        val applied: Boolean
        if (product == LotteryProduct.LOTTO_645) {
            val draws = input.lotto.map { LottoPriorDesignDraw(it.roundNo, it.numbers(), it.bonusNumber) }
            records = repository.lottoExperiments.list(draws).map {
                Entry(it.specification, it.targetRounds, it.capturedInputRounds, it.run?.state == LottoDesignRunState.SUCCESS,
                    it.issue, it.judgment.decision)
            }
            applied = repository.lottoExperiments.approvedSetting().priorDraws != 32.0
        } else {
            records = repository.pensionExperiments.list().map {
                Entry(it.specification, it.specification?.get("targetRounds")?.let { rounds ->
                    LottoDesignRecordJson.list(rounds).map(LottoDesignRecordJson::integer)
                }.orEmpty(), it.capturedRounds, it.state == "SUCCESS",
                    (it.issues + if (it.failures.isEmpty()) emptyList() else listOf("비교 실패 기록이 있습니다.")).firstOrNull(), it.judgment.decision)
            }
            applied = repository.pensionExperiments.setting().count != 3
        }
        records.firstOrNull { it.issue != null || it.spec == null || it.spec["purpose"] == "INVALID" }?.let {
            return "평가 보류 · ${it.issue ?: "이전 등록 기록을 확인할 수 없습니다."}"
        }
        if (applied) return "검증된 변경 설정을 사용하고 있습니다. 변경 근거를 계속 점검합니다."
        val finals = records.filter { it.spec?.get("purpose") == "FINAL" }
        val active = finals.firstOrNull { !it.completed }
        if (active != null) {
            if (active.spec?.get("policy")?.let(LottoDesignRecordJson::objectValue)?.get("confirmedByUser") != true)
                return "이전 평가의 기준이 미확정입니다. 기존 등록을 보존하고 자동 등록을 보류했습니다."
            val missed = active.rounds.filter { it <= latest && it !in active.captured }
            if (missed.isNotEmpty()) return "평가 보류 · 추첨 전 자료가 없는 ${missed.size}회차가 있습니다. 결과를 보고 채우지 않습니다."
            return "평가 자료 수집 ${active.rounds.count { it <= latest }}/${active.rounds.size}회 · ${active.rounds.first()}~${active.rounds.last()}회"
        }
        if (finals.any { it.decision == LottoExperimentDecision.ADOPTION_CANDIDATE })
            return "적용·복구 이력을 보존하고 있습니다. 같은 평가 결과를 반복 적용하지 않습니다."
        if (finals.any { it.spec?.get("automaticRegistrationPolicy") != POLICY_ID })
            return "기존 평가 기록을 유지합니다. 다른 기준의 비교 예산을 자동으로 초기화하지 않습니다."
        if (finals.size >= MAX_TRIALS) return "예정된 비교를 마쳤습니다. 개선 근거가 부족해 기존 설정을 유지합니다."
        if (!registerIfNeeded) return if (finals.isEmpty()) "자동 개선을 켜면 앱이 평가를 준비합니다. 파일을 가져올 필요가 없습니다."
            else "기존 설정 유지 · 다음 비교를 자동 준비합니다."
        require(input.audit().canResearch) { "최신 회차까지의 데이터 점검이 필요합니다." }
        val first = latest + 1
        require(lotteryEvaluationNow().isBefore(lotteryEvaluationDeadline(product, first))) { "최신 추첨 결과를 수집한 뒤 평가를 준비합니다." }
        val source = sourceEvidence(product)
        val targets = (first until first + FINAL_ROUNDS).toList()
        val spec = mapOf(
            "purpose" to "FINAL", "automaticRegistrationPolicy" to POLICY_ID,
            "historyStartRound" to requireNotNull(input.rounds().minOrNull()), "targetRounds" to targets,
            "commonSeeds" to (0 until 5).map { (0x145B2L + first.toLong() * 1_000_003L + it * 97L).toString() },
            "controlSeeds" to (0 until 5).map { (-0x145B2L - first.toLong() * 1_000_003L - it * 97L).toString() },
            "hypothesis" to if (product == LotteryProduct.LOTTO_645) "최근 빈도 반영을 완화한 균형형 BASIC 5게임 비교" else "출현형 끝자리 후보 최대 3개와 4개 비교",
            "selectionRounds" to input.rounds().sorted(),
            "comparisonFamilyId" to POLICY_ID,
            "historyCompletenessConfirmation" to "등록 시점의 미래 회차만 사용. 보유 기록의 충돌은 기존 저장소에서 검사하며 전체 과거 이력 존재를 보증하지 않음.",
            "policy" to policy(product),
        )
        if (!LotteryAutoImprovementWorker.isEnabled(app, product)) return "자동 개선이 꺼져 있습니다."
        val document = LottoDesignRecordJson.encode(mapOf("formatVersion" to 1, "experiment" to spec, "sourceEvidence" to source)).toByteArray(Charsets.UTF_8)
        if (product == LotteryProduct.LOTTO_645) {
            repository.lottoExperiments.register(document, input.lotto.map { LottoPriorDesignDraw(it.roundNo, it.numbers(), it.bonusNumber) })
        } else repository.pensionExperiments.register(document, repository.getPensionExperimentInput())
        return "평가 자동 준비 완료 · ${targets.first()}~${targets.last()}회 · 결과 수집 후 자동 평가"
    }

    private fun policy(product: LotteryProduct) = mapOf(
        "confirmedByUser" to true, "confirmedAt" to Instant.now().toString(),
        "confirmationMode" to "delegated-policy-selection-and-app-auto-improvement-enabled",
        "primaryMetric" to if (product == LotteryProduct.LOTTO_645) "main-three-plus-game-proportion" else "normal-suffix-two-plus-proportion",
        "confidenceLevel" to 0.95, "minimumEffect" to if (product == LotteryProduct.LOTTO_645) 0.0025 else 0.002,
        "finalRoundCount" to FINAL_ROUNDS, "familyComparisonCount" to MAX_TRIALS * 2 * 2,
        "intervalMethod" to LottoExperimentPolicy.METHOD,
        "assumptions" to "회차별 짝지은 차이는 [-1,1]. 과거 입력만 사용하며 시드·게임은 독립 회차로 세지 않는다. 두 제품 각 최대 4번, 기준·무작위와의 총 16개 비교에 보정한다.",
        "sampleSizeRationale" to "52회는 대기·계산 예산이다. 충분성이나 검정력을 보장하지 않는다. 52회 종료 때 한 번 평가하고 미충족이면 기존 설정을 유지한다. 후속 비교는 겹치지 않는 새 52회로 최대 4번만 수행한다.",
        "rejectWhenUpperNonPositive" to true, "singleFinalLook" to true, "noEarlyAdoption" to true,
    )

    private fun sourceEvidence(product: LotteryProduct): Map<String, Any?> {
        val paths = if (product == LotteryProduct.PENSION_720) PensionExperimentStore.SOURCE_FILES else LOTTO_SOURCES
        val files = paths.sorted().map { path ->
            val bytes = app.assets.open("lottery-experiment-source/$path").use { it.readBytes() }
            mapOf("path" to path, "sha256" to LottoDesignRecordJson.hash(bytes), "content" to bytes.toString(Charsets.UTF_8))
        }
        val versions = Properties().apply {
            app.assets.open("lottery-experiment-source/dependencies.properties").use { load(it) }
        }
        val sourceHash = LottoDesignRecordJson.hash(files.map { it - "content" })
        return mapOf(
            "files" to files, "buildSourceHash" to sourceHash,
            "apkHash" to LottoDesignRecordJson.hash(File(app.applicationInfo.sourceDir).readBytes()),
            "splitApkHashes" to app.applicationInfo.splitSourceDirs.orEmpty().associate { File(it).name to LottoDesignRecordJson.hash(File(it).readBytes()) },
            "dependencyVersions" to mapOf("kotlin" to requireNotNull(versions.getProperty("kotlin")),
                "kotlinStdlib" to KotlinVersion.CURRENT.toString(), "coroutines" to requireNotNull(versions.getProperty("coroutines"))),
            "buildReference" to "gradle-preBuild-source-assets-v1:$sourceHash; Git 상태는 검사하지 않으며 작업 소스를 보존함",
            "gitHead" to null, "hasUncommittedChanges" to true,
        )
    }

    private data class Entry(val spec: Map<String, Any?>?, val rounds: List<Int>, val captured: List<Int>,
        val completed: Boolean, val issue: String?, val decision: LottoExperimentDecision)

    companion object {
        const val POLICY_ID = "automatic-fixed-candidate-52x4-v1"
        const val FINAL_ROUNDS = 52
        private const val MAX_TRIALS = 4
        private val LOTTO_SOURCES = (listOf("app/build.gradle.kts", "build.gradle.kts", "gradle/libs.versions.toml",
            "app/src/main/java/com/habittracker/data/repository/HabitRepository.kt") +
            listOf("LottoNumberGenerator", "LottoPriorDesignComparison", "LottoDesignRunStore", "LottoDesignRecordJson", "LottoExperimentStore", "LottoExperimentPolicy")
                .map { "app/src/main/java/com/habittracker/data/lotto/$it.kt" } +
            listOf("LottoViewModel", "LottoScreen", "LottoExperimentSection").map { "app/src/main/java/com/habittracker/ui/lotto/$it.kt" } +
            LotteryAutoImprovement.SOURCE_FILES).distinct().sorted()
    }
}
