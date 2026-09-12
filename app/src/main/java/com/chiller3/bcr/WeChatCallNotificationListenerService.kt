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

/**
 * Detects the start/end of a WeChat (com.tencent.mm) voice or video call by watching for its
 * ongoing in-call notification, and shows/hides the shared floating bubble (see
 * [FloatingButtonService]) accordingly.
 *
 * Confirmed by real-device testing (2026-09-10): WeChat posts an ongoing notification only once
 * a call is actually connected (nothing during ringing), with text "语音通话中" for voice calls
 * and "视频通话中" for video calls. When the call ends, WeChat cancels that same notification
 * itself (reason=REASON_APP_CANCEL).
 *
 * DESIGN DECISION (2026-09-11/12): two earlier attempts to auto-START recording as soon as this
 * notification appears were both abandoned after real-call testing:
 *
 *  - This class's own detection reacts to the notification in under 50ms, so it isn't the
 *    bottleneck. But real calls repeatedly came out missing their first several seconds of
 *    audio regardless -- the gap is specifically between the user actually being connected and
 *    WeChat getting around to posting/updating this notification, which is entirely on WeChat's
 *    side and not something reacting faster to the notification can fix.
 *  - A parallel attempt using the public, no-permission-required
 *    AudioManager.registerAudioPlaybackCallback API (to catch a USAGE_VOICE_COMMUNICATION
 *    playback stream becoming active, independent of WeChat's own notification) was tested too.
 *    Real-call logging showed this signal flickering true/false multiple times *before* the
 *    call was actually connected per WeChat's own notification (likely picking up ringback/dial
 *    tones, which are also tagged USAGE_VOICE_COMMUNICATION) -- not clean enough to use as a
 *    reliable trigger without yet more debounce logic of uncertain benefit. Abandoned.
 *
 * Given both automatic approaches hit a wall, recording is now fully manual: this class only
 * shows/hides the floating bubble in step with WeChat's call notification, and the user taps it
 * themselves whenever they're ready (confirmed near-instant: ~50ms from tap to AudioRecord
 * actually starting, so no equivalent "missing first few seconds" problem here). The one thing
 * that stays automatic is STOPPING: when the call notification disappears (for any reason -- the
 * user hanging up, the other side hanging up, switching to answer an incoming cellular call, an
 * error, etc.), any in-progress WeChat recording is stopped automatically, since reacting a
 * little late to an ending call only means a few extra seconds of harmless trailing silence, not
 * missing content -- unlike the START side, which is why that part alone doesn't get the same
 * "just let the user do it" treatment.
 */
class WeChatCallNotificationListenerService : NotificationListenerService() {
    companion object {
        private val TAG = WeChatCallNotificationListenerService::class.java.simpleName
        private const val WECHAT_PACKAGE = "com.tencent.mm"

        // Text observed on WeChat's ongoing in-call notification. Both voice and video calls
        // confirmed by real-device testing (2026-09-11).
        private val CALL_TEXT_KEYWORDS = listOf("语音通话中", "视频通话中")

        private var instance: WeChatCallNotificationListenerService? = null

        /** Called when the shared floating bubble is tapped while showing for a WeChat call. */
        fun toggleRecordingFromBubble() {
            val service = instance ?: return
            service.handler.post {
                service.toggleRecording()
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    // The StatusBarNotification.key of the currently active call notification, or null if no
    // call is currently detected as in progress. Using the key (rather than id/tag) is the
    // correct way to match a POSTED notification to its later REMOVED event, since it's
    // guaranteed unique per notification instance by the system.
    private var activeCallKey: String? = null
    private var isRecording = false

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
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
        Log.i(TAG, "WeChat call started: text=[$text] key=${sbn.key}")
        FloatingButtonService.show(this, FloatingBubbleUi.BubbleState.NOT_RECORDING) {
            toggleRecordingFromBubble()
        }
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

        Log.i(TAG, "WeChat call ended: reason=$reason isRecording=$isRecording")
        FloatingButtonService.hide(this)

        if (isRecording) {
            stopService(Intent(this, WeChatCallCaptureService::class.java))
            isRecording = false
        }

        activeCallKey = null
    }

    private fun toggleRecording() {
        if (activeCallKey == null) {
            // Call already ended (e.g. tap raced with hangup); nothing to toggle.
            return
        }

        isRecording = !isRecording
        if (isRecording) {
            startForegroundService(Intent(this, WeChatCallCaptureService::class.java))
        } else {
            stopService(Intent(this, WeChatCallCaptureService::class.java))
        }

        FloatingButtonService.setBubbleState(
            if (isRecording) {
                FloatingBubbleUi.BubbleState.RECORDING
            } else {
                FloatingBubbleUi.BubbleState.NOT_RECORDING
            },
        )
    }

    override fun onListenerDisconnected() {
        if (instance === this) {
            instance = null
        }
        super.onListenerDisconnected()
        Log.d(TAG, "Notification listener disconnected")
    }
}
