package com.habittracker.ui.entry

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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.habittracker.data.local.ValueType
import java.time.LocalDate

@Composable
fun DailyEntryScreen(viewModel: DailyEntryViewModel, initialDate: String) {
    val uiState by viewModel.uiState.collectAsState()
    var dateInput by remember(initialDate) { mutableStateOf(TextFieldValue(initialDate)) }
    var memo by remember(uiState.selectedDate, uiState.memo) { mutableStateOf(TextFieldValue(uiState.memo)) }
    var isHoliday by remember(uiState.selectedDate, uiState.isHoliday) { mutableStateOf(uiState.isHoliday) }
    var expanded by remember(uiState.selectedDate, uiState.taskItems) { mutableStateOf(false) }
    var selectedCategory by remember(uiState.selectedDate, uiState.taskItems) { mutableStateOf("전체") }
    val editableItems = remember(uiState.selectedDate, uiState.taskItems) { mutableStateListOf<TaskItemEditorState>().apply { addAll(uiState.taskItems.map(TaskItemEditorState.Companion::from)) } }
    val visibleItemIds = remember(uiState.selectedDate, uiState.taskItems) {
        val defaultVisibleIds = if (uiState.hasExistingRecord) uiState.taskItems.filter(TaskItemInputState::hasExistingValue).map(TaskItemInputState::taskItemMasterId).toMutableSet() else uiState.taskItems.map(TaskItemInputState::taskItemMasterId).toMutableSet()
        mutableStateOf(defaultVisibleIds)
    }

    LaunchedEffect(initialDate) { viewModel.loadRecord(initialDate) }

    val categories = remember(uiState.taskItems) { listOf("전체") + uiState.taskItems.map(TaskItemInputState::category).distinct() }
    val hiddenItems = editableItems.filterNot { visibleItemIds.value.contains(it.taskItemMasterId) }
    val selectedHiddenLabel = hiddenItems.firstOrNull()?.name ?: "운동 / 항목 선택"
    val filteredVisibleItems = editableItems.filter { visibleItemIds.value.contains(it.taskItemMasterId) && (selectedCategory == "전체" || it.category == selectedCategory) }

    LazyColumn(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "일일 기록", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(text = if (uiState.hasExistingRecord) "저장된 기록을 먼저 보여주고 있습니다." else "새 기록을 작성할 수 있습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(value = dateInput, onValueChange = { dateInput = it }, label = { Text("기록 날짜") }, modifier = Modifier.weight(1f), singleLine = true)
                Button(onClick = { viewModel.loadRecord(dateInput.text) }, modifier = Modifier.align(Alignment.CenterVertically)) { Text("불러오기") }
            }
        }
        item { OutlinedTextField(value = memo, onValueChange = { memo = it }, label = { Text("메모") }, modifier = Modifier.fillMaxWidth(), minLines = 3) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(checked = isHoliday, onCheckedChange = { isHoliday = it })
                Column {
                    Text(text = "휴일")
                    Text(text = "체크하면 달력에서 빨간색으로 표시됩니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "카테고리별 보기", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    categories.take(4).forEach { category ->
                        FilterChip(selected = selectedCategory == category, onClick = { selectedCategory = category }, label = { Text(category) })
                    }
                }
                if (categories.size > 4) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        categories.drop(4).forEach { category ->
                            FilterChip(selected = selectedCategory == category, onClick = { selectedCategory = category }, label = { Text(category) })
                        }
                    }
                }
            }
        }
        if (hiddenItems.isNotEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(value = selectedHiddenLabel, onValueChange = {}, readOnly = true, label = { Text("운동 선택") }, modifier = Modifier.fillMaxWidth().clickable { expanded = true })
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        hiddenItems.filter { selectedCategory == "전체" || it.category == selectedCategory }.forEach { hiddenItem ->
                            DropdownMenuItem(text = { Text(hiddenItem.name) }, onClick = {
                                visibleItemIds.value = visibleItemIds.value.toMutableSet().apply { add(hiddenItem.taskItemMasterId) }
                                expanded = false
                            })
                        }
                    }
                }
            }
        }
        items(filteredVisibleItems, key = { it.taskItemMasterId }) { item ->
            TaskInputCard(item = item, onItemChanged = { updated ->
                val index = editableItems.indexOfFirst { it.taskItemMasterId == updated.taskItemMasterId }
                if (index >= 0) editableItems[index] = updated
            })
        }
        item {
            Button(onClick = {
                val parsedDate = runCatching { LocalDate.parse(dateInput.text) }.getOrNull()
                if (parsedDate != null) {
                    viewModel.saveDailyRecord(recordDate = parsedDate, memo = memo.text, isHoliday = isHoliday, items = editableItems.map(TaskItemEditorState::toInputState))
                } else {
                    viewModel.loadRecord(dateInput.text)
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("기록 저장") }
        }
        item { uiState.statusMessage?.let { message -> Text(text = message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) } }
    }
}

@Composable
private fun TaskInputCard(item: TaskItemEditorState, onItemChanged: (TaskItemEditorState) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(text = item.category, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            when (item.valueType) {
                ValueType.NUMBER -> OutlinedTextField(value = item.numberValue, onValueChange = { input -> onItemChanged(item.copy(numberValue = input, checked = input.text.isNotBlank())) }, label = { Text(if (item.unit == null) "수치" else "수치 (${item.unit})") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                ValueType.BOOLEAN -> Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("완료 여부"); Switch(checked = item.booleanValue, onCheckedChange = { checked -> onItemChanged(item.copy(booleanValue = checked, checked = checked)) }) }
                ValueType.TEXT -> OutlinedTextField(value = item.textValue, onValueChange = { input -> onItemChanged(item.copy(textValue = input, checked = input.text.isNotBlank())) }, label = { Text("상세 내용") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                ValueType.DURATION -> OutlinedTextField(value = item.durationMinutes, onValueChange = { input -> onItemChanged(item.copy(durationMinutes = input, checked = input.text.isNotBlank())) }, label = { Text("시간(분)") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = item.checked, onCheckedChange = { checked -> onItemChanged(item.copy(checked = checked)) }); Text("완료로 표시") }
            OutlinedTextField(value = item.note, onValueChange = { note -> onItemChanged(item.copy(note = note)) }, label = { Text("메모") }, modifier = Modifier.fillMaxWidth())
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
    val hasExistingValue: Boolean,
) {
    fun toInputState(): TaskItemInputState = TaskItemInputState(taskItemMasterId = taskItemMasterId, name = name, category = category, valueType = valueType, unit = unit, numberValue = numberValue.text, booleanValue = booleanValue, textValue = textValue.text, durationMinutes = durationMinutes.text, checked = checked, note = note.text, hasExistingValue = hasExistingValue)

    companion object {
        fun from(item: TaskItemInputState): TaskItemEditorState = TaskItemEditorState(taskItemMasterId = item.taskItemMasterId, name = item.name, category = item.category, valueType = item.valueType, unit = item.unit, numberValue = TextFieldValue(item.numberValue), booleanValue = item.booleanValue, textValue = TextFieldValue(item.textValue), durationMinutes = TextFieldValue(item.durationMinutes), checked = item.checked, note = TextFieldValue(item.note), hasExistingValue = item.hasExistingValue)
    }
}