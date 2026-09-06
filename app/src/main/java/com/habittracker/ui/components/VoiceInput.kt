package com.habittracker.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
    var isListening by remember { mutableStateOf(false) }
    val speechRecognizer = remember(context) {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
        } else {
            null
        }
    }
    val startVoiceInput = {
        if (speechRecognizer == null) {
            currentOnStatusMessage("이 기기에서는 음성 인식을 사용할 수 없습니다.")
        } else {
            currentOnStatusMessage("말씀해 주세요.")
            isListening = true
            speechRecognizer.startListening(koreanSpeechRecognitionIntent())
        }
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
                    isListening = true
                    currentOnStatusMessage("음성을 듣고 있습니다.")
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    currentOnStatusMessage("음성을 텍스트로 변환하고 있습니다.")
                }

                override fun onError(error: Int) {
                    isListening = false
                    currentOnStatusMessage(speechRecognitionErrorMessage(error))
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val recognizedText = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (recognizedText.isNullOrBlank()) {
                        currentOnStatusMessage("인식된 내용이 없습니다. 다시 말씀해 주세요.")
                    } else {
                        currentOnRecognizedText(recognizedText)
                        currentOnStatusMessage("음성 내용을 추가했습니다.")
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            },
        )
        onDispose {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        }
    }

    IconButton(
        onClick = {
            if (isListening) {
                speechRecognizer?.stopListening()
                currentOnStatusMessage("음성을 텍스트로 변환하고 있습니다.")
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
            contentDescription = if (isListening) "음성 입력 중지" else "음성으로 입력",
            tint = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}

private fun koreanSpeechRecognitionIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
}

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
