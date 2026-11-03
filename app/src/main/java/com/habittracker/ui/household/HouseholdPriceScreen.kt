package com.habittracker.ui.household

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.habittracker.data.*
import com.habittracker.data.local.entity.*
import com.habittracker.ui.components.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.math.BigDecimal
import java.text.DecimalFormat
import java.time.LocalDate
import java.util.UUID

data class HouseholdPriceState(val loaded: Boolean = false, val rows: List<HouseholdSummary> = emptyList(), val issue: String? = null)

class HouseholdPriceViewModel(private val store: HouseholdPrices, private val saved: SavedStateHandle) : ViewModel() {
    private val stateMutable = MutableStateFlow(HouseholdPriceState())
    val state = stateMutable.asStateFlow()
    val draft = saved.getStateFlow("household.draft", "")
    val selected = saved.getStateFlow("household.selected", "")
    private val busyMutable = MutableStateFlow(false)
    val busy = busyMutable.asStateFlow()
    private val messageMutable = MutableStateFlow<String?>(null)
    val message = messageMutable.asStateFlow()
    private val errorsMutable = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors = errorsMutable.asStateFlow()
    fun dismissMessage() { messageMutable.value = null }
    private var subscription: Job? = null
    init { refresh() }
    fun refresh() {
        subscription?.cancel()
        subscription = viewModelScope.launch {
            store.observe().map { rows -> rows.map { HouseholdSummary(it, it.purchases.sortedWith(compareBy<HouseholdPurchase> { p -> p.date }.thenBy { p -> p.recordedOrder }.thenBy { p -> p.id })) } }
                .flowOn(Dispatchers.Default).catch { error ->
                    if (error is CancellationException) throw error
                    android.util.Log.e("HouseholdPrices", "phase=load failed", error)
                    stateMutable.value = HouseholdPriceState(issue = "구매 기록을 불러오지 못했습니다.")
                }.collect { stateMutable.value = HouseholdPriceState(loaded = true, rows = it) }
        }
    }
    fun select(id: String) { if (!busy.value) saved["household.selected"] = id }
    fun close() { saved["household.draft"] = ""; saved["household.original"] = ""; errorsMutable.value = emptyMap() }
    val dirty: Boolean get() = draft.value.isNotEmpty() && draft.value != saved.get<String>("household.original")
    fun set(key: String, value: String) {
        if (busy.value) return
        saved["household.draft"] = JSONObject(draft.value).put(key, value).toString()
        errorsMutable.value = errors.value - key
    }
    fun editProduct(row: HouseholdSummary? = null) {
        if (busy.value) return
        val p = row?.history?.product
        open(JSONObject().put("kind", "product").put("id", p?.id ?: UUID.randomUUID().toString())
            .put("existing", p != null).put("name", p?.name ?: "").put("unit", p?.unit ?: "개")
            .put("size", "1").put("count", p?.defaultCount?.toString() ?: "1")
            .put("date", LocalDate.now().toString()).put("amount", "").put("shop", "").put("note", ""))
    }
    fun editPurchase(row: HouseholdSummary, purchase: HouseholdPurchase? = null) {
        if (busy.value) return
        val product = row.history.product
        val previous = purchase ?: row.latest
        open(JSONObject().put("kind", "purchase").put("id", purchase?.id ?: UUID.randomUUID().toString())
            .put("existing", purchase != null).put("productId", product.id).put("name", product.name).put("unit", product.unit)
            .put("date", purchase?.date ?: LocalDate.now().toString()).put("size", "1")
            .put("count", previous?.count?.toString() ?: product.defaultCount.toString()).put("amount", purchase?.amount?.toString() ?: "")
            .put("shop", previous?.shop ?: "").put("note", purchase?.note ?: ""))
    }
    fun useRegisteredProduct(row: HouseholdSummary) {
        if (busy.value || draft.value.isEmpty()) return
        val d = JSONObject(draft.value)
        if (d.getString("kind") != "product" || d.getBoolean("existing")) return
        val original = JSONObject(requireNotNull(saved.get<String>("household.original")))
        val product = row.history.product
        val updatedFields = mutableSetOf("name", "unit")
        if (d.optString("count") == original.optString("count")) {
            d.put("count", (row.latest?.count ?: product.defaultCount).toString())
            updatedFields.add("count")
        }
        if (d.optString("shop") == original.optString("shop")) {
            d.put("shop", row.latest?.shop ?: "")
            updatedFields.add("shop")
        }
        d.put("kind", "purchase").put("productId", product.id).put("name", product.name).put("unit", product.unit)
        saved["household.selected"] = product.id
        saved["household.draft"] = d.toString()
        errorsMutable.value = errors.value - updatedFields
    }
    private fun open(value: JSONObject) {
        saved["household.draft"] = value.toString(); saved["household.original"] = value.toString(); errorsMutable.value = emptyMap()
    }
    fun save() {
        if (busy.value || draft.value.isEmpty()) return
        val d = JSONObject(draft.value)
        val product = d.getString("kind") == "product"
        val newProduct = product && !d.getBoolean("existing")
        val problems = linkedMapOf<String, String>()
        fun text(key: String) = d.optString(key, if (key == "date") LocalDate.now().toString() else "").trim()
        val count = text("count").toIntOrNull()
        if (count == null || count <= 0) problems["count"] = "1 이상의 정수로 입력해 주세요."
        if (product) {
            if (text("name").isEmpty() || text("name").length > 100) problems["name"] = "제품명을 1~100자로 입력해 주세요."
            if (text("unit").isEmpty() || text("unit").length > 30) problems["unit"] = "비교 단위를 1~30자로 입력해 주세요."
        }
        if (!product || newProduct) {
            if (runCatching { LocalDate.parse(text("date")) }.isFailure) problems["date"] = "날짜를 확인해 주세요."
            if (text("amount").toLongOrNull()?.let { it >= 0 } != true) problems["amount"] = "0 이상의 원 단위 정수를 입력해 주세요."
            if (text("shop").length > 100) problems["shop"] = "100자 이내로 입력해 주세요."
            if (text("note").length > 1000) problems["note"] = "1,000자 이내로 입력해 주세요."
        }
        errorsMutable.value = problems
        if (problems.isNotEmpty()) return
        operation {
            if (product) {
                val item = HouseholdProduct(d.getString("id"), text("name"), text("unit"), "1", requireNotNull(count))
                if (newProduct) {
                    store.register(item, HouseholdPurchase("${item.id}:first-purchase", item.id, text("date"), "1",
                        requireNotNull(count), item.unit, text("amount").toLong(), text("shop"), text("note")))
                } else store.save(item, editing = true)
                saved["household.selected"] = d.getString("id")
            } else store.save(HouseholdPurchase(d.getString("id"), d.getString("productId"), text("date"), "1", requireNotNull(count), d.getString("unit"), text("amount").toLong(), text("shop"), text("note")), editing = d.getBoolean("existing"))
            close(); messageMutable.value = "저장했습니다."
        }
    }
    fun delete(product: Boolean, id: String) = operation {
        if (product) { store.deleteProduct(id); saved["household.selected"] = "" } else store.deletePurchase(id)
        messageMutable.value = "삭제했습니다."
    }
    private fun operation(action: suspend () -> Unit) {
        if (busy.value) return
        busyMutable.value = true
        viewModelScope.launch {
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { android.util.Log.e("HouseholdPrices", "phase=write failed", error); messageMutable.value = error.message ?: "저장하지 못했습니다." }
            finally { busyMutable.value = false }
        }
    }
}

private fun money(value: BigDecimal) = DecimalFormat("#,##0.##").format(value) + "원"
private fun money(value: Long) = money(BigDecimal.valueOf(value))
private fun unitPrice(p: HouseholdPurchase) = "${p.unit}당 ${money(householdPrice(p))}"
private fun configuration(p: HouseholdPurchase) = "${p.count}${p.unit} 구매"

@Composable
fun HouseholdPriceScreen(vm: HouseholdPriceViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val raw by vm.draft.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val row = state.rows.find { it.history.product.id == selected }
    val guard = LocalAppNavigationGuard.current
    SideEffect { guard.hasUnsavedChanges = vm.dirty }
    DisposableEffect(Unit) { onDispose { guard.hasUnsavedChanges = false } }
    BackHandler(raw.isNotEmpty() || selected.isNotEmpty()) {
        if (!busy) guard.navigate { if (raw.isNotEmpty()) vm.close() else vm.select("") }
    }
    AppActionNotice(message, vm::dismissMessage)
    var deletion by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteName by rememberSaveable { mutableStateOf("") }
    var deleteProduct by rememberSaveable { mutableStateOf(false) }
    if (raw.isNotEmpty()) {
        HouseholdEditor(vm, raw, row, state.rows, busy) { guard.navigate { vm.close() } }
    } else AppScreen {
        item { AppHeroCard(title = row?.history?.product?.name ?: "생활용품 가격 기록", description = if (row == null) "내가 산 가격을 기준으로 비교하세요." else null) }
        if (!state.loaded) {
            item { if (state.issue != null) { Text(state.issue!!); AppSecondaryButton("다시 불러오기", onClick = vm::refresh) } else AppLoadingCard("구매 기록을 불러오고 있습니다.") }
        } else if (row == null) {
            item { AppPrimaryButton("제품 등록", onClick = { vm.editProduct() }) }
            if (state.rows.isEmpty()) item { AppEmptyCard("제품을 등록하고 첫 구매 가격을 남겨보세요.") }
            else item { AppTableRow(listOf("제품", "구매 가격"), header = true, trailingContent = { Text("기록") }) }
            items(state.rows, key = { it.history.product.id }) { summary ->
                val latest = summary.latest
                AppTableRow(listOf(summary.history.product.name, if (latest == null) "구매 기록 없음" else
                    "최근 ${money(householdPrice(latest))}\n최저 ${money(householdPrice(requireNotNull(summary.cheapest)))}\n${latest.unit}당"),
                    modifier = Modifier.clickable { vm.select(summary.history.product.id) }, trailingContent = {
                        TextButton(onClick = { vm.select(summary.history.product.id); vm.editPurchase(summary) }) { Text("추가") }
                    })
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppSecondaryButton("제품 목록", onClick = { vm.select("") }, modifier = Modifier.weight(1f))
                    AppPrimaryButton("구매 기록 추가", onClick = { vm.editPurchase(row) }, modifier = Modifier.weight(1f))
                }
            }
            val latest = row.latest
            val cheapest = row.cheapest
            if (latest == null || cheapest == null) item { AppEmptyCard("첫 구매를 기록하면 최저가와 추이를 확인할 수 있습니다.") }
            else {
                item {
                    AppSectionCard {
                        Text("내 최저가", style = MaterialTheme.typography.labelLarge)
                        Text(unitPrice(cheapest), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                        Text(listOf(cheapest.date, cheapest.shop).filter { it.isNotBlank() }.joinToString(" · "))
                        Text("${configuration(cheapest)} · 총 ${money(cheapest.amount)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        Text("최근 ${unitPrice(latest)} · 총 ${money(latest.amount)}")
                        val difference = householdPrice(latest) - householdPrice(cheapest)
                        Text(if (difference.signum() == 0) "최근 구매가 내 최저가입니다." else "내 최저가보다 단위당 ${money(difference)} 높아요.")
                        row.ordered.dropLast(1).lastOrNull()?.let { previous ->
                            val change = householdPrice(latest) - householdPrice(previous)
                            Text(when (change.signum()) {
                                0 -> "직전 구매와 단위가격이 같아요."
                                1 -> "직전 구매보다 단위당 ${money(change)} 높아요."
                                else -> "직전 구매보다 단위당 ${money(change.abs())} 낮아요."
                            }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item { AppSectionHeader("구매 가격 비교", subtitle = "${row.ordered.size}건 · 총 구매금액 ÷ 구입 개수") }
            if (row.ordered.isNotEmpty()) item { AppTableRow(listOf("구매 내역", "${row.history.product.unit}당 가격", "최저가 대비"), header = true) }
            items(row.ordered.asReversed(), key = { it.id }) { p ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val price = householdPrice(p)
                    val difference = price - householdPrice(requireNotNull(row.cheapest))
                    AppTableRow(listOf("${p.date}\n${p.count}${p.unit} · ${money(p.amount)}${p.shop.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()}",
                        money(price), if (difference.signum() == 0) "최저가" else "+${money(difference)}"))
                    if (p.note.isNotBlank()) Text(p.note)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(enabled = !busy, onClick = { vm.editPurchase(row, p) }) { Text("수정") }
                        TextButton(enabled = !busy, onClick = { deleteProduct = false; deleteName = "${p.date} ${money(p.amount)} 구매 기록"; deletion = p.id }) { Text("삭제") }
                    }
                    HorizontalDivider()
                }
            }
            if (row.ordered.isNotEmpty()) item { PriceChart(row) }
            item {
                AppSecondaryButton("제품 정보 수정", onClick = { vm.editProduct(row) })
                TextButton(enabled = !busy, onClick = { deleteProduct = true; deleteName = "${row.history.product.name} 및 구매 기록 ${row.ordered.size}건"; deletion = row.history.product.id }) { Text("제품 삭제") }
            }
        }
    }
    if (deletion != null) AppConfirmDialog(title = "삭제할까요?", message = "$deleteName\n삭제한 기록은 복구할 수 없습니다.", confirmText = "삭제",
        onDismiss = { deletion = null }, onConfirm = { vm.delete(deleteProduct, requireNotNull(deletion)); deletion = null })
}

@Composable
private fun HouseholdEditor(vm: HouseholdPriceViewModel, raw: String, row: HouseholdSummary?, products: List<HouseholdSummary>, busy: Boolean, cancel: () -> Unit) {
    val draft = remember(raw) { JSONObject(raw) }
    val errors by vm.errors.collectAsStateWithLifecycle()
    val product = draft.getString("kind") == "product"
    val newProduct = product && !draft.getBoolean("existing")
    val recordsPurchase = !product || newProduct
    val unit = draft.getString("unit")
    val context = LocalContext.current
    var nameFocused by remember { mutableStateOf(false) }
    val query = draft.optString("name").trim()
    val suggestions = remember(products, query, newProduct) {
        if (newProduct && query.isNotEmpty()) products.filter { it.history.product.name.contains(query, ignoreCase = true) }
        else emptyList()
    }
    @Composable fun field(key: String, label: String, numeric: Boolean = false, help: String? = null, modifier: Modifier = Modifier) {
        AppTextField(draft.optString(key), { vm.set(key, it) }, label, modifier = modifier, enabled = !busy, singleLine = key != "note",
            keyboardOptions = KeyboardOptions(keyboardType = if (!numeric) KeyboardType.Text else if (key == "size") KeyboardType.Decimal else KeyboardType.Number),
            isError = errors.containsKey(key), supportingText = errors[key] ?: help)
    }
    AppScreen(bottomBar = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppSecondaryButton("취소", onClick = cancel, enabled = !busy, modifier = Modifier.weight(1f))
            AppSaveButton(onClick = vm::save, enabled = !busy, text = if (busy) "저장 중…" else "저장", modifier = Modifier.weight(1f))
        }
    }) {
        item { AppHeroCard(title = if (newProduct) "제품·첫 구매 등록" else if (product) "제품 정보" else "구매 기록", description = if (product) null else draft.getString("name")) }
        if (product) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    field("name", "제품명", help = if (newProduct) "등록한 제품명 검색 또는 새 제품명 입력" else "예: 보습 샴푸 500ml · 3겹 화장지 30롤",
                        modifier = Modifier.onFocusChanged { nameFocused = it.isFocused })
                    if (nameFocused && suggestions.isNotEmpty()) {
                        Text("등록된 제품", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                            items(suggestions, key = { it.history.product.id }) { suggestion ->
                                ListItem(
                                    headlineContent = { Text(suggestion.history.product.name) },
                                    supportingContent = { Text("${suggestion.history.product.unit} 단위 · 구매 기록 추가") },
                                    modifier = Modifier.clickable(enabled = !busy, onClickLabel = "이 제품에 구매 기록 추가") {
                                        vm.useRegisteredProduct(suggestion)
                                    },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
        item { field("count", if (product && !newProduct) "기본 구입 개수" else "구입 개수($unit)", numeric = true) }
        if (recordsPurchase) {
            item { field("amount", "총 구매금액(원)", numeric = true, help = "입력한 개수 전체의 결제 금액") }
            item {
                val count = draft.optString("count").toIntOrNull()
                val amount = draft.optString("amount").toLongOrNull()
                if (count != null && count > 0 && amount != null && amount >= 0) {
                    val preview = HouseholdPurchase("", "", "", "1", count, unit, amount, "", "")
                    val price = householdPrice(preview)
                    Text("${money(amount)} ÷ ${count}${unit}", style = MaterialTheme.typography.bodySmall)
                    Text(unitPrice(preview), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    val minimum = row?.takeUnless { newProduct }?.ordered?.filter { it.id != draft.getString("id") }?.minByOrNull { householdPrice(it) }
                    if (minimum != null) {
                        val delta = price - householdPrice(minimum)
                        Text(if (delta.signum() <= 0) "기록하면 내 최저가입니다." else "내 최저가보다 단위당 ${money(delta)} 높아요.")
                    }
                }
            }
        }
        if (product) item { field("unit", "비교 단위", help = "예: 개 · 팩 · 묶음") }
        if (recordsPurchase) {
            item {
                val date = LocalDate.parse(draft.optString("date", LocalDate.now().toString()))
                AppSecondaryButton("구매일 ${date}", enabled = !busy, onClick = {
                    DatePickerDialog(context, { _, y, m, day -> vm.set("date", LocalDate.of(y, m + 1, day).toString()) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
                })
            }
            item { field("shop", "판매처(선택)") }
            item { field("note", "메모(선택)") }
        }
    }
}

@Composable
private fun PriceChart(row: HouseholdSummary) {
    val purchases = row.ordered
    var selected by rememberSaveable(row.history.product.id) { mutableStateOf("") }
    val index = purchases.indexOfFirst { it.id == selected }.takeIf { it >= 0 } ?: purchases.lastIndex
    val current = purchases[index]
    val prices = remember(purchases) { purchases.map { householdPrice(it).toDouble() } }
    val dates = remember(purchases) { purchases.map { LocalDate.parse(it.date).toEpochDay() } }
    val low = prices.minOrNull()!!; val high = prices.maxOrNull()!!
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppSectionHeader("가격 추이", subtitle = "${current.unit}당 · 실제 구매 시점")
        Text("${money(BigDecimal.valueOf(low))} ~ ${money(BigDecimal.valueOf(high))}", style = MaterialTheme.typography.bodySmall)
        Canvas(Modifier.fillMaxWidth().height(170.dp)
            .semantics { contentDescription = "구매 ${purchases.size}건의 단위가격 추이. 아래 이전·다음 기록으로 확인할 수 있습니다." }
            .pointerInput(dates, prices) {
                detectTapGestures { point ->
                    val width = (size.width - 24.dp.toPx()).coerceAtLeast(1f)
                    val range = (dates.last() - dates.first()).coerceAtLeast(1).toDouble()
                    val nearest = dates.indices.minByOrNull { kotlin.math.abs((if (dates.last() == dates.first()) width.toDouble() / 2 else (dates[it] - dates.first()) / range * width) + 12.dp.toPx() - point.x) }
                    if (nearest != null) selected = purchases[nearest].id
                }
            }) {
            val inset = 12.dp.toPx(); val width = size.width - inset * 2; val height = size.height - inset * 2
            val range = (dates.last() - dates.first()).coerceAtLeast(1).toDouble()
            val spread = (high - low).takeIf { it > 0 } ?: 1.0
            val points = prices.indices.map { i -> Offset(inset + if (dates.last() == dates.first()) width / 2 else ((dates[i] - dates.first()) / range * width).toFloat(),
                inset + height - ((prices[i] - low) / spread * height).toFloat()) }
            repeat(3) { n -> val y = inset + height * n / 2; drawLine(grid, Offset(inset, y), Offset(size.width - inset, y)) }
            points.zipWithNext().forEach { (a, b) -> drawLine(color, a, b, strokeWidth = 2.dp.toPx()) }
            points.forEachIndexed { i, p -> drawCircle(color, if (i == index) 6.dp.toPx() else 3.dp.toPx(), p) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(purchases.first().date); Text(purchases.last().date) }
        Text("${current.date} · ${unitPrice(current)}", style = MaterialTheme.typography.titleSmall)
        Text("${configuration(current)} · 총 ${money(current.amount)}${current.shop.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { selected = purchases[index - 1].id }, enabled = index > 0) { Text("이전 기록") }
            TextButton(onClick = { selected = purchases[index + 1].id }, enabled = index < purchases.lastIndex) { Text("다음 기록") }
        }
    }
}
