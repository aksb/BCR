/*
 * SPDX-FileCopyrightText: 2026 aksb
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.IBinder
import android.service.quicksettings.TileService
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.chiller3.bcr.output.OutputDirUtils
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
 * Started/stopped from three possible places, all converging on the same instance since only one
 * recording can be in progress at a time:
 *  - WeChatCallNotificationListenerService: fully manual (the user taps the floating bubble) or
 *    automatic (Preferences.wechatAutoRecord), triggered by call detection; always stops
 *    automatically when WeChat's call notification disappears.
 *  - WeChatRecorderTileService: a Quick Settings tile that starts/stops recording directly,
 *    independent of call detection entirely -- meant to be tapped BEFORE a call even begins, to
 *    sidestep WeChat's own detection-timing quirks completely (see that class's doc). If tapped
 *    with no call ever actually happening, there's no notification to trigger an automatic stop,
 *    so it keeps recording until manually stopped (via the tile again, or the bubble once/if a
 *    real call does get detected and confirms it).
 *  - The floating bubble itself, via WeChatCallNotificationListenerService.toggleRecordingFromBubble.
 *
 * [isRunning] is the single source of truth all three check to know whether a recording is
 * already in progress, so none of them ever start a second, overlapping one.
 */
class WeChatCallCaptureService : Service() {
    companion object {
        private val TAG = WeChatCallCaptureService::class.java.simpleName
        private const val CHANNEL_ID = "wechat_call_capture"
        private const val NOTIFICATION_ID = 0x57454348 // arbitrary but stable ("WECH")

        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // See recordLoop()'s diagnostics: samples at or below this are treated as "silence" for
        // the near-silence-run detector, and a run has to last at least this long to be logged
        // (short quiet moments between words are completely normal and not worth logging).
        private const val SILENCE_AMPLITUDE_THRESHOLD = 200
        private const val SILENCE_LOG_THRESHOLD_MS = 800L

        /** Whether a WeChat call recording is currently in progress, regardless of how it was started. */
        var isRunning = false
            private set
    }

    private var micRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private val running = AtomicBoolean(false)
    private var micThread: Thread? = null
    private var micBytesWritten = 0L
    private var micFile: File? = null
    private var captureStartedAtMs: Long = 0

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        requestTileRefresh()
    }

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
        captureStartedAtMs = timestamp
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
        disableAudioEffects(mic.audioSessionId)

        val micFile = File(localScratchDir(), "wechat_$timestamp.pcm")
        this.micFile = micFile
        micThread = thread(name = "WeChatCallCaptureMic") {
            recordLoop(mic, micFile) { micBytesWritten += it }
        }
        mic.startRecording()

        Log.i(TAG, "Capture started")
    }

    /**
     * Disables (and, where the platform allows, fully releases) the automatic echo cancellation,
     * noise suppression, and automatic gain control effects the system may otherwise attach to
     * this recording session.
     *
     * These effects exist to make *voice calls* sound cleaner for the person on the other end --
     * they suppress anything that resembles the device's own speaker output as "echo" and clamp
     * down on background noise/level swings. That's exactly the wrong behavior here: this class
     * captures a WeChat call "through the air" with a plain microphone (see the class doc above
     * for why -- direct playback capture is rejected by the OS for this use case), so WeChat's
     * own call audio playing out of the earpiece/speaker while we're trying to record it looks
     * *exactly* like the "echo" these effects exist to remove. They can end up muting or gating
     * out the very audio this recording exists to capture, which lines up with reports of
     * recordings sometimes having no sound at all, or cutting out for a few seconds at a time.
     * Disabling them here doesn't touch whatever WeChat's own internal call-audio pipeline does
     * (out of our control), but at least stops our own recording session from doing it a second
     * time on top of that.
     */
    private fun disableAudioEffects(sessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply {
                    enabled = false
                }
                Log.i(TAG, "AcousticEchoCanceler disabled for session $sessionId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to disable AcousticEchoCanceler", e)
        }
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply {
                    enabled = false
                }
                Log.i(TAG, "NoiseSuppressor disabled for session $sessionId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to disable NoiseSuppressor", e)
        }
        try {
            if (AutomaticGainControl.isAvailable()) {
                automaticGainControl = AutomaticGainControl.create(sessionId)?.apply {
                    enabled = false
                }
                Log.i(TAG, "AutomaticGainControl disabled for session $sessionId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to disable AutomaticGainControl", e)
        }
    }

    private fun recordLoop(record: AudioRecord, file: File, onBytes: (Int) -> Unit) {
        val buffer = ByteArray(4096)

        // Diagnostics only -- doesn't change what gets written to the file. Previously read()
        // return codes <= 0 (errors, or a zero-length read) were silently ignored here, so there
        // was no way to tell, after the fact, whether a "silent" stretch in a recording was
        // AudioRecord actually erroring out versus just correctly capturing real silence. This
        // makes both cases visible in Logcat without spamming a line per read: errors are logged
        // once when they start and once when they clear, and near-silent audio is only logged as
        // a single summary line once a run of it has lasted long enough to plausibly be the
        // "cuts out for a few seconds" symptom rather than just a normal pause in speech.
        var lastReadWasError = false
        var silenceRunStartedAtMs = -1L
        var silenceRunDurationMs = 0L

        try {
            FileOutputStream(file).use { out ->
                while (running.get()) {
                    val read = record.read(buffer, 0, buffer.size)

                    if (read > 0) {
                        if (lastReadWasError) {
                            Log.w(TAG, "AudioRecord.read() recovered after an error")
                            lastReadWasError = false
                        }

                        out.write(buffer, 0, read)
                        onBytes(read)

                        val maxAmplitude = maxAbsAmplitude(buffer, read)
                        // read is a byte count; 2 bytes/sample, mono, at SAMPLE_RATE Hz.
                        val chunkDurationMs = (read / 2) * 1000L / SAMPLE_RATE

                        if (maxAmplitude <= SILENCE_AMPLITUDE_THRESHOLD) {
                            if (silenceRunStartedAtMs < 0) {
                                silenceRunStartedAtMs = System.currentTimeMillis()
                            }
                            silenceRunDurationMs += chunkDurationMs
                        } else if (silenceRunStartedAtMs >= 0) {
                            if (silenceRunDurationMs >= SILENCE_LOG_THRESHOLD_MS) {
                                Log.w(
                                    TAG,
                                    "Detected ~${silenceRunDurationMs}ms of near-silence " +
                                        "(max amplitude <= $SILENCE_AMPLITUDE_THRESHOLD) " +
                                        "before audio resumed",
                                )
                            }
                            silenceRunStartedAtMs = -1
                            silenceRunDurationMs = 0
                        }
                    } else {
                        if (!lastReadWasError) {
                            Log.e(TAG, "AudioRecord.read() returned error code $read")
                            lastReadWasError = true
                        }
                    }
                }

                if (silenceRunDurationMs >= SILENCE_LOG_THRESHOLD_MS) {
                    Log.w(
                        TAG,
                        "Recording stopped with an ongoing ~${silenceRunDurationMs}ms " +
                            "near-silence run",
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in record loop for ${file.name}", e)
        }
    }

    /**
     * Max absolute sample value in a little-endian 16-bit PCM mono buffer, used only to flag
     * near-silent stretches for [recordLoop]'s diagnostics -- not a real loudness measurement.
     */
    private fun maxAbsAmplitude(buffer: ByteArray, length: Int): Int {
        var max = 0
        var i = 0
        while (i + 1 < length) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
            val abs = kotlin.math.abs(sample.toInt())
            if (abs > max) {
                max = abs
            }
            i += 2
        }
        return max
    }

    override fun onDestroy() {
        running.set(false)
        micThread?.join(1000)

        micRecord?.apply {
            stop()
            release()
        }
        micRecord = null

        echoCanceler?.release()
        echoCanceler = null
        noiseSuppressor?.release()
        noiseSuppressor = null
        automaticGainControl?.release()
        automaticGainControl = null

        val micWav = micFile?.let { wrapPcmAsWav(it) }
        micFile = null

        val finalFile: DocumentFile? = if (micWav != null) {
            try {
                // Human-readable display name, e.g. "WeChat_2026-09-12_14-30-05". Deliberately
                // NOT including the ".wav" extension here: moveToOutputDir/createFile appends
                // the correct extension itself based on the mimeType passed below, so including
                // it here as well previously produced files literally named "....wav.wav".
                val displayName = "WeChat_" + SimpleDateFormat(
                    "yyyy-MM-dd_HH-mm-ss",
                    Locale.US,
                ).format(Date(captureStartedAtMs))

                val dirUtils = OutputDirUtils(applicationContext, OutputDirUtils.NULL_REDACTOR)
                dirUtils.moveToOutputDir(
                    DocumentFile.fromFile(micWav),
                    listOf("WeChat", displayName),
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

        isRunning = false
        requestTileRefresh()

        super.onDestroy()
    }

    /**
     * Nudges WeChatRecorderTileService to re-check [isRunning] and update its appearance, in case
     * it's currently visible (e.g. the Quick Settings panel is open) when recording starts/stops
     * via some other path than the tile itself (the bubble, or auto-record on call detection).
     */
    private fun requestTileRefresh() {
        TileService.requestListeningState(
            this,
            ComponentName(this, WeChatRecorderTileService::class.java),
        )
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
