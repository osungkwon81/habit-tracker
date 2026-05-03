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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
import com.habittracker.ui.memo.MemoScreen
import com.habittracker.ui.memo.MemoViewModel
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
        AppDestination.MEMO,
        AppDestination.STATS,
        AppDestination.ADMIN,
    )

    Scaffold(
        bottomBar = {
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
                    bottomDestinations.forEach { destination ->
                        val selected = currentDestination?.hierarchy?.any { route ->
                            route.route == destination.route || route.route?.startsWith("${destination.route}/") == true
                        } == true
                        FloatingNavItem(
                            emoji = destination.emoji,
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
                    onOpenMemo = { navController.navigate(AppDestination.MEMO.route) },
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
            composable(AppDestination.MEMO.route) {
                val viewModel: MemoViewModel = viewModel(factory = AppViewModelFactory())
                MemoScreen(viewModel = viewModel)
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

@Composable
private fun FloatingNavItem(
    emoji: String,
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
