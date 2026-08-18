package com.habittracker.ui.lotto

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import com.habittracker.R
import com.habittracker.ui.components.AppHeroCard
import com.habittracker.ui.components.AppPrimaryButton
import com.habittracker.ui.components.AppScreen
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppSectionHeader

@Composable
fun LotteryHomeScreen(
    onOpenLotto645: () -> Unit,
    onOpenPensionLottery: () -> Unit,
) {
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
    }
}
