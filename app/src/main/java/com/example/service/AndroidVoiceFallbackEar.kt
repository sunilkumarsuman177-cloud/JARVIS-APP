package com.example.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class AndroidVoiceFallbackEar(
    private val context: Context,
    private val onPartialPhrase: (String) -> Unit = {},
    private val onRecognizedPhrase: (String) -> Unit,
    private val onFinishedOrError: () -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    var isActivelyListening: Boolean = false
        private set

    fun startSingleCaptureSession() {
        stopCaptureSession()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onFinishedOrError()
            return
        }

        try {
            val sr = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isActivelyListening = true
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isActivelyListening = false
                }

                override fun onError(error: Int) {
                    isActivelyListening = false
                    onFinishedOrError()
                }

                override fun onResults(results: Bundle?) {
                    isActivelyListening = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val topPhrase = matches?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                    if (topPhrase.isNotEmpty()) {
                        onRecognizedPhrase(topPhrase)
                    } else {
                        onFinishedOrError()
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partials = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val liveText = partials?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                    if (liveText.isNotEmpty()) {
                        onPartialPhrase(liveText)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                // Bilingual Indian English + Hinglish + Hindi support
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }

            recognizer = sr
            isActivelyListening = true
            sr.startListening(intent)
        } catch (_: Throwable) {
            isActivelyListening = false
            onFinishedOrError()
        }
    }

    fun stopCaptureSession() {
        isActivelyListening = false
        try {
            recognizer?.stopListening()
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Throwable) {
        }
        recognizer = null
    }
}
