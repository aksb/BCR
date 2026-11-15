/*
 * SPDX-FileCopyrightText: 2023-2024 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr.settings

import android.net.Uri

sealed interface SettingsAlert {
    data class LogcatSucceeded(val uri: Uri) : SettingsAlert

    data class LogcatFailed(val uri: Uri, val error: String) : SettingsAlert

    data class BackupSucceeded(val uri: Uri) : SettingsAlert

    data class BackupFailed(val uri: Uri, val error: String) : SettingsAlert

    data class RestoreSucceeded(val uri: Uri) : SettingsAlert

    /**
     * Settings were restored, but the recording output directory could not be (its access grant
     * is gone, most likely because the app was reinstalled or its data was cleared since the
     * backup was made) and fell back to the default. The user needs to pick their folder again.
     */
    data class RestoreSucceededOutputDirNeedsReselect(val uri: Uri) : SettingsAlert

    data class RestoreFailed(val uri: Uri, val error: String) : SettingsAlert

    data object BrowserNotFound : SettingsAlert

    data object DocumentsUINotFound : SettingsAlert
}
