package com.benedykt.assistant

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Klient REST API Home Assistant.
 * Konfigurowany w ustawieniach aplikacji – base URL + Long-Lived Access Token.
 *
 * Dokumentacja API: https://developers.home-assistant.io/docs/api/rest/
 */
class HomeAssistantClient(context: Context) {

    private val prefs = context.getSharedPreferences("benedykt_ha", Context.MODE_PRIVATE)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun isConfigured(): Boolean = baseUrl().isNotBlank() && token().isNotBlank()

    fun baseUrl(): String = prefs.getString(KEY_URL, "").orEmpty().trimEnd('/')
    fun token(): String = prefs.getString(KEY_TOKEN, "").orEmpty()

    fun configure(url: String, token: String) {
        prefs.edit()
            .putString(KEY_URL, url.trim())
            .putString(KEY_TOKEN, token.trim())
            .apply()
    }

    fun clearConfig() = prefs.edit().clear().apply()

    fun listEntities(domain: String?): JSONArray {
        val resp = get("/api/states") ?: return JSONArray()
        val arr = JSONArray(resp)
        if (domain.isNullOrBlank()) return arr
        val filtered = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("entity_id").startsWith("$domain.")) filtered.put(o)
        }
        return filtered
    }

    fun getState(entityId: String): JSONObject? {
        val resp = get("/api/states/$entityId") ?: return null
        return runCatching { JSONObject(resp) }.getOrNull()
    }

    fun callService(
        domain: String,
        service: String,
        entityId: String?,
        data: JSONObject?
    ): String? {
        val payload = JSONObject().apply {
            if (!entityId.isNullOrBlank()) put("entity_id", entityId)
            data?.keys()?.forEach { k -> put(k, data.get(k)) }
        }
        return post("/api/services/$domain/$service", payload.toString())
    }

    private fun get(path: String): String? {
        if (!isConfigured()) return null
        val req = Request.Builder()
            .url(baseUrl() + path)
            .header("Authorization", "Bearer ${token()}")
            .get()
            .build()
        return runCatching {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.string()
            }
        }.getOrNull()
    }

    private fun post(path: String, jsonBody: String): String? {
        if (!isConfigured()) return null
        val req = Request.Builder()
            .url(baseUrl() + path)
            .header("Authorization", "Bearer ${token()}")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.string()
            }
        }.getOrNull()
    }

    companion object {
        private const val KEY_URL = "url"
        private const val KEY_TOKEN = "token"
    }
}
