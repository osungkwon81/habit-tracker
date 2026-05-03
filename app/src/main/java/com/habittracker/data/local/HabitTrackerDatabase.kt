package com.habittracker.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.habittracker.data.local.entity.DailyDiaryEntity
import com.habittracker.data.local.entity.DailyRecordEntity
import com.habittracker.data.local.entity.DailyRecordItemAttachmentEntity
import com.habittracker.data.local.entity.DailyRecordItemEntity
import com.habittracker.data.local.entity.LottoDrawEntity
import com.habittracker.data.local.entity.LottoPurchaseEntity
import com.habittracker.data.local.entity.LottoTicketEntity
import com.habittracker.data.local.entity.LottoWinningEntity
import com.habittracker.data.local.entity.MemoNoteEntity
import com.habittracker.data.local.entity.TaskItemAttachmentEntity
import com.habittracker.data.local.entity.TaskItemMasterEntity
import com.habittracker.data.local.entity.VocabularyWordEntity

@Database(
    entities = [
        TaskItemMasterEntity::class,
        DailyRecordEntity::class,
        DailyRecordItemEntity::class,
        TaskItemAttachmentEntity::class,
        DailyRecordItemAttachmentEntity::class,
        DailyDiaryEntity::class,
        LottoDrawEntity::class,
        LottoTicketEntity::class,
        LottoPurchaseEntity::class,
        LottoWinningEntity::class,
        MemoNoteEntity::class,
        VocabularyWordEntity::class,
    ],
    version = 10,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class HabitTrackerDatabase : RoomDatabase() {
    abstract fun habitDao(): HabitDao

    companion object {
        const val DB_NAME = "habit-tracker.db"

        fun builder(context: Context) =
            Room.databaseBuilder(
                context.applicationContext,
                HabitTrackerDatabase::class.java,
                DB_NAME,
            ).addMigrations(*HabitTrackerMigrations.all)

        fun create(context: Context): HabitTrackerDatabase =
            builder(context).build()
    }
}
