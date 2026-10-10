package com.habittracker.data.local

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LottoMigration39FixtureTest {
    @Test
    fun migration38To39PreservesLegacyRowsWithoutInventingSnapshots() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "lotto-migration-fixture-${UUID.randomUUID()}").canonicalFile.apply { check(mkdir()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = File(root, name)
        }
        val name = "migration-38-39-fixture.db"
        val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("com.habittracker.data.local.HabitTrackerDatabase/38.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("${'$'}{TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (entry in 0 until indices.length()) db.execSQL(indices.getJSONObject(entry).getString("createSql").replace("${'$'}{TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) db.execSQL(setup.getString(index))
            db.execSQL("INSERT INTO lotto_ticket(id,source_label,number1,number2,number3,number4,number5,number6,created_at,generation_version,is_purchased,is_evaluation_target) VALUES(1,'균형형',1,2,3,4,5,6,'2026-01-01T00:00:00','legacy',0,0)")
            db.execSQL("INSERT INTO pension_lottery_generated_number(id,generation_id,generation_type,group_no,winning_number,digit_scores,total_score,score_band,duplicate_label,cold_positions,cold_priority_scores,generated_at,generation_version,is_control,is_evaluation_target,is_hidden) VALUES(1,'fixture','fixture',1,'123456','[]',100,'fixture','fixture','[]','[]','2026-01-01T00:00:00','legacy',0,0,0)")
            db.version = 38
        }
        val database = Room.databaseBuilder(context, HabitTrackerDatabase::class.java, name)
            .addMigrations(*HabitTrackerMigrations.all).build()
        try {
            val db = database.openHelper.writableDatabase
            assertEquals(39, db.version)
            db.query("SELECT number1,number6,generation_version,input_data_hash,is_hidden FROM lotto_ticket WHERE id=1").use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals(1, cursor.getInt(0)); assertEquals(6, cursor.getInt(1))
                assertEquals("legacy", cursor.getString(2)); assertTrue(cursor.isNull(3)); assertEquals(0, cursor.getInt(4))
            }
            db.query("SELECT winning_number,total_score,generation_config_json FROM pension_lottery_generated_number WHERE id=1").use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals("123456", cursor.getString(0)); assertEquals(100, cursor.getInt(1)); assertTrue(cursor.isNull(2))
            }
        } finally { database.close() }
        println("LOTTO_MIGRATION_FIXTURE_ROOT=${root.path}")
    }
}
