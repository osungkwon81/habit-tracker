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
import androidx.compose.material3.CardDefaults
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

private val AdminHeroColor = androidx.compose.ui.graphics.Color(0xFF1C262C)
private val AdminHeroSubColor = androidx.compose.ui.graphics.Color(0xFFE7DCC7)
private val AdminCardColor = androidx.compose.ui.graphics.Color(0xFFFFFBF5)
private val AdminTextStrongColor = androidx.compose.ui.graphics.Color(0xFF172126)
private val AdminTextMutedColor = androidx.compose.ui.graphics.Color(0xFF34464D)

@Composable
fun AdminScreen(viewModel: AdminViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var category by remember { mutableStateOf(TextFieldValue("운동")) }
    var name by remember { mutableStateOf(TextFieldValue("")) }
    var unit by remember { mutableStateOf(TextFieldValue("")) }
    var description by remember { mutableStateOf(TextFieldValue("")) }
    var selectedValueType by remember { mutableStateOf(ValueType.NUMBER) }

    LazyColumn(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(shape = RoundedCornerShape(30.dp), colors = CardDefaults.cardColors(containerColor = AdminHeroColor)) {
                Column(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = "⚙️ 관리자 화면", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.White)
                    Text(text = "기록에 사용할 항목과 입력 방식을 정리합니다.", color = AdminHeroSubColor, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item { Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = AdminCardColor)) { OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("카테고리") }, modifier = Modifier.fillMaxWidth().padding(12.dp)) } }
        item { Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = AdminCardColor)) { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("항목 이름") }, modifier = Modifier.fillMaxWidth().padding(12.dp)) } }
        item {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = AdminCardColor)) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    viewModel.supportedValueTypes.forEachIndexed { index, valueType ->
                        SegmentedButton(selected = selectedValueType == valueType, onClick = { selectedValueType = valueType }, shape = SegmentedButtonDefaults.itemShape(index = index, count = viewModel.supportedValueTypes.size)) {
                            Text(text = viewModel.getValueTypeLabel(valueType))
                        }
                    }
                }
            }
        }
        if (selectedValueType == ValueType.NUMBER) {
            item { Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = AdminCardColor)) { OutlinedTextField(value = unit, onValueChange = { unit = it }, label = { Text("단위") }, modifier = Modifier.fillMaxWidth().padding(12.dp)) } }
        } else if (selectedValueType == ValueType.EXERCISE) {
            item {
                Text(
                    text = "운동 기록은 거리(km)와 시간(분)을 함께 입력합니다.",
                    color = AdminTextMutedColor,
                )
            }
        }
        item { Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = AdminCardColor)) { OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("설명") }, modifier = Modifier.fillMaxWidth().padding(12.dp), minLines = 2) } }
        item {
            Button(onClick = {
                viewModel.addTaskItem(
                    name = name.text,
                    category = category.text,
                    valueType = selectedValueType,
                    unit = if (selectedValueType == ValueType.NUMBER) unit.text else "",
                    description = description.text,
                )
                name = TextFieldValue("")
                description = TextFieldValue("")
                unit = TextFieldValue("")
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
    var editValueType by remember(item.id, item.valueType) {
        mutableStateOf(if (item.valueType in viewModel.supportedValueTypes) item.valueType else ValueType.NUMBER)
    }

    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = AdminCardColor)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isEditing) {
                OutlinedTextField(value = editName, onValueChange = { editName = it }, label = { Text("\uD56D\uBAA9 \uC774\uB984") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = editCategory, onValueChange = { editCategory = it }, label = { Text("\uCE74\uD14C\uACE0\uB9AC") }, modifier = Modifier.fillMaxWidth())
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    viewModel.supportedValueTypes.forEachIndexed { index, valueType ->
                        SegmentedButton(selected = editValueType == valueType, onClick = { editValueType = valueType }, shape = SegmentedButtonDefaults.itemShape(index = index, count = viewModel.supportedValueTypes.size)) {
                            Text(text = viewModel.getValueTypeLabel(valueType))
                        }
                    }
                }
                if (editValueType == ValueType.NUMBER) {
                    OutlinedTextField(value = editUnit, onValueChange = { editUnit = it }, label = { Text("\uB2E8\uC704") }, modifier = Modifier.fillMaxWidth())
                } else if (editValueType == ValueType.EXERCISE) {
                    Text(
                        text = "운동 기록은 거리(km)와 시간(분)을 함께 입력합니다.",
                        color = AdminTextMutedColor,
                    )
                }
                OutlinedTextField(value = editDescription, onValueChange = { editDescription = it }, label = { Text("\uC124\uBA85") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Button(onClick = {
                    onSave(
                        editName.text,
                        editCategory.text,
                        editValueType,
                        if (editValueType == ValueType.NUMBER) editUnit.text else "",
                        editDescription.text,
                    )
                    isEditing = false
                }, modifier = Modifier.fillMaxWidth()) { Text("\uC218\uC815 \uC800\uC7A5") }
            } else {
                Text(text = item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = AdminTextStrongColor)
                Text(text = "\uCE74\uD14C\uACE0\uB9AC: ${item.category}", color = AdminTextMutedColor)
                Text(text = "\uD0C0\uC785: ${viewModel.getValueTypeLabel(item.valueType)}", color = AdminTextMutedColor)
                if (item.valueType == ValueType.NUMBER) {
                    Text(text = "\uB2E8\uC704: ${item.unit ?: "\uC5C6\uC74C"}", color = AdminTextMutedColor)
                } else if (item.valueType == ValueType.EXERCISE) {
                    Text(text = "\uAE30\uB85D: \uAC70\uB9AC(km) + \uC2DC\uAC04(\uBD84)", color = AdminTextMutedColor)
                }
                Text(text = "\uC124\uBA85: ${item.description ?: "-"}", color = AdminTextMutedColor)
                Button(onClick = { isEditing = true }, modifier = Modifier.fillMaxWidth()) { Text("\uD56D\uBAA9 \uC218\uC815") }
                Button(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("\uD56D\uBAA9 \uC0AD\uC81C") }
            }
        }
    }
}
