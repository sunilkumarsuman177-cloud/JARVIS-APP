package com.example.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.JarvisOrchestratorState
import com.example.service.JarvisRuntimeSnapshot
import com.example.ui.theme.AmoledBlack
import com.example.ui.theme.JarvisCyan
import com.example.ui.theme.MutedTextGray
import com.example.ui.theme.ObsidianCard
import com.example.ui.theme.SleepingGray
import com.example.ui.theme.SubtleTextGray
import com.example.ui.theme.TextPrimary

/**
 * Center Hero Element:
 * - When SLEEPING: A minimalist, hollow gray ring (#333333).
 * - When AWAKE/LISTENING: The ring pulses smoothly with an Electric Cyan (#00E5FF) glow.
 * - Tap this ring to manually toggle Wake/Sleep.
 */
@Composable
fun MinimalistCenterHeroRing(
    snapshot: JarvisRuntimeSnapshot,
    onToggleWakeSleep: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isActive = snapshot.state != JarvisOrchestratorState.SLEEPING

    val ringColor by animateColorAsState(
        targetValue = if (isActive) JarvisCyan else SleepingGray,
        animationSpec = tween(durationMillis = 450),
        label = "heroRingColor"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "heroRingPulse")
    val pulseRadiusMultiplier by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isActive) 1300 else 2800,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseRadiusMultiplier"
    )

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.34f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    val statusCaption = when (snapshot.state) {
        JarvisOrchestratorState.SLEEPING -> "SLEEPING"
        JarvisOrchestratorState.RECORDING -> "RECORDING • ${snapshot.remainingWakeSeconds}s"
        JarvisOrchestratorState.TRANSCRIBING -> "TRANSCRIBING"
        JarvisOrchestratorState.WAKING_BRAIN -> "WAKING BRAIN"
        JarvisOrchestratorState.ONLINE -> "LISTENING • ${snapshot.remainingWakeSeconds}s"
        JarvisOrchestratorState.EXECUTING -> "EXECUTING"
    }

    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(210.dp)
                .clip(CircleShape)
                .semantics {
                    contentDescription = "Toggle JARVIS Wake or Sleep"
                    role = Role.Button
                }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onToggleWakeSleep
                )
                .testTag("wake_sleep_override_button")
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val baseRadius = size.minDimension * 0.34f

                if (isActive) {
                    // Smooth Electric Cyan radial ambient glow + outer pulsing halo
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                JarvisCyan.copy(alpha = glowAlpha),
                                JarvisCyan.copy(alpha = glowAlpha * 0.35f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = baseRadius * pulseRadiusMultiplier * 1.35f
                        ),
                        radius = baseRadius * pulseRadiusMultiplier * 1.35f
                    )

                    drawCircle(
                        color = JarvisCyan.copy(alpha = glowAlpha * 0.9f),
                        radius = baseRadius * pulseRadiusMultiplier,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }

                // Core minimalist hollow ring
                drawCircle(
                    color = ringColor,
                    radius = baseRadius,
                    style = Stroke(width = if (isActive) 2.5.dp.toPx() else 1.5.dp.toPx())
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = statusCaption,
            color = if (isActive) JarvisCyan else MutedTextGray,
            fontSize = 11.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Light,
            letterSpacing = 2.8.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("center_status_indicator")
        )
    }
}

/**
 * The Terminal (Bottom 30% of screen):
 * A sleek, dark monospace text area fading into the background.
 * Silently logs what Vosk hears and the JSON executed by the GGUF model.
 * No bulky borders — just floating text that auto-scrolls.
 */
@Composable
fun FloatingBottomTerminal(
    snapshot: JarvisRuntimeSnapshot,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val logs = snapshot.terminalLogs

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .testTag("terminal_log_card")
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)
        ) {
            items(logs, key = { it.id }) { entry ->
                val isActiveLine = entry.isJson || entry.tag == "HEARD" || entry.tag == "EXEC"
                val lineColor = when {
                    entry.isJson -> JarvisCyan
                    entry.tag == "HEARD" -> TextPrimary
                    else -> MutedTextGray
                }
                Text(
                    text = "${entry.timestamp}  ${entry.tag.lowercase()} › ${entry.message}",
                    color = lineColor,
                    fontSize = 11.sp,
                    fontWeight = if (isActiveLine) FontWeight.Normal else FontWeight.Light,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 15.sp
                )
            }
        }

        // Top gradient fade into #050505 AMOLED background for a seamless floating effect
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(AmoledBlack, Color.Transparent)
                    )
                )
        )
    }
}

/**
 * Clean Dialog Box to Edit and Save the Custom Wake Phrase
 */
@Composable
fun WakeLineDialog(
    wakeLineDraft: String,
    onWakeLineDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ObsidianCard,
        titleContentColor = TextPrimary,
        textContentColor = SubtleTextGray,
        title = {
            Text(
                text = "Change Wake Line",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Vosk listens offline for this exact phrase while SLEEPING:",
                    color = SubtleTextGray,
                    fontSize = 12.sp
                )
                OutlinedTextField(
                    value = wakeLineDraft,
                    onValueChange = onWakeLineDraftChange,
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = JarvisCyan,
                        unfocusedBorderColor = SleepingGray,
                        focusedContainerColor = AmoledBlack,
                        unfocusedContainerColor = AmoledBlack,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("wake_line_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                colors = ButtonDefaults.buttonColors(
                    containerColor = JarvisCyan,
                    contentColor = AmoledBlack
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.testTag("update_wake_line_button")
            ) {
                Text(
                    text = "Save",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = SubtleTextGray, fontSize = 12.sp)
            }
        }
    )
}

/**
 * Clean Dialog Box to Manage Microphone, Accessibility Service, Battery Unrestricted, and /Download/ GGUF path
 */
@Composable
fun PermissionsDashboardDialog(
    micPermissionGranted: Boolean,
    accessibilityConnected: Boolean,
    overlayPermissionGranted: Boolean,
    batteryOptimizationIgnored: Boolean,
    onGrantMicPermission: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onRequestIgnoreBatteryOptimizations: () -> Unit,
    onUseDefaultDownloadGguf: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ObsidianCard,
        titleContentColor = TextPrimary,
        textContentColor = SubtleTextGray,
        title = {
            Text(
                text = "Permissions Dashboard",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PermissionDialogItem(
                    icon = Icons.Default.Mic,
                    title = "Microphone & Phone",
                    status = if (micPermissionGranted) "ACTIVE" else "GRANT",
                    isEnabled = micPermissionGranted,
                    testTag = "grant_mic_permission_button",
                    onClick = onGrantMicPermission
                )

                HorizontalDivider(color = SleepingGray.copy(alpha = 0.4f), thickness = 0.5.dp)

                PermissionDialogItem(
                    icon = Icons.Default.AccessibilityNew,
                    title = "Accessibility Service",
                    status = if (accessibilityConnected) "ACTIVE" else "ENABLE",
                    isEnabled = accessibilityConnected,
                    testTag = "enable_accessibility_button",
                    onClick = onOpenAccessibilitySettings
                )

                HorizontalDivider(color = SleepingGray.copy(alpha = 0.4f), thickness = 0.5.dp)

                PermissionDialogItem(
                    icon = Icons.Default.CheckCircle,
                    title = "Overlay Floating Pill",
                    status = if (overlayPermissionGranted) "ACTIVE" else "ALLOW",
                    isEnabled = overlayPermissionGranted,
                    testTag = "enable_overlay_pill_button",
                    onClick = onOpenOverlaySettings
                )

                HorizontalDivider(color = SleepingGray.copy(alpha = 0.4f), thickness = 0.5.dp)

                PermissionDialogItem(
                    icon = Icons.Default.BatteryChargingFull,
                    title = "Battery Unrestricted",
                    status = if (batteryOptimizationIgnored) "ACTIVE" else "UNRESTRICT",
                    isEnabled = batteryOptimizationIgnored,
                    testTag = "ignore_battery_opt_button",
                    onClick = onRequestIgnoreBatteryOptimizations
                )

                HorizontalDivider(color = SleepingGray.copy(alpha = 0.4f), thickness = 0.5.dp)

                PermissionDialogItem(
                    icon = Icons.Default.FolderOpen,
                    title = "Auto-Link /Download/ .gguf",
                    status = "LINK",
                    isEnabled = false,
                    testTag = "scan_download_gguf_button",
                    onClick = onUseDefaultDownloadGguf
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Done",
                    color = JarvisCyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
    )
}

@Composable
private fun PermissionDialogItem(
    icon: ImageVector,
    title: String,
    status: String,
    isEnabled: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (isEnabled) Icons.Default.CheckCircle else icon,
                contentDescription = title,
                tint = if (isEnabled) JarvisCyan else SubtleTextGray,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 13.sp
            )
        }
        Text(
            text = status,
            color = if (isEnabled) JarvisCyan else SubtleTextGray,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}
