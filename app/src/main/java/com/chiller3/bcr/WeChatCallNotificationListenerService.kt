/*
 * SPDX-FileCopyrightText: 2026 aksb
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr

import android.app.Notification
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.widget.Toast

/**
 * Detects the start/end of a WeChat (com.tencent.mm) voice or video call by watching for its
 * ongoing in-call notification.
 *
 * Confirmed by real-device testing (2026-09-10): WeChat posts an ongoing notification only once
 * a call is actually connected (nothing during ringing), with text "语音通话中" for voice calls
 * (video calls are expected to say "视频通话中", not yet directly observed). When the call ends,
 * WeChat cancels that same notification itself (reason=REASON_APP_CANCEL).
 *
 * This still does NOT record anything -- it only detects the start/end boundary and reports it
 * via Toast + Log, so the detection logic itself can be validated across several real calls
 * before any audio capture is wired up.
 */
class WeChatCallNotificationListenerService : NotificationListenerService() {
    companion object {
        private val TAG = WeChatCallNotificationListenerService::class.java.simpleName
        private const val WECHAT_PACKAGE = "com.tencent.mm"

        // Text observed on WeChat's ongoing in-call notification. Video calls are expected to
        // use a similar but distinct string; both are matched by substring so small wording
        // variations across WeChat versions don't break detection outright.
        private val CALL_TEXT_KEYWORDS = listOf("语音通话中", "视频通话中")
    }

    // The StatusBarNotification.key of the currently active call notification, or null if no
    // call is currently detected as in progress. Using the key (rather than id/tag) is the
    // correct way to match a POSTED notification to its later REMOVED event, since it's
    // guaranteed unique per notification instance by the system.
    private var activeCallKey: String? = null
    private var callStartedAtMs: Long = 0

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Notification listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName != WECHAT_PACKAGE || !sbn.isOngoing) {
            return
        }

        val text = sbn.notification.extras
            .getCharSequence(Notification.EXTRA_TEXT)
            ?.toString()
            ?: return

        if (CALL_TEXT_KEYWORDS.none { text.contains(it) }) {
            return
        }

        // WeChat may re-post/update this notification while the call is ongoing (e.g. switching
        // audio route). Only treat it as a NEW call if we don't already think one is active.
        if (activeCallKey != null) {
            return
        }

        activeCallKey = sbn.key
        callStartedAtMs = System.currentTimeMillis()
        Log.i(TAG, "WeChat call started: text=[$text] key=${sbn.key}")
        showToast("检测到微信通话开始")
        startForegroundService(Intent(this, WeChatCallCaptureService::class.java))
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: RankingMap?,
        reason: Int,
    ) {
        sbn ?: return
        if (sbn.key != activeCallKey) {
            return
        }

        val durationSec = (System.currentTimeMillis() - callStartedAtMs) / 1000
        Log.i(TAG, "WeChat call ended: durationSec=$durationSec reason=$reason")
        showToast("检测到微信通话结束，时长约 ${durationSec}s")
        stopService(Intent(this, WeChatCallCaptureService::class.java))
        activeCallKey = null
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "Notification listener disconnected")
    }
}
