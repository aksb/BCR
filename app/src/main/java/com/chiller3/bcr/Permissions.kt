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
     * Get intent for opening the app info page in the system settings.
     */
    fun getAppInfoIntent(context: Context) = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
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
