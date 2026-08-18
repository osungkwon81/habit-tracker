package com.habittracker.ui.lotto

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.habittracker.R
import com.habittracker.ui.components.AppHeroCard
import com.habittracker.ui.components.AppPrimaryButton
import com.habittracker.ui.components.AppScreen
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppSectionHeader
import com.habittracker.ui.components.AppSecondaryButton
import com.habittracker.ui.components.AppSupportText

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
            permissionPreferences.edit().putBoolean("requested", true).apply()
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    AppScreen {
        item {
            AppHeroCard(
                title = "동행복권",
                description = "관리할 복권 종류를 선택합니다.",
                iconRes = R.drawable.home_quick_lotto,
                eyebrow = "LOTTERY · SELECT",
            )
        }
        item {
            AppSectionCard {
                AppSectionHeader(
                    title = "복권 선택",
                    subtitle = "로또 6/45 또는 연금복권 720+로 이동합니다.",
                )
                AppPrimaryButton(
                    text = "🎯 로또 6/45",
                    onClick = onOpenLotto645,
                    modifier = Modifier.fillMaxWidth(),
                )
                AppPrimaryButton(
                    text = "🎟️ 연금복권 720+",
                    onClick = onOpenPensionLottery,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            AppSectionCard {
                AppSectionHeader(
                    title = "자동 당첨번호 확인",
                    subtitle = "연금복권은 목요일 20:30, 로또는 토요일 22:00경 공식 결과를 확인합니다.",
                )
                AppSupportText("실패하면 30분, 60분 후 최대 2회 다시 시도하고, 최종 실패 원인을 알림으로 표시합니다.")
                AppSupportText("로또 공식 번호가 저장되면 QR 등록표와 구매완료 저장번호도 자동 대조해 결과를 알립니다.")
                if (!notificationPermissionGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    AppSupportText("동기화 실패·로또 당첨 결과 알림을 받으려면 알림 권한을 허용해 주세요. 권한이 없어도 화면에서 결과를 확인할 수 있습니다.")
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
