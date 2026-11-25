package com.erezai.assistant

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ErezAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* Screen text is read only while explicitly enabled by the user. */ }
    override fun onInterrupt() {}

    fun clickText(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return clickMatching(root, label.lowercase())
    }

    private fun clickMatching(node: AccessibilityNodeInfo, label: String): Boolean {
        val text = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").lowercase()
        if (text.contains(label) && node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (clickMatching(child, label)) return true
        }
        return false
    }
}