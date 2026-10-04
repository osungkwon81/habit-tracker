package com.habittracker.ui.reminder

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.habittracker.data.local.entity.ReminderEntity
import com.habittracker.ui.components.AppActionNotice
import com.habittracker.ui.components.AppConfirmDialog
import com.habittracker.ui.components.AppEmptyCard
import com.habittracker.ui.components.AppHeroCard
import com.habittracker.ui.components.AppPrimaryButton
import com.habittracker.ui.components.AppSaveButton
import com.habittracker.ui.components.AppScreen
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppSectionHeader
import com.habittracker.ui.components.AppSecondaryButton
import com.habittracker.ui.components.AppSpacing
import com.habittracker.ui.components.AppSupportText
import com.habittracker.ui.components.AppTextField
import com.habittracker.ui.components.LocalAppNavigationGuard
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun ReminderScreen(viewModel: ReminderViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    AppActionNotice(uiState.message, viewModel::clearMessage)
    when (uiState.screenMode) {
        ReminderScreenMode.LIST -> ReminderListScreen(viewModel, uiState)
        ReminderScreenMode.EDITOR -> ReminderEditorScreen(viewModel, uiState)
    }
}

@Composable
private fun ReminderEditorScreen(viewModel: ReminderViewModel, uiState: ReminderUiState) {
    val context = LocalContext.current
    val title by viewModel.title.collectAsStateWithLifecycle()
    val dateValue by viewModel.date.collectAsStateWithLifecycle()
    val timeValue by viewModel.time.collectAsStateWithLifecycle()
    val intervalMinutes by viewModel.intervalMinutes.collectAsStateWithLifecycle()
    val date = remember(dateValue) { LocalDate.parse(dateValue) }
    val time = remember(timeValue) { LocalTime.parse(timeValue) }
    val navigationGuard = LocalAppNavigationGuard.current
    val hasUnsavedChanges = title.isNotBlank() || intervalMinutes.isNotBlank()
    var notificationPermissionGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationPermissionGranted = granted
        if (granted) viewModel.restoreSchedules()
    }

    SideEffect { navigationGuard.hasUnsavedChanges = hasUnsavedChanges }
    DisposableEffect(Unit) { onDispose { navigationGuard.hasUnsavedChanges = false } }
    BackHandler { navigationGuard.navigate(viewModel::closeEditor) }

    val remindAt = LocalDateTime.of(date, time)
    val canSave = title.isNotBlank() &&
        (intervalMinutes.toIntOrNull() ?: 0) > 0 &&
        remindAt.isAfter(LocalDateTime.now()) &&
        !uiState.isSaving

    AppScreen {
        item {
            AppHeroCard(
                title = "리마인더 설정",
                description = "첫 알림과 완료 전 재알림 간격을 설정합니다.",
            )
        }
        if (!notificationPermissionGranted) {
            item {
                AppSectionCard {
                    AppSupportText("푸시 알림을 받으려면 시스템 알림 권한이 필요합니다.")
                    AppPrimaryButton(
                        text = "알림 권한 허용",
                        onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        item {
            AppSectionCard {
                AppTextField(
                    value = title,
                    onValueChange = viewModel::updateTitle,
                    label = "알림 내용",
                    singleLine = true,
                )
                Text("첫 알림 시작", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                AppSupportText("선택한 날짜와 시간부터 첫 알림을 보냅니다.")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                ) {
                    AppSecondaryButton(
                        text = date.format(DateTimeFormatter.ofPattern("yyyy년 M월 d일")),
                        onClick = {
                            DatePickerDialog(
                                context,
                                { _, year, month, day -> viewModel.updateDate(LocalDate.of(year, month + 1, day)) },
                                date.year,
                                date.monthValue - 1,
                                date.dayOfMonth,
                            ).show()
                        },
                        modifier = Modifier.weight(1f),
                    )
                    AppSecondaryButton(
                        text = time.format(DateTimeFormatter.ofPattern("HH:mm")),
                        onClick = {
                            TimePickerDialog(
                                context,
                                { _, hour, minute -> viewModel.updateTime(hour, minute) },
                                time.hour,
                                time.minute,
                                true,
                            ).show()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                AppSupportText("첫 알림을 보낼 시간과 분을 선택합니다.")
                AppTextField(
                    value = intervalMinutes,
                    onValueChange = viewModel::updateIntervalMinutes,
                    label = "재알림 간격(분)",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = intervalMinutes.isNotBlank() && (intervalMinutes.toIntOrNull() ?: 0) <= 0,
                    supportingText = "1분 이상의 숫자를 입력해 주세요.",
                )
                AppSupportText("알림창에 같은 리마인더가 남아 있으면 다시 보내지 않습니다. 알림을 지워도 완료 전이면 설정한 간격 후 다시 알립니다.")
                if (!remindAt.isAfter(LocalDateTime.now())) {
                    Text("현재 이후의 날짜와 시간을 선택해 주세요.", color = MaterialTheme.colorScheme.error)
                }
                AppSaveButton(
                    onClick = viewModel::save,
                    text = if (uiState.isSaving) "저장 중" else "리마인더 저장",
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth(),
                )
                AppSecondaryButton(
                    text = "취소",
                    onClick = { navigationGuard.navigate(viewModel::closeEditor) },
                    enabled = !uiState.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ReminderListScreen(viewModel: ReminderViewModel, uiState: ReminderUiState) {
    var deleteTarget by remember { mutableStateOf<ReminderEntity?>(null) }
    deleteTarget?.let { reminder ->
        AppConfirmDialog(
            title = "리마인더 삭제",
            message = "${reminder.title} 리마인더를 삭제합니다.",
            confirmText = "삭제",
            onConfirm = {
                viewModel.delete(reminder.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }

    val activeReminders = uiState.reminders.filter { it.completedAt == null }
    val completedReminders = uiState.reminders.filter { it.completedAt != null }
    AppScreen {
        item {
            AppHeroCard(
                title = "리마인더",
                description = "진행 중인 알림을 확인하고 완료 체크합니다.",
                action = {
                    AppPrimaryButton(
                        text = "새 리마인더 설정",
                        onClick = viewModel::startEditor,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
            )
        }
        item { AppSectionHeader(title = "진행 중") }
        if (activeReminders.isEmpty()) item { AppEmptyCard("진행 중인 리마인더가 없습니다.") }
        activeReminders.forEach { reminder ->
            item(key = "active:${reminder.id}") {
                ReminderRow(
                    reminder = reminder,
                    enabled = reminder.id !in uiState.busyIds,
                    onComplete = { viewModel.complete(reminder.id) },
                    onDelete = { deleteTarget = reminder },
                )
            }
        }
        if (completedReminders.isNotEmpty()) {
            item { AppSectionHeader(title = "완료") }
            completedReminders.forEach { reminder ->
                item(key = "completed:${reminder.id}") {
                    ReminderRow(
                        reminder = reminder,
                        enabled = reminder.id !in uiState.busyIds,
                        onComplete = {},
                        onDelete = { deleteTarget = reminder },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReminderRow(
    reminder: ReminderEntity,
    enabled: Boolean,
    onComplete: () -> Unit,
    onDelete: () -> Unit,
) {
    AppSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = reminder.completedAt != null,
                onCheckedChange = { checked -> if (checked) onComplete() },
                enabled = enabled && reminder.completedAt == null,
            )
            Column(modifier = Modifier.weight(1f).padding(start = AppSpacing.xs)) {
                Text(reminder.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${reminder.remindAt.format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm"))} · ${reminder.repeatIntervalMinutes}분 간격",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        AppSecondaryButton(
            text = "삭제",
            onClick = onDelete,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
