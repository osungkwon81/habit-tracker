package com.habittracker

import android.os.Bundle
import com.habittracker.ui.exchange.ExchangeScreen
import com.habittracker.ui.exchange.ExchangePage
import com.habittracker.ui.exchange.ExchangeViewModel
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
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.drawBehind
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
import com.habittracker.ui.card.CardChartsScreen
import com.habittracker.ui.card.CardHistoryViewModel
import com.habittracker.ui.diary.DiaryScreen
import com.habittracker.ui.diary.DiaryViewModel
import com.habittracker.ui.entry.DailyEntryScreen
import com.habittracker.ui.entry.DailyEntryViewModel
import com.habittracker.ui.home.HomeScreen
import com.habittracker.ui.home.HomeViewModel
import com.habittracker.ui.lotto.LottoScreen
import com.habittracker.ui.lotto.LottoTab
import com.habittracker.ui.lotto.PensionLotteryTab
import com.habittracker.ui.lotto.LottoViewModel
import com.habittracker.ui.lotto.LotteryHomeScreen
import com.habittracker.ui.lotto.PensionLotteryScreen
import com.habittracker.ui.lotto.PensionLotteryViewModel
import com.habittracker.ui.lotto.PensionLotteryGeneratorScreen
import com.habittracker.ui.lotto.PensionLotteryGeneratorViewModel
import com.habittracker.ui.memo.MemoScreen
import com.habittracker.ui.memo.MemoViewModel
import com.habittracker.ui.navigation.AppDestination
import com.habittracker.ui.components.AppSpacing
import com.habittracker.ui.notification.NotificationAssistantScreen
import com.habittracker.ui.notification.NotificationAssistantPage
import com.habittracker.ui.notification.NotificationAssistantViewModel
import com.habittracker.ui.plant.PlantScreen
import com.habittracker.ui.plant.PlantViewModel
import com.habittracker.ui.reminder.ReminderScreen
import com.habittracker.ui.reminder.ReminderViewModel
import com.habittracker.ui.stats.MonthlyStatsScreen
import com.habittracker.ui.stats.MonthlyStatsViewModel
import com.habittracker.ui.settings.AppManagementScreen
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
    val applicationContext = LocalContext.current.applicationContext
    androidx.compose.runtime.LaunchedEffect(route) {
        val destination = AppDestination.entries.firstOrNull { it.route == route }
            ?: AppDestination.ENTRY.takeIf { it.matches(route) }
        if (destination != null) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.habittracker.ui.home.HomeMenuUsageStore.recordVisit(applicationContext, destination)
            }
        }
    }
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
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
                ) {
                    TextButton(
                        onClick = { guard.navigate { navController.popBackStack() } },
                        modifier = Modifier.sizeIn(minWidth = 88.dp, minHeight = 48.dp),
                    ) { Text("‹ 뒤로") }
                }
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
                    item { AppHeroCard(title = "전체") }
                    AppDestination.menuGroups.forEach { (title, destinations) ->
                        item(key = title) { com.habittracker.ui.components.AppSectionHeader(title = title) }
                        items(destinations, key = { it.route }) { destination ->
                            MenuTreeNode(destination = destination, onOpen = { target ->
                                guard.navigate {
                                    navController.navigate(target.route) { launchSingleTop = true }
                                }
                            })
                        }
                    }
                }
            }
            composable(AppDestination.HOME.route) {
                val viewModel: HomeViewModel = viewModel(factory = viewModelFactory)
                HomeScreen(
                    viewModel = viewModel,
                    onOpenRecord = { date -> navController.navigate("${AppDestination.ENTRY.route}/${date}") },
                    onOpenDestination = { destination -> navController.navigate(destination.route) },
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
            composable(AppDestination.ENTRY_EXERCISE.route) {
                val viewModel: DailyEntryViewModel = viewModel(factory = viewModelFactory)
                DailyEntryScreen(
                    viewModel = viewModel,
                    initialDate = LocalDate.now().toString(),
                    initialCategory = "운동",
                    onOpenAdmin = { navController.navigate(AppDestination.ADMIN.route) },
                    onOpenStats = { navController.navigate(AppDestination.STATS.route) },
                )
            }
            composable(AppDestination.DIARY.route) {
                val viewModel: DiaryViewModel = viewModel(factory = viewModelFactory)
                DiaryScreen(viewModel = viewModel)
            }
            composable(AppDestination.DIARY_NEW.route) {
                val viewModel: DiaryViewModel = viewModel(factory = viewModelFactory)
                DiaryScreen(viewModel = viewModel, startNew = true)
            }
            composable(AppDestination.MEMO.route) {
                val viewModel: MemoViewModel = viewModel(factory = viewModelFactory)
                MemoScreen(viewModel = viewModel)
            }
            composable(AppDestination.MEMO_NEW.route) {
                val viewModel: MemoViewModel = viewModel(factory = viewModelFactory)
                MemoScreen(viewModel = viewModel, startNew = true)
            }
            composable(AppDestination.STATS.route) {
                val viewModel: MonthlyStatsViewModel = viewModel(factory = viewModelFactory)
                MonthlyStatsScreen(viewModel = viewModel, onOpenEntry = { navController.navigate(AppDestination.ENTRY.route) })
            }
            composable(AppDestination.SETTINGS.route) {
                AppManagementScreen()
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
            listOf(
                AppDestination.LOTTO_645 to null,
                AppDestination.LOTTO_GENERATOR to LottoTab.GENERATOR,
                AppDestination.LOTTO_SAVED to LottoTab.SAVED,
                AppDestination.LOTTO_QR to LottoTab.PHYSICAL_QR,
                AppDestination.LOTTO_PURCHASE to LottoTab.PURCHASE,
                AppDestination.LOTTO_WINNING to LottoTab.WINNING,
                AppDestination.LOTTO_DRAW to LottoTab.DRAW,
                AppDestination.LOTTO_STATS to LottoTab.STATS,
                AppDestination.LOTTO_IMPROVEMENT to LottoTab.EXPERIMENT,
            ).forEach { (destination, initialTab) ->
                composable(destination.route) {
                    val viewModel: LottoViewModel = viewModel(factory = viewModelFactory)
                    LottoScreen(
                        viewModel = viewModel,
                        onBackToLotteryHome = { navController.popBackStack() },
                        initialTab = initialTab,
                    )
                }
            }
            listOf(
                AppDestination.PENSION_LOTTO to null,
                AppDestination.PENSION_LOTTO_PURCHASE to PensionLotteryTab.PURCHASE,
                AppDestination.PENSION_LOTTO_WINNING to PensionLotteryTab.WINNING,
                AppDestination.PENSION_LOTTO_DRAW to PensionLotteryTab.INPUT,
                AppDestination.PENSION_LOTTO_MATCH to PensionLotteryTab.MATCH,
                AppDestination.PENSION_LOTTO_PATTERN to PensionLotteryTab.STATS,
                AppDestination.PENSION_LOTTO_STATS to PensionLotteryTab.ACCOUNTING_STATS,
            ).forEach { (destination, initialTab) ->
                composable(destination.route) {
                    val viewModel: PensionLotteryViewModel = viewModel(factory = viewModelFactory)
                    PensionLotteryScreen(
                        viewModel = viewModel,
                        onBackToLotteryHome = { navController.popBackStack() },
                        onOpenGenerator = { navController.navigate(AppDestination.PENSION_LOTTO_GENERATOR.route) },
                        initialTab = initialTab,
                    )
                }
            }
            listOf(
                AppDestination.PENSION_LOTTO_GENERATOR to "NUMBERS",
                AppDestination.PENSION_LOTTO_SAVED to "HISTORY",
                AppDestination.PENSION_LOTTO_IMPROVEMENT to "IMPROVEMENT",
            ).forEach { (destination, initialSection) ->
                composable(destination.route) {
                    val viewModel: PensionLotteryGeneratorViewModel = viewModel(factory = viewModelFactory)
                    PensionLotteryGeneratorScreen(
                        viewModel = viewModel,
                        onBack = { navController.navigate(AppDestination.PENSION_LOTTO.route) { launchSingleTop = true } },
                        initialSection = initialSection,
                    )
                }
            }
            composable(AppDestination.CARD.route) {
                val viewModel: CardHistoryViewModel = viewModel(factory = viewModelFactory)
                CardHistoryScreen(
                    viewModel = viewModel,
                    onOpenCharts = {
                        guard.navigate { navController.navigate(AppDestination.CARD_CHARTS.route) { launchSingleTop = true } }
                    },
                )
            }
            composable(AppDestination.CARD_CHARTS.route) {
                val viewModel: CardHistoryViewModel = viewModel(factory = viewModelFactory)
                CardChartsScreen(viewModel = viewModel)
            }
            composable(AppDestination.PLANT.route) {
                val viewModel: PlantViewModel = viewModel(factory = viewModelFactory)
                PlantScreen(viewModel = viewModel)
            }
            composable(AppDestination.PLANT_NEW.route) {
                val viewModel: PlantViewModel = viewModel(factory = viewModelFactory)
                PlantScreen(viewModel = viewModel, startNew = true)
            }
            composable(AppDestination.REMINDER.route) {
                val viewModel: ReminderViewModel = viewModel(factory = viewModelFactory)
                ReminderScreen(viewModel = viewModel)
            }
            composable(AppDestination.REMINDER_NEW.route) {
                val viewModel: ReminderViewModel = viewModel(factory = viewModelFactory)
                ReminderScreen(viewModel = viewModel, startNew = true)
            }
            composable(
                AppDestination.EXCHANGE.route,
                arguments = listOf(androidx.navigation.navArgument("widgetCurrency") {
                    type = androidx.navigation.NavType.StringType
                    nullable = true
                    defaultValue = null
                }),
                deepLinks = listOf(androidx.navigation.navDeepLink { uriPattern = "habittracker://exchange/rates?widgetCurrency={widgetCurrency}" }),
            ) {
                val viewModel: ExchangeViewModel = viewModel(factory = viewModelFactory)
                ExchangeScreen(
                    viewModel = viewModel,
                    onOpenRecords = { navController.navigate(AppDestination.EXCHANGE_RECORDS.route) { launchSingleTop = true } },
                    onOpenCollectionSettings = { navController.navigate(AppDestination.EXCHANGE_COLLECTION_SETTINGS.route) { launchSingleTop = true } },
                )
            }
            composable(AppDestination.EXCHANGE_COLLECTION_SETTINGS.route) { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    navController.previousBackStackEntry?.takeIf { it.destination.route == AppDestination.EXCHANGE.route }
                        ?: backStackEntry
                }
                val viewModel: ExchangeViewModel = viewModel(viewModelStoreOwner = parentEntry, factory = viewModelFactory)
                ExchangeScreen(
                    viewModel = viewModel,
                    page = ExchangePage.COLLECTION_SETTINGS,
                )
            }
            listOf(
                AppDestination.EXCHANGE_RECORDS to com.habittracker.ui.exchange.ExchangeEntryType.EXCHANGE,
                AppDestination.EXCHANGE_EXPENSES to com.habittracker.ui.exchange.ExchangeEntryType.EXPENSE,
            ).forEach { (destination, initialEntryType) ->
                composable(destination.route) { backStackEntry ->
                    val parentEntry = remember(backStackEntry) {
                        navController.previousBackStackEntry?.takeIf { it.destination.route == AppDestination.EXCHANGE.route }
                            ?: backStackEntry
                    }
                    val viewModel: ExchangeViewModel = viewModel(viewModelStoreOwner = parentEntry, factory = viewModelFactory)
                    ExchangeScreen(
                        viewModel = viewModel,
                        page = ExchangePage.RECORDS,
                        initialEntryType = initialEntryType,
                        onOpenRates = {
                            if (!navController.popBackStack(AppDestination.EXCHANGE.route, false)) {
                                navController.navigate(AppDestination.EXCHANGE.route) { launchSingleTop = true }
                            }
                        },
                    )
                }
            }
            composable(AppDestination.NOTIFICATION_ASSISTANT.route) {
                val viewModel: NotificationAssistantViewModel = viewModel(factory = viewModelFactory)
                NotificationAssistantScreen(
                    viewModel = viewModel,
                    onOpenApps = { navController.navigate(AppDestination.NOTIFICATION_ASSISTANT_APPS.route) },
                    onOpenActive = { navController.navigate(AppDestination.NOTIFICATION_ASSISTANT_ACTIVE.route) },
                    onOpenTasks = { navController.navigate(AppDestination.NOTIFICATION_ASSISTANT_TASKS.route) },
                )
            }
            composable(AppDestination.NOTIFICATION_ASSISTANT_APPS.route) {
                val viewModel: NotificationAssistantViewModel = viewModel(factory = viewModelFactory)
                NotificationAssistantScreen(viewModel = viewModel, page = NotificationAssistantPage.APPS)
            }
            composable(AppDestination.NOTIFICATION_ASSISTANT_ACTIVE.route) {
                val viewModel: NotificationAssistantViewModel = viewModel(factory = viewModelFactory)
                NotificationAssistantScreen(viewModel = viewModel, page = NotificationAssistantPage.ACTIVE)
            }
            composable(AppDestination.NOTIFICATION_ASSISTANT_TASKS.route) {
                val viewModel: NotificationAssistantViewModel = viewModel(factory = viewModelFactory)
                NotificationAssistantScreen(viewModel = viewModel, page = NotificationAssistantPage.TASKS)
            }
        }
    }
}
}

@Composable
private fun MenuTreeNode(
    destination: AppDestination,
    onOpen: (AppDestination) -> Unit,
    showIcon: Boolean = true,
) {
    val children = remember(destination) { destination.children }
    var expanded by rememberSaveable(destination.route) { mutableStateOf(false) }
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surface),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f)
                    .clickable(role = Role.Button, onClickLabel = "${destination.label} 열기") { onOpen(destination) }
                    .sizeIn(minHeight = if (showIcon) 64.dp else 52.dp)
                    .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                if (showIcon) {
                    com.habittracker.ui.components.AppFeatureIcon(destination.route, destination.iconRes, Modifier.size(40.dp))
                }
                Text(
                    destination.label,
                    style = if (showIcon) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                    fontWeight = if (showIcon) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
            if (children.isNotEmpty()) {
                androidx.compose.material3.IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                        contentDescription = "${destination.label} 하위 메뉴 ${if (expanded) "접기" else "펼치기"}"
                        stateDescription = if (expanded) "펼쳐짐" else "접힘"
                    },
                ) {
                    val iconColor = MaterialTheme.colorScheme.onSurfaceVariant
                    Canvas(Modifier.size(20.dp)) {
                        val unit = size.width / 20f
                        drawPath(Path().apply {
                            if (expanded) {
                                moveTo(5 * unit, 12 * unit); lineTo(10 * unit, 7 * unit); lineTo(15 * unit, 12 * unit)
                            } else {
                                moveTo(5 * unit, 7 * unit); lineTo(10 * unit, 12 * unit); lineTo(15 * unit, 7 * unit)
                            }
                        }, iconColor, style = Stroke(1.8f * unit))
                    }
                }
            }
        }
        if (children.isNotEmpty() && expanded) {
            Column(
                modifier = Modifier.padding(start = AppSpacing.md, top = AppSpacing.xs, bottom = AppSpacing.sm).drawBehind {
                    drawLine(lineColor, Offset.Zero, Offset(0f, size.height), strokeWidth = 1.dp.toPx())
                }.padding(start = AppSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
            ) {
                children.forEach { child ->
                    androidx.compose.runtime.key(child.route) {
                        MenuTreeNode(destination = child, onOpen = onOpen, showIcon = false)
                    }
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
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        AppDestination.bottomNavigation.forEach { destination ->
            val selected = if (destination == AppDestination.MORE) {
                currentDestination != null && AppDestination.bottomNavigation
                    .filterNot { it == AppDestination.MORE }
                    .none { it.matches(currentDestination.route) }
            } else currentDestination?.hierarchy?.any { current ->
                destination.matches(current.route)
            } == true
            NavigationBarItem(
                label = { Text(destination.label, style = MaterialTheme.typography.labelSmall) },
                colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.surface,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
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
