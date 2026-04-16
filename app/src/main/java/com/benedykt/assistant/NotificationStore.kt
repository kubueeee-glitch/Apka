package com.benedykt.assistant

import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * In-memory bufor ostatnich powiadomień – napełniany przez
 * [BenedyktNotificationListener], czytany przez narzędzia Gemini.
 */
object NotificationStore {

    private const val MAX = 50

    data class Entry(
        val id: Int,
        val key: String,
        val pkg: String,
        val appLabel: String,
        val title: String,
        val text: String,
        val postedAt: Long,
        /** Czy do notyfikacji da się odpowiedzieć (pole tekstowe z RemoteInput). */
        val replyable: Boolean
    )

    private val list = Collections.synchronizedList(mutableListOf<Entry>())

    fun add(entry: Entry) {
        synchronized(list) {
            list.removeAll { it.key == entry.key }
            list += entry
            while (list.size > MAX) list.removeAt(0)
        }
    }

    fun remove(key: String) {
        synchronized(list) { list.removeAll { it.key == key } }
    }

    fun latest(limit: Int = 10, appFilter: String? = null): List<Entry> {
        val snapshot = synchronized(list) { list.toList() }
        val filtered = if (appFilter.isNullOrBlank()) snapshot
        else snapshot.filter {
            it.appLabel.contains(appFilter, true) || it.pkg.contains(appFilter, true)
        }
        return filtered.sortedByDescending { it.postedAt }.take(limit)
    }

    fun findReplyableFor(who: String): Entry? {
        val q = who.trim()
        if (q.isEmpty()) return latest(1).firstOrNull { it.replyable }
        val snapshot = synchronized(list) { list.toList() }
        return snapshot
            .filter { it.replyable }
            .sortedByDescending { it.postedAt }
            .firstOrNull {
                it.title.contains(q, true) ||
                    it.text.contains(q, true) ||
                    it.appLabel.contains(q, true)
            }
    }

    fun format(entries: List<Entry>): String {
        if (entries.isEmpty()) return "Brak powiadomień."
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        return entries.joinToString("\n") {
            val when_ = fmt.format(Date(it.postedAt))
            "[$when_] ${it.appLabel} • ${it.title}: ${it.text.take(120)}"
        }
    }

    fun clear() {
        synchronized(list) { list.clear() }
    }
}
