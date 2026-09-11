/*
 * SPDX-FileCopyrightText: 2026 aksb
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr

import android.app.Notification
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
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
 * DIAGNOSTIC ADDITION (2026-09-11): real recordings consistently start about 10 seconds late and
 * are missing the first ~10 seconds of audio, even though this class's own detection fires
 * essentially instantly relative to whenever the matched notification actually posts (confirmed:
 * 47ms from notification to AudioRecord actually starting). Real-call testing with the extra
 * "ALL: POSTED"/"ALL: REMOVED" logging below found NO earlier intermediate notification state --
 * WeChat posts exactly one notification, already saying "语音通话中", and the gap between it
 * appearing and the notification disappearing lines up exactly with the recorded file's
 * duration. So the missing seconds are specifically the gap between the user actually being
 * connected and WeChat getting around to posting/updating that notification -- entirely on
 * WeChat's side, not fixable by reacting faster to the notification itself.
 *
 * registerPlaybackObserver() below is a second, independent, PARALLEL signal source that doesn't
 * replace the notification-based detection above (nothing here triggers WeChatCallCaptureService
 * yet) -- it only logs, with a precise timestamp, whenever the system reports a
 * USAGE_VOICE_COMMUNICATION playback stream becoming active/inactive anywhere on the device, via
 * the public AudioManager.AudioPlaybackCallback API (no special permission needed -- this is
 * just a status callback, not audio capture, so it isn't affected by the
 * USAGE_VOICE_COMMUNICATION capture restriction confirmed elsewhere in this project). The idea is
 * to compare its timestamp against the notification-based one on the same real call: if it fires
 * meaningfully earlier, it's a better trigger signal and detection can switch over to it; if not,
 * this dead-ends the same way AudioPlaybackCaptureConfiguration did.
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
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Notification listener connected")
        registerPlaybackObserver()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName != WECHAT_PACKAGE) {
            return
        }

        // Unconditional diagnostic log -- see class doc. Runs regardless of whether this
        // notification matches our detection keywords, unlike everything below it.
        Log.d(TAG, "ALL: POSTED ${describe(sbn)}")

        if (!sbn.isOngoing) {
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
        if (sbn.packageName == WECHAT_PACKAGE) {
            Log.d(TAG, "ALL: REMOVED (reason=$reason) ${describe(sbn)}")
        }

        if (sbn.key != activeCallKey) {
            return
        }

        val durationSec = (System.currentTimeMillis() - callStartedAtMs) / 1000
        Log.i(TAG, "WeChat call ended: durationSec=$durationSec reason=$reason")
        showToast("检测到微信通话结束，时长约 ${durationSec}s")
        stopService(Intent(this, WeChatCallCaptureService::class.java))
        activeCallKey = null
    }

    private fun describe(sbn: StatusBarNotification): String {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
        val ticker = sbn.notification.tickerText
        return "key=${sbn.key} ongoing=${sbn.isOngoing} category=${sbn.notification.category} " +
            "postTimeMs=${sbn.postTime} title=[$title] text=[$text] subText=[$subText] " +
            "ticker=[$ticker]"
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * See class doc. Purely observational -- logs a precise timestamp whenever a
     * USAGE_VOICE_COMMUNICATION playback stream anywhere on the device becomes active/inactive.
     * Does not trigger recording; that's still driven entirely by the notification-based
     * detection above, until/unless this proves to be a meaningfully earlier signal.
     */
    private fun registerPlaybackObserver() {
        val audioManager = getSystemService(AudioManager::class.java) ?: return
        val callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                configs?.forEach { config ->
                    if (config.audioAttributes.usage == AudioAttributes.USAGE_VOICE_COMMUNICATION) {
                        Log.d(
                            TAG,
                            "PLAYBACK_CB: usage=VOICE_COMMUNICATION isActive=${config.isActive}",
                        )
                    }
                }
            }
        }
        audioManager.registerAudioPlaybackCallback(callback, Handler(Looper.getMainLooper()))
        playbackCallback = callback
    }

    private fun unregisterPlaybackObserver() {
        playbackCallback?.let {
            val audioManager = getSystemService(AudioManager::class.java)
            audioManager?.unregisterAudioPlaybackCallback(it)
        }
        playbackCallback = null
    }

    override fun onListenerDisconnected() {
        unregisterPlaybackObserver()
        super.onListenerDisconnected()
        Log.d(TAG, "Notification listener disconnected")
    }
}
