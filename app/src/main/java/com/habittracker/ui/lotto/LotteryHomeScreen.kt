package com.habittracker.ui.lotto

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.habittracker.ui.components.AppHeroCard
import com.habittracker.ui.components.AppNavigationRow
import com.habittracker.ui.components.AppScreen
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppSectionHeader
import com.habittracker.ui.components.AppSecondaryButton
import com.habittracker.ui.components.AppSupportText
import com.habittracker.ui.components.AppMenuGroup
import com.habittracker.ui.components.AppSectionNavigation
import com.habittracker.ui.components.LocalAppNavigationGuard
import com.habittracker.ui.navigation.AppDestination
import kotlinx.coroutines.delay

@Composable
fun LotteryHomeScreen(
    onOpenLotto645: () -> Unit,
    onOpenPensionLottery: () -> Unit,
) {
    val context = LocalContext.current
    val permissionPreferences = remember(context) {
        context.getSharedPreferences("lottery-notification-permission", android.content.Context.MODE_PRIVATE)
    }
    var notificationPermissionGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationPermissionGranted = granted
    }
    LaunchedEffect(Unit) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !notificationPermissionGranted &&
            !permissionPreferences.getBoolean("requested", false)
        ) {
            delay(1_850)
            permissionPreferences.edit().putBoolean("requested", true).apply()
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppScreen {
            item {
                AppHeroCard(
                    title = "동행복권",
                )
            }
            item {
                AppSectionCard {
                    AppNavigationRow(
                        title = "로또 6/45",
                        subtitle = "번호 생성 · 구입 내역 · 추첨 결과",
                        onClick = onOpenLotto645,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AppNavigationRow(
                        title = "연금복권 720+",
                        subtitle = "번호 생성 · 구입 내역 · 추첨 결과",
                        onClick = onOpenPensionLottery,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                AppSectionCard {
                    AppSectionHeader(
                        title = "자동 당첨번호 확인",
                        subtitle = "연금복권은 목요일 19:35, 로또는 토요일 21:05경 공식 결과를 확인합니다.",
                    )
                    AppSupportText("실패하면 30분, 60분 후 최대 2회 다시 시도하고, 최종 실패 원인을 알림으로 표시합니다.")
                    AppSupportText("공식 번호가 저장되면 로또와 연금복권 구입번호를 자동 대조해 결과를 알립니다.")
                    if (!notificationPermissionGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        AppSupportText("동기화 실패·복권 당첨 결과 알림을 받으려면 알림 권한을 허용해 주세요. 권한이 없어도 화면에서 결과를 확인할 수 있습니다.")
                        AppSecondaryButton(
                            text = "복권 결과 알림 권한 허용",
                            onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun LotteryScreenHeader(
    lottery: AppDestination,
    latestRoundNo: Int?,
    nextRoundNo: Int? = latestRoundNo?.plus(1),
) {
    AppHeroCard(
        title = lottery.label,
        description = "번호 생성과 구입·추첨 이력을 관리합니다.",
        status = "최근 ${latestRoundNo ?: "-"}회 · 다음 ${nextRoundNo ?: "-"}회",
    )
}

@Composable
internal fun LotterySectionNavigation(
    lottery: AppDestination,
    selected: AppDestination,
    onSelect: (AppDestination) -> Unit,
) {
    val guard = LocalAppNavigationGuard.current
    val groups = remember(lottery) { lottery.childGroups }
    AppSectionNavigation(
        title = lottery.label,
        groups = groups.map { (title, destinations) ->
            AppMenuGroup(title, destinations.map { it.route to it.label })
        },
        selectedKey = selected.route,
        onSelect = { route ->
            val destination = groups.asSequence().flatMap { it.second.asSequence() }.first { it.route == route }
            if (destination != selected) guard.navigate { onSelect(destination) }
        },
    )
}
