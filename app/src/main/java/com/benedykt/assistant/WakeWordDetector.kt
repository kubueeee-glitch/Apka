package com.benedykt.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Wykrywanie frazy wyzwalającej ("Hej Benedykt") przy użyciu Android SpeechRecognizer
 * działającego w pętli. To proste podejście nie wymaga zewnętrznych bibliotek i
 * jest darmowe – rozpoznajemy po prostu każdą wypowiedź i sprawdzamy, czy
 * zawiera wariant "hej benedykt".
 */
class WakeWordDetector(
    private val context: Context,
    private val onWakeWord: () -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var paused = false

    private val wakePatterns = listOf(
        "hej benedykt",
        "hej benedyk",
        "hey benedykt",
        "ej benedykt",
        "benedykt",
        "benedyk"
    )

    fun start() {
        if (running) return
        running = true
        paused = false
        ensureRecognizer()
        listen()
    }

    fun pause() {
        paused = true
        runCatching { recognizer?.cancel() }
    }

    fun resume() {
        if (!running) {
            start()
            return
        }
        paused = false
        handler.postDelayed({ listen() }, 300)
    }

    fun stop() {
        running = false
        paused = false
        handler.removeCallbacksAndMessages(null)
        runCatching { recognizer?.cancel() }
        recognizer?.destroy()
        recognizer = null
    }

    private fun ensureRecognizer() {
        if (recognizer != null) return
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(listener)
        }
    }

    private fun listen() {
        if (!running || paused) return
        ensureRecognizer()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pl-PL")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                800L
            )
        }
        try {
            recognizer?.startListening(intent)
        } catch (t: Throwable) {
            scheduleRestart(1200)
        }
    }

    private fun scheduleRestart(delay: Long) {
        if (!running) return
        handler.postDelayed({ listen() }, delay)
    }

    private fun containsWake(text: String): Boolean {
        val t = text.lowercase(Locale.getDefault())
        return wakePatterns.any { t.contains(it) }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val list =
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = list?.firstOrNull().orEmpty()
            if (containsWake(text)) {
                runCatching { recognizer?.cancel() }
                onWakeWord()
            }
        }

        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = list?.firstOrNull().orEmpty()
            if (containsWake(text)) {
                onWakeWord()
            } else {
                scheduleRestart(400)
            }
        }

        override fun onError(error: Int) {
            val delay = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 200L
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 800L
                SpeechRecognizer.ERROR_CLIENT -> 800L
                else -> 1200L
            }
            scheduleRestart(delay)
        }
    }
}
