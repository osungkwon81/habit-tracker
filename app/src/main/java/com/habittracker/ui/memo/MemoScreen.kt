package com.habittracker.ui.memo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.habittracker.data.local.entity.MemoNoteEntity
import com.habittracker.ui.components.AppButtonRow
import com.habittracker.ui.components.AppEmptyCard
import com.habittracker.ui.components.AppHeroCard
import com.habittracker.ui.components.AppNoticeDialog
import com.habittracker.ui.components.AppPrimaryButton
import com.habittracker.ui.components.AppScreen
import com.habittracker.ui.components.AppSecondaryButton
import com.habittracker.ui.components.AppSectionCard
import com.habittracker.ui.components.AppStatusText
import com.habittracker.ui.components.AppSupportText
import com.habittracker.ui.components.AppTextField
import com.habittracker.ui.components.actionNoticeDialogTitle
import com.habittracker.ui.components.shouldShowActionNoticeDialog
import java.time.format.DateTimeFormatter

@Composable
fun MemoScreen(viewModel: MemoViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var passwordDialogTarget by remember { mutableStateOf<MemoNoteEntity?>(null) }
    var unlockPassword by remember { mutableStateOf("") }
    var noticeMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uiState.statusMessage) {
        val message = uiState.statusMessage.orEmpty()
        if (message.shouldShowActionNoticeDialog()) {
            noticeMessage = message
        }
    }

    if (passwordDialogTarget != null) {
        AlertDialog(
            onDismissRequest = {
                passwordDialogTarget = null
                unlockPassword = ""
            },
            confirmButton = {
                AppPrimaryButton(text = "열기", onClick = {
                    viewModel.unlockMemo(passwordDialogTarget!!.id, unlockPassword)
                    passwordDialogTarget = null
                    unlockPassword = ""
                })
            },
            dismissButton = {
                AppButtonRow(
                    primaryText = "취소",
                    onPrimaryClick = {
                        passwordDialogTarget = null
                        unlockPassword = ""
                    },
                )
            },
            title = { Text("잠금 메모") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("이 메모는 잠겨 있습니다. 4자리 비밀번호를 입력해 주세요.")
                    AppTextField(
                        value = unlockPassword,
                        onValueChange = { unlockPassword = it.filter(Char::isDigit).take(4) },
                        label = "비밀번호 4자리",
                        singleLine = true,
                    )
                }
            },
        )
    }

    noticeMessage?.let { message ->
        AppNoticeDialog(
            message = message,
            onDismiss = { noticeMessage = null },
            title = message.actionNoticeDialogTitle(),
        )
    }

    if (uiState.screenMode == "editor") {
        MemoEditorScreen(viewModel = viewModel, uiState = uiState)
    } else {
        MemoListScreen(
            uiState = uiState,
            onSearchQueryChange = viewModel::updateSearchQuery,
            onNewMemo = viewModel::startNewMemo,
            onOpenMemo = { memoNote ->
                if (memoNote.isLocked) {
                    passwordDialogTarget = memoNote
                } else {
                    viewModel.openMemo(memoNote)
                }
            },
            onTogglePinned = viewModel::toggleMemoPinned,
            onLoadMore = viewModel::loadMoreMemoNotes,
        )
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
    AppScreen {
        item {
            AppHeroCard(
                title = "메모",
                description = null,
                action = {
                    AppPrimaryButton(text = "새 메모", onClick = onNewMemo, modifier = Modifier.fillMaxWidth())
                },
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
            item { AppEmptyCard("검색 결과가 없습니다.") }
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
    AppScreen {
        item {
            AppHeroCard(
                title = if (uiState.selectedMemoId == null) "메모 작성" else "메모 수정",
                description = null,
                action = {
                    AppButtonRow(primaryText = "목록으로", onPrimaryClick = viewModel::showList)
                },
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
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = uiState.isLocked, onCheckedChange = viewModel::updateLocked)
                    Text("잠금", color = MaterialTheme.colorScheme.onSurface)
                }
                if (uiState.isLocked) {
                    AppTextField(
                        value = uiState.password,
                        onValueChange = viewModel::updatePassword,
                        label = "비밀번호 4자리",
                        singleLine = true,
                    )
                    AppSupportText("잠금 메모는 저장 시 4자리 숫자 비밀번호가 필요합니다.")
                }
                AppPrimaryButton(text = "메모 저장", onClick = viewModel::saveMemo, modifier = Modifier.fillMaxWidth())
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
            AppSecondaryButton(
                text = if (memoNote.isPinned) "고정 해제" else "상단 고정",
                onClick = onTogglePinned,
            )
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
