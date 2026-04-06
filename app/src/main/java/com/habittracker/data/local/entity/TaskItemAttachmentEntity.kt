package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "task_item_attachment",
    foreignKeys = [
        ForeignKey(
            entity = TaskItemMasterEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_item_master_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["task_item_master_id"])],
)
data class TaskItemAttachmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "task_item_master_id")
    val taskItemMasterId: Long,
    @ColumnInfo(name = "file_uri")
    val fileUri: String,
    @ColumnInfo(name = "file_name")
    val fileName: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "description")
    val description: String?,
)
