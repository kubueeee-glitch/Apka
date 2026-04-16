package com.benedykt.assistant

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
 * Klient API Google Gemini (darmowy gemini-2.0-flash).
 *
 * Pobierz DARMOWY klucz na: https://aistudio.google.com/apikey
 *
 * Utrzymuje historię konwersacji oraz listę narzędzi (function calling),
 * dzięki którym model może sterować telefonem.
 */
class GeminiClient(
    private var apiKey: String,
    private val model: String = "gemini-2.0-flash"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val history = mutableListOf<JSONObject>()

    private val systemInstruction = """
        Jesteś Benedykt – inteligentny, przyjacielski asystent głosowy na telefon w języku polskim.
        Zwracaj się do użytkownika zwięźle, naturalnie i ciepło, jak dobry kumpel.
        Odpowiadaj tylko po polsku, chyba że użytkownik wyraźnie zmieni język.
        Twoje odpowiedzi są odczytywane na głos, więc:
          - NIE używaj markdowna, gwiazdek, emoji ani list wypunktowanych.
          - Pisz krótko (1-3 zdania), chyba że użytkownik prosi o szczegóły.
          - Używaj naturalnej, mówionej polszczyzny.
        Kiedy użytkownik prosi Cię o operację na telefonie (otwórz aplikację, zadzwoń,
        wyślij SMS, ustaw alarm, włącz latarkę, zmień głośność, włącz WiFi/Bluetooth,
        wyszukaj coś itp.) – WYWOŁAJ odpowiednią funkcję zamiast tylko mówić, że to zrobiłeś.
        Po udanym wywołaniu funkcji potwierdź krótko po polsku, np. "Już otwieram…".
        Jeśli rozmowa jest zwykła (small talk, pytania, rozmowa) – po prostu odpowiedz tekstem,
        bez wywoływania funkcji.
    """.trimIndent()

    fun setApiKey(key: String) {
        apiKey = key
    }

    fun clearHistory() {
        history.clear()
    }

    /**
     * Wysyła wiadomość użytkownika do Gemini i zwraca odpowiedź.
     * Wynik to albo tekst do wypowiedzenia, albo wywołanie funkcji (tool call).
     */
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
     * Wysyła do modelu wynik wykonanej funkcji, żeby dostać finalną odpowiedź głosową.
     */
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

        val body = JSONObject().apply {
            put("contents", contents)
            put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemInstruction))
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
        // Upewnij się, że w historii ten obiekt ma poprawną rolę "model"
        if (!content.has("role")) content.put("role", "model")
        return GeminiResponse(
            text = textBuilder.toString().trim(),
            functionCall = functionCall,
            rawContent = content
        )
    }

    /** Lista funkcji (narzędzi), które model może wywołać. */
    private fun buildTools(): JSONArray {
        val tools = JSONArray()

        tools.put(
            func(
                "open_app",
                "Otwiera aplikację zainstalowaną na telefonie po jej nazwie (np. Spotify, YouTube, Ustawienia, Aparat).",
                mapOf("app_name" to "Nazwa aplikacji do otwarcia, np. 'Spotify'."),
                required = listOf("app_name")
            )
        )
        tools.put(
            func(
                "make_call",
                "Dzwoni pod podany numer telefonu lub do kontaktu po imieniu.",
                mapOf(
                    "number" to "Numer telefonu w formacie międzynarodowym lub krajowym, np. '+48123456789'.",
                    "contact_name" to "Zamiast numeru – imię kontaktu z książki telefonicznej."
                )
            )
        )
        tools.put(
            func(
                "send_sms",
                "Wysyła wiadomość SMS na podany numer lub do kontaktu.",
                mapOf(
                    "number" to "Numer odbiorcy (opcjonalne, jeśli podano contact_name).",
                    "contact_name" to "Imię kontaktu (opcjonalne, jeśli podano number).",
                    "message" to "Treść wiadomości SMS."
                ),
                required = listOf("message")
            )
        )
        tools.put(
            func(
                "set_alarm",
                "Ustawia alarm na podaną godzinę.",
                mapOf(
                    "hour" to "Godzina (0-23) jako liczba całkowita.",
                    "minute" to "Minuty (0-59) jako liczba całkowita.",
                    "label" to "Opcjonalna etykieta alarmu."
                ),
                required = listOf("hour", "minute")
            )
        )
        tools.put(
            func(
                "set_timer",
                "Ustawia minutnik na podaną liczbę sekund.",
                mapOf(
                    "seconds" to "Liczba sekund minutnika.",
                    "label" to "Opcjonalna etykieta."
                ),
                required = listOf("seconds")
            )
        )
        tools.put(
            func(
                "toggle_flashlight",
                "Włącza lub wyłącza latarkę (tylną lampę błyskową).",
                mapOf("on" to "true aby włączyć, false aby wyłączyć."),
                required = listOf("on")
            )
        )
        tools.put(
            func(
                "set_volume",
                "Ustawia głośność multimediów w procentach (0-100).",
                mapOf("percent" to "Głośność w procentach (0-100)."),
                required = listOf("percent")
            )
        )
        tools.put(
            func(
                "toggle_bluetooth",
                "Otwiera ustawienia Bluetooth, żeby użytkownik mógł go włączyć/wyłączyć.",
                emptyMap()
            )
        )
        tools.put(
            func(
                "toggle_wifi",
                "Otwiera ustawienia Wi-Fi.",
                emptyMap()
            )
        )
        tools.put(
            func(
                "open_url",
                "Otwiera podany adres URL w przeglądarce.",
                mapOf("url" to "Pełny adres URL, np. https://example.com"),
                required = listOf("url")
            )
        )
        tools.put(
            func(
                "web_search",
                "Wyszukuje podaną frazę w Google (otwiera wyniki w przeglądarce).",
                mapOf("query" to "Fraza do wyszukania."),
                required = listOf("query")
            )
        )
        tools.put(
            func(
                "open_camera",
                "Otwiera aplikację Aparat.",
                emptyMap()
            )
        )
        tools.put(
            func(
                "take_photo",
                "Robi zdjęcie (otwiera Aparat w trybie foto).",
                emptyMap()
            )
        )
        tools.put(
            func(
                "open_maps",
                "Otwiera Mapy Google i nawiguje do podanego miejsca (opcjonalnie).",
                mapOf("destination" to "Cel podróży lub adres.")
            )
        )
        tools.put(
            func(
                "open_settings",
                "Otwiera systemowe ustawienia telefonu (opcjonalnie konkretną sekcję).",
                mapOf("section" to "Sekcja ustawień: 'wifi', 'bluetooth', 'sound', 'display', 'battery', 'apps' lub puste.")
            )
        )
        tools.put(
            func(
                "send_whatsapp",
                "Wysyła wiadomość na WhatsApp do kontaktu.",
                mapOf(
                    "number" to "Numer w formacie międzynarodowym, np. '+48123456789'.",
                    "message" to "Treść wiadomości."
                ),
                required = listOf("number", "message")
            )
        )
        tools.put(
            func(
                "play_music",
                "Odtwarza muzykę (otwiera Spotify/YouTube Music z podanym zapytaniem).",
                mapOf("query" to "Nazwa utworu, artysty lub playlisty.")
            )
        )
        tools.put(
            func(
                "get_battery",
                "Zwraca aktualny stan baterii telefonu.",
                emptyMap()
            )
        )
        tools.put(
            func(
                "get_time",
                "Zwraca aktualną godzinę i datę.",
                emptyMap()
            )
        )
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
