package com.habittracker.data.lotto

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.habittracker.data.local.HabitTrackerDatabase
import com.habittracker.data.local.HabitTrackerDatabaseProtector
import com.habittracker.data.local.entity.LottoDrawEntity
import com.habittracker.data.local.entity.LottoTicketEntity
import com.habittracker.data.repository.HabitRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LottoPersistenceFixtureTest {
    private lateinit var database: HabitTrackerDatabase
    private lateinit var protector: HabitTrackerDatabaseProtector
    private lateinit var repository: HabitRepository

    @Before
    fun prepareOwnedDatabase() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "lotto-persistence-fixture-${UUID.randomUUID()}").canonicalFile.apply { check(mkdir()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
            override fun getDatabasePath(name: String): File = File(root, name)
        }
        database = Room.inMemoryDatabaseBuilder(context, HabitTrackerDatabase::class.java).build()
        protector = HabitTrackerDatabaseProtector(context)
        repository = HabitRepository(context, database, protector, database.habitDao())
        println("LOTTO_PERSISTENCE_FIXTURE_ROOT=${root.path}")
    }

    @After
    fun releaseOwnedDatabase() {
        if (::protector.isInitialized) protector.shutdown()
        if (::database.isInitialized) database.close()
    }

    @Test
    fun hiddenCrossStrategyDuplicateIsRejectedWithoutAddingGames() = runBlocking {
        val dao = database.habitDao()
        dao.insertLottoDraws(listOf(LottoDrawEntity.from(9998, (1..6).toList(), 7)))
        val snapshot = repository.getLottoGenerationSnapshot()
        val games = (0..4).map { offset -> (offset * 6 + 1..offset * 6 + 6).toList() }
        dao.insertLottoTicket(LottoTicketEntity.from("분산형", games.first(), roundNo = 9999, setNo = 1, recommendationRank = 1).copy(isHidden = true))
        val tickets = games.map { LottoGeneratedTicket(it, generationSnapshot = snapshot, generationMode = "BASIC", generationSeed = 42L) }
        try { repository.saveLottoBatch(9999, "균형형", tickets); fail("숨긴 다른 전략의 같은 조합도 저장을 거절해야 합니다.") }
        catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty().contains("숨긴 기록")) }
        assertEquals(1, dao.getLottoTicketsByRoundIncludingHidden(9999).size)
    }

    @Test
    fun rescanHiddenQrRestoresRowsWithoutPurchaseOrStatisticDuplication() = runBlocking {
        val qr = LottoQrPurchase(9999, listOf((1..6).toList(), (7..12).toList()))
        val first = repository.importLottoQrPurchase(qr)
        assertEquals(0, first.restoredGameCount)
        val before = database.habitDao().getLottoTicketsByRoundIncludingHidden(9999)
        val purchases = count("lotto_purchase")
        val stats = count("lotto_winning_stat")
        repository.deleteLottoRound(9999)
        assertTrue(database.habitDao().getLottoTicketsByRoundIncludingHidden(9999).all { it.isHidden })
        val restored = repository.importLottoQrPurchase(qr)
        assertEquals(2, restored.restoredGameCount)
        val after = database.habitDao().getLottoTicketsByRoundIncludingHidden(9999)
        assertEquals(before.map { it.id }, after.map { it.id })
        assertFalse(after.any { it.isHidden })
        assertEquals(purchases, count("lotto_purchase"))
        assertEquals(stats, count("lotto_winning_stat"))
        try { repository.importLottoQrPurchase(qr); fail("이미 보이는 같은 QR은 중복 구매를 추가하면 안 됩니다.") }
        catch (_: IllegalArgumentException) { }
        assertEquals(purchases, count("lotto_purchase"))
    }

    private suspend fun count(table: String): Long = withContext(Dispatchers.IO) {
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
    }
}
