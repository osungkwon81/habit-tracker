package com.habittracker.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.habittracker.data.local.ValueType
import com.habittracker.ui.components.AppHeroCard
import com.habittracker.ui.components.AppNoticeDialog
import com.habittracker.ui.components.AppPrimaryButton
import com.habittracker.ui.components.AppScreen
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppSectionHeader
import com.habittracker.ui.components.AppStatusText
import com.habittracker.ui.components.AppTextField
import com.habittracker.ui.components.actionNoticeDialogTitle
import com.habittracker.ui.components.shouldShowActionNoticeDialog

@Composable
fun AdminScreen(viewModel: AdminViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var category by remember { mutableStateOf(TextFieldValue("운동")) }
    var name by remember { mutableStateOf(TextFieldValue("")) }
    var unit by remember { mutableStateOf(TextFieldValue("")) }
    var selectedValueType by remember { mutableStateOf(ValueType.NUMBER) }
    var selectedColorHex by remember { mutableStateOf(viewModel.colorOptions.first()) }
    var noticeMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uiState.statusMessage) {
        val message = uiState.statusMessage.orEmpty()
        if (message.shouldShowActionNoticeDialog()) {
            noticeMessage = message
        }
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
                title = "관리",
                description = null,
            )
        }
        item {
            AppSectionCard {
                AppSectionHeader(title = "새 항목")
                AppTextField(
                    value = category.text,
                    onValueChange = { category = TextFieldValue(it) },
                    label = "카테고리",
                    singleLine = true,
                )
                ValueTypeSelector(
                    supportedValueTypes = viewModel.supportedValueTypes,
                    selectedValueType = selectedValueType,
                    getLabel = viewModel::getValueTypeLabel,
                    onSelected = { selectedValueType = it },
                )
                if (selectedValueType == ValueType.NUMBER) {
                    AppTextField(
                        value = name.text,
                        onValueChange = { name = TextFieldValue(it) },
                        label = "항목 이름",
                        singleLine = true,
                    )
                    AppTextField(
                        value = unit.text,
                        onValueChange = { unit = TextFieldValue(it) },
                        label = "단위",
                        singleLine = true,
                    )
                } else {
                    WalkingRecordInputFields()
                }
                ColorSelectorRow(
                    colors = viewModel.colorOptions,
                    selectedColorHex = selectedColorHex,
                    onSelect = { selectedColorHex = it },
                )
                AppPrimaryButton(
                    text = "항목 추가",
                    onClick = {
                        viewModel.addTaskItem(
                            name = if (selectedValueType == ValueType.EXERCISE) "걷기 기록" else name.text,
                            category = category.text,
                            valueType = selectedValueType,
                            unit = if (selectedValueType == ValueType.NUMBER) unit.text else "",
                            description = "",
                            colorHex = selectedColorHex,
                        )
                        name = TextFieldValue("")
                        unit = TextFieldValue("")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            uiState.statusMessage?.let { message -> AppStatusText(message) }
        }
        item {
            AppSectionHeader(title = "현재 항목")
        }
        items(uiState.taskItems, key = { it.item.id }) { item ->
            TaskItemCard(
                item = item,
                onDelete = { viewModel.deleteTaskItem(item.item.id) },
            )
        }
    }
}

@Composable
private fun TaskItemCard(
    item: AdminTaskItemUi,
    onDelete: () -> Unit,
) {
    AppSectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = item.item.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "카테고리: ${item.item.category}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ColorPreview(colorHex = item.colorHex)
            }
            Text(
                text = if (item.item.valueType == ValueType.NUMBER) {
                    "입력: 숫자 / ${item.item.unit ?: "단위 없음"}"
                } else {
                    "입력: KM / 시간"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "삭제",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onDelete)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun ValueTypeSelector(
    supportedValueTypes: List<ValueType>,
    selectedValueType: ValueType,
    getLabel: (ValueType) -> String,
    onSelected: (ValueType) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        supportedValueTypes.forEachIndexed { index, valueType ->
            SegmentedButton(
                selected = selectedValueType == valueType,
                onClick = { onSelected(valueType) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = supportedValueTypes.size),
            ) {
                Text(text = getLabel(valueType))
            }
        }
    }
}

@Composable
private fun ColorSelectorRow(
    colors: List<String>,
    selectedColorHex: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "통계 선 색상",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            colors.forEach { colorHex ->
                val selected = selectedColorHex == colorHex
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(colorFromHex(colorHex))
                        .clickable { onSelect(colorHex) }
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape,
                        ),
                )
            }
        }
    }
}

@Composable
private fun ColorPreview(colorHex: String) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(colorFromHex(colorHex)),
    )
}

@Composable
private fun WalkingRecordInputFields() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppTextField(value = "KM", onValueChange = {}, label = "거리 입력", readOnly = true, singleLine = true)
        AppTextField(value = "시간", onValueChange = {}, label = "시간 입력", readOnly = true, singleLine = true)
    }
}

private fun colorFromHex(colorHex: String): Color {
    return runCatching { Color(android.graphics.Color.parseColor(colorHex)) }
        .getOrDefault(Color(0xFF256A52))
}
