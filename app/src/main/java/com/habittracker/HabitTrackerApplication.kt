package com.habittracker

import android.app.Application
import com.habittracker.data.AppContainer

class HabitTrackerApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
    }
}
