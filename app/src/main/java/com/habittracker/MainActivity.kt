package com.habittracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.habittracker.ui.AppViewModelFactory
import com.habittracker.ui.admin.AdminScreen
import com.habittracker.ui.admin.AdminViewModel
import com.habittracker.ui.diary.DiaryScreen
import com.habittracker.ui.diary.DiaryViewModel
import com.habittracker.ui.entry.DailyEntryScreen
import com.habittracker.ui.entry.DailyEntryViewModel
import com.habittracker.ui.home.HomeScreen
import com.habittracker.ui.home.HomeViewModel
import com.habittracker.ui.lotto.LottoScreen
import com.habittracker.ui.lotto.LottoViewModel
import com.habittracker.ui.navigation.AppDestination
import com.habittracker.ui.stats.MonthlyStatsScreen
import com.habittracker.ui.stats.MonthlyStatsViewModel
import com.habittracker.ui.theme.HabitTrackerTheme
import java.time.LocalDate

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
    val bottomDestinations = listOf(
        AppDestination.HOME,
        AppDestination.ENTRY,
        AppDestination.DIARY,
        AppDestination.STATS,
        AppDestination.ADMIN,
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                bottomDestinations.forEach { destination ->
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any { route -> route.route == destination.route || route.route?.startsWith("${destination.route}/") == true } == true,
                        onClick = {
                            val popped = navController.popBackStack(destination.route, false)
                            if (!popped) {
                                navController.navigate(destination.route) {
                                    launchSingleTop = true
                                }
                            }
                        },
                        icon = { Text(destination.label.take(1)) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AppDestination.HOME.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(AppDestination.HOME.route) {
                val viewModel: HomeViewModel = viewModel(factory = AppViewModelFactory())
                HomeScreen(
                    viewModel = viewModel,
                    onOpenRecord = { date -> navController.navigate("${AppDestination.ENTRY.route}/${date}") },
                    onOpenDiary = { navController.navigate(AppDestination.DIARY.route) },
                    onOpenStats = { navController.navigate(AppDestination.STATS.route) },
                    onOpenAdmin = { navController.navigate(AppDestination.ADMIN.route) },
                    onOpenLotto = { navController.navigate(AppDestination.LOTTO.route) },
                )
            }
            composable(AppDestination.ENTRY.route) {
                val viewModel: DailyEntryViewModel = viewModel(factory = AppViewModelFactory())
                DailyEntryScreen(viewModel = viewModel, initialDate = LocalDate.now().toString())
            }
            composable("${AppDestination.ENTRY.route}/{date}") { backStackEntry ->
                val viewModel: DailyEntryViewModel = viewModel(factory = AppViewModelFactory())
                DailyEntryScreen(viewModel = viewModel, initialDate = backStackEntry.arguments?.getString("date") ?: LocalDate.now().toString())
            }
            composable(AppDestination.DIARY.route) {
                val viewModel: DiaryViewModel = viewModel(factory = AppViewModelFactory())
                DiaryScreen(viewModel = viewModel)
            }
            composable(AppDestination.STATS.route) {
                val viewModel: MonthlyStatsViewModel = viewModel(factory = AppViewModelFactory())
                MonthlyStatsScreen(viewModel = viewModel)
            }
            composable(AppDestination.ADMIN.route) {
                val viewModel: AdminViewModel = viewModel(factory = AppViewModelFactory())
                AdminScreen(viewModel = viewModel)
            }
            composable(AppDestination.LOTTO.route) {
                val viewModel: LottoViewModel = viewModel(factory = AppViewModelFactory())
                LottoScreen(viewModel = viewModel)
            }
        }
    }
}
