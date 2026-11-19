package com.habittracker.data.lotto

import com.habittracker.data.local.entity.LottoDrawEntity
import com.habittracker.data.local.entity.PensionLotteryDrawEntity

internal object LottoBatchCoverage {
    const val POLICY = "exact-three-of-six-batch-v1"
    const val TOTAL = 8_145_060
    private val combinations = Array(46) { IntArray(7) }.apply {
        for (n in indices) {
            this[n][0] = 1
            for (k in 1..minOf(6, n)) this[n][k] = this[n - 1][k - 1] + this[n - 1][k]
        }
    }

    fun mask(numbers: List<Int>): Long {
        require(numbers.size == 6 && numbers.all { it in 1..45 })
        return numbers.fold(0L) { bits, n -> bits or (1L shl (n - 1)) }.also { require(it.countOneBits() == 6) }
    }

    // 공유 번호의 추첨 여부를 고정하면 각 게임의 전용 번호와 나머지 번호는 서로소다.
    // 모든 게임에서 2개 이하로 맞는 추첨 수를 센 뒤 전체에서 뺀다.
    fun wins(games: List<Long>): Int {
        require(games.size in 1..5)
        var union = 0L
        var shared = 0L
        for (game in games) {
            shared = shared or (union and game)
            union = union or game
        }
        val privateCounts = games.map { (it and shared.inv()).countOneBits() }
        val outside = 45 - union.countOneBits()
        val ways = IntArray(7)
        fun losses(remaining: Long, drawn: Long, left: Int): Int {
            if (remaining != 0L && left > 0) {
                val bit = remaining and -remaining
                val rest = remaining xor bit
                val withBit = drawn or bit
                val canLose = games.none { (it and withBit).countOneBits() >= 3 }
                return losses(rest, drawn, left) + if (canLose) losses(rest, withBit, left - 1) else 0
            }
            ways.fill(0)
            ways[0] = 1
            for (i in games.indices) {
                val limit = 2 - (games[i] and drawn).countOneBits()
                for (k in left downTo 1) {
                    if (limit >= 1) ways[k] += ways[k - 1] * privateCounts[i]
                    if (limit == 2 && k >= 2) ways[k] += ways[k - 2] * combinations[privateCounts[i]][2]
                }
            }
            return (0..left).sumOf { ways[it] * combinations[outside][left - it] }
        }
        return TOTAL - losses(shared, 0, 6)
    }

    data class Selection(val indices: List<Int>, val previousWins: Int, val selectedWins: Int)

    fun select(pool: List<Long>, previous: List<Int>, pairLimit: Int): Selection {
        require(previous.size == 5)
        val before = wins(previous.map(pool::get))
        val unchanged = Selection(previous, before, before)
        // 5개가 서로소이면 두 게임 동시 당첨은 각각 3개씩 맞힌 400가지이고 세 게임 동시 당첨은 불가능하다.
        // 공유 번호를 미사용 번호로 옮기면 다른 게임이 가리던 당첨 결과 수가 늘지 않는다.
        val disjointWins = 5 * 194_130 - 10 * 400
        if (before == disjointWins) return unchanged
        for (start in pool.indices) {
            val packed = mutableListOf(start)
            var used = pool[start]
            for (i in pool.indices) if ((used and pool[i]) == 0L) {
                packed += i
                used = used or pool[i]
                if (packed.size == 5) return Selection(packed, before, disjointWins)
            }
        }
        val selected = mutableListOf(0)
        val masks = mutableListOf(pool.first())
        var used = pool.first()
        var usedTwice = 0L
        while (selected.size < 5) {
            val disjoint = pool.indexOfFirst { (it and used) == 0L }
            val next = if (disjoint >= 0) disjoint else pool.indices.asSequence().filter { i ->
                val game = pool[i]
                i !in selected && (game and usedTwice) == 0L && masks.all { (it and game).countOneBits() <= pairLimit }
            }.maxByOrNull { i -> wins(masks + pool[i]) } ?: return unchanged
            selected += next
            masks += pool[next]
            usedTwice = usedTwice or (used and pool[next])
            used = used or pool[next]
        }
        val after = wins(masks)
        return if (after > before) Selection(selected, before, after) else unchanged
    }
}

/** 후보 번호는 원본 풀의 인덱스로 참조한다. 점수 차이는 후보에서 실제 경계 후보를 뺀 값이다. */
internal fun prizeDiagnostics(
    pool: List<String>, matches: List<Int>, ranked: List<Int>, selected: Set<Int>,
    stage: Map<String, Any?>, role: String, lotto: LottoDrawEntity?, pension: PensionLotteryDrawEntity?,
    legacy: Boolean, legacyAllGroups: Boolean,
): Map<String, Any?> {
    val retained = ranked.toHashSet()
    val order = stage["order"]?.let(LottoDesignRecordJson::list)?.map(LottoDesignRecordJson::integer)
    val scores = stage["allScores"]?.let(LottoDesignRecordJson::list)
    val strategy = stage["strategy"] as? String
    val weights = stage["rankingWeights"]?.let(LottoDesignRecordJson::list)?.map(LottoDesignRecordJson::decimal)
        ?: if (legacy && strategy != null && scores != null) LottoNumberGenerator.rankingWeights(strategy) else null
    val reasons = stage["selectionReasons"]?.let(LottoDesignRecordJson::objectValue).orEmpty()
    val selectionTrace = stage["selectionTrace"]?.let(LottoDesignRecordJson::objectValue).orEmpty()
    val coverage = stage["batchCoverage"]?.let(LottoDesignRecordJson::objectValue)
    val selectedMasks = if (lotto != null && coverage != null) selected.associateWith {
        LottoBatchCoverage.mask(pool[it].split(',').map(String::toInt))
    } else emptyMap()
    val alternate = stage["alternateRanking"]?.let(LottoDesignRecordJson::list)?.map(LottoDesignRecordJson::integer)?.toSet()
    val groups = stage["candidateGroups"]?.let(LottoDesignRecordJson::list)
    // 끝자리별 대표는 보호되므로 일반 후보가 경쟁한 마지막 비보호 후보를 기준으로 삼는다.
    val protected = if (pension != null && order != null) order.distinctBy { pool[it].last() }.toSet() else emptySet()
    val boundary = ranked.lastOrNull { it !in protected }
    fun contributions(index: Int): List<Double>? {
        val row = scores?.get(index)?.let(LottoDesignRecordJson::list)?.map(LottoDesignRecordJson::decimal) ?: return null
        if (lotto == null) {
            if (row.size != 4) return null
            return listOf(row[0] - row[2], row[2])
        }
        if (weights == null || row.size != 7) return null
        val values = listOf(row[1] * weights[0], row[2] * weights[1], row[3] * weights[2],
            (row[4] - 50.0) * weights[3], -row[5], row[6])
        return values + (row[0] - values.sum())
    }
    val reference = boundary?.let(::contributions)
    val rankLossSum = DoubleArray(3)
    var scoredLosses = 0
    var finalDiversityDeficit = 0.0
    var finalScoreLosses = 0
    val counts = linkedMapOf<String, MutableMap<String, Int>>()
    val outcomes = mutableListOf<Map<String, Any?>>()
    for ((index, count) in matches.withIndex()) {
        val number = pool[index]
        val bonusHit = pension?.bonusNumber?.let { number == it }
        if (count < (if (lotto != null) 3 else 1) && bonusHit != true) continue
        val grades = if (lotto != null) listOf(when (count) {
            6 -> "1"
            5 -> when (lotto.bonusNumber?.let { it.toString() in number.split(',') }) {
                true -> "2"
                false -> "3"
                null -> "2_OR_3"
            }
            4 -> "4"
            else -> "5"
        }) else buildList {
            if (count == 6) {
                when {
                    stage["allGroups"] == true || legacyAllGroups -> { add("1"); add("2") }
                    groups != null -> add(if (LottoDesignRecordJson.integer(groups[index]) == requireNotNull(pension).groupNo) "1" else "2")
                    else -> add("1_OR_2")
                }
            } else if (count > 0) add((8 - count).toString())
        }
        val phase = when (index) {
            in selected -> "SELECTED"
            in retained -> "FINAL_SELECTION"
            else -> "RANKING"
        }
        for (grade in grades + if (bonusHit == true) listOf("BONUS") else emptyList()) {
            val byStage = counts.getOrPut(grade) { linkedMapOf() }
            byStage[phase] = (byStage[phase] ?: 0) + 1
        }
        if (phase == "SELECTED") continue
        val delta = if (phase == "RANKING" && reference != null) contributions(index)?.mapIndexed { k, v -> v - reference[k] } else null
        if (delta != null && lotto != null) {
            for (k in rankLossSum.indices) rankLossSum[k] -= delta[k]
            scoredLosses++
        }
        val compared = selectionTrace[index.toString()]?.let(LottoDesignRecordJson::objectValue)
        if (lotto != null && phase == "FINAL_SELECTION" && compared?.get("reason") == "DIVERSITY_ADJUSTED_SCORE") {
            val baseGap = LottoDesignRecordJson.decimal(compared.getValue("baseGap"))
            val diversityGap = LottoDesignRecordJson.decimal(compared.getValue("diversityGap"))
            if (baseGap > 0.0 && diversityGap < 0.0) finalDiversityDeficit -= diversityGap
            finalScoreLosses++
        }
        outcomes += buildMap {
            put("index", index); put("grades", grades); put("stage", phase)
            if (bonusHit == true) put("bonus", true)
            if (delta != null) put("scoreDelta", delta)
            put("reason", when {
                role == "control" -> "RANDOM_SELECTION"
                phase == "FINAL_SELECTION" -> reasons[index.toString()]
                else -> stage["rankingReason"] ?: if (lotto != null && scores != null) "TOTAL_SCORE" else null
            })
            selectionTrace[index.toString()]?.let { put("comparison", it) }
            if (lotto != null && phase == "FINAL_SELECTION" && reasons[index.toString()] == "EXACT_BATCH_COVERAGE") {
                val game = LottoBatchCoverage.mask(number.split(',').map(String::toInt))
                val replacements = selectedMasks.keys.mapNotNull { removed ->
                    val others = selectedMasks.filterKeys { it != removed }.values
                    var used = 0L
                    var repeated = 0L
                    for (other in others) { repeated = repeated or (used and other); used = used or other }
                    if ((game and repeated) != 0L || others.any { (it and game).countOneBits() > 2 }) null
                    else removed to LottoBatchCoverage.wins(others + game)
                }
                val best = replacements.maxByOrNull { it.second }
                put("bestSingleSwap", best?.let { mapOf("replaceIndex" to it.first, "winsChange" to
                    it.second - LottoDesignRecordJson.integer(requireNotNull(coverage)["after"])) })
            }
        }
    }
    return buildMap {
        put("gradeStages", counts); put("losses", outcomes); put("boundaryIndex", boundary)
        put("deltaColumns", if (lotto != null) listOf("데이터", "패턴", "분산", "공동당첨 회피", "직전 중복", "반복 패턴", "점수 상하한")
            else listOf("기본 순위 점수", "반복 패턴"))
        if (pension != null) {
            put("bonusKnown", pension.bonusNumber != null)
            put("rankingBasis", stage["rankingBasis"])
            stage["lastDigitSelection"]?.let { put("lastDigitSelection", it) }
            if (alternate != null) put("alternateShortlistGain",
                (alternate.count { matches[it] >= 2 } - ranked.count { matches[it] >= 2 }).toDouble() / ranked.size)
        }
        if (lotto != null && legacy && reasons.isNotEmpty()) put("selectionReasonBasis", "LEGACY_FINAL_SET")
        if (finalScoreLosses > 0) put("meanFinalDiversityDeficit", finalDiversityDeficit / finalScoreLosses)
        if (scoredLosses > 0 && weights != null && reference != null && scores != null) {
            val hitDeficit = rankLossSum.map { it / scoredLosses }
            put("rankingWeights", weights)
            put("meanComponentDeficit", hitDeficit)
            // 순위 밖에 있는 모든 조합에서 공통으로 낮은 항목을 당첨 후보만의 약점으로 오인하지 않는다.
            val otherDeficit = DoubleArray(3)
            var others = 0
            for (i in pool.indices) {
                if (i in retained || matches[i] >= 3) continue
                val row = LottoDesignRecordJson.list(scores[i])
                for (k in otherDeficit.indices) otherDeficit[k] += reference[k] - LottoDesignRecordJson.decimal(row[k + 1]) * weights[k]
                others++
            }
            if (others > 0) put("excessComponentDeficit", hitDeficit.mapIndexed { k, gap -> minOf(gap, gap - otherDeficit[k] / others) })
        }
    }
}
