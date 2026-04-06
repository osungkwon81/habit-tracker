package com.habittracker.ui.diary

import android.app.DatePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable
fun DiaryScreen(viewModel: DiaryViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var dateInput by remember(uiState.diaryDate) { mutableStateOf(TextFieldValue(uiState.diaryDate.toString())) }
    var title by remember(uiState.diaryDate, uiState.title) { mutableStateOf(TextFieldValue(uiState.title)) }
    var body by remember(uiState.diaryDate, uiState.body) { mutableStateOf(TextFieldValue(uiState.body)) }
    var selectedWeather by remember(uiState.diaryDate, uiState.weather) { mutableStateOf(uiState.weather) }
    val imageUris = remember(uiState.diaryDate, uiState.imageUris) { mutableStateListOf<String>().apply { addAll(uiState.imageUris) } }
    val openDatePicker = {
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val pickedDate = LocalDate.of(year, month + 1, dayOfMonth)
                dateInput = TextFieldValue(pickedDate.toString())
                viewModel.loadDiary(pickedDate.toString())
            },
            uiState.diaryDate.year,
            uiState.diaryDate.monthValue - 1,
            uiState.diaryDate.dayOfMonth,
        ).show()
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { uri ->
            val imageToken = "\n![image](${uri})\n"
            val cursorPosition = body.selection.start
            val updatedText = body.text.substring(0, cursorPosition) + imageToken + body.text.substring(cursorPosition)
            val nextCursor = cursorPosition + imageToken.length
            body = TextFieldValue(text = updatedText, selection = TextRange(nextCursor))
            imageUris.add(uri.toString())
        }
    }
    val weatherOptions = listOf("\uB9D1\uC74C", "\uD750\uB9BC", "\uBE44", "\uB208", "\uBC14\uB78C")
    val dateFieldInteraction = remember { MutableInteractionSource() }

    LaunchedEffect(uiState.diaryDate) {
        viewModel.loadDiary(uiState.diaryDate.toString())
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(text = "\uC77C\uAE30\uC7A5", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = dateInput,
                        onValueChange = { },
                        readOnly = true,
                        label = { Text("\uC791\uC131 \uB0A0\uC9DC") },
                        modifier = Modifier.fillMaxWidth(),
                        interactionSource = dateFieldInteraction,
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable(onClick = openDatePicker),
                    )
                }
                Button(onClick = openDatePicker) { Text("\uB2EC\uB825") }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "\uB0A0\uC528")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    weatherOptions.take(3).forEach { weather -> AssistChip(onClick = { selectedWeather = weather }, label = { Text(weather) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    weatherOptions.drop(3).forEach { weather -> AssistChip(onClick = { selectedWeather = weather }, label = { Text(weather) }) }
                }
                Text(text = "\uC120\uD0DD\uB428: $selectedWeather", color = MaterialTheme.colorScheme.primary)
            }
        }
        item { OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("\uC81C\uBAA9") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("\uC77C\uAE30 \uB0B4\uC6A9") }, modifier = Modifier.fillMaxWidth(), minLines = 10) }
        item { Text(text = "\uCEF4\uC11C\uAC00 \uC788\uB294 \uC704\uCE58\uC5D0 \uC774\uBBF8\uC9C0 \uD1A0\uD070\uC744 \uB123\uC2B5\uB2C8\uB2E4.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        item { TextButton(onClick = { imagePicker.launch("image/*") }) { Text("\uC0AC\uC9C4 \uCCA8\uBD80") } }
        item { Button(onClick = { viewModel.saveDiary(dateInput.text, title.text, body.text, selectedWeather, imageUris.toList()) }, modifier = Modifier.fillMaxWidth()) { Text("\uC77C\uAE30 \uC800\uC7A5") } }
        item { uiState.statusMessage?.let { message -> Text(text = message, color = MaterialTheme.colorScheme.primary) } }
        item { Text(text = "\uCCA8\uBD80\uB41C \uC774\uBBF8\uC9C0", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(imageUris) { uri -> Card(shape = RoundedCornerShape(18.dp)) { Text(text = uri, modifier = Modifier.padding(16.dp)) } }
    }
}
