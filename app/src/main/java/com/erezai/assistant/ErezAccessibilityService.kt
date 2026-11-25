package com.erezai.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ErezAccessibilityService : AccessibilityService() {
    companion object {
        const val ACTION_ACCESSIBILITY_TEXT = "com.erezai.assistant.ACCESSIBILITY_TEXT"
        const val EXTRA_ACCESSIBILITY_TEXT = "accessibility_text"
    }

    private var lastText = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val root = rootInActiveWindow ?: return
        val collected = StringBuilder()
        collectVisibleText(root, collected)
        val text = collected.toString().trim().take(5000)
        if (text.isNotBlank() && text != lastText) {
            lastText = text
            sendBroadcast(Intent(ACTION_ACCESSIBILITY_TEXT)
                .setPackage(packageName)
                .putExtra(EXTRA_ACCESSIBILITY_TEXT, text))
        }
    }

    private fun collectVisibleText(node: AccessibilityNodeInfo, out: StringBuilder) {
        if (!node.isVisibleToUser) return
        val value = node.text?.toString()?.trim().orEmpty()
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        if (value.isNotEmpty() && out.indexOf(value) < 0) out.append(value).append('\n')
        if (description.isNotEmpty() && out.indexOf(description) < 0) out.append(description).append('\n')
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectVisibleText(child, out)
        }
    }

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