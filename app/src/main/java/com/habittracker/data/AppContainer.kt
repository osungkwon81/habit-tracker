package com.habittracker.data

import android.content.Context
import com.habittracker.data.local.HabitTrackerDatabase
import com.habittracker.data.local.HabitTrackerDatabaseProtector
import com.habittracker.data.repository.HabitRepository

/** 앱 전역 의존성을 한곳에서 생성하는 간단한 수동 DI 컨테이너다. */
class AppContainer(context: Context) {
    // Activity Context를 오래 보관하면 메모리 누수가 생길 수 있어 Application Context로 정규화한다.
    private val applicationContext = context.applicationContext
    private val databaseProtector = HabitTrackerDatabaseProtector(applicationContext)
    private val database: HabitTrackerDatabase = databaseProtector.openDatabase()

    val habitRepository: HabitRepository = HabitRepository(
        context = applicationContext,
        database = database,
        databaseProtector = databaseProtector,
        habitDao = database.habitDao(),
    )
}
