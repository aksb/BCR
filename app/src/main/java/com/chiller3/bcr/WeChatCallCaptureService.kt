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
import android.os.IBinder
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
 * Android normally forbids third-party apps from capturing USAGE_VOICE_COMMUNICATION at all;
 * whether WeChatCallAccessibilityService simply being an active, bound accessibility service is
 * enough to unlock it on this device (in place of the CAPTURE_VOICE_COMMUNICATION_OUTPUT
 * permission, which doesn't even exist on this Android 13 build -- see CUSTOM_CHANGES.md) is
 * the open question this class exists to answer. A resulting file with a large, non-zero byte
 * count that is NOT just silence when played back means it worked; a tiny or all-zero file means
 * it didn't.
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
    }

    private var micRecord: AudioRecord? = null
    private var remoteRecord: AudioRecord? = null
    private val running = AtomicBoolean(false)
    private var micThread: Thread? = null
    private var remoteThread: Thread? = null
    private var micBytesWritten = 0L
    private var remoteBytesWritten = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        startCapture()
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

        // Remote side: whatever WeChat plays out loud, via playback capture. Only attempted on
        // API 29+ (AudioPlaybackCaptureConfiguration doesn't exist below that); this project's
        // minSdk is 28, so this whole block is runtime-guarded rather than assumed available.
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
                    val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start playback capture", e)
                }
            }
        } else {
            Log.w(TAG, "SDK ${Build.VERSION.SDK_INT} < 29, playback capture unavailable")
        }

        Log.i(TAG, "Capture started, writing to ${outputDir()}")
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
            "Capture stopped: micBytes=$micBytesWritten remoteBytes=$remoteBytesWritten " +
                "(non-zero remoteBytes containing real audio, not just silence, means it worked)",
        )

        super.onDestroy()
    }
}
