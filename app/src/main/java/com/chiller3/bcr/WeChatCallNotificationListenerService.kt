/*
 * SPDX-FileCopyrightText: 2026 aksb
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Diagnostic-only notification listener.
 *
 * Does NOT detect calls and does NOT record anything yet. It only logs every notification
 * posted or removed by WeChat (com.tencent.mm), including all the text fields we might be able
 * to use later (title, text, subText, bigText, ticker, category, ongoing flag), so the exact
 * lifecycle of WeChat's call notification (ringing -> connected -> ended) can be captured from
 * logcat with real data instead of guessed.
 *
 * Requires the user to manually grant "Notification access" in system settings -- same kind of
 * one-time manual step as the accessibility service, and just as unavoidable by design.
 */
class WeChatCallNotificationListenerService : NotificationListenerService() {
    companion object {
        private val TAG = WeChatCallNotificationListenerService::class.java.simpleName
        private const val WECHAT_PACKAGE = "com.tencent.mm"
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Notification listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName != WECHAT_PACKAGE) {
            return
        }
        Log.d(TAG, "POSTED: ${describe(sbn)}")
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: RankingMap?,
        reason: Int,
    ) {
        sbn ?: return
        if (sbn.packageName != WECHAT_PACKAGE) {
            return
        }
        Log.d(TAG, "REMOVED (reason=$reason): ${describe(sbn)}")
    }

    private fun describe(sbn: StatusBarNotification): String {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
        val ticker = sbn.notification.tickerText
        val isOngoing = sbn.isOngoing
        val category = sbn.notification.category
        val whenMs = sbn.notification.`when`

        return "id=${sbn.id} tag=${sbn.tag} ongoing=$isOngoing category=$category " +
            "when=$whenMs title=[$title] text=[$text] subText=[$subText] " +
            "bigText=[$bigText] ticker=[$ticker]"
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "Notification listener disconnected")
    }
}
