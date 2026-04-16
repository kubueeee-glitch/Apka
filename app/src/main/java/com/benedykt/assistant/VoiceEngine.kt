package com.benedykt.assistant

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale

/**
 * STT + TTS + barge-in.
 * Barge-in: w trakcie odtwarzania TTS monitorujemy amplitudę mikrofonu przez
 * MediaRecorder. Jeśli użytkownik zaczyna mówić głośno – TTS jest przerywany
 * i automatycznie włącza się pełne rozpoznawanie mowy.
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
        fun onBargeIn()
        fun onTtsReady(available: Boolean)
    }

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isListening = false

    // --- Barge-in --- //
    private var bargeRecorder: MediaRecorder? = null
    private var bargeFile: File? = null
    private val handler = Handler(Looper.getMainLooper())
    private var bargeActive = false
    private var calibratedBaseline = 600
    private var samples = 0

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
                        startBargeIn()
                    }

                    override fun onDone(utteranceId: String?) {
                        stopBargeIn()
                        listener.onSpeechFinished()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        stopBargeIn()
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
        stopBargeIn()
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
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                1500L
            )
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
        stopBargeIn()
    }

    fun release() {
        stopListening()
        stopBargeIn()
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    // --- Barge-in --- //

    private fun startBargeIn() {
        if (bargeActive) return
        try {
            bargeFile = File.createTempFile("bene-barge", ".3gp", context.cacheDir)
            bargeRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(bargeFile!!.absolutePath)
                prepare()
                start()
            }
            bargeActive = true
            samples = 0
            calibratedBaseline = 800
            handler.postDelayed(bargePoll, POLL_MS)
        } catch (t: Throwable) {
            bargeActive = false
            runCatching { bargeRecorder?.release() }
            bargeRecorder = null
        }
    }

    private val bargePoll = object : Runnable {
        override fun run() {
            if (!bargeActive) return
            val amp = runCatching { bargeRecorder?.maxAmplitude ?: 0 }.getOrDefault(0)
            samples++
            // Pierwsze ~400ms – kalibracja tła
            if (samples <= 4) {
                if (amp > calibratedBaseline) calibratedBaseline = amp
            } else if (amp > calibratedBaseline * BARGE_MULTIPLIER && amp > MIN_AMPLITUDE) {
                stopBargeIn()
                stopSpeaking()
                listener.onBargeIn()
                return
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    private fun stopBargeIn() {
        if (!bargeActive && bargeRecorder == null) return
        bargeActive = false
        handler.removeCallbacks(bargePoll)
        runCatching { bargeRecorder?.stop() }
        runCatching { bargeRecorder?.release() }
        bargeRecorder = null
        bargeFile?.delete()
        bargeFile = null
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
        private const val POLL_MS = 120L
        private const val BARGE_MULTIPLIER = 3.0f
        private const val MIN_AMPLITUDE = 3500

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
