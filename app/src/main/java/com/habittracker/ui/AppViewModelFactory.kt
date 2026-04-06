package com.habittracker.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.habittracker.HabitTrackerApplication
import com.habittracker.ui.admin.AdminViewModel
import com.habittracker.ui.diary.DiaryViewModel
import com.habittracker.ui.entry.DailyEntryViewModel
import com.habittracker.ui.home.HomeViewModel
import com.habittracker.ui.stats.MonthlyStatsViewModel

class AppViewModelFactory : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) as HabitTrackerApplication
        val repository = application.appContainer.habitRepository

        @Suppress("UNCHECKED_CAST")
        return when {
            modelClass.isAssignableFrom(HomeViewModel::class.java) -> HomeViewModel(repository) as T
            modelClass.isAssignableFrom(DailyEntryViewModel::class.java) -> DailyEntryViewModel(repository) as T
            modelClass.isAssignableFrom(MonthlyStatsViewModel::class.java) -> MonthlyStatsViewModel(repository) as T
            modelClass.isAssignableFrom(AdminViewModel::class.java) -> AdminViewModel(repository) as T
            modelClass.isAssignableFrom(DiaryViewModel::class.java) -> DiaryViewModel(repository) as T
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}