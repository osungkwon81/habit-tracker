package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import java.time.LocalDateTime

@Entity(
    tableName = "lotto_generation_config",
    primaryKeys = ["generation_version", "config_hash"],
)
data class LottoGenerationConfigEntity(
    @ColumnInfo(name = "generation_version")
    val generationVersion: String,
    @ColumnInfo(name = "config_json")
    val configJson: String,
    @ColumnInfo(name = "config_hash")
    val configHash: String,
    @ColumnInfo(name = "created_at")
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
