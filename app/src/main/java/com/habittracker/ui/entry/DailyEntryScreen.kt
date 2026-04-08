package com.habittracker.ui.entry

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import java.time.LocalDate

private val EntryHeroColor = androidx.compose.ui.graphics.Color(0xFF13242A)
private val EntryHeroSubColor = androidx.compose.ui.graphics.Color(0xFFE4D8BF)
private val EntryCardColor = androidx.compose.ui.graphics.Color(0xFFFFFBF5)
private val EntryExerciseCardColor = androidx.compose.ui.graphics.Color(0xFFE9F4ED)
private val EntryTextStrongColor = androidx.compose.ui.graphics.Color(0xFF172126)
private val EntryTextMutedColor = androidx.compose.ui.graphics.Color(0xFF34464D)

@Composable
fun DailyEntryScreen(viewModel: DailyEntryViewModel, initialDate: String) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var dateInput by remember(initialDate) { mutableStateOf(TextFieldValue(initialDate)) }
    var memo by remember(uiState.selectedDate, uiState.memo) { mutableStateOf(TextFieldValue(uiState.memo)) }
    var isHoliday by remember(uiState.selectedDate, uiState.isHoliday) { mutableStateOf(uiState.isHoliday) }
    var expanded by remember(uiState.selectedDate, uiState.taskItems) { mutableStateOf(false) }
    var selectedCategory by remember(uiState.selectedDate, uiState.taskItems) { mutableStateOf("전체") }
    val editableItems = remember(uiState.selectedDate, uiState.taskItems) { mutableStateListOf<TaskItemEditorState>().apply { addAll(uiState.taskItems.map(TaskItemEditorState.Companion::from)) } }
    val visibleItemIds = remember(uiState.selectedDate, uiState.taskItems) {
        mutableStateOf(uiState.taskItems.map(TaskItemInputState::taskItemMasterId).toMutableSet())
    }

    LaunchedEffect(initialDate) { viewModel.loadRecord(initialDate) }

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

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(shape = RoundedCornerShape(30.dp), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = EntryHeroColor)) {
                Column(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "✍️ 일일 기록", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.White)
                    Text(
                        text = if (uiState.hasExistingRecord) "기존 기록을 불러와 이어서 수정할 수 있습니다." else "오늘의 루틴과 메모를 한 번에 정리하세요.",
                        color = EntryHeroSubColor,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(24.dp), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = dateInput,
                            onValueChange = { },
                            readOnly = true,
                            label = { Text("기록 날짜") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Box(modifier = Modifier.fillMaxSize().clickable(onClick = openDatePicker))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = openDatePicker, modifier = Modifier.weight(1f)) { Text("달력") }
                        Button(onClick = { viewModel.loadRecord(dateInput.text) }, modifier = Modifier.weight(1f)) { Text("불러오기") }
                    }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(24.dp), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                OutlinedTextField(
                    value = memo,
                    onValueChange = { memo = it },
                    label = { Text("메모") },
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    minLines = 3,
                )
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(checked = isHoliday, onCheckedChange = { isHoliday = it })
                Column {
                    Text(text = "휴일")
                    Text(
                        text = "체크하면 달력에서 빨간색으로 표시됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = EntryTextMutedColor,
                    )
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "카테고리별 보기", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    items(categories, key = { it }) { category ->
                        FilterChip(
                            selected = selectedCategory == category,
                            onClick = { selectedCategory = category },
                            label = { Text(category) },
                        )
                    }
                }
            }
        }
        if (hiddenItems.isNotEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = selectedHiddenLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("항목 추가") },
                        modifier = Modifier.fillMaxWidth().clickable { expanded = true },
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
            Button(
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
            ) {
                Text("기록 저장")
            }
        }
        item {
            uiState.statusMessage?.let { message ->
                Text(text = message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun TaskInputCard(item: TaskItemEditorState, onItemChanged: (TaskItemEditorState) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (item.valueType == ValueType.EXERCISE) EntryExerciseCardColor else EntryCardColor,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = EntryTextStrongColor)
            Text(text = item.category, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            if (!item.description.isBlank()) {
                Text(text = item.description, style = MaterialTheme.typography.bodySmall, color = EntryTextMutedColor)
            }
            when (item.valueType) {
                ValueType.NUMBER -> OutlinedTextField(
                    value = item.numberValue,
                    onValueChange = { input -> onItemChanged(item.copy(numberValue = input, checked = input.text.isNotBlank())) },
                    label = { Text(if (item.unit == null) "수치" else "수치 (${item.unit})") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
                    Text(
                        text = "거리와 시간을 함께 입력합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = EntryTextMutedColor,
                    )
                    OutlinedTextField(
                        value = item.numberValue,
                        onValueChange = { input -> onItemChanged(item.copy(numberValue = input, checked = input.text.isNotBlank() || item.durationMinutes.text.isNotBlank())) },
                        label = { Text("거리(km)") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = item.durationMinutes,
                        onValueChange = { input -> onItemChanged(item.copy(durationMinutes = input, checked = input.text.isNotBlank() || item.numberValue.text.isNotBlank())) },
                        label = { Text("시간(분)") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }
                ValueType.TEXT -> OutlinedTextField(
                    value = item.textValue,
                    onValueChange = { input -> onItemChanged(item.copy(textValue = input, checked = input.text.isNotBlank())) },
                    label = { Text("상세 내용") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                ValueType.DURATION -> OutlinedTextField(
                    value = item.durationMinutes,
                    onValueChange = { input -> onItemChanged(item.copy(durationMinutes = input, checked = input.text.isNotBlank())) },
                    label = { Text("시간(분)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }
            if (item.valueType != ValueType.BOOLEAN && item.valueType != ValueType.EXERCISE) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = item.checked, onCheckedChange = { checked -> onItemChanged(item.copy(checked = checked)) })
                    Text("완료로 표시")
                }
            }
            OutlinedTextField(
                value = item.note,
                onValueChange = { note -> onItemChanged(item.copy(note = note)) },
                label = { Text("메모") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
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
