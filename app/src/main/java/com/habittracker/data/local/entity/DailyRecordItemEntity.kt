package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 특정 날짜 기록과 항목 정의를 연결하는 값 객체다.
 * ForeignKey와 복합 unique index가 같은 날짜에 같은 항목이 중복 저장되는 것을 DB에서도 막는다.
 */
@Entity(
    tableName = "daily_record_item",
    foreignKeys = [
        ForeignKey(
            entity = DailyRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["daily_record_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TaskItemMasterEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_item_master_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["daily_record_id"]),
        Index(value = ["task_item_master_id"]),
        Index(value = ["daily_record_id", "task_item_master_id"], unique = true),
    ],
)
data class DailyRecordItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "daily_record_id")
    val dailyRecordId: Long,
    @ColumnInfo(name = "task_item_master_id")
    val taskItemMasterId: Long,
    @ColumnInfo(name = "number_value")
    val numberValue: Double?,
    @ColumnInfo(name = "boolean_value")
    val booleanValue: Boolean?,
    @ColumnInfo(name = "text_value")
    val textValue: String?,
    @ColumnInfo(name = "duration_minutes")
    val durationMinutes: Int?,
    @ColumnInfo(name = "checked")
    val checked: Boolean = false,
    @ColumnInfo(name = "note")
    val note: String?,
)
