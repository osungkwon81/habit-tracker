package com.habittracker.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExchangeMigration44Test {
    @Test fun migrationPreservesAmountsAndDatesWithoutInventingNewFields() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(43) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE exchange_record (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, currency TEXT NOT NULL, won_amount TEXT NOT NULL, applied_rate TEXT NOT NULL)")
                        db.execSQL("CREATE TABLE exchange_expense (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, currency TEXT NOT NULL, foreign_amount TEXT NOT NULL, description TEXT NOT NULL, expense_date TEXT)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        try {
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO exchange_record VALUES (1, 'JPY', '90000', '900')")
            db.execSQL("INSERT INTO exchange_expense VALUES (1, 'JPY', '2000', '기존 지출', '2026-10-02')")
            HabitTrackerMigrations.all.single { it.startVersion == 43 && it.endVersion == 44 }.migrate(db)
            db.query("SELECT won_amount, applied_rate, exchange_date, exchange_time FROM exchange_record WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("90000", cursor.getString(0)); assertEquals("900", cursor.getString(1))
                assertTrue(cursor.isNull(2)); assertTrue(cursor.isNull(3))
            }
            db.query("SELECT foreign_amount, description, expense_date, expense_time, payment_method, card_won_amount FROM exchange_expense WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("2000", cursor.getString(0)); assertEquals("기존 지출", cursor.getString(1))
                assertEquals("2026-10-02", cursor.getString(2))
                assertTrue(cursor.isNull(3)); assertTrue(cursor.isNull(4)); assertTrue(cursor.isNull(5))
            }
        } finally { helper.close() }
    }
}
