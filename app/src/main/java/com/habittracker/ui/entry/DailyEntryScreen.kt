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
    var expanded by remember(uiState.selectedDate, uiState.taskItems) { mutableStateOf(false) }
    val editableItems = remember(uiState.selectedDate, uiState.taskItems) { mutableStateListOf<TaskItemEditorState>().apply { addAll(uiState.taskItems.map(TaskItemEditorState.Companion::from)) } }
    val visibleItemIds = remember(uiState.selectedDate, uiState.taskItems) {
        val defaultVisibleIds = if (uiState.hasExistingRecord) uiState.taskItems.filter(TaskItemInputState::hasExistingValue).map(TaskItemInputState::taskItemMasterId).toMutableSet() else uiState.taskItems.map(TaskItemInputState::taskItemMasterId).toMutableSet()
        mutableStateOf(defaultVisibleIds)
    }

    LaunchedEffect(initialDate) { viewModel.loadRecord(initialDate) }

    val hiddenItems = editableItems.filterNot { visibleItemIds.value.contains(it.taskItemMasterId) }
    val selectedHiddenLabel = hiddenItems.firstOrNull()?.name ?: "\uC6B4\uB3D9 / \uD56D\uBAA9 \uC120\uD0DD"

    LazyColumn(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "\uC77C\uC77C \uAE30\uB85D", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(text = if (uiState.hasExistingRecord) "\uC800\uC7A5\uB41C \uAE30\uB85D\uC744 \uBA3C\uC800 \uBCF4\uC5EC\uC8FC\uACE0 \uC788\uC2B5\uB2C8\uB2E4." else "\uC0C8 \uAE30\uB85D\uC744 \uC791\uC131\uD560 \uC218 \uC788\uC2B5\uB2C8\uB2E4.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(value = dateInput, onValueChange = { dateInput = it }, label = { Text("\uAE30\uB85D \uB0A0\uC9DC") }, modifier = Modifier.weight(1f), singleLine = true)
                Button(onClick = { viewModel.loadRecord(dateInput.text) }, modifier = Modifier.align(Alignment.CenterVertically)) { Text("\uBD88\uB7EC\uC624\uAE30") }
            }
        }
        item { OutlinedTextField(value = memo, onValueChange = { memo = it }, label = { Text("\uBA54\uBAA8") }, modifier = Modifier.fillMaxWidth(), minLines = 3) }
        if (hiddenItems.isNotEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(value = selectedHiddenLabel, onValueChange = {}, readOnly = true, label = { Text("\uC6B4\uB3D9 \uC120\uD0DD") }, modifier = Modifier.fillMaxWidth().clickable { expanded = true })
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        hiddenItems.forEach { hiddenItem ->
                            DropdownMenuItem(text = { Text(hiddenItem.name) }, onClick = {
                                visibleItemIds.value = visibleItemIds.value.toMutableSet().apply { add(hiddenItem.taskItemMasterId) }
                                expanded = false
                            })
                        }
                    }
                }
            }
        }
        items(editableItems.filter { visibleItemIds.value.contains(it.taskItemMasterId) }, key = { it.taskItemMasterId }) { item ->
            TaskInputCard(item = item, onItemChanged = { updated ->
                val index = editableItems.indexOfFirst { it.taskItemMasterId == updated.taskItemMasterId }
                if (index >= 0) editableItems[index] = updated
            })
        }
        item {
            Button(onClick = {
                val parsedDate = runCatching { LocalDate.parse(dateInput.text) }.getOrNull()
                if (parsedDate != null) viewModel.saveDailyRecord(recordDate = parsedDate, memo = memo.text, items = editableItems.map(TaskItemEditorState::toInputState)) else viewModel.loadRecord(dateInput.text)
            }, modifier = Modifier.fillMaxWidth()) { Text("\uAE30\uB85D \uC800\uC7A5") }
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
                ValueType.NUMBER -> OutlinedTextField(value = item.numberValue, onValueChange = { input -> onItemChanged(item.copy(numberValue = input, checked = input.text.isNotBlank())) }, label = { Text(if (item.unit == null) "\uC218\uCE58" else "\uC218\uCE58 (${item.unit})") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                ValueType.BOOLEAN -> Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("\uC644\uB8CC \uC5EC\uBD80"); Switch(checked = item.booleanValue, onCheckedChange = { checked -> onItemChanged(item.copy(booleanValue = checked, checked = checked)) }) }
                ValueType.TEXT -> OutlinedTextField(value = item.textValue, onValueChange = { input -> onItemChanged(item.copy(textValue = input, checked = input.text.isNotBlank())) }, label = { Text("\uC0C1\uC138 \uB0B4\uC6A9") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                ValueType.DURATION -> OutlinedTextField(value = item.durationMinutes, onValueChange = { input -> onItemChanged(item.copy(durationMinutes = input, checked = input.text.isNotBlank())) }, label = { Text("\uC2DC\uAC04(\uBD84)") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = item.checked, onCheckedChange = { checked -> onItemChanged(item.copy(checked = checked)) }); Text("\uC644\uB8CC\uB85C \uD45C\uC2DC") }
            OutlinedTextField(value = item.note, onValueChange = { note -> onItemChanged(item.copy(note = note)) }, label = { Text("\uBA54\uBAA8") }, modifier = Modifier.fillMaxWidth())
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