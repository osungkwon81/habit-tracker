package com.habittracker.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import com.habittracker.R

@Composable
fun AppVoiceInputButton(
    onRecognizedText: (String) -> Unit,
    onStatusMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val currentOnRecognizedText by rememberUpdatedState(onRecognizedText)
    val currentOnStatusMessage by rememberUpdatedState(onStatusMessage)
    var isContinuousListening by remember { mutableStateOf(false) }
    var isRecognitionActive by remember { mutableStateOf(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val speechRecognizer = remember(context) {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
        } else {
            null
        }
    }
    val startRecognitionSession = {
        if (speechRecognizer == null) {
            isContinuousListening = false
            currentOnStatusMessage("이 기기에서는 음성 인식을 사용할 수 없습니다.")
        } else if (!isRecognitionActive) {
            isRecognitionActive = true
            runCatching {
                speechRecognizer.startListening(koreanSpeechRecognitionIntent())
            }.onFailure {
                isRecognitionActive = false
                isContinuousListening = false
                currentOnStatusMessage("음성 인식을 시작하지 못했습니다. 다시 시도해 주세요.")
            }
        }
    }
    val startVoiceInput = {
        isContinuousListening = true
        currentOnStatusMessage("음성 입력을 켰습니다. 마이크를 다시 누르면 끝납니다.")
        startRecognitionSession()
    }
    val restartRecognition = {
        mainHandler.postDelayed(
            {
                if (isContinuousListening && !isRecognitionActive) {
                    startRecognitionSession()
                }
            },
            VOICE_RESTART_DELAY_MILLIS,
        )
    }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startVoiceInput()
        } else {
            currentOnStatusMessage("음성 입력을 사용하려면 마이크 권한을 허용해 주세요.")
        }
    }

    DisposableEffect(speechRecognizer) {
        speechRecognizer?.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isRecognitionActive = true
                    currentOnStatusMessage("음성을 계속 듣고 있습니다. 마이크를 누르면 끝납니다.")
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    currentOnStatusMessage("음성을 텍스트로 변환하고 있습니다.")
                }

                override fun onError(error: Int) {
                    isRecognitionActive = false
                    if (isContinuousListening && error.isRecoverableVoiceInputError()) {
                        currentOnStatusMessage("음성을 계속 듣고 있습니다. 마이크를 누르면 끝납니다.")
                        restartRecognition()
                    } else if (isContinuousListening) {
                        isContinuousListening = false
                        currentOnStatusMessage(speechRecognitionErrorMessage(error))
                    } else {
                        currentOnStatusMessage("음성 입력을 끝냈습니다.")
                    }
                }

                override fun onResults(results: Bundle?) {
                    isRecognitionActive = false
                    val recognizedText = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (recognizedText.isNullOrBlank()) {
                        currentOnStatusMessage(
                            if (isContinuousListening) "음성을 계속 듣고 있습니다." else "음성 입력을 끝냈습니다.",
                        )
                    } else {
                        currentOnRecognizedText(recognizedText)
                        currentOnStatusMessage(
                            if (isContinuousListening) {
                                "음성 내용을 추가했습니다. 계속 듣고 있습니다."
                            } else {
                                "음성 내용을 추가하고 입력을 끝냈습니다."
                            },
                        )
                    }
                    if (isContinuousListening) {
                        restartRecognition()
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            },
        )
        onDispose {
            isContinuousListening = false
            isRecognitionActive = false
            mainHandler.removeCallbacksAndMessages(null)
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        }
    }

    IconButton(
        onClick = {
            if (isContinuousListening) {
                isContinuousListening = false
                mainHandler.removeCallbacksAndMessages(null)
                if (isRecognitionActive) {
                    speechRecognizer?.stopListening()
                    currentOnStatusMessage("마지막 음성을 텍스트로 변환하고 있습니다.")
                } else {
                    currentOnStatusMessage("음성 입력을 끝냈습니다.")
                }
            } else if (
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                startVoiceInput()
            } else {
                microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_microphone),
            contentDescription = if (isContinuousListening) "음성 입력 끄기" else "음성 입력 켜기",
            tint = if (isContinuousListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}

private fun koreanSpeechRecognitionIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
}

private fun Int.isRecoverableVoiceInputError(): Boolean =
    this == SpeechRecognizer.ERROR_NO_MATCH ||
        this == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
        this == SpeechRecognizer.ERROR_RECOGNIZER_BUSY

private fun speechRecognitionErrorMessage(error: Int): String = when (error) {
    SpeechRecognizer.ERROR_AUDIO -> "마이크 입력을 처리하지 못했습니다. 다시 시도해 주세요."
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "음성 입력을 사용하려면 마이크 권한을 허용해 주세요."
    SpeechRecognizer.ERROR_NETWORK,
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
    SpeechRecognizer.ERROR_SERVER,
    -> "음성 인식 서비스에 연결하지 못했습니다. 네트워크 상태를 확인해 주세요."
    SpeechRecognizer.ERROR_NO_MATCH -> "음성을 인식하지 못했습니다. 다시 말씀해 주세요."
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "음성 인식기가 사용 중입니다. 잠시 후 다시 시도해 주세요."
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "들리는 음성이 없습니다. 마이크 가까이에서 말씀해 주세요."
    else -> "음성 인식에 실패했습니다. 다시 시도해 주세요."
}

private const val VOICE_RESTART_DELAY_MILLIS = 350L
