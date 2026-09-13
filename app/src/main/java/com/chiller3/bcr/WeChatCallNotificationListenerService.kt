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
 * Detects the start/end of a WeChat (com.tencent.mm) voice or video call, and shows/hides the
 * shared floating bubble (see [FloatingButtonService]) accordingly.
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
 *    AudioManager.registerAudioPlaybackCallback API was tested too. Real-call logging showed
 *    this signal flickering true/false multiple times *before* the call was actually connected
 *    per WeChat's own notification (likely picking up ringback/dial tones) -- not clean enough
 *    to use as a reliable trigger. Abandoned.
 *
 * EARLY-TRIGGER ADDITION (2026-09-13): [WeChatCallActivityProbe] (a separate accessibility
 * service) detects WeChat's call window appearing MUCH earlier than the notification -- up to
 * ~17 seconds earlier was observed on a real "cold" call, matching the same WeChat-side slow
 * connection warm-up that causes the notification delay above. Real-world testing across normal
 * WeChat usage (chats, Moments, camera, mini programs, video feed) found zero false positives,
 * so this is now used as an early "something is probably happening" signal -- but since it isn't
 * 100% guaranteed to only ever fire for real calls, anything it triggers is treated as PENDING/
 * unconfirmed until the notification actually shows up to confirm it, with a timeout to bail out
 * safely if it never does (see PENDING_TIMEOUT_MS and the pending-state fields below). This
 * means recording can now start as early as the probe's signal instead of waiting for the
 * notification, without the risk of leaving behind a bogus recording if the probe ever does fire
 * for something that isn't a call after all.
 *
 * Manual mode (default): the bubble shows as soon as EITHER signal fires, and the user taps it
 * themselves whenever they're ready (confirmed near-instant: ~50ms from tap to AudioRecord
 * actually starting). Automatic mode (Preferences.wechatAutoRecord): recording starts the moment
 * either signal fires, tentatively, and is kept only if the notification later confirms it was a
 * real call; the bubble shows immediately in its "recording" appearance either way, purely as a
 * visual confirmation (tapping it while in this mode still works, and just stops the recording
 * early -- this also immediately marks it as a real, keep-no-matter-what recording, per
 * [toggleRecording]).
 *
 * The one thing that stays automatic and unconditional is STOPPING once a call is CONFIRMED:
 * when the call notification disappears (for any reason), any in-progress WeChat recording is
 * stopped automatically, since reacting a little late to an ending call only means a few extra
 * seconds of harmless trailing silence, not missing content.
 */
class WeChatCallNotificationListenerService : NotificationListenerService() {
    companion object {
        private val TAG = WeChatCallNotificationListenerService::class.java.simpleName
        private const val WECHAT_PACKAGE = "com.tencent.mm"

        // Text observed on WeChat's ongoing in-call notification. Both voice and video calls
        // confirmed by real-device testing (2026-09-11).
        private val CALL_TEXT_KEYWORDS = listOf("语音通话中", "视频通话中")

        // How long to wait, after WeChatCallActivityProbe fires, for the notification to confirm
        // it was actually a call, before assuming it wasn't and bailing out. The longest real
        // gap observed so far between the probe firing and the notification appearing was ~17
        // seconds, on a "cold" call; this leaves a healthy margin above that.
        private const val PENDING_TIMEOUT_MS = 30_000L

        private var instance: WeChatCallNotificationListenerService? = null

        /** Called when the shared floating bubble is tapped while showing for a WeChat call. */
        fun toggleRecordingFromBubble() {
            val service = instance ?: return
            service.handler.post {
                service.toggleRecording()
            }
        }

        /** Called by WeChatCallActivityProbe when it sees WeChat's call window appear. */
        fun notifyProbeEvent() {
            val service = instance ?: return
            service.handler.post {
                service.onProbeEvent()
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    // The StatusBarNotification.key of the currently CONFIRMED active call notification, or null
    // if no call is confirmed as in progress yet (it may still be PENDING -- see below). Using
    // the key (rather than id/tag) is the correct way to match a POSTED notification to its later
    // REMOVED event, since it's guaranteed unique per notification instance by the system.
    private var activeCallKey: String? = null

    // Non-null while we've reacted to WeChatCallActivityProbe's signal but the notification
    // hasn't confirmed it yet. Cleared either by confirmation (activeCallKey gets set) or by the
    // pending timeout firing (see pendingTimeoutRunnable).
    private var pendingSince: Long? = null
    private var pendingTimeoutRunnable: Runnable? = null

    private var isRecording = false

    // True only while isRecording is a tentative, auto-started, not-yet-confirmed recording from
    // the PENDING state -- i.e. one that pendingTimeoutRunnable is allowed to discard if the
    // notification never shows up. Manually starting/stopping recording (toggleRecording) always
    // clears this, since a manual tap means the user has taken responsibility for that decision.
    private var pendingRecordingIsDiscardable = false

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d(TAG, "Notification listener connected")
    }

    /** See class doc. Only acts if nothing is currently pending or already confirmed. */
    private fun onProbeEvent() {
        if (activeCallKey != null || pendingSince != null) {
            return
        }

        pendingSince = System.currentTimeMillis()
        Log.i(TAG, "WeChat call window detected (pending notification confirmation)")

        if (Preferences(this).wechatAutoRecord) {
            isRecording = true
            pendingRecordingIsDiscardable = true
            startForegroundService(Intent(this, WeChatCallCaptureService::class.java))
            FloatingButtonService.show(this, FloatingBubbleUi.BubbleState.RECORDING) {
                toggleRecordingFromBubble()
            }
        } else {
            FloatingButtonService.show(this, FloatingBubbleUi.BubbleState.NOT_RECORDING) {
                toggleRecordingFromBubble()
            }
        }

        val timeoutRunnable = Runnable { onPendingTimeout() }
        pendingTimeoutRunnable = timeoutRunnable
        handler.postDelayed(timeoutRunnable, PENDING_TIMEOUT_MS)
    }

    /**
     * The probe fired but no notification confirmed it was a real call within the timeout
     * window. What happens next depends on what's happened since the probe fired:
     *
     *  - If it's still an untouched, auto-started tentative recording (pendingRecordingIsDiscardable):
     *    discard it and hide the bubble -- this was a false positive.
     *  - If the user manually started/stopped recording during the pending window (isRecording is
     *    true, or was toggled at all), leave everything exactly as it is -- a manual tap means
     *    the user has taken responsibility for that decision, whatever it was.
     *  - Otherwise (manual mode, bubble just sitting there untouched): hide the bubble, since
     *    nothing else will ever do so if this really was a false positive.
     */
    private fun onPendingTimeout() {
        pendingTimeoutRunnable = null
        if (activeCallKey != null || pendingSince == null) {
            // Already confirmed, or already handled some other way; nothing to do.
            return
        }
        pendingSince = null

        if (pendingRecordingIsDiscardable) {
            Log.i(TAG, "WeChat call window timed out with no notification -- discarding tentative recording")
            FloatingButtonService.hide(this)
            WeChatCallCaptureService.stopAndDiscard(this)
            isRecording = false
            pendingRecordingIsDiscardable = false
        } else if (!isRecording) {
            Log.i(TAG, "WeChat call window timed out with no notification -- treating as a false positive")
            FloatingButtonService.hide(this)
        }
        // else: isRecording is true but not discardable -- the user manually started recording
        // during the pending window. Leave it alone entirely; they're in control now, and it'll
        // only stop via another manual tap since there's no confirmed notification to react to.
    }

    private fun cancelPendingTimeout() {
        pendingTimeoutRunnable?.let { handler.removeCallbacks(it) }
        pendingTimeoutRunnable = null
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
        Log.i(TAG, "WeChat call confirmed: text=[$text] key=${sbn.key}")
        cancelPendingTimeout()
        pendingSince = null
        pendingRecordingIsDiscardable = false

        if (isRecording) {
            // The probe (or a manual tap during the pending window) already got us recording;
            // this just confirms it now has a notification tracking its eventual hangup.
            // Nothing else to change -- in particular, do NOT start a second recording.
            return
        }

        // No probe signal got us started already (e.g. its accessibility service isn't
        // enabled) -- react fresh, same as before the probe existed.
        if (Preferences(this).wechatAutoRecord) {
            isRecording = true
            startForegroundService(Intent(this, WeChatCallCaptureService::class.java))
            FloatingButtonService.show(this, FloatingBubbleUi.BubbleState.RECORDING) {
                toggleRecordingFromBubble()
            }
        } else {
            FloatingButtonService.show(this, FloatingBubbleUi.BubbleState.NOT_RECORDING) {
                toggleRecordingFromBubble()
            }
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
        pendingRecordingIsDiscardable = false

        activeCallKey = null
    }

    private fun toggleRecording() {
        if (activeCallKey == null && pendingSince == null) {
            // Nothing being tracked at all (e.g. tap raced with hangup/timeout); nothing to do.
            return
        }

        // A manual tap always means "I, the user, am deciding this" -- regardless of what
        // happens with the notification confirmation afterwards, this recording should never be
        // silently discarded by the pending timeout.
        pendingRecordingIsDiscardable = false

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
