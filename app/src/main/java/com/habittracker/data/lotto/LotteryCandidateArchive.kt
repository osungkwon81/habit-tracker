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
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

/** 후보는 추첨 결과를 받기 전에 고정한다. 단계는 원본 후보의 인덱스만 참조한다. */
internal class LotteryCandidateArchive(context: Context) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "lottery-candidates")

    fun summary(product: LotteryProduct): String? {
        val directory = File(root, product.name)
        if (!directory.exists()) return null
        val evaluated = requireNotNull(directory.listFiles()).filter { it.name.endsWith(".evaluation.gz") }.maxByOrNull { it.lastModified() } ?: return null
        val result = read(evaluated)
        val models = LottoDesignRecordJson.objectValue(result.getValue("models"))
        val candidate = LottoDesignRecordJson.objectValue(models.getValue("candidate"))
        val stages = LottoDesignRecordJson.list(candidate.getValue("stages")).map(LottoDesignRecordJson::objectValue)
        fun hits(key: String) = stages.sumOf { stage ->
            val counts = LottoDesignRecordJson.list(LottoDesignRecordJson.objectValue(stage.getValue(key)).getValue("matchCounts")).map(LottoDesignRecordJson::integer)
            counts.drop(if (product == LotteryProduct.LOTTO_645) 3 else 2).sum()
        }
        return "${result["round"]}회 변경 후보 · 기준 이상 일치: 전체 ${hits("generated")} → 압축 ${hits("ranked")} → 최종 ${hits("selected")}개"
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
        val seed = 0x145B2L + target.toLong() * 1_000_003L
        val models = withContext(Dispatchers.Default) {
            listOf("baseline", "candidate", "control").associateWith { role ->
                context.ensureActive()
                val stages = mutableListOf<Map<String, Any?>>()
                val modelSeed = if (role == "control") -seed else seed
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
                } else if (input.product == LotteryProduct.LOTTO_645) {
                    LottoNumberGenerator.generateTracked(input.lotto.sortedByDescending { it.roundNo }.map { it.numbers() },
                        modelSeed, target - 1, if (role == "baseline") 32.0 else 64.0) { stages += it }
                } else {
                    generateTrackedPension(input.pension.sortedByDescending { it.roundNo }, modelSeed,
                        if (role == "baseline") 1.0 else 0.5, checkActive = { context.ensureActive() }) { stages += it }
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
        require(frozen["inputHash"] == LottoDesignRecordJson.hash(input.through(round - 1).generationJson())) { "평가 입력이 고정 당시와 다릅니다: $round" }
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
                fun ids(key: String) = LottoDesignRecordJson.list(stage.getValue(key)).map(LottoDesignRecordJson::integer).also { ids ->
                    require(ids.distinct().size == ids.size && ids.all { it in pool.indices }) { "후보 단계 참조 오류: $round" }
                }
                val ranked = ids("ranked"); val selected = ids("selected")
                require(selected.all { it in ranked })
                fun metric(numbers: List<String>): Map<String, Any?> {
                    require(numbers.isNotEmpty())
                    val counts = numbers.map(::matches)
                    return mapOf("count" to numbers.size, "exact" to (target in numbers), "rate" to counts.count { it >= threshold }.toDouble() / counts.size,
                        "secondary" to counts.average(), "matchCounts" to (0..6).map { n -> counts.count { it == n } })
                }
                mapOf("generated" to metric(pool), "ranked" to metric(ranked.map { pool[it] }),
                    "selected" to metric(selected.map { pool[it] }), "selectedNumbers" to selected.map { pool[it] },
                    "exactLoss" to when { target !in pool -> "NOT_IN_POOL"; target !in ranked.map { pool[it] } -> "RANKING"; target !in selected.map { pool[it] } -> "SELECTION"; else -> "RETAINED" })
            }
            val final = pools.map { LottoDesignRecordJson.objectValue(it.getValue("selected")) }
            val total = final.sumOf { LottoDesignRecordJson.integer(it.getValue("count")) }
            fun mean(key: String) = final.sumOf { LottoDesignRecordJson.decimal(it.getValue(key)) * LottoDesignRecordJson.integer(it.getValue("count")) } / total
            mapOf("stages" to pools, "rate" to mean("rate"), "secondary" to mean("secondary"))
        }
        val result = mutableMapOf<String, Any?>("round" to round, "preDraw" to frozen["preDraw"],
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

    companion object { const val IMPLEMENTATION = "final-selection-precise-pension1000-v3" }
}
