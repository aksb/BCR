/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shared appearance and touch behavior for the draggable call-recording bubble.
 *
 * This is used by [FloatingButtonService], a plain overlay window that (with
 * `FLAG_SHOW_WHEN_LOCKED`) is shown both while the device is unlocked and on top of a secure lock
 * screen. Factoring the shared bits out here keeps the drag/tap/appearance logic reusable.
 */
object FloatingBubbleUi {
    private val TAG = FloatingBubbleUi::class.java.simpleName

    /** Minimum finger movement (dp) before a touch is treated as a drag instead of a tap. */
    private const val DRAG_THRESHOLD_DP = 8f

    /**
     * Bubble size multiplier relative to the original 56dp bubble. Bumped up because the default
     * size was hard to hit reliably during a call, then scaled back down to ~80% of that enlarged
     * size (1.5f * 0.8 = 1.2f) since it ended up a bit too large in practice.
     */
    private const val SIZE_SCALE = 1.2f

    /**
     * The two visual/interaction states the bubble can be in for the current call. This must stay
     * in sync with what tapping the bubble does in
     * [RecorderInCallService.toggleManualRecording]: [NOT_RECORDING] starts a new recording (or
     * resumes an existing paused one - both look the same here, since from the user's point of
     * view both simply mean "tap to make this call start recording"), and [RECORDING] pauses it.
     */
    enum class BubbleState {
        NOT_RECORDING,
        PAUSED,
        RECORDING,
    }

    private const val BASE_ICON_SIZE_DP = 56f
    private const val BASE_ICON_PADDING_DP = 14f

    /** Text size of the label shown under the bubble's icon. */
    private const val LABEL_TEXT_SIZE_SP = 12f

    /** Horizontal padding inside the label's background chip, on each side. */
    private const val LABEL_HORIZONTAL_PADDING_DP = 8f

    /** Vertical padding inside the label's background chip, on each side. */
    private const val LABEL_VERTICAL_PADDING_DP = 3f

    /** Vertical gap between the icon and the label below it. */
    private const val LABEL_GAP_DP = 4f

    /** Duration (ms) of one full expand-and-fade cycle of the recording ripple animation. */
    private const val RECORDING_RIPPLE_DURATION_MS = 1400L

    /** How large the ripple grows (relative to the icon's own size) before resetting. */
    private const val RECORDING_RIPPLE_MAX_SCALE = 1.6f

    /** The ripple's opacity at the start of each cycle; it fades to 0 as it expands. */
    private const val RECORDING_RIPPLE_START_ALPHA = 0.55f

    /** The icon's on-screen size (width and height, it's square), in pixels. */
    private fun iconSizePx(context: Context): Int {
        val density = context.resources.displayMetrics.density
        return (BASE_ICON_SIZE_DP * SIZE_SCALE * density).toInt()
    }

    private fun iconPaddingPx(context: Context): Int {
        val density = context.resources.displayMetrics.density
        return (BASE_ICON_PADDING_DP * SIZE_SCALE * density).toInt()
    }

    /**
     * The size (width and height) of the square box reserved for the icon, in pixels. This is
     * larger than [iconSizePx] itself: the recording ripple animates out to
     * [RECORDING_RIPPLE_MAX_SCALE] times the icon's size, so this box leaves enough room around
     * the icon for the ripple to fully expand into without being clipped - by this view, or by
     * the bubble's overlay window, which is sized exactly to [bubbleWidthPx] x [bubbleHeightPx].
     */
    private fun iconBoxSizePx(context: Context): Int {
        val iconSize = iconSizePx(context)
        val ripplePadding = ((RECORDING_RIPPLE_MAX_SCALE - 1f) / 2f * iconSize).roundToInt()
        return iconSize + 2 * ripplePadding
    }

    private fun labelTextSizePx(context: Context): Float =
        LABEL_TEXT_SIZE_SP * context.resources.displayMetrics.scaledDensity

    /** The two possible label strings, one per (merged) bubble appearance. */
    private fun labelStrings(context: Context): List<String> = listOf(
        context.getString(R.string.floating_bubble_label_not_recording),
        context.getString(R.string.floating_bubble_label_recording),
    )

    private fun labelPaint(context: Context): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = labelTextSizePx(context)
            typeface = Typeface.DEFAULT_BOLD
        }

    /**
     * The bubble's fixed overall width in pixels: whichever is wider between the icon itself and
     * the widest of the two possible label chips (text + its horizontal padding). Using a single
     * fixed width for both labels (rather than sizing the window to each label individually) means
     * the bubble never needs to resize, reposition, or re-clamp itself to the screen edges when its
     * state - and thus its label text - changes.
     */
    fun bubbleWidthPx(context: Context): Int {
        val density = context.resources.displayMetrics.density
        val paint = labelPaint(context)
        val widestLabelTextPx = labelStrings(context).maxOf { paint.measureText(it) }
        val labelChipWidthPx = widestLabelTextPx + 2 * LABEL_HORIZONTAL_PADDING_DP * density

        return max(iconBoxSizePx(context), labelChipWidthPx.roundToInt())
    }

    /** The bubble's fixed overall height in pixels: the icon, plus the gap, plus the label chip. */
    fun bubbleHeightPx(context: Context): Int {
        val density = context.resources.displayMetrics.density
        val paint = labelPaint(context)
        val fontMetrics = paint.fontMetrics
        val labelChipHeightPx =
            (fontMetrics.bottom - fontMetrics.top) + 2 * LABEL_VERTICAL_PADDING_DP * density

        return iconBoxSizePx(context) + (LABEL_GAP_DP * density).roundToInt() +
                labelChipHeightPx.roundToInt()
    }

    /**
     * Create the bubble's view: a small icon on top (round, colored background, matching the
     * previous look) with a text label chip below it describing what tapping the bubble will do.
     * Caller is responsible for placing it in a window, sized to [bubbleWidthPx] x
     * [bubbleHeightPx].
     */
    fun createBubbleView(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // The ripple (added below) is drawn larger than the icon box it sits behind, so
            // neither this container nor the FrameLayout wrapping the icon may clip their
            // children to bounds, or the expanding ripple would get cut off at the icon's edges.
            clipChildren = false

            val iconSize = iconSizePx(context)
            val iconBoxSize = iconBoxSizePx(context)
            val iconPadding = iconPaddingPx(context)

            addView(
                FrameLayout(context).apply {
                    clipChildren = false

                    // Sits behind the icon and is invisible (alpha 0) until a recording is
                    // actually in progress; see updateAppearance().
                    addView(
                        View(context).apply {
                            background =
                                ContextCompat.getDrawable(context, R.drawable.bg_floating_bubble_ripple)
                            alpha = 0f
                        },
                        FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER),
                    )

                    addView(
                        ImageView(context).apply {
                            setImageResource(R.drawable.ic_floating_mic)
                            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
                            scaleType = ImageView.ScaleType.FIT_CENTER
                        },
                        FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER),
                    )
                },
                LinearLayout.LayoutParams(iconBoxSize, iconBoxSize),
            )

            val density = context.resources.displayMetrics.density
            val labelHPadding = (LABEL_HORIZONTAL_PADDING_DP * density).roundToInt()
            val labelVPadding = (LABEL_VERTICAL_PADDING_DP * density).roundToInt()

            addView(
                TextView(context).apply {
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = LABEL_TEXT_SIZE_SP
                    setTypeface(typeface, Typeface.BOLD)
                    background =
                        ContextCompat.getDrawable(context, R.drawable.bg_floating_bubble_label)
                    setPadding(labelHPadding, labelVPadding, labelHPadding, labelVPadding)
                    maxLines = 1
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = (LABEL_GAP_DP * density).roundToInt()
                },
            )
        }

    /** Update the bubble's icon background and label text to reflect its current [BubbleState]. */
    fun updateAppearance(view: View, state: BubbleState) {
        val group = view as LinearLayout
        val iconWrapper = group.getChildAt(0) as FrameLayout
        val rippleView = iconWrapper.getChildAt(0) as View
        val iconView = iconWrapper.getChildAt(1) as ImageView
        val labelView = group.getChildAt(1) as TextView

        iconView.background = ContextCompat.getDrawable(
            view.context,
            when (state) {
                // Not recording and paused look the same to the user here - both just mean
                // "tap to make this call start recording" - so they share the same green
                // appearance. The underlying handling of what a tap actually does (start a new
                // recording vs. resume an existing paused one) is unchanged; only the two states'
                // shared appearance is merged.
                BubbleState.NOT_RECORDING, BubbleState.PAUSED -> R.drawable.bg_floating_bubble_idle
                BubbleState.RECORDING -> R.drawable.bg_floating_bubble_recording
            },
        )

        // The icon itself also changes with state: a mic while idle/paused (tap to start
        // recording), and a stop-style rounded square while actively recording (tap to pause) -
        // matching the same convention used by the two label strings below.
        iconView.setImageResource(
            when (state) {
                BubbleState.NOT_RECORDING, BubbleState.PAUSED -> R.drawable.ic_floating_mic
                BubbleState.RECORDING -> R.drawable.ic_floating_stop
            },
        )

        labelView.text = view.context.getString(
            when (state) {
                BubbleState.NOT_RECORDING, BubbleState.PAUSED ->
                    R.string.floating_bubble_label_not_recording
                BubbleState.RECORDING -> R.string.floating_bubble_label_recording
            },
        )

        // While actively recording, a translucent ring behind the icon repeatedly grows outward
        // and fades away - a radar/sonar-ping style cue that recording is live. Any previous
        // animation is cancelled first so switching states (or calling this repeatedly for the
        // same state) never stacks up multiple running animators on the same view.
        (rippleView.tag as? ObjectAnimator)?.cancel()
        if (state == BubbleState.RECORDING) {
            rippleView.tag = ObjectAnimator.ofPropertyValuesHolder(
                rippleView,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, RECORDING_RIPPLE_MAX_SCALE),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, RECORDING_RIPPLE_MAX_SCALE),
                PropertyValuesHolder.ofFloat(View.ALPHA, RECORDING_RIPPLE_START_ALPHA, 0f),
            ).apply {
                duration = RECORDING_RIPPLE_DURATION_MS
                repeatMode = ObjectAnimator.RESTART
                repeatCount = ObjectAnimator.INFINITE
                start()
            }
        } else {
            rippleView.tag = null
            rippleView.scaleX = 1f
            rippleView.scaleY = 1f
            rippleView.alpha = 0f
        }
    }

    /**
     * Stop the recording ripple animation, if one is running, and hide the ripple. Must be
     * called before the bubble view is discarded (e.g. in the hosting service's `onDestroy`),
     * since an in-progress [ObjectAnimator] with infinite repeat count otherwise keeps running -
     * and keeps the view alive - even after the view is detached from its window.
     */
    fun cancelAnimations(view: View) {
        val iconWrapper = (view as LinearLayout).getChildAt(0) as FrameLayout
        val rippleView = iconWrapper.getChildAt(0)
        (rippleView.tag as? ObjectAnimator)?.cancel()
        rippleView.tag = null
        rippleView.scaleX = 1f
        rippleView.scaleY = 1f
        rippleView.alpha = 0f
    }

    /**
     * Wire up drag-to-move and tap-to-toggle behavior for [view].
     *
     * The bubble's current position is read via [getPosition] and changed via [setPosition] (both
     * in pixels, relative to the top-left of the screen) so that this same logic works whether the
     * position is backed by a [android.view.WindowManager.LayoutParams] (overlay window) or an
     * [android.view.Window]'s attributes (activity window). Dragging is clamped so the bubble
     * always stays fully within the screen bounds. [onTap] is invoked when the bubble is tapped
     * without being dragged. [onDragEnd], if given, is invoked with the final position once a drag
     * finishes (not on every intermediate move), so the caller can persist it.
     */
    fun attachTouchHandler(
        context: Context,
        view: View,
        bubbleWidthPx: Int,
        bubbleHeightPx: Int,
        getPosition: () -> Pair<Int, Int>,
        setPosition: (x: Int, y: Int) -> Unit,
        onTap: () -> Unit,
        onDragEnd: (x: Int, y: Int) -> Unit = { _, _ -> },
    ) {
        val density = context.resources.displayMetrics.density
        val dragThresholdPx = DRAG_THRESHOLD_DP * density

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val (x, y) = getPosition()
                    initialX = x
                    initialY = y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY

                    if (!isDragging && (abs(dx) > dragThresholdPx || abs(dy) > dragThresholdPx)) {
                        isDragging = true
                    }

                    if (isDragging) {
                        // Keep the bubble fully on screen instead of letting it drift past the
                        // edges.
                        val metrics = context.resources.displayMetrics
                        val maxX = (metrics.widthPixels - bubbleWidthPx).coerceAtLeast(0)
                        val maxY = (metrics.heightPixels - bubbleHeightPx).coerceAtLeast(0)
                        val newX = (initialX + dx.toInt()).coerceIn(0, maxX)
                        val newY = (initialY + dy.toInt()).coerceIn(0, maxY)
                        try {
                            setPosition(newX, newY)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to update bubble position", e)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        val (x, y) = getPosition()
                        onDragEnd(x, y)
                    } else if (event.action == MotionEvent.ACTION_UP) {
                        v.performClick()
                        onTap()
                    }
                    true
                }
                else -> false
            }
        }
        // No-op: the real action happens in the touch listener above. This is only present so
        // that performClick() (called for accessibility/click-sound purposes) doesn't warn about
        // a missing OnClickListener.
        view.setOnClickListener {}
    }
}
