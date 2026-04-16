package com.benedykt.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Trwała pamięć faktów o użytkowniku. Benedykt zapamiętuje imiona, preferencje,
 * ulubione miejsca/aplikacje itp. Dzięki temu po restarcie nadal zna użytkownika –
 * inaczej niż bezstanowe ChatGPT/Gemini.
 */
class MemoryStore(context: Context) {

    private val prefs = context.getSharedPreferences("benedykt_memory", Context.MODE_PRIVATE)

    /** Zwraca listę wszystkich faktów (w kolejności dodania, najnowsze na końcu). */
    fun all(): List<Fact> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val list = mutableListOf<Fact>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list += Fact(
                id = o.optString("id"),
                topic = o.optString("topic"),
                value = o.optString("value"),
                createdAt = o.optLong("createdAt", 0L)
            )
        }
        return list
    }

    fun remember(topic: String, value: String): Fact {
        val list = all().toMutableList()
        // Deduplikacja po temacie – nadpisujemy wartość
        list.removeAll { it.topic.equals(topic, ignoreCase = true) }
        val fact = Fact(
            id = System.currentTimeMillis().toString(36),
            topic = topic,
            value = value,
            createdAt = System.currentTimeMillis()
        )
        list += fact
        save(list)
        return fact
    }

    fun forget(topic: String): Boolean {
        val list = all().toMutableList()
        val removed = list.removeAll { it.topic.equals(topic, ignoreCase = true) }
        if (removed) save(list)
        return removed
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    /** Kompaktowy tekst faktów do wstrzyknięcia w system prompt. */
    fun asPromptContext(): String {
        val facts = all()
        if (facts.isEmpty()) return ""
        val lines = facts.joinToString("\n") { "- ${it.topic}: ${it.value}" }
        return "Oto co pamiętasz o użytkowniku (używaj tego w rozmowie, ale nie recytuj bez powodu):\n$lines"
    }

    private fun save(list: List<Fact>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("topic", it.topic)
                    .put("value", it.value)
                    .put("createdAt", it.createdAt)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    data class Fact(val id: String, val topic: String, val value: String, val createdAt: Long)

    companion object {
        private const val KEY = "facts"
    }
}
