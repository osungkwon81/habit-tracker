package com.habittracker.ui.entry

import android.app.DatePickerDialog
import android.os.Bundle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import com.habittracker.ui.components.LocalAppNavigationGuard
import com.habittracker.ui.components.AppLoadingCard
import com.habittracker.ui.components.AppSecondaryButton
import com.habittracker.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.habittracker.data.local.ValueType
import com.habittracker.ui.components.AppActionNotice
import com.habittracker.ui.components.AppHeroCard
import com.habittracker.ui.components.AppPrimaryButton
import com.habittracker.ui.components.AppSaveButton
import com.habittracker.ui.components.AppScreen
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppSectionHeader
import com.habittracker.ui.components.AppSelectableChip
import com.habittracker.ui.components.AppStatusText
import com.habittracker.ui.components.AppTextField
import java.time.LocalDate

@Composable
fun DailyEntryScreen(
    viewModel: DailyEntryViewModel,
    initialDate: String,
    onOpenAdmin: (() -> Unit)? = null,
    onOpenStats: (() -> Unit)? = null,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val navigationGuard = LocalAppNavigationGuard.current
    LaunchedEffect(initialDate) { viewModel.initializeRecord(initialDate) }
    AppActionNotice(uiState.statusMessage, viewModel::clearStatusMessage)
    if (!uiState.isLoaded) {
        AppScreen { item { AppLoadingCard("기록을 불러오고 있습니다.") } }
        return
    }
    // 서버/DB에서 온 uiState와 사용자가 편집 중인 임시 입력값을 분리해 저장 전 변경도 즉시 화면에 보인다.
    var dateInput by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(uiState.selectedDate.toString())) }
    var memo by rememberSaveable(uiState.selectedDate, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(uiState.memo)) }
    var isHoliday by rememberSaveable(uiState.selectedDate) { mutableStateOf(uiState.isHoliday) }
    var expanded by remember(uiState.selectedDate, uiState.taskItems) { mutableStateOf(false) }
    var selectedCategory by remember(uiState.selectedDate, uiState.taskItems) { mutableStateOf("전체") }
    // 날짜별 초안을 저장해 화면 재생성 중에도 편집 내용을 유지한다.
    var editableItems by rememberSaveable(uiState.selectedDate, stateSaver = TaskItemsSaver) {
        mutableStateOf(uiState.taskItems.map(TaskItemEditorState.Companion::from))
    }
    val visibleItemIds = remember(uiState.selectedDate, uiState.taskItems) {
        mutableStateOf(uiState.taskItems.map(TaskItemInputState::taskItemMasterId).toMutableSet())
    }

    val hasChanges = memo.text.trim() != uiState.memo || isHoliday != uiState.isHoliday ||
        editableItems.map { it.toInputState().comparisonValue() } != uiState.taskItems.map { it.comparisonValue() }
    SideEffect { navigationGuard.hasUnsavedChanges = hasChanges }
    DisposableEffect(navigationGuard) {
        onDispose { navigationGuard.hasUnsavedChanges = false }
    }
    val saveRecord: () -> Unit = {
        viewModel.saveDailyRecord(
            recordDate = uiState.selectedDate,
            memo = memo.text,
            isHoliday = isHoliday,
            items = editableItems.map(TaskItemEditorState::toInputState),
        )
    }

    val categories = remember(uiState.taskItems) { listOf("전체") + uiState.taskItems.map(TaskItemInputState::category).distinct() }
    val hiddenItems = editableItems.filterNot { visibleItemIds.value.contains(it.taskItemMasterId) }
    val selectedHiddenLabel = hiddenItems.firstOrNull()?.name ?: "추가할 항목 선택"
    val filteredVisibleItems = editableItems.filter {
        visibleItemIds.value.contains(it.taskItemMasterId) && (selectedCategory == "전체" || it.category == selectedCategory)
    }
    val openDatePicker = {
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val pickedDate = LocalDate.of(year, month + 1, dayOfMonth)
                if (pickedDate != uiState.selectedDate) navigationGuard.navigate {
                    dateInput = TextFieldValue(pickedDate.toString())
                    viewModel.loadRecord(pickedDate.toString())
                }
            },
            uiState.selectedDate.year,
            uiState.selectedDate.monthValue - 1,
            uiState.selectedDate.dayOfMonth,
        ).show()
    }

    AppScreen(bottomBar = {
        AppSaveButton(
            text = if (uiState.isSaving) "저장 중…" else "기록 저장",
            onClick = saveRecord,
            enabled = !uiState.isSaving && dateInput.text == uiState.selectedDate.toString(),
            modifier = Modifier.fillMaxWidth(),
        )
    }) {
        item {
            AppHeroCard(
                title = "일일 기록",
                description = "날짜별 습관과 운동 기록을 입력합니다.",
                iconRes = R.drawable.ic_category_record,
                eyebrow = "HABIT · DAILY",
                action = {
                    onOpenAdmin?.let { openAdmin ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            onOpenStats?.let { openStats ->
                                AppSecondaryButton(
                                    text = "통계",
                                    onClick = { navigationGuard.navigate(openStats) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        AppSecondaryButton(
                            text = "항목 관리",
                            onClick = { navigationGuard.navigate(openAdmin) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
            )
        }
        item {
            AppSectionCard {
                AppTextField(
                    value = dateInput.text,
                    onValueChange = { },
                    label = "기록 날짜",
                    readOnly = true,
                    singleLine = true,
                    trailingOverlay = {
                        Box(modifier = Modifier.matchParentSize().clickable(onClick = openDatePicker))
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(checked = isHoliday, onCheckedChange = { isHoliday = it })
                    Text(text = "휴일", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppSectionHeader(title = "항목 필터")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    items(categories, key = { it }) { category ->
                        AppSelectableChip(label = category, selected = selectedCategory == category, onClick = { selectedCategory = category })
                    }
                }
            }
        }
        item {
            AppSectionCard {
                AppTextField(
                    value = memo.text,
                    onValueChange = { memo = TextFieldValue(it) },
                    label = "메모",
                    minLines = 3,
                )
            }
        }
        if (hiddenItems.isNotEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth()) {
                    AppTextField(
                        value = selectedHiddenLabel,
                        onValueChange = {},
                        label = "추가할 항목",
                        readOnly = true,
                        singleLine = true,
                        modifier = Modifier.clickable { expanded = true },
                    )
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        hiddenItems
                            .filter { selectedCategory == "전체" || it.category == selectedCategory }
                            .forEach { hiddenItem ->
                                DropdownMenuItem(
                                    text = { Text(hiddenItem.name) },
                                    onClick = {
                                        visibleItemIds.value = visibleItemIds.value.toMutableSet().apply { add(hiddenItem.taskItemMasterId) }
                                        expanded = false
                                    },
                                )
                            }
                    }
                }
            }
        }
        items(filteredVisibleItems, key = { it.taskItemMasterId }) { item ->
            TaskInputCard(
                item = item,
                onItemChanged = { updated ->
                    val index = editableItems.indexOfFirst { it.taskItemMasterId == updated.taskItemMasterId }
                    if (index >= 0) editableItems = editableItems.toMutableList().also { it[index] = updated }
                },
            )
        }
        item {
            uiState.statusMessage?.let { message ->
                AppStatusText(message)
            }
        }
    }
}

@Composable
private fun TaskInputCard(item: TaskItemEditorState, onItemChanged: (TaskItemEditorState) -> Unit) {
    var isExpanded by remember(item.taskItemMasterId) { mutableStateOf(item.hasExistingValue) }
    AppSectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                    .clickable { isExpanded = !isExpanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = item.category,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = if (isExpanded) "접기" else "펼치기",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (isExpanded) {
                when (item.valueType) {
                    ValueType.NUMBER -> AppTextField(
                        value = item.numberValue.text,
                        onValueChange = { input -> onItemChanged(item.copy(numberValue = TextFieldValue(input), checked = input.isNotBlank())) },
                        label = if (item.unit == null) "수치" else "수치 (${item.unit})",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = item.numberValue.text.isNotBlank() && item.numberValue.text.toDoubleOrNull() == null,
                        singleLine = true,
                    )
                    ValueType.BOOLEAN -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = item.booleanValue || item.checked,
                            onCheckedChange = { checked -> onItemChanged(item.copy(booleanValue = checked, checked = checked)) },
                        )
                        Text("완료 여부")
                    }
                    ValueType.EXERCISE -> {
                        AppTextField(
                            value = item.numberValue.text,
                            onValueChange = { input -> onItemChanged(item.copy(numberValue = TextFieldValue(input), checked = input.isNotBlank() || item.durationMinutes.text.isNotBlank())) },
                            label = "거리(km)",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            isError = item.numberValue.text.isNotBlank() && item.numberValue.text.toDoubleOrNull() == null,
                            singleLine = true,
                        )
                        AppTextField(
                            value = item.durationMinutes.text,
                            onValueChange = { input -> onItemChanged(item.copy(durationMinutes = TextFieldValue(input), checked = input.isNotBlank() || item.numberValue.text.isNotBlank())) },
                            label = "시간(분)",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = item.durationMinutes.text.isNotBlank() && item.durationMinutes.text.toIntOrNull() == null,
                            singleLine = true,
                        )
                    }
                    ValueType.TEXT -> AppTextField(
                        value = item.textValue.text,
                        onValueChange = { input -> onItemChanged(item.copy(textValue = TextFieldValue(input), checked = input.isNotBlank())) },
                        label = "상세 내용",
                        minLines = 2,
                    )
                    ValueType.DURATION -> AppTextField(
                        value = item.durationMinutes.text,
                        onValueChange = { input -> onItemChanged(item.copy(durationMinutes = TextFieldValue(input), checked = input.isNotBlank())) },
                        label = "시간(분)",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = item.durationMinutes.text.isNotBlank() && item.durationMinutes.text.toIntOrNull() == null,
                        singleLine = true,
                    )
                }
                if (item.valueType != ValueType.BOOLEAN && item.valueType != ValueType.EXERCISE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = item.checked, onCheckedChange = { checked -> onItemChanged(item.copy(checked = checked)) })
                        Text("완료로 표시")
                    }
                }
                AppTextField(
                    value = item.note.text,
                    onValueChange = { note -> onItemChanged(item.copy(note = TextFieldValue(note))) },
                    label = "메모",
                )
            } else {
                Text(
                    text = summarizeTaskItem(item),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun summarizeTaskItem(item: TaskItemEditorState): String {
    return when (item.valueType) {
        ValueType.NUMBER -> item.numberValue.text.takeIf { it.isNotBlank() }?.let {
            if (item.unit.isNullOrBlank()) it else "$it ${item.unit}"
        } ?: "입력 전"
        ValueType.BOOLEAN -> if (item.booleanValue || item.checked) "완료" else "미완료"
        ValueType.EXERCISE -> {
            val distance = item.numberValue.text.takeIf { it.isNotBlank() }?.let { "$it km" }
            val duration = item.durationMinutes.text.takeIf { it.isNotBlank() }?.let { "$it 분" }
            listOfNotNull(distance, duration).joinToString(" / ").ifBlank { "입력 전" }
        }
        ValueType.TEXT -> item.textValue.text.takeIf { it.isNotBlank() } ?: "입력 전"
        ValueType.DURATION -> item.durationMinutes.text.takeIf { it.isNotBlank() }?.let { "$it 분" } ?: "입력 전"
    }
}

data class TaskItemEditorState(
    val taskItemMasterId: Long,
    val name: String,
    val category: String,
    val valueType: ValueType,
    val unit: String?,
    val numberValue: TextFieldValue,
    val booleanValue: Boolean,
    val textValue: TextFieldValue,
    val durationMinutes: TextFieldValue,
    val checked: Boolean,
    val note: TextFieldValue,
    val description: String,
    val hasExistingValue: Boolean,
) {
    fun toInputState(): TaskItemInputState = TaskItemInputState(
        taskItemMasterId = taskItemMasterId,
        name = name,
        category = category,
        valueType = valueType,
        unit = unit,
        numberValue = numberValue.text,
        booleanValue = booleanValue,
        textValue = textValue.text,
        durationMinutes = durationMinutes.text,
        checked = checked,
        note = note.text,
        description = description,
        hasExistingValue = hasExistingValue,
    )

    companion object {
        fun from(item: TaskItemInputState): TaskItemEditorState = TaskItemEditorState(
            taskItemMasterId = item.taskItemMasterId,
            name = item.name,
            category = item.category,
            valueType = item.valueType,
            unit = item.unit,
            numberValue = TextFieldValue(item.numberValue),
            booleanValue = item.booleanValue,
            textValue = TextFieldValue(item.textValue),
            durationMinutes = TextFieldValue(item.durationMinutes),
            checked = item.checked,
            note = TextFieldValue(item.note),
            description = item.description,
            hasExistingValue = item.hasExistingValue,
        )
    }
}

private fun TaskItemInputState.comparisonValue() = copy(
    numberValue = numberValue.toDoubleOrNull()?.toString() ?: numberValue,
    durationMinutes = durationMinutes.toIntOrNull()?.toString() ?: durationMinutes,
    textValue = textValue.trim(),
    note = note.trim(),
    hasExistingValue = false,
)

private val TaskItemsSaver = listSaver<List<TaskItemEditorState>, Bundle>(
    save = { items -> items.map { item ->
        Bundle().apply {
            putLong("id", item.taskItemMasterId)
            putString("name", item.name)
            putString("category", item.category)
            putString("type", item.valueType.name)
            putString("unit", item.unit)
            putString("number", item.numberValue.text)
            putBoolean("boolean", item.booleanValue)
            putString("text", item.textValue.text)
            putString("duration", item.durationMinutes.text)
            putBoolean("checked", item.checked)
            putString("note", item.note.text)
            putString("description", item.description)
            putBoolean("existing", item.hasExistingValue)
        }
    } },
    restore = { values -> values.map { value ->
        TaskItemEditorState(
            taskItemMasterId = value.getLong("id"),
            name = value.getString("name").orEmpty(),
            category = value.getString("category").orEmpty(),
            valueType = ValueType.valueOf(requireNotNull(value.getString("type"))),
            unit = value.getString("unit"),
            numberValue = TextFieldValue(value.getString("number").orEmpty()),
            booleanValue = value.getBoolean("boolean"),
            textValue = TextFieldValue(value.getString("text").orEmpty()),
            durationMinutes = TextFieldValue(value.getString("duration").orEmpty()),
            checked = value.getBoolean("checked"),
            note = TextFieldValue(value.getString("note").orEmpty()),
            description = value.getString("description").orEmpty(),
            hasExistingValue = value.getBoolean("existing"),
        )
    } },
)
