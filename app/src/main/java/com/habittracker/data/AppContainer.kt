package com.habittracker.data

import android.content.Context
import com.habittracker.data.local.HabitTrackerDatabase
import com.habittracker.data.repository.HabitRepository

class AppContainer(context: Context) {
    private val database: HabitTrackerDatabase = HabitTrackerDatabase.create(context)

    val habitRepository: HabitRepository = HabitRepository(
        database = database,
        habitDao = database.habitDao(),
    )
}
