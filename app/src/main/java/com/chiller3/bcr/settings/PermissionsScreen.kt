/*
 * SPDX-FileCopyrightText: 2026 aksb
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.chiller3.bcr.Permissions
import com.chiller3.bcr.R
import com.chiller3.bcr.ui.AppScreen
import com.chiller3.bcr.ui.BetterSegmentedShapes
import com.chiller3.bcr.ui.Preference
import com.chiller3.bcr.ui.PreferenceCategory
import com.chiller3.bcr.ui.PreferenceColumn
import com.chiller3.bcr.ui.PreferenceGap
import com.chiller3.bcr.ui.theme.Icons

/**
 * A single "grant everything at once" screen, listing every permission any recording feature in
 * the app needs, each with a live ✓ / ✕ status.
 *
 * Why this exists: permission checks used to be scattered across each individual switch (system
 * call recording, the two WeChat switches), each only checking the subset it happened to need,
 * with no single place to see the full picture. In particular, neither WeChat switch checked for
 * the microphone permission at all -- WeChatCallCaptureService has no guard around its
 * AudioRecord(...) call, so recording would simply error out if the mic permission wasn't
 * granted. This screen doesn't replace those per-switch checks (they still catch it if someone
 * flips a switch without visiting this screen first); it's a faster, one-stop way to see and fix
 * everything at once.
 *
 * The overlay permission and notification listener access are both "special app access"
 * permissions -- unlike the microphone/notification runtime permissions, Android has no API to
 * pop a normal dialog for them; each can only be granted from its own dedicated Settings screen,
 * one at a time. [grantAllPending] chains all of it (runtime permissions dialog, then overlay
 * settings, then notification listener settings) so the user only has to work through it once, in
 * order, rather than hunting for each one separately.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }

    // Neither the overlay permission nor notification listener access has a proper
    // ActivityResult callback (they're settings screens, not permission dialogs), so the
    // reliable way to notice the user granted (or revoked) either of them is to just re-check
    // every time this screen comes back into the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                reload++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val micGranted = remember(reload) { Permissions.haveMicrophone(context) }
    val notificationGranted = remember(reload) { Permissions.haveNotifications(context) }
    val overlayGranted = remember(reload) { Permissions.haveOverlay(context) }
    val listenerGranted = remember(reload) { Permissions.isNotificationListenerEnabled(context) }
    val optionalGranted = remember(reload) { Permissions.haveOptional(context) }

    val requestRuntimePermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        reload++
    }
    val requestOverlaySettings = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        reload++
    }
    val requestListenerSettings = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        reload++
    }

    fun requestOverlay() {
        requestOverlaySettings.launch(Permissions.getOverlaySettingsIntent(context))
    }

    fun requestListener() {
        requestListenerSettings.launch(Permissions.getNotificationListenerSettingsIntent())
    }

    // Walks through every not-yet-granted permission, one screen/dialog at a time. Each launcher
    // callback above just increments `reload`, which recomposes this function and re-evaluates
    // the `if` chain below from the top -- so calling this once after each step naturally
    // advances to the next still-missing item, and stops calling anything once everything is
    // granted.
    fun grantAllPending() {
        if (!micGranted || !notificationGranted) {
            requestRuntimePermissions.launch(Permissions.REQUIRED)
        } else if (!overlayGranted) {
            requestOverlay()
        } else if (!listenerGranted) {
            requestListener()
        } else if (!optionalGranted) {
            requestRuntimePermissions.launch(Permissions.OPTIONAL)
        }
    }

    AppScreen(
        title = { Text(text = stringResource(R.string.pref_permissions_name)) },
        onBack = onBack,
    ) { params ->
        PreferenceColumn(contentPadding = params.contentPadding) {
            item(key = "grant_all") {
                Preference(
                    onClick = ::grantAllPending,
                    shapes = BetterSegmentedShapes.single(),
                    title = { Text(text = stringResource(R.string.pref_grant_all_name)) },
                    summary = { Text(text = stringResource(R.string.pref_grant_all_desc)) },
                )
            }

            item(key = "required_gap") {
                PreferenceGap()
            }

            item(key = "required_header") {
                PreferenceCategory(
                    title = {
                        Text(text = stringResource(R.string.pref_permissions_required_header))
                    },
                )
            }

            item(key = "mic") {
                PermissionStatusRow(
                    granted = micGranted,
                    shapes = BetterSegmentedShapes.top(),
                    title = stringResource(R.string.pref_permission_mic_name),
                    summary = stringResource(R.string.pref_permission_mic_desc),
                    onClick = {
                        if (!micGranted) {
                            requestRuntimePermissions.launch(
                                arrayOf(Manifest.permission.RECORD_AUDIO),
                            )
                        }
                    },
                )
            }

            item(key = "notification") {
                PermissionStatusRow(
                    granted = notificationGranted,
                    shapes = BetterSegmentedShapes.middle(),
                    title = stringResource(R.string.pref_permission_notification_name),
                    summary = stringResource(R.string.pref_permission_notification_desc),
                    onClick = {
                        if (!notificationGranted &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        ) {
                            requestRuntimePermissions.launch(
                                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                            )
                        }
                    },
                )
            }

            item(key = "overlay") {
                PermissionStatusRow(
                    granted = overlayGranted,
                    shapes = BetterSegmentedShapes.middle(),
                    title = stringResource(R.string.pref_permission_overlay_name),
                    summary = stringResource(R.string.pref_permission_overlay_desc),
                    onClick = { if (!overlayGranted) requestOverlay() },
                )
            }

            item(key = "listener") {
                PermissionStatusRow(
                    granted = listenerGranted,
                    shapes = BetterSegmentedShapes.bottom(),
                    title = stringResource(R.string.pref_permission_listener_name),
                    summary = stringResource(R.string.pref_permission_listener_desc),
                    onClick = { if (!listenerGranted) requestListener() },
                )
            }

            item(key = "optional_gap") {
                PreferenceGap()
            }

            item(key = "optional_header") {
                PreferenceCategory(
                    title = {
                        Text(text = stringResource(R.string.pref_permissions_optional_header))
                    },
                )
            }

            item(key = "optional") {
                PermissionStatusRow(
                    granted = optionalGranted,
                    shapes = BetterSegmentedShapes.single(),
                    title = stringResource(R.string.pref_permission_optional_name),
                    summary = stringResource(R.string.pref_permission_optional_desc),
                    onClick = {
                        if (!optionalGranted) {
                            requestRuntimePermissions.launch(Permissions.OPTIONAL)
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PermissionStatusRow(
    granted: Boolean,
    shapes: ListItemShapes,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Preference(
        onClick = onClick,
        shapes = shapes,
        title = { Text(text = title) },
        summary = { Text(text = summary) },
        trailingContent = {
            val description = if (granted) {
                stringResource(R.string.permission_status_granted)
            } else {
                stringResource(R.string.permission_status_not_granted)
            }
            if (granted) {
                Icon(
                    imageVector = Icons.Check,
                    contentDescription = description,
                    tint = Color(0xFF2E7D32),
                )
            } else {
                Icon(
                    imageVector = Icons.Close,
                    contentDescription = description,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}
