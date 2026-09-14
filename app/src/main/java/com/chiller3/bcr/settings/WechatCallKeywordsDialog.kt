/*
 * SPDX-FileCopyrightText: 2026 aksb
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chiller3.bcr.Preferences
import com.chiller3.bcr.R

/**
 * Lets the user edit the comma-separated keywords matched against WeChat's call notification
 * text (see Preferences.wechatCallKeywords's doc for why this exists at all). Pre-filled with
 * the currently active value (which defaults to the built-in defaults if never customized), so
 * the user is editing/extending what's already there rather than starting from a blank field.
 */
@Composable
fun WechatCallKeywordsDialog(
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialValue) }

    AlertDialog(
        title = { Text(text = stringResource(R.string.pref_wechat_call_keywords_name)) },
        text = {
            Column {
                Text(text = stringResource(R.string.pref_wechat_call_keywords_dialog_hint))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    singleLine = true,
                )
            }
        },
        onDismissRequest = onDismiss,
        dismissButton = {
            TextButton(onClick = { text = Preferences.DEFAULT_WECHAT_CALL_KEYWORDS_STRING }) {
                Text(text = stringResource(R.string.pref_wechat_call_keywords_reset))
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(text = stringResource(android.R.string.ok))
            }
        },
    )
}
