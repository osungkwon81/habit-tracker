package com.habittracker.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.habittracker.data.local.HabitTrackerMigrations
import com.habittracker.data.local.entity.HouseholdPurchase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal

@RunWith(AndroidJUnit4::class)
class HouseholdPricesTest {
    private fun purchase(size: String, count: Int, amount: Long, unit: String = "ML") = HouseholdPurchase("p", "item", "2026-10-30", size, count, unit, amount, "", "")
    @Test fun priceUsesPurchasedCount() {
        val small = householdPrice(purchase("500", 2, 12000))
        val large = householdPrice(purchase("1000", 2, 20000))
        assertEquals(0, BigDecimal("6000").compareTo(small))
        assertEquals(0, BigDecimal("10000").compareTo(large))
        assertTrue(small < large)
    }
    @Test fun sizeDoesNotAffectPricePerPurchasedItem() {
        val price = householdPrice(purchase("30", 30, 18000, "M"))
        assertEquals(0, BigDecimal("600").compareTo(price))
        assertEquals(price, householdPrice(purchase("15", 30, 18000, "M")))
    }
    @Test fun smallAndZeroPricesAreNotRoundedBeforeComparison() {
        assertTrue(householdPrice(purchase("999", 1000, 1)) < householdPrice(purchase("998", 999, 1)))
        assertEquals(0, householdPrice(purchase("500", 1, 0)).signum())
    }
    @Test fun migrationPreservesExistingRowsAndCascadesOnlyProductPurchases() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(
            InstrumentationRegistry.getInstrumentation().targetContext).name(null).callback(object : SupportSQLiteOpenHelper.Callback(44) {
            override fun onConfigure(db: SupportSQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
            override fun onCreate(db: SupportSQLiteDatabase) { db.execSQL("CREATE TABLE previous_data (value TEXT NOT NULL)"); db.execSQL("INSERT INTO previous_data VALUES ('keep')") }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }).build())
        try {
            val db = helper.writableDatabase
            HabitTrackerMigrations.all.single { it.startVersion == 44 && it.endVersion == 45 }.migrate(db)
            HabitTrackerMigrations.all.single { it.startVersion == 45 && it.endVersion == 46 }.migrate(db)
            db.execSQL("INSERT INTO household_product VALUES ('item','샴푸','ML','500',2)")
            db.execSQL("INSERT INTO household_purchase VALUES ('p','item','2026-10-30','500',2,'ML',12000,'판매처','',1)")
            HabitTrackerMigrations.all.single { it.startVersion == 46 && it.endVersion == 47 }.migrate(db)
            db.query("SELECT unit, count, amount FROM household_purchase").use {
                assertTrue(it.moveToFirst()); assertEquals("개", it.getString(0)); assertEquals(2, it.getInt(1)); assertEquals(12000L, it.getLong(2))
            }
            db.execSQL("DELETE FROM household_product WHERE id = 'item'")
            db.query("SELECT COUNT(*) FROM household_purchase").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            db.query("SELECT value FROM previous_data").use { assertTrue(it.moveToFirst()); assertEquals("keep", it.getString(0)) }
        } finally { helper.close() }
    }
}
