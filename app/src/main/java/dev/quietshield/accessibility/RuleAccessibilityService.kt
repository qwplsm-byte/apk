package dev.quietshield.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.quietshield.QuietPrefs

class RuleAccessibilityService : AccessibilityService() {
    private val lastClick = mutableMapOf<String, Long>()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !QuietPrefs.isUiEnabled(this)) return
        if (QuietPrefs.bool(this, "schedule_enabled") && !QuietPrefs.bool(this, "schedule_active")) return
        val packageName = event.packageName?.toString() ?: return
        if (packageName !in QuietPrefs.packages(this, "ui_apps")) return
        val now = System.currentTimeMillis()
        if (now - (lastClick[packageName] ?: 0L) < 1_800) return
        val rules = QuietPrefs.uiRules(this).filter { it.enabled && (it.appPackage == null || it.appPackage == packageName) }
        if (rules.isEmpty()) return
        val root = rootInActiveWindow ?: return
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        var clicked = false
        while (queue.isNotEmpty() && visited++ < 240 && !clicked) {
            val node = queue.removeFirst()
            try {
                val text = node.text?.toString().orEmpty().ifBlank { node.contentDescription?.toString().orEmpty() }
                val rule = rules.firstOrNull { text.contains(it.keyword, ignoreCase = true) }
                if (rule != null && isSafeButton(node) && clickNodeOrParent(node)) {
                    lastClick[packageName] = now
                    QuietPrefs.addUiHit(this, packageName, rule.keyword)
                    clicked = true
                }
                if (!clicked) for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
            } finally {
                node.recycle()
            }
        }
        while (queue.isNotEmpty()) queue.removeFirst().recycle()
    }

    private fun isSafeButton(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser || !node.isEnabled) return false
        val text = node.text?.toString()?.trim()?.lowercase().orEmpty()
        val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
        val words = "$text $desc"
        val hasActionWord = listOf("跳过", "skip", "关闭", "close").any { word -> words.contains(word) } ||
            text == "×" || desc == "×" || text == "x" || desc == "x"
        if (!hasActionWord) return false
        val bounds = Rect().also(node::getBoundsInScreen)
        val parent = node.parent
        val clickableParent = try { parent?.isClickable == true } finally { parent?.recycle() }
        return bounds.width() > 0 && bounds.height() > 0 && (node.isClickable || clickableParent)
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val parent = node.parent ?: return false
        return try { parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK) } finally { parent.recycle() }
    }

    override fun onInterrupt() = Unit
}

