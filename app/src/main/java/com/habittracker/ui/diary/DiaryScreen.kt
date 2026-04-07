package com.habittracker.ui.diary

import android.app.DatePickerDialog
import android.net.Uri
import android.widget.ImageView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import java.time.LocalDate

private val imageTokenRegex = Regex("!\\[image]\\((.*?)\\)")

private data class WeatherOption(
    val label: String,
    val accentColor: Color,
)

@Composable
fun DiaryScreen(viewModel: DiaryViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var dateInput by remember(uiState.diaryDate) { mutableStateOf(TextFieldValue(uiState.diaryDate.toString())) }
    var title by remember(uiState.diaryDate, uiState.title) { mutableStateOf(TextFieldValue(uiState.title)) }
    var body by remember(uiState.diaryDate, uiState.body) { mutableStateOf(TextFieldValue(uiState.body)) }
    var searchQuery by remember(uiState.searchQuery) { mutableStateOf(TextFieldValue(uiState.searchQuery)) }
    var selectedWeather by remember(uiState.diaryDate, uiState.weather) { mutableStateOf(uiState.weather) }
    val imageUris = remember(uiState.diaryDate, uiState.imageUris) { mutableStateListOf<String>().apply { addAll(uiState.imageUris) } }
    var expandedImageUri by remember { mutableStateOf<String?>(null) }
    val weatherOptions = listOf(
        WeatherOption("맑음", Color(0xFFFFD36E)),
        WeatherOption("흐림", Color(0xFFD8DEE9)),
        WeatherOption("비", Color(0xFFB8D8FF)),
        WeatherOption("눈", Color(0xFFF2F7FF)),
        WeatherOption("바람", Color(0xFFD9F2E6)),
    )
    val currentWeatherOption = weatherOptions.firstOrNull { it.label == selectedWeather } ?: weatherOptions.first()

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

    val previewBlocks = remember(body.text) { parseDiaryBlocks(body.text) }

    LaunchedEffect(uiState.diaryDate) {
        viewModel.loadDiary(uiState.diaryDate.toString())
    }

    if (expandedImageUri != null) {
        Dialog(onDismissRequest = { expandedImageUri = null }) {
            Surface(shape = RoundedCornerShape(24.dp), color = Color.Black.copy(alpha = 0.92f)) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DiaryImage(uri = expandedImageUri.orEmpty(), modifier = Modifier.fillMaxWidth().height(420.dp))
                    Button(onClick = { expandedImageUri = null }, modifier = Modifier.fillMaxWidth()) {
                        Text("닫기")
                    }
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(text = "일기장", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(text = "일기 검색", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            viewModel.updateSearchQuery(it.text)
                        },
                        label = { Text("제목 또는 내용 검색") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Button(onClick = viewModel::searchDiaries, modifier = Modifier.fillMaxWidth()) {
                        Text("검색")
                    }
                    if (uiState.searchResults.isNotEmpty()) {
                        uiState.searchResults.forEach { result ->
                            Card(modifier = Modifier.fillMaxWidth().clickable {
                                dateInput = TextFieldValue(result.diaryDate.toString())
                                viewModel.openSearchResult(result.diaryDate)
                            }) {
                                Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(text = "${result.diaryDate} · ${result.weather}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    Text(text = result.title, fontWeight = FontWeight.Bold)
                                    Text(text = result.preview, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(value = dateInput, onValueChange = { }, readOnly = true, label = { Text("작성 날짜") }, modifier = Modifier.fillMaxWidth())
                    Box(modifier = Modifier.fillMaxSize().clickable(onClick = openDatePicker))
                }
                Button(onClick = openDatePicker) { Text("달력") }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = currentWeatherOption.accentColor.copy(alpha = 0.65f))) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(text = "날씨", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Box(modifier = Modifier.fillMaxWidth().height(88.dp).background(currentWeatherOption.accentColor, RoundedCornerShape(18.dp)), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Text(text = currentWeatherOption.label, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        weatherOptions.take(3).forEach { weather ->
                            AssistChip(onClick = { selectedWeather = weather.label }, label = { Text(weather.label) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        weatherOptions.drop(3).forEach { weather ->
                            AssistChip(onClick = { selectedWeather = weather.label }, label = { Text(weather.label) })
                        }
                    }
                }
            }
        }
        item { OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("제목") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("일기 내용") }, modifier = Modifier.fillMaxWidth(), minLines = 10) }
        item { Text(text = "커서가 있는 위치에 이미지를 넣고, 아래 미리보기에서 실제 이미지로 확인할 수 있습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        item { TextButton(onClick = { imagePicker.launch("image/*") }) { Text("사진 첨부") } }
        item { Button(onClick = { viewModel.saveDiary(dateInput.text, title.text, body.text, selectedWeather, imageUris.distinct()) }, modifier = Modifier.fillMaxWidth()) { Text("일기 저장") } }
        item { uiState.statusMessage?.let { message -> Text(text = message, color = MaterialTheme.colorScheme.primary) } }
        item { Text(text = "내용 미리보기", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(previewBlocks) { block ->
            when (block) {
                is DiaryBlock.TextBlock -> {
                    if (block.text.isNotBlank()) {
                        Card(shape = RoundedCornerShape(18.dp)) {
                            Text(text = block.text, modifier = Modifier.fillMaxWidth().padding(14.dp))
                        }
                    }
                }
                is DiaryBlock.ImageBlock -> {
                    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().clickable { expandedImageUri = block.uri }) {
                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            DiaryImage(uri = block.uri, modifier = Modifier.fillMaxWidth().height(220.dp))
                            Text(text = "이미지 크게 보기", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
        if (imageUris.isNotEmpty()) {
            item { Text(text = "첨부 이미지", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(imageUris.distinct()) { uri ->
                Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().clickable { expandedImageUri = uri }) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DiaryImage(uri = uri, modifier = Modifier.fillMaxWidth().height(200.dp))
                        Text(text = uri, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiaryImage(uri: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            ImageView(context).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
            }
        },
        update = { imageView ->
            imageView.setImageURI(Uri.parse(uri))
        },
    )
}

private sealed interface DiaryBlock {
    data class TextBlock(val text: String) : DiaryBlock
    data class ImageBlock(val uri: String) : DiaryBlock
}

private fun parseDiaryBlocks(body: String): List<DiaryBlock> {
    if (body.isBlank()) return emptyList()
    val blocks = mutableListOf<DiaryBlock>()
    var lastIndex = 0

    imageTokenRegex.findAll(body).forEach { match ->
        val text = body.substring(lastIndex, match.range.first).trim()
        if (text.isNotBlank()) {
            blocks += DiaryBlock.TextBlock(text)
        }
        val uri = match.groupValues.getOrNull(1).orEmpty()
        if (uri.isNotBlank()) {
            blocks += DiaryBlock.ImageBlock(uri)
        }
        lastIndex = match.range.last + 1
    }

    val trailingText = body.substring(lastIndex).trim()
    if (trailingText.isNotBlank()) {
        blocks += DiaryBlock.TextBlock(trailingText)
    }

    return blocks
}