package com.habittracker.ui.lotto

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import com.habittracker.data.lotto.LotteryProduct
import com.habittracker.data.lotto.LotterySyncState
import com.habittracker.data.lotto.LotterySyncStatus
import com.habittracker.ui.components.AppPrimaryButton
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppSectionHeader
import com.habittracker.ui.components.AppStatusText
import com.habittracker.ui.components.AppSupportText
import java.time.format.DateTimeFormatter

@Composable
fun LotterySyncStatusCard(
    product: LotteryProduct,
    status: LotterySyncStatus,
    isSyncing: Boolean,
    onSyncNow: () -> Unit,
) {
    AppSectionCard {
        AppSectionHeader(
            title = "공식 당첨번호 자동 동기화",
            subtitle = when (product) {
                LotteryProduct.LOTTO_645 -> "매주 토요일 22:00경 확인합니다."
                LotteryProduct.PENSION_720 -> "매주 목요일 20:30경 확인합니다."
            },
        )
        status.message?.let { message -> AppStatusText(message) }
        status.lastSuccessAt?.let { successAt ->
            AppSupportText(
                "최근 성공 ${successAt.format(statusTimeFormatter)}" +
                    (status.lastSuccessRound?.let { round -> " · ${round}회" } ?: ""),
            )
        }
        AppPrimaryButton(
            text = if (isSyncing || status.state == LotterySyncState.RUNNING) "공식 번호 확인 중" else "지금 당첨번호 가져오기",
            onClick = onSyncNow,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSyncing && status.state != LotterySyncState.RUNNING,
        )
        AppSupportText("자동 조회에 문제가 있으면 아래 수동 입력을 그대로 사용할 수 있습니다.")
    }
}

private val statusTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
