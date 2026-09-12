package com.habittracker.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.habittracker.data.AppSettingsStore
import com.habittracker.data.lotto.LottoTicketResultNotifier
import com.habittracker.ui.components.AppActionNotice
import com.habittracker.ui.components.AppHeroCard
import com.habittracker.ui.components.AppPrimaryButton
import com.habittracker.ui.components.AppScreen
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppSectionHeader
import com.habittracker.ui.components.AppSupportText

@Composable
fun AppManagementScreen() {
    val context = LocalContext.current
    var resultNotificationsEnabled by remember {
        mutableStateOf(AppSettingsStore.areLotteryResultNotificationsEnabled(context))
    }
    var syncFailureNotificationsEnabled by remember {
        mutableStateOf(AppSettingsStore.areLotterySyncFailureNotificationsEnabled(context))
    }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            LottoTicketResultNotifier.showTest(context)
            statusMessage = "테스트 알림을 보냈습니다."
        } else {
            statusMessage = "알림을 받으려면 시스템 알림 권한을 허용해 주세요."
        }
    }
    AppActionNotice(statusMessage) { statusMessage = null }

    AppScreen {
        item {
            AppHeroCard(
                title = "앱 관리",
                description = "앱 전체에 적용되는 알림과 음성 입력을 관리합니다.",
                eyebrow = "APP · SETTINGS",
            )
        }
        item {
            AppSectionCard {
                AppSectionHeader(title = "복권 알림")
                SettingToggleRow(
                    title = "구입 번호 당첨 결과",
                    subtitle = "공식 번호가 저장되면 내 구입 번호의 결과를 알립니다.",
                    checked = resultNotificationsEnabled,
                    onCheckedChange = { enabled ->
                        resultNotificationsEnabled = enabled
                        AppSettingsStore.setLotteryResultNotificationsEnabled(context, enabled)
                    },
                )
                SettingToggleRow(
                    title = "공식 번호 확인 실패",
                    subtitle = "재시도 후에도 실패한 경우에만 알립니다.",
                    checked = syncFailureNotificationsEnabled,
                    onCheckedChange = { enabled ->
                        syncFailureNotificationsEnabled = enabled
                        AppSettingsStore.setLotterySyncFailureNotificationsEnabled(context, enabled)
                    },
                )
                AppPrimaryButton(
                    text = "테스트 알림 보내기",
                    onClick = {
                        if (
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            LottoTicketResultNotifier.showTest(context)
                            statusMessage = "테스트 알림을 보냈습니다."
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = resultNotificationsEnabled,
                )
            }
        }
        item {
            AppSectionCard {
                AppSectionHeader(
                    title = "음성 입력",
                    subtitle = "마이크 버튼으로 음성 입력을 직접 켜고 끌 수 있습니다.",
                )
                AppSupportText("한 문장의 인식이 끝나면 다음 음성 인식을 자동으로 이어갑니다. 마이크를 다시 누르면 종료됩니다.")
            }
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
