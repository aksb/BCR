/*
 * SPDX-FileCopyrightText: 2026 aksb
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Diagnostic-only foreground service. For the duration of a single detected WeChat call, it
 * records two RAW (headerless) PCM streams to separate files under
 * getExternalFilesDir(null)/wechat_call_test/:
 *
 *  - mic_<timestamp>.pcm: this device's own microphone, via
 *    MediaRecorder.AudioSource.VOICE_COMMUNICATION (includes echo cancellation).
 *  - remote_<timestamp>.pcm: whatever WeChat is playing out loud, via
 *    AudioPlaybackCaptureConfiguration matching AudioAttributes.USAGE_VOICE_COMMUNICATION --
 *    the usage WeChat's call audio was confirmed to use earlier via `dumpsys audio`.
 *
 * This does NOT mix, encode, resample, or otherwise post-process anything. The sole purpose
 * right now is to find out whether remote_*.pcm actually contains real audio data at all.
 *
 * CONFIRMED (2026-09-10): on the real test device, AudioRecord.Builder().build() throws
 * "UnsupportedOperationException: Error: could not register audio policy" when matching
 * USAGE_VOICE_COMMUNICATION, even with a valid MediaProjection and an active, bound
 * accessibility service present. Whether this is specific to USAGE_VOICE_COMMUNICATION or
 * whether AudioPlaybackCapture is unavailable entirely on this ROM is still unknown -- see the
 * EXTRA_TEST_USAGE_MEDIA self-test mode below, which swaps in USAGE_MEDIA to isolate that
 * question without touching WeChat at all.
 *
 * Started/stopped by WeChatCallNotificationListenerService when it detects a WeChat call
 * starting/ending. Requires WeChatCallAudioCaptureHolder.mediaProjection to already be set,
 * which is obtained once via WeChatCallGrantActivity (a one-time manual step, same idea as
 * granting accessibility/notification access).
 */
class WeChatCallCaptureService : Service() {
    companion object {
        private val TAG = WeChatCallCaptureService::class.java.simpleName
        private const val CHANNEL_ID = "wechat_call_capture_test"
        private const val NOTIFICATION_ID = 0x57454348 // arbitrary but stable ("WECH")

        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // When true, matches USAGE_MEDIA instead of USAGE_VOICE_COMMUNICATION and auto-stops
        // after TEST_DURATION_MS. This is a standalone self-test, unrelated to WeChat, meant to
        // answer one narrow question: does AudioPlaybackCaptureConfiguration work AT ALL on this
        // device for a usage that third-party apps are normally allowed to capture? If this
        // still produces remoteBytes=0 (or the same exception), the whole mechanism is broken on
        // this ROM, not just the voice-communication usage specifically.
        const val EXTRA_TEST_USAGE_MEDIA = "test_usage_media"
        private const val TEST_DURATION_MS = 15_000L
    }

    private var micRecord: AudioRecord? = null
    private var remoteRecord: AudioRecord? = null
    private val running = AtomicBoolean(false)
    private var micThread: Thread? = null
    private var remoteThread: Thread? = null
    private var micBytesWritten = 0L
    private var remoteBytesWritten = 0L
    private var testMode = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        testMode = intent?.getBooleanExtra(EXTRA_TEST_USAGE_MEDIA, false) == true
        startForeground(NOTIFICATION_ID, buildNotification())
        startCapture()

        if (testMode) {
            Handler(Looper.getMainLooper()).postDelayed({ stopSelf() }, TEST_DURATION_MS)
        }

        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "微信通话录音测试",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("正在测试微信通话录音（实验）")
            .setSmallIcon(R.drawable.ic_launcher_quick_settings)
            .setOngoing(true)
            .build()
    }

    private fun outputDir(): File {
        val dir = File(getExternalFilesDir(null), "wechat_call_test")
        dir.mkdirs()
        return dir
    }

    private fun startCapture() {
        if (running.getAndSet(true)) {
            return
        }
        micBytesWritten = 0
        remoteBytesWritten = 0

        val timestamp = System.currentTimeMillis()
        val bufferSize = AudioRecord
            .getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT)
            .coerceAtLeast(4096)

        // Mic side: your own voice, with echo cancellation applied by VOICE_COMMUNICATION source.
        val mic = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            CHANNEL_CONFIG_IN,
            AUDIO_FORMAT,
            bufferSize,
        )
        micRecord = mic

        val micFile = File(outputDir(), "mic_$timestamp.pcm")
        micThread = thread(name = "WeChatCallCaptureMic") {
            recordLoop(mic, micFile) { micBytesWritten += it }
        }
        mic.startRecording()

        // Remote side: whatever is playing out loud, via playback capture. Only attempted on
        // API 29+ (AudioPlaybackCaptureConfiguration doesn't exist below that); this project's
        // minSdk is 28, so this whole block is runtime-guarded rather than assumed available.
        var remoteReady = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = WeChatCallAudioCaptureHolder.mediaProjection
            if (projection == null) {
                Log.w(
                    TAG,
                    "No MediaProjection available -- open the grant screen first. " +
                        "Only recording mic this time.",
                )
            } else {
                try {
                    val usage = if (testMode) {
                        AudioAttributes.USAGE_MEDIA
                    } else {
                        AudioAttributes.USAGE_VOICE_COMMUNICATION
                    }
                    val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(usage)
                        .build()
                    val format = AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG_IN)
                        .build()
                    val remote = AudioRecord.Builder()
                        .setAudioFormat(format)
                        .setAudioPlaybackCaptureConfig(captureConfig)
                        .setBufferSizeInBytes(bufferSize)
                        .build()
                    remoteRecord = remote

                    val remoteFile = File(outputDir(), "remote_$timestamp.pcm")
                    remoteThread = thread(name = "WeChatCallCaptureRemote") {
                        recordLoop(remote, remoteFile) { remoteBytesWritten += it }
                    }
                    remote.startRecording()
                    remoteReady = true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start playback capture", e)
                }
            }
        } else {
            Log.w(TAG, "SDK ${Build.VERSION.SDK_INT} < 29, playback capture unavailable")
        }

        // Explicit, unambiguous status lines -- a single "Capture started" line here previously
        // printed even when the remote side had already failed above, which was misleading.
        Log.i(TAG, "Mode: ${if (testMode) "SELF-TEST (USAGE_MEDIA)" else "WECHAT_CALL (USAGE_VOICE_COMMUNICATION)"}")
        Log.i(TAG, "Mic capture: OK")
        Log.i(TAG, if (remoteReady) "Playback capture: OK" else "Playback capture: FAILED")
        Log.i(TAG, "Output dir: ${outputDir()}")
    }

    private fun recordLoop(record: AudioRecord, file: File, onBytes: (Int) -> Unit) {
        val buffer = ByteArray(4096)
        try {
            FileOutputStream(file).use { out ->
                while (running.get()) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        out.write(buffer, 0, read)
                        onBytes(read)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in record loop for ${file.name}", e)
        }
    }

    override fun onDestroy() {
        running.set(false)

        micThread?.join(1000)
        remoteThread?.join(1000)

        micRecord?.apply {
            stop()
            release()
        }
        remoteRecord?.apply {
            stop()
            release()
        }
        micRecord = null
        remoteRecord = null

        Log.i(
            TAG,
            "Capture stopped (mode=${if (testMode) "SELF-TEST" else "WECHAT_CALL"}): " +
                "micBytes=$micBytesWritten remoteBytes=$remoteBytesWritten " +
                "(non-zero remoteBytes containing real audio, not just silence, means it worked)",
        )

        super.onDestroy()
    }
}
