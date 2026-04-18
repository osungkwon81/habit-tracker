package com.habittracker.ui.entry

import android.app.DatePickerDialog
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
import androidx.compose.runtime.collectAsState
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
import com.habittracker.data.local.ValueType
import com.habittracker.ui.components.AppHeroCard
import com.habittracker.ui.components.AppNoticeDialog
import com.habittracker.ui.components.AppPrimaryButton
import com.habittracker.ui.components.AppScreen
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppSectionHeader
import com.habittracker.ui.components.AppSelectableChip
import com.habittracker.ui.components.AppStatusText
import com.habittracker.ui.components.AppTextField
import com.habittracker.ui.components.actionNoticeDialogTitle
import com.habittracker.ui.components.shouldShowActionNoticeDialog
import java.time.LocalDate

private val EntryHeroColor = androidx.compose.ui.graphics.Color(0xFFFFFFFF)
private val EntryHeroSubColor = androidx.compose.ui.graphics.Color(0xFF5C6661)
private val EntryCardColor = androidx.compose.ui.graphics.Color(0xFFFFFFFF)
private val EntryExerciseCardColor = androidx.compose.ui.graphics.Color(0xFFF2F4F3)
private val EntryTextStrongColor = androidx.compose.ui.graphics.Color(0xFF171C19)
private val EntryTextMutedColor = androidx.compose.ui.graphics.Color(0xFF5C6661)

@Composable
fun DailyEntryScreen(viewModel: DailyEntryViewModel, initialDate: String) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var dateInput by remember(initialDate) { mutableStateOf(TextFieldValue(initialDate)) }
    var memo by remember(uiState.selectedDate, uiState.memo) { mutableStateOf(TextFieldValue(uiState.memo)) }
    var isHoliday by remember(uiState.selectedDate, uiState.isHoliday) { mutableStateOf(uiState.isHoliday) }
    var expanded by remember(uiState.selectedDate, uiState.taskItems) { mutableStateOf(false) }
    var selectedCategory by remember(uiState.selectedDate, uiState.taskItems) { mutableStateOf("전체") }
    var noticeMessage by remember { mutableStateOf<String?>(null) }
    val editableItems = remember(uiState.selectedDate, uiState.taskItems) { mutableStateListOf<TaskItemEditorState>().apply { addAll(uiState.taskItems.map(TaskItemEditorState.Companion::from)) } }
    val visibleItemIds = remember(uiState.selectedDate, uiState.taskItems) {
        mutableStateOf(uiState.taskItems.map(TaskItemInputState::taskItemMasterId).toMutableSet())
    }

    LaunchedEffect(initialDate) { viewModel.loadRecord(initialDate) }
    LaunchedEffect(uiState.statusMessage) {
        val message = uiState.statusMessage.orEmpty()
        if (message.shouldShowActionNoticeDialog()) {
            noticeMessage = message
        }
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
                dateInput = TextFieldValue(pickedDate.toString())
                viewModel.loadRecord(pickedDate.toString())
            },
            uiState.selectedDate.year,
            uiState.selectedDate.monthValue - 1,
            uiState.selectedDate.dayOfMonth,
        ).show()
    }

    AppScreen {
        noticeMessage?.let { message ->
            item {
                AppNoticeDialog(
                    message = message,
                    onDismiss = { noticeMessage = null },
                    title = message.actionNoticeDialogTitle(),
                )
            }
        }
        item {
            AppHeroCard(
                title = "일일 기록",
                description = null,
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
                    if (index >= 0) editableItems[index] = updated
                },
            )
        }
        item {
            AppPrimaryButton(
                text = "기록 저장",
                onClick = {
                    val parsedDate = runCatching { LocalDate.parse(dateInput.text) }.getOrNull()
                    if (parsedDate != null) {
                        viewModel.saveDailyRecord(
                            recordDate = parsedDate,
                            memo = memo.text,
                            isHoliday = isHoliday,
                            items = editableItems.map(TaskItemEditorState::toInputState),
                        )
                    } else {
                        viewModel.loadRecord(dateInput.text)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
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
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
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
                        color = androidx.compose.ui.graphics.Color.White,
                    )
                    Text(
                        text = item.category,
                        style = MaterialTheme.typography.labelMedium,
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.86f),
                    )
                }
                Text(
                    text = if (isExpanded) "접기" else "펼치기",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = androidx.compose.ui.graphics.Color.White,
                )
            }
            if (isExpanded) {
                when (item.valueType) {
                    ValueType.NUMBER -> AppTextField(
                        value = item.numberValue.text,
                        onValueChange = { input -> onItemChanged(item.copy(numberValue = TextFieldValue(input), checked = input.isNotBlank())) },
                        label = if (item.unit == null) "수치" else "수치 (${item.unit})",
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
                            singleLine = true,
                        )
                        AppTextField(
                            value = item.durationMinutes.text,
                            onValueChange = { input -> onItemChanged(item.copy(durationMinutes = TextFieldValue(input), checked = input.isNotBlank() || item.numberValue.text.isNotBlank())) },
                            label = "시간(분)",
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
