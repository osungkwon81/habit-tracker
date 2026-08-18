package com.habittracker

import android.app.Application
import com.habittracker.data.AppContainer
import com.habittracker.data.lotto.LotteryDrawSyncScheduler

/**
 * 프로세스 전체에서 하나만 생성되는 Android Application이다.
 * 별도 DI 라이브러리 없이 [AppContainer]를 애플리케이션 생명주기에 맞춰 보관한다.
 */
class HabitTrackerApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
        // 정확한 알람 권한 없이 발표 후 여유 시간에 실행하고, 재부팅 후에도 WorkManager가 일정을 복구한다.
        LotteryDrawSyncScheduler.scheduleAll(this)
    }
}
