package com.benedykt.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Notatki głosowe dyktowane do Benedykta z automatyczną kategoryzacją. */
class NotesStore(context: Context) {

    private val prefs = context.getSharedPreferences("benedykt_notes", Context.MODE_PRIVATE)

    fun all(): List<Note> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val list = mutableListOf<Note>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list += Note(
                id = o.optString("id"),
                content = o.optString("content"),
                category = o.optString("category", "ogólne"),
                createdAt = o.optLong("createdAt", 0L)
            )
        }
        return list.sortedByDescending { it.createdAt }
    }

    fun add(content: String, category: String?): Note {
        val list = all().toMutableList()
        val note = Note(
            id = System.currentTimeMillis().toString(36),
            content = content.trim(),
            category = category?.takeIf { it.isNotBlank() } ?: "ogólne",
            createdAt = System.currentTimeMillis()
        )
        list += note
        save(list)
        return note
    }

    fun filter(query: String?, category: String?): List<Note> {
        val q = query?.lowercase(Locale.getDefault())?.trim().orEmpty()
        val c = category?.lowercase(Locale.getDefault())?.trim().orEmpty()
        return all().filter { note ->
            (q.isEmpty() || note.content.lowercase(Locale.getDefault()).contains(q)) &&
                (c.isEmpty() || note.category.lowercase(Locale.getDefault()) == c)
        }
    }

    fun format(notes: List<Note>): String {
        if (notes.isEmpty()) return "Brak notatek."
        val fmt = SimpleDateFormat("d MMM, HH:mm", Locale("pl", "PL"))
        return notes.take(20).joinToString("\n") {
            "[${it.category}] ${fmt.format(Date(it.createdAt))}: ${it.content}"
        }
    }

    private fun save(list: List<Note>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("content", it.content)
                    .put("category", it.category)
                    .put("createdAt", it.createdAt)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    data class Note(
        val id: String,
        val content: String,
        val category: String,
        val createdAt: Long
    )

    companion object {
        private const val KEY = "notes"
    }
}
