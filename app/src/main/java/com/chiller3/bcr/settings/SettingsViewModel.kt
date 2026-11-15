/*
 * SPDX-FileCopyrightText: 2023-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr.settings

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chiller3.bcr.Logcat
import com.chiller3.bcr.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class SettingsViewModel : ViewModel() {
    companion object {
        private val TAG = SettingsViewModel::class.java.simpleName
    }

    private val _alerts = MutableStateFlow<List<SettingsAlert>>(emptyList())
    val alerts = _alerts.asStateFlow()

    fun acknowledgeFirstAlert() {
        _alerts.update { it.drop(1) }
    }

    fun addAlert(alert: SettingsAlert) {
        _alerts.update { it + alert }
    }

    fun saveLogs(uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    Logcat.dump(uri)
                }
                _alerts.update { it + SettingsAlert.LogcatSucceeded(uri) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to dump logs to $uri", e)
                _alerts.update { it + SettingsAlert.LogcatFailed(uri, e.toString()) }
            }
        }
    }

    /** Export all app settings as a JSON document to [uri]. */
    fun backupSettings(context: Context, uri: Uri) {
        val appContext = context.applicationContext

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val json = Preferences(appContext).exportToJson()
                    val out = appContext.contentResolver.openOutputStream(uri)
                        ?: throw IOException("Failed to open URI: $uri")
                    out.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                }
                _alerts.update { it + SettingsAlert.BackupSucceeded(uri) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to back up settings to $uri", e)
                _alerts.update { it + SettingsAlert.BackupFailed(uri, e.toString()) }
            }
        }
    }

    /**
     * Restore all app settings from a JSON document previously produced by [backupSettings].
     * [onRestored] is invoked on success so the caller can refresh any cached preference state.
     */
    fun restoreSettings(context: Context, uri: Uri, onRestored: () -> Unit) {
        val appContext = context.applicationContext

        viewModelScope.launch {
            try {
                val outputDirNeedsReselect = withContext(Dispatchers.IO) {
                    val json = appContext.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: throw IOException("Failed to open URI: $uri")

                    Preferences(appContext).importFromJson(json)
                }
                onRestored()
                _alerts.update {
                    it + if (outputDirNeedsReselect) {
                        SettingsAlert.RestoreSucceededOutputDirNeedsReselect(uri)
                    } else {
                        SettingsAlert.RestoreSucceeded(uri)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore settings from $uri", e)
                _alerts.update { it + SettingsAlert.RestoreFailed(uri, e.toString()) }
            }
        }
    }
}
