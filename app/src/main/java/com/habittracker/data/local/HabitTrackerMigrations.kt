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

    private val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(database: SupportSQLiteDatabase) {
            if (!database.hasColumn(tableName = "lotto_ticket", columnName = "generation_version")) {
                database.execSQL(
                    """
                    ALTER TABLE `lotto_ticket`
                    ADD COLUMN `generation_version` TEXT NOT NULL DEFAULT 'legacy'
                    """.trimIndent(),
                )
            }
        }
    }

    private val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(database: SupportSQLiteDatabase) {
            listOf(
                "analysis_score",
                "data_score",
                "pattern_score",
                "distribution_score",
                "avoidance_score",
                "validation_score",
            ).forEach { columnName ->
                if (!database.hasColumn(tableName = "lotto_ticket", columnName = columnName)) {
                    database.execSQL("ALTER TABLE `lotto_ticket` ADD COLUMN `$columnName` REAL")
                }
            }
            if (!database.hasColumn(tableName = "lotto_ticket", columnName = "generation_mode")) {
                database.execSQL("ALTER TABLE `lotto_ticket` ADD COLUMN `generation_mode` TEXT")
            }
            if (!database.hasColumn(tableName = "lotto_ticket", columnName = "recommendation_rank")) {
                database.execSQL("ALTER TABLE `lotto_ticket` ADD COLUMN `recommendation_rank` INTEGER")
            }

            database.execSQL("DROP TABLE IF EXISTS `lotto_winning_stat`")
            database.execSQL("DROP TABLE IF EXISTS `lotto_winning_stat_round`")
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `lotto_winning_stat` (
                    `source_label` TEXT NOT NULL,
                    `generation_version` TEXT NOT NULL,
                    `rank5_count` INTEGER NOT NULL,
                    `rank4_count` INTEGER NOT NULL,
                    `rank3_count` INTEGER NOT NULL,
                    `rank2_count` INTEGER NOT NULL,
                    `rank1_count` INTEGER NOT NULL,
                    `evaluated_ticket_count` INTEGER NOT NULL,
                    `style_pass_count` INTEGER NOT NULL,
                    `style_score_total` INTEGER NOT NULL,
                    `scored_ticket_count` INTEGER NOT NULL,
                    `analysis_score_total` REAL NOT NULL,
                    `match_count_total` INTEGER NOT NULL,
                    PRIMARY KEY(`source_label`, `generation_version`)
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `lotto_winning_stat_round` (
                    `round_no` INTEGER NOT NULL,
                    `source_label` TEXT NOT NULL,
                    `generation_version` TEXT NOT NULL,
                    `rank5_count` INTEGER NOT NULL,
                    `rank4_count` INTEGER NOT NULL,
                    `rank3_count` INTEGER NOT NULL,
                    `rank2_count` INTEGER NOT NULL,
                    `rank1_count` INTEGER NOT NULL,
                    `evaluated_ticket_count` INTEGER NOT NULL,
                    `style_pass_count` INTEGER NOT NULL,
                    `style_score_total` INTEGER NOT NULL,
                    `scored_ticket_count` INTEGER NOT NULL,
                    `analysis_score_total` REAL NOT NULL,
                    `match_count_total` INTEGER NOT NULL,
                    PRIMARY KEY(`round_no`, `source_label`, `generation_version`)
                )
                """.trimIndent(),
            )
        }
    }

    private val MIGRATION_18_19 = object : Migration(18, 19) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `kis_api_config` (
                    `environment` TEXT NOT NULL,
                    `app_key_encrypted` TEXT NOT NULL,
                    `app_secret_encrypted` TEXT NOT NULL,
                    `account_number_encrypted` TEXT NOT NULL,
                    `account_product_code_encrypted` TEXT NOT NULL,
                    `hts_id_encrypted` TEXT,
                    `access_token_encrypted` TEXT,
                    `access_token_expired_at` TEXT,
                    `updated_at` TEXT NOT NULL,
                    PRIMARY KEY(`environment`)
                )
                """.trimIndent(),
            )
        }
    }

    private val MIGRATION_19_20 = object : Migration(19, 20) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `stock_order` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `order_number` TEXT NOT NULL,
                    `order_date` TEXT NOT NULL,
                    `order_time` TEXT NOT NULL,
                    `side` TEXT NOT NULL,
                    `product_code` TEXT NOT NULL,
                    `product_name` TEXT NOT NULL,
                    `requested_quantity` INTEGER NOT NULL,
                    `requested_unit_price` INTEGER NOT NULL,
                    `reference_price` INTEGER NOT NULL,
                    `order_division_code` TEXT NOT NULL,
                    `exchange_code` TEXT NOT NULL,
                    `source` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `filled_quantity` INTEGER NOT NULL,
                    `applied_filled_quantity` INTEGER NOT NULL,
                    `filled_average_price` INTEGER,
                    `remaining_quantity` INTEGER NOT NULL,
                    `estimated_realized_profit` INTEGER,
                    `message` TEXT,
                    `created_at` TEXT NOT NULL,
                    `updated_at` TEXT NOT NULL
                )
                """.trimIndent(),
            )
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_stock_order_order_date_order_number` ON `stock_order` (`order_date`, `order_number`)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_order_product_code` ON `stock_order` (`product_code`)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_order_status` ON `stock_order` (`status`)")
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `stock_exit_rule` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `product_code` TEXT NOT NULL,
                    `product_name` TEXT NOT NULL,
                    `rule_type` TEXT NOT NULL,
                    `trigger_value` REAL NOT NULL,
                    `sell_quantity_percent` REAL NOT NULL,
                    `action_mode` TEXT NOT NULL,
                    `order_division_code` TEXT NOT NULL,
                    `reference_high_price` INTEGER,
                    `enabled` INTEGER NOT NULL,
                    `last_triggered_at` TEXT,
                    `created_at` TEXT NOT NULL,
                    `updated_at` TEXT NOT NULL
                )
                """.trimIndent(),
            )
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_exit_rule_product_code_enabled` ON `stock_exit_rule` (`product_code`, `enabled`)")
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `stock_target_allocation` (
                    `product_code` TEXT NOT NULL,
                    `product_name` TEXT NOT NULL,
                    `target_percent` REAL NOT NULL,
                    `enabled` INTEGER NOT NULL,
                    `updated_at` TEXT NOT NULL,
                    PRIMARY KEY(`product_code`)
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `stock_safety_config` (
                    `id` INTEGER NOT NULL,
                    `monitoring_enabled` INTEGER NOT NULL,
                    `automatic_order_enabled` INTEGER NOT NULL,
                    `global_order_blocked` INTEGER NOT NULL,
                    `block_reason` TEXT,
                    `crash_guard_enabled` INTEGER NOT NULL,
                    `crash_benchmark_code` TEXT,
                    `crash_threshold_percent` REAL,
                    `monitor_interval_minutes` INTEGER,
                    `max_order_amount` INTEGER,
                    `daily_buy_limit` INTEGER,
                    `updated_at` TEXT NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `stock_automation_event` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `level` TEXT NOT NULL,
                    `event_type` TEXT NOT NULL,
                    `product_code` TEXT,
                    `message` TEXT NOT NULL,
                    `created_at` TEXT NOT NULL
                )
                """.trimIndent(),
            )
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_automation_event_created_at` ON `stock_automation_event` (`created_at`)")
        }
    }

    private val MIGRATION_20_21 = object : Migration(20, 21) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                ALTER TABLE `lotto_ticket`
                ADD COLUMN `is_evaluation_target` INTEGER NOT NULL DEFAULT 0
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `lotto_generation_config` (
                    `generation_version` TEXT NOT NULL,
                    `config_json` TEXT NOT NULL,
                    `created_at` TEXT NOT NULL,
                    PRIMARY KEY(`generation_version`)
                )
                """.trimIndent(),
            )
        }
    }

    private val MIGRATION_21_22 = object : Migration(21, 22) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE `lotto_generation_config_new` (
                    `generation_version` TEXT NOT NULL,
                    `config_json` TEXT NOT NULL,
                    `config_hash` TEXT NOT NULL,
                    `created_at` TEXT NOT NULL,
                    PRIMARY KEY(`generation_version`)
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO `lotto_generation_config_new` (
                    `generation_version`,
                    `config_json`,
                    `config_hash`,
                    `created_at`
                )
                SELECT `generation_version`, `config_json`, '', `created_at`
                FROM `lotto_generation_config`
                """.trimIndent(),
            )
            database.execSQL("DROP TABLE `lotto_generation_config`")
            database.execSQL("ALTER TABLE `lotto_generation_config_new` RENAME TO `lotto_generation_config`")
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
        MIGRATION_16_17,
        MIGRATION_17_18,
        MIGRATION_18_19,
        MIGRATION_19_20,
        MIGRATION_20_21,
        MIGRATION_21_22,
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
