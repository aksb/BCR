/*
 * SPDX-FileCopyrightText: 2026 aksb
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr

import android.media.projection.MediaProjection

/**
 * Simple in-process holder for the MediaProjection token used to capture WeChat's call audio.
 *
 * MediaProjection can only be obtained via a user-facing system consent screen -- there is no
 * way to request it directly from a background service. WeChatCallGrantActivity obtains it once
 * and stores it here; WeChatCallCaptureService reads it when a call is detected.
 *
 * On Android 13 (this project's current test device), a granted MediaProjection instance can be
 * reused for multiple capture sessions as long as this process stays alive and
 * mediaProjection.stop() hasn't been called. If the process is killed by the system or the
 * token is otherwise revoked, this goes back to null and the user needs to re-grant it via
 * WeChatCallGrantActivity. (Newer Android versions are stricter about reusing a single grant
 * across multiple sessions -- not yet handled here.)
 */
object WeChatCallAudioCaptureHolder {
    var mediaProjection: MediaProjection? = null
}
