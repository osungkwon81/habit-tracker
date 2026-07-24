package com.habittracker.ui.stock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.habittracker.ui.components.AppPrimaryButton
import com.habittracker.ui.components.AppScreen
import com.habittracker.ui.components.AppSecondaryButton
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppSelectableChip
import com.habittracker.ui.components.AppSpacing
import com.habittracker.ui.components.AppStatusText
import com.habittracker.ui.components.AppSupportText
import com.habittracker.ui.components.AppTextField

@Composable
fun StockSettingsScreen(viewModel: StockViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    StockStatusDialog(uiState, viewModel::clearStatusMessage)

    AppScreen {
        item {
            StockHeroCard(
                icon = "⚙",
                eyebrow = "STOCK · SETTINGS",
                title = "KIS·안전 설정",
                description = "KIS 실전 계좌와 주문·자동화 안전 한도를 관리합니다.",
            )
        }
        item {
            AppSectionCard {
                StockSectionTitle("KIS 실전 계좌")
                AppStatusText(if (uiState.isConfigSaved) "실전 계좌 설정 저장됨" else "설정 필요")
                AppSecondaryButton(
                    text = if (uiState.isConfigExpanded) "입력 닫기" else "설정 입력",
                    onClick = viewModel::toggleConfigExpanded,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (uiState.isConfigExpanded) {
                    AppTextField(
                        value = uiState.appKey,
                        onValueChange = viewModel::updateAppKey,
                        label = "실전 App Key",
                        singleLine = true,
                    )
                    AppTextField(
                        value = uiState.appSecret,
                        onValueChange = viewModel::updateAppSecret,
                        label = "실전 App Secret",
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                    ) {
                        AppTextField(
                            value = uiState.accountNumber,
                            onValueChange = viewModel::updateAccountNumber,
                            label = "계좌 앞 8자리",
                            modifier = Modifier.weight(2f),
                            singleLine = true,
                        )
                        AppTextField(
                            value = uiState.accountProductCode,
                            onValueChange = viewModel::updateAccountProductCode,
                            label = "뒤 2자리",
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                    }
                    AppPrimaryButton(
                        text = "실전 계좌 설정 저장",
                        onClick = viewModel::saveConfig,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                AppSupportText("App Key, App Secret, 계좌번호는 Android Keystore 기반 암호화 후 로컬 DB에 저장됩니다.")
            }
        }
        item {
            AppSectionCard {
                StockSectionTitle("자동 주문 허용")
                SettingSwitchRow(
                    title = "자동 매도 주문",
                    description = "꺼져 있으면 자동 규칙은 알림만 남기고 주문하지 않습니다.",
                    checked = uiState.automaticOrderEnabled,
                    onCheckedChange = viewModel::setAutomaticOrderEnabled,
                )
                Text(
                    "실전 주문이므로 규칙과 한도를 모두 확인한 뒤 켜세요.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            AppSectionCard {
                StockSectionTitle("급락 시 전체 주문 차단")
                SettingSwitchRow(
                    title = "시장 급락 감시",
                    description = "선택한 지수의 전일 대비 하락률이 기준에 도달하면 모든 주문을 차단합니다.",
                    checked = uiState.crashGuardEnabled,
                    onCheckedChange = viewModel::setCrashGuardEnabled,
                )
                if (uiState.crashGuardEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                    ) {
                        listOf("0001" to "코스피", "1001" to "코스닥", "2001" to "코스피200").forEach { (code, label) ->
                            AppSelectableChip(
                                label = label,
                                selected = uiState.crashBenchmarkCode == code,
                                onClick = { viewModel.selectCrashBenchmark(code) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    AppTextField(
                        value = uiState.crashThresholdPercent,
                        onValueChange = viewModel::updateCrashThreshold,
                        label = "전체 주문 차단 하락률 (%)",
                        singleLine = true,
                    )
                    AppSupportText("예: 3을 입력하면 선택 지수가 전일 대비 -3% 이하일 때 영구 차단하며, 사용자가 직접 확인 후 해제해야 합니다.")
                }
            }
        }
        item {
            AppSectionCard {
                StockSectionTitle("모니터링·주문 한도")
                AppTextField(
                    value = uiState.monitorIntervalMinutes,
                    onValueChange = viewModel::updateMonitoringInterval,
                    label = "잔고·안전설정 동기화 주기 (분)",
                    singleLine = true,
                )
                AppTextField(
                    value = uiState.maxOrderAmount,
                    onValueChange = viewModel::updateMaxOrderAmount,
                    label = "1회 최대 주문금액 (원, 빈칸 제한 없음·긴급 전체 매도 제외)",
                    singleLine = true,
                )
                AppTextField(
                    value = uiState.dailyBuyLimit,
                    onValueChange = viewModel::updateDailyBuyLimit,
                    label = "하루 최대 매수금액 (원, 빈칸은 제한 없음)",
                    singleLine = true,
                )
                AppPrimaryButton(
                    text = "안전 설정 저장",
                    onClick = viewModel::saveSafetySettings,
                    modifier = Modifier.fillMaxWidth(),
                )
                AppSupportText("시장가 주문도 주문 직전 현재가를 기준으로 한도를 검사합니다. 체결금액은 가격 변동에 따라 달라질 수 있습니다.")
                AppSupportText("손절·익절 가격은 실시간으로 감시하며, 입력한 주기는 잔고·활성 규칙·급락 안전장치를 다시 확인하는 간격입니다.")
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
