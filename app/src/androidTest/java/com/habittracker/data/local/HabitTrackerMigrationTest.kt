package com.habittracker.data.local

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class HabitTrackerMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        context.deleteDatabase(V3_TEST_DB)
        context.deleteDatabase(V5_TEST_DB)
    }

    @Test
    fun migrateFromV3ToV8_preservesRecordsAndAddsHolidayColumn() {
        createVersion3Database(V3_TEST_DB)

        val database = Room.databaseBuilder(context, HabitTrackerDatabase::class.java, V3_TEST_DB)
            .addMigrations(*HabitTrackerMigrations.all)
            .build()
        try {
            database.openHelper.writableDatabase

            database.openHelper.readableDatabase.query(
                "SELECT memo, is_holiday FROM daily_record WHERE id = 1",
            ).useCursor { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("legacy memo", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
            }

            database.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM lotto_ticket",
            ).useCursor { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun migrateFromV5ToV8_preservesHolidayAndLottoTickets() {
        createVersion5Database(V5_TEST_DB)

        val database = Room.databaseBuilder(context, HabitTrackerDatabase::class.java, V5_TEST_DB)
            .addMigrations(*HabitTrackerMigrations.all)
            .build()
        try {
            database.openHelper.writableDatabase

            database.openHelper.readableDatabase.query(
                "SELECT memo, is_holiday FROM daily_record WHERE id = 1",
            ).useCursor { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("holiday memo", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
            }

            database.openHelper.readableDatabase.query(
                "SELECT source_label, note FROM lotto_ticket WHERE id = 1",
            ).useCursor { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("수동입력", cursor.getString(0))
                assertEquals("legacy-ticket", cursor.getString(1))
            }
        } finally {
            database.close()
        }
    }

    private fun createVersion3Database(name: String) {
        createDatabase(name, version = 3) { db ->
            db.execSQL(
                """
                CREATE TABLE `task_item_master` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `code` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `category` TEXT NOT NULL,
                    `value_type` TEXT NOT NULL,
                    `unit` TEXT,
                    `description` TEXT,
                    `is_active` INTEGER NOT NULL,
                    `sort_order` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE UNIQUE INDEX `index_task_item_master_code` ON `task_item_master` (`code`)")
            db.execSQL("CREATE INDEX `index_task_item_master_category` ON `task_item_master` (`category`)")

            db.execSQL(
                """
                CREATE TABLE `daily_record` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `record_date` TEXT NOT NULL,
                    `memo` TEXT,
                    `created_at` TEXT NOT NULL,
                    `updated_at` TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE UNIQUE INDEX `index_daily_record_record_date` ON `daily_record` (`record_date`)")

            db.execSQL(
                """
                CREATE TABLE `daily_record_item` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `daily_record_id` INTEGER NOT NULL,
                    `task_item_master_id` INTEGER NOT NULL,
                    `number_value` REAL,
                    `boolean_value` INTEGER,
                    `text_value` TEXT,
                    `duration_minutes` INTEGER,
                    `checked` INTEGER NOT NULL,
                    `note` TEXT,
                    FOREIGN KEY(`daily_record_id`) REFERENCES `daily_record`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`task_item_master_id`) REFERENCES `task_item_master`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX `index_daily_record_item_daily_record_id` ON `daily_record_item` (`daily_record_id`)")
            db.execSQL("CREATE INDEX `index_daily_record_item_task_item_master_id` ON `daily_record_item` (`task_item_master_id`)")
            db.execSQL(
                """
                CREATE UNIQUE INDEX `index_daily_record_item_daily_record_id_task_item_master_id`
                ON `daily_record_item` (`daily_record_id`, `task_item_master_id`)
                """.trimIndent(),
            )

            db.execSQL(
                """
                CREATE TABLE `task_item_attachment` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `task_item_master_id` INTEGER NOT NULL,
                    `file_uri` TEXT NOT NULL,
                    `file_name` TEXT NOT NULL,
                    `mime_type` TEXT NOT NULL,
                    `description` TEXT,
                    FOREIGN KEY(`task_item_master_id`) REFERENCES `task_item_master`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX `index_task_item_attachment_task_item_master_id` ON `task_item_attachment` (`task_item_master_id`)")

            db.execSQL(
                """
                CREATE TABLE `daily_record_item_attachment` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `daily_record_item_id` INTEGER NOT NULL,
                    `file_uri` TEXT NOT NULL,
                    `file_name` TEXT NOT NULL,
                    `mime_type` TEXT NOT NULL,
                    `description` TEXT,
                    FOREIGN KEY(`daily_record_item_id`) REFERENCES `daily_record_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX `index_daily_record_item_attachment_daily_record_item_id` ON `daily_record_item_attachment` (`daily_record_item_id`)")

            db.execSQL(
                """
                CREATE TABLE `daily_diary` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `diary_date` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `body` TEXT NOT NULL,
                    `weather` TEXT NOT NULL,
                    `image_uris` TEXT NOT NULL,
                    `updated_at` TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE UNIQUE INDEX `index_daily_diary_diary_date` ON `daily_diary` (`diary_date`)")

            db.execSQL(
                """
                CREATE TABLE `lotto_draw` (
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

            db.execSQL(
                """
                INSERT INTO task_item_master (id, code, name, category, value_type, unit, description, is_active, sort_order)
                VALUES (1, 'PUSH_UP', '푸시업', '운동', 'NUMBER', '회', 'legacy', 1, 10)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO daily_record (id, record_date, memo, created_at, updated_at)
                VALUES (1, '2026-04-01', 'legacy memo', '2026-04-01T08:00:00', '2026-04-01T08:00:00')
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO daily_record_item (id, daily_record_id, task_item_master_id, number_value, boolean_value, text_value, duration_minutes, checked, note)
                VALUES (1, 1, 1, 15.0, NULL, NULL, NULL, 1, 'legacy item')
                """.trimIndent(),
            )
        }
    }

    private fun createVersion5Database(name: String) {
        createVersion3Database(name)
        createDatabase(name, recreate = false, version = 5) { db ->
            db.execSQL("ALTER TABLE `daily_record` ADD COLUMN `is_holiday` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE `daily_record` SET `memo` = 'holiday memo', `is_holiday` = 1 WHERE `id` = 1")
            db.execSQL(
                """
                CREATE TABLE `lotto_ticket` (
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
            db.execSQL("CREATE INDEX `index_lotto_ticket_created_at` ON `lotto_ticket` (`created_at`)")
            db.execSQL(
                """
                INSERT INTO lotto_ticket (id, source_label, number1, number2, number3, number4, number5, number6, note, created_at)
                VALUES (1, '수동입력', 1, 2, 3, 4, 5, 6, 'legacy-ticket', '2026-04-02T09:00:00')
                """.trimIndent(),
            )
        }
    }

    private fun createDatabase(
        name: String,
        recreate: Boolean = true,
        version: Int,
        builder: (SQLiteDatabase) -> Unit,
    ) {
        if (recreate) {
            context.deleteDatabase(name)
        }
        val databaseFile = context.getDatabasePath(name)
        databaseFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { db ->
            builder(db)
            db.execSQL("PRAGMA user_version = $version")
        }
    }

    private companion object {
        private const val V3_TEST_DB = "migration-v3-test.db"
        private const val V5_TEST_DB = "migration-v5-test.db"
    }
}

private inline fun <T> Cursor.useCursor(block: (Cursor) -> T): T =
    use(block)
