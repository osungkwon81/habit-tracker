package com.habittracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.ListItem
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import com.habittracker.ui.components.AppNavigationGuard
import com.habittracker.ui.components.LocalAppNavigationGuard
import com.habittracker.ui.components.LocalAppSnackbar
import com.habittracker.ui.components.AppConfirmDialog
import com.habittracker.ui.components.AppScreen
import com.habittracker.ui.components.AppHeroCard
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val container = (LocalContext.current.applicationContext as HabitTrackerApplication).appContainer
    val readiness by container.readiness.collectAsStateWithLifecycle()
    if (readiness?.isSuccess != true) {
        AppScreen {
            item { AppHeroCard(title = "Habit Tracker") }
            item {
                if (readiness == null) com.habittracker.ui.components.AppLoadingCard("기록을 불러오고 있습니다.")
                else Text("저장소를 열지 못했습니다. 앱을 다시 열어 주세요.", color = MaterialTheme.colorScheme.error)
            }
        }
        return
    }
    val navController = rememberNavController()
    // remember는 재구성 때마다 동일한 Factory를 새로 만들지 않도록 값을 보관한다.
    val viewModelFactory = remember { AppViewModelFactory() }
    val snackbar = remember { SnackbarHostState() }
    val guard = remember { AppNavigationGuard() }
    val activity = LocalContext.current as? ComponentActivity
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val isTopLevel = AppDestination.bottomNavigation.any { it.route == route }
    BackHandler(enabled = guard.hasUnsavedChanges) {
        guard.navigate { if (!navController.popBackStack()) activity?.finish() }
    }

    CompositionLocalProvider(LocalAppSnackbar provides snackbar, LocalAppNavigationGuard provides guard) {
    guard.pendingAction?.let { action ->
        AppConfirmDialog(
            title = "작성 중인 기록을 나갈까요?",
            message = "저장하지 않은 변경 내용은 사라집니다.",
            confirmText = "변경 버리기",
            onConfirm = {
                guard.pendingAction = null
                guard.hasUnsavedChanges = false
                action()
            },
            onDismiss = { guard.pendingAction = null },
        )
    }
    Scaffold(
        bottomBar = { AppBottomNavigation(navController) },
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (!isTopLevel && route != null) {
                TextButton(onClick = { guard.navigate { navController.popBackStack() } }) { Text("‹ 뒤로") }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AppDestination.HOME.route,
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
        ) {
            composable(AppDestination.MORE.route) {
                AppScreen {
                    item { AppHeroCard(title = "전체", description = "생활 기록과 자산 관리를 한곳에서") }
                    items(listOf(AppDestination.MEMO, AppDestination.PLANT, AppDestination.ENTRY, AppDestination.DIARY, AppDestination.STATS, AppDestination.ADMIN)) { destination ->
                        ListItem(
                            headlineContent = { Text(destination.label) },
                            trailingContent = { Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            modifier = Modifier.clickable { navController.navigate(destination.route) { launchSingleTop = true } },
                        )
                    }
                }
            }
            composable(AppDestination.HOME.route) {
                val viewModel: HomeViewModel = viewModel(factory = viewModelFactory)
                HomeScreen(
                    viewModel = viewModel,
                    onOpenRecord = { date -> navController.navigate("${AppDestination.ENTRY.route}/${date}") },
                    onOpenMemo = { navController.navigate(AppDestination.MEMO.route) },
                    onOpenStock = { navController.navigate(AppDestination.STOCK.route) },
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
}

@Composable
private fun AppBottomNavigation(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val guard = LocalAppNavigationGuard.current
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        AppDestination.bottomNavigation.forEach { destination ->
            val selected = if (destination == AppDestination.MORE) {
                currentDestination != null && AppDestination.bottomNavigation
                    .filterNot { it == AppDestination.MORE }
                    .none { it.matches(currentDestination.route) }
            } else currentDestination?.hierarchy?.any { current ->
                destination.matches(current.route)
            } == true
            NavigationBarItem(
                label = { Text(destination.label, maxLines = 1) },
                icon = { NavigationIcon(destination) },
                selected = selected,
                onClick = {
                    if (destination.route != currentDestination?.route) guard.navigate {
                        val popped = navController.popBackStack(destination.route, false)
                        if (!popped) {
                            navController.navigate(destination.route) {
                                launchSingleTop = true
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun NavigationIcon(destination: AppDestination) {
    val color = androidx.compose.material3.LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val u = size.width / 24f
        val stroke = Stroke(1.8f * u)
        when (destination) {
            AppDestination.HOME -> drawPath(Path().apply {
                moveTo(3 * u, 11 * u); lineTo(12 * u, 3 * u); lineTo(21 * u, 11 * u)
                lineTo(21 * u, 21 * u); lineTo(15 * u, 21 * u); lineTo(15 * u, 14 * u)
                lineTo(9 * u, 14 * u); lineTo(9 * u, 21 * u); lineTo(3 * u, 21 * u); close()
            }, color, style = stroke)
            AppDestination.STOCK -> {
                drawRect(color, Offset(4 * u, 3 * u), androidx.compose.ui.geometry.Size(16 * u, 18 * u), style = stroke)
                drawPath(Path().apply { moveTo(7 * u, 16 * u); lineTo(11 * u, 11 * u); lineTo(14 * u, 13 * u); lineTo(18 * u, 7 * u) }, color, style = stroke)
            }
            AppDestination.CARD -> {
                drawRoundRect(color, Offset(2 * u, 5 * u), androidx.compose.ui.geometry.Size(20 * u, 14 * u), androidx.compose.ui.geometry.CornerRadius(3 * u), style = stroke)
                drawLine(color, Offset(3 * u, 10 * u), Offset(21 * u, 10 * u), 1.8f * u)
            }
            AppDestination.LOTTO -> {
                drawCircle(color, 9 * u, Offset(12 * u, 12 * u), style = stroke)
                drawCircle(color, 2 * u, Offset(9 * u, 10 * u))
                drawCircle(color, 2 * u, Offset(15 * u, 14 * u))
            }
            else -> listOf(6f, 18f).forEach { x -> listOf(6f, 18f).forEach { y -> drawCircle(color, 2.5f * u, Offset(x * u, y * u)) } }
        }
    }
}
