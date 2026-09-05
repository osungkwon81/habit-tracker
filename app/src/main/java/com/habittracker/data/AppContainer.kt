package com.habittracker.data

import android.content.Context
import com.habittracker.data.local.HabitTrackerDatabase
import com.habittracker.data.local.HabitTrackerDatabaseProtector
import com.habittracker.data.repository.HabitRepository
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/** 앱 전역 의존성을 한곳에서 생성하는 간단한 수동 DI 컨테이너다. */
class AppContainer(context: Context) {
    // Activity Context를 오래 보관하면 메모리 누수가 생길 수 있어 Application Context로 정규화한다.
    private val applicationContext = context.applicationContext
    private val databaseProtector = HabitTrackerDatabaseProtector(applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _readiness = MutableStateFlow<Result<Unit>?>(null)
    val readiness = _readiness.asStateFlow()
    lateinit var habitRepository: HabitRepository
        private set

    init {
        scope.launch {
            try {
                val database = withContext(Dispatchers.IO) { databaseProtector.openDatabase() }
                databaseProtector.attach(database)
                habitRepository = withContext(Dispatchers.IO) {
                    HabitRepository(applicationContext, database, databaseProtector, database.habitDao())
                }
                _readiness.value = Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("AppContainer", "Database initialization failed", error)
                _readiness.value = Result.failure(error)
            }
        }
    }

    suspend fun awaitRepository(): HabitRepository {
        readiness.filterNotNull().first().getOrThrow()
        return habitRepository
    }
}
