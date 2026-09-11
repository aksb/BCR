/*
 * SPDX-FileCopyrightText: 2026 aksb
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Minimal, diagnostic-only activity whose only job is to trigger the system's MediaProjection
 * consent screen and store the resulting token in [WeChatCallAudioCaptureHolder].
 *
 * This has its own home screen icon (see the activity-alias in AndroidManifest.xml) so it can be
 * opened directly, without relying on `am start` via Termux/su -- that path is blocked by
 * SELinux on this device (see CUSTOM_CHANGES.md for details).
 *
 * There is no actual UI: it immediately shows the system consent dialog on launch, reports the
 * result via Toast, and finishes itself either way.
 */
class WeChatCallGrantActivity : ComponentActivity() {
    companion object {
        private val TAG = WeChatCallGrantActivity::class.java.simpleName
    }

    private val requestProjection = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            val projection = manager.getMediaProjection(result.resultCode, data)
            WeChatCallAudioCaptureHolder.mediaProjection = projection
            Log.i(TAG, "MediaProjection granted")

            // Immediately run a 15-second self-test using USAGE_MEDIA (not WeChat-related at
            // all) to isolate whether AudioPlaybackCaptureConfiguration works AT ALL on this
            // device, independent of whether USAGE_VOICE_COMMUNICATION specifically is blocked.
            val testIntent = Intent(this, WeChatCallCaptureService::class.java).apply {
                putExtra(WeChatCallCaptureService.EXTRA_TEST_USAGE_MEDIA, true)
            }
            startForegroundService(testIntent)

            Toast.makeText(
                this,
                "已获取授权，现在开始15秒自测——请播放一段音乐或视频",
                Toast.LENGTH_LONG,
            ).show()
        } else {
            Log.w(TAG, "MediaProjection request denied or cancelled")
            Toast.makeText(this, "未获得授权，无法测试通话录音", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
        requestProjection.launch(manager.createScreenCaptureIntent())
    }
}
