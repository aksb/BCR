/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout

/**
 * Displays a small draggable bubble ("floating button") over other apps, including on top of a
 * secure lock screen, while a call is in progress. Tapping it (without dragging) toggles manual
 * recording of the current call via [RecorderInCallService].
 *
 * This is a single plain overlay window ([WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY])
 * that works in all three situations: device unlocked, insecure/swipe lock screen, and secure
 * (PIN/pattern/password/biometric) lock screen. Normally a plain overlay window is always kept
 * below a secure lock screen by the system, but adding [WindowManager.LayoutParams
 * .FLAG_SHOW_WHEN_LOCKED] lets it draw on top of the lock screen too, without needing any special
 * permission beyond the `SYSTEM_ALERT_WINDOW` this already has. Combined with
 * [WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL], touches outside the bubble's small rectangle
 * fall through to whatever is actually underneath (the still-alive, still-interactive lock screen
 * or in-call UI) instead of being swallowed - so the rest of the screen (e.g. the answer/decline
 * buttons) stays fully usable while the bubble is showing.
 *
 * This service does nothing on its own besides manage the overlay view; all recording logic
 * lives in [RecorderInCallService], which starts/stops this service as calls come and go.
 */
class FloatingButtonService : Service() {
    companion object {
        private val TAG = FloatingButtonService::class.java.simpleName

        private var instance: FloatingButtonService? = null

        /**
         * The appearance the bubble should have as soon as it's created. [show] stashes its
         * `initialState` argument here right before starting the service.
         *
         * This exists because [Context.startService] is asynchronous: [onCreate] doesn't actually
         * run until after the calling code (here, [show] itself) returns control to the main
         * thread's message queue. So a call to [setBubbleState] made right after [show] - as
         * [RecorderInCallService] used to do - would find [instance] still null and silently do
         * nothing, leaving the bubble on whatever hardcoded default [onCreate] used until some
         * later, unrelated state change happened to refresh it (visible as an incorrect color for
         * the bubble's first frame or two). Reading this in [onCreate] instead means the bubble is
         * correct from its very first frame, with no such gap.
         *
         * Both [show] and [onCreate] only ever run on the main thread (all callers funnel through
         * a `Handler` bound to the main looper), so no synchronization is needed here, same as
         * [instance] above.
         */
        private var pendingInitialState = FloatingBubbleUi.BubbleState.NOT_RECORDING

        /**
         * Show the floating button with the given initial appearance, if the overlay permission is
         * granted. Safe to call multiple times; if the bubble is already showing, this just
         * updates its appearance instead (equivalent to [setBubbleState]).
         */
        fun show(context: Context, initialState: FloatingBubbleUi.BubbleState) {
            if (instance != null) {
                setBubbleState(initialState)
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                Log.w(TAG, "Cannot show floating button: overlay permission not granted")
                return
            }

            pendingInitialState = initialState
            context.startService(Intent(context, FloatingButtonService::class.java))
        }

        /** Hide the floating button, if it's currently showing. Safe to call multiple times. */
        fun hide(context: Context) {
            context.stopService(Intent(context, FloatingButtonService::class.java))
        }

        /** Update the bubble's appearance to reflect its current [FloatingBubbleUi.BubbleState]. */
        fun setBubbleState(state: FloatingBubbleUi.BubbleState) {
            instance?.updateAppearance(state)
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: LinearLayout
    private lateinit var layoutParams: WindowManager.LayoutParams

    private var bubbleState = FloatingBubbleUi.BubbleState.NOT_RECORDING

    override fun onCreate() {
        super.onCreate()
        instance = this
        bubbleState = pendingInitialState

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val prefs = Preferences(this)

        val widthPx = FloatingBubbleUi.bubbleWidthPx(this)
        val heightPx = FloatingBubbleUi.bubbleHeightPx(this)
        val metrics = resources.displayMetrics
        val maxX = (metrics.widthPixels - widthPx).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - heightPx).coerceAtLeast(0)

        // Restore the position the user last dragged the bubble to, if any. The fraction is
        // re-clamped to the current screen size in case it was saved on a different display or
        // orientation.
        val savedPosition = prefs.floatingButtonPosition
        val (initialX, initialY) = if (savedPosition != null) {
            val (xFraction, yFraction) = savedPosition
            (xFraction * maxX).toInt().coerceIn(0, maxX) to
                    (yFraction * maxY).toInt().coerceIn(0, maxY)
        } else {
            0 to metrics.heightPixels / 3
        }

        bubbleView = FloatingBubbleUi.createBubbleView(this)
        updateAppearance(bubbleState)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    // Let this draw on top of a secure lock screen, same as the system's own
                    // incoming call UI.
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    // Turn the screen on for an incoming call. Deliberately not combined with
                    // FLAG_KEEP_SCREEN_ON: the bubble persists for the whole call, and forcing the
                    // screen to stay on for that entire duration would be a needless battery/heat
                    // cost. The bubble itself isn't affected by the screen turning back off; it's
                    // simply not visible until the screen is on again.
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    // Let touches outside the bubble's small rectangle fall through to whatever is
                    // actually underneath (lock screen, in-call UI, etc.) instead of this window
                    // swallowing them.
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }

        FloatingBubbleUi.attachTouchHandler(
            context = this,
            view = bubbleView,
            bubbleWidthPx = widthPx,
            bubbleHeightPx = heightPx,
            getPosition = { layoutParams.x to layoutParams.y },
            setPosition = { x, y ->
                layoutParams.x = x
                layoutParams.y = y
                windowManager.updateViewLayout(bubbleView, layoutParams)
            },
            onTap = { RecorderInCallService.toggleManualRecordingFromBubble() },
            onDragEnd = { x, y ->
                // Store as a fraction of the (clamped) draggable range rather than raw pixels, so
                // it still lands in a sensible spot if this is next read back on a different
                // screen size/orientation.
                prefs.floatingButtonPosition = (
                        if (maxX > 0) x.toFloat() / maxX else 0f
                        ) to (
                        if (maxY > 0) y.toFloat() / maxY else 0f
                        )
            },
        )

        try {
            windowManager.addView(bubbleView, layoutParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating button overlay", e)
            stopSelf()
        }
    }

    private fun updateAppearance(state: FloatingBubbleUi.BubbleState) {
        bubbleState = state
        if (::bubbleView.isInitialized) {
            FloatingBubbleUi.updateAppearance(bubbleView, state)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Nothing to do here besides keeping the service (and thus the overlay view) alive; all
        // interaction happens through the static helpers above.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        if (::bubbleView.isInitialized) {
            try {
                windowManager.removeView(bubbleView)
            } catch (e: Exception) {
                // View was never attached or was already removed.
                Log.w(TAG, "Failed to remove floating button overlay", e)
            }
        }

        if (instance === this) {
            instance = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
