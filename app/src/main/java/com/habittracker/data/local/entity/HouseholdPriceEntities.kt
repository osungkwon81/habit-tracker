package com.habittracker.data.local.entity

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "household_product")
data class HouseholdProduct(
    @PrimaryKey val id: String,
    val name: String,
    val unit: String,
    val defaultSize: String,
    val defaultCount: Int,
)

@Entity(tableName = "household_purchase", foreignKeys = [ForeignKey(entity = HouseholdProduct::class,
    parentColumns = ["id"], childColumns = ["productId"], onDelete = ForeignKey.CASCADE)], indices = [Index("productId")])
data class HouseholdPurchase(
    @PrimaryKey val id: String,
    val productId: String,
    val date: String,
    val size: String,
    val count: Int,
    val unit: String,
    val amount: Long,
    val shop: String,
    val note: String,
    @ColumnInfo(defaultValue = "0") val recordedOrder: Long = 0,
)

data class HouseholdProductHistory(
    @Embedded val product: HouseholdProduct,
    @Relation(parentColumn = "id", entityColumn = "productId") val purchases: List<HouseholdPurchase>,
)

@Dao
interface HouseholdPriceDao {
    @Query("SELECT COALESCE(MAX(recordedOrder), 0) + 1 FROM household_purchase") suspend fun nextPurchaseOrder(): Long
    @Transaction @Query("SELECT * FROM household_product ORDER BY name COLLATE LOCALIZED, id")
    fun observe(): Flow<List<HouseholdProductHistory>>
    @Query("SELECT * FROM household_product WHERE id = :id") suspend fun product(id: String): HouseholdProduct?
    @Query("SELECT * FROM household_purchase WHERE id = :id") suspend fun purchase(id: String): HouseholdPurchase?
    @Query("SELECT COUNT(*) FROM household_purchase WHERE productId = :id") suspend fun purchaseCount(id: String): Int
    @Insert suspend fun insert(product: HouseholdProduct)
    @Update suspend fun update(product: HouseholdProduct): Int
    @Insert suspend fun insert(purchase: HouseholdPurchase)
    @Update suspend fun update(purchase: HouseholdPurchase): Int
    @Query("DELETE FROM household_product WHERE id = :id") suspend fun deleteProduct(id: String): Int
    @Query("DELETE FROM household_purchase WHERE id = :id") suspend fun deletePurchase(id: String): Int
}
