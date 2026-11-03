package com.habittracker.data

import com.habittracker.data.local.HabitTrackerDatabase
import com.habittracker.data.local.HabitTrackerDatabaseProtector
import com.habittracker.data.local.entity.*
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate

fun householdPrice(purchase: HouseholdPurchase): BigDecimal = BigDecimal.valueOf(purchase.amount)
    .divide(BigDecimal(purchase.count), MathContext.DECIMAL128)

data class HouseholdSummary(val history: HouseholdProductHistory, val ordered: List<HouseholdPurchase>) {
    val latest = ordered.lastOrNull()
    val cheapest = ordered.minWithOrNull(compareBy<HouseholdPurchase> { householdPrice(it) }.thenByDescending { it.date }.thenBy { it.id })
}

class HouseholdPrices(private val db: HabitTrackerDatabase, private val backup: HabitTrackerDatabaseProtector) {
    private val dao = db.householdPriceDao()
    fun observe() = dao.observe()
    suspend fun register(product: HouseholdProduct, firstPurchase: HouseholdPurchase) = change {
        require(firstPurchase.productId == product.id) { "첫 구매 기록의 제품이 일치하지 않습니다." }
        saveProduct(product, editing = false)
        savePurchase(firstPurchase, editing = false)
    }
    suspend fun save(product: HouseholdProduct, editing: Boolean = false) = change { saveProduct(product, editing) }
    private suspend fun saveProduct(product: HouseholdProduct, editing: Boolean) {
        require(product.name.isNotBlank() && product.name.length <= 100) { "제품명은 1~100자로 입력해 주세요." }
        require(product.unit.isNotBlank() && product.unit.length <= 30) { "비교 단위를 1~30자로 입력해 주세요." }
        require(product.defaultCount > 0) { "구입 개수는 1 이상이어야 합니다." }
        val old = dao.product(product.id)
        require(!editing || old != null) { "수정할 제품이 삭제되었습니다: ${product.id}" }
        if (old == null) dao.insert(product) else check(dao.update(product) == 1)
    }
    suspend fun save(purchase: HouseholdPurchase, editing: Boolean = false) = change { savePurchase(purchase, editing) }
    private suspend fun savePurchase(purchase: HouseholdPurchase, editing: Boolean) {
        val product = requireNotNull(dao.product(purchase.productId)) { "제품이 삭제되었습니다. 목록을 확인해 주세요." }
        require(product.unit == purchase.unit) { "제품의 비교 단위가 변경됐습니다. 입력을 다시 열어 주세요." }
        LocalDate.parse(purchase.date)
        require(purchase.count > 0) { "구입 개수는 1 이상이어야 합니다." }
        require(purchase.amount >= 0 && purchase.shop.length <= 100 && purchase.note.length <= 1000) { "금액·판매처·메모를 확인해 주세요." }
        val old = dao.purchase(purchase.id)
        require(!editing || old != null) { "수정할 구매 기록이 삭제되었습니다: ${purchase.id}" }
        require(old == null || old.productId == purchase.productId) { "구매 기록의 제품이 일치하지 않습니다." }
        if (old == null) dao.insert(purchase.copy(recordedOrder = dao.nextPurchaseOrder()))
        else check(dao.update(purchase.copy(recordedOrder = old.recordedOrder)) == 1)
    }
    suspend fun deleteProduct(id: String) = change { check(dao.deleteProduct(id) == 1) { "삭제할 제품이 없습니다: $id" } }
    suspend fun deletePurchase(id: String) = change { check(dao.deletePurchase(id) == 1) { "삭제할 구매 기록이 없습니다: $id" } }
    private suspend fun change(block: suspend () -> Unit) = withContext(Dispatchers.IO) {
        db.withTransaction { block() }; backup.requestBackup(db)
    }
}
