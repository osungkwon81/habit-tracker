package com.habittracker.ui.notification

import android.app.NotificationManager
import android.app.DatePickerDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.habittracker.data.local.entity.NotificationAssistantItemEntity
import com.habittracker.data.notification.NotificationAssistantCategory
import com.habittracker.data.notification.NotificationAssistantListenerService
import com.habittracker.data.notification.NotificationAssistantPreferences
import com.habittracker.data.notification.NotificationAssistantStatus
import com.habittracker.ui.components.AppEmptyCard
import com.habittracker.ui.components.AppConfirmDialog
import com.habittracker.ui.components.AppHeroCard
import com.habittracker.ui.components.AppPrimaryButton
import com.habittracker.ui.components.AppScreen
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppSectionHeader
import com.habittracker.ui.components.AppSecondaryButton
import com.habittracker.ui.components.AppSpacing
import com.habittracker.ui.components.AppSupportText
import com.habittracker.ui.components.AppTextField
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class SelectableNotificationApp(val packageName: String, val label: String)
private data class PurchaseSummary(
    val payment: NotificationAssistantItemEntity,
    val order: NotificationAssistantItemEntity?,
    val delivery: NotificationAssistantItemEntity?,
)

enum class NotificationAssistantPage { HOME, APPS, ACTIVE, TASKS }

@Composable
fun NotificationAssistantScreen(
    viewModel: NotificationAssistantViewModel,
    page: NotificationAssistantPage = NotificationAssistantPage.HOME,
    onOpenApps: () -> Unit = {},
    onOpenActive: () -> Unit = {},
    onOpenTasks: () -> Unit = {},
) {
    val context = LocalContext.current
    val isCollectionEnabled = NotificationAssistantListenerService.isCollectionEnabled
    val lifecycleOwner = LocalLifecycleOwner.current
    val preferences = remember(context) { NotificationAssistantPreferences(context) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val activeItems by NotificationAssistantListenerService.activeItems.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    var selectionRefresh by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var appSearch by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<NotificationAssistantItemEntity?>(null) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(page, refresh, selectionRefresh) {
        if (page == NotificationAssistantPage.ACTIVE) NotificationAssistantListenerService.refreshActiveSnapshot()
    }
    val hasAccess = remember(context, refresh) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.isNotificationListenerAccessGranted(ComponentName(context, NotificationAssistantListenerService::class.java))
    }
    val selectableApps by produceState<List<SelectableNotificationApp>?>(initialValue = null, context, refresh, page) {
        if (page == NotificationAssistantPage.APPS && isCollectionEnabled) {
            value = withContext(Dispatchers.IO) { loadSelectableApps(context, preferences) }
        }
    }
    val enabledPackages = remember(refresh, selectionRefresh) { preferences.enabledPackages() }
    val visibleApps = remember(selectableApps, appSearch, enabledPackages) {
        selectableApps.orEmpty().filter {
            appSearch.isBlank() || it.label.contains(appSearch, ignoreCase = true) ||
                it.packageName.contains(appSearch, ignoreCase = true)
        }.sortedWith(
            compareByDescending<SelectableNotificationApp> { it.packageName in enabledPackages }
                .thenBy { it.label.lowercase() },
        )
    }
    val visibleItems = remember(uiState.items, search) {
        val tasks = uiState.items.filter {
            it.category == NotificationAssistantCategory.RESERVATION || it.category == NotificationAssistantCategory.DEADLINE
        }
        if (search.isBlank()) tasks else tasks.filter {
            it.title.contains(search, ignoreCase = true) || it.sourceText.contains(search, ignoreCase = true)
        }
    }
    val purchaseSummaries = remember(uiState.items) { buildPurchaseSummaries(uiState.items) }
    val todayPaymentTotal = remember(uiState.items) {
        uiState.items.filter {
            it.category == NotificationAssistantCategory.PAYMENT && it.receivedAt.toLocalDate() == LocalDate.now()
        }.sumOf { it.amount ?: 0L }
    }
    val todayDeliveryCount = uiState.items.count {
        it.category == NotificationAssistantCategory.DELIVERY &&
            it.deliveryState == "배송 완료" && it.receivedAt.toLocalDate() == LocalDate.now()
    }
    val today = LocalDate.now()
    val pending = visibleItems.filter { it.status == NotificationAssistantStatus.PENDING }
    val due = visibleItems.filter {
        it.status == NotificationAssistantStatus.ACCEPTED && it.eventAt?.toLocalDate()?.let { date -> !date.isAfter(today) } == true
    }
    val upcoming = visibleItems.filter {
        it.status == NotificationAssistantStatus.ACCEPTED && (it.eventAt == null || it.eventAt.toLocalDate().isAfter(today))
    }
    val completed = visibleItems.filter { it.status == NotificationAssistantStatus.DONE }
    val ignored = visibleItems.filter { it.status == NotificationAssistantStatus.IGNORED }

    deleteTarget?.let { target ->
        AppConfirmDialog(
            title = "알림 항목 삭제",
            message = "${target.title} 항목을 삭제합니다.",
            confirmText = "삭제",
            onConfirm = {
                viewModel.delete(target.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }

    AppScreen {
        item {
            AppHeroCard(
                title = when (page) {
                    NotificationAssistantPage.HOME -> "알림 생활비서"
                    NotificationAssistantPage.APPS -> "알림 체크 앱"
                    NotificationAssistantPage.ACTIVE -> "실제 알림 목록"
                    NotificationAssistantPage.TASKS -> "알림에서 찾은 할 일"
                },
                description = when (page) {
                    NotificationAssistantPage.HOME -> "수집 중단 전 저장된 정보를 확인합니다."
                    NotificationAssistantPage.APPS -> "데이터 수집 중단으로 앱 선택을 일시 중지했습니다."
                    NotificationAssistantPage.ACTIVE -> "알림 데이터 수집이 중단되어 있습니다."
                    NotificationAssistantPage.TASKS -> "알림에서 찾은 예약·마감 정보를 확인합니다."
                },
            )
        }
        item {
            AppSupportText("알림 데이터 수집을 중단했습니다. 기존 저장 데이터와 앱 선택 설정은 유지됩니다.")
        }
        if (page == NotificationAssistantPage.HOME) {
            item {
                AppSectionCard {
                    Text("오늘 요약", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("알림 결제 추정 합계 ${todayPaymentTotal.asWon()} · 배송 완료 알림 ${todayDeliveryCount}건")
                    AppSupportText("선택한 앱의 알림에 표시된 정보만 집계하며 중복 알림은 확인이 필요합니다. 카드 화면의 수동 입력 기록과 합산하지 않습니다.")
                }
            }
            item { AppSectionHeader(title = "결제·주문·배송 요약") }
            if (purchaseSummaries.isEmpty()) item { AppEmptyCard("연결할 결제 알림이 없습니다.") }
            purchaseSummaries.forEach { summary ->
                item(key = "payment:${summary.payment.id}") {
                    AppSectionCard {
                        Text(summary.order?.productName ?: "구매 상품 확인 필요", style = MaterialTheme.typography.titleMedium)
                        Text("결제 ${summary.payment.amount.asWon()} · ${summary.payment.merchant ?: "사용처 확인 필요"}")
                        Text(
                            summary.delivery?.let { "${it.deliveryState ?: "배송 상태 확인 필요"} · ${it.receivedAt.format(DateTimeFormatter.ofPattern("M월 d일 HH:mm"))}" }
                                ?: "연결된 배송 알림 없음",
                        )
                        if (summary.order != null) AppSupportText("같은 날짜·금액의 주문 알림을 연결했습니다. 상품과 결제의 실제 일치 여부를 확인해 주세요.")
                    }
                }
            }
            val connectedOrderIds = purchaseSummaries.mapNotNull { it.order?.id }.toSet()
            val allOrders = uiState.items.filter { it.category == NotificationAssistantCategory.ORDER }
            val allDeliveries = uiState.items.filter { it.category == NotificationAssistantCategory.DELIVERY }
            val connectedDeliveryIds = purchaseSummaries.mapNotNull { it.delivery?.id }.toMutableSet()
            uiState.items.filter { it.category == NotificationAssistantCategory.ORDER && it.id !in connectedOrderIds }
                .forEach { order ->
                    val delivery = findDeliveryForOrder(order, allOrders, allDeliveries)
                    delivery?.let { connectedDeliveryIds += it.id }
                    item(key = "order:${order.id}") {
                        AppSectionCard {
                            Text(order.productName ?: "주문 상품 확인 필요", style = MaterialTheme.typography.titleMedium)
                            Text("주문 ${order.amount.asWon()} · 결제 알림 연결 확인 필요")
                            delivery?.let { Text(it.deliveryState ?: "배송 상태 확인 필요") }
                        }
                    }
                }
            allDeliveries.filter { it.id !in connectedDeliveryIds }.forEach { delivery ->
                item(key = "delivery:${delivery.id}") {
                    AppSectionCard {
                        Text(delivery.productName ?: "배송 상품 확인 필요", style = MaterialTheme.typography.titleMedium)
                        Text("${delivery.deliveryState ?: "배송 상태 확인 필요"} · 주문·결제 연결 확인 필요")
                    }
                }
            }
            item { AppSectionHeader(title = "알림 관리") }
            item { AppSecondaryButton("알림 체크 앱", onOpenApps, Modifier.fillMaxWidth()) }
            item { AppSecondaryButton("실제 알림 목록", onOpenActive, Modifier.fillMaxWidth()) }
            item { AppPrimaryButton("알림에서 찾은 할 일", onOpenTasks, Modifier.fillMaxWidth()) }
        }
        if (page == NotificationAssistantPage.APPS && isCollectionEnabled) {
        item {
            AppSectionCard {
                Text("알림 접근", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (hasAccess) "알림 접근이 켜져 있습니다. 아래에서 분석할 앱을 선택하세요."
                    else "알림 접근을 켠 뒤, 분석할 앱을 따로 선택하세요. 선택 전에는 알림 내용을 저장하지 않습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!hasAccess) {
                    AppPrimaryButton(
                        text = "알림 접근 설정 열기",
                        onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                AppSecondaryButton(text = "앱 목록 새로고침", onClick = { refresh++ }, modifier = Modifier.fillMaxWidth())
                Text("문자도 알림에 본문이 표시될 때만 분석할 수 있습니다. 선택한 앱의 관련 알림만 기기 안에서 분석합니다.", style = MaterialTheme.typography.bodySmall)
            }
        }
        item { AppSectionHeader(title = "분석할 앱", subtitle = "택배 문자는 문자 앱을 켜세요. 분석을 켠 앱은 맨 위에 표시합니다.") }
        item { AppTextField(value = appSearch, onValueChange = { appSearch = it }, label = "앱 이름 검색", singleLine = true) }
        if (selectableApps == null) item { Text("설치된 앱을 불러오고 있습니다.") }
        else if (visibleApps.isEmpty()) item { AppEmptyCard("표시할 앱이 없습니다.") }
        items(visibleApps, key = { "app:${it.packageName}" }) { app ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = AppSpacing.xs), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f).padding(end = AppSpacing.sm)) {
                    Text(app.label, style = MaterialTheme.typography.bodyLarge)
                    Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = app.packageName in enabledPackages,
                    onCheckedChange = { enabled ->
                        preferences.setEnabled(app.packageName, enabled)
                        selectionRefresh++
                        NotificationAssistantListenerService.refreshActiveSnapshot()
                    },
                    enabled = hasAccess,
                )
            }
        }
        }
        if (page == NotificationAssistantPage.ACTIVE) {
            if (!isCollectionEnabled) {
                item { AppEmptyCard("수집 중단으로 현재 알림을 조회하지 않습니다.") }
            } else if (!hasAccess) {
                item { AppEmptyCard("알림 접근을 켠 뒤 확인할 수 있습니다.") }
            } else if (activeItems.isEmpty()) {
                item { AppEmptyCard("선택한 앱의 현재 알림이 없습니다.") }
            } else {
                items(activeItems, key = { it.key }) { notification ->
                    AppSectionCard {
                        Text(appLabel(context, notification.packageName), style = MaterialTheme.typography.labelLarge)
                        Text(notification.title.ifBlank { "제목 없음" }, style = MaterialTheme.typography.titleMedium)
                        if (notification.content.isNotBlank()) Text(notification.content)
                    }
                }
            }
        }
        if (page == NotificationAssistantPage.TASKS) {
        item {
            AppTextField(value = search, onValueChange = { search = it }, label = "알림 항목 검색", singleLine = true)
        }
        item { AppSectionHeader(title = "오늘까지 할 일", subtitle = "확인한 예약·마감 정보") }
        if (due.isEmpty()) item { AppEmptyCard("오늘까지 할 일이 없습니다.") }
        due.forEach { entry -> item(key = entry.id) { AssistantItem(entry, uiState.busyIds, viewModel) { deleteTarget = entry } } }
        item { AppSectionHeader(title = "확인 대기", subtitle = "내용을 확인한 뒤 추가해 주세요.") }
        if (pending.isEmpty()) item { AppEmptyCard("확인을 기다리는 알림이 없습니다.") }
        pending.forEach { entry -> item(key = entry.id) { AssistantItem(entry, uiState.busyIds, viewModel) { deleteTarget = entry } } }
        if (upcoming.isNotEmpty()) {
            item { AppSectionHeader(title = "예정 및 날짜 확인 필요") }
            upcoming.forEach { entry -> item(key = entry.id) { AssistantItem(entry, uiState.busyIds, viewModel) { deleteTarget = entry } } }
        }
        if (completed.isNotEmpty()) {
            item { AppSectionHeader(title = "완료") }
            completed.forEach { entry -> item(key = entry.id) { AssistantItem(entry, uiState.busyIds, viewModel) { deleteTarget = entry } } }
        }
        if (ignored.isNotEmpty()) {
            item { AppSectionHeader(title = "무시한 제안") }
            ignored.forEach { entry -> item(key = entry.id) { AssistantItem(entry, uiState.busyIds, viewModel) { deleteTarget = entry } } }
        }
        uiState.message?.let { message ->
            item {
                Text(message, color = MaterialTheme.colorScheme.primary)
            }
        }
        }
    }
}

private fun buildPurchaseSummaries(items: List<NotificationAssistantItemEntity>): List<PurchaseSummary> {
    val payments = items.filter { it.category == NotificationAssistantCategory.PAYMENT }
    val orders = items.filter { it.category == NotificationAssistantCategory.ORDER }
    val deliveries = items.filter { it.category == NotificationAssistantCategory.DELIVERY }
    return payments.map { payment ->
        val sameAmountOrders = orders.filter {
            payment.amount != null && it.amount == payment.amount &&
                it.receivedAt.toLocalDate() == payment.receivedAt.toLocalDate()
        }
        val order = sameAmountOrders.singleOrNull()?.takeIf { candidate ->
            payments.count {
                it.amount == candidate.amount && it.receivedAt.toLocalDate() == candidate.receivedAt.toLocalDate()
            } == 1
        }
        PurchaseSummary(payment, order, order?.let { findDeliveryForOrder(it, orders, deliveries) })
    }
}

private fun findDeliveryForOrder(
    order: NotificationAssistantItemEntity,
    orders: List<NotificationAssistantItemEntity>,
    deliveries: List<NotificationAssistantItemEntity>,
): NotificationAssistantItemEntity? {
    val matchingReference = order.referenceId?.let { reference ->
        deliveries.filter { it.referenceId == reference }
    }.orEmpty()
    if (matchingReference.isNotEmpty()) return matchingReference.maxByOrNull { it.receivedAt }
    val product = order.productName ?: return null
    if (orders.count { it.productName == product } != 1) return null
    return deliveries.filter {
        it.productName == product && !it.receivedAt.isBefore(order.receivedAt)
    }.maxByOrNull { it.receivedAt }
}

private fun Long?.asWon(): String = this?.let {
    "${NumberFormat.getNumberInstance(Locale.KOREA).format(it)}원"
} ?: "금액 확인 필요"

@Composable
private fun AssistantItem(
    entry: NotificationAssistantItemEntity,
    busyIds: Set<Long>,
    viewModel: NotificationAssistantViewModel,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val label = remember(entry.sourcePackage) { appLabel(context, entry.sourcePackage) }
    val category = when (entry.category) {
        NotificationAssistantCategory.RESERVATION -> "예약"
        NotificationAssistantCategory.DEADLINE -> "마감"
        else -> "배송"
    }
    val eventLabel = entry.eventAt?.let {
        if (it.toLocalTime() == java.time.LocalTime.MIDNIGHT) it.toLocalDate().toString()
        else it.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    } ?: "날짜 확인 필요"
    AppSectionCard {
        Text("$category · $label", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(entry.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (entry.sourceText.isNotBlank()) Text(entry.sourceText, style = MaterialTheme.typography.bodyMedium)
        Text("예정 $eventLabel", style = MaterialTheme.typography.bodySmall)
        if (entry.status == NotificationAssistantStatus.PENDING || entry.status == NotificationAssistantStatus.ACCEPTED) {
            AppSecondaryButton(
                text = if (entry.eventAt == null) "날짜 설정" else "날짜 수정",
                onClick = {
                    val initial = entry.eventAt?.toLocalDate() ?: LocalDate.now()
                    DatePickerDialog(
                        context,
                        { _, year, month, day -> viewModel.updateDate(entry, LocalDate.of(year, month + 1, day)) },
                        initial.year,
                        initial.monthValue - 1,
                        initial.dayOfMonth,
                    ).show()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = entry.id !in busyIds,
            )
        }
        when (entry.status) {
            NotificationAssistantStatus.PENDING -> Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                AppPrimaryButton("추가", { viewModel.accept(entry.id) }, Modifier.weight(1f), enabled = entry.id !in busyIds)
                AppSecondaryButton("무시", { viewModel.ignore(entry.id) }, Modifier.weight(1f), enabled = entry.id !in busyIds)
            }
            NotificationAssistantStatus.ACCEPTED -> AppSecondaryButton(
                "완료",
                { viewModel.complete(entry.id) },
                Modifier.fillMaxWidth(),
                enabled = entry.id !in busyIds,
            )
        }
        AppSecondaryButton("삭제", onDelete, Modifier.fillMaxWidth(), enabled = entry.id !in busyIds)
    }
}

private fun appLabel(context: Context, packageName: String): String = runCatching {
    val info = context.packageManager.getApplicationInfo(packageName, 0)
    context.packageManager.getApplicationLabel(info).toString()
}.getOrDefault(packageName)

private fun loadSelectableApps(context: Context, preferences: NotificationAssistantPreferences): List<SelectableNotificationApp> {
    val packageManager = context.packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val launchablePackages = packageManager.queryIntentActivities(launcherIntent, 0)
        .mapNotNull { it.activityInfo?.packageName }
    val enabledPackages = preferences.enabledPackages()
    return (launchablePackages + preferences.observedPackages() + enabledPackages)
        .asSequence()
        .filter { it != context.packageName && (it != "com.android.systemui" || it in enabledPackages) }
        .distinct()
        .map { SelectableNotificationApp(it, appLabel(context, it)) }
        .sortedBy { it.label.lowercase() }
        .toList()
}
