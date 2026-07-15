package com.habittracker.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.habittracker.HabitTrackerApplication
import com.habittracker.ui.admin.AdminViewModel
import com.habittracker.ui.card.CardHistoryViewModel
import com.habittracker.ui.diary.DiaryViewModel
import com.habittracker.ui.entry.DailyEntryViewModel
import com.habittracker.ui.home.HomeViewModel
import com.habittracker.ui.lotto.LottoViewModel
import com.habittracker.ui.memo.MemoViewModel
import com.habittracker.ui.plant.PlantViewModel
import com.habittracker.ui.stats.MonthlyStatsViewModel
import com.habittracker.ui.stock.StockViewModel

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
            modelClass.isAssignableFrom(LottoViewModel::class.java) -> LottoViewModel(repository) as T
            modelClass.isAssignableFrom(CardHistoryViewModel::class.java) -> CardHistoryViewModel(repository) as T
            modelClass.isAssignableFrom(MemoViewModel::class.java) -> MemoViewModel(repository) as T
            modelClass.isAssignableFrom(PlantViewModel::class.java) -> PlantViewModel(repository) as T
            modelClass.isAssignableFrom(StockViewModel::class.java) -> StockViewModel(repository) as T
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
