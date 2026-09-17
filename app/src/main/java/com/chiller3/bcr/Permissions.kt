/*
 * SPDX-FileCopyrightText: 2022-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Permissions {
    private val NOTIFICATION: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf()
        }

    val REQUIRED: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO) + NOTIFICATION
    val OPTIONAL: Array<String> = arrayOf(
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_PHONE_STATE,
    )

    /**
     * Check if all permissions required for call recording have been granted.
     */
    fun haveRequired(context: Context): Boolean =
        REQUIRED.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * Check if the microphone permission alone has been granted. Needed by every recording path
     * (system calls and WeChat calls alike) -- without it, WeChatCallCaptureService in particular
     * currently has no guard at all around its `AudioRecord(...)` construction and will error out
     * as soon as it's asked to actually record.
     */
    fun haveMicrophone(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Check if the notification permission has been granted. Always true below API 33, where
     * this permission doesn't exist and foreground service notifications don't need it.
     */
    fun haveNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * Check if all optional permissions (call log, contacts, phone state -- only used to enrich
     * system call recording filenames/metadata, never required for recording to work at all)
     * have been granted.
     */
    fun haveOptional(context: Context): Boolean =
        OPTIONAL.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * Get intent for opening the app info page in the system settings.
     */
    fun getAppInfoIntent(context: Context) = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )

    /**
     * Check whether this app currently has the "draw over other apps" permission granted, needed
     * by both the system call recording bubble and the WeChat call recording bubble.
     */
    fun haveOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * Get intent for opening the system's "draw over other apps" settings screen for this app.
     */
    fun getOverlaySettingsIntent(context: Context) = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.fromParts("package", context.packageName, null),
    )

    /**
     * Check whether this app currently has notification listener access granted.
     *
     * WeChatCallNotificationListenerService (which both "Auto-record WeChat calls" and "WeChat
     * call recording bubble" depend on) needs this system-level "notification access" permission
     * to work at all. It cannot be requested via the normal runtime permission dialog -- the user
     * has to grant it manually from Settings > Apps > Special app access > Notification access.
     * Previously nothing checked this before letting either switch be turned on, so users could
     * flip them on with no permission granted and silently get no recordings at all.
     */
    fun isNotificationListenerEnabled(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

    /**
     * Get intent for opening the system's notification listener access settings screen, so the
     * user can grant (or review) notification access for this app.
     */
    fun getNotificationListenerSettingsIntent() =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
}
