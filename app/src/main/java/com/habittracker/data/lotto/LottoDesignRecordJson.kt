package com.habittracker.data.lotto

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal object LottoDesignRecordJson {
    const val VERSION = 1

    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean -> value.toString()
        is Int, is Long -> value.toString()
        is Double -> {
            require(value.isFinite()) { "실행 기록에 유한하지 않은 실수를 저장할 수 없습니다." }
            if (value == 0.0) "0.0" else value.toString()
        }
        is List<*> -> value.joinToString(",", "[", "]") { encode(it) }
        is Map<*, *> -> {
            require(value.keys.all { it is String }) { "JSON 객체 키는 문자열이어야 합니다." }
            value.keys.map { it as String }.sorted().joinToString(",", "{", "}") {
                "${quote(it)}:${encode(value[it])}"
            }
        }
        else -> error("지원하지 않는 실행 기록 값: ${value.javaClass.name}")
    }

    fun decode(bytes: ByteArray, requireCanonical: Boolean = true): Map<String, Any?> {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val tokener = JSONTokener(text)
        val parsed = unwrap(tokener.nextValue())
        require(tokener.nextClean() == '\u0000') { "JSON 문서 뒤에 추가 데이터가 있습니다." }
        require(!requireCanonical || encode(parsed) == text) { "실행 기록이 버전 1 정규 JSON 형식이 아닙니다." }
        return objectValue(parsed)
    }

    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    fun hash(value: Any?): String = hash(encode(value).toByteArray(Charsets.UTF_8))

    @Suppress("UNCHECKED_CAST")
    fun objectValue(value: Any?): Map<String, Any?> {
        require(value is Map<*, *> && value.keys.all { it is String }) { "JSON 객체가 필요합니다." }
        return value as Map<String, Any?>
    }

    fun list(value: Any?): List<Any?> {
        require(value is List<*>) { "JSON 배열이 필요합니다." }
        return value
    }

    fun string(value: Any?): String {
        require(value is String) { "JSON 문자열이 필요합니다." }
        return value
    }

    fun integer(value: Any?): Int {
        require(value is Int || value is Long) { "JSON 정수가 필요합니다." }
        val number = (value as Number).toLong()
        require(number >= Int.MIN_VALUE.toLong() && number <= Int.MAX_VALUE.toLong()) { "정수 범위를 초과했습니다." }
        return number.toInt()
    }

    fun decimal(value: Any?): Double {
        require(value is Number && value.toDouble().isFinite()) { "유한한 JSON 숫자가 필요합니다." }
        return value.toDouble()
    }

    fun longInteger(value: Any?): Long {
        require(value is Int || value is Long) { "JSON 정수가 필요합니다." }
        return (value as Number).toLong()
    }

    private fun unwrap(value: Any?): Any? = when (value) {
        JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().associateWith { unwrap(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { unwrap(value.get(it)) }
        else -> value
    }

    private fun quote(value: String): String = buildString {
        append('"')
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when {
                character == '"' -> append("\\\"")
                character == '\\' -> append("\\\\")
                character.code < 32 -> append("\\u").append(character.code.toString(16).padStart(4, '0'))
                character.isHighSurrogate() -> {
                    require(index + 1 < value.length && value[index + 1].isLowSurrogate()) { "잘못된 Unicode surrogate입니다." }
                    append(character).append(value[++index])
                }
                character.isLowSurrogate() -> error("잘못된 Unicode surrogate입니다.")
                else -> append(character)
            }
            index++
        }
        append('"')
    }
}
