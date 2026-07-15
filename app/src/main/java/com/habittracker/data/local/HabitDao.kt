package com.habittracker.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.habittracker.data.local.entity.DailyDiaryEntity
import com.habittracker.data.local.entity.DailyRecordEntity
import com.habittracker.data.local.entity.DailyRecordItemEntity
import com.habittracker.data.local.entity.CardHistoryEntity
import com.habittracker.data.local.entity.LottoDrawEntity
import com.habittracker.data.local.entity.LottoPurchaseEntity
import com.habittracker.data.local.entity.LottoTicketEntity
import com.habittracker.data.local.entity.LottoWinningEntity
import com.habittracker.data.local.entity.LottoWinningStatEntity
import com.habittracker.data.local.entity.LottoWinningStatRoundEntity
import com.habittracker.data.local.entity.KisApiConfigEntity
import com.habittracker.data.local.entity.MemoNoteEntity
import com.habittracker.data.local.entity.PlantEntity
import com.habittracker.data.local.entity.StockAutomationEventEntity
import com.habittracker.data.local.entity.StockExitRuleEntity
import com.habittracker.data.local.entity.StockOrderEntity
import com.habittracker.data.local.entity.StockSafetyConfigEntity
import com.habittracker.data.local.entity.StockTargetAllocationEntity
import com.habittracker.data.local.entity.TaskItemMasterEntity
import com.habittracker.data.local.entity.VocabularyWordEntity
import com.habittracker.data.local.model.DiarySearchRow
import com.habittracker.data.local.model.DiarySummaryRow
import com.habittracker.data.local.model.DailyTaskStatRow
import com.habittracker.data.local.model.LottoPeriodStatRow
import com.habittracker.data.local.model.MonthlyStatRow
import com.habittracker.data.local.model.RecordDetailRow
import com.habittracker.data.local.model.RecordSummaryRow
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalDateTime

@Dao
interface HabitDao {
    @Query(
        """
        SELECT * FROM card_history
        ORDER BY use_date DESC, id DESC
        LIMIT :limit
        """,
    )
    fun observeCardHistories(limit: Int): Flow<List<CardHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCardHistory(history: CardHistoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCardHistories(histories: List<CardHistoryEntity>): List<Long>

    @Query("DELETE FROM card_history WHERE id = :historyId")
    suspend fun deleteCardHistoryById(historyId: Long)

    @Query("DELETE FROM card_history")
    suspend fun deleteAllCardHistories()

    @Query("SELECT COUNT(*) FROM card_history")
    suspend fun getCardHistoryCount(): Int

    @Query(
        """
        SELECT * FROM lotto_draw
        WHERE (:roundNo IS NULL OR round_no = :roundNo)
        ORDER BY round_no DESC
        LIMIT :limit
        """,
    )
    fun observeLottoDraws(roundNo: Int?, limit: Int): Flow<List<LottoDrawEntity>>

    @Query(
        """
        SELECT * FROM lotto_ticket
        ORDER BY created_at DESC, id DESC
        LIMIT :limit
        """,
    )
    fun observeSavedLottoTickets(limit: Int): Flow<List<LottoTicketEntity>>

    @Query(
        """
        SELECT * FROM lotto_ticket
        ORDER BY created_at DESC, id DESC
        """,
    )
    fun observeAllSavedLottoTickets(): Flow<List<LottoTicketEntity>>

    @Query(
        """
        SELECT * FROM lotto_ticket
        WHERE is_purchased = 1
          AND analysis_score IS NOT NULL
        ORDER BY created_at DESC, id DESC
        """,
    )
    fun observeScoredPurchasedLottoTickets(): Flow<List<LottoTicketEntity>>

    @Query("SELECT * FROM lotto_draw ORDER BY round_no DESC")
    fun observeAllLottoDraws(): Flow<List<LottoDrawEntity>>

    @Query(
        """
        SELECT * FROM lotto_ticket
        WHERE note LIKE :notePrefix || '%'
        ORDER BY created_at DESC, id DESC
        """,
    )
    fun observeSavedLottoTicketsByNotePrefix(notePrefix: String): Flow<List<LottoTicketEntity>>

    @Query(
        """
        SELECT * FROM lotto_purchase
        ORDER BY purchase_date DESC, id DESC
        LIMIT :limit
        """,
    )
    fun observeLottoPurchases(limit: Int): Flow<List<LottoPurchaseEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLottoPurchase(purchase: LottoPurchaseEntity): Long

    @Query("DELETE FROM lotto_purchase WHERE id = :purchaseId")
    suspend fun deleteLottoPurchaseById(purchaseId: Long)

    @Query(
        """
        SELECT * FROM lotto_winning
        ORDER BY round_no DESC, id DESC
        LIMIT :limit
        """,
    )
    fun observeLottoWinnings(limit: Int): Flow<List<LottoWinningEntity>>

    @Query(
        """
        SELECT * FROM lotto_winning_stat
        ORDER BY CASE source_label
            WHEN '균형형' THEN 0
            WHEN '분산형' THEN 1
            ELSE 2
        END, generation_version DESC
        """,
    )
    fun observeLottoWinningStats(): Flow<List<LottoWinningStatEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLottoWinningStats(stats: List<LottoWinningStatEntity>)

    @Query("DELETE FROM lotto_winning_stat")
    suspend fun deleteAllLottoWinningStats()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLottoWinningStatRounds(stats: List<LottoWinningStatRoundEntity>)

    @Query("DELETE FROM lotto_winning_stat_round")
    suspend fun deleteAllLottoWinningStatRounds()

    @Query("DELETE FROM lotto_winning_stat_round WHERE round_no = :roundNo")
    suspend fun deleteLottoWinningStatRoundsByRound(roundNo: Int)

    @Query("SELECT * FROM lotto_winning_stat_round")
    suspend fun getAllLottoWinningStatRounds(): List<LottoWinningStatRoundEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLottoWinning(winning: LottoWinningEntity): Long

    @Query("DELETE FROM lotto_winning WHERE id = :winningId")
    suspend fun deleteLottoWinningById(winningId: Long)

    @Query("SELECT COALESCE(SUM(amount), 0) FROM lotto_purchase")
    fun observeTotalLottoPurchaseAmount(): Flow<Long>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM lotto_winning")
    fun observeTotalLottoWinningAmount(): Flow<Long>

    @Query(
        """
        WITH purchases AS (
            SELECT date(
                purchase_date,
                printf('+%d days', (6 - CAST(strftime('%w', purchase_date) AS INTEGER) + 7) % 7)
            ) AS period,
                   SUM(amount) AS amount
            FROM lotto_purchase
            GROUP BY period
        ),
        winnings AS (
            SELECT date('2002-12-07', printf('+%d days', (round_no - 1) * 7)) AS period,
                   SUM(amount) AS amount
            FROM lotto_winning
            GROUP BY period
        ),
        periods AS (
            SELECT period FROM purchases
            UNION
            SELECT period FROM winnings
        )
        SELECT periods.period AS period,
               COALESCE(purchases.amount, 0) AS purchase_amount,
               COALESCE(winnings.amount, 0) AS winning_amount
        FROM periods
        LEFT JOIN purchases ON purchases.period = periods.period
        LEFT JOIN winnings ON winnings.period = periods.period
        ORDER BY periods.period DESC
        LIMIT :limit
        """,
    )
    fun observeLottoWeeklyStats(limit: Int): Flow<List<LottoPeriodStatRow>>

    @Query(
        """
        WITH purchases AS (
            SELECT substr(purchase_date, 1, 7) AS period,
                   SUM(amount) AS amount
            FROM lotto_purchase
            GROUP BY period
        ),
        winnings AS (
            SELECT substr(date('2002-12-07', printf('+%d days', (round_no - 1) * 7)), 1, 7) AS period,
                   SUM(amount) AS amount
            FROM lotto_winning
            GROUP BY period
        ),
        periods AS (
            SELECT period FROM purchases
            UNION
            SELECT period FROM winnings
        )
        SELECT periods.period AS period,
               COALESCE(purchases.amount, 0) AS purchase_amount,
               COALESCE(winnings.amount, 0) AS winning_amount
        FROM periods
        LEFT JOIN purchases ON purchases.period = periods.period
        LEFT JOIN winnings ON winnings.period = periods.period
        ORDER BY periods.period DESC
        LIMIT :limit
        """,
    )
    fun observeLottoMonthlyStats(limit: Int): Flow<List<LottoPeriodStatRow>>

    @Query(
        """
        WITH purchases AS (
            SELECT substr(purchase_date, 1, 4) AS period,
                   SUM(amount) AS amount
            FROM lotto_purchase
            GROUP BY period
        ),
        winnings AS (
            SELECT substr(date('2002-12-07', printf('+%d days', (round_no - 1) * 7)), 1, 4) AS period,
                   SUM(amount) AS amount
            FROM lotto_winning
            GROUP BY period
        ),
        periods AS (
            SELECT period FROM purchases
            UNION
            SELECT period FROM winnings
        )
        SELECT periods.period AS period,
               COALESCE(purchases.amount, 0) AS purchase_amount,
               COALESCE(winnings.amount, 0) AS winning_amount
        FROM periods
        LEFT JOIN purchases ON purchases.period = periods.period
        LEFT JOIN winnings ON winnings.period = periods.period
        ORDER BY periods.period DESC
        LIMIT :limit
        """,
    )
    fun observeLottoYearlyStats(limit: Int): Flow<List<LottoPeriodStatRow>>

    @Query("SELECT * FROM lotto_draw ORDER BY round_no DESC")
    suspend fun getAllLottoDrawsDesc(): List<LottoDrawEntity>

    @Query("SELECT * FROM lotto_draw WHERE round_no = :roundNo LIMIT 1")
    suspend fun getLottoDrawByRoundNo(roundNo: Int): LottoDrawEntity?

    @Query("SELECT MAX(round_no) FROM lotto_draw")
    suspend fun getLatestLottoRoundNo(): Int?

    @Query("SELECT COUNT(*) FROM lotto_draw")
    suspend fun getLottoDrawCount(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLottoDraws(draws: List<LottoDrawEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLottoDraw(draw: LottoDrawEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLottoTicket(ticket: LottoTicketEntity): Long

    @Query(
        """
        SELECT * FROM lotto_ticket
        WHERE source_label = :sourceLabel
          AND note LIKE :notePrefix || '%'
        ORDER BY created_at DESC, id DESC
        """,
    )
    suspend fun getLottoTicketsBySourceAndNotePrefix(sourceLabel: String, notePrefix: String): List<LottoTicketEntity>

    @Query(
        """
        SELECT * FROM lotto_ticket
        WHERE note LIKE :notePrefix || '%'
          AND is_purchased = 1
        ORDER BY created_at DESC, id DESC
        """,
    )
    suspend fun getPurchasedLottoTicketsByNotePrefix(notePrefix: String): List<LottoTicketEntity>

    @Query(
        """
        DELETE FROM lotto_ticket
        WHERE source_label = :sourceLabel
          AND note = :note
        """,
    )
    suspend fun deleteLottoTicketsBySourceAndNote(sourceLabel: String, note: String)

    @Query("DELETE FROM lotto_ticket WHERE id = :ticketId")
    suspend fun deleteLottoTicketById(ticketId: Long)

    @Query(
        """
        DELETE FROM lotto_ticket
        WHERE source_label = :sourceLabel
          AND note = :note
        """,
    )
    suspend fun deleteLottoTicketsBySourceAndNoteExact(sourceLabel: String, note: String)

    @Query(
        """
        UPDATE lotto_ticket
        SET is_purchased = 1,
            source_label = :sourceLabel
        WHERE note = :note
          AND (
              source_label = :sourceLabel
              OR (:sourceLabel = '균형형' AND (
                  source_label LIKE '%균형형%'
                  OR lower(source_label) LIKE '%chatgpt%'
                  OR lower(source_label) LIKE '%gpt%'
              ))
              OR (:sourceLabel = '분산형' AND (
                  source_label LIKE '%분산형%'
                  OR lower(source_label) LIKE '%gemini%'
                  OR source_label LIKE '%제미나이%'
              ))
          )
        """,
    )
    suspend fun markLottoTicketsPurchasedBySourceAndNote(sourceLabel: String, note: String): Int

    @Query(
        """
        DELETE FROM lotto_ticket
        WHERE note LIKE :notePrefix || '%'
        """,
    )
    suspend fun deleteLottoTicketsByNotePrefix(notePrefix: String)

    @Query(
        """
        SELECT * FROM memo_note
        ORDER BY is_pinned DESC, updated_at DESC, id DESC
        LIMIT :limit
        """,
    )
    fun observeMemoNotes(limit: Int): Flow<List<MemoNoteEntity>>

    @Query(
        """
        SELECT * FROM memo_note
        WHERE title LIKE '%' || :query || '%'
           OR content LIKE '%' || :query || '%'
        ORDER BY is_pinned DESC, updated_at DESC, id DESC
        LIMIT :limit
        """,
    )
    fun observeMemoNotesByQuery(query: String, limit: Int): Flow<List<MemoNoteEntity>>

    @Query("SELECT * FROM memo_note WHERE id = :memoId LIMIT 1")
    suspend fun getMemoNoteById(memoId: Long): MemoNoteEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMemoNote(memoNote: MemoNoteEntity): Long

    @Update
    suspend fun updateMemoNote(memoNote: MemoNoteEntity)

    @Query("DELETE FROM memo_note WHERE id = :memoId")
    suspend fun deleteMemoNoteById(memoId: Long)

    @Query("UPDATE memo_note SET is_pinned = :isPinned, updated_at = :updatedAt WHERE id = :memoId")
    suspend fun updateMemoPinned(memoId: Long, isPinned: Boolean, updatedAt: java.time.LocalDateTime)

    @Query(
        """
        SELECT * FROM plant
        ORDER BY next_watering_date ASC, updated_at DESC, id DESC
        """,
    )
    fun observePlants(): Flow<List<PlantEntity>>

    @Query("SELECT * FROM plant WHERE id = :plantId LIMIT 1")
    suspend fun getPlantById(plantId: Long): PlantEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPlant(plant: PlantEntity): Long

    @Update
    suspend fun updatePlant(plant: PlantEntity)

    @Delete
    suspend fun deletePlant(plant: PlantEntity)

    @Query("SELECT * FROM kis_api_config WHERE environment = :environment LIMIT 1")
    suspend fun getKisApiConfig(environment: String): KisApiConfigEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM kis_api_config WHERE environment = :environment)")
    suspend fun hasKisApiConfig(environment: String): Boolean

    @Query("SELECT access_token_expired_at FROM kis_api_config WHERE environment = :environment LIMIT 1")
    suspend fun getKisAccessTokenExpiredAt(environment: String): LocalDateTime?

    @Query(
        """
        UPDATE kis_api_config
        SET access_token_encrypted = :encryptedAccessToken,
            access_token_expired_at = :accessTokenExpiredAt,
            updated_at = :updatedAt
        WHERE environment = :environment
        """,
    )
    suspend fun updateKisAccessToken(
        environment: String,
        encryptedAccessToken: String,
        accessTokenExpiredAt: LocalDateTime,
        updatedAt: LocalDateTime,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertKisApiConfig(config: KisApiConfigEntity)

    @Query("SELECT * FROM stock_order ORDER BY order_date DESC, order_time DESC, id DESC")
    fun observeStockOrders(): Flow<List<StockOrderEntity>>

    @Query("SELECT * FROM stock_order ORDER BY order_date DESC, order_time DESC, id DESC")
    suspend fun getStockOrders(): List<StockOrderEntity>

    @Query("SELECT * FROM stock_order WHERE status IN ('SUBMITTED', 'PARTIALLY_FILLED', 'UNKNOWN') ORDER BY order_date ASC, order_time ASC")
    suspend fun getUnfinishedStockOrders(): List<StockOrderEntity>

    @Query("SELECT * FROM stock_order WHERE order_date = :orderDate AND order_number = :orderNumber LIMIT 1")
    suspend fun getStockOrder(orderDate: LocalDate, orderNumber: String): StockOrderEntity?

    @Query("SELECT * FROM stock_order WHERE side = 'BUY' AND product_code = :productCode AND remaining_quantity > 0 ORDER BY order_date ASC, order_time ASC, id ASC")
    suspend fun getOpenStockBuyLots(productCode: String): List<StockOrderEntity>

    @Query("SELECT MIN(order_date) FROM stock_order WHERE side = 'BUY' AND product_code = :productCode AND remaining_quantity > 0")
    suspend fun getOldestOpenStockBuyDate(productCode: String): LocalDate?

    @Query("SELECT COUNT(*) FROM stock_order WHERE product_code = :productCode AND side = :side AND status IN ('SUBMITTED', 'PARTIALLY_FILLED', 'UNKNOWN')")
    suspend fun countUnfinishedStockOrders(productCode: String, side: String): Int

    @Query(
        """
        SELECT COALESCE(SUM(
            CASE
                WHEN status = 'REJECTED' THEN 0
                WHEN status = 'CANCELED' THEN
                    filled_quantity * COALESCE(
                        filled_average_price,
                        CASE WHEN requested_unit_price > 0 THEN requested_unit_price ELSE reference_price END
                    )
                ELSE requested_quantity *
                    CASE WHEN requested_unit_price > 0 THEN requested_unit_price ELSE reference_price END
            END
        ), 0)
        FROM stock_order
        WHERE side = 'BUY' AND order_date = :orderDate
        """,
    )
    suspend fun getSubmittedBuyAmount(orderDate: LocalDate): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStockOrder(order: StockOrderEntity): Long

    @Update
    suspend fun updateStockOrder(order: StockOrderEntity)

    @Query("SELECT * FROM stock_exit_rule ORDER BY product_name ASC, product_code ASC, created_at ASC")
    fun observeStockExitRules(): Flow<List<StockExitRuleEntity>>

    @Query("SELECT * FROM stock_exit_rule WHERE enabled = 1 ORDER BY product_code ASC, created_at ASC")
    suspend fun getEnabledStockExitRules(): List<StockExitRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStockExitRule(rule: StockExitRuleEntity): Long

    @Update
    suspend fun updateStockExitRule(rule: StockExitRuleEntity)

    @Query("DELETE FROM stock_exit_rule WHERE id = :ruleId")
    suspend fun deleteStockExitRule(ruleId: Long)

    @Query("SELECT * FROM stock_target_allocation ORDER BY product_name ASC, product_code ASC")
    fun observeStockTargetAllocations(): Flow<List<StockTargetAllocationEntity>>

    @Query("SELECT * FROM stock_target_allocation WHERE enabled = 1 ORDER BY product_code ASC")
    suspend fun getEnabledStockTargetAllocations(): List<StockTargetAllocationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStockTargetAllocation(allocation: StockTargetAllocationEntity)

    @Query("DELETE FROM stock_target_allocation WHERE product_code = :productCode")
    suspend fun deleteStockTargetAllocation(productCode: String)

    @Query("SELECT * FROM stock_safety_config WHERE id = 1 LIMIT 1")
    fun observeStockSafetyConfig(): Flow<StockSafetyConfigEntity?>

    @Query("SELECT * FROM stock_safety_config WHERE id = 1 LIMIT 1")
    suspend fun getStockSafetyConfig(): StockSafetyConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStockSafetyConfig(config: StockSafetyConfigEntity)

    @Query("SELECT * FROM stock_automation_event ORDER BY created_at DESC, id DESC LIMIT :limit")
    fun observeStockAutomationEvents(limit: Int): Flow<List<StockAutomationEventEntity>>

    @Insert
    suspend fun insertStockAutomationEvent(event: StockAutomationEventEntity): Long

    @Query(
        """
        SELECT * FROM vocabulary_word
        ORDER BY updated_at DESC, id DESC
        LIMIT :limit
        """,
    )
    fun observeVocabularyWords(limit: Int): Flow<List<VocabularyWordEntity>>

    @Query(
        """
        SELECT * FROM vocabulary_word
        WHERE word LIKE '%' || :query || '%'
           OR meaning LIKE '%' || :query || '%'
           OR COALESCE(pronunciation, '') LIKE '%' || :query || '%'
        ORDER BY updated_at DESC, id DESC
        LIMIT :limit
        """,
    )
    fun observeVocabularyWordsByQuery(query: String, limit: Int): Flow<List<VocabularyWordEntity>>

    @Query("SELECT * FROM vocabulary_word ORDER BY updated_at DESC, id DESC")
    suspend fun getAllVocabularyWords(): List<VocabularyWordEntity>

    @Query("SELECT * FROM vocabulary_word WHERE id = :wordId LIMIT 1")
    suspend fun getVocabularyWordById(wordId: Long): VocabularyWordEntity?

    @Query("SELECT COUNT(*) FROM vocabulary_word WHERE word = :word AND meaning = :meaning AND (:excludeId IS NULL OR id != :excludeId)")
    suspend fun countDuplicateVocabulary(word: String, meaning: String, excludeId: Long?): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVocabularyWord(word: VocabularyWordEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertVocabularyWords(words: List<VocabularyWordEntity>): List<Long>

    @Update
    suspend fun updateVocabularyWord(word: VocabularyWordEntity)

    @Delete
    suspend fun deleteVocabularyWord(word: VocabularyWordEntity)

    @Query(
        """
        SELECT * FROM task_item_master
        WHERE is_active = 1
        ORDER BY sort_order ASC, name ASC
        """,
    )
    fun observeActiveTaskItems(): Flow<List<TaskItemMasterEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTaskItems(taskItems: List<TaskItemMasterEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTaskItem(taskItem: TaskItemMasterEntity): Long

    @Query("SELECT * FROM task_item_master WHERE id = :taskItemId LIMIT 1")
    suspend fun getTaskItemById(taskItemId: Long): TaskItemMasterEntity?

    @Update
    suspend fun updateTaskItem(taskItem: TaskItemMasterEntity)

    @Query("UPDATE task_item_master SET is_active = 0 WHERE id = :taskItemId")
    suspend fun deactivateTaskItem(taskItemId: Long)

    @Query(
        """
        UPDATE task_item_master
        SET is_active = 0
        WHERE is_active = 1
          AND value_type NOT IN (:allowedValueTypes)
        """,
    )
    suspend fun deactivateTaskItemsByUnsupportedValueTypes(allowedValueTypes: List<String>): Int

    @Query("SELECT COALESCE(MAX(sort_order), 0) FROM task_item_master")
    suspend fun getMaxSortOrder(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDailyRecord(record: DailyRecordEntity): Long

    @Query("SELECT * FROM daily_record WHERE record_date = :recordDate LIMIT 1")
    suspend fun getDailyRecordByDate(recordDate: LocalDate): DailyRecordEntity?

    @Update
    suspend fun updateDailyRecord(record: DailyRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDailyRecordItems(items: List<DailyRecordItemEntity>)

    @Query("DELETE FROM daily_record_item WHERE daily_record_id = :dailyRecordId")
    suspend fun deleteItemsByRecordId(dailyRecordId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDiary(diary: DailyDiaryEntity)

    @Query("SELECT * FROM daily_diary WHERE diary_date = :diaryDate LIMIT 1")
    suspend fun getDiaryByDate(diaryDate: LocalDate): DailyDiaryEntity?

    @Query(
        """
        SELECT diary_date AS diary_date,
               title AS title,
               weather AS weather,
               SUBSTR(body, 1, 80) AS preview
        FROM daily_diary
        ORDER BY diary_date DESC
        LIMIT :limit
        """,
    )
    fun observeDiaryList(limit: Int): Flow<List<DiarySearchRow>>

    @Query(
        """
        SELECT diary_date AS diary_date,
               title AS title,
               weather AS weather,
               SUBSTR(body, 1, 80) AS preview
        FROM daily_diary
        WHERE title LIKE '%' || :query || '%'
           OR body LIKE '%' || :query || '%'
        ORDER BY diary_date DESC
        LIMIT :limit
        """,
    )
    fun observeDiaryListByQuery(query: String, limit: Int): Flow<List<DiarySearchRow>>

    @Query(
        """
        SELECT diary_date AS diary_date,
               title AS title,
               weather AS weather
        FROM daily_diary
        WHERE diary_date BETWEEN :startDate AND :endDate
        ORDER BY diary_date ASC
        """,
    )
    fun observeMonthlyDiarySummaries(startDate: LocalDate, endDate: LocalDate): Flow<List<DiarySummaryRow>>

    @Query(
        """
        SELECT diary_date AS diary_date,
               title AS title,
               weather AS weather,
               SUBSTR(body, 1, 80) AS preview
        FROM daily_diary
        WHERE title LIKE '%' || :query || '%'
           OR body LIKE '%' || :query || '%'
        ORDER BY diary_date DESC
        LIMIT :limit
        """,
    )
    suspend fun searchDiaries(query: String, limit: Int): List<DiarySearchRow>

    @Transaction
    @Query(
        """
        SELECT dr.record_date,
               COUNT(dri.id) AS item_count,
               SUM(CASE
                       WHEN dri.checked = 1
                           OR dri.boolean_value = 1
                           OR COALESCE(dri.number_value, 0) > 0
                           OR COALESCE(dri.duration_minutes, 0) > 0
                           OR COALESCE(dri.text_value, '') <> ''
                       THEN 1
                       ELSE 0
                   END) AS completed_count,
               dr.is_holiday AS is_holiday
        FROM daily_record dr
        LEFT JOIN daily_record_item dri ON dri.daily_record_id = dr.id
        WHERE dr.record_date BETWEEN :startDate AND :endDate
        GROUP BY dr.record_date, dr.is_holiday
        ORDER BY dr.record_date ASC
        """,
    )
    fun observeMonthlySummaries(startDate: LocalDate, endDate: LocalDate): Flow<List<RecordSummaryRow>>

    @Transaction
    @Query(
        """
        SELECT dri.id AS item_id,
               dr.id AS record_id,
               tim.id AS task_item_master_id,
               tim.name AS task_name,
               tim.category AS category,
               tim.value_type AS value_type,
               tim.unit AS unit,
               dri.number_value AS number_value,
               dri.boolean_value AS boolean_value,
               dri.text_value AS text_value,
               dri.duration_minutes AS duration_minutes,
               dri.checked AS checked,
               dri.note AS note
        FROM daily_record dr
        JOIN daily_record_item dri ON dri.daily_record_id = dr.id
        JOIN task_item_master tim ON tim.id = dri.task_item_master_id
        WHERE dr.record_date = :recordDate
        ORDER BY tim.sort_order ASC, tim.name ASC
        """,
    )
    suspend fun getRecordDetails(recordDate: LocalDate): List<RecordDetailRow>

    @Transaction
    @Query(
        """
        SELECT tim.id AS task_item_master_id,
               tim.name AS task_name,
               tim.value_type AS value_type,
               tim.unit AS unit,
               SUM(dri.number_value) AS total_number,
               SUM(dri.duration_minutes) AS total_duration,
               SUM(CASE
                       WHEN dri.checked = 1 OR dri.boolean_value = 1 THEN 1
                       WHEN COALESCE(dri.number_value, 0) > 0 OR COALESCE(dri.duration_minutes, 0) > 0 THEN 1
                       ELSE 0
                   END) AS completed_count
        FROM daily_record dr
        JOIN daily_record_item dri ON dri.daily_record_id = dr.id
        JOIN task_item_master tim ON tim.id = dri.task_item_master_id
        WHERE dr.record_date BETWEEN :startDate AND :endDate
        GROUP BY tim.id, tim.name, tim.value_type, tim.unit
        ORDER BY tim.name ASC
        """,
    )
    fun observeMonthlyStats(startDate: LocalDate, endDate: LocalDate): Flow<List<MonthlyStatRow>>

    @Transaction
    @Query(
        """
        SELECT tim.id AS task_item_master_id,
               tim.name AS task_name,
               tim.value_type AS value_type,
               tim.unit AS unit,
               dr.record_date AS record_date,
               SUM(dri.number_value) AS total_number,
               SUM(dri.duration_minutes) AS total_duration,
               SUM(CASE
                       WHEN dri.checked = 1 OR dri.boolean_value = 1 THEN 1
                       WHEN COALESCE(dri.number_value, 0) > 0 OR COALESCE(dri.duration_minutes, 0) > 0 THEN 1
                       ELSE 0
                   END) AS completed_count
        FROM daily_record dr
        JOIN daily_record_item dri ON dri.daily_record_id = dr.id
        JOIN task_item_master tim ON tim.id = dri.task_item_master_id
        WHERE dr.record_date BETWEEN :startDate AND :endDate
        GROUP BY tim.id, tim.name, tim.value_type, tim.unit, dr.record_date
        ORDER BY tim.sort_order ASC, tim.name ASC, dr.record_date ASC
        """,
    )
    fun observeDailyTaskStats(startDate: LocalDate, endDate: LocalDate): Flow<List<DailyTaskStatRow>>
}
