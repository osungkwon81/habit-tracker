package com.habittracker.ui.diary

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.habittracker.ui.components.AppButtonRow
import com.habittracker.ui.components.AppEmptyCard
import com.habittracker.ui.components.AppHeroCard
import com.habittracker.ui.components.AppNoticeDialog
import com.habittracker.ui.components.AppPrimaryButton
import com.habittracker.ui.components.AppScreen
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppSecondaryButton
import com.habittracker.ui.components.AppSelectableChip
import com.habittracker.ui.components.AppStatusText
import com.habittracker.ui.components.AppSupportText
import com.habittracker.ui.components.AppTextField
import com.habittracker.ui.components.actionNoticeDialogTitle
import com.habittracker.ui.components.shouldShowActionNoticeDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private data class WeatherOption(
    val label: String,
)

private val DiaryTitleCardColor = Color(0xFFFFFFFF)
private val DiaryBodyCardColor = Color(0xFFF7F8F7)
private val DiaryHeroColor = Color(0xFFFFFFFF)
private val DiaryHeroSubColor = Color(0xFF5C6661)
private val DiaryTextStrongColor = Color(0xFF171C19)
private val DiaryTextMutedColor = Color(0xFF5C6661)
private const val DiaryImageTokenPrefix = "![image]("
private const val DiaryImageTokenSuffix = ")"

@Composable
fun DiaryScreen(viewModel: DiaryViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    when (uiState.screenMode) {
        "editor" -> DiaryEditorScreen(viewModel = viewModel, uiState = uiState)
        "detail" -> DiaryDetailScreen(viewModel = viewModel, uiState = uiState)
        else -> DiaryListScreen(viewModel = viewModel, uiState = uiState)
    }
}

@Composable
private fun DiaryListScreen(viewModel: DiaryViewModel, uiState: DiaryUiState) {
    AppScreen {
        item {
            AppHeroCard(
                title = "일기",
                description = null,
                action = {
                    AppPrimaryButton(text = "새 일기", onClick = viewModel::startNewDiary, modifier = Modifier.fillMaxWidth())
                },
            )
        }
        item {
            AppSectionCard {
                AppTextField(
                    value = uiState.searchQuery,
                    onValueChange = viewModel::updateSearchQuery,
                    label = "제목 또는 내용 검색",
                    singleLine = true,
                )
            }
        }
        item {
            uiState.statusMessage?.let { message ->
                AppStatusText(message)
            }
        }
        if (uiState.searchResults.isEmpty()) {
            item { AppEmptyCard("저장된 일기가 없습니다.") }
        } else {
            items(uiState.searchResults, key = { it.diaryDate }) { result ->
                AppSectionCard(modifier = Modifier.clickable { viewModel.openSearchResult(result.diaryDate) }) {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = result.title.ifBlank { "무제" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(text = result.preview.ifBlank { "본문 미리보기 없음" }, style = MaterialTheme.typography.bodyMedium, color = DiaryTextMutedColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = "${result.diaryDate} · ${weatherEmoji(result.weather)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = DiaryTextMutedColor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiaryEditorScreen(viewModel: DiaryViewModel, uiState: DiaryUiState) {
    val context = LocalContext.current
    var dateInput by remember(uiState.diaryDate) { mutableStateOf(TextFieldValue(uiState.diaryDate.toString())) }
    var title by remember(uiState.diaryDate, uiState.title) { mutableStateOf(TextFieldValue(uiState.title)) }
    var body by remember(uiState.diaryDate, uiState.body) { mutableStateOf(TextFieldValue(uiState.body)) }
    var selectedWeather by remember(uiState.diaryDate, uiState.weather) { mutableStateOf(uiState.weather) }
    val imageUris = remember(uiState.diaryDate, uiState.imageUris) { mutableStateListOf<String>().apply { addAll(uiState.imageUris) } }
    var expandedImageUri by remember { mutableStateOf<String?>(null) }
    val weatherOptions = listOf(
        WeatherOption("맑음"),
        WeatherOption("흐림"),
        WeatherOption("비"),
        WeatherOption("눈"),
        WeatherOption("바람"),
    )
    var noticeMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uiState.statusMessage) {
        val message = uiState.statusMessage.orEmpty()
        if (message.shouldShowActionNoticeDialog()) {
            noticeMessage = message
        }
    }

    val openDatePicker = {
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val pickedDate = java.time.LocalDate.of(year, month + 1, dayOfMonth)
                dateInput = TextFieldValue(pickedDate.toString())
                viewModel.loadDiary(pickedDate.toString())
            },
            uiState.diaryDate.year,
            uiState.diaryDate.monthValue - 1,
            uiState.diaryDate.dayOfMonth,
        ).show()
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val storedUri = copyDiaryImageToAppStorage(context, uri) ?: uri.toString()
            val imageToken = "\n$DiaryImageTokenPrefix$storedUri$DiaryImageTokenSuffix\n"
            val cursorPosition = body.selection.start
            val updatedText = body.text.substring(0, cursorPosition) + imageToken + body.text.substring(cursorPosition)
            val nextCursor = cursorPosition + imageToken.length
            body = TextFieldValue(text = updatedText, selection = TextRange(nextCursor))
            imageUris.add(storedUri)
        }
    }

    if (expandedImageUri != null) {
        Dialog(onDismissRequest = { expandedImageUri = null }) {
            Surface(shape = RoundedCornerShape(24.dp), color = Color.Black.copy(alpha = 0.92f)) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DiaryImage(uri = expandedImageUri.orEmpty(), modifier = Modifier.fillMaxWidth().height(420.dp))
                    AppSecondaryButton(text = "닫기", onClick = { expandedImageUri = null }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    noticeMessage?.let { message ->
        AppNoticeDialog(
            message = message,
            onDismiss = { noticeMessage = null },
            title = message.actionNoticeDialogTitle(),
        )
    }

    AppScreen {
        item {
            AppHeroCard(
                title = "일기 작성",
                description = null,
                action = {
                    AppSecondaryButton(text = "목록으로", onClick = viewModel::showList, modifier = Modifier.fillMaxWidth())
                },
            )
        }
        item {
            AppSectionCard {
                AppTextField(
                    value = dateInput.text,
                    onValueChange = { },
                    label = "작성 날짜",
                    readOnly = true,
                    singleLine = true,
                    trailingOverlay = {
                        Box(modifier = Modifier.matchParentSize().clickable(onClick = openDatePicker))
                    },
                )
            }
        }
        item {
            AppSectionCard {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "날씨",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        weatherOptions.forEach { weather ->
                            WeatherTextCard(weather = weather, selected = weather.label == selectedWeather, onClick = { selectedWeather = weather.label }, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        item { AppTextField(value = title.text, onValueChange = { title = TextFieldValue(it) }, label = "제목", singleLine = true) }
        item { AppTextField(value = body.text, onValueChange = { body = TextFieldValue(it) }, label = "일기 내용", minLines = 10) }
        item { AppSecondaryButton(text = "사진 첨부", onClick = { imagePicker.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) }
        item { AppPrimaryButton(text = "일기 저장", onClick = { viewModel.saveDiary(dateInput.text, title.text, body.text, selectedWeather, imageUris.distinct()) }, modifier = Modifier.fillMaxWidth()) }
        item { uiState.statusMessage?.let { message -> AppStatusText(message) } }
        if (imageUris.isNotEmpty()) {
            item { Text(text = "첨부 이미지", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(imageUris.distinct()) { uri ->
                AppSectionCard(modifier = Modifier.clickable { expandedImageUri = uri }) {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DiaryImage(uri = uri, modifier = Modifier.fillMaxWidth().height(200.dp))
                        Text(text = "이미지 크게 보기", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiaryDetailScreen(viewModel: DiaryViewModel, uiState: DiaryUiState) {
    var expandedImageUri by remember { mutableStateOf<String?>(null) }

    if (expandedImageUri != null) {
        Dialog(onDismissRequest = { expandedImageUri = null }) {
            Surface(shape = RoundedCornerShape(24.dp), color = Color.Black.copy(alpha = 0.92f)) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DiaryImage(uri = expandedImageUri.orEmpty(), modifier = Modifier.fillMaxWidth().height(420.dp))
                    AppSecondaryButton(text = "닫기", onClick = { expandedImageUri = null }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    AppScreen {
        item {
            AppHeroCard(
                title = "일기 상세",
                description = null,
            )
        }
        item {
            AppButtonRow(primaryText = "수정", onPrimaryClick = viewModel::editCurrentDiary, secondaryText = "목록", onSecondaryClick = viewModel::showList)
        }
        item {
            AppSectionCard {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = uiState.title.ifBlank { "무제" }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = DiaryTextStrongColor)
                    Text(
                        text = "${uiState.diaryDate} · ${uiState.weather}",
                        color = DiaryTextMutedColor,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        item {
            DiaryContentPreview(
                body = uiState.body,
                onImageClick = { uri -> expandedImageUri = uri },
            )
        }
    }
}

@Composable
private fun WeatherTextCard(weather: WeatherOption, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = weather.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun weatherEmoji(weather: String): String {
    return when (weather) {
        "맑음" -> "☀️"
        "흐림" -> "☁️"
        "비" -> "🌧️"
        "눈" -> "❄️"
        "바람" -> "🌬️"
        else -> "☀️"
    }
}

@Composable
private fun DiaryContentPreview(
    body: String,
    onImageClick: (String) -> Unit,
) {
    val blocks = remember(body) {
        runCatching { parseDiaryContentBlocks(body) }.getOrDefault(listOf(DiaryContentBlock.TextBlock(body)))
    }

    AppSectionCard {
        Column(
            modifier = Modifier
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (blocks.isEmpty()) {
                Text(
                    text = "아직 본문 내용이 없습니다.",
                    color = DiaryTextMutedColor,
                )
            } else {
                blocks.forEach { block ->
                    when (block) {
                        is DiaryContentBlock.TextBlock -> {
                            Text(
                                text = block.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = DiaryTextStrongColor,
                            )
                        }

                        is DiaryContentBlock.ImageBlock -> {
                            AppSectionCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onImageClick(block.uri) },
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    DiaryImage(
                                        uri = block.uri,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(220.dp),
                                    )
                                    Text(
                                        text = "이미지 크게 보기",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private sealed interface DiaryContentBlock {
    data class TextBlock(val text: String) : DiaryContentBlock
    data class ImageBlock(val uri: String) : DiaryContentBlock
}

private fun parseDiaryContentBlocks(body: String): List<DiaryContentBlock> {
    if (body.isBlank()) return emptyList()
    val blocks = mutableListOf<DiaryContentBlock>()
    val textBuffer = mutableListOf<String>()

    body.lineSequence().forEach { rawLine ->
        val line = rawLine.trimEnd()
        if (line.startsWith(DiaryImageTokenPrefix) && line.endsWith(DiaryImageTokenSuffix)) {
            val uri = line
                .removePrefix(DiaryImageTokenPrefix)
                .removeSuffix(DiaryImageTokenSuffix)
                .trim()

            if (textBuffer.isNotEmpty()) {
                blocks += DiaryContentBlock.TextBlock(textBuffer.joinToString("\n").trim())
                textBuffer.clear()
            }

            if (uri.isNotBlank()) {
                blocks += DiaryContentBlock.ImageBlock(uri)
            }
        } else {
            textBuffer += rawLine
        }
    }

    if (textBuffer.isNotEmpty()) {
        val text = textBuffer.joinToString("\n").trim()
        if (text.isNotBlank()) {
            blocks += DiaryContentBlock.TextBlock(text)
        }
    }

    return blocks
}

private fun copyDiaryImageToAppStorage(context: android.content.Context, sourceUri: Uri): String? {
    return runCatching {
        val extension = when (context.contentResolver.getType(sourceUri)) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val diaryImageDir = File(context.filesDir, "diary-images").apply { mkdirs() }
        val targetFile = File(diaryImageDir, "${UUID.randomUUID()}.$extension")

        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: return null

        Uri.fromFile(targetFile).toString()
    }.getOrNull()
}

@Composable
private fun DiaryImage(uri: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmapState = produceState<android.graphics.Bitmap?>(initialValue = null, key1 = uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use(BitmapFactory::decodeStream)
            }.getOrNull()
        }
    }

    val bitmap = bitmapState.value
    if (bitmap == null) {
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "이미지를 불러올 수 없습니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        modifier = modifier,
    )
}
