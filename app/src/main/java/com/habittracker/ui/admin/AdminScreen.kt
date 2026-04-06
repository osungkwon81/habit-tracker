package com.habittracker.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.habittracker.data.local.ValueType
import com.habittracker.data.local.entity.TaskItemMasterEntity

@Composable
fun AdminScreen(viewModel: AdminViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var name by remember { mutableStateOf(TextFieldValue("")) }
    var category by remember { mutableStateOf(TextFieldValue("\uC6B4\uB3D9")) }
    var unit by remember { mutableStateOf(TextFieldValue("\uD68C")) }
    var description by remember { mutableStateOf(TextFieldValue("")) }
    var selectedValueType by remember { mutableStateOf(ValueType.NUMBER) }

    LazyColumn(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(text = "\uAD00\uB9AC\uC790 \uD654\uBA74", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("\uD56D\uBAA9 \uC774\uB984") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("\uCE74\uD14C\uACE0\uB9AC") }, modifier = Modifier.fillMaxWidth()) }
        item {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ValueType.entries.forEachIndexed { index, valueType ->
                    SegmentedButton(selected = selectedValueType == valueType, onClick = { selectedValueType = valueType }, shape = SegmentedButtonDefaults.itemShape(index = index, count = ValueType.entries.size)) {
                        Text(text = viewModel.getValueTypeLabel(valueType))
                    }
                }
            }
        }
        item { OutlinedTextField(value = unit, onValueChange = { unit = it }, label = { Text("\uB2E8\uC704") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("\uC124\uBA85") }, modifier = Modifier.fillMaxWidth(), minLines = 2) }
        item {
            Button(onClick = {
                viewModel.addTaskItem(name = name.text, category = category.text, valueType = selectedValueType, unit = unit.text, description = description.text)
                name = TextFieldValue("")
                description = TextFieldValue("")
            }, modifier = Modifier.fillMaxWidth()) { Text("\uD56D\uBAA9 \uCD94\uAC00") }
        }
        item { uiState.statusMessage?.let { message -> Text(text = message, color = MaterialTheme.colorScheme.primary) } }
        item { Text(text = "\uD604\uC7AC \uD56D\uBAA9", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(uiState.taskItems, key = { it.id }) { item ->
            EditableTaskItemCard(item = item, viewModel = viewModel, onSave = { updatedName, updatedCategory, updatedValueType, updatedUnit, updatedDescription ->
                viewModel.updateTaskItem(taskItemId = item.id, name = updatedName, category = updatedCategory, valueType = updatedValueType, unit = updatedUnit, description = updatedDescription)
            }, onDelete = { viewModel.deleteTaskItem(item.id) })
        }
    }
}

@Composable
private fun EditableTaskItemCard(item: TaskItemMasterEntity, viewModel: AdminViewModel, onSave: (String, String, ValueType, String, String) -> Unit, onDelete: () -> Unit) {
    var isEditing by remember(item.id) { mutableStateOf(false) }
    var editName by remember(item.id, item.name) { mutableStateOf(TextFieldValue(item.name)) }
    var editCategory by remember(item.id, item.category) { mutableStateOf(TextFieldValue(item.category)) }
    var editUnit by remember(item.id, item.unit) { mutableStateOf(TextFieldValue(item.unit.orEmpty())) }
    var editDescription by remember(item.id, item.description) { mutableStateOf(TextFieldValue(item.description.orEmpty())) }
    var editValueType by remember(item.id, item.valueType) { mutableStateOf(item.valueType) }

    Card(shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isEditing) {
                OutlinedTextField(value = editName, onValueChange = { editName = it }, label = { Text("\uD56D\uBAA9 \uC774\uB984") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = editCategory, onValueChange = { editCategory = it }, label = { Text("\uCE74\uD14C\uACE0\uB9AC") }, modifier = Modifier.fillMaxWidth())
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ValueType.entries.forEachIndexed { index, valueType ->
                        SegmentedButton(selected = editValueType == valueType, onClick = { editValueType = valueType }, shape = SegmentedButtonDefaults.itemShape(index = index, count = ValueType.entries.size)) {
                            Text(text = viewModel.getValueTypeLabel(valueType))
                        }
                    }
                }
                OutlinedTextField(value = editUnit, onValueChange = { editUnit = it }, label = { Text("\uB2E8\uC704") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = editDescription, onValueChange = { editDescription = it }, label = { Text("\uC124\uBA85") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Button(onClick = { onSave(editName.text, editCategory.text, editValueType, editUnit.text, editDescription.text); isEditing = false }, modifier = Modifier.fillMaxWidth()) { Text("\uC218\uC815 \uC800\uC7A5") }
            } else {
                Text(text = item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(text = "\uCE74\uD14C\uACE0\uB9AC: ${item.category}")
                Text(text = "\uD0C0\uC785: ${viewModel.getValueTypeLabel(item.valueType)}")
                Text(text = "\uB2E8\uC704: ${item.unit ?: "\uC5C6\uC74C"}")
                Text(text = "\uC124\uBA85: ${item.description ?: "-"}")
                Button(onClick = { isEditing = true }, modifier = Modifier.fillMaxWidth()) { Text("\uD56D\uBAA9 \uC218\uC815") }
                Button(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("\uD56D\uBAA9 \uC0AD\uC81C") }
            }
        }
    }
}