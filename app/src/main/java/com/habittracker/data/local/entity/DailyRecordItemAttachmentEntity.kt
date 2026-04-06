package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "daily_record_item_attachment",
    foreignKeys = [
        ForeignKey(
            entity = DailyRecordItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["daily_record_item_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["daily_record_item_id"])],
)
data class DailyRecordItemAttachmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "daily_record_item_id")
    val dailyRecordItemId: Long,
    @ColumnInfo(name = "file_uri")
    val fileUri: String,
    @ColumnInfo(name = "file_name")
    val fileName: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "description")
    val description: String?,
)
