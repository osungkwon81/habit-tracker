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
            if (!database.hasColumn(tableName = "memo_note", columnName = "is_pinned")) {
                database.execSQL(
                    """
                    ALTER TABLE `memo_note`
                    ADD COLUMN `is_pinned` INTEGER NOT NULL DEFAULT 0
                    """.trimIndent(),
                )
            }
        }
    }

    private val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `plant` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `image_uri` TEXT,
                    `memo` TEXT,
                    `watering_interval_days` INTEGER NOT NULL,
                    `last_watered_date` TEXT NOT NULL,
                    `next_watering_date` TEXT NOT NULL,
                    `created_at` TEXT NOT NULL,
                    `updated_at` TEXT NOT NULL
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE INDEX IF NOT EXISTS `index_plant_next_watering_date`
                ON `plant` (`next_watering_date`)
                """.trimIndent(),
            )
        }
    }

    private val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                ALTER TABLE `lotto_ticket`
                ADD COLUMN `is_purchased` INTEGER NOT NULL DEFAULT 0
                """.trimIndent(),
            )
        }
    }

    private val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(database: SupportSQLiteDatabase) {
            if (!database.hasColumn(tableName = "lotto_draw", columnName = "bonus_number")) {
                database.execSQL(
                    """
                    ALTER TABLE `lotto_draw`
                    ADD COLUMN `bonus_number` INTEGER
                    """.trimIndent(),
                )
            }
        }
    }

    private val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `card_history` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `use_date` TEXT NOT NULL,
                    `amount` INTEGER NOT NULL,
                    `memo` TEXT,
                    `created_at` TEXT NOT NULL
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE INDEX IF NOT EXISTS `index_card_history_use_date`
                ON `card_history` (`use_date`)
                """.trimIndent(),
            )
            if (!database.hasColumn(tableName = "lotto_winning", columnName = "source_label")) {
                database.execSQL(
                    """
                    ALTER TABLE `lotto_winning`
                    ADD COLUMN `source_label` TEXT NOT NULL DEFAULT '기타'
                    """.trimIndent(),
                )
            }
        }
    }

    private val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `lotto_winning_stat` (
                    `source_label` TEXT NOT NULL,
                    `rank5_count` INTEGER NOT NULL,
                    `rank4_count` INTEGER NOT NULL,
                    `rank3_count` INTEGER NOT NULL,
                    `rank2_count` INTEGER NOT NULL,
                    `rank1_count` INTEGER NOT NULL,
                    `evaluated_ticket_count` INTEGER NOT NULL DEFAULT 0,
                    `style_pass_count` INTEGER NOT NULL DEFAULT 0,
                    `style_score_total` INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(`source_label`)
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `lotto_winning_stat_round` (
                    `round_no` INTEGER NOT NULL,
                    `source_label` TEXT NOT NULL,
                    `rank5_count` INTEGER NOT NULL,
                    `rank4_count` INTEGER NOT NULL,
                    `rank3_count` INTEGER NOT NULL,
                    `rank2_count` INTEGER NOT NULL,
                    `rank1_count` INTEGER NOT NULL,
                    `evaluated_ticket_count` INTEGER NOT NULL DEFAULT 0,
                    `style_pass_count` INTEGER NOT NULL DEFAULT 0,
                    `style_score_total` INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(`round_no`, `source_label`)
                )
                """.trimIndent(),
            )
        }
    }

    private val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(database: SupportSQLiteDatabase) {
            if (!database.hasColumn(tableName = "lotto_winning_stat", columnName = "evaluated_ticket_count")) {
                database.execSQL(
                    """
                    ALTER TABLE `lotto_winning_stat`
                    ADD COLUMN `evaluated_ticket_count` INTEGER NOT NULL DEFAULT 0
                    """.trimIndent(),
                )
            }
            if (!database.hasColumn(tableName = "lotto_winning_stat", columnName = "style_pass_count")) {
                database.execSQL(
                    """
                    ALTER TABLE `lotto_winning_stat`
                    ADD COLUMN `style_pass_count` INTEGER NOT NULL DEFAULT 0
                    """.trimIndent(),
                )
            }
            if (!database.hasColumn(tableName = "lotto_winning_stat", columnName = "style_score_total")) {
                database.execSQL(
                    """
                    ALTER TABLE `lotto_winning_stat`
                    ADD COLUMN `style_score_total` INTEGER NOT NULL DEFAULT 0
                    """.trimIndent(),
                )
            }
            if (!database.hasColumn(tableName = "lotto_winning_stat_round", columnName = "evaluated_ticket_count")) {
                database.execSQL(
                    """
                    ALTER TABLE `lotto_winning_stat_round`
                    ADD COLUMN `evaluated_ticket_count` INTEGER NOT NULL DEFAULT 0
                    """.trimIndent(),
                )
            }
            if (!database.hasColumn(tableName = "lotto_winning_stat_round", columnName = "style_pass_count")) {
                database.execSQL(
                    """
                    ALTER TABLE `lotto_winning_stat_round`
                    ADD COLUMN `style_pass_count` INTEGER NOT NULL DEFAULT 0
                    """.trimIndent(),
                )
            }
            if (!database.hasColumn(tableName = "lotto_winning_stat_round", columnName = "style_score_total")) {
                database.execSQL(
                    """
                    ALTER TABLE `lotto_winning_stat_round`
                    ADD COLUMN `style_score_total` INTEGER NOT NULL DEFAULT 0
                    """.trimIndent(),
                )
            }
            database.execSQL("UPDATE `lotto_winning_stat` SET `rank5_count` = 0, `rank4_count` = 0, `rank3_count` = 0, `rank2_count` = 0, `rank1_count` = 0")
        }
    }

    val all = arrayOf(
        MIGRATION_2_3,
        MIGRATION_3_5,
        MIGRATION_5_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14,
        MIGRATION_14_15,
        MIGRATION_15_16,
    )
}

private fun SupportSQLiteDatabase.hasColumn(tableName: String, columnName: String): Boolean =
    query("PRAGMA table_info(`$tableName`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        while (cursor.moveToNext()) {
            if (nameIndex >= 0 && cursor.getString(nameIndex) == columnName) {
                return@use true
            }
        }
        false
    }
