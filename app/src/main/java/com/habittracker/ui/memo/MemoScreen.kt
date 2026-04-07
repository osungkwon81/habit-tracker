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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

@Composable
fun MemoScreen(viewModel: MemoViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var passwordDialogTarget by remember { mutableStateOf<MemoNoteEntity?>(null) }
    var unlockPassword by remember { mutableStateOf("") }

    if (passwordDialogTarget != null) {
        AlertDialog(
            onDismissRequest = {
                passwordDialogTarget = null
                unlockPassword = ""
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.unlockMemo(passwordDialogTarget!!.id, unlockPassword)
                    passwordDialogTarget = null
                    unlockPassword = ""
                }) {
                    Text("열기")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    passwordDialogTarget = null
                    unlockPassword = ""
                }) {
                    Text("취소")
                }
            },
            title = { Text("잠금 메모") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("이 메모는 잠겨 있습니다. 4자리 비밀번호를 입력해 주세요.")
                    OutlinedTextField(
                        value = unlockPassword,
                        onValueChange = { unlockPassword = it.filter(Char::isDigit).take(4) },
                        label = { Text("비밀번호 4자리") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                }
            },
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
    onLoadMore: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(text = "메모장", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Button(onClick = onNewMemo) {
                    Text("작성")
                }
            }
        }
        item {
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = onSearchQueryChange,
                label = { Text("제목 또는 내용 검색") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        uiState.statusMessage?.let { message ->
            item {
                Text(text = message, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (uiState.memoNotes.isEmpty()) {
            item {
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Text(text = "검색 결과가 없습니다.", modifier = Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            itemsIndexed(uiState.memoNotes, key = { _, memoNote -> memoNote.id }) { index, memoNote ->
                if (index == uiState.memoNotes.lastIndex && uiState.canLoadMore) {
                    LaunchedEffect(memoNote.id, uiState.visibleLimit, uiState.searchQuery) {
                        onLoadMore()
                    }
                }
                MemoListCard(memoNote = memoNote, onClick = { onOpenMemo(memoNote) })
            }
        }
    }
}

@Composable
private fun MemoEditorScreen(viewModel: MemoViewModel, uiState: MemoUiState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(text = if (uiState.selectedMemoId == null) "메모 작성" else "메모 수정", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                TextButton(onClick = viewModel::showList) {
                    Text("목록")
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = uiState.title,
                        onValueChange = viewModel::updateTitle,
                        label = { Text("제목") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = uiState.content,
                        onValueChange = viewModel::updateContent,
                        label = { Text("내용") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 10,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = uiState.isLocked, onCheckedChange = viewModel::updateLocked)
                        Text("잠금")
                    }
                    if (uiState.isLocked) {
                        OutlinedTextField(
                            value = uiState.password,
                            onValueChange = viewModel::updatePassword,
                            label = { Text("비밀번호 4자리") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                        )
                        Text(
                            text = "잠금 메모는 저장 시 4자리 숫자 비밀번호가 필수입니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = viewModel::saveMemo, modifier = Modifier.fillMaxWidth()) {
                        Text("메모 저장")
                    }
                    uiState.statusMessage?.let { message ->
                        Text(text = message, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoListCard(memoNote: MemoNoteEntity, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Text(
            text = memoNote.title,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}
