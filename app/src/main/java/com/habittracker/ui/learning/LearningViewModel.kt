package com.habittracker.ui.learning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.habittracker.data.local.entity.VocabularyWordEntity
import com.habittracker.data.repository.BulkVocabularyInsertResult
import com.habittracker.data.repository.HabitRepository
import com.habittracker.data.repository.VocabularyStudyRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.random.Random

private const val learningTabFlashcard = "flashcard"
private const val learningTabTest = "test"
private const val learningTabWord = "word"
private const val learningTabStats = "stats"
private const val vocabularyListPageSize = 100

class LearningViewModel(
    private val repository: HabitRepository,
) : ViewModel() {
    private val selectedTab = MutableStateFlow(learningTabFlashcard)
    private val searchQuery = MutableStateFlow("")
    private val selectedWordId = MutableStateFlow<Long?>(null)
    private val wordInput = MutableStateFlow("")
    private val meaningInput = MutableStateFlow("")
    private val pronunciationInput = MutableStateFlow("")
    private val bulkInput = MutableStateFlow("")
    private val bulkMode = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val flashcardCount = MutableStateFlow("10")
    private val flashcardShowPronunciation = MutableStateFlow(true)
    private val flashcardBlindMode = MutableStateFlow(BlindMode.MEANING)
    private val flashcardWeighted = MutableStateFlow(true)
    private val flashcardSession = MutableStateFlow<FlashcardSession?>(null)
    private val testCount = MutableStateFlow("10")
    private val testWeighted = MutableStateFlow(true)
    private val testSession = MutableStateFlow<TestSession?>(null)
    private val nowMillis = MutableStateFlow(System.currentTimeMillis())

    init {
        viewModelScope.launch {
            while (isActive) {
                nowMillis.value = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val wordsFlow = searchQuery.flatMapLatest { query ->
        if (query.isBlank()) {
            repository.observeVocabularyWords(vocabularyListPageSize)
        } else {
            repository.observeVocabularyWordsByQuery(query, vocabularyListPageSize)
        }
    }

    val uiState: StateFlow<LearningUiState> = combine(
        selectedTab,
        wordsFlow,
        searchQuery,
        selectedWordId,
        wordInput,
        meaningInput,
        pronunciationInput,
        bulkInput,
        bulkMode,
        message,
        flashcardCount,
        flashcardShowPronunciation,
        flashcardBlindMode,
        flashcardWeighted,
        flashcardSession,
        testCount,
        testWeighted,
        testSession,
        nowMillis,
    ) { values ->
        val tab = values[0] as String
        val words = values[1] as List<VocabularyWordEntity>
        val query = values[2] as String
        val selectedId = values[3] as Long?
        val word = values[4] as String
        val meaning = values[5] as String
        val pronunciation = values[6] as String
        val bulkText = values[7] as String
        val isBulkMode = values[8] as Boolean
        val statusMessage = values[9] as String?
        val flashCount = values[10] as String
        val showPronunciation = values[11] as Boolean
        val blindMode = values[12] as BlindMode
        val flashWeighted = values[13] as Boolean
        val flashSessionState = values[14] as FlashcardSession?
        val testCountValue = values[15] as String
        val testWeightedValue = values[16] as Boolean
        val testSessionState = values[17] as TestSession?
        val currentNow = values[18] as Long

        val totalCorrect = words.sumOf(VocabularyWordEntity::correctCount)
        val totalWrong = words.sumOf(VocabularyWordEntity::wrongCount)
        val totalExposure = words.sumOf(VocabularyWordEntity::exposureCount)
        val totalFlashcardStudySeconds = words.sumOf(VocabularyWordEntity::flashcardStudySeconds)
        val totalTestStudySeconds = words.sumOf(VocabularyWordEntity::testStudySeconds)

        LearningUiState(
            selectedTab = tab,
            words = words,
            searchQuery = query,
            selectedWordId = selectedId,
            wordInput = word,
            meaningInput = meaning,
            pronunciationInput = pronunciation,
            bulkInput = bulkText,
            bulkMode = isBulkMode,
            statusMessage = statusMessage,
            flashcardCount = flashCount,
            flashcardShowPronunciation = showPronunciation,
            flashcardBlindMode = blindMode,
            flashcardWeighted = flashWeighted,
            flashcardSession = flashSessionState,
            flashcardElapsedSeconds = flashSessionState?.elapsedSeconds(currentNow) ?: 0,
            testCount = testCountValue,
            testWeighted = testWeightedValue,
            testSession = testSessionState,
            testElapsedSeconds = testSessionState?.elapsedSeconds(currentNow) ?: 0,
            totalCorrect = totalCorrect,
            totalWrong = totalWrong,
            totalExposure = totalExposure,
            totalFlashcardStudySeconds = totalFlashcardStudySeconds,
            totalTestStudySeconds = totalTestStudySeconds,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LearningUiState(),
    )

    fun selectTab(tab: String) {
        selectedTab.value = tab
        message.value = null
    }

    fun updateSearchQuery(value: String) {
        searchQuery.value = value
    }

    fun editWord(word: VocabularyWordEntity) {
        selectedWordId.value = word.id
        wordInput.value = word.word
        meaningInput.value = word.meaning
        pronunciationInput.value = word.pronunciation.orEmpty()
        bulkMode.value = false
        selectedTab.value = learningTabWord
    }

    fun startNewWord() {
        selectedWordId.value = null
        wordInput.value = ""
        meaningInput.value = ""
        pronunciationInput.value = ""
        bulkMode.value = false
        selectedTab.value = learningTabWord
    }

    fun showBulkMode() {
        bulkMode.value = true
        selectedWordId.value = null
        wordInput.value = ""
        meaningInput.value = ""
        pronunciationInput.value = ""
        selectedTab.value = learningTabWord
    }

    fun updateWordInput(value: String) { wordInput.value = value }
    fun updateMeaningInput(value: String) { meaningInput.value = value }
    fun updatePronunciationInput(value: String) { pronunciationInput.value = value }
    fun updateBulkInput(value: String) { bulkInput.value = value }
    fun updateFlashcardCount(value: String) { flashcardCount.value = value.filter(Char::isDigit).take(3) }
    fun updateFlashcardShowPronunciation(value: Boolean) { flashcardShowPronunciation.value = value }
    fun updateFlashcardBlindMode(value: BlindMode) { flashcardBlindMode.value = value }
    fun updateFlashcardWeighted(value: Boolean) { flashcardWeighted.value = value }
    fun updateTestCount(value: String) { testCount.value = value.filter(Char::isDigit).take(3) }
    fun updateTestWeighted(value: Boolean) { testWeighted.value = value }

    fun saveWord() {
        viewModelScope.launch {
            runCatching {
                repository.saveVocabularyWord(
                    wordId = selectedWordId.value,
                    word = wordInput.value,
                    meaning = meaningInput.value,
                    pronunciation = pronunciationInput.value,
                )
            }.onSuccess {
                startNewWord()
                message.value = "단어를 저장했습니다."
            }.onFailure { error ->
                message.value = error.message ?: "단어 저장에 실패했습니다."
            }
        }
    }

    fun deleteWord(wordId: Long) {
        viewModelScope.launch {
            runCatching {
                repository.deleteVocabularyWord(wordId)
            }.onSuccess {
                if (selectedWordId.value == wordId) startNewWord()
                message.value = "단어를 삭제했습니다."
            }.onFailure { error ->
                message.value = error.message ?: "단어 삭제에 실패했습니다."
            }
        }
    }

    fun bulkInsertWords() {
        viewModelScope.launch {
            runCatching {
                repository.bulkInsertVocabulary(bulkInput.value)
            }.onSuccess { result: BulkVocabularyInsertResult ->
                bulkInput.value = ""
                bulkMode.value = false
                message.value = "${result.insertedCount}개 등록, ${result.duplicateCount}개 중복 제외"
            }.onFailure { error ->
                message.value = error.message ?: "대량 등록에 실패했습니다."
            }
        }
    }

    fun startFlashcard() {
        viewModelScope.launch {
            val words = repository.getAllVocabularyWords()
            if (words.isEmpty()) {
                message.value = "먼저 단어를 등록해 주세요."
                return@launch
            }
            val requestedCount = flashcardCount.value.toIntOrNull() ?: 10
            val sessionWords = buildStudySet(words, requestedCount, flashcardWeighted.value)
            flashcardSession.value = FlashcardSession(
                words = sessionWords,
                startedAtMillis = System.currentTimeMillis(),
            )
            selectedTab.value = learningTabFlashcard
            message.value = "암기를 시작했습니다."
        }
    }

    fun revealFlashcard(index: Int) {
        val session = flashcardSession.value ?: return
        flashcardSession.value = session.copy(revealedIndices = session.revealedIndices + index)
    }

    fun answerFlashcard(index: Int, correct: Boolean) {
        val session = flashcardSession.value ?: return
        flashcardSession.value = session.copy(
            revealedIndices = session.revealedIndices + index,
            answers = session.answers + (index to correct),
        )
    }

    fun finishFlashcard() {
        finalizeFlashcard(completedLabel = "암기를 완료했습니다.")
    }

    fun endFlashcard() {
        finalizeFlashcard(completedLabel = "암기를 종료했습니다.")
    }

    fun startTest() {
        viewModelScope.launch {
            val words = repository.getAllVocabularyWords()
            if (words.isEmpty()) {
                message.value = "먼저 단어를 등록해 주세요."
                return@launch
            }
            val requestedCount = testCount.value.toIntOrNull() ?: 10
            val sessionWords = buildStudySet(words, requestedCount, testWeighted.value)
            testSession.value = TestSession(
                questions = sessionWords,
                startedAtMillis = System.currentTimeMillis(),
            )
            selectedTab.value = learningTabTest
            message.value = "시험을 시작했습니다."
        }
    }

    fun updateTestAnswer(index: Int, value: String) {
        val session = testSession.value ?: return
        testSession.value = session.copy(answers = session.answers + (index to value))
    }

    fun finishTest() {
        finalizeTest(completedLabel = "시험을 완료했습니다.", markBlankAsWrong = true)
    }

    fun endTest() {
        finalizeTest(completedLabel = "시험을 종료했습니다.", markBlankAsWrong = false)
    }

    private fun finalizeFlashcard(completedLabel: String) {
        viewModelScope.launch {
            val session = flashcardSession.value ?: return@launch
            if (session.finished) return@launch

            val finishedAt = System.currentTimeMillis()
            val records = session.words.mapIndexed { index, word ->
                VocabularyStudyRecord(
                    wordId = word.id,
                    isCorrect = session.answers[index],
                )
            }
            repository.recordVocabularyStudySession(
                records = records,
                flashcardSeconds = session.elapsedSeconds(finishedAt),
            )
            flashcardSession.value = session.copy(
                finished = true,
                finishedLabel = completedLabel,
                endedAtMillis = finishedAt,
            )
            message.value = completedLabel
        }
    }

    private fun finalizeTest(completedLabel: String, markBlankAsWrong: Boolean) {
        viewModelScope.launch {
            val session = testSession.value ?: return@launch
            if (session.finished) return@launch

            val resultMap = session.questions.mapIndexed { index, word ->
                val answer = session.answers[index].orEmpty().trim()
                val correct = when {
                    answer.isBlank() && !markBlankAsWrong -> null
                    else -> answer.equals(word.meaning.trim(), ignoreCase = true)
                }
                index to correct
            }.toMap()
            val finishedAt = System.currentTimeMillis()
            val records = session.questions.mapIndexed { index, word ->
                VocabularyStudyRecord(
                    wordId = word.id,
                    isCorrect = resultMap[index],
                )
            }
            repository.recordVocabularyStudySession(
                records = records,
                testSeconds = session.elapsedSeconds(finishedAt),
            )
            testSession.value = session.copy(
                results = resultMap,
                finished = true,
                finishedLabel = completedLabel,
                endedAtMillis = finishedAt,
            )
            val correctCount = resultMap.values.count { it == true }
            val answeredCount = resultMap.values.count { it != null }
            message.value = completedLabel + " 정답 " + correctCount + "개 / 응답 " + answeredCount + "개"
        }
    }

    private fun buildStudySet(words: List<VocabularyWordEntity>, count: Int, weighted: Boolean): List<VocabularyWordEntity> {
        if (words.isEmpty()) return emptyList()
        val safeCount = max(1, count)
        return if (!weighted) {
            buildRandomStudySet(words, safeCount)
        } else {
            List(safeCount) { pickWeightedWord(words) }
        }
    }

    private fun buildRandomStudySet(words: List<VocabularyWordEntity>, count: Int): List<VocabularyWordEntity> {
        if (count <= words.size) {
            return words.shuffled().take(count)
        }
        val results = mutableListOf<VocabularyWordEntity>()
        while (results.size < count) {
            results += words.shuffled()
        }
        return results.take(count)
    }

    private fun pickWeightedWord(words: List<VocabularyWordEntity>): VocabularyWordEntity {
        val weights = words.map { calculateWeight(it) }
        val totalWeight = weights.sum()
        var target = Random.nextDouble(totalWeight)
        words.forEachIndexed { index, word ->
            target -= weights[index]
            if (target <= 0) {
                return word
            }
        }
        return words.last()
    }

    private fun calculateWeight(word: VocabularyWordEntity): Double {
        val difficulty = (word.wrongCount * 2.2) - (word.correctCount * 0.6) + ((word.exposureCount + 1).toDouble().reciprocal())
        return max(1.0, difficulty + 1.5)
    }
}

enum class BlindMode {
    WORD,
    MEANING,
}

data class FlashcardSession(
    val words: List<VocabularyWordEntity> = emptyList(),
    val revealedIndices: Set<Int> = emptySet(),
    val answers: Map<Int, Boolean> = emptyMap(),
    val startedAtMillis: Long = System.currentTimeMillis(),
    val endedAtMillis: Long? = null,
    val finished: Boolean = false,
    val finishedLabel: String? = null,
) {
    fun elapsedSeconds(nowMillis: Long): Int = (((endedAtMillis ?: nowMillis) - startedAtMillis) / 1_000L).toInt().coerceAtLeast(0)
}

data class TestSession(
    val questions: List<VocabularyWordEntity> = emptyList(),
    val answers: Map<Int, String> = emptyMap(),
    val results: Map<Int, Boolean?> = emptyMap(),
    val startedAtMillis: Long = System.currentTimeMillis(),
    val endedAtMillis: Long? = null,
    val finished: Boolean = false,
    val finishedLabel: String? = null,
) {
    fun elapsedSeconds(nowMillis: Long): Int = (((endedAtMillis ?: nowMillis) - startedAtMillis) / 1_000L).toInt().coerceAtLeast(0)
}

data class LearningUiState(
    val selectedTab: String = learningTabFlashcard,
    val words: List<VocabularyWordEntity> = emptyList(),
    val searchQuery: String = "",
    val selectedWordId: Long? = null,
    val wordInput: String = "",
    val meaningInput: String = "",
    val pronunciationInput: String = "",
    val bulkInput: String = "",
    val bulkMode: Boolean = false,
    val statusMessage: String? = null,
    val flashcardCount: String = "10",
    val flashcardShowPronunciation: Boolean = true,
    val flashcardBlindMode: BlindMode = BlindMode.MEANING,
    val flashcardWeighted: Boolean = true,
    val flashcardSession: FlashcardSession? = null,
    val flashcardElapsedSeconds: Int = 0,
    val testCount: String = "10",
    val testWeighted: Boolean = true,
    val testSession: TestSession? = null,
    val testElapsedSeconds: Int = 0,
    val totalCorrect: Int = 0,
    val totalWrong: Int = 0,
    val totalExposure: Int = 0,
    val totalFlashcardStudySeconds: Int = 0,
    val totalTestStudySeconds: Int = 0,
)

private fun Double.reciprocal(): Double = 1.0 / this

