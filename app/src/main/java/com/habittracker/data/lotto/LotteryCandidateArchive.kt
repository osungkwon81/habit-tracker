package com.habittracker.data.lotto

import android.content.Context
import com.habittracker.ui.lotto.generateTrackedPension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

/** 후보는 추첨 결과를 받기 전에 고정한다. 단계는 원본 후보의 인덱스만 참조한다. */
internal class LotteryCandidateArchive(context: Context, private val now: () -> LocalDateTime = ::lotteryEvaluationNow) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "lottery-candidates")
    private val patterns = LotteryPatternEvolution(app)

    fun summary(product: LotteryProduct): String? {
        val directory = File(root, product.name)
        if (!directory.exists()) return null
        val automatic = requireNotNull(directory.listFiles()).filter { it.name.endsWith(".evaluation.gz") }.maxByOrNull { it.lastModified() }
        val userSummary = synchronized(userRecordsGate) {
            val folder = File(directory, "user")
            folder.listFiles()?.filter { it.name.endsWith(".evaluation.gz") }?.maxByOrNull { it.lastModified() }
                ?.let { summarize(read(it), product, "직접 생성 후보") }
        }
        return listOfNotNull(automatic?.let { summarize(read(it), product, "자동 비교 후보") }, userSummary)
            .takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    private fun summarize(result: Map<String, Any?>, product: LotteryProduct, label: String): String {
        val models = LottoDesignRecordJson.objectValue(result.getValue("models"))
        val candidate = LottoDesignRecordJson.objectValue(models["user"] ?: models["patternCandidate"] ?: models.getValue("candidate"))
        val stages = LottoDesignRecordJson.list(candidate.getValue("stages")).map(LottoDesignRecordJson::objectValue)
        fun hits(key: String) = stages.sumOf { stage ->
            val counts = LottoDesignRecordJson.list(LottoDesignRecordJson.objectValue(stage.getValue(key)).getValue("matchCounts")).map(LottoDesignRecordJson::integer)
            counts.drop(if (product == LotteryProduct.LOTTO_645) 3 else 2).sum()
        }
        val diagnostics = stages.mapNotNull { it["diagnostics"]?.let(LottoDesignRecordJson::objectValue) }
        val lost = diagnostics.sumOf { LottoDesignRecordJson.integer(it.getValue("rankingLostHits")) }
        val examples = diagnostics.flatMap { LottoDesignRecordJson.list(it.getValue("examples")) }
            .take(3).joinToString("\n") { raw ->
                val row = LottoDesignRecordJson.objectValue(raw)
                val columns = diagnostics.firstNotNullOfOrNull { it["scoreColumns"] }?.let(LottoDesignRecordJson::list).orEmpty()
                val scores = LottoDesignRecordJson.list(row.getValue("scores"))
                val details = columns.zip(scores).joinToString { (name, score) -> "$name=$score" }
                "${row["number"]} · ${row["rank"]}위 · ${row["reason"]} · $details · 경계 ${row["cutoffScore"]}"
            }
        val eligibility = if (result["preDraw"] == true) "추첨 전 기록" else "추첨 후 기록 · 학습 제외"
        val userDetails = if (result["observationSource"] == "user") "\n${result["source"]} · ${result["generatedAt"]} · 생성 번호 " +
            stages.flatMap { LottoDesignRecordJson.list(it.getValue("selectedNumbers")) }.joinToString(" / ") else ""
        return "${result["round"]}회 $label · $eligibility · 기준 이상 일치: 전체 ${hits("generated")} → 압축 ${hits("ranked")} → 최종 ${hits("selected")}개$userDetails" +
            if (diagnostics.isEmpty()) "\n과거 기록에는 항목별 탈락 점수가 없습니다." else "\n순위 탈락 ${lost}개\n$examples"
    }

    suspend fun recordUserGeneration(input: LotteryResearchInput, generationInputHash: String, configJson: String,
        source: String, seed: Long, startedAt: LocalDateTime, stages: List<Map<String, Any?>>, resultAlreadyKnown: Boolean): String =
        withContext(Dispatchers.IO) {
            val coroutine = currentCoroutineContext()
            synchronized(userRecordsGate) {
                require(stages.isNotEmpty()) { "직접 생성 후보 기록이 없습니다: ${input.product}" }
                val target = requireNotNull(input.rounds().maxOrNull()) + 1
                val recordedAt = now()
                val id = "$target-${UUID.randomUUID()}"
                val deadline = lotteryEvaluationDeadline(input.product, target)
                val body = mapOf("implementation" to IMPLEMENTATION, "userFormatVersion" to 1,
                    "product" to input.product.name, "target" to target, "userRunId" to id,
                    "source" to source, "seed" to seed.toString(), "startedAt" to startedAt.toString(),
                    "createdAt" to recordedAt.toString(), "generationInputHash" to generationInputHash,
                    "preDraw" to (!resultAlreadyKnown && !recordedAt.isBefore(startedAt) && startedAt.isBefore(deadline) && recordedAt.isBefore(deadline)),
                    "input" to input.generationJson(), "inputHash" to LottoDesignRecordJson.hash(input.generationJson()),
                    "settings" to configJson, "historyPresenceGuaranteed" to false,
                    "models" to mapOf("user" to mapOf("seed" to seed.toString(), "stages" to stages)))
                coroutine.ensureActive()
                write(File(userFolder(input.product), "$id.json.gz"), body)
                coroutine.ensureActive()
                val completedAt = now()
                write(File(userFolder(input.product), "$id.committed.gz"), mapOf("candidateHash" to LottoDesignRecordJson.hash(body),
                    "completedAt" to completedAt.toString(),
                    "preDraw" to (body["preDraw"] == true && !completedAt.isBefore(recordedAt) && completedAt.isBefore(deadline))))
                id
            }
        }

    /** 구매·저장·화면 노출 여부와 관계없이 생성 시 고정한 모든 성공 후보를 평가한다. */
    fun evaluateUserPending(input: LotteryResearchInput): Boolean = synchronized(userRecordsGate) {
        var changed = false
        for (file in userFiles(input.product).filter { it.name.matches(USER_RECORD_NAME) }) {
            val frozen = readUserRecord(file) ?: continue
            val round = LottoDesignRecordJson.integer(frozen.getValue("target"))
            if (round !in input.rounds()) continue
            if (!generationInputMatches(frozen, input, round)) {
                android.util.Log.w("LotteryCandidates", "product=${input.product} run=${frozen["userRunId"]} round=$round phase=user-evaluation held: input changed")
                continue
            }
            val evaluation = File(userFolder(input.product), "${frozen.getValue("userRunId")}-${LottoDesignRecordJson.hash(input.through(round).generationJson())}.evaluation.gz")
            if (evaluation.exists()) { read(evaluation); continue }
            val result = evaluate(frozen, input, round) + mapOf("userRunId" to frozen.getValue("userRunId"),
                "generatedAt" to frozen["startedAt"],
                "source" to frozen["source"], "settings" to frozen["settings"], "observationSource" to "user")
            write(evaluation, result)
            changed = true
        }
        changed
    }

    private fun userEvaluations(input: LotteryResearchInput): List<Map<String, Any?>> = synchronized(userRecordsGate) {
        userFiles(input.product).filter { it.name.endsWith(".evaluation.gz") }.map(::read).filter { result ->
            val round = LottoDesignRecordJson.integer(result.getValue("round"))
            val runId = LottoDesignRecordJson.string(result.getValue("userRunId"))
            require("$runId.json.gz".matches(USER_RECORD_NAME)) { "직접 생성 기록 ID 오류" }
            val file = File(userFolder(input.product), "$runId.json.gz")
            round in input.rounds() && file.isFile &&
                result["targetDataHash"] == LottoDesignRecordJson.hash(input.through(round).generationJson()) &&
                readUserRecord(file)?.let { frozen ->
                    generationInputMatches(frozen, input, round) &&
                        result["candidateHash"] == LottoDesignRecordJson.hash(frozen)
                } == true
        }
    }

    private fun userFolder(product: LotteryProduct) = File(File(root, product.name), "user")

    private fun readUserRecord(file: File): Map<String, Any?>? {
        val id = file.name.removeSuffix(".json.gz")
        val committed = File(requireNotNull(file.parentFile), "$id.committed.gz")
        if (!committed.exists()) {
            android.util.Log.w("LotteryCandidates", "run=$id phase=user-read held: registration incomplete")
            return null
        }
        val body = read(file)
        val completion = read(committed)
        require(completion["candidateHash"] == LottoDesignRecordJson.hash(body)) { "직접 생성 등록 참조 오류: $id" }
        return body + mapOf("preDraw" to completion.getValue("preDraw"), "completedAt" to completion.getValue("completedAt"))
    }

    private fun userFiles(product: LotteryProduct): List<File> {
        val folder = userFolder(product)
        if (!folder.exists()) return emptyList()
        require(folder.isDirectory && folder.canonicalFile == folder.absoluteFile) { "직접 생성 기록 경로 오류: $product" }
        return requireNotNull(folder.listFiles()).toList()
    }

    private fun generationInputMatches(frozen: Map<String, Any?>, input: LotteryResearchInput, round: Int): Boolean {
        var before = input.through(round - 1)
        if (frozen["userRunId"] != null) {
            val original = LottoDesignRecordJson.objectValue(frozen.getValue("input"))
            val first = LottoDesignRecordJson.list(original.getValue("draws")).minOf { raw ->
                LottoDesignRecordJson.integer(LottoDesignRecordJson.objectValue(raw).getValue("roundNo"))
            }
            before = before.copy(lotto = before.lotto.filter { it.roundNo >= first }, pension = before.pension.filter { it.roundNo >= first })
        }
        return frozen["inputHash"] == LottoDesignRecordJson.hash(before.generationJson())
    }

    fun recorded(input: LotteryResearchInput, round: Int): Map<String, Any?>? {
        val file = File(File(root, input.product.name), "$round.json.gz")
        return if (file.exists()) read(file).takeIf { it["implementation"] == IMPLEMENTATION &&
            it["inputHash"] == LottoDesignRecordJson.hash(input.through(round - 1).generationJson()) } else null
    }

    fun eligible(input: LotteryResearchInput, round: Int): Boolean {
        val frozen = recorded(input, round) ?: return false
        return frozen["preDraw"] == true
    }

    suspend fun freeze(input: LotteryResearchInput, target: Int, destination: File? = null): Map<String, Any?> {
        val future = destination == null
        val file = destination ?: File(File(root, input.product.name), "$target.json.gz")
        if (file.exists()) return read(file).also {
            require(it["implementation"] == IMPLEMENTATION) { "고정 후보 구현이 변경됐습니다: $target" }
            require(it["product"] == input.product.name && LottoDesignRecordJson.integer(it.getValue("target")) == target)
            require(it["inputHash"] == LottoDesignRecordJson.hash(input.generationJson())) { "고정 후보의 입력이 변경됐습니다: $target 회차" }
        }
        require(input.rounds().maxOrNull() == target - 1) { "후보 입력은 직전 회차까지 필요합니다: $target" }
        if (future) require(lotteryEvaluationNow().isBefore(lotteryEvaluationDeadline(input.product, target))) { "추첨 시각이 지나 후보를 사전 등록하지 않습니다: $target" }
        val started = File(requireNotNull(file.parentFile), "${file.name}.started.gz")
        require(!started.exists()) { "후보 생성 실패·중단 기록을 자동 재실행하지 않습니다: $target" }
        write(started, mapOf("target" to target, "implementation" to IMPLEMENTATION, "inputHash" to LottoDesignRecordJson.hash(input.generationJson())))
        val context = currentCoroutineContext()
        val trial = if (future) patterns.trial(input.product) else null
        val seed = 0x145B2L + target.toLong() * 1_000_003L
        val models = withContext(Dispatchers.Default) {
            val roles = listOf("baseline", "candidate", "control") + if (trial == null) emptyList() else
                listOf("patternBaseline", "patternCandidate") + if (input.product == LotteryProduct.LOTTO_645)
                    listOf("patternDiversifiedBaseline", "patternDiversifiedCandidate") else emptyList()
            roles.associateWith { role ->
                context.ensureActive()
                val stages = mutableListOf<Map<String, Any?>>()
                val modelSeed = if (role == "control") -seed else seed
                val patternModel = if (role.startsWith("pattern")) LotteryPatternModel.from(trial?.get(
                    if (role.endsWith("Candidate")) "after" else "before")) else LotteryPatternModel()
                val operatingValue = trial?.get("operatingValue")?.let(LottoDesignRecordJson::decimal)
                if (role == "control") {
                    val random = Random(modelSeed)
                    val pool = linkedSetOf<String>()
                    val size = if (input.product == LotteryProduct.LOTTO_645) 12000 else 1000
                    while (pool.size < size) {
                        context.ensureActive()
                        pool += if (input.product == LotteryProduct.LOTTO_645) (1..45).shuffled(random).take(6).sorted().joinToString(",")
                        else random.nextInt(1_000_000).toString().padStart(6, '0')
                    }
                    val finalists = if (input.product == LotteryProduct.LOTTO_645) 260 else 200
                    stages += mapOf("pool" to pool.toList(), "ranked" to (0 until finalists).toList(),
                        "selected" to (0 until if (input.product == LotteryProduct.LOTTO_645) 5 else 4).toList())
                } else if (role.startsWith("patternDiversified")) {
                    LottoNumberGenerator.generateDiversified(input.lotto.sortedByDescending { it.roundNo }.map { it.numbers() },
                        mode = LottoGenerationMode.PRECISE, seed = modelSeed, historyThroughRound = target - 1,
                        patternModel = patternModel, recordStages = { stages += it })
                } else if (input.product == LotteryProduct.LOTTO_645) {
                    LottoNumberGenerator.generateTracked(input.lotto.sortedByDescending { it.roundNo }.map { it.numbers() },
                        modelSeed, target - 1, if (role.startsWith("pattern")) requireNotNull(operatingValue)
                        else if (role == "baseline") 32.0 else 64.0, patternModel) { stages += it }
                } else {
                    generateTrackedPension(input.pension.sortedByDescending { it.roundNo }, modelSeed,
                        if (role.startsWith("pattern")) requireNotNull(operatingValue) else if (role == "baseline") 1.0 else 0.5,
                        checkActive = { context.ensureActive() }, patternModel = patternModel) { stages += it }
                }
                mapOf("seed" to modelSeed.toString(), "stages" to stages)
            }
        }
        val sharedPools = linkedMapOf<String, Any?>()
        val compactModels = models.mapValues { (_, model) -> model + ("stages" to
            LottoDesignRecordJson.list(model.getValue("stages")).map { raw ->
                val stage = LottoDesignRecordJson.objectValue(raw)
                val pool = stage.getValue("pool")
                val hash = LottoDesignRecordJson.hash(pool)
                sharedPools[hash] = pool
                (stage - "pool") + ("poolRef" to hash)
            }) }
        val body = mapOf("implementation" to IMPLEMENTATION, "product" to input.product.name, "target" to target,
            "createdAt" to Instant.now().toString(), "preDraw" to future,
            "patternTrialId" to trial?.get("trialId"), "patternTrial" to trial,
            "input" to input.generationJson(), "inputHash" to LottoDesignRecordJson.hash(input.generationJson()),
            "apkHash" to LottoDesignRecordJson.hash(File(app.applicationInfo.sourceDir).readBytes()),
            "splitApkHashes" to app.applicationInfo.splitSourceDirs.orEmpty().map { LottoDesignRecordJson.hash(File(it).readBytes()) },
            "settings" to listOf(if (input.product == LotteryProduct.LOTTO_645) 32.0 else 1.0,
                if (input.product == LotteryProduct.LOTTO_645) 64.0 else 0.5).map { LotteryAdaptiveSettings.configurationFor(input.product, it) },
            "historyPresenceGuaranteed" to false, "pools" to sharedPools, "models" to compactModels)
        context.ensureActive()
        if (future) require(lotteryEvaluationNow().isBefore(lotteryEvaluationDeadline(input.product, target))) { "후보 생성 중 추첨 시각이 지났습니다: $target" }
        write(file, body)
        return body
    }

    fun evaluate(frozen: Map<String, Any?>, input: LotteryResearchInput, round: Int): Map<String, Any?> {
        require(frozen["product"] == input.product.name && LottoDesignRecordJson.integer(frozen.getValue("target")) == round)
        require(generationInputMatches(frozen, input, round)) { "평가 입력이 고정 당시와 다릅니다: $round" }
        val target = if (input.product == LotteryProduct.LOTTO_645) input.lotto.single { it.roundNo == round }.numbers().sorted().joinToString(",")
            else input.pension.single { it.roundNo == round }.winningNumber
        val targetNumbers = target.split(',').toSet()
        fun matches(number: String): Int = if (input.product == LotteryProduct.LOTTO_645)
            number.split(',').count { it in targetNumbers }
        else number.reversed().zip(target.reversed()).takeWhile { (a, b) -> a == b }.size
        val threshold = if (input.product == LotteryProduct.LOTTO_645) 3 else 2
        val models = LottoDesignRecordJson.objectValue(frozen.getValue("models"))
        val metrics = models.mapValues { (_, value) ->
            val pools = LottoDesignRecordJson.list(LottoDesignRecordJson.objectValue(value).getValue("stages")).map { raw ->
                val stage = LottoDesignRecordJson.objectValue(raw)
                val storedPool = stage["pool"] ?: LottoDesignRecordJson.objectValue(frozen.getValue("pools")).getValue(LottoDesignRecordJson.string(stage.getValue("poolRef"))).also {
                    require(LottoDesignRecordJson.hash(it) == stage["poolRef"]) { "후보 풀 참조 오류: $round" }
                }
                val pool = LottoDesignRecordJson.list(storedPool).map(LottoDesignRecordJson::string)
                require(pool.distinct().size == pool.size) { "후보 중복 오류: $round" }
                val matchValues = pool.map(::matches)
                val matchCache = pool.zip(matchValues).toMap()
                fun ids(key: String) = LottoDesignRecordJson.list(stage.getValue(key)).map(LottoDesignRecordJson::integer).also { ids ->
                    require(ids.distinct().size == ids.size && ids.all { it in pool.indices }) { "후보 단계 참조 오류: $round" }
                }
                val ranked = ids("ranked"); val selected = ids("selected")
                require(selected.all { it in ranked })
                fun metric(numbers: List<String>): Map<String, Any?> {
                    require(numbers.isNotEmpty())
                    val counts = numbers.map { matchCache.getValue(it) }
                    return mapOf("count" to numbers.size, "exact" to (target in numbers), "rate" to counts.count { it >= threshold }.toDouble() / counts.size,
                        "secondary" to counts.average(), "matchCounts" to (0..6).map { n -> counts.count { it == n } })
                }
                val ordered = stage["order"]?.let { LottoDesignRecordJson.list(it).map(LottoDesignRecordJson::integer) }
                val allScores = stage["allScores"]?.let(LottoDesignRecordJson::list)
                val reasons = stage["selectionReasons"]?.let(LottoDesignRecordJson::objectValue).orEmpty()
                val positions = ordered?.withIndex()?.associate { it.value to it.index + 1 }.orEmpty()
                val rankedSet = ranked.toSet()
                val selectedSet = selected.toSet()
                val lostHits = pool.indices.filter { matchValues[it] >= threshold && it !in selectedSet }
                val patternGroups = pool.indices.flatMap { index ->
                    LotteryPatternModel.keys(pool[index], input.product == LotteryProduct.LOTTO_645).map { it to index }
                }.groupBy({ it.first }, { it.second })
                val patternOutcomes = patternGroups.mapValues { (_, indices) ->
                    val hitIndices = indices.filter { matchValues[it] >= threshold }
                    mapOf("count" to indices.size, "hits" to hitIndices.size,
                        "rankingLostHits" to hitIndices.count { it !in rankedSet },
                        "selectionLostHits" to hitIndices.count { it in rankedSet && it !in selectedSet })
                }
                val cutoff = ranked.lastOrNull()?.let { index -> allScores?.get(index)?.let(LottoDesignRecordJson::list)?.firstOrNull() }
                val diagnostics = if (allScores == null) null else mapOf(
                    "scoreColumns" to stage["scoreColumns"], "cutoffScore" to cutoff,
                    "rankingLostHits" to lostHits.count { it !in rankedSet },
                    "selectionLostHits" to lostHits.count { it in rankedSet },
                    "randomWithinPool" to metric(pool.indices.shuffled(Random(round.toLong())).take(ranked.size).map { pool[it] }),
                    "examples" to lostHits.sortedByDescending { matchValues[it] }.take(10).map { index -> mapOf(
                        "number" to pool[index], "rank" to positions[index], "matches" to matchValues[index],
                        "scores" to allScores[index], "cutoffScore" to cutoff,
                        "reason" to if (index !in rankedSet) stage["rankingReason"] ?: "종합 점수 상위 260개에서 제외" else when (reasons[index.toString()]) {
                            "PAIR_OVERLAP" -> "최종 게임과 번호 중복 한도 초과"
                            "NUMBER_REUSE" -> "번호 재사용 한도 초과"
                            "DIVERSITY_ADJUSTED_SCORE" -> "분산 보정 후 선택 점수에서 밀림"
                            "LAST_DIGIT_REUSE" -> "끝자리 재사용이 더 많음"
                            "LAST_DIGIT_WEIGHTED_DRAW" -> "끝자리 가중 추첨에서 제외"
                            "WITHIN_LAST_DIGIT_DRAW" -> "같은 끝자리 후보 내 추첨에서 제외"
                            else -> "과거 기록에 선택 사유 없음"
                        },
                        "patterns" to LotteryPatternModel.keys(pool[index], input.product == LotteryProduct.LOTTO_645)) },
                    "exactRank" to pool.indexOf(target).takeIf { it >= 0 }?.let { positions[it] })
                mapOf("generated" to metric(pool), "ranked" to metric(ranked.map { pool[it] }),
                    "diagnostics" to diagnostics,
                    "patternOutcomes" to patternOutcomes,
                    "selected" to metric(selected.map { pool[it] }), "selectedNumbers" to selected.map { pool[it] },
                    "exactLoss" to when { target !in pool -> "NOT_IN_POOL"; target !in ranked.map { pool[it] } -> "RANKING"; target !in selected.map { pool[it] } -> "SELECTION"; else -> "RETAINED" })
            }
            val final = pools.map { LottoDesignRecordJson.objectValue(it.getValue("selected")) }
            val total = final.sumOf { LottoDesignRecordJson.integer(it.getValue("count")) }
            fun mean(key: String) = final.sumOf { LottoDesignRecordJson.decimal(it.getValue(key)) * LottoDesignRecordJson.integer(it.getValue("count")) } / total
            mapOf("stages" to pools, "rate" to mean("rate"), "secondary" to mean("secondary"))
        }
        val result = mutableMapOf<String, Any?>("round" to round, "preDraw" to frozen["preDraw"],
            "patternTrialId" to frozen["patternTrialId"],
            "candidateHash" to LottoDesignRecordJson.hash(frozen), "target" to target,
            "targetDataHash" to LottoDesignRecordJson.hash(input.through(round).generationJson()), "models" to metrics)
        metrics.forEach { (role, metric) -> result[role + "Rate"] = metric.getValue("rate"); result[role + "Secondary"] = metric.getValue("secondary") }
        return result
    }

    fun evaluatePending(input: LotteryResearchInput): List<Int> {
        val held = mutableListOf<Int>()
        val directory = File(root, input.product.name)
        if (!directory.exists()) return held
        require(directory.isDirectory && directory.canonicalFile == directory.absoluteFile)
        for (file in requireNotNull(directory.listFiles()).filter { it.name.matches(Regex("[0-9]+\\.json\\.gz")) }.sortedBy { it.name }) {
            val round = file.name.substringBefore('.').toInt()
            if (round !in input.rounds()) continue
            val frozen = read(file)
            if (frozen["implementation"] != IMPLEMENTATION || frozen["inputHash"] != LottoDesignRecordJson.hash(input.through(round - 1).generationJson())) {
                android.util.Log.w("LotteryCandidates", "product=${input.product} round=$round phase=evaluation held: implementation/input changed")
                held += round
                continue
            }
            val evaluation = File(directory, "$round-${LottoDesignRecordJson.hash(input.through(round).generationJson())}.evaluation.gz")
            if (evaluation.exists()) { read(evaluation); continue }
            write(evaluation, evaluate(frozen, input, round))
        }
        return held
    }

    fun evaluations(input: LotteryResearchInput): List<Map<String, Any?>> {
        val folder = File(root, input.product.name)
        if (!folder.exists()) return emptyList()
        return requireNotNull(folder.listFiles()).filter { it.name.endsWith(".evaluation.gz") }.map(::read).filter { result ->
            val round = LottoDesignRecordJson.integer(result.getValue("round"))
            round in input.rounds() && result["targetDataHash"] == LottoDesignRecordJson.hash(input.through(round).generationJson()) &&
                recorded(input, round)?.let { result["candidateHash"] == LottoDesignRecordJson.hash(it) } == true
        } + userEvaluations(input)
    }

    fun canFreeze(input: LotteryResearchInput, round: Int): Boolean {
        val directory = File(root, input.product.name)
        if (File(directory, "$round.json.gz").exists()) return recorded(input, round) != null
        return !File(directory, "$round.json.gz.started.gz").exists()
    }

    private fun write(file: File, body: Map<String, Any?>) {
        val parent = requireNotNull(file.parentFile)
        require(parent.isDirectory || parent.mkdirs()) { "후보 기록 폴더 생성 실패" }
        require(parent.canonicalFile == parent.absoluteFile)
        val record = body + ("recordHash" to LottoDesignRecordJson.hash(body))
        val bytes = ByteArrayOutputStream().use { buffer ->
            GZIPOutputStream(buffer).use { it.write(LottoDesignRecordJson.encode(record).toByteArray(Charsets.UTF_8)) }; buffer.toByteArray()
        }
        LottoDesignRunStore.writeImmutable(file, bytes)
    }

    private fun read(file: File): Map<String, Any?> {
        require(file.isFile && file.canonicalFile == file.absoluteFile)
        val value = GZIPInputStream(file.inputStream()).use { LottoDesignRecordJson.decode(it.readBytes()) }
        require(value["recordHash"] == LottoDesignRecordJson.hash(value - "recordHash")) { "후보 기록 무결성 오류: ${file.name}" }
        return value - "recordHash"
    }

    companion object {
        const val IMPLEMENTATION = "final-selection-pattern-diagnostics-v4"
        private val userRecordsGate = Any()
        private val USER_RECORD_NAME = Regex("[0-9]+-[0-9a-f-]{36}\\.json\\.gz")
    }
}
