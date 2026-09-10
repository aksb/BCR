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
 * enumerate every currently visible window on every relevant event, log the ones that belong to
 * WeChat (com.tencent.mm), and dump their class name and visible on-screen text -- so the
 * specific text/class name pattern that appears during an actual voice/video call can be
 * identified from logcat.
 *
 * Deliberately does NOT filter by event.packageName or by the accessibility service config's
 * packageNames attribute: WeChat's call UI may be drawn as an overlay window rather than a
 * normal Activity, and such windows aren't always reliably tagged with their owning package on
 * the event itself. Instead, every window's own root node package name is checked directly.
 *
 * Once the real pattern is known, this class will be extended to recognize "call started" /
 * "call ended" and expose that as a proper state change instead of raw logging.
 */
class WeChatCallAccessibilityService : AccessibilityService() {
    companion object {
        private val TAG = WeChatCallAccessibilityService::class.java.simpleName
        private const val WECHAT_PACKAGE = "com.tencent.mm"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val windowList = windows ?: return

        var loggedHeader = false

        for (window in windowList) {
            val root = window.root ?: continue

            try {
                if (root.packageName?.toString() != WECHAT_PACKAGE) {
                    continue
                }

                if (!loggedHeader) {
                    val eventTypeName = AccessibilityEvent.eventTypeToString(event.eventType)
                    Log.d(
                        TAG,
                        "Event: type=$eventTypeName class=${event.className} " +
                            "eventPackage=${event.packageName}",
                    )
                    loggedHeader = true
                }

                val texts = mutableListOf<String>()
                collectText(root, texts)
                Log.d(
                    TAG,
                    "WeChat window: type=${window.type} layer=${window.layer} " +
                        "class=${root.className} title=${window.title} " +
                        "texts=${texts.joinToString(" | ")}",
                )
            } finally {
                @Suppress("DEPRECATION")
                root.recycle()
            }
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
