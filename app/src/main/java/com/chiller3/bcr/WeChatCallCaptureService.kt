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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.chiller3.bcr.output.OutputDirUtils
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * For the duration of a single detected WeChat call, this records the microphone with a plain
 * MediaRecorder.AudioSource.MIC to a temporary headerless .pcm file (streaming write). Once
 * stopped, that raw file is wrapped with a standard 44-byte WAV header and moved into the user's
 * configured output directory (same one real phone call recordings use, from
 * Preferences.outputDirOrDefault) under a "WeChat" subfolder, using the same OutputDirUtils
 * helper RecorderThread uses for real calls.
 *
 * BACKGROUND: earlier versions of this class tried to also capture WeChat's own playback (the
 * other party's voice) directly via AudioPlaybackCaptureConfiguration matching
 * USAGE_VOICE_COMMUNICATION, using a MediaProjection token obtained through a dedicated grant
 * screen. Real-device testing (2026-09-10/11) confirmed this is rejected at the OS audio policy
 * layer on this device regardless of accessibility-service state ("UnsupportedOperationException:
 * Error: could not register audio policy"), while the same mechanism worked fine for USAGE_MEDIA
 * -- so it's specifically WeChat's call-audio usage that's blocked here, not the mechanism in
 * general. Listening tests confirmed a plain MIC recording picks up both sides clearly enough on
 * this device regardless, so that dead-end code (the grant screen, the MediaProjection holder,
 * the playback-capture attempt here) was removed rather than kept as unused weight.
 *
 * Started/stopped by WeChatCallNotificationListenerService: recording start is either fully
 * manual (the user taps the floating bubble) or automatic (if the user has enabled that in
 * settings), but stopping is always automatic, triggered by WeChat's call notification
 * disappearing.
 */
class WeChatCallCaptureService : Service() {
    companion object {
        private val TAG = WeChatCallCaptureService::class.java.simpleName
        private const val CHANNEL_ID = "wechat_call_capture"
        private const val NOTIFICATION_ID = 0x57454348 // arbitrary but stable ("WECH")

        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var micRecord: AudioRecord? = null
    private val running = AtomicBoolean(false)
    private var micThread: Thread? = null
    private var micBytesWritten = 0L
    private var micFile: File? = null

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
                    "微信通话录音",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("正在录制微信通话")
            .setSmallIcon(R.drawable.ic_launcher_quick_settings)
            .setOngoing(true)
            .build()
    }

    private fun localScratchDir(): File {
        val dir = File(getExternalFilesDir(null), "wechat_call_scratch")
        dir.mkdirs()
        return dir
    }

    private fun startCapture() {
        if (running.getAndSet(true)) {
            return
        }
        micBytesWritten = 0
        micFile = null

        val timestamp = System.currentTimeMillis()
        val bufferSize = AudioRecord
            .getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT)
            .coerceAtLeast(4096)

        val mic = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG_IN,
            AUDIO_FORMAT,
            bufferSize,
        )
        micRecord = mic

        val micFile = File(localScratchDir(), "wechat_$timestamp.pcm")
        this.micFile = micFile
        micThread = thread(name = "WeChatCallCaptureMic") {
            recordLoop(mic, micFile) { micBytesWritten += it }
        }
        mic.startRecording()

        Log.i(TAG, "Capture started")
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

        micRecord?.apply {
            stop()
            release()
        }
        micRecord = null

        val micWav = micFile?.let { wrapPcmAsWav(it) }
        micFile = null

        val finalFile: DocumentFile? = if (micWav != null) {
            try {
                val dirUtils = OutputDirUtils(applicationContext, OutputDirUtils.NULL_REDACTOR)
                dirUtils.moveToOutputDir(
                    DocumentFile.fromFile(micWav),
                    listOf("WeChat", micWav.name),
                    "audio/x-wav",
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to move $micWav into user output directory", e)
                null
            }
        } else {
            null
        }

        Log.i(TAG, "Capture stopped: micBytes=$micBytesWritten finalFile=${finalFile?.uri}")

        super.onDestroy()
    }

    /**
     * Wraps a raw (headerless) 16kHz/mono/16-bit PCM file with a standard 44-byte WAV header and
     * deletes the original .pcm file. Reads the whole file into memory to do so -- fine for
     * these short test recordings (tens of seconds to a few minutes), but would need a
     * write-header-then-patch-at-the-end approach instead for anything long enough that loading
     * it all into memory becomes a problem.
     */
    private fun wrapPcmAsWav(pcmFile: File): File? {
        if (!pcmFile.exists() || pcmFile.length() == 0L) {
            return null
        }

        val wavFile = File(pcmFile.parentFile, pcmFile.nameWithoutExtension + ".wav")
        return try {
            val pcmData = pcmFile.readBytes()
            FileOutputStream(wavFile).use { out ->
                out.write(buildWavHeader(pcmData.size))
                out.write(pcmData)
            }
            pcmFile.delete()
            wavFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to wrap ${pcmFile.name} as WAV", e)
            null
        }
    }

    private fun buildWavHeader(dataSize: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // fmt chunk size for PCM
            putShort(1) // audio format: 1 = PCM
            putShort(channels.toShort())
            putInt(SAMPLE_RATE)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()
    }
}
