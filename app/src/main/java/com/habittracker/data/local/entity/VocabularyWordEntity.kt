package com.habittracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDateTime

@Entity(
    tableName = "vocabulary_word",
    indices = [Index(value = ["word", "meaning"], unique = true)],
)
data class VocabularyWordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "word")
    val word: String,
    @ColumnInfo(name = "meaning")
    val meaning: String,
    @ColumnInfo(name = "pronunciation")
    val pronunciation: String? = null,
    @ColumnInfo(name = "correct_count")
    val correctCount: Int = 0,
    @ColumnInfo(name = "wrong_count")
    val wrongCount: Int = 0,
    @ColumnInfo(name = "exposure_count")
    val exposureCount: Int = 0,
    @ColumnInfo(name = "flashcard_study_seconds")
    val flashcardStudySeconds: Int = 0,
    @ColumnInfo(name = "test_study_seconds")
    val testStudySeconds: Int = 0,
    @ColumnInfo(name = "created_at")
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: LocalDateTime = LocalDateTime.now(),
)
