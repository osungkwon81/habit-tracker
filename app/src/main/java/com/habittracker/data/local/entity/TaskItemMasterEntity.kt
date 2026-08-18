package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.habittracker.data.local.ValueType

/**
 * 사용자가 기록할 수 있는 항목의 정의다.
 * 실제 날짜별 값과 분리해 두면 항목 이름·단위는 한 번만 저장하고 여러 날짜에서 ID로 참조할 수 있다.
 */
@Entity(
    tableName = "task_item_master",
    indices = [
        Index(value = ["code"], unique = true),
        Index(value = ["category"]),
    ],
)
data class TaskItemMasterEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "code")
    val code: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "category")
    val category: String,
    @ColumnInfo(name = "value_type")
    val valueType: ValueType,
    @ColumnInfo(name = "unit")
    val unit: String?,
    @ColumnInfo(name = "description")
    val description: String?,
    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,
)
