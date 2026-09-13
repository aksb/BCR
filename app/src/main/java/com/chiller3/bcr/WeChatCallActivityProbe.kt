/*
 * SPDX-FileCopyrightText: 2026 aksb
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Detects WeChat's (com.tencent.mm) call window appearing, well before WeChat's own call
 * notification does (up to ~17 seconds earlier was observed on a real "cold" call -- see
 * WeChatCallNotificationListenerService's class doc for the full story and how this signal is
 * used safely despite not being provably 100% specific to real calls).
 *
 * Real-world testing across normal WeChat usage (chats, Moments, camera, mini programs, video
 * feed) found zero false triggers of this event, but every trigger is still routed through
 * WeChatCallNotificationListenerService's pending/confirm/timeout state machine rather than
 * treated as a guaranteed call, specifically because this can't be proven to NEVER fire for
 * anything else.
 *
 * Does not request window content access -- already confirmed blocked by FLAG_SECURE on this
 * device, and not needed here anyway; only the fact that WeChat's window state just changed
 * matters, not what's actually on screen.
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
        WeChatCallNotificationListenerService.notifyProbeEvent()
    }

    override fun onInterrupt() {
        Log.d(TAG, "Activity probe interrupted")
    }
}
