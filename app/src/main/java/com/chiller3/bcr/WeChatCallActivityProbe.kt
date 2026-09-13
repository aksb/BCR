/*
 * SPDX-FileCopyrightText: 2026 aksb
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Diagnostic-only, temporary. Logs the class name of every WeChat (com.tencent.mm) window state
 * change, with a precise timestamp, so it can be compared against when WeChat's call notification
 * appears (logged by WeChatCallNotificationListenerService).
 *
 * The question this exists to answer: real-device testing confirmed WeChat itself is sometimes
 * measurably slower to actually connect a call after being idle for a while (the first call
 * after some time apart takes several seconds longer than back-to-back calls), and our current
 * detection (WeChat's own call notification) only fires once that slow connection finishes. If
 * WeChat's call Activity (observed via `dumpsys activity activities` to be
 * com.tencent.mm.plugin.voip.ui.VideoActivity, for both ringing/unanswered calls and connected
 * ones) becomes foreground BEFORE that slow connection completes, switching detection to watch
 * for it instead could start recording earlier -- capturing a few extra seconds of harmless
 * silence while WeChat is still connecting, instead of missing real content. If it doesn't
 * appear any earlier than the notification, this doesn't help and isn't worth pursuing further.
 *
 * Does NOT replace or interact with the current notification-based detection in any way -- purely
 * observational, and does not request window content access (already confirmed blocked by
 * FLAG_SECURE on this device, and not needed for this question anyway -- just the class name
 * reported directly on the event itself).
 */
class WeChatCallActivityProbe : AccessibilityService() {
    companion object {
        private val TAG = WeChatCallActivityProbe::class.java.simpleName
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Activity probe connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        Log.d(TAG, "PROBE: class=${event.className} time=${System.currentTimeMillis()}")
    }

    override fun onInterrupt() {
        Log.d(TAG, "Activity probe interrupted")
    }
}
