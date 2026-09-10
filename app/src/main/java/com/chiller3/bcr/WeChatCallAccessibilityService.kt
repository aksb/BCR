/*
 * SPDX-FileCopyrightText: 2026 aksb
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Diagnostic-only accessibility service.
 *
 * This does NOT detect calls and does NOT record anything yet. Its only job right now is to
 * log WeChat's (com.tencent.mm) window class name and visible on-screen text every time its
 * foreground window changes, so that the specific text/class name pattern that appears during
 * an actual voice/video call can be identified from logcat.
 *
 * Once that pattern is known, this class will be extended to recognize "call started" /
 * "call ended" and expose that as a proper state change instead of raw logging.
 */
class WeChatCallAccessibilityService : AccessibilityService() {
    companion object {
        private val TAG = WeChatCallAccessibilityService::class.java.simpleName
        private const val WECHAT_PACKAGE = "com.tencent.mm"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected and watching $WECHAT_PACKAGE")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName?.toString() != WECHAT_PACKAGE) {
            return
        }

        val eventTypeName = AccessibilityEvent.eventTypeToString(event.eventType)
        Log.d(TAG, "Event: type=$eventTypeName class=${event.className}")

        @Suppress("DEPRECATION")
        val root = rootInActiveWindow ?: return
        try {
            val texts = mutableListOf<String>()
            collectText(root, texts)
            if (texts.isNotEmpty()) {
                Log.d(TAG, "Visible text: ${texts.joinToString(" | ")}")
            }
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    private fun collectText(node: AccessibilityNodeInfo, out: MutableList<String>) {
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectText(child, out)
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }
}
