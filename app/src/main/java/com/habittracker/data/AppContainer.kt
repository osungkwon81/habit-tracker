package com.habittracker.data

import android.content.Context
import com.habittracker.data.local.HabitTrackerDatabase
import com.habittracker.data.local.HabitTrackerDatabaseProtector
import com.habittracker.data.repository.HabitRepository

class AppContainer(context: Context) {
    private val databaseProtector = HabitTrackerDatabaseProtector(context)
    private val database: HabitTrackerDatabase = databaseProtector.openDatabase()

    val habitRepository: HabitRepository = HabitRepository(
        context = context.applicationContext,
        database = database,
        databaseProtector = databaseProtector,
        habitDao = database.habitDao(),
    )
}
