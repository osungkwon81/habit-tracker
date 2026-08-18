package com.habittracker

import android.app.Application
import com.habittracker.data.AppContainer

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
    }
}
