package com.habittracker.ui.memo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.habittracker.data.local.entity.MemoNoteEntity
import com.habittracker.data.repository.HabitRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val memoPageSize = 10
private const val listMode = "list"
private const val editorMode = "editor"

class MemoViewModel(
    private val repository: HabitRepository,
) : ViewModel() {
    private val selectedMemoId = MutableStateFlow<Long?>(null)
    private val title = MutableStateFlow("")
    private val content = MutableStateFlow("")
    private val isLocked = MutableStateFlow(false)
    private val password = MutableStateFlow("")
    private val statusMessage = MutableStateFlow<String?>(null)
    private val screenMode = MutableStateFlow(listMode)
    private val searchQuery = MutableStateFlow("")
    private val visibleLimit = MutableStateFlow(memoPageSize)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val memoNotesFlow = combine(searchQuery, visibleLimit) { query, limit -> query to limit }
        .flatMapLatest { (query, limit) ->
            if (query.isBlank()) {
                repository.observeMemoNotes(limit)
            } else {
                repository.observeMemoNotesByQuery(query, limit)
            }
        }

    val uiState: StateFlow<MemoUiState> = combine(
        memoNotesFlow,
        selectedMemoId,
        title,
        content,
        isLocked,
        password,
        statusMessage,
        screenMode,
        searchQuery,
        visibleLimit,
    ) { values ->
        val memoNotes = values[0] as List<MemoNoteEntity>
        val memoId = values[1] as Long?
        val titleValue = values[2] as String
        val contentValue = values[3] as String
        val lockedValue = values[4] as Boolean
        val passwordValue = values[5] as String
        val message = values[6] as String?
        val mode = values[7] as String
        val query = values[8] as String
        val limit = values[9] as Int

        MemoUiState(
            memoNotes = memoNotes,
            selectedMemoId = memoId,
            title = titleValue,
            content = contentValue,
            isLocked = lockedValue,
            password = passwordValue,
            statusMessage = message,
            screenMode = mode,
            searchQuery = query,
            visibleLimit = limit,
            canLoadMore = memoNotes.size >= limit,
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
        password.value = value.filter(Char::isDigit).take(4)
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
        screenMode.value = listMode
        statusMessage.value = null
    }

    fun startNewMemo() {
        selectedMemoId.value = null
        title.value = ""
        content.value = ""
        isLocked.value = false
        password.value = ""
        statusMessage.value = null
        screenMode.value = editorMode
    }

    fun openMemo(memoNote: MemoNoteEntity) {
        selectedMemoId.value = memoNote.id
        title.value = memoNote.title
        content.value = memoNote.content
        isLocked.value = memoNote.isLocked
        password.value = ""
        statusMessage.value = null
        screenMode.value = editorMode
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
                selectedMemoId.value = null
                title.value = ""
                content.value = ""
                isLocked.value = false
                password.value = ""
                screenMode.value = listMode
                statusMessage.value = if (lockedSnapshot) {
                    "잠금 메모를 저장했습니다."
                } else {
                    "메모를 저장했습니다."
                }
            }.onFailure { error ->
                statusMessage.value = error.message ?: "메모 저장에 실패했습니다."
            }
        }
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
    val screenMode: String = listMode,
    val searchQuery: String = "",
    val visibleLimit: Int = memoPageSize,
    val canLoadMore: Boolean = false,
)
