package com.benedykt.assistant

import android.app.Notification
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Słucha powiadomień systemowych. Użytkownik musi ręcznie włączyć dostęp
 * w: Ustawienia → Powiadomienia → Dostęp do powiadomień.
 *
 * Zebrane powiadomienia lądują w [NotificationStore], a Benedykt może je
 * czytać i na nie odpowiadać przez function-calle Gemini.
 */
class BenedyktNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val n = sbn.notification ?: return
        val extras: Bundle = n.extras ?: Bundle()
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        // Ignoruj nasze własne powiadomienie foregroundowe.
        if (sbn.packageName == packageName) return
        if (title.isBlank() && text.isBlank()) return

        val appLabel = runCatching {
            val ai = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(ai).toString()
        }.getOrDefault(sbn.packageName)

        NotificationStore.add(
            NotificationStore.Entry(
                id = sbn.id,
                key = sbn.key,
                pkg = sbn.packageName,
                appLabel = appLabel,
                title = title,
                text = text,
                postedAt = sbn.postTime,
                replyable = findReplyAction(n) != null
            )
        )

        val intent = Intent(ACTION_NEW_NOTIFICATION).apply {
            setPackage(packageName)
            putExtra("app", appLabel)
            putExtra("title", title)
            putExtra("text", text)
            putExtra("key", sbn.key)
        }
        sendBroadcast(intent)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        NotificationStore.remove(sbn.key)
    }

    /** Próbuje odpowiedzieć na notyfikację tekstem. Zwraca true jeśli się udało. */
    fun replyTo(key: String, message: String): Boolean {
        val active = runCatching { activeNotifications }.getOrNull() ?: return false
        val sbn = active.firstOrNull { it.key == key } ?: return false
        val action = findReplyAction(sbn.notification) ?: return false
        val remoteInput = action.remoteInputs?.firstOrNull() ?: return false

        val intent = Intent()
        val bundle = Bundle().apply { putCharSequence(remoteInput.resultKey, message) }
        android.app.RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, bundle)
        return try {
            action.actionIntent.send(this, 0, intent)
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun findReplyAction(n: Notification): Notification.Action? {
        val actions = n.actions ?: return null
        return actions.firstOrNull { a ->
            val inputs = a.remoteInputs ?: return@firstOrNull false
            inputs.isNotEmpty()
        }
    }

    companion object {
        const val ACTION_NEW_NOTIFICATION = "com.benedykt.assistant.NEW_NOTIFICATION"

        /** Globalna referencja do aktualnie połączonej instancji. */
        @Volatile
        var instance: BenedyktNotificationListener? = null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }
}
