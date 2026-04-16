package com.benedykt.assistant

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Klient API Google Gemini (darmowy gemini-2.0-flash, obsługujący multimodal).
 *
 * Pobierz DARMOWY klucz na: https://aistudio.google.com/apikey
 *
 * Trzyma historię konwersacji oraz zestaw narzędzi (function calling).
 * System prompt jest generowany dynamicznie: zawiera aktualną pamięć
 * użytkownika, jego skille i wybraną osobowość.
 */
class GeminiClient(
    private var apiKey: String,
    private val memory: MemoryStore,
    private val skills: SkillsStore,
    private val personas: PersonaStore,
    private val model: String = "gemini-2.0-flash"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val history = mutableListOf<JSONObject>()

    private val basePrompt = """
        Jesteś Benedykt – inteligentny, ciepły asystent głosowy po polsku.
        Twoje odpowiedzi są odczytywane na głos, więc:
          - NIE używaj markdowna, gwiazdek, emoji ani list wypunktowanych.
          - Pisz krótko (1-3 zdania), chyba że użytkownik prosi o szczegóły.
          - Używaj naturalnej mówionej polszczyzny.
        Kiedy użytkownik prosi o operację na telefonie (otwórz apkę, zadzwoń,
        wyślij SMS, alarm, latarka, głośność, WiFi/Bluetooth, wyszukiwanie itd.)
        – WYWOŁAJ odpowiednią funkcję zamiast tylko mówić, że to zrobiłeś.
        Zapamiętuj ważne fakty o użytkowniku przez funkcję remember (imię,
        preferencje, bliscy, adresy, ulubione aplikacje). Nie pytaj za każdym
        razem o to samo – sprawdź swoją pamięć.
        Jeśli użytkownik definiuje własną komendę ("stwórz tryb nocny który…"),
        wywołaj create_skill. Gdy użytkownik wypowie nazwę zdefiniowanego skilla,
        wywołaj run_skill – zostanie Ci zwrócona lista kroków, wykonaj je w kolejności.
        Dla zwykłej rozmowy (small talk, pytania) – odpowiadaj tekstem bez funkcji.
    """.trimIndent()

    fun setApiKey(key: String) {
        apiKey = key
    }

    fun clearHistory() {
        history.clear()
    }

    suspend fun sendMessage(userText: String): GeminiResponse = withContext(Dispatchers.IO) {
        val userMessage = JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", userText)))
        }
        history.add(userMessage)
        val response = callApi()
        history.add(response.rawContent)
        response
    }

    /**
     * Wysyła wiadomość z obrazem (np. ze zdjęcia z aparatu) – do trybu wizji.
     */
    suspend fun sendImageMessage(
        userText: String,
        imageBytes: ByteArray,
        mimeType: String = "image/jpeg"
    ): GeminiResponse = withContext(Dispatchers.IO) {
        val parts = JSONArray()
        parts.put(JSONObject().put("text", userText.ifBlank { "Co widzisz na tym zdjęciu?" }))
        parts.put(
            JSONObject().put(
                "inlineData",
                JSONObject()
                    .put("mimeType", mimeType)
                    .put("data", Base64.encodeToString(imageBytes, Base64.NO_WRAP))
            )
        )
        val userMessage = JSONObject().put("role", "user").put("parts", parts)
        history.add(userMessage)
        val response = callApi()
        history.add(response.rawContent)
        response
    }

    suspend fun sendFunctionResult(
        name: String,
        result: JSONObject
    ): GeminiResponse = withContext(Dispatchers.IO) {
        val toolMessage = JSONObject().apply {
            put("role", "user")
            put(
                "parts",
                JSONArray().put(
                    JSONObject().put(
                        "functionResponse",
                        JSONObject()
                            .put("name", name)
                            .put("response", result)
                    )
                )
            )
        }
        history.add(toolMessage)
        val response = callApi()
        history.add(response.rawContent)
        response
    }

    private fun callApi(): GeminiResponse {
        if (apiKey.isBlank()) {
            throw IllegalStateException("Brak klucza API Gemini. Wklej go w ustawieniach.")
        }
        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val contents = JSONArray()
        history.forEach { contents.put(it) }

        val systemPrompt = buildString {
            append(basePrompt)
            append("\n\nAktywna osobowość: ")
            append(personas.current().prompt)
            val mem = memory.asPromptContext()
            if (mem.isNotBlank()) {
                append("\n\n")
                append(mem)
            }
            val sk = skills.asPromptContext()
            if (sk.isNotBlank()) {
                append("\n\n")
                append(sk)
            }
        }

        val body = JSONObject().apply {
            put("contents", contents)
            put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemPrompt))
                )
            )
            put("tools", JSONArray().put(JSONObject().put("functionDeclarations", buildTools())))
            put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.9)
                    .put("topP", 0.95)
                    .put("maxOutputTokens", 1024)
            )
            put(
                "safetySettings",
                JSONArray()
                    .put(safety("HARM_CATEGORY_HARASSMENT"))
                    .put(safety("HARM_CATEGORY_HATE_SPEECH"))
                    .put(safety("HARM_CATEGORY_SEXUALLY_EXPLICIT"))
                    .put(safety("HARM_CATEGORY_DANGEROUS_CONTENT"))
            )
        }

        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("Gemini ${resp.code}: $raw")
            }
            return parseResponse(JSONObject(raw))
        }
    }

    private fun safety(category: String): JSONObject =
        JSONObject().put("category", category).put("threshold", "BLOCK_ONLY_HIGH")

    private fun parseResponse(json: JSONObject): GeminiResponse {
        val candidates = json.optJSONArray("candidates")
            ?: throw RuntimeException("Brak kandydatów: $json")
        if (candidates.length() == 0) throw RuntimeException("Pusta odpowiedź modelu")
        val content = candidates.getJSONObject(0).getJSONObject("content")
        val parts = content.optJSONArray("parts") ?: JSONArray()
        val textBuilder = StringBuilder()
        var functionCall: FunctionCall? = null
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (part.has("functionCall")) {
                val fc = part.getJSONObject("functionCall")
                functionCall = FunctionCall(
                    name = fc.getString("name"),
                    args = fc.optJSONObject("args") ?: JSONObject()
                )
            } else if (part.has("text")) {
                textBuilder.append(part.getString("text"))
            }
        }
        if (!content.has("role")) content.put("role", "model")
        return GeminiResponse(
            text = textBuilder.toString().trim(),
            functionCall = functionCall,
            rawContent = content
        )
    }

    private fun buildTools(): JSONArray {
        val tools = JSONArray()

        // --- Sterowanie telefonem --- //
        tools.put(
            func(
                "open_app",
                "Otwiera aplikację zainstalowaną na telefonie po nazwie.",
                mapOf("app_name" to "Nazwa aplikacji np. 'Spotify'."),
                required = listOf("app_name")
            )
        )
        tools.put(
            func(
                "make_call",
                "Dzwoni pod numer lub do kontaktu po imieniu.",
                mapOf(
                    "number" to "Numer telefonu.",
                    "contact_name" to "Imię kontaktu z książki telefonicznej."
                )
            )
        )
        tools.put(
            func(
                "send_sms",
                "Wysyła wiadomość SMS.",
                mapOf(
                    "number" to "Numer odbiorcy.",
                    "contact_name" to "Imię kontaktu.",
                    "message" to "Treść wiadomości."
                ),
                required = listOf("message")
            )
        )
        tools.put(
            func(
                "set_alarm",
                "Ustawia alarm na podaną godzinę.",
                mapOf(
                    "hour" to "Godzina (0-23).",
                    "minute" to "Minuty (0-59).",
                    "label" to "Opcjonalna etykieta."
                ),
                required = listOf("hour", "minute")
            )
        )
        tools.put(
            func(
                "set_timer",
                "Ustawia minutnik na sekundy.",
                mapOf("seconds" to "Liczba sekund.", "label" to "Etykieta."),
                required = listOf("seconds")
            )
        )
        tools.put(
            func(
                "toggle_flashlight",
                "Włącza/wyłącza latarkę.",
                mapOf("on" to "true aby włączyć, false wyłączyć."),
                required = listOf("on")
            )
        )
        tools.put(
            func(
                "set_volume",
                "Ustawia głośność multimediów (0-100).",
                mapOf("percent" to "Głośność w procentach."),
                required = listOf("percent")
            )
        )
        tools.put(func("toggle_bluetooth", "Otwiera ustawienia Bluetooth.", emptyMap()))
        tools.put(func("toggle_wifi", "Otwiera ustawienia Wi-Fi.", emptyMap()))
        tools.put(
            func(
                "open_url",
                "Otwiera URL w przeglądarce.",
                mapOf("url" to "Pełny adres URL."),
                required = listOf("url")
            )
        )
        tools.put(
            func(
                "web_search",
                "Wyszukuje frazę w Google.",
                mapOf("query" to "Fraza."),
                required = listOf("query")
            )
        )
        tools.put(func("open_camera", "Otwiera Aparat.", emptyMap()))
        tools.put(func("take_photo", "Robi zdjęcie.", emptyMap()))
        tools.put(
            func(
                "open_maps",
                "Otwiera Mapy Google z celem nawigacji.",
                mapOf("destination" to "Adres/miejsce.")
            )
        )
        tools.put(
            func(
                "open_settings",
                "Otwiera ustawienia systemowe.",
                mapOf("section" to "'wifi', 'bluetooth', 'sound', 'display', 'battery', 'apps' lub puste.")
            )
        )
        tools.put(
            func(
                "send_whatsapp",
                "Wysyła wiadomość na WhatsApp.",
                mapOf("number" to "Numer (międzynarodowy).", "message" to "Treść."),
                required = listOf("number", "message")
            )
        )
        tools.put(
            func(
                "play_music",
                "Odtwarza muzykę (Spotify/YouTube Music).",
                mapOf("query" to "Utwór, artysta lub playlista.")
            )
        )
        tools.put(func("get_battery", "Stan baterii telefonu.", emptyMap()))
        tools.put(func("get_time", "Aktualny czas i data.", emptyMap()))

        // --- Pamięć --- //
        tools.put(
            func(
                "remember",
                "Zapamiętuje ważny fakt o użytkowniku, żeby pamiętać go między sesjami.",
                mapOf(
                    "topic" to "Krótki klucz, np. 'imię', 'praca', 'ulubiona_muzyka', 'mama'.",
                    "value" to "Wartość do zapamiętania."
                ),
                required = listOf("topic", "value")
            )
        )
        tools.put(
            func(
                "forget",
                "Zapomina fakt o podanym temacie.",
                mapOf("topic" to "Klucz do usunięcia."),
                required = listOf("topic")
            )
        )
        tools.put(func("list_memory", "Zwraca wszystkie zapamiętane fakty.", emptyMap()))

        // --- Skille / makra --- //
        tools.put(
            func(
                "create_skill",
                "Tworzy własny skill użytkownika (makro). " +
                    "'steps' to naturalny opis kroków – przy wywołaniu run_skill Gemini wykona je sekwencyjnie.",
                mapOf(
                    "name" to "Krótka nazwa skilla (np. 'tryb_nocny').",
                    "description" to "Krótki opis, co skill robi.",
                    "steps" to "Opis kroków w języku naturalnym."
                ),
                required = listOf("name", "steps")
            )
        )
        tools.put(
            func(
                "run_skill",
                "Zwraca kroki skilla, żeby je kolejno wykonać wywołaniami innych funkcji.",
                mapOf("name" to "Nazwa skilla."),
                required = listOf("name")
            )
        )
        tools.put(
            func(
                "delete_skill",
                "Usuwa skill.",
                mapOf("name" to "Nazwa skilla."),
                required = listOf("name")
            )
        )
        tools.put(func("list_skills", "Lista wszystkich skilli.", emptyMap()))

        // --- Notatki --- //
        tools.put(
            func(
                "save_note",
                "Zapisuje notatkę głosową z kategorią.",
                mapOf(
                    "content" to "Treść notatki.",
                    "category" to "Kategoria (np. 'zakupy', 'praca', 'pomysły')."
                ),
                required = listOf("content")
            )
        )
        tools.put(
            func(
                "list_notes",
                "Zwraca notatki (opcjonalnie filtrowane).",
                mapOf(
                    "query" to "Tekst do wyszukania.",
                    "category" to "Kategoria."
                )
            )
        )

        // --- Osobowości --- //
        tools.put(
            func(
                "set_persona",
                "Zmienia osobowość Benedykta.",
                mapOf(
                    "persona" to "Jedna z: " + PersonaStore.Persona.ids.joinToString(", ")
                ),
                required = listOf("persona")
            )
        )

        // --- Schowek --- //
        tools.put(func("read_clipboard", "Odczytuje zawartość systemowego schowka.", emptyMap()))
        tools.put(
            func(
                "write_clipboard",
                "Wpisuje tekst do systemowego schowka.",
                mapOf("text" to "Tekst do skopiowania."),
                required = listOf("text")
            )
        )

        // --- Wizja / kamera --- //
        tools.put(
            func(
                "analyze_image",
                "Otwiera aparat, robi zdjęcie i analizuje jego zawartość.",
                mapOf("question" to "Opcjonalne pytanie o zdjęcie (np. 'co to za roślina?').")
            )
        )

        // --- Briefing --- //
        tools.put(func("morning_briefing", "Szybkie podsumowanie: czas, bateria, pogoda.", emptyMap()))

        return tools
    }

    private fun func(
        name: String,
        description: String,
        params: Map<String, String>,
        required: List<String> = emptyList()
    ): JSONObject {
        val properties = JSONObject()
        params.forEach { (k, desc) ->
            val type = when (k) {
                "hour", "minute", "seconds", "percent" -> "integer"
                "on" -> "boolean"
                else -> "string"
            }
            properties.put(k, JSONObject().put("type", type).put("description", desc))
        }
        val parameters = JSONObject()
            .put("type", "object")
            .put("properties", properties)
        if (required.isNotEmpty()) {
            parameters.put("required", JSONArray(required))
        }
        return JSONObject()
            .put("name", name)
            .put("description", description)
            .put("parameters", parameters)
    }
}

data class FunctionCall(val name: String, val args: JSONObject)

data class GeminiResponse(
    val text: String,
    val functionCall: FunctionCall?,
    val rawContent: JSONObject
)
