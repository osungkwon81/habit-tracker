package com.habittracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.habittracker.ui.AppViewModelFactory
import com.habittracker.ui.admin.AdminScreen
import com.habittracker.ui.admin.AdminViewModel
import com.habittracker.ui.card.CardHistoryScreen
import com.habittracker.ui.card.CardHistoryViewModel
import com.habittracker.ui.diary.DiaryScreen
import com.habittracker.ui.diary.DiaryViewModel
import com.habittracker.ui.entry.DailyEntryScreen
import com.habittracker.ui.entry.DailyEntryViewModel
import com.habittracker.ui.home.HomeScreen
import com.habittracker.ui.home.HomeViewModel
import com.habittracker.ui.lotto.LottoScreen
import com.habittracker.ui.lotto.LottoViewModel
import com.habittracker.ui.lotto.LotteryHomeScreen
import com.habittracker.ui.lotto.PensionLotteryScreen
import com.habittracker.ui.lotto.PensionLotteryViewModel
import com.habittracker.ui.lotto.PensionLotteryGeneratorScreen
import com.habittracker.ui.lotto.PensionLotteryGeneratorViewModel
import com.habittracker.ui.memo.MemoScreen
import com.habittracker.ui.memo.MemoViewModel
import com.habittracker.ui.navigation.AppDestination
import com.habittracker.ui.plant.PlantScreen
import com.habittracker.ui.plant.PlantViewModel
import com.habittracker.ui.stats.MonthlyStatsScreen
import com.habittracker.ui.stats.MonthlyStatsViewModel
import com.habittracker.ui.stock.StockScreen
import com.habittracker.ui.stock.StockAutomationScreen
import com.habittracker.ui.stock.StockJournalScreen
import com.habittracker.ui.stock.StockOrderScreen
import com.habittracker.ui.stock.StockPortfolioScreen
import com.habittracker.ui.stock.StockRebalanceScreen
import com.habittracker.ui.stock.StockSettingsScreen
import com.habittracker.ui.stock.StockViewModel
import com.habittracker.ui.theme.HabitTrackerTheme
import java.time.LocalDate

/** Android 진입점은 테마와 최상위 Composable만 연결하고 화면 로직은 Compose 계층에 맡긴다. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HabitTrackerTheme {
                HabitTrackerApp()
            }
        }
    }
}

@Composable
private fun HabitTrackerApp() {
    val navController = rememberNavController()
    // remember는 재구성 때마다 동일한 Factory를 새로 만들지 않도록 값을 보관한다.
    val viewModelFactory = remember { AppViewModelFactory() }

    Scaffold(
        bottomBar = { AppBottomNavigation(navController) },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AppDestination.HOME.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(AppDestination.HOME.route) {
                val viewModel: HomeViewModel = viewModel(factory = viewModelFactory)
                HomeScreen(
                    viewModel = viewModel,
                    onOpenRecord = { date -> navController.navigate("${AppDestination.ENTRY.route}/${date}") },
                    onOpenDiary = { navController.navigate(AppDestination.DIARY.route) },
                    onOpenMemo = { navController.navigate(AppDestination.MEMO.route) },
                    onOpenLotto = { navController.navigate(AppDestination.LOTTO.route) },
                    onOpenPlant = { navController.navigate(AppDestination.PLANT.route) },
                    onOpenCard = { navController.navigate(AppDestination.CARD.route) },
                )
            }
            composable(AppDestination.ENTRY.route) {
                val viewModel: DailyEntryViewModel = viewModel(factory = viewModelFactory)
                DailyEntryScreen(
                    viewModel = viewModel,
                    initialDate = LocalDate.now().toString(),
                    onOpenAdmin = { navController.navigate(AppDestination.ADMIN.route) },
                    onOpenStats = { navController.navigate(AppDestination.STATS.route) },
                )
            }
            composable("${AppDestination.ENTRY.route}/{date}") { backStackEntry ->
                val viewModel: DailyEntryViewModel = viewModel(factory = viewModelFactory)
                DailyEntryScreen(
                    viewModel = viewModel,
                    initialDate = backStackEntry.arguments?.getString("date") ?: LocalDate.now().toString(),
                    onOpenAdmin = { navController.navigate(AppDestination.ADMIN.route) },
                    onOpenStats = { navController.navigate(AppDestination.STATS.route) },
                )
            }
            composable(AppDestination.DIARY.route) {
                val viewModel: DiaryViewModel = viewModel(factory = viewModelFactory)
                DiaryScreen(viewModel = viewModel)
            }
            composable(AppDestination.MEMO.route) {
                val viewModel: MemoViewModel = viewModel(factory = viewModelFactory)
                MemoScreen(viewModel = viewModel)
            }
            composable(AppDestination.STATS.route) {
                val viewModel: MonthlyStatsViewModel = viewModel(factory = viewModelFactory)
                MonthlyStatsScreen(viewModel = viewModel, onOpenEntry = { navController.navigate(AppDestination.ENTRY.route) })
            }
            composable(AppDestination.STOCK.route) {
                val viewModel: StockViewModel = viewModel(factory = viewModelFactory)
                StockScreen(
                    viewModel = viewModel,
                    onOpenOrder = { navController.navigate(AppDestination.STOCK_ORDER.route) },
                    onOpenPortfolio = { navController.navigate(AppDestination.STOCK_PORTFOLIO.route) },
                    onOpenAutomation = { navController.navigate(AppDestination.STOCK_AUTOMATION.route) },
                    onOpenRebalance = { navController.navigate(AppDestination.STOCK_REBALANCE.route) },
                    onOpenJournal = { navController.navigate(AppDestination.STOCK_JOURNAL.route) },
                    onOpenSettings = { navController.navigate(AppDestination.STOCK_SETTINGS.route) },
                )
            }
            composable(AppDestination.STOCK_ORDER.route) {
                val viewModel: StockViewModel = viewModel(factory = viewModelFactory)
                StockOrderScreen(viewModel)
            }
            composable(AppDestination.STOCK_PORTFOLIO.route) {
                val viewModel: StockViewModel = viewModel(factory = viewModelFactory)
                StockPortfolioScreen(viewModel)
            }
            composable(AppDestination.STOCK_AUTOMATION.route) {
                val viewModel: StockViewModel = viewModel(factory = viewModelFactory)
                StockAutomationScreen(viewModel)
            }
            composable(AppDestination.STOCK_REBALANCE.route) {
                val viewModel: StockViewModel = viewModel(factory = viewModelFactory)
                StockRebalanceScreen(viewModel)
            }
            composable(AppDestination.STOCK_JOURNAL.route) {
                val viewModel: StockViewModel = viewModel(factory = viewModelFactory)
                StockJournalScreen(viewModel)
            }
            composable(AppDestination.STOCK_SETTINGS.route) {
                val viewModel: StockViewModel = viewModel(factory = viewModelFactory)
                StockSettingsScreen(viewModel)
            }
            composable(AppDestination.ADMIN.route) {
                val viewModel: AdminViewModel = viewModel(factory = viewModelFactory)
                AdminScreen(
                    viewModel = viewModel,
                    onOpenEntry = { navController.navigate(AppDestination.ENTRY.route) { launchSingleTop = true } },
                )
            }
            composable(AppDestination.LOTTO.route) {
                LotteryHomeScreen(
                    onOpenLotto645 = { navController.navigate(AppDestination.LOTTO_645.route) },
                    onOpenPensionLottery = { navController.navigate(AppDestination.PENSION_LOTTO.route) },
                )
            }
            composable(AppDestination.LOTTO_645.route) {
                val viewModel: LottoViewModel = viewModel(factory = viewModelFactory)
                LottoScreen(
                    viewModel = viewModel,
                    onBackToLotteryHome = { navController.popBackStack() },
                )
            }
            composable(AppDestination.PENSION_LOTTO.route) {
                val viewModel: PensionLotteryViewModel = viewModel(factory = viewModelFactory)
                PensionLotteryScreen(
                    viewModel = viewModel,
                    onBackToLotteryHome = { navController.popBackStack() },
                    onOpenGenerator = { navController.navigate(AppDestination.PENSION_LOTTO_GENERATOR.route) },
                )
            }
            composable(AppDestination.PENSION_LOTTO_GENERATOR.route) {
                val viewModel: PensionLotteryGeneratorViewModel = viewModel(factory = viewModelFactory)
                PensionLotteryGeneratorScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(AppDestination.CARD.route) {
                val viewModel: CardHistoryViewModel = viewModel(factory = viewModelFactory)
                CardHistoryScreen(viewModel = viewModel)
            }
            composable(AppDestination.PLANT.route) {
                val viewModel: PlantViewModel = viewModel(factory = viewModelFactory)
                PlantScreen(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun AppBottomNavigation(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            AppDestination.bottomNavigation.forEach { destination ->
                val selected = currentDestination?.hierarchy?.any { current ->
                    destination.matches(current.route)
                } == true
                FloatingNavItem(
                    label = destination.label,
                    selected = selected,
                    onClick = {
                        val popped = navController.popBackStack(destination.route, false)
                        if (!popped) {
                            navController.navigate(destination.route) {
                                launchSingleTop = true
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun FloatingNavItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}
