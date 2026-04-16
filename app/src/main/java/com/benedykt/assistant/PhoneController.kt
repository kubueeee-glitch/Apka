package com.benedykt.assistant

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Wykonuje function calls modelu – zarówno akcje telefonu, jak i
 * zarządzanie pamięcią, skillami, notatkami, osobowościami i wizją.
 */
class PhoneController(
    private val context: Context,
    private val memory: MemoryStore,
    private val skills: SkillsStore,
    private val notes: NotesStore,
    private val personas: PersonaStore,
    private val callbacks: Callbacks
) {

    /** Callbacki do MainActivity dla funkcji wymagających UI (np. aparat). */
    interface Callbacks {
        fun onRequestVision(question: String?)
    }

    fun execute(call: FunctionCall): JSONObject {
        return try {
            when (call.name) {
                // --- Telefon --- //
                "open_app" -> openApp(call.args.optString("app_name"))
                "make_call" -> makeCall(
                    call.args.optString("number"),
                    call.args.optString("contact_name")
                )
                "send_sms" -> sendSms(
                    call.args.optString("number"),
                    call.args.optString("contact_name"),
                    call.args.optString("message")
                )
                "set_alarm" -> setAlarm(
                    call.args.optInt("hour", -1),
                    call.args.optInt("minute", 0),
                    call.args.optString("label")
                )
                "set_timer" -> setTimer(
                    call.args.optInt("seconds", 60),
                    call.args.optString("label")
                )
                "toggle_flashlight" -> toggleFlashlight(call.args.optBoolean("on", true))
                "set_volume" -> setVolume(call.args.optInt("percent", 50))
                "toggle_bluetooth" -> openSettings("bluetooth")
                "toggle_wifi" -> openSettings("wifi")
                "open_url" -> openUrl(call.args.optString("url"))
                "web_search" -> webSearch(call.args.optString("query"))
                "open_camera" -> openCamera()
                "take_photo" -> takePhoto()
                "open_maps" -> openMaps(call.args.optString("destination"))
                "open_settings" -> openSettings(call.args.optString("section"))
                "send_whatsapp" -> sendWhatsapp(
                    call.args.optString("number"),
                    call.args.optString("message")
                )
                "play_music" -> playMusic(call.args.optString("query"))
                "get_battery" -> getBattery()
                "get_time" -> getTime()

                // --- Pamięć --- //
                "remember" -> remember(
                    call.args.optString("topic"),
                    call.args.optString("value")
                )
                "forget" -> forget(call.args.optString("topic"))
                "list_memory" -> listMemory()

                // --- Skille --- //
                "create_skill" -> createSkill(
                    call.args.optString("name"),
                    call.args.optString("description"),
                    call.args.optString("steps")
                )
                "run_skill" -> runSkill(call.args.optString("name"))
                "delete_skill" -> deleteSkill(call.args.optString("name"))
                "list_skills" -> listSkills()

                // --- Notatki --- //
                "save_note" -> saveNote(
                    call.args.optString("content"),
                    call.args.optString("category")
                )
                "list_notes" -> listNotes(
                    call.args.optString("query"),
                    call.args.optString("category")
                )

                // --- Osobowość --- //
                "set_persona" -> setPersona(call.args.optString("persona"))

                // --- Schowek --- //
                "read_clipboard" -> readClipboard()
                "write_clipboard" -> writeClipboard(call.args.optString("text"))

                // --- Wizja --- //
                "analyze_image" -> analyzeImage(call.args.optString("question"))

                // --- Briefing --- //
                "morning_briefing" -> morningBriefing()

                else -> fail("Nieznana funkcja: ${call.name}")
            }
        } catch (t: Throwable) {
            fail("Błąd: ${t.message}")
        }
    }

    // --- Telefon --- //

    private fun openApp(appName: String): JSONObject {
        if (appName.isBlank()) return fail("Brak nazwy aplikacji.")
        val pm = context.packageManager
        val target = appName.lowercase(Locale.getDefault()).trim()
        val candidates = pm.getInstalledApplications(0)
        val match = candidates.firstOrNull {
            val label = pm.getApplicationLabel(it).toString().lowercase(Locale.getDefault())
            label == target || label.contains(target) || target.contains(label)
        } ?: return fail("Nie znalazłem aplikacji \"$appName\".")

        val launch = pm.getLaunchIntentForPackage(match.packageName)
            ?: return fail("Nie mogę uruchomić aplikacji \"$appName\".")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return ok("Otwieram $appName.")
    }

    private fun makeCall(numberArg: String?, contactName: String?): JSONObject {
        val number = resolveNumber(numberArg, contactName) ?: return fail("Nie znalazłem numeru.")
        val intent = Intent(Intent.ACTION_CALL, "tel:$number".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ok("Dzwonię pod $number.")
    }

    private fun sendSms(
        numberArg: String?,
        contactName: String?,
        message: String
    ): JSONObject {
        if (message.isBlank()) return fail("Brak treści wiadomości.")
        val number = resolveNumber(numberArg, contactName)
        val intent = if (number != null) {
            Intent(Intent.ACTION_SENDTO, "smsto:$number".toUri()).apply {
                putExtra("sms_body", message)
            }
        } else {
            val defaultSms = Telephony.Sms.getDefaultSmsPackage(context)
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                if (defaultSms != null) setPackage(defaultSms)
                putExtra("sms_body", message)
                putExtra(Intent.EXTRA_TEXT, message)
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ok("Otwieram SMS z wiadomością.")
    }

    private fun setAlarm(hour: Int, minute: Int, label: String?): JSONObject {
        if (hour !in 0..23 || minute !in 0..59) return fail("Nieprawidłowa godzina.")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ok(
            "Ustawiłem alarm na ${"%02d:%02d".format(hour, minute)}."
        )
    }

    private fun setTimer(seconds: Int, label: String?): JSONObject {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ok("Ustawiłem minutnik na $seconds sekund.")
    }

    @SuppressLint("MissingPermission")
    private fun toggleFlashlight(on: Boolean): JSONObject {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return fail("Brak latarki.")
        cm.setTorchMode(id, on)
        return ok(if (on) "Włączyłem latarkę." else "Wyłączyłem latarkę.")
    }

    private fun setVolume(percent: Int): JSONObject {
        val clamped = percent.coerceIn(0, 100)
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val value = (max * clamped / 100.0).toInt()
        am.setStreamVolume(AudioManager.STREAM_MUSIC, value, AudioManager.FLAG_SHOW_UI)
        return ok("Ustawiłem głośność na $clamped procent.")
    }

    private fun openUrl(url: String): JSONObject {
        if (url.isBlank()) return fail("Brak adresu URL.")
        val normalized = if (url.startsWith("http")) url else "https://$url"
        val intent = Intent(Intent.ACTION_VIEW, normalized.toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ok("Otwieram $normalized.")
    }

    private fun webSearch(query: String): JSONObject {
        if (query.isBlank()) return fail("Brak zapytania.")
        val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra("query", query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            val url = "https://www.google.com/search?q=" + Uri.encode(query)
            context.startActivity(
                Intent(Intent.ACTION_VIEW, url.toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        return ok("Szukam: $query.")
    }

    private fun openCamera(): JSONObject {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ok("Otwieram aparat.")
    }

    private fun takePhoto(): JSONObject = openCamera()

    private fun openMaps(destination: String?): JSONObject {
        val uri = if (destination.isNullOrBlank()) {
            "geo:0,0?q=".toUri()
        } else {
            ("geo:0,0?q=" + Uri.encode(destination)).toUri()
        }
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .setPackage("com.google.android.apps.maps")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        return ok("Otwieram Mapy${destination?.let { " – nawigacja do $it" } ?: ""}.")
    }

    private fun openSettings(section: String?): JSONObject {
        val action = when (section?.lowercase(Locale.getDefault())) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "battery" -> "android.settings.BATTERY_SAVER_SETTINGS"
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ok("Otwieram ustawienia.")
    }

    private fun sendWhatsapp(number: String, message: String): JSONObject {
        if (number.isBlank() || message.isBlank()) return fail("Brak numeru lub treści.")
        val clean = number.replace("+", "").replace(" ", "").replace("-", "")
        val url = "https://wa.me/$clean?text=" + Uri.encode(message)
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            .setPackage("com.whatsapp")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, url.toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        return ok("Wysyłam wiadomość na WhatsApp.")
    }

    private fun playMusic(query: String?): JSONObject {
        val q = query?.takeIf { it.isNotBlank() }
        val pm = context.packageManager
        val spotify = pm.getLaunchIntentForPackage("com.spotify.music")
        val ytMusic = pm.getLaunchIntentForPackage("com.google.android.apps.youtube.music")
        val launch = spotify ?: ytMusic
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            return ok(if (q != null) "Puszczam $q." else "Włączam muzykę.")
        }
        val url = "https://music.youtube.com/search?q=" + Uri.encode(q ?: "muzyka")
        context.startActivity(
            Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return ok("Szukam muzyki w YouTube Music.")
    }

    private fun getBattery(): JSONObject {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return ok("Bateria: $level procent${if (charging) ", ładuje się" else ""}.")
            .put("level", level)
            .put("charging", charging)
    }

    private fun getTime(): JSONObject {
        val now = Date()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        val date = SimpleDateFormat("EEEE, d MMMM yyyy", Locale("pl", "PL")).format(now)
        return ok("Jest $time, $date.")
            .put("time", time)
            .put("date", date)
    }

    // --- Pamięć --- //

    private fun remember(topic: String, value: String): JSONObject {
        if (topic.isBlank() || value.isBlank()) return fail("Potrzebny temat i wartość.")
        memory.remember(topic, value)
        return ok("Zapamiętałem: $topic – $value.")
    }

    private fun forget(topic: String): JSONObject {
        if (topic.isBlank()) return fail("Podaj temat do zapomnienia.")
        val removed = memory.forget(topic)
        return if (removed) ok("Zapomniałem o $topic.")
        else fail("Nie miałem nic zapisanego dla '$topic'.")
    }

    private fun listMemory(): JSONObject {
        val facts = memory.all()
        val arr = JSONArray()
        facts.forEach {
            arr.put(JSONObject().put("topic", it.topic).put("value", it.value))
        }
        return ok(if (facts.isEmpty()) "Pusta pamięć." else "Pamiętam ${facts.size} faktów.")
            .put("facts", arr)
    }

    // --- Skille --- //

    private fun createSkill(name: String, description: String, steps: String): JSONObject {
        if (name.isBlank() || steps.isBlank()) return fail("Potrzebna nazwa i kroki.")
        skills.upsert(name.trim(), description.trim(), steps.trim())
        return ok("Skill \"$name\" zapisany.")
    }

    private fun runSkill(name: String): JSONObject {
        val s = skills.findByName(name) ?: return fail("Nie znam skilla \"$name\".")
        return ok("Kroki skilla '${s.name}'.")
            .put("name", s.name)
            .put("description", s.description)
            .put("steps", s.steps)
    }

    private fun deleteSkill(name: String): JSONObject {
        val removed = skills.delete(name)
        return if (removed) ok("Usunąłem skill \"$name\".")
        else fail("Nie ma skilla \"$name\".")
    }

    private fun listSkills(): JSONObject {
        val list = skills.all()
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("name", it.name)
                    .put("description", it.description)
            )
        }
        return ok(if (list.isEmpty()) "Brak skilli." else "Masz ${list.size} skilli.")
            .put("skills", arr)
    }

    // --- Notatki --- //

    private fun saveNote(content: String, category: String?): JSONObject {
        if (content.isBlank()) return fail("Notatka jest pusta.")
        val note = notes.add(content, category)
        return ok("Zapisałem notatkę w kategorii '${note.category}'.")
    }

    private fun listNotes(query: String?, category: String?): JSONObject {
        val filtered = notes.filter(query, category)
        val arr = JSONArray()
        filtered.take(20).forEach {
            arr.put(
                JSONObject()
                    .put("category", it.category)
                    .put("content", it.content)
                    .put("createdAt", it.createdAt)
            )
        }
        return ok("Znalazłem ${filtered.size} notatek.")
            .put("notes", arr)
            .put("formatted", notes.format(filtered))
    }

    // --- Osobowość --- //

    private fun setPersona(personaId: String): JSONObject {
        if (personaId.isBlank()) return fail("Podaj nazwę osobowości.")
        val p = personas.set(personaId)
        return ok("Przełączam się na tryb: ${p.label}.")
    }

    // --- Schowek --- //

    private fun readClipboard(): JSONObject {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val text = (0 until (clip?.itemCount ?: 0))
            .mapNotNull { clip?.getItemAt(it)?.coerceToText(context)?.toString() }
            .joinToString("\n")
        return if (text.isBlank()) fail("Schowek jest pusty.")
        else ok("Schowek odczytany.").put("text", text)
    }

    private fun writeClipboard(text: String): JSONObject {
        if (text.isBlank()) return fail("Pusty tekst.")
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Benedykt", text))
        return ok("Skopiowałem tekst do schowka.")
    }

    // --- Wizja --- //

    private fun analyzeImage(question: String?): JSONObject {
        callbacks.onRequestVision(question?.takeIf { it.isNotBlank() })
        return ok("Włączam aparat – zrób zdjęcie, a potem opiszę co widzę.")
    }

    // --- Briefing --- //

    private fun morningBriefing(): JSONObject {
        val time = getTime().optString("time")
        val date = getTime().optString("date")
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val text = "Jest $time, $date. Bateria: $level procent" +
            if (charging) ", ładuje się." else "."
        return ok(text)
            .put("time", time)
            .put("date", date)
            .put("battery_level", level)
            .put("battery_charging", charging)
    }

    // --- Helpers --- //

    private fun resolveNumber(number: String?, contactName: String?): String? {
        if (!number.isNullOrBlank()) return number
        if (contactName.isNullOrBlank()) return null
        return lookupContact(contactName)
    }

    private fun lookupContact(name: String): String? {
        val cr: ContentResolver = context.contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%$name%")
        cr.query(uri, projection, selection, args, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (idx >= 0) return c.getString(idx)
            }
        }
        return null
    }

    private fun ok(msg: String): JSONObject =
        JSONObject().put("status", "ok").put("message", msg)

    private fun fail(msg: String): JSONObject =
        JSONObject().put("status", "error").put("message", msg)
}
