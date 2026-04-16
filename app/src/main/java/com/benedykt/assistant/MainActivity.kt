package com.benedykt.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.benedykt.assistant.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), VoiceEngine.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var voice: VoiceEngine
    private lateinit var controller: PhoneController
    private lateinit var gemini: GeminiClient
    private val adapter = MessageAdapter()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("benedykt", Context.MODE_PRIVATE)
        controller = PhoneController(this)
        gemini = GeminiClient(prefs.getString(KEY_API, "").orEmpty())
        voice = VoiceEngine(this, this)

        binding.messages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.messages.adapter = adapter

        binding.micButton.setOnClickListener { startAssistantTurn() }
        binding.stopButton.setOnClickListener { stopCurrent() }
        binding.settingsButton.setOnClickListener { askForApiKey() }
        binding.sendButton.setOnClickListener {
            val t = binding.textInput.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty()) {
                binding.textInput.setText("")
                sendToGemini(t)
            }
        }

        ensurePermissions()
        voice.init()

        addSystemMessage(
            "Cześć! Jestem Benedykt. Naciśnij mikrofon albo powiedz \"Hej Benedykt\"."
        )

        if (prefs.getString(KEY_API, "").isNullOrBlank()) {
            askForApiKey()
        }

        if (intent?.getBooleanExtra(EXTRA_FROM_WAKE_WORD, false) == true) {
            binding.root.post { startAssistantTurn() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_FROM_WAKE_WORD, false)) {
            binding.root.post { startAssistantTurn() }
        }
    }

    override fun onResume() {
        super.onResume()
        // Zatrzymaj nasłuch wake word gdy okno aplikacji jest aktywne
        stopService(Intent(this, WakeWordService::class.java))
    }

    override fun onPause() {
        super.onPause()
        startWakeWordServiceIfAllowed()
    }

    override fun onDestroy() {
        voice.release()
        super.onDestroy()
    }

    // --- Konwersacja --- //

    private fun startAssistantTurn() {
        if (busy) return
        if (!hasRecordPermission()) {
            ensurePermissions()
            return
        }
        voice.stopSpeaking()
        binding.statusText.text = getString(R.string.status_listening)
        voice.startListening()
    }

    private fun stopCurrent() {
        voice.cancel()
        voice.stopSpeaking()
        busy = false
        binding.statusText.text = getString(R.string.status_idle)
    }

    private fun sendToGemini(userText: String) {
        if (prefs.getString(KEY_API, "").isNullOrBlank()) {
            addSystemMessage("Najpierw wklej darmowy klucz Gemini API (koło zębate u góry).")
            askForApiKey()
            return
        }
        addUserMessage(userText)
        busy = true
        binding.statusText.text = getString(R.string.status_thinking)
        lifecycleScope.launch {
            try {
                var response = gemini.sendMessage(userText)
                // Pozwól modelowi wywołać funkcję (ewentualnie wiele razy)
                var guard = 0
                while (response.functionCall != null && guard < 5) {
                    val call = response.functionCall!!
                    addSystemMessage("[akcja: ${call.name}]")
                    val result = controller.execute(call)
                    response = gemini.sendFunctionResult(call.name, result)
                    guard++
                }
                val reply = response.text.ifBlank { "Gotowe." }
                addAssistantMessage(reply)
                binding.statusText.text = getString(R.string.status_speaking)
                voice.speak(reply)
            } catch (t: Throwable) {
                val msg = t.message ?: "Błąd komunikacji z Gemini."
                addSystemMessage("Błąd: $msg")
                binding.statusText.text = getString(R.string.status_idle)
                busy = false
            }
        }
    }

    // --- UI helpers --- //

    private fun addUserMessage(text: String) {
        adapter.add(Message(Message.Role.USER, text))
        binding.messages.scrollToPosition(adapter.itemCount - 1)
    }

    private fun addAssistantMessage(text: String) {
        adapter.add(Message(Message.Role.ASSISTANT, text))
        binding.messages.scrollToPosition(adapter.itemCount - 1)
    }

    private fun addSystemMessage(text: String) {
        adapter.add(Message(Message.Role.SYSTEM, text))
        binding.messages.scrollToPosition(adapter.itemCount - 1)
    }

    // --- VoiceEngine.Listener --- //

    override fun onListeningStarted() {
        binding.statusText.text = getString(R.string.status_listening)
        binding.pulse.visibility = View.VISIBLE
    }

    override fun onPartialResult(text: String) {
        binding.statusText.text = "\u201C$text\u2026\u201D"
    }

    override fun onFinalResult(text: String) {
        binding.pulse.visibility = View.GONE
        sendToGemini(text)
    }

    override fun onRecognitionError(errorCode: Int) {
        binding.pulse.visibility = View.GONE
        binding.statusText.text = VoiceEngine.errorToText(errorCode)
        busy = false
    }

    override fun onSpeechStarted() {
        binding.statusText.text = getString(R.string.status_speaking)
    }

    override fun onSpeechFinished() {
        busy = false
        binding.statusText.text = getString(R.string.status_idle)
        // Automatyczny tryb konwersacji – po odpowiedzi asystenta wróć do nasłuchu
        binding.root.postDelayed({
            if (!busy && !isFinishing) startAssistantTurn()
        }, 400)
    }

    override fun onTtsReady(available: Boolean) {
        if (!available) addSystemMessage("Brak polskiego TTS. Odpowiedzi będą tylko tekstem.")
    }

    // --- Uprawnienia --- //

    private fun hasRecordPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensurePermissions() {
        val needed = mutableListOf<String>()
        val wanted = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wanted += Manifest.permission.BLUETOOTH_CONNECT
        }
        for (p in wanted) {
            if (ContextCompat.checkSelfPermission(this, p) !=
                PackageManager.PERMISSION_GRANTED
            ) needed += p
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERMS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) startWakeWordServiceIfAllowed()
    }

    private fun startWakeWordServiceIfAllowed() {
        if (!hasRecordPermission()) return
        if (prefs.getBoolean(KEY_WAKE_ENABLED, true).not()) return
        val intent = Intent(this, WakeWordService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun askForApiKey() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "AIza..."
            setText(prefs.getString(KEY_API, ""))
        }
        AlertDialog.Builder(this)
            .setTitle("Klucz Gemini API")
            .setMessage(
                "Wklej darmowy klucz z https://aistudio.google.com/apikey\n\n" +
                    "Klucz jest przechowywany tylko lokalnie na tym telefonie."
            )
            .setView(input)
            .setPositiveButton("Zapisz") { _, _ ->
                val key = input.text.toString().trim()
                prefs.edit().putString(KEY_API, key).apply()
                gemini.setApiKey(key)
                addSystemMessage("Klucz zapisany. Powiedz coś do mnie.")
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    companion object {
        const val EXTRA_FROM_WAKE_WORD = "from_wake"
        private const val REQ_PERMS = 1001
        private const val KEY_API = "gemini_api_key"
        private const val KEY_WAKE_ENABLED = "wake_enabled"
    }
}
