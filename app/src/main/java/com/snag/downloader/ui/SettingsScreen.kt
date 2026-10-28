package com.snag.downloader.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.snag.downloader.data.QualityPreference
import com.snag.downloader.scroll.ScrollModeService
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: SnagViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val startSignIn = rememberTikTokSignIn { vm.markTikTokSignedIn() }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScreenHeader(title = "Settings")

            Text(
                "Save quality",
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                style = MaterialTheme.typography.titleMedium,
            )

            SnagCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(0.dp),
            ) {
                QualityPreference.entries.forEachIndexed { index, option ->
                    if (index > 0) SnagDivider()
                    QualityRow(
                        option = option,
                        selected = vm.quality == option,
                        onSelect = { vm.updateQuality(option) },
                    )
                }
            }

            Text(
                "Saving",
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                style = MaterialTheme.typography.titleMedium,
            )

            SnagCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            ) {
                LabelValueRow("Folder", "Downloads/Snag")
                SnagDivider()
                SettingSwitch(
                    title = "Auto-save clipboard links",
                    checked = vm.autoClipboard,
                    onCheckedChange = vm::updateAutoClipboard,
                )
                SnagDivider()
                SettingSwitch(
                    title = "Allow TikWM fallback",
                    checked = vm.useFallbackService,
                    onCheckedChange = vm::updateUseFallbackService,
                )
                SnagDivider()
                SettingLink(
                    title = "Notifications",
                    icon = Icons.Rounded.Notifications,
                    onClick = { openNotificationSettings(context) },
                )
            }

            Text(
                "Scroll mode",
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                style = MaterialTheme.typography.titleMedium,
            )

            SnagCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            ) {
                SettingSwitch(
                    title = "Scroll mode",
                    checked = vm.scrollModeEnabled,
                    onCheckedChange = { enabled ->
                        vm.updateScrollMode(enabled)
                        if (enabled && !AndroidSettings.canDrawOverlays(context)) {
                            openOverlaySettings(context)
                        }
                        ScrollModeService.sync(context)
                    },
                )
                SnagDivider()
                SettingLink(
                    title = "Display over other apps",
                    icon = Icons.Rounded.Layers,
                    onClick = { openOverlaySettings(context) },
                )
                SnagDivider()
                LabelValueRow(
                    "Floating crown",
                    if (AndroidSettings.canDrawOverlays(context)) "Allowed" else "Permission needed",
                )
                SnagDivider()
                SettingSwitch(
                    title = "Low-profile button",
                    checked = vm.lowProfileCrown,
                    onCheckedChange = { enabled ->
                        vm.updateLowProfileCrown(enabled)
                        ScrollModeService.refresh(context)
                    },
                )
                SnagDivider()
                Column(Modifier.padding(vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Button opacity",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "${(vm.scrollOverlayOpacity * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Slider(
                        value = vm.scrollOverlayOpacity,
                        onValueChange = vm::updateScrollOverlayOpacity,
                        onValueChangeFinished = { ScrollModeService.refresh(context) },
                        valueRange = com.snag.downloader.data.SettingsStore.MIN_OVERLAY_OPACITY..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    )
                }
            }

            Text(
                "Copy a TikTok link, then tap the floating crown. Snag reads the clipboard only when you tap. If your phone denies overlay access, use the notification's Save copied link action.",
                modifier = Modifier.padding(start = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                "Account",
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                style = MaterialTheme.typography.titleMedium,
            )

            SnagCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(0.dp),
            ) {
                val colors = snagColors()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("TikTok", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (vm.tiktokSignedIn) "Signed in" else "Not signed in",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (vm.tiktokSignedIn) colors.accent
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SnagTextButton(
                        text = if (vm.tiktokSignedIn) "Sign out" else "Sign in",
                        onClick = {
                            if (vm.tiktokSignedIn) confirmSignOut = true else startSignIn()
                        },
                    )
                }
            }

            Text(
                "History",
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                style = MaterialTheme.typography.titleMedium,
            )

            SnagCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(0.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { confirmClear = true }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Clear history",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                "About",
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                style = MaterialTheme.typography.titleMedium,
            )

            SnagCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            ) {
                LabelValueRow("Version", versionName(context))
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("Saved files stay on your phone. Only the history list is cleared.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.clearHistory()
                        confirmClear = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
            shape = RoundedCornerShape(12.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out of TikTok?") },
            text = { Text("This clears the TikTok web session from this app. Saved files stay on your phone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.signOutTikTok()
                        
                        confirmSignOut = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Sign out")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") }
            },
            shape = RoundedCornerShape(12.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QualityRow(
    option: QualityPreference,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = snagColors()
    val shape = RoundedCornerShape(8.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val rowBackground by animateColorAsState(
        targetValue = if (selected) colors.accent.copy(alpha = 0.06f) else Color.Transparent,
        animationSpec = tween(SnagMotion.ms(180)),
        label = "quality-row",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBackground)
            .then(if (focused) Modifier.border(2.dp, scheme.primary, shape) else Modifier)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = colors.accent,
                unselectedColor = scheme.outline,
            ),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            option.title,
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurface,
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = scheme.onPrimary,
                checkedTrackColor = scheme.primary,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = scheme.outline,
                uncheckedTrackColor = scheme.surfaceVariant,
                uncheckedBorderColor = scheme.outline,
            ),
        )
    }
}

@Composable
private fun SettingLink(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
        )
    }
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun openOverlaySettings(context: Context) {
    val intent = Intent(
        AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun versionName(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull() ?: "0.1.0"
