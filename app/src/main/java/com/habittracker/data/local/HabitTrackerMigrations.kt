package com.habittracker.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object HabitTrackerMigrations {
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `lotto_draw` (
                    `round_no` INTEGER NOT NULL,
                    `number1` INTEGER NOT NULL,
                    `number2` INTEGER NOT NULL,
                    `number3` INTEGER NOT NULL,
                    `number4` INTEGER NOT NULL,
                    `number5` INTEGER NOT NULL,
                    `number6` INTEGER NOT NULL,
                    `saved_at` TEXT NOT NULL,
                    PRIMARY KEY(`round_no`)
                )
                """.trimIndent(),
            )
        }
    }

    private val MIGRATION_3_5 = object : Migration(3, 5) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                ALTER TABLE `daily_record`
                ADD COLUMN `is_holiday` INTEGER NOT NULL DEFAULT 0
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `lotto_ticket` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `source_label` TEXT NOT NULL,
                    `number1` INTEGER NOT NULL,
                    `number2` INTEGER NOT NULL,
                    `number3` INTEGER NOT NULL,
                    `number4` INTEGER NOT NULL,
                    `number5` INTEGER NOT NULL,
                    `number6` INTEGER NOT NULL,
                    `note` TEXT,
                    `created_at` TEXT NOT NULL
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE INDEX IF NOT EXISTS `index_lotto_ticket_created_at`
                ON `lotto_ticket` (`created_at`)
                """.trimIndent(),
            )
        }
    }

    private val MIGRATION_5_8 = object : Migration(5, 8) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `memo_note` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `title` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `is_locked` INTEGER NOT NULL,
                    `is_pinned` INTEGER NOT NULL DEFAULT 0,
                    `password_hash` TEXT,
                    `created_at` TEXT NOT NULL,
                    `updated_at` TEXT NOT NULL
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `vocabulary_word` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `word` TEXT NOT NULL,
                    `meaning` TEXT NOT NULL,
                    `pronunciation` TEXT,
                    `correct_count` INTEGER NOT NULL DEFAULT 0,
                    `wrong_count` INTEGER NOT NULL DEFAULT 0,
                    `exposure_count` INTEGER NOT NULL DEFAULT 0,
                    `flashcard_study_seconds` INTEGER NOT NULL DEFAULT 0,
                    `test_study_seconds` INTEGER NOT NULL DEFAULT 0,
                    `created_at` TEXT NOT NULL,
                    `updated_at` TEXT NOT NULL
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS `index_vocabulary_word_word_meaning`
                ON `vocabulary_word` (`word`, `meaning`)
                """.trimIndent(),
            )
        }
    }

    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `lotto_purchase` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `purchase_date` TEXT NOT NULL,
                    `lotto_type` TEXT NOT NULL,
                    `amount` INTEGER NOT NULL,
                    `memo` TEXT,
                    `created_at` TEXT NOT NULL
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE INDEX IF NOT EXISTS `index_lotto_purchase_purchase_date`
                ON `lotto_purchase` (`purchase_date`)
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `lotto_winning` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `round_no` INTEGER NOT NULL,
                    `amount` INTEGER NOT NULL,
                    `memo` TEXT,
                    `created_at` TEXT NOT NULL
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE INDEX IF NOT EXISTS `index_lotto_winning_round_no`
                ON `lotto_winning` (`round_no`)
                """.trimIndent(),
            )
        }
    }

    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                ALTER TABLE `memo_note`
                ADD COLUMN `is_pinned` INTEGER NOT NULL DEFAULT 0
                """.trimIndent(),
            )
        }
    }

    val all = arrayOf(
        MIGRATION_2_3,
        MIGRATION_3_5,
        MIGRATION_5_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
    )
}
