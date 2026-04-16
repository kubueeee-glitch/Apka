package com.benedykt.assistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.view.View
import android.widget.EditText
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.benedykt.assistant.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File

class MainActivity : AppCompatActivity(), VoiceEngine.Listener, PhoneController.Callbacks {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var voice: VoiceEngine
    private lateinit var controller: PhoneController
    private lateinit var gemini: GeminiClient
    private lateinit var memory: MemoryStore
    private lateinit var skills: SkillsStore
    private lateinit var notes: NotesStore
    private lateinit var personas: PersonaStore
    private val adapter = MessageAdapter()
    private var busy = false
    private var pendingVisionQuestion: String? = null
    private var cameraPhotoUri: Uri? = null

    private lateinit var cameraLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("benedykt", Context.MODE_PRIVATE)
        memory = MemoryStore(this)
        skills = SkillsStore(this)
        notes = NotesStore(this)
        personas = PersonaStore(this)
        controller = PhoneController(this, memory, skills, notes, personas, this)
        val effectiveKey = prefs.getString(KEY_API, "").orEmpty()
            .ifBlank { BuildConfig.GEMINI_API_KEY }
        gemini = GeminiClient(effectiveKey, memory, skills, personas)
        voice = VoiceEngine(this, this)

        cameraLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) handleCameraResult()
            else {
                addSystemMessage("Anulowano zdjęcie.")
                pendingVisionQuestion = null
                busy = false
                binding.statusText.text = getString(R.string.status_idle)
            }
        }

        binding.messages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.messages.adapter = adapter

        binding.micButton.setOnClickListener { startAssistantTurn() }
        binding.stopButton.setOnClickListener { stopCurrent() }
        binding.settingsButton.setOnClickListener { openSettingsMenu() }
        binding.personaButton.setOnClickListener { showPersonaPicker() }
        binding.sendButton.setOnClickListener {
            val t = binding.textInput.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty()) {
                binding.textInput.setText("")
                sendToGemini(t)
            }
        }

        ensurePermissions()
        voice.init()
        refreshPersonaBadge()

        addSystemMessage(
            "Cześć! Jestem Benedykt. Naciśnij mikrofon lub powiedz \"Hej Benedykt\"."
        )

        if (effectiveKey.isBlank()) {
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
                loopFunctionCalls(response)
            } catch (t: Throwable) {
                handleError(t)
            }
        }
    }

    private suspend fun loopFunctionCalls(first: GeminiResponse) {
        var response = first
        var guard = 0
        while (response.functionCall != null && guard < 10) {
            val call = response.functionCall!!
            addSystemMessage("[akcja: ${call.name}]")
            val result = controller.execute(call)

            if (call.name == "set_persona" && result.optString("status") == "ok") {
                runOnUiThread { refreshPersonaBadge() }
            }
            if (call.name == "analyze_image") {
                // Dalsza część konwersacji odbędzie się po powrocie z aparatu
                busy = false
                binding.statusText.text = getString(R.string.status_idle)
                return
            }

            response = gemini.sendFunctionResult(call.name, result)
            guard++
        }
        val reply = response.text.ifBlank { "Gotowe." }
        addAssistantMessage(reply)
        binding.statusText.text = getString(R.string.status_speaking)
        voice.speak(reply)
    }

    private fun handleError(t: Throwable) {
        val msg = t.message ?: "Błąd komunikacji z Gemini."
        addSystemMessage("Błąd: $msg")
        binding.statusText.text = getString(R.string.status_idle)
        busy = false
    }

    // --- Vision --- //

    override fun onRequestVision(question: String?) {
        pendingVisionQuestion = question
        runOnUiThread { startCameraCapture() }
    }

    override fun onRequestDriverMode() {
        runOnUiThread { startDriverMode() }
    }

    private fun startDriverMode() {
        startActivity(Intent(this, DriverModeActivity::class.java))
    }

    private fun startCameraCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), REQ_PERMS
            )
            return
        }
        val photo = File(cacheDir, "vision_" + System.currentTimeMillis() + ".jpg")
        val uri = FileProvider.getUriForFile(
            this, "$packageName.fileprovider", photo
        )
        cameraPhotoUri = uri
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        try {
            cameraLauncher.launch(intent)
        } catch (t: Throwable) {
            addSystemMessage("Nie udało się otworzyć aparatu: ${t.message}")
        }
    }

    private fun handleCameraResult() {
        val uri = cameraPhotoUri ?: return
        val bytes = compressImage(uri) ?: run {
            addSystemMessage("Nie udało się odczytać zdjęcia.")
            return
        }
        val question = pendingVisionQuestion
            ?: "Opisz krótko po polsku, co widzisz na tym zdjęciu."
        pendingVisionQuestion = null
        addSystemMessage("[wysyłam zdjęcie do analizy…]")
        busy = true
        binding.statusText.text = getString(R.string.status_thinking)
        lifecycleScope.launch {
            try {
                val response = gemini.sendImageMessage(question, bytes)
                loopFunctionCalls(response)
            } catch (t: Throwable) {
                handleError(t)
            }
        }
    }

    private fun compressImage(uri: Uri): ByteArray? {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val bmp = android.graphics.BitmapFactory.decodeStream(input)
                val scaled = scaleBitmap(bmp, 1280)
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
                out.toByteArray()
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun scaleBitmap(src: Bitmap, maxSide: Int): Bitmap {
        val w = src.width
        val h = src.height
        val longest = maxOf(w, h)
        if (longest <= maxSide) return src
        val ratio = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(src, (w * ratio).toInt(), (h * ratio).toInt(), true)
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

    private fun refreshPersonaBadge() {
        binding.personaButton.text = "\u2605 " + personas.current().label
    }

    // --- Menus --- //

    private fun openSettingsMenu() {
        val opts = arrayOf(
            "Klucz Gemini API",
            "Osobowość",
            "Wyczyść pamięć (${memory.all().size})",
            "Wyczyść historię rozmowy",
            "Moje skille (${skills.all().size})",
            "Moje notatki (${notes.all().size})",
            "Dostęp do powiadomień",
            "Dostępność (sterowanie ekranem)",
            "Home Assistant",
            "Tryb kierowcy"
        )
        AlertDialog.Builder(this)
            .setTitle("Ustawienia Benedykta")
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> askForApiKey()
                    1 -> showPersonaPicker()
                    2 -> confirmWipeMemory()
                    3 -> {
                        gemini.clearHistory()
                        addSystemMessage("Wyczyszczono historię rozmowy.")
                    }
                    4 -> showSkills()
                    5 -> showNotes()
                    6 -> openNotificationAccess()
                    7 -> openAccessibilitySettings()
                    8 -> configureHomeAssistant()
                    9 -> startDriverMode()
                }
            }
            .show()
    }

    private fun openNotificationAccess() {
        addSystemMessage(
            "Włącz 'Benedykt' na liście dostępu do powiadomień, żeby mógł je czytać i odpowiadać."
        )
        val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    private fun openAccessibilitySettings() {
        addSystemMessage(
            "Włącz usługę 'Benedykt' w Ustawieniach → Dostępność, żeby mógł czytać ekran i dotykać elementów."
        )
        val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    private fun configureHomeAssistant() {
        val ha = HomeAssistantClient(this)
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val urlInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "http://homeassistant.local:8123"
            setText(ha.baseUrl())
        }
        val tokenInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "Long-Lived Access Token"
            setText(ha.token())
        }
        layout.addView(urlInput)
        layout.addView(tokenInput)
        AlertDialog.Builder(this)
            .setTitle("Home Assistant")
            .setMessage(
                "Wklej adres swojej instancji Home Assistant oraz długotrwały token dostępu " +
                    "(Profil → Security → Long-Lived Access Tokens)."
            )
            .setView(layout)
            .setPositiveButton("Zapisz") { _, _ ->
                ha.configure(
                    urlInput.text.toString().trim(),
                    tokenInput.text.toString().trim()
                )
                addSystemMessage("Home Assistant skonfigurowany.")
            }
            .setNeutralButton("Wyłącz") { _, _ ->
                ha.clearConfig()
                addSystemMessage("Wyłączono Home Assistant.")
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    private fun showPersonaPicker() {
        val values = PersonaStore.Persona.values()
        val labels = values.map { it.label }.toTypedArray()
        val current = values.indexOf(personas.current())
        AlertDialog.Builder(this)
            .setTitle("Osobowość Benedykta")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                personas.set(values[which].id)
                refreshPersonaBadge()
                addSystemMessage("Zmiana osobowości: ${values[which].label}.")
                dialog.dismiss()
            }
            .setNegativeButton("Zamknij", null)
            .show()
    }

    private fun confirmWipeMemory() {
        AlertDialog.Builder(this)
            .setTitle("Wyczyścić całą pamięć Benedykta?")
            .setMessage("Zapomni wszystkie fakty o Tobie. Skille i notatki zostaną.")
            .setPositiveButton("Wyczyść") { _, _ ->
                memory.clear()
                addSystemMessage("Pamięć wyczyszczona.")
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    private fun showSkills() {
        val list = skills.all()
        if (list.isEmpty()) {
            addSystemMessage("Nie masz jeszcze zdefiniowanych skilli.")
            return
        }
        val body = list.joinToString("\n\n") { "• ${it.name}\n  ${it.description.ifBlank { it.steps }}" }
        AlertDialog.Builder(this)
            .setTitle("Twoje skille")
            .setMessage(body)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showNotes() {
        val list = notes.all()
        if (list.isEmpty()) {
            addSystemMessage("Nie masz notatek.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Twoje notatki")
            .setMessage(notes.format(list))
            .setPositiveButton("OK", null)
            .show()
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
        binding.root.postDelayed({
            if (!busy && !isFinishing) startAssistantTurn()
        }, 400)
    }

    override fun onBargeIn() {
        runOnUiThread {
            addSystemMessage("[przerwano – słucham]")
            busy = false
            startAssistantTurn()
        }
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
