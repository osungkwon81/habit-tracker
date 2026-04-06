package com.habittracker.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.habittracker.data.local.entity.DailyDiaryEntity
import com.habittracker.data.local.entity.DailyRecordEntity
import com.habittracker.data.local.entity.DailyRecordItemEntity
import com.habittracker.data.local.entity.TaskItemMasterEntity
import com.habittracker.data.local.model.DiarySummaryRow
import com.habittracker.data.local.model.MonthlyStatRow
import com.habittracker.data.local.model.RecordDetailRow
import com.habittracker.data.local.model.RecordSummaryRow
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface HabitDao {
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
               weather AS weather
        FROM daily_diary
        WHERE diary_date BETWEEN :startDate AND :endDate
        ORDER BY diary_date ASC
        """,
    )
    fun observeMonthlyDiarySummaries(startDate: LocalDate, endDate: LocalDate): Flow<List<DiarySummaryRow>>

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
                   END) AS completed_count
        FROM daily_record dr
        LEFT JOIN daily_record_item dri ON dri.daily_record_id = dr.id
        WHERE dr.record_date BETWEEN :startDate AND :endDate
        GROUP BY dr.record_date
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
        SELECT tim.name AS task_name,
               tim.value_type AS value_type,
               SUM(dri.number_value) AS total_number,
               SUM(CASE
                       WHEN dri.checked = 1 OR dri.boolean_value = 1 THEN 1
                       ELSE 0
                   END) AS completed_count
        FROM daily_record dr
        JOIN daily_record_item dri ON dri.daily_record_id = dr.id
        JOIN task_item_master tim ON tim.id = dri.task_item_master_id
        WHERE dr.record_date BETWEEN :startDate AND :endDate
        GROUP BY tim.id, tim.name, tim.value_type
        ORDER BY tim.name ASC
        """,
    )
    fun observeMonthlyStats(startDate: LocalDate, endDate: LocalDate): Flow<List<MonthlyStatRow>>
}