package com.benedykt.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Łączy rozpoznawanie mowy (SpeechRecognizer) oraz syntezę (TextToSpeech)
 * w prosty interfejs używany przez asystenta.
 */
class VoiceEngine(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onListeningStarted()
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onRecognitionError(errorCode: Int)
        fun onSpeechStarted()
        fun onSpeechFinished()
        fun onTtsReady(available: Boolean)
    }

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isListening = false

    fun init() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            listener.onRecognitionError(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(createListener())
        }
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val loc = Locale("pl", "PL")
                val res = tts?.setLanguage(loc) ?: TextToSpeech.LANG_NOT_SUPPORTED
                ttsReady = res != TextToSpeech.LANG_MISSING_DATA &&
                    res != TextToSpeech.LANG_NOT_SUPPORTED
                if (!ttsReady) tts?.language = Locale.getDefault()
                tts?.setSpeechRate(1.05f)
                tts?.setPitch(1.0f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        listener.onSpeechStarted()
                    }

                    override fun onDone(utteranceId: String?) {
                        listener.onSpeechFinished()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        listener.onSpeechFinished()
                    }
                })
                listener.onTtsReady(true)
            } else {
                listener.onTtsReady(false)
            }
        }
    }

    fun startListening() {
        if (isListening) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pl-PL")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        try {
            recognizer?.startListening(intent)
            isListening = true
        } catch (t: Throwable) {
            listener.onRecognitionError(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    fun stopListening() {
        if (!isListening) return
        runCatching { recognizer?.stopListening() }
        isListening = false
    }

    fun cancel() {
        runCatching { recognizer?.cancel() }
        isListening = false
    }

    fun speak(text: String) {
        if (!ttsReady || text.isBlank()) {
            listener.onSpeechFinished()
            return
        }
        val utteranceId = "bene-" + System.currentTimeMillis()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    fun release() {
        stopListening()
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    private fun createListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            listener.onListeningStarted()
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            isListening = false
        }

        override fun onError(error: Int) {
            isListening = false
            listener.onRecognitionError(error)
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = list?.firstOrNull().orEmpty().trim()
            if (text.isNotEmpty()) listener.onFinalResult(text)
            else listener.onRecognitionError(SpeechRecognizer.ERROR_NO_MATCH)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val list =
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = list?.firstOrNull().orEmpty().trim()
            if (text.isNotEmpty()) listener.onPartialResult(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    companion object {
        fun errorToText(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_AUDIO -> "Problem z mikrofonem."
            SpeechRecognizer.ERROR_CLIENT -> "Błąd klienta mowy."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Brak uprawnień do mikrofonu."
            SpeechRecognizer.ERROR_NETWORK -> "Błąd sieci."
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Sieć odpowiada zbyt wolno."
            SpeechRecognizer.ERROR_NO_MATCH -> "Nie zrozumiałem."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Rozpoznawanie mowy zajęte."
            SpeechRecognizer.ERROR_SERVER -> "Błąd serwera rozpoznawania."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Nie usłyszałem mowy."
            else -> "Nieznany błąd mowy ($code)."
        }
    }
}
