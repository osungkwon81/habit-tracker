package com.habittracker.ui.plant

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.habittracker.R
import com.habittracker.data.local.entity.PlantEntity
import com.habittracker.ui.components.AppActionNotice
import com.habittracker.ui.components.AppConfirmDialog
import com.habittracker.ui.components.AppEditButton
import com.habittracker.ui.components.AppEmptyCard
import com.habittracker.ui.components.AppHeroCard
import com.habittracker.ui.components.AppPrimaryButton
import com.habittracker.ui.components.AppSaveButton
import com.habittracker.ui.components.AppScreen
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppSecondaryButton
import com.habittracker.ui.components.AppStatusText
import com.habittracker.ui.components.AppSupportText
import com.habittracker.ui.components.AppTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.ceil

@Composable
fun PlantScreen(viewModel: PlantViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    AppActionNotice(uiState.statusMessage, viewModel::clearStatusMessage)
    when (uiState.screenMode) {
        PlantScreenMode.EDITOR -> PlantEditorScreen(viewModel = viewModel, uiState = uiState)
        PlantScreenMode.LIST -> PlantListScreen(viewModel = viewModel, uiState = uiState)
    }
}

@Composable
private fun PlantListScreen(viewModel: PlantViewModel, uiState: PlantUiState) {
    var deleteTarget by remember { mutableStateOf<PlantEntity?>(null) }

    deleteTarget?.let { plant ->
        AppConfirmDialog(
            title = "화분 삭제",
            message = "${plant.name} 정보를 삭제합니다.",
            confirmText = "삭제",
            onConfirm = {
                viewModel.deletePlant(plant.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }

    AppScreen {
        item {
            AppHeroCard(
                title = "화분 관리",
                description = "물주기 예정일과 완료 상태를 관리합니다.",
                iconRes = R.drawable.home_quick_plant,
                eyebrow = "LIFE · PLANT",
                action = {
                    AppPrimaryButton(
                        text = "화분 등록",
                        onClick = viewModel::startNewPlant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
            )
        }
        item {
            AppSectionCard {
                Text(
                    text = "오늘 물주기",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (uiState.duePlants.isEmpty()) {
                    AppEmptyCard("오늘 물줘야 할 화분이 없습니다.")
                } else {
                    uiState.duePlants.forEach { plant ->
                        DuePlantRow(
                            plant = plant,
                            onComplete = { viewModel.completeWatering(plant.id) },
                            onIncreaseInterval = { viewModel.increaseWateringIntervalOneDay(plant.id) },
                        )
                    }
                }
            }
        }
        item {
            AppSectionCard {
                Text(
                    text = "등록된 화분",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (uiState.plants.isEmpty()) {
                    AppEmptyCard("등록된 화분이 없습니다.")
                } else {
                    uiState.plants.forEach { plant ->
                        PlantCard(
                            plant = plant,
                            onEdit = { viewModel.openPlant(plant) },
                            onDelete = { deleteTarget = plant },
                        )
                    }
                }
                uiState.statusMessage?.let { message ->
                    AppStatusText(message)
                }
            }
        }
    }
}

@Composable
private fun PlantEditorScreen(viewModel: PlantViewModel, uiState: PlantUiState) {
    val context = LocalContext.current
    var expandedImageUri by remember { mutableStateOf<String?>(null) }
    val openDatePicker = {
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                viewModel.updateLastWateredDate(LocalDate.of(year, month + 1, dayOfMonth))
            },
            uiState.lastWateredDate.year,
            uiState.lastWateredDate.monthValue - 1,
            uiState.lastWateredDate.dayOfMonth,
        ).show()
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        viewModel.updateImageUri(copyPlantImageToAppStorage(context, uri) ?: uri.toString())
    }

    if (expandedImageUri != null) {
        Dialog(onDismissRequest = { expandedImageUri = null }) {
            Surface(color = Color.Black.copy(alpha = 0.92f)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(com.habittracker.ui.components.AppSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(com.habittracker.ui.components.AppSpacing.sm),
                ) {
                    PlantImage(
                        uri = expandedImageUri.orEmpty(),
                        maxSizePx = 1600,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(com.habittracker.ui.components.AppSpacing.lg * 10),
                    )
                    AppSecondaryButton(
                        text = "닫기",
                        onClick = { expandedImageUri = null },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    AppScreen {
        item {
            AppHeroCard(
                title = if (uiState.selectedPlantId == null) "화분 등록" else "화분 수정",
                description = "1개월은 30일 기준으로 다음 물주기 날짜를 계산합니다.",
                iconRes = R.drawable.home_quick_plant,
                eyebrow = "LIFE · PLANT",
                action = {
                    AppSecondaryButton(
                        text = "목록으로",
                        onClick = viewModel::showList,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
            )
        }
        item {
            AppSectionCard {
                AppTextField(
                    value = uiState.name,
                    onValueChange = viewModel::updateName,
                    label = "화분 이름",
                    singleLine = true,
                )
                AppTextField(
                    value = uiState.memo,
                    onValueChange = viewModel::updateMemo,
                    label = "특이 사항 메모",
                    minLines = 4,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(com.habittracker.ui.components.AppSpacing.xs),
                ) {
                    AppTextField(
                        value = uiState.wateringMonths,
                        onValueChange = viewModel::updateWateringMonths,
                        label = "개월",
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    AppTextField(
                        value = uiState.wateringDays,
                        onValueChange = viewModel::updateWateringDays,
                        label = "일",
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }
                AppSupportText("예: 1개월 20일은 50일 주기로 계산됩니다.")
                AppTextField(
                    value = uiState.lastWateredDate.toString(),
                    onValueChange = {},
                    label = "마지막 물준 날짜",
                    readOnly = true,
                    trailingOverlay = {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = com.habittracker.ui.components.AppSpacing.xs)
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = MaterialTheme.shapes.small,
                                )
                                .clickable(onClick = openDatePicker)
                                .padding(
                                    horizontal = com.habittracker.ui.components.AppSpacing.sm,
                                    vertical = com.habittracker.ui.components.AppSpacing.xs,
                                ),
                        ) {
                            Text("변경", color = MaterialTheme.colorScheme.onSurface)
                        }
                    },
                )
                AppTextField(
                    value = uiState.nextWateringDate.toString(),
                    onValueChange = {},
                    label = "다음 물주기 날짜",
                    readOnly = true,
                )
                AppPrimaryButton(
                    text = "화분 이미지 선택",
                    onClick = { imagePicker.launch(arrayOf("image/*")) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!uiState.imageUri.isNullOrBlank()) {
                    PlantImage(
                        uri = uiState.imageUri.orEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(com.habittracker.ui.components.AppSpacing.lg * 6)
                            .clickable { expandedImageUri = uiState.imageUri },
                    )
                    AppSecondaryButton(
                        text = "이미지 제거",
                        onClick = { viewModel.updateImageUri(null) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                AppSaveButton(
                    text = "화분 저장",
                    onClick = viewModel::savePlant,
                    modifier = Modifier.fillMaxWidth(),
                )
                uiState.statusMessage?.let { message ->
                    AppStatusText(message)
                }
            }
        }
    }
}

@Composable
private fun DuePlantRow(
    plant: PlantEntity,
    onComplete: () -> Unit,
    onIncreaseInterval: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
            .padding(com.habittracker.ui.components.AppSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(com.habittracker.ui.components.AppSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = false, onCheckedChange = { if (it) onComplete() })
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = plant.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "예정일 ${plant.nextWateringDate.format(DateTimeFormatter.ISO_LOCAL_DATE)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            onClick = onIncreaseInterval,
            modifier = Modifier.height(40.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
            ),
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+1일",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun PlantCard(
    plant: PlantEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    AppSectionCard {
        if (!plant.imageUri.isNullOrBlank()) {
            PlantImage(
                uri = plant.imageUri.orEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(com.habittracker.ui.components.AppSpacing.lg * 5),
            )
        }
        Text(
            text = plant.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "마지막 물준 날짜 ${plant.lastWateredDate}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "다음 물주기 ${plant.nextWateringDate} · 주기 ${plant.wateringIntervalDays}일",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!plant.memo.isNullOrBlank()) {
            Text(
                text = plant.memo,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(com.habittracker.ui.components.AppSpacing.xs),
        ) {
            AppEditButton(onClick = onEdit, modifier = Modifier.weight(1f))
            AppSecondaryButton(text = "삭제", onClick = onDelete, modifier = Modifier.weight(1f))
        }
    }
}

private fun copyPlantImageToAppStorage(context: android.content.Context, sourceUri: Uri): String? {
    return runCatching {
        val extension = when (context.contentResolver.getType(sourceUri)) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val plantImageDir = File(context.filesDir, "plant-images").apply { mkdirs() }
        val targetFile = File(plantImageDir, "${UUID.randomUUID()}.$extension")

        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: return null

        Uri.fromFile(targetFile).toString()
    }.getOrNull()
}

@Composable
private fun PlantImage(
    uri: String,
    maxSizePx: Int = 1024,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cacheKey = remember(uri, maxSizePx) { PlantImageCacheKey(uri, maxSizePx) }
    val bitmapState = produceState<Bitmap?>(
        initialValue = plantImageCache.get(cacheKey),
        key1 = cacheKey,
    ) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            plantImageCache.get(cacheKey) ?: runCatching {
                decodeSampledBitmap(context, Uri.parse(uri), maxSizePx)
            }.getOrNull()?.also { decodedBitmap ->
                plantImageCache.put(cacheKey, decodedBitmap)
            }
        }
    }

    val bitmap = bitmapState.value
    if (bitmap == null) {
        Card(
            modifier = modifier,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
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

private data class PlantImageCacheKey(
    val uri: String,
    val maxSizePx: Int,
)

private val plantImageCache = object : LruCache<PlantImageCacheKey, Bitmap>(16 * 1024) {
    override fun sizeOf(key: PlantImageCacheKey, value: Bitmap): Int =
        (value.allocationByteCount / 1024).coerceAtLeast(1)
}

private fun decodeSampledBitmap(context: android.content.Context, uri: Uri, maxSizePx: Int): Bitmap? {
    // EXIF는 디코딩 전에 한 번만 읽고, 축소된 Bitmap에 회전을 적용한다.
    val orientation = readExifOrientation(context, uri)
    resolveLocalImageFile(uri)?.let { file ->
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sampleSize = calculateInSampleSize(bounds, maxSizePx, maxSizePx)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        return applyExifOrientation(bitmap, orientation)
    }

    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    openImageInputStream(context, uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
    } ?: return null

    val sampleSize = calculateInSampleSize(bounds, maxSizePx, maxSizePx)
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
    }
    val bitmap = openImageInputStream(context, uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, options)
    } ?: return null
    return applyExifOrientation(bitmap, orientation)
}

/**
 * 사진 픽셀은 그대로 두고 회전 방향만 EXIF 메타데이터에 기록하는 카메라가 많다.
 * 디코딩한 Bitmap에 그 방향을 적용해야 세로 사진이 옆으로 눕지 않는다.
 */
private fun readExifOrientation(context: android.content.Context, uri: Uri): Int {
    return runCatching {
        resolveLocalImageFile(uri)?.let { file ->
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: openImageInputStream(context, uri)?.use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
}

private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
            matrix.setRotate(180f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(-90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        else -> return bitmap
    }

    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun resolveLocalImageFile(uri: Uri): File? {
    return when (uri.scheme) {
        "file" -> uri.path?.let(::File)
        null -> uri.toString().takeIf(String::isNotBlank)?.let(::File)
        else -> null
    }?.takeIf(File::exists)
}

private fun openImageInputStream(
    context: android.content.Context,
    uri: Uri,
): java.io.InputStream? {
    return when (uri.scheme) {
        "file" -> resolveLocalImageFile(uri)?.inputStream()
        null -> resolveLocalImageFile(uri)?.inputStream()
        else -> context.contentResolver.openInputStream(uri)
    }
}

private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val height = options.outHeight
    val width = options.outWidth
    if (height <= 0 || width <= 0) return 1

    // 두 변이 모두 클 때만 축소하던 기존 방식과 달리, 긴 변 하나라도 목표 크기를 넘으면 줄인다.
    val requiredSample = maxOf(
        ceil(width.toDouble() / reqWidth.coerceAtLeast(1)).toInt(),
        ceil(height.toDouble() / reqHeight.coerceAtLeast(1)).toInt(),
        1,
    )
    var sampleSize = 1
    while (sampleSize < requiredSample) {
        sampleSize *= 2
    }
    return sampleSize
}
