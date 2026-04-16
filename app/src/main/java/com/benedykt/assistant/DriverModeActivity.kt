package com.benedykt.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.benedykt.assistant.databinding.ActivityDriverBinding
import kotlinx.coroutines.launch

/**
 * Tryb kierowcy: wielki przycisk mikrofonu, auto-czytanie powiadomień,
 * jasny kolor tła. Ekran nie gaśnie podczas jazdy.
 */
class DriverModeActivity : AppCompatActivity(), VoiceEngine.Listener {

    private lateinit var binding: ActivityDriverBinding
    private lateinit var voice: VoiceEngine
    private lateinit var memory: MemoryStore
    private lateinit var skills: SkillsStore
    private lateinit var notes: NotesStore
    private lateinit var personas: PersonaStore
    private lateinit var gemini: GeminiClient
    private lateinit var controller: PhoneController
    private var busy = false

    private val notifReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            val app = intent.getStringExtra("app").orEmpty()
            val title = intent.getStringExtra("title").orEmpty()
            val text = intent.getStringExtra("text").orEmpty()
            val spoken = listOfNotNull(
                app.takeIf { it.isNotBlank() },
                title.takeIf { it.isNotBlank() },
                text.takeIf { it.isNotBlank() }
            ).joinToString(": ")
            if (spoken.isNotBlank()) {
                voice.speak("Powiadomienie: $spoken")
                binding.lastNotification.text = spoken
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDriverBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val prefs = getSharedPreferences("benedykt", Context.MODE_PRIVATE)
        memory = MemoryStore(this)
        skills = SkillsStore(this)
        notes = NotesStore(this)
        personas = PersonaStore(this)
        controller = PhoneController(
            this, memory, skills, notes, personas,
            object : PhoneController.Callbacks {
                override fun onRequestVision(question: String?) {
                    binding.status.text = "Wizja nie działa w trybie kierowcy."
                }
            }
        )
        val key = prefs.getString("gemini_api_key", "").orEmpty()
            .ifBlank { BuildConfig.GEMINI_API_KEY }
        gemini = GeminiClient(key, memory, skills, personas)
        voice = VoiceEngine(this, this)
        voice.init()

        binding.micButton.setOnClickListener { startTurn() }
        binding.exitButton.setOnClickListener { finish() }

        val filter = IntentFilter(BenedyktNotificationListener.ACTION_NEW_NOTIFICATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notifReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(notifReceiver, filter)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(notifReceiver) }
        voice.release()
        super.onDestroy()
    }

    private fun startTurn() {
        if (busy) return
        voice.stopSpeaking()
        binding.status.text = "Słucham…"
        voice.startListening()
    }

    private fun sendToGemini(text: String) {
        busy = true
        binding.status.text = "Myślę…"
        binding.lastMessage.text = text
        lifecycleScope.launch {
            try {
                var response = gemini.sendMessage(text)
                var guard = 0
                while (response.functionCall != null && guard < 5) {
                    val call = response.functionCall!!
                    val result = controller.execute(call)
                    if (call.name == "analyze_image") {
                        binding.status.text = "Tryb wizji jest wyłączony w trybie kierowcy."
                        busy = false
                        return@launch
                    }
                    response = gemini.sendFunctionResult(call.name, result)
                    guard++
                }
                val reply = response.text.ifBlank { "Zrobione." }
                binding.lastReply.text = reply
                voice.speak(reply)
            } catch (t: Throwable) {
                binding.status.text = "Błąd: ${t.message}"
                busy = false
            }
        }
    }

    // VoiceEngine.Listener
    override fun onListeningStarted() {
        binding.status.text = "Mów…"
    }

    override fun onPartialResult(text: String) {
        binding.status.text = "„$text…"
    }

    override fun onFinalResult(text: String) {
        sendToGemini(text)
    }

    override fun onRecognitionError(errorCode: Int) {
        binding.status.text = VoiceEngine.errorToText(errorCode)
        busy = false
    }

    override fun onSpeechStarted() {
        binding.status.text = "Mówię…"
    }

    override fun onSpeechFinished() {
        busy = false
        binding.status.text = "Dotknij, by mówić"
        // Tryb kierowcy: automatyczne wznowienie słuchania po odpowiedzi.
        binding.root.postDelayed({ if (!busy && !isFinishing) startTurn() }, 400)
    }

    override fun onBargeIn() {
        busy = false
        startTurn()
    }

    override fun onTtsReady(available: Boolean) = Unit
}
