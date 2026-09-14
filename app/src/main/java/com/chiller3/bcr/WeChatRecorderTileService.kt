/*
 * SPDX-FileCopyrightText: 2026 aksb
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile that starts/stops WeChat call recording directly, independent of any call
 * detection at all.
 *
 * Why this exists: every attempt at auto-detecting a WeChat call earlier than its own ongoing-call
 * notification (or starting recording faster in reaction to it) ran into the same wall -- WeChat
 * itself is sometimes measurably slower to actually connect a call after being idle for a while,
 * and no signal we could observe ever preceded that delay. This tile sidesteps the whole problem
 * instead of trying to solve it: tap it BEFORE placing/answering a WeChat call, and recording
 * starts immediately (confirmed near-instant, same as the floating bubble), with zero dependency
 * on WeChat's own timing.
 *
 * If tapped but no call actually happens (a misclick, or changing your mind), there's no
 * notification to ever trigger an automatic stop -- recording keeps going until manually stopped
 * again (via this tile, or the bubble if a call is later detected and confirms it). The tile's
 * own active/inactive state (and label) is the way to notice this is still going, in addition to
 * the bubble showing "recording" state once/if a real call does get detected while this is
 * already running (see WeChatCallNotificationListenerService.onNotificationPosted, which detects
 * an already-in-progress recording and adopts it rather than starting a second one).
 */
class WeChatRecorderTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refreshTileState()
    }

    override fun onClick() {
        super.onClick()

        if (WeChatCallCaptureService.isRunning) {
            stopService(Intent(this, WeChatCallCaptureService::class.java))
        } else {
            startForegroundService(Intent(this, WeChatCallCaptureService::class.java))
        }

        // Keep the bubble in sync too, in case one happens to already be showing (e.g. a call
        // was detected first and this tile is being used mid-call instead of the bubble itself).
        // Safe no-op if no bubble is currently showing.
        FloatingButtonService.setBubbleState(
            if (WeChatCallCaptureService.isRunning) {
                FloatingBubbleUi.BubbleState.RECORDING
            } else {
                FloatingBubbleUi.BubbleState.NOT_RECORDING
            },
        )

        refreshTileState()
    }

    private fun refreshTileState() {
        val tile = qsTile ?: return
        val running = WeChatCallCaptureService.isRunning

        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(
            if (running) {
                R.string.tile_wechat_recorder_label_active
            } else {
                R.string.tile_wechat_recorder_label_inactive
            },
        )
        tile.updateTile()
    }
}
