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
import com.habittracker.data.local.entity.LottoTicketEntity
import com.habittracker.data.local.entity.TaskItemAttachmentEntity
import com.habittracker.data.local.entity.TaskItemMasterEntity

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
    ],
    version = 5,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class HabitTrackerDatabase : RoomDatabase() {
    abstract fun habitDao(): HabitDao

    companion object {
        fun create(context: Context): HabitTrackerDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                HabitTrackerDatabase::class.java,
                "habit-tracker.db",
            ).fallbackToDestructiveMigration().build()
        }
    }
}