package com.habittracker.ui.notification

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.habittracker.data.PersonalAlerts
import com.habittracker.data.objects
import com.habittracker.data.billPaid
import com.habittracker.data.recordBillPayment
import com.habittracker.ui.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler

class PersonalAlertsViewModel(private val store: PersonalAlerts, private val saved: SavedStateHandle) : ViewModel() {
    val snapshot = store.snapshot
    val message = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val busy = kotlinx.coroutines.flow.MutableStateFlow(false)
    val loadIssue = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val errors = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())
    val selected = saved.getStateFlow("selected", "")
    fun select(id: String) { if (!busy.value) { clearDraft(); saved["selected"] = id } }
    fun clearDraft() { listOf("keyword", "accountName", "balance", "expenseName", "expenseAmount", "billId", "billName", "billAmount", "day", "advance").forEach { set(it, "") }; saved["draftBaseline"] = "" }
    private val draftKeys = listOf("keyword", "accountName", "balance", "expenseName", "expenseAmount", "billName", "billAmount", "day", "advance")
    private fun draftValues() = JSONObject().also { json -> draftKeys.forEach { json.put(it, text(it)) } }.toString()
    fun captureDraft() { saved["draftBaseline"] = draftValues() }
    fun hasChanges() = draftValues() != (saved.get<String>("draftBaseline").takeUnless { it.isNullOrEmpty() }
        ?: JSONObject().also { json -> draftKeys.forEach { json.put(it, "") } }.toString())
    fun field(key: String) = saved.getStateFlow(key, "")
    fun text(key: String): String = saved[key] ?: ""
    fun set(key: String, value: String) { saved[key] = value; errors.value = errors.value - key }
    init { refresh() }
    fun refresh() = operation {
        loadIssue.value = null
        val data = store.load()
        if (selected.value.isEmpty()) data.getJSONArray("accounts").objects().firstOrNull()?.let { saved["selected"] = it.getString("id") }
        store.schedule()
        store.chargeDueBills()
    }
    private fun operation(action: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                android.util.Log.e("PersonalAlerts", "phase=user-operation failed", error)
                message.value = error.message ?: "저장 정보를 확인해 주세요."
                errors.value = draftKeys.filter { error.message?.startsWith(label(it)) == true }
                    .associateWith { error.message.orEmpty() }
                if (snapshot.value == null) loadIssue.value = "저장 정보를 불러오지 못했습니다."
            } finally { busy.value = false }
        }
    }
    private fun name(key: String) = text(key).trim().also { require(it.isNotEmpty() && it.length <= 100) { "${label(key)}: 1~100자로 입력해 주세요." } }
    private fun amount(key: String) = text(key).toLongOrNull()?.also { require(key == "balance" || it >= 0) { "${label(key)}: 0 이상으로 입력해 주세요." } }
        ?: throw IllegalArgumentException("${label(key)}: 올바른 정수를 입력해 주세요.")
    fun save(kind: String) = operation {
        val word = if (kind == "keyword") name("keyword") else null
        if (word != null) require(word.split(',').any { it.isNotBlank() }) { "${label("keyword")}: 쉼표 외에 검색할 단어를 입력해 주세요." }
        val accountName = if (kind == "account") name("accountName") else null
        val money = when (kind) { "balance" -> amount("balance"); "expense" -> amount("expenseAmount"); "subscription" -> amount("billAmount"); else -> 0L }
        val title = when (kind) { "expense" -> name("expenseName"); "subscription" -> name("billName"); else -> "" }
        val day = if (kind == "subscription") text("day").toIntOrNull()?.takeIf { it in 1..31 }
            ?: throw IllegalArgumentException("${label("day")}: 1~31일로 입력해 주세요.") else 1
        val advance = if (kind == "subscription") text("advance").toIntOrNull()?.takeIf { it in 0..31 }
            ?: throw IllegalArgumentException("${label("advance")}: 0~31일 전으로 입력해 주세요.") else 0
        val id = UUID.randomUUID().toString()
        store.change { data ->
            val accounts = data.getJSONArray("accounts")
            val account = accounts.objects().find { it.getString("id") == selected.value }
            when (kind) {
                "keyword" -> {
                    val words = data.getJSONArray("keywords")
                    require(words.objects().none { it.getString("word").equals(word, true) }) { "이미 등록한 키워드입니다." }
                    words.put(JSONObject().put("id", id).put("word", word))
                }
                "account" -> {
                    require(accounts.objects().none { it.getString("name") == accountName }) { "같은 이름의 계정이 있습니다." }
                    accounts.put(JSONObject().put("id", id).put("name", accountName).put("balance", 0L))
                }
                else -> {
                    requireNotNull(account) { "계정을 먼저 선택해 주세요." }
                    when (kind) {
                        "balance" -> account.put("balance", money).put("revision", account.optLong("revision") + 1)
                        "expense" -> {
                            require(money > 0 && money <= account.getLong("balance")) { "${label("expenseAmount")}: 0보다 크고 입력 잔액 이하여야 합니다." }
                            account.put("balance", account.getLong("balance") - money)
                            data.getJSONArray("expenses").put(JSONObject().put("id", id).put("account", selected.value).put("name", title)
                                .put("amount", money).put("date", LocalDate.now().toString()).put("revision", account.optLong("revision")))
                        }
                        "subscription" -> {
                            require(money > 0) { "${label("billAmount")}: 0보다 커야 합니다." }
                            val editId = text("billId")
                            val bills = data.getJSONArray("subscriptions")
                            val bill = bills.objects().find { it.getString("id") == editId && it.getString("account") == selected.value }
                                ?: JSONObject().put("id", id).put("account", selected.value)
                                    .put("autoChargeFrom", LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString()).also { bills.put(it) }
                            bill.put("name", title).put("amount", money).put("day", day).put("advance", advance)
                        }
                    }
                }
            }
        }
        if (kind == "account") saved["selected"] = id
        val fields = when (kind) { "keyword" -> listOf("keyword"); "account" -> listOf("accountName"); "balance" -> listOf("balance");
            "expense" -> listOf("expenseName", "expenseAmount"); else -> listOf("billId", "billName", "billAmount", "day", "advance") }
        fields.forEach { set(it, "") }
        captureDraft()
        store.schedule()
        store.chargeDueBills()
        message.value = "저장했습니다."
    }
    fun editBill(bill: JSONObject) {
        set("billId", bill.getString("id")); set("billName", bill.getString("name")); set("billAmount", bill.getLong("amount").toString())
        set("day", bill.getInt("day").toString()); set("advance", bill.getInt("advance").toString())
        captureDraft()
    }
    fun payBill(id: String) = operation {
        store.change { data ->
            val bill = data.getJSONArray("subscriptions").objects().first { it.getString("id") == id && it.getString("account") == selected.value }
            val today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
            val month = java.time.YearMonth.from(today)
            val date = month.atDay(minOf(bill.getInt("day"), month.lengthOfMonth()))
            recordBillPayment(data, bill, date, today)
        }
        message.value = "결제를 기록했습니다."
    }
    fun delete(collection: String, id: String) = operation {
        store.change { data ->
            val entries = data.getJSONArray(collection).objects()
            val target = entries.find { it.getString("id") == id } ?: error("삭제 대상이 없습니다.")
            if (collection == "expenses") {
                val account = data.getJSONArray("accounts").objects().first { it.getString("id") == target.getString("account") }
                if (account.optLong("revision") == target.optLong("revision")) account.put("balance", Math.addExact(account.getLong("balance"), target.getLong("amount")))
                // 사용 내역을 취소해도 해당 월의 자동 차감이 다시 실행되지 않도록 처리 월은 유지한다.
            }
            data.put(collection, JSONArray(entries.filterNot { it.getString("id") == id }))
            if (collection == "accounts") listOf("expenses", "subscriptions").forEach { key ->
                data.put(key, JSONArray(data.getJSONArray(key).objects().filterNot { it.getString("account") == id }))
            }
        }
        message.value = "삭제했습니다."
        store.schedule()
    }
}

private fun label(key: String) = when (key) {
    "keyword" -> "제목 검색 키워드"; "accountName" -> "계정 이름 또는 이메일"; "balance" -> "현재 잔액(원)";
    "expenseName" -> "사용 내용"; "expenseAmount" -> "사용 금액(원)"; "billName" -> "정기결제 이름";
    "billAmount" -> "월 결제 금액(원)"; "day" -> "매월 결제일(1~31)"; else -> "결제 며칠 전 알림(0~31)"
}

private fun alertMoney(value: Long): String = java.text.NumberFormat.getIntegerInstance(java.util.Locale.KOREA).format(value) + "원"

@Composable
fun PersonalAlertsScreen(viewModel: PersonalAlertsViewModel, google: Boolean) {
    val raw by viewModel.snapshot.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val loadIssue by viewModel.loadIssue.collectAsStateWithLifecycle()
    val errors by viewModel.errors.collectAsStateWithLifecycle()
    val data = remember(raw) { raw?.let(::JSONObject) }
    var deleting by remember { mutableStateOf<Pair<String, JSONObject>?>(null) }
    var editor by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(editor) { if (editor.isNotEmpty()) viewModel.message.value = null }
    var paying by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(message) { if (message == "저장했습니다.") editor = "" }
    AppActionNotice(message) { viewModel.message.value = null }
    val context = LocalContext.current
    var permissionRevision by remember { mutableStateOf(0) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                permissionRevision++
                if (google) viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.message.value = if (granted) "알림을 허용했습니다." else "알림 권한이 없어 알림을 표시할 수 없습니다."
    }
    val fields = if (google) listOf("accountName", "balance", "expenseName", "expenseAmount", "billName", "billAmount", "day", "advance") else listOf("keyword")
    fields.forEach { key -> viewModel.field(key).collectAsStateWithLifecycle().value }
    val dirty = viewModel.hasChanges()
    val guard = LocalAppNavigationGuard.current
    SideEffect { guard.hasUnsavedChanges = dirty }
    DisposableEffect(Unit) { onDispose { guard.hasUnsavedChanges = false } }
    BackHandler(enabled = editor.isNotEmpty()) { guard.navigate { viewModel.clearDraft(); editor = "" } }
    @Composable fun input(key: String, modifier: Modifier = Modifier) {
        val value by viewModel.field(key).collectAsStateWithLifecycle()
        AppTextField(value = value, onValueChange = { viewModel.set(key, it) }, label = label(key), enabled = !busy, modifier = modifier,
            isError = errors.containsKey(key),
            supportingText = errors[key],
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = if (key in listOf("balance", "expenseAmount", "billAmount", "day", "advance")) androidx.compose.ui.text.input.KeyboardType.Number else androidx.compose.ui.text.input.KeyboardType.Text))
    }
    deleting?.let { (collection, target) -> AppConfirmDialog(title = "삭제 확인", message = "${target.optString("name", target.optString("word"))} 삭제${if (collection == "accounts") " 및 연결된 사용·정기결제 내역 삭제" else if (collection == "expenses") {
        val revision = data?.getJSONArray("accounts")?.objects()?.find { it.getString("id") == target.getString("account") }?.optLong("revision")
        if (revision == target.optLong("revision")) " 후 잔액 복원" else "(잔액 보정 전 내역이므로 현재 잔액 유지)"
    } else ""}할까요?${if (collection == "subscriptions") "\n이후 자동 차감은 중단하며 기존 사용 내역은 유지합니다."
        else if (collection == "expenses" && target.has("bill")) "\n해당 결제 월은 다시 자동 차감하지 않습니다." else ""}",
        confirmText = "삭제", onConfirm = { viewModel.delete(collection, target.getString("id")); deleting = null }, onDismiss = { deleting = null }) }
    paying?.let { bill -> AppConfirmDialog(title = "결제 기록", message = "${bill.getString("name")} ${bill.getLong("amount")}원을 사용 내역에 기록하고 잔액에서 차감합니다.",
        confirmText = "결제 완료", onConfirm = { viewModel.payBill(bill.getString("id")); paying = null }, onDismiss = { paying = null }) }
    if (editor.isNotEmpty()) {
        AppScreen(bottomBar = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppSecondaryButton(text = "취소", enabled = !busy, modifier = Modifier.weight(1f), onClick = { guard.navigate { viewModel.clearDraft(); editor = "" } })
                AppPrimaryButton(text = if (busy) "저장 중" else "저장", enabled = !busy, modifier = Modifier.weight(1f), onClick = { viewModel.save(editor) })
            }
        }) {
            item { AppHeroCard(title = when (editor) { "account" -> "계정 등록"; "balance" -> "잔액 수정"; "expense" -> "사용 기록"; else -> "정기결제" }, description = "") }
            item {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (editor) {
                        "account" -> input("accountName")
                        "balance" -> input("balance")
                        "expense" -> { input("expenseName"); input("expenseAmount") }
                        else -> {
                            input("billName"); input("billAmount"); input("day"); input("advance")
                            AppSupportText("매월 반복 · 없는 날짜는 말일 · 0일 전은 당일 알림")
                        }
                    }
                }
            }
        }
        return
    }
    val channelId = PersonalAlerts.channelId(google)
    val channelBlocked = remember(permissionRevision, google, raw) {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        (manager.getNotificationChannel(channelId) ?: manager.getNotificationChannel("personal-alerts"))?.importance == android.app.NotificationManager.IMPORTANCE_NONE
    }
    val upcoming = remember(raw, selected) {
        data?.let { com.habittracker.data.upcomingBills(it, selected, LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))) }.orEmpty()
    }
    AppScreen {
        item { AppHeroCard(title = if (google) "구글 잔액" else "뽐뿌 알림", description = "") }
        if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() || channelBlocked) item {
            AppSupportText(if (channelBlocked) "이 기능의 알림이 차단되어 있습니다." else "알림을 허용해 주세요.")
            AppSecondaryButton(text = "알림 권한 설정", onClick = {
                if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(android.content.Intent(if (channelBlocked) android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS else android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, channelId))
            })
        }
        if (data == null) item {
            if (loadIssue == null) AppLoadingCard("불러오는 중") else AppEmptyCard(loadIssue!!)
            if (loadIssue != null) AppSecondaryButton(text = "다시 불러오기", onClick = { viewModel.refresh() })
        }
        else if (!google) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    input("keyword", Modifier.weight(1f))
                    AppPrimaryButton(text = "등록", enabled = !busy, onClick = { viewModel.save("keyword") })
                }
            }
            item { AppSupportText("쉼표로 구분한 단어를 순서 없이 모두 포함 · 예: 감귤, 고당도, 5KG")
                AppSupportText("제목 검색 · 영문 대소문자 구분 없음 · 약 15분 간격 · 절전 시 지연 가능")
                AppSupportText(data.optString("ppomppuError").ifEmpty { "최근 확인: ${data.optString("ppomppuCheckedAt", "아직 확인 전")}" }) }
            if (data.getJSONArray("keywords").length() == 0) item { AppEmptyCard("알림 받을 키워드를 등록해 주세요.") }
            else item { AppTableRow(listOf("등록 키워드"), header = true, trailingContent = { Text("관리") }) }
            data.getJSONArray("keywords").objects().forEach { keyword -> item(key = keyword.getString("id")) {
                AppTableRow(listOf(keyword.getString("word")), trailingContent = {
                    TextButton(enabled = !busy, onClick = { deleting = "keywords" to keyword }) { Text("삭제") }
                })
            } }
        } else {
            if (data.getJSONArray("accounts").length() == 0) item { AppEmptyCard("관리할 구글 계정을 등록해 주세요.") }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("계정", style = MaterialTheme.typography.titleMedium)
                    AppSecondaryButton(text = "계정 추가", enabled = !busy, compact = true, onClick = { editor = "account" })
                }
            }
            if (data.getJSONArray("accounts").length() > 0) item { AppTableRow(listOf("계정", "잔액"), header = true, trailingContent = { Text("선택") }) }
            data.getJSONArray("accounts").objects().forEach { account -> item(key = account.getString("id")) {
                AppTableRow(listOf(account.getString("name"), alertMoney(account.getLong("balance"))), trailingContent = {
                    if (account.getString("id") == selected) Text("선택됨", color = MaterialTheme.colorScheme.primary)
                    else TextButton(enabled = !busy, onClick = { guard.navigate { viewModel.select(account.getString("id")) } }) { Text("조회") }
                })
            } }
            val account = data.getJSONArray("accounts").objects().find { it.getString("id") == selected }
            if (account != null) {
                item {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppSectionHeader(title = account.getString("name"))
                        Text(alertMoney(account.getLong("balance")), modifier = Modifier.align(Alignment.End), style = MaterialTheme.typography.headlineMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AppSecondaryButton(text = "잔액 수정", enabled = !busy, modifier = Modifier.weight(1f), onClick = { viewModel.set("balance", account.getLong("balance").toString()); viewModel.captureDraft(); editor = "balance" })
                            AppPrimaryButton(text = "사용 기록", enabled = !busy, modifier = Modifier.weight(1f), onClick = { editor = "expense" })
                        }
                    }
                }
                upcoming.firstOrNull()?.let { (bill, date) -> item {
                    AppSectionCard {
                        AppTableRow(listOf("다음 결제일", date.toString()))
                        AppTableRow(listOf(bill.getString("name"), alertMoney(bill.getLong("amount"))))
                        val dueTotal = upcoming.filter { !it.second.isAfter(date) }.fold(0L) { sum, entry -> Math.addExact(sum, entry.first.getLong("amount")) }
                        AppTableRow(listOf("예정 합계", alertMoney(dueTotal)))
                        if (dueTotal > account.getLong("balance")) Text("${alertMoney(dueTotal - account.getLong("balance"))} 부족", modifier = Modifier.align(Alignment.End), color = MaterialTheme.colorScheme.error)
                    }
                } }
                item {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("정기결제", style = MaterialTheme.typography.titleMedium)
                        AppSecondaryButton(text = "추가", enabled = !busy, compact = true, onClick = { editor = "subscription" })
                    }
                    AppSupportText("삭제 전까지 매월 결제일에 등록 잔액에서 자동 차감합니다. 잔액이 부족하면 음수로 표시됩니다.")
                    AppTableRow(listOf("결제명", "금액", "일정"), header = true)
                    }
                }
                if (data.getJSONArray("subscriptions").objects().none { it.getString("account") == selected }) item { AppEmptyCard("등록된 정기결제가 없습니다.") }
                data.getJSONArray("subscriptions").objects().filter { it.getString("account") == selected }.forEach { bill -> item(key = bill.getString("id")) {
                    AppTableRow(listOf(bill.getString("name"), alertMoney(bill.getLong("amount")), "매월 ${bill.getInt("day")}일\n${bill.getInt("advance")}일 전 알림"))
                    val paid = billPaid(bill, java.time.YearMonth.now(java.time.ZoneId.of("Asia/Seoul")))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        if (paid) Text("이번 달 처리됨", color = MaterialTheme.colorScheme.primary)
                        else TextButton(enabled = !busy, onClick = { paying = bill }) { Text("결제 기록") }
                        TextButton(enabled = !busy, onClick = { viewModel.editBill(bill); editor = "subscription" }) { Text("수정") }
                        TextButton(enabled = !busy, onClick = { deleting = "subscriptions" to bill }) { Text("삭제") }
                    }
                } }
                item { AppSectionHeader(title = "사용 내역"); AppTableRow(listOf("날짜·내용", "금액"), header = true, trailingContent = { Text("관리") }) }
                if (data.getJSONArray("expenses").objects().none { it.getString("account") == selected }) item { AppEmptyCard("사용 내역이 없습니다.") }
                data.getJSONArray("expenses").objects().filter { it.getString("account") == selected }.asReversed().forEach { expense -> item(key = expense.getString("id")) {
                    AppTableRow(listOf("${expense.getString("date")}\n${expense.getString("name")}", alertMoney(expense.getLong("amount"))), trailingContent = {
                        TextButton(enabled = !busy, onClick = { deleting = "expenses" to expense }) { Text("취소") }
                    })
                } }
                item { AppSecondaryButton(text = "선택 계정 삭제", enabled = !busy, onClick = { deleting = "accounts" to account }) }
            }
        }
    }
}
