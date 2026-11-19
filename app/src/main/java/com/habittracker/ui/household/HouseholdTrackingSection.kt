package com.habittracker.ui.household

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.habittracker.data.*
import com.habittracker.ui.components.*

@Composable
internal fun HouseholdTrackingSection(vm: HouseholdPriceViewModel, row: HouseholdSummary, settings: HouseholdTrackingSettings, busy: Boolean) {
    val context = LocalContext.current
    val id = row.history.product.id
    val manager = context.getSystemService(NotificationManager::class.java)
    fun blocked() = !manager.areNotificationsEnabled() ||
        manager.getNotificationChannel(HouseholdPriceTracking.CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE
    var permissionBlocked by remember { mutableStateOf(blocked()) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) permissionBlocked = blocked() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionBlocked = blocked() }
    var removal by rememberSaveable(id) { mutableStateOf<String?>(null) }
    val target = settings.links.find { it.id == removal }
    if (target != null) ConfirmRemoveOffer(row, target, { removal = null }) {
        vm.removeLink(id, target.id, false)
        removal = null
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppSectionHeader("가격 추적", subtitle = "1시간마다 등록한 링크를 확인합니다. 기본 배송비 포함 단위당 가격이 내 최저가의 105% 이하면 알립니다.")
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            listOf(true to "켜기", false to "끄기").forEach { (enable, label) ->
                val active = !busy && settings.links.isNotEmpty()
                Row(Modifier.weight(1f).heightIn(min = 48.dp).selectable(selected = enable == settings.enabled,
                    enabled = active, role = Role.RadioButton, onClick = {
                        if (enable != settings.enabled) {
                            vm.enableTracking(id, enable)
                            if (enable && Build.VERSION.SDK_INT >= 33 &&
                                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = enable == settings.enabled, enabled = active, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(label)
                }
            }
        }
        if (settings.enabled && permissionBlocked) {
            AppSupportText("가격·품절 알림을 받으려면 휴대폰 설정에서 알림을 허용해 주세요.")
            AppSecondaryButton("알림 설정", onClick = {
                val channel = manager.areNotificationsEnabled() && manager.getNotificationChannel(HouseholdPriceTracking.CHANNEL) != null
                context.startActivity(Intent(if (channel) Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS else Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, HouseholdPriceTracking.CHANNEL))
            })
        }
        settings.links.forEach { link ->
            HorizontalDivider()
            Text("${offerSite(link.url)} · ${link.count}${link.unit}" + link.option.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                style = MaterialTheme.typography.titleSmall)
            Text(link.url, style = MaterialTheme.typography.bodySmall)
            if (link.status.isNotBlank()) AppSupportText(link.status)
            Row {
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url))) }) { Text("상품 열기") }
                TextButton(enabled = !busy, onClick = { removal = link.id }) { Text("링크 삭제") }
            }
        }
        AppSecondaryButton("추적 링크 추가", enabled = !busy, onClick = { vm.editLink(row) })
    }
}

@Composable
internal fun ConfirmRemoveOffer(row: HouseholdSummary, link: HouseholdOfferLink, dismiss: () -> Unit, confirm: () -> Unit) {
    AppConfirmDialog(title = "추적 링크를 삭제할까요?",
        message = "${row.history.product.name} · ${offerSite(link.url)} · ${link.count}${link.unit}" +
            link.option.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty() + "\n${link.url}\n이 링크의 가격 확인이 중단됩니다.",
        confirmText = "링크 삭제", onDismiss = dismiss, onConfirm = confirm)
}
