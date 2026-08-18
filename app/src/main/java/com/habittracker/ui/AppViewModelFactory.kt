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
import com.habittracker.ui.lotto.PensionLotteryViewModel
import com.habittracker.ui.lotto.PensionLotteryGeneratorViewModel
import com.habittracker.ui.memo.MemoViewModel
import com.habittracker.ui.plant.PlantViewModel
import com.habittracker.ui.stats.MonthlyStatsViewModel
import com.habittracker.ui.stock.StockViewModel

/**
 * Android가 ViewModel을 다시 만들 때 Repository 생성 방법을 알 수 있도록 연결하는 Factory다.
 * 각 화면은 Repository의 생성 과정을 모르고 생성자 매개변수로만 의존성을 받는다.
 */
class AppViewModelFactory : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val application = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as? HabitTrackerApplication
            ?: error("HabitTrackerApplication을 CreationExtras에서 찾을 수 없습니다.")
        val repository = application.appContainer.habitRepository

        // Factory API가 제네릭 T를 요구하므로, 실제 타입을 확인한 분기 안에서만 제한적으로 캐스팅한다.
        @Suppress("UNCHECKED_CAST")
        return when {
            modelClass.isAssignableFrom(HomeViewModel::class.java) -> HomeViewModel(repository) as T
            modelClass.isAssignableFrom(DailyEntryViewModel::class.java) -> DailyEntryViewModel(repository) as T
            modelClass.isAssignableFrom(MonthlyStatsViewModel::class.java) -> MonthlyStatsViewModel(repository) as T
            modelClass.isAssignableFrom(AdminViewModel::class.java) -> AdminViewModel(repository) as T
            modelClass.isAssignableFrom(DiaryViewModel::class.java) -> DiaryViewModel(repository) as T
            modelClass.isAssignableFrom(LottoViewModel::class.java) -> LottoViewModel(repository) as T
            modelClass.isAssignableFrom(PensionLotteryViewModel::class.java) -> PensionLotteryViewModel(repository) as T
            modelClass.isAssignableFrom(PensionLotteryGeneratorViewModel::class.java) -> PensionLotteryGeneratorViewModel(repository) as T
            modelClass.isAssignableFrom(CardHistoryViewModel::class.java) -> CardHistoryViewModel(repository) as T
            modelClass.isAssignableFrom(MemoViewModel::class.java) -> MemoViewModel(repository) as T
            modelClass.isAssignableFrom(PlantViewModel::class.java) -> PlantViewModel(repository) as T
            modelClass.isAssignableFrom(StockViewModel::class.java) -> StockViewModel(repository) as T
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
