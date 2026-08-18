package com.habittracker.data.local

import androidx.room.TypeConverter
import java.time.LocalDate
import java.time.LocalDateTime

/** Room이 직접 저장할 수 없는 Kotlin/Java 타입을 DB의 문자열과 상호 변환한다. */
class Converters {
    @TypeConverter
    fun fromLocalDate(value: LocalDate?): String? = value?.toString()

    @TypeConverter
    fun toLocalDate(value: String?): LocalDate? = value?.let(LocalDate::parse)

    @TypeConverter
    fun fromLocalDateTime(value: LocalDateTime?): String? = value?.toString()

    @TypeConverter
    fun toLocalDateTime(value: String?): LocalDateTime? = value?.let(LocalDateTime::parse)

    @TypeConverter
    fun fromValueType(value: ValueType?): String? = value?.name

    @TypeConverter
    fun toValueType(value: String?): ValueType? = value?.let(ValueType::valueOf)
}
