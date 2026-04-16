package com.benedykt.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Własne makra / skille użytkownika.
 *
 * Przykład: "Benedykt, zapamiętaj tryb nocny: wyłącz WiFi, ustaw głośność 0
 * i alarm na 7:00". Benedykt tworzy skill `tryb_nocny` i od teraz wystarczy
 * powiedzieć "tryb nocny", żeby Gemini uruchomił wszystkie kroki jednym
 * wywołaniem funkcji `run_skill`.
 *
 * Gemini sam rozkłada skill na sekwencję pojedynczych function calls.
 */
class SkillsStore(context: Context) {

    private val prefs = context.getSharedPreferences("benedykt_skills", Context.MODE_PRIVATE)

    fun all(): List<Skill> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = mutableListOf<Skill>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += Skill(
                name = o.optString("name"),
                description = o.optString("description"),
                steps = o.optString("steps")
            )
        }
        return out
    }

    fun findByName(name: String): Skill? =
        all().firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun upsert(name: String, description: String, steps: String): Skill {
        val list = all().toMutableList()
        list.removeAll { it.name.equals(name, ignoreCase = true) }
        val skill = Skill(name, description, steps)
        list += skill
        save(list)
        return skill
    }

    fun delete(name: String): Boolean {
        val list = all().toMutableList()
        val removed = list.removeAll { it.name.equals(name, ignoreCase = true) }
        if (removed) save(list)
        return removed
    }

    fun asPromptContext(): String {
        val list = all()
        if (list.isEmpty()) return ""
        val lines = list.joinToString("\n") {
            "- \"${it.name}\" → ${it.description.ifBlank { it.steps }}"
        }
        return "Zdefiniowane skille użytkownika (gdy użytkownik wypowie nazwę, wywołaj run_skill):\n$lines"
    }

    private fun save(list: List<Skill>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("name", it.name)
                    .put("description", it.description)
                    .put("steps", it.steps)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    data class Skill(val name: String, val description: String, val steps: String)

    companion object {
        private const val KEY = "skills"
    }
}
