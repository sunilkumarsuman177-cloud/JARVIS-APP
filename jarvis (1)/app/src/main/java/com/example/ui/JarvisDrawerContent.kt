package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AmoledBlack
import com.example.ui.theme.JarvisCyan
import com.example.ui.theme.MutedTextGray
import com.example.ui.theme.ObsidianCard
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.SleepingGray
import com.example.ui.theme.SubtleTextGray
import com.example.ui.theme.TextPrimary

@Composable
fun JarvisDrawerContent(
    voskReady: Boolean,
    voskDownloadProgress: Int?,
    voskStatusText: String,
    whisperReady: Boolean,
    whisperDownloadProgress: Int?,
    whisperStatusText: String,
    ggufDisplayName: String,
    ggufFileExists: Boolean,
    activeWakeLine: String,
    selectedTimeoutSec: Int,
    sharingInProgress: Boolean,
    onDownloadVoskClick: () -> Unit,
    onPickLocalVoskZipClick: () -> Unit,
    onDownloadWhisperClick: () -> Unit,
    onPickLocalWhisperBinClick: () -> Unit,
    onSelectGgufClick: () -> Unit,
    onOpenWakeLineDialog: () -> Unit,
    onSelectTimeoutSec: (Int) -> Unit,
    onOpenPermissionsDialog: () -> Unit,
    onShareApkClick: () -> Unit
) {
    var timerMenuExpanded by remember { mutableStateOf(false) }

    ModalDrawerSheet(
        drawerContainerColor = ObsidianSurface,
        drawerContentColor = TextPrimary,
        drawerTonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxHeight()
            .width(310.dp)
            .testTag("hamburger_drawer_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Drawer Header: "JARVIS" (bold) with subtext "Engineered by Prince Priyadarshi"
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "JARVIS",
                    color = TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp
                )
                Text(
                    text = "Engineered by Prince Priyadarshi",
                    color = SubtleTextGray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Light
                )
            }

            HorizontalDivider(color = SleepingGray.copy(alpha = 0.35f), thickness = 0.5.dp)

            // Section 1: Setup & Engine
            DrawerSectionGroup(title = "SETUP & ENGINE") {
                DrawerActionRow(
                    icon = Icons.Default.CloudDownload,
                    title = "Download Voice Pack (Vosk)",
                    subtitle = if (voskDownloadProgress != null) {
                        voskStatusText
                    } else if (voskReady) {
                        "Installed • Offline Wake Ear Active"
                    } else {
                        "36 MB • One-time offline wake model"
                    },
                    accentHighlight = voskReady,
                    testTag = "download_vosk_button",
                    onClick = onDownloadVoskClick
                )

                if (voskDownloadProgress != null) {
                    val progressFraction = (voskDownloadProgress.coerceIn(0, 100)) / 100f
                    LinearProgressIndicator(
                        progress = { progressFraction },
                        color = JarvisCyan,
                        trackColor = SleepingGray,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 36.dp, top = 4.dp, bottom = 4.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .testTag("vosk_download_progress_bar")
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 36.dp),
                    horizontalArrangement = Arrangement.Start
                ) {
                    TextButton(
                        onClick = onPickLocalVoskZipClick,
                        modifier = Modifier.testTag("pick_vosk_zip_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = MutedTextGray,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Or import local Vosk .zip",
                            color = MutedTextGray,
                            fontSize = 11.sp
                        )
                    }
                }

                // Requirement 4: UI Downloader for Whisper Model (75 MB)
                DrawerActionRow(
                    icon = Icons.Default.CloudDownload,
                    title = "Download Whisper Ear (75 MB)",
                    subtitle = if (whisperDownloadProgress != null) {
                        whisperStatusText
                    } else if (whisperReady) {
                        "Installed • ggml-tiny.bin Ready"
                    } else {
                        "75 MB • ggml-tiny.bin bilingual transcriber"
                    },
                    accentHighlight = whisperReady,
                    testTag = "download_whisper_button",
                    onClick = onDownloadWhisperClick
                )

                if (whisperDownloadProgress != null) {
                    val whisperProgressFraction = (whisperDownloadProgress.coerceIn(0, 100)) / 100f
                    LinearProgressIndicator(
                        progress = { whisperProgressFraction },
                        color = JarvisCyan,
                        trackColor = SleepingGray,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 36.dp, top = 4.dp, bottom = 4.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .testTag("whisper_download_progress_bar")
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 36.dp),
                    horizontalArrangement = Arrangement.Start
                ) {
                    TextButton(
                        onClick = onPickLocalWhisperBinClick,
                        modifier = Modifier.testTag("pick_whisper_bin_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = MutedTextGray,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Or import local ggml-tiny.bin",
                            color = MutedTextGray,
                            fontSize = 11.sp
                        )
                    }
                }

                DrawerActionRow(
                    icon = Icons.Default.Memory,
                    title = "Select Brain (.gguf)",
                    subtitle = if (ggufFileExists) {
                        "Linked: $ggufDisplayName"
                    } else {
                        "Pick $ggufDisplayName"
                    },
                    accentHighlight = ggufFileExists,
                    testTag = "select_gguf_button",
                    onClick = onSelectGgufClick
                )
            }

            HorizontalDivider(color = SleepingGray.copy(alpha = 0.35f), thickness = 0.5.dp)

            // Section 2: Configuration
            DrawerSectionGroup(title = "CONFIGURATION") {
                DrawerActionRow(
                    icon = Icons.Default.RecordVoiceOver,
                    title = "Change Wake Line",
                    subtitle = "\"$activeWakeLine\"",
                    accentHighlight = false,
                    testTag = "open_wake_line_dialog_button",
                    onClick = onOpenWakeLineDialog
                )

                Box(modifier = Modifier.fillMaxWidth()) {
                    DrawerActionRow(
                        icon = Icons.Default.Timer,
                        title = "Auto-Sleep Timer",
                        subtitle = "${selectedTimeoutSec}s inactivity unload",
                        trailingBadge = "${selectedTimeoutSec}s ▾",
                        accentHighlight = false,
                        testTag = "auto_sleep_timer_row",
                        onClick = { timerMenuExpanded = true }
                    )

                    DropdownMenu(
                        expanded = timerMenuExpanded,
                        onDismissRequest = { timerMenuExpanded = false },
                        modifier = Modifier.background(ObsidianCard)
                    ) {
                        listOf(30, 60, 120).forEach { seconds ->
                            val isSelected = seconds == selectedTimeoutSec
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (seconds == 60) "60s (Default)" else "${seconds}s",
                                        color = if (isSelected) JarvisCyan else TextPrimary,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                },
                                onClick = {
                                    timerMenuExpanded = false
                                    onSelectTimeoutSec(seconds)
                                },
                                modifier = Modifier.testTag("timeout_chip_${seconds}s")
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = SleepingGray.copy(alpha = 0.35f), thickness = 0.5.dp)

            // Section 3: System Access
            DrawerSectionGroup(title = "SYSTEM ACCESS") {
                DrawerActionRow(
                    icon = Icons.Default.Security,
                    title = "Permissions Dashboard",
                    subtitle = "Microphone, Accessibility & Battery",
                    accentHighlight = false,
                    testTag = "open_permissions_dialog_button",
                    onClick = onOpenPermissionsDialog
                )
            }

            HorizontalDivider(color = SleepingGray.copy(alpha = 0.35f), thickness = 0.5.dp)

            // Section 4: App Sharing
            DrawerSectionGroup(title = "APP SHARING") {
                DrawerActionRow(
                    icon = Icons.Default.Share,
                    title = if (sharingInProgress) "Extracting APK..." else "Share JARVIS APK",
                    subtitle = "Send installable .apk via WhatsApp / Bluetooth",
                    accentHighlight = true,
                    testTag = "share_apk_button",
                    onClick = {
                        if (!sharingInProgress) {
                            onShareApkClick()
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "100% OFFLINE • GALAXY A10 TUNED",
                color = SleepingGray,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.2.sp
            )
        }
    }
}

@Composable
private fun DrawerSectionGroup(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            color = MutedTextGray,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.6.sp
        )
        content()
    }
}

@Composable
private fun DrawerActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentHighlight: Boolean,
    testTag: String,
    trailingBadge: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = if (accentHighlight) JarvisCyan else SubtleTextGray,
            modifier = Modifier.size(20.dp)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                color = if (accentHighlight) JarvisCyan else TextPrimary,
                fontSize = 14.sp,
                fontWeight = if (accentHighlight) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(
                text = subtitle,
                color = MutedTextGray,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (trailingBadge != null) {
            Text(
                text = trailingBadge,
                color = JarvisCyan,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(AmoledBlack, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}
