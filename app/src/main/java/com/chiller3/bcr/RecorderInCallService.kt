/*
 * SPDX-FileCopyrightText: 2022-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.InCallService
import android.util.Log
import androidx.annotation.StringRes
import com.chiller3.bcr.extension.threadIdCompat
import com.chiller3.bcr.output.CallMetadataCollector
import com.chiller3.bcr.output.OutputFile
import com.chiller3.bcr.rule.RecordRule
import kotlin.random.Random

class RecorderInCallService : InCallService(), RecorderThread.OnRecordingCompletedListener {
    companion object {
        private val TAG = RecorderInCallService::class.java.simpleName

        private const val PHONE_PACKAGE = "com.android.phone"

        private val ACTION_PAUSE = "${RecorderInCallService::class.java.canonicalName}.pause"
        private val ACTION_RESUME = "${RecorderInCallService::class.java.canonicalName}.resume"
        private val ACTION_RESTORE = "${RecorderInCallService::class.java.canonicalName}.restore"
        private val ACTION_DELETE = "${RecorderInCallService::class.java.canonicalName}.delete"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"

        private var instance: RecorderInCallService? = null

        /**
         * Toggle manual recording of the current call. Called from [FloatingButtonService] when
         * the user taps the floating bubble. Safe to call from any thread.
         */
        fun toggleManualRecordingFromBubble() {
            val service = instance ?: return
            service.handler.post {
                service.toggleManualRecording()
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var notificationManager: NotificationManager
    private lateinit var prefs: Preferences
    private lateinit var notifications: Notifications

    /**
     * Notification ID to use for the foreground service. Throughout the lifetime of the service, it
     * may be associated with different calls. It is not cancelled until all recorders exit.
     */
    private val foregroundNotificationId by lazy {
        prefs.nextNotificationId
    }

    /**
     * Notification IDs and their associated recorders. This indicates the desired state of the
     * notifications. It may not match the actual state until [updateForegroundState] is called.
     */
    private val notificationIdsToRecorders = HashMap<Int, RecorderThread>()

    private data class NotificationState(
        @StringRes val titleResId: Int,
        val message: String?,
        // We don't store the intents because Intent does not override equals()
        val actionsResIds: List<Int>,
    )

    /**
     * All notification IDs currently shown, along with their state. This is used to determine which
     * notifications should be cancelled after items are removed from [notificationIdsToRecorders].
     * The state is used for only applying updates when the state actually changes. Otherwise,
     * Android will block updates if they exceed the rate limit (10 updates per second).
     */
    private val allNotificationIds = HashMap<Int, NotificationState>()

    /**
     * Recording threads for each active call. When a call is disconnected, it is immediately
     * removed from this map.
     */
    private val callsToRecorders = HashMap<Call, RecorderThread>()

    /**
     * Token value for all intents received by this instance of the service.
     *
     * For the pause/resume functionality, we cannot use a bound service because [InCallService]
     * uses its own non-extensible [onBind] implementation. So instead, we rely on [onStartCommand].
     * However, because this service is required to be exported, the intents could potentially come
     * from third party apps and we don't want those interfering with the recordings.
     */
    private val token = Random.nextBytes(128)

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            Log.d(TAG, "onStateChanged: $call, $state")

            handleStateChange(call, state)
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            super.onDetailsChanged(call, details)
            Log.d(TAG, "onDetailsChanged: $call, $details")

            handleDetailsChange(call, details)

            // Due to firmware bugs, on older Samsung firmware, this callback (with the DISCONNECTED
            // state) is the only notification we receive that a call ended
            handleStateChange(call, null)
        }

        override fun onCallDestroyed(call: Call) {
            super.onCallDestroyed(call)
            Log.d(TAG, "onCallDestroyed: $call")

            requestStopRecording(call)
        }
    }

    private fun createActionIntent(notificationId: Int, action: String): Intent =
        Intent(this, RecorderInCallService::class.java).apply {
            this.action = action
            // The URI is not used for anything besides ensuring that the PendingIntents across
            // different notifications are unique. PendingIntent treats Intents that differ only in
            // the extras as the same.
            data = Uri.fromParts("notification", notificationId.toString(), null)
            putExtra(EXTRA_TOKEN, token)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }

    override fun onCreate() {
        super.onCreate()

        notificationManager = getSystemService(NotificationManager::class.java)
        prefs = Preferences(this)
        notifications = Notifications(this)
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()

        if (instance === this) {
            instance = null
        }
        FloatingButtonService.hide(this)
    }

    /** Handle intents triggered from notification actions for pausing and resuming. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val receivedToken = intent?.getByteArrayExtra(EXTRA_TOKEN)
            if (!receivedToken.contentEquals(token)) {
                throw IllegalArgumentException("Invalid token")
            }

            val notificationId = intent?.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
            if (notificationId == -1) {
                throw IllegalArgumentException("Invalid notification ID")
            }

            when (val action = intent?.action) {
                ACTION_PAUSE, ACTION_RESUME -> {
                    notificationIdsToRecorders[notificationId]?.isPaused = action == ACTION_PAUSE
                }
                ACTION_RESTORE, ACTION_DELETE -> {
                    notificationIdsToRecorders[notificationId]?.keepRecording =
                        if (action == ACTION_RESTORE) {
                            RecorderThread.KeepState.KEEP
                        } else {
                            RecorderThread.KeepState.DISCARD
                        }
                }
                else -> throw IllegalArgumentException("Invalid action: $action")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle intent: $intent", e)
        }

        // All actions are oneshot actions that should not be redelivered if a restart occurs
        stopSelf(startId)
        return START_NOT_STICKY
    }

    /**
     * Always called when the telephony framework becomes aware of a new call.
     *
     * This is the entry point for a new call. [callback] is always registered to keep track of
     * state changes.
     */
    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        Log.d(TAG, "onCallAdded: $call")

        // The callback is unregistered in requestStopRecording()
        call.registerCallback(callback)

        // In case the call is already in the active state
        handleStateChange(call, null)

        updateFloatingButtonVisibility()
    }

    /**
     * Called when the telephony framework destroys a call.
     *
     * This will request the cancellation of the recording, even if [call] happens to not be in one
     * of the disconnecting states.
     *
     * This is NOT guaranteed to be called, notably on older Samsung firmware, due to bugs in the
     * telephony framework. As a result, [handleStateChange] stop the recording if the call enters a
     * disconnecting state.
     */
    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.d(TAG, "onCallRemoved: $call")

        // Unconditionally request the recording to stop, even if it's not in a disconnecting state
        // since no further events will be received for the call.
        requestStopRecording(call)

        updateFloatingButtonVisibility()
    }

    /**
     * Start or stop recording based on the [call] state.
     *
     * If the state is [Call.STATE_ACTIVE], then recording will begin. If the state is either
     * [Call.STATE_DISCONNECTING] or [Call.STATE_DISCONNECTED], then the cancellation of the active
     * recording will be requested. If [state] is null, then the call state is queried from [call].
     */
    private fun handleStateChange(call: Call, state: Int?) {
        val callState = state ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            call.details.state
        } else {
            @Suppress("DEPRECATION")
            call.state
        }

        Log.d(TAG, "handleStateChange: $call, $state, $callState")

        if (call.parent != null) {
            Log.v(TAG, "Ignoring state change of conference call child")
        } else if (callState == Call.STATE_ACTIVE || (prefs.recordDialingState && callState == Call.STATE_DIALING)) {
            startRecording(call)
        } else if (callState == Call.STATE_DISCONNECTING || callState == Call.STATE_DISCONNECTED) {
            // This is necessary because onCallRemoved() might not be called due to firmware bugs
            requestStopRecording(call)
        }

        callsToRecorders[call]?.isHolding = callState == Call.STATE_HOLDING
    }

    /**
     * Start a [RecorderThread] for [call].
     *
     * If call recording is disabled or the required permissions aren't granted, then no
     * [RecorderThread] will be created. [ignoreEnabledPref] can be used to bypass the call
     * recording toggle, which is used when the user explicitly starts a recording via the
     * floating button. [forceImmediateRecording] is also used for that same explicit action - it
     * makes the new recorder start unpaused regardless of the matching record rule's configured
     * initial state, since a manual tap to start recording should always start recording, not
     * land in a paused state the user didn't ask for.
     *
     * This function is idempotent.
     */
    private fun startRecording(
        call: Call,
        ignoreEnabledPref: Boolean = false,
        forceImmediateRecording: Boolean = false,
    ) {
        if (!ignoreEnabledPref && !prefs.isCallRecordingEnabled) {
            Log.v(TAG, "Call recording is disabled")
        } else if (!Permissions.haveRequired(this)) {
            Log.v(TAG, "Required permissions have not been granted")
        } else if (!callsToRecorders.containsKey(call)) {
            val callPackage = call.details.accountHandle.componentName.packageName
            if (callPackage != PHONE_PACKAGE && !prefs.recordTelecomApps) {
                Log.w(TAG, "Ignoring call associated with package: $callPackage")
                return
            }

            val recorder = try {
                RecorderThread(this, this, call, forceImmediateStart = forceImmediateRecording)
            } catch (e: Exception) {
                notifications.notifyRecordingFailure(e.message, null, emptyList())
                throw e
            }
            callsToRecorders[call] = recorder

            val notificationId = if (notificationIdsToRecorders.isEmpty()) {
                foregroundNotificationId
            } else {
                prefs.nextNotificationId
            }
            notificationIdsToRecorders[notificationId] = recorder

            updateForegroundState()
            recorder.start()
        }
    }

    /**
     * Request the cancellation of the [RecorderThread].
     *
     * The [RecorderThread] is immediately removed from [callsToRecorders], but will remain in
     * [notificationIdsToRecorders] to keep the foreground service alive until the [RecorderThread]
     * exits and reports its status. The thread may exit and be removed from [callsToRecorders]
     * before this function is called if an error occurs during recording.
     *
     * This function will also unregister [callback] from the call since it's no longer necessary to
     * track further state changes.
     *
     * This function is idempotent.
     */
    private fun requestStopRecording(call: Call) {
        // This is safe to call multiple times in the AOSP implementation and also in heavily
        // modified builds, like Samsung's firmware. If this ever becomes a problem, we can keep
        // track of which calls have callbacks registered.
        call.unregisterCallback(callback)

        val recorder = callsToRecorders[call]
        if (recorder != null) {
            recorder.cancel()

            callsToRecorders.remove(call)

            // Don't change the foreground state until the thread has exited
        }
    }

    /** The call that the floating button and its manual toggle action apply to. */
    private fun currentPrimaryCall(): Call? = calls.firstOrNull { it.parent == null }

    /**
     * Whether [call]'s phone number(s) currently match a record rule with the "ignore" action -
     * i.e. this call would never be recorded no matter what, per the user's own record rules.
     *
     * Ordinarily, record rules are only evaluated once a [RecorderThread] actually starts trying
     * to record a call, which only happens if "Automatic call recording" is on (or the user taps
     * the floating button to force one). This re-evaluates the same rules independently of that,
     * purely so [updateFloatingButtonVisibility] can hide the bubble entirely for a call the user
     * has explicitly told BCR to ignore - showing a bubble offering to manually record it anyway
     * would contradict what they configured and just be confusing ("why is there a record button
     * on a number I told it to ignore?").
     *
     * This uses a throwaway [CallMetadataCollector] just to gather the phone number(s)/direction/
     * SIM slot [RecordRule.evaluate] needs; it's not tied to [RecorderThread] and can be used on
     * its own like this. The match itself is a cheap, local (contacts-database) lookup, not
     * network I/O, so re-running it here in addition to whatever a real [RecorderThread] does
     * later isn't a real cost.
     */
    private fun isCallIgnoredByRules(call: Call): Boolean {
        return try {
            val metadata = CallMetadataCollector(this, call).callMetadata
            val numbers = metadata.calls.mapNotNull { it.phoneNumber }
            val rules = prefs.recordRules ?: Preferences.DEFAULT_RECORD_RULES
            RecordRule.evaluate(this, rules, numbers, metadata.direction, metadata.simSlot) ==
                    RecordRule.Action.Ignore
        } catch (e: Exception) {
            Log.w(TAG, "Failed to evaluate record rules for floating button visibility", e)
            // Err on the side of showing the bubble.
            false
        }
    }

    /**
     * Whether [call] would immediately begin recording unpaused, without the user having to do
     * anything, if a [RecorderThread] were started for it right now.
     *
     * This is true only when "Automatic call recording" is on *and* the record rule that would
     * apply to this call doesn't discard it and has its initial state set to "start recording
     * immediately" (as opposed to "paused, until manually resumed").
     *
     * This is used purely to decide what the bubble should look like for a call that has no
     * [RecorderThread] yet - e.g. a still-ringing incoming call, since actual audio capture can't
     * start until the call is active regardless of any of this. Showing the bubble as already
     * "recording" in that situation previews what's about to happen once the call connects,
     * rather than the user needing to watch it flip from idle to recording right as they answer.
     *
     * Like [isCallIgnoredByRules], this uses a throwaway [CallMetadataCollector] and a cheap,
     * local rule evaluation - not the real one a [RecorderThread] will later perform.
     */
    private fun willRecordImmediatelyIfStarted(call: Call): Boolean {
        if (!prefs.isCallRecordingEnabled) {
            return false
        }

        return try {
            val metadata = CallMetadataCollector(this, call).callMetadata
            val numbers = metadata.calls.mapNotNull { it.phoneNumber }
            val rules = prefs.recordRules ?: Preferences.DEFAULT_RECORD_RULES
            val initialState = when (
                val action = RecordRule.evaluate(this, rules, numbers, metadata.direction, metadata.simSlot)
            ) {
                is RecordRule.Action.Save -> action.initialState
                is RecordRule.Action.Discard -> action.initialState
                RecordRule.Action.Ignore -> return false
            }
            initialState == RecordRule.InitialState.RECORDING
        } catch (e: Exception) {
            Log.w(TAG, "Failed to evaluate record rules for floating button preview", e)
            // Err on the side of not previewing a state that might not happen.
            false
        }
    }

    /**
     * The bubble's current state for [currentPrimaryCall]: not recording (no recorder yet and
     * none is about to start on its own, or a recorder that hasn't started/has already finished),
     * paused (a recorder exists but is currently paused, e.g. because a record rule's initial
     * state is "paused, until manually resumed"), or actively recording.
     *
     * A call can still be showing as "recording" here even before any [RecorderThread] exists for
     * it - see [willRecordImmediatelyIfStarted].
     */
    private fun currentBubbleState(): FloatingBubbleUi.BubbleState {
        val call = currentPrimaryCall() ?: return FloatingBubbleUi.BubbleState.NOT_RECORDING
        val recorder = callsToRecorders[call] ?: return if (willRecordImmediatelyIfStarted(call)) {
            FloatingBubbleUi.BubbleState.RECORDING
        } else {
            FloatingBubbleUi.BubbleState.NOT_RECORDING
        }
        if (recorder.state != RecorderThread.State.RECORDING) {
            return FloatingBubbleUi.BubbleState.NOT_RECORDING
        }
        return if (recorder.isPaused) {
            FloatingBubbleUi.BubbleState.PAUSED
        } else {
            FloatingBubbleUi.BubbleState.RECORDING
        }
    }

    /**
     * Show or hide the call-recording bubble depending on whether the feature is enabled in
     * settings, whether there's currently a call to control, and whether that call is one the
     * user has told BCR to always ignore (see [isCallIgnoredByRules]), and refresh its appearance
     * to match the current call.
     *
     * [FloatingButtonService]'s overlay window shows on top of a secure lock screen too (via
     * `FLAG_SHOW_WHEN_LOCKED`), so there's no separate lock-screen-only code path here any more.
     */
    private fun updateFloatingButtonVisibility() {
        val call = currentPrimaryCall()

        val shouldShow = prefs.floatingButtonEnabled && call != null &&
                (callsToRecorders.containsKey(call) || !isCallIgnoredByRules(call))

        if (!shouldShow) {
            FloatingButtonService.hide(this)
            return
        }

        // Pass the desired appearance straight to show() instead of calling
        // updateFloatingButtonRecordingState() as a separate step afterwards - see the comment on
        // FloatingButtonService.pendingInitialState for why a separate call would silently fail to
        // apply on the bubble's first appearance for this call.
        FloatingButtonService.show(this, currentBubbleState())
    }

    /** Refresh the bubble to match [currentBubbleState]. */
    private fun updateFloatingButtonRecordingState() {
        FloatingButtonService.setBubbleState(currentBubbleState())
    }

    /**
     * Start, resume, or pause recording of [currentPrimaryCall] in response to a tap on the
     * floating button, based on [currentBubbleState] so the tap always does what the bubble's
     * appearance implies. This mirrors the persistent notification's "pause"/"resume" actions
     * exactly (same `recorder.isPaused` assignment) rather than introducing a separate concept of
     * its own - a call only ever has one recording from start to finish, same as the original app:
     *
     * - Not recording -> start a new recording for the call, even if the automatic call recording
     *   toggle is off, as long as the required permissions have been granted, since this is an
     *   explicit user action. The recording always starts unpaused (see
     *   [startRecording]'s `forceImmediateRecording`), even if the matching record rule's initial
     *   state is "paused, until manually resumed" - the user just explicitly asked for recording
     *   to start, so it should, immediately.
     * - Paused (e.g. a record rule's initial state is "paused, until manually resumed") -> resume
     *   it, the same as the persistent notification's "resume" action does. A paused recorder
     *   still counts as "a recorder exists" for [callsToRecorders], so this has to be checked
     *   before the "recording" case below, or a tap here would incorrectly fall into that branch.
     * - Recording -> pause it, the same as the persistent notification's "pause" action does.
     *   There's no separate "stop" action here: like the persistent notification, the only way to
     *   end a call's recording for good is for the call itself to end.
     */
    private fun toggleManualRecording() {
        val call = currentPrimaryCall() ?: run {
            Log.w(TAG, "No active call to toggle recording for")
            return
        }

        when (val recorder = callsToRecorders[call]) {
            null -> {
                if (!Permissions.haveRequired(this)) {
                    Log.w(TAG, "Cannot start manual recording: required permissions not granted")
                } else {
                    startRecording(call, ignoreEnabledPref = true, forceImmediateRecording = true)
                }
            }
            else -> recorder.isPaused = !recorder.isPaused
        }

        updateFloatingButtonRecordingState()
    }

    /**
     * Notify the recording thread of call details changes.
     *
     * The recording thread uses call details for generating filenames.
     */
    private fun handleDetailsChange(call: Call, details: Call.Details) {
        val parentCall = call.parent
        val recorder = if (parentCall != null) {
            callsToRecorders[parentCall]
        } else {
            callsToRecorders[call]
        }

        recorder?.onCallDetailsChanged(call, details)
    }

    /**
     * Move to foreground, creating a persistent notification, when there are active calls or
     * recording threads that haven't finished exiting yet.
     */
    private fun updateForegroundState() {
        if (notificationIdsToRecorders.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            // Cancel and remove notifications for recorders that have exited
            for (notificationId in allNotificationIds.keys.minus(notificationIdsToRecorders.keys)) {
                // The foreground notification will be overwritten
                if (notificationId != foregroundNotificationId) {
                    notificationManager.cancel(notificationId)
                }
                allNotificationIds.remove(notificationId)
            }

            // Reassign the foreground notification to another recorder
            if (foregroundNotificationId !in notificationIdsToRecorders) {
                val iterator = notificationIdsToRecorders.iterator()
                val (notificationId, recorder) = iterator.next()
                iterator.remove()
                notificationManager.cancel(notificationId)
                allNotificationIds.remove(notificationId)
                notificationIdsToRecorders[foregroundNotificationId] = recorder
            }

            // Create/update notifications
            for ((notificationId, recorder) in notificationIdsToRecorders) {
                val titleResId: Int
                val actionResIds = mutableListOf<Int>()
                val actionIntents = mutableListOf<Intent>()
                val canShowDelete: Boolean

                when (recorder.state) {
                    RecorderThread.State.NOT_STARTED -> {
                        titleResId = R.string.notification_recording_initializing
                        canShowDelete = true
                    }
                    RecorderThread.State.RECORDING -> {
                        if (recorder.isHolding) {
                            titleResId = R.string.notification_recording_on_hold
                            // Don't allow changing the pause state while holding
                        } else if (recorder.isPaused) {
                            titleResId = R.string.notification_recording_paused
                            actionResIds.add(R.string.notification_action_resume)
                            actionIntents.add(createActionIntent(notificationId, ACTION_RESUME))
                        } else {
                            titleResId = R.string.notification_recording_in_progress
                            actionResIds.add(R.string.notification_action_pause)
                            actionIntents.add(createActionIntent(notificationId, ACTION_PAUSE))
                        }
                        canShowDelete = true
                    }
                    RecorderThread.State.FINALIZING, RecorderThread.State.COMPLETED -> {
                        titleResId = R.string.notification_recording_finalizing
                        canShowDelete = false
                    }
                }

                val message = StringBuilder(recorder.outputPath.unredacted)

                if (canShowDelete) {
                    recorder.keepRecording?.let {
                        when (it) {
                            RecorderThread.KeepState.KEEP -> {
                                actionResIds.add(R.string.notification_action_delete)
                                actionIntents.add(createActionIntent(notificationId, ACTION_DELETE))
                            }
                            RecorderThread.KeepState.DISCARD -> {
                                message.append("\n\n")
                                message.append(getString(R.string.notification_message_delete_at_end))
                                actionResIds.add(R.string.notification_action_restore)
                                actionIntents.add(createActionIntent(notificationId, ACTION_RESTORE))
                            }
                            RecorderThread.KeepState.DISCARD_TOO_SHORT -> {
                                val minDuration = prefs.minDuration

                                message.append("\n\n")
                                message.append(resources.getQuantityString(
                                    R.plurals.notification_message_delete_at_end_too_short,
                                    minDuration,
                                    minDuration,
                                ))
                                actionResIds.add(R.string.notification_action_restore)
                                actionIntents.add(createActionIntent(notificationId, ACTION_RESTORE))
                            }
                        }
                    }
                }

                val state = NotificationState(
                    titleResId,
                    message.toString(),
                    actionResIds,
                )
                if (state == allNotificationIds[notificationId]) {
                    // Avoid rate limiting
                    continue
                }

                val notification = notifications.createPersistentNotification(
                    state.titleResId,
                    state.message,
                    state.actionsResIds.zip(actionIntents),
                )

                if (notificationId == foregroundNotificationId) {
                    startForeground(notificationId, notification)
                } else {
                    notificationManager.notify(notificationId, notification)
                }

                allNotificationIds[notificationId] = state
            }

            notifications.vibrateIfEnabled(Notifications.CHANNEL_ID_PERSISTENT)
        }
    }

    private fun onRecorderExited(recorder: RecorderThread) {
        // This may be an early exit if an error occurred while recording or if the call matched an
        // "ignore" rule. Make sure we stop receiving state changes for this call or else a new
        // recorder thread might be started.
        val call = callsToRecorders.entries.find { it.value === recorder }?.key
        if (call != null) {
            Log.w(TAG, "$recorder exited before cancellation")
            callsToRecorders.remove(call)
            requestStopRecording(call)
        }

        // The notification no longer needs to be shown. If this recorder was associated with the
        // foreground service notification, updateForegroundState() will reassign
        // foregroundNotificationId to another recorder.
        assert(notificationIdsToRecorders.entries.removeIf { it.value === recorder }) {
            "$recorder not found"
        }

        updateForegroundState()
    }

    override fun onRecordingStateChanged(thread: RecorderThread) {
        handler.post {
            updateForegroundState()
            updateFloatingButtonVisibility()
        }
    }

    override fun onRecordingCompleted(
        thread: RecorderThread,
        file: OutputFile?,
        additionalFiles: List<OutputFile>,
        status: RecorderThread.Status,
    ) {
        Log.i(TAG, "Recording completed: ${thread.threadIdCompat}: ${file?.redacted}: $status")
        handler.post {
            onRecorderExited(thread)
            updateFloatingButtonVisibility()

            val firstMoveError = file?.moveError
                ?: additionalFiles.firstNotNullOfOrNull { it.moveError }
            if (firstMoveError != null) {
                notifications.notifyMoveFailure(firstMoveError.localizedMessage)
            }

            when (status) {
                RecorderThread.Status.Succeeded -> {
                    notifications.notifyRecordingSuccess(file!!, additionalFiles)
                }
                is RecorderThread.Status.Failed -> {
                    val message = buildString {
                        when (status.component) {
                            is RecorderThread.FailureComponent.AndroidMedia -> {
                                val frame = status.component.stackFrame

                                append(getString(R.string.notification_internal_android_error,
                                    "${frame.className}.${frame.methodName}"))
                            }
                            RecorderThread.FailureComponent.Other -> {}
                        }

                        status.exception?.localizedMessage?.let {
                            if (isNotEmpty()) {
                                append("\n\n")
                            }
                            append(it)
                        }
                    }

                    notifications.notifyRecordingFailure(message, file, additionalFiles)
                }
                is RecorderThread.Status.Discarded -> {
                    when (status.reason) {
                        RecorderThread.DiscardReason.Intentional -> {}
                        is RecorderThread.DiscardReason.Silence -> {
                            notifications.notifyRecordingPureSilence(status.reason.callPackage)
                        }
                    }
                }
                RecorderThread.Status.Cancelled -> {}
            }
        }
    }
}
