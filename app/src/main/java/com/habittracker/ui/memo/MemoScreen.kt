package com.habittracker.ui.memo

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.fingerprint.FingerprintManager
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.os.CancellationSignal
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.habittracker.R
import com.habittracker.data.local.entity.MemoNoteEntity
import com.habittracker.ui.components.AppActionNotice
import com.habittracker.ui.components.AppButtonRow
import com.habittracker.ui.components.AppEmptyCard
import com.habittracker.ui.components.AppHeroCard
import com.habittracker.ui.components.AppPrimaryButton
import com.habittracker.ui.components.AppSaveButton
import com.habittracker.ui.components.AppSecondaryButton
import com.habittracker.ui.components.AppScreen
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppStatusText
import com.habittracker.ui.components.AppSupportText
import com.habittracker.ui.components.AppTextField
import com.habittracker.ui.components.AppVoiceInputButton
import java.time.format.DateTimeFormatter

@Composable
fun MemoScreen(viewModel: MemoViewModel, startNew: Boolean = false) {
    LaunchedEffect(viewModel, startNew) {
        if (startNew) viewModel.initializeNewEntry()
    }
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var fingerprintTarget by remember { mutableStateOf<MemoNoteEntity?>(null) }
    var fingerprintMessage by remember { mutableStateOf<String?>(null) }
    var authenticationAttempt by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    AppActionNotice(uiState.statusMessage, viewModel::clearStatusMessage)

    fingerprintTarget?.let { targetMemo ->
        DisposableEffect(targetMemo.id, authenticationAttempt, lifecycleOwner) {
            val signal = CancellationSignal()
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    signal.cancel()
                    fingerprintTarget = null
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            fingerprintMessage = "지문 센서에 손가락을 대 주세요."
            authenticateMemoFingerprint(
                context = context,
                signal = signal,
                onSuccess = {
                    if (!signal.isCanceled && fingerprintTarget?.id == targetMemo.id) {
                        fingerprintTarget = null
                        viewModel.unlockMemoWithBiometric(targetMemo.id)
                    }
                },
                onError = { if (!signal.isCanceled) fingerprintMessage = it },
            )
            onDispose {
                signal.cancel()
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
        AlertDialog(
            onDismissRequest = { fingerprintTarget = null },
            title = { Text("지문으로 메모 열기") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("등록된 지문으로만 잠금을 해제할 수 있습니다.")
                    fingerprintMessage?.let { AppStatusText(it) }
                }
            },
            confirmButton = {
                TextButton(onClick = { authenticationAttempt++ }) { Text("다시 인증") }
            },
            dismissButton = {
                TextButton(onClick = { fingerprintTarget = null }) { Text("취소") }
            },
        )
    }

    if (uiState.screenMode == MemoScreenMode.EDITOR) {
        MemoEditorScreen(viewModel = viewModel, uiState = uiState)
    } else {
        MemoListScreen(
            uiState = uiState,
            onSearchQueryChange = viewModel::updateSearchQuery,
            onNewMemo = viewModel::startNewMemo,
            onOpenMemo = { memoNote ->
                if (memoNote.isLocked) {
                    fingerprintTarget = memoNote
                } else {
                    viewModel.openMemo(memoNote)
                }
            },
            onTogglePinned = viewModel::toggleMemoPinned,
            onLoadMore = viewModel::loadMoreMemoNotes,
        )
    }
}

@Suppress("DEPRECATION")
private fun memoFingerprintUnavailableReason(context: Context): String? {
    val manager = context.getSystemService(FingerprintManager::class.java)
    return when {
        !context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) ||
            manager == null || !manager.isHardwareDetected -> "지문 인식을 지원하지 않아 잠금 메모를 사용할 수 없습니다."
        !manager.hasEnrolledFingerprints() -> "기기 설정에서 지문을 등록해 주세요."
        else -> null
    }
}

@Suppress("DEPRECATION")
private fun authenticateMemoFingerprint(
    context: Context,
    signal: CancellationSignal,
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
) {
    try {
        val unavailableReason = memoFingerprintUnavailableReason(context)
        if (unavailableReason != null) {
            onError(unavailableReason)
            return
        }
        val manager = context.getSystemService(FingerprintManager::class.java)
        if (manager == null) {
            onError("지문 인증을 시작할 수 없습니다. 기기 설정을 확인해 주세요.")
            return
        }
        manager.authenticate(null, signal, 0, object : FingerprintManager.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: FingerprintManager.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationFailed() {
                onError("지문이 일치하지 않습니다. 센서에 다시 손가락을 대 주세요.")
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != FingerprintManager.FINGERPRINT_ERROR_CANCELED) {
                    onError("지문 인증에 실패했습니다: $errString")
                }
            }

            override fun onAuthenticationHelp(helpCode: Int, helpString: CharSequence) {
                onError(helpString.toString())
            }
        }, null)
    } catch (error: Exception) {
        android.util.Log.e("MemoFingerprint", "지문 인증 시작 실패", error)
        onError("지문 인증을 시작할 수 없습니다. 기기 설정을 확인해 주세요.")
    }
}

@Composable
private fun MemoListScreen(
    uiState: MemoUiState,
    onSearchQueryChange: (String) -> Unit,
    onNewMemo: () -> Unit,
    onOpenMemo: (MemoNoteEntity) -> Unit,
    onTogglePinned: (MemoNoteEntity) -> Unit,
    onLoadMore: () -> Unit,
) {
    AppScreen(bottomBar = {
        AppPrimaryButton(text = "새 메모", onClick = onNewMemo, modifier = Modifier.fillMaxWidth())
    }) {
        item {
            AppHeroCard(
                title = "메모",
            )
        }
        item {
            AppSectionCard {
                AppTextField(
                    value = uiState.searchQuery,
                    onValueChange = onSearchQueryChange,
                    label = "제목 또는 내용 검색",
                    singleLine = true,
                )
            }
        }
        uiState.statusMessage?.let { message ->
            item { AppStatusText(message) }
        }
        if (uiState.memoNotes.isEmpty()) {
            item { AppEmptyCard(if (uiState.searchQuery.isBlank()) "저장된 메모가 없습니다." else "검색 결과가 없습니다.") }
        } else {
            itemsIndexed(uiState.memoNotes, key = { _, memoNote -> memoNote.id }) { index, memoNote ->
                if (index == uiState.memoNotes.lastIndex && uiState.canLoadMore) {
                    LaunchedEffect(memoNote.id, uiState.visibleLimit, uiState.searchQuery) {
                        onLoadMore()
                    }
                }
                MemoListCard(memoNote = memoNote, onClick = { onOpenMemo(memoNote) }, onTogglePinned = { onTogglePinned(memoNote) })
            }
        }
    }
}

@Composable
private fun MemoEditorScreen(viewModel: MemoViewModel, uiState: MemoUiState) {
    val context = LocalContext.current
    var lockMessage by remember { mutableStateOf<String?>(null) }
    var speechStatusMessage by remember { mutableStateOf<String?>(null) }

    AppScreen(bottomBar = {
        AppButtonRow(
            primaryText = "저장",
            onPrimaryClick = viewModel::saveMemo,
            secondaryText = "목록",
            onSecondaryClick = viewModel::showList,
        )
    }) {
        item {
            AppHeroCard(
                title = if (uiState.selectedMemoId == null) "메모 작성" else "메모 수정",
            )
        }
        item {
            AppSectionCard {
                AppTextField(
                    value = uiState.title,
                    onValueChange = viewModel::updateTitle,
                    label = "제목",
                    singleLine = true,
                )
                AppTextField(
                    value = uiState.content,
                    onValueChange = viewModel::updateContent,
                    label = "내용",
                    minLines = 10,
                    trailingContent = {
                        AppVoiceInputButton(
                            onRecognizedText = viewModel::appendRecognizedContent,
                            onStatusMessage = { speechStatusMessage = it },
                        )
                    },
                    supportingText = speechStatusMessage,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = uiState.isLocked, onCheckedChange = { locked ->
                        lockMessage = if (locked) memoFingerprintUnavailableReason(context) else null
                        if (lockMessage == null) viewModel.updateLocked(locked)
                    })
                    Text("잠금", color = MaterialTheme.colorScheme.onSurface)
                }
                AppSupportText("잠금 메모는 등록된 지문으로만 열 수 있습니다. 비밀번호는 사용하지 않습니다.")
                lockMessage?.let { AppStatusText(it) }
                if (uiState.selectedMemoId != null) {
                    androidx.compose.material3.TextButton(onClick = viewModel::deleteMemo) {
                        Text("메모 삭제", color = MaterialTheme.colorScheme.error)
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
private fun MemoListCard(memoNote: MemoNoteEntity, onClick: () -> Unit, onTogglePinned: () -> Unit) {
    val previewText = if (memoNote.isLocked) {
        "잠금 해제 후 내용을 볼 수 있습니다."
    } else {
        memoNote.content.lineSequence().firstOrNull().orEmpty().ifBlank { "내용 없음" }
    }

    AppSectionCard(
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = memoNote.title.ifBlank { "제목 없음" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Surface(
                onClick = onTogglePinned,
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = if (memoNote.isPinned) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (memoNote.isPinned) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                border = BorderStroke(
                    width = 1.dp,
                    color = if (memoNote.isPinned) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                ),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_push_pin),
                        contentDescription = if (memoNote.isPinned) "상단 고정 해제" else "상단 고정",
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(if (memoNote.isPinned) 0f else 35f),
                    )
                }
            }
        }
        Text(
            text = previewText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
        Text(
            text = memoNote.updatedAt.format(DateTimeFormatter.ofPattern("M월 d일 HH:mm")),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (memoNote.isPinned) {
            Text(
                text = "상단 고정",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (memoNote.isLocked) {
            Text(
                text = "잠금 메모",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
