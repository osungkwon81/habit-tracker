package com.habittracker.ui.memo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.habittracker.data.local.entity.MemoNoteEntity
import com.habittracker.data.repository.HabitRepository
import com.habittracker.ui.digitsOnly
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val memoPageSize = 10
private const val memoSearchDebounceMillis = 300L

/** 목록과 편집 화면에서 허용하는 상태만 표현한다. */
enum class MemoScreenMode {
    LIST,
    EDITOR,
}

private data class MemoEditorState(
    val memoId: Long?,
    val title: String,
    val content: String,
    val isLocked: Boolean,
    val password: String,
)

private data class MemoListState(
    val memoNotes: List<MemoNoteEntity>,
    val searchQuery: String,
    val visibleLimit: Int,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MemoViewModel(
    private val repository: HabitRepository,
) : ViewModel() {
    private val selectedMemoId = MutableStateFlow<Long?>(null)
    private val title = MutableStateFlow("")
    private val content = MutableStateFlow("")
    private val isLocked = MutableStateFlow(false)
    private val password = MutableStateFlow("")
    private val statusMessage = MutableStateFlow<String?>(null)
    private val screenMode = MutableStateFlow(MemoScreenMode.LIST)
    private val searchQuery = MutableStateFlow("")
    private val visibleLimit = MutableStateFlow(memoPageSize)

    private val memoNotesFlow = combine(searchQuery, visibleLimit) { query, limit -> query to limit }
        .debounce(memoSearchDebounceMillis)
        .distinctUntilChanged()
        .flatMapLatest { (query, limit) ->
            if (query.isBlank()) {
                repository.observeMemoNotes(limit)
            } else {
                repository.observeMemoNotesByQuery(query, limit)
            }
        }

    private val editorState = combine(
        selectedMemoId,
        title,
        content,
        isLocked,
        password,
    ) { memoId, title, content, isLocked, password ->
        MemoEditorState(memoId, title, content, isLocked, password)
    }

    private val listState = combine(
        memoNotesFlow,
        searchQuery,
        visibleLimit,
    ) { memoNotes, query, limit ->
        MemoListState(memoNotes, query, limit)
    }

    // 작은 상태 묶음을 다시 합치면 인덱스 접근과 `as` 강제 형변환 없이 UI 상태를 만들 수 있다.
    val uiState: StateFlow<MemoUiState> = combine(
        editorState,
        listState,
        statusMessage,
        screenMode,
    ) { editor, list, message, mode ->
        MemoUiState(
            memoNotes = list.memoNotes,
            selectedMemoId = editor.memoId,
            title = editor.title,
            content = editor.content,
            isLocked = editor.isLocked,
            password = editor.password,
            statusMessage = message,
            screenMode = mode,
            searchQuery = list.searchQuery,
            visibleLimit = list.visibleLimit,
            canLoadMore = list.memoNotes.size >= list.visibleLimit,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MemoUiState(),
    )

    fun updateTitle(value: String) {
        title.value = value
    }

    fun updateContent(value: String) {
        content.value = value
    }

    fun updateLocked(value: Boolean) {
        isLocked.value = value
        if (!value) {
            password.value = ""
        }
    }

    fun updatePassword(value: String) {
        password.value = value.digitsOnly().take(10)
    }

    fun updateSearchQuery(value: String) {
        searchQuery.value = value
        visibleLimit.value = memoPageSize
    }

    fun loadMoreMemoNotes() {
        val currentState = uiState.value
        if (!currentState.canLoadMore) return
        visibleLimit.value = visibleLimit.value + memoPageSize
    }

    fun showList() {
        screenMode.value = MemoScreenMode.LIST
        statusMessage.value = null
    }

    fun startNewMemo() {
        resetEditor()
        statusMessage.value = null
        screenMode.value = MemoScreenMode.EDITOR
    }

    fun openMemo(memoNote: MemoNoteEntity) {
        selectedMemoId.value = memoNote.id
        title.value = memoNote.title
        content.value = memoNote.content
        isLocked.value = memoNote.isLocked
        password.value = ""
        statusMessage.value = null
        screenMode.value = MemoScreenMode.EDITOR
    }

    fun unlockMemo(memoId: Long, password: String) {
        viewModelScope.launch {
            runCatching {
                repository.verifyMemoPassword(memoId, password)
            }.onSuccess { memoNote ->
                openMemo(memoNote)
                statusMessage.value = "잠금 메모를 열었습니다."
            }.onFailure { error ->
                statusMessage.value = error.message ?: "잠금 해제에 실패했습니다."
            }
        }
    }

    fun toggleMemoPinned(memoNote: MemoNoteEntity) {
        viewModelScope.launch {
            runCatching {
                repository.updateMemoPinned(memoId = memoNote.id, isPinned = !memoNote.isPinned)
            }.onSuccess {
                statusMessage.value = if (memoNote.isPinned) {
                    "메모 고정을 해제했습니다."
                } else {
                    "메모를 상단에 고정했습니다."
                }
            }.onFailure { error ->
                statusMessage.value = error.message ?: "메모 고정 변경에 실패했습니다."
            }
        }
    }

    fun saveMemo() {
        viewModelScope.launch {
            val lockedSnapshot = isLocked.value
            runCatching {
                repository.saveMemoNote(
                    memoId = selectedMemoId.value,
                    title = title.value,
                    content = content.value,
                    isLocked = lockedSnapshot,
                    password = password.value.takeIf(String::isNotEmpty),
                )
            }.onSuccess {
                resetEditor()
                screenMode.value = MemoScreenMode.LIST
                statusMessage.value = if (lockedSnapshot) {
                    "잠금 메모가 저장되었습니다."
                } else {
                    "메모가 저장되었습니다."
                }
            }.onFailure { error ->
                statusMessage.value = error.message ?: "메모 저장에 실패했습니다."
            }
        }
    }

    fun deleteMemo() {
        val memoId = selectedMemoId.value ?: return
        viewModelScope.launch {
            runCatching {
                repository.deleteMemoNote(memoId)
            }.onSuccess {
                resetEditor()
                screenMode.value = MemoScreenMode.LIST
                statusMessage.value = "메모를 삭제했습니다."
            }.onFailure { error ->
                statusMessage.value = error.message ?: "메모 삭제에 실패했습니다."
            }
        }
    }

    fun clearStatusMessage() {
        statusMessage.value = null
    }

    private fun resetEditor() {
        selectedMemoId.value = null
        title.value = ""
        content.value = ""
        isLocked.value = false
        password.value = ""
    }
}

data class MemoUiState(
    val memoNotes: List<MemoNoteEntity> = emptyList(),
    val selectedMemoId: Long? = null,
    val title: String = "",
    val content: String = "",
    val isLocked: Boolean = false,
    val password: String = "",
    val statusMessage: String? = null,
    val screenMode: MemoScreenMode = MemoScreenMode.LIST,
    val searchQuery: String = "",
    val visibleLimit: Int = memoPageSize,
    val canLoadMore: Boolean = false,
)
