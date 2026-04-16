package com.benedykt.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Benedykt "widzi" ekran i może na niego dotykać – jak użytkownik.
 * Wymaga włączenia w Ustawieniach → Dostępność → Benedykt.
 *
 * Używane przez narzędzia Gemini: read_screen, tap_text, scroll.
 */
class BenedyktAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    /** Zrzut wszystkich widocznych napisów na ekranie (max 80 pozycji). */
    fun dumpScreenText(): String {
        val root = rootInActiveWindow ?: return ""
        val out = mutableListOf<String>()
        walk(root) { node ->
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            val str = listOf(t, d).firstOrNull { it.isNotBlank() }
            if (!str.isNullOrBlank() && out.size < 80 && str !in out) out += str
        }
        return out.joinToString("\n")
    }

    /** Tap w pierwszy widget, który zawiera podany tekst lub content description. */
    fun tapTextual(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findByText(root, text) ?: return false
        return clickNodeOrGesture(target)
    }

    /** Scroll w aktualnie widocznym kontenerze – kierunek: up/down/left/right. */
    fun scroll(direction: String): Boolean {
        val action = when (direction.lowercase()) {
            "down" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "up" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            "left" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            "right" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        val root = rootInActiveWindow ?: return false
        val scrollable = findScrollable(root) ?: return false
        return scrollable.performAction(action)
    }

    /** Naciska systemowy przycisk HOME/BACK/RECENTS. */
    fun pressSystemButton(name: String): Boolean {
        val action = when (name.lowercase()) {
            "home" -> GLOBAL_ACTION_HOME
            "back" -> GLOBAL_ACTION_BACK
            "recents", "overview" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return false
        }
        return performGlobalAction(action)
    }

    private fun walk(node: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        visit(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, visit)
        }
    }

    private fun findByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val q = text.lowercase()
        var found: AccessibilityNodeInfo? = null
        walk(root) { node ->
            if (found != null) return@walk
            val t = node.text?.toString()?.lowercase().orEmpty()
            val d = node.contentDescription?.toString()?.lowercase().orEmpty()
            if ((t.contains(q) || d.contains(q)) && node.isVisibleToUser) {
                found = node
            }
        }
        return found
    }

    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        walk(root) { node ->
            if (found == null && node.isScrollable) found = node
        }
        return found
    }

    private fun clickNodeOrGesture(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        // Spróbuj clicknąć rodzica (częsty pattern np. dla Compose)
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            parent = parent.parent
        }
        // Ostatecznie – gest tapnięcia w środek bounds
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() == 0 || bounds.height() == 0) return false
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        @Volatile
        var instance: BenedyktAccessibilityService? = null
    }
}
