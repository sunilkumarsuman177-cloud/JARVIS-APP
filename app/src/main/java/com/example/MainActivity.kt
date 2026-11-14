package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.JarvisPreferences
import com.example.engine.VoskModelManager
import com.example.engine.WhisperModelManager
import com.example.service.JarvisAccessibilityService
import com.example.service.JarvisOrchestratorState
import com.example.service.JarvisService
import com.example.service.JarvisStateBus
import com.example.ui.BottomRightSiriPill
import com.example.ui.FloatingBottomTerminal
import com.example.ui.JarvisDrawerContent
import com.example.ui.MinimalistCenterHeroRing
import com.example.ui.PermissionsDashboardDialog
import com.example.ui.WakeLineDialog
import com.example.ui.theme.AmoledBlack
import com.example.ui.theme.JarvisCyan
import com.example.ui.theme.MutedTextGray
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.TextPrimary
import com.example.util.ApkSelfSharer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        JarvisService.sendAction(this, JarvisService.ACTION_START_OR_SYNC)

        setContent {
            MyApplicationTheme {
                JarvisOrchestratorScreen()
            }
        }
    }
}

@Composable
fun JarvisOrchestratorScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }
    val prefs = remember { JarvisPreferences(context) }

    val snapshot by JarvisStateBus.snapshot.collectAsState()
    val accessibilityConnected by JarvisAccessibilityService.isConnected.collectAsState()

    var wakeLineDraft by rememberSaveable { mutableStateOf(prefs.getWakeLine()) }
    var showWakeLineDialog by rememberSaveable { mutableStateOf(false) }
    var showPermissionsDialog by rememberSaveable { mutableStateOf(false) }
    var sharingApkInProgress by remember { mutableStateOf(false) }

    var micPermGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var overlayPermGranted by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }
    var batteryUnrestricted by remember {
        mutableStateOf(isIgnoringBatteryOptimizations(context))
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                micPermGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                overlayPermGranted = Settings.canDrawOverlays(context)
                batteryUnrestricted = isIgnoringBatteryOptimizations(context)
                JarvisService.sendAction(context, JarvisService.ACTION_START_OR_SYNC)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Runtime Permission Launcher
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val micOk = result[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        micPermGranted = micOk
        if (micOk) {
            JarvisService.sendAction(context, JarvisService.ACTION_RELOAD_VOSK)
            scope.launch {
                snackbarHostState.showSnackbar("Microphone granted • Offline Ear active")
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!micPermGranted) {
            permissionsLauncher.launch(buildRequiredPermissionsArray())
        }
    }

    // SAF Picker for .GGUF Brain File
    val ggufFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                snackbarHostState.showSnackbar("Linking selected .gguf brain...")
                val resolved = withContext(Dispatchers.IO) {
                    resolveOrCopyGgufUri(context, uri)
                }
                if (resolved != null) {
                    prefs.setGgufModelPath(resolved.first.absolutePath, resolved.second)
                    JarvisService.sendAction(context, JarvisService.ACTION_RELOAD_CONFIG)
                    JarvisStateBus.appendLog(
                        "GGUF",
                        "Selected Brain: ${resolved.second} (${resolved.first.length() / (1024 * 1024)} MB)"
                    )
                    snackbarHostState.showSnackbar("Brain linked: ${resolved.second}")
                } else {
                    snackbarHostState.showSnackbar("Could not read selected .gguf file")
                }
            }
        }
    }

    // SAF Picker for local Vosk .ZIP archive
    val voskZipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val res = VoskModelManager.importLocalVoskZip(context, uri) { pct, status ->
                    JarvisStateBus.updateState {
                        it.copy(voskDownloadProgress = pct, voskStatusText = status)
                    }
                }
                JarvisStateBus.updateState { it.copy(voskDownloadProgress = null) }
                res.onSuccess {
                    JarvisStateBus.appendLog("VOSK", "Installed offline Vosk pack into filesDir/vosk-model")
                    JarvisService.sendAction(context, JarvisService.ACTION_RELOAD_VOSK)
                    snackbarHostState.showSnackbar("Vosk Voice Pack ready")
                }.onFailure { err ->
                    JarvisStateBus.appendLog("ERROR", "Vosk zip import failed: ${err.localizedMessage}")
                    snackbarHostState.showSnackbar("Failed to unpack Vosk zip")
                }
            }
        }
    }

    // SAF Picker for local Whisper ggml-tiny.bin file
    val whisperBinPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val res = WhisperModelManager.importLocalWhisperBin(context, uri) { pct, status ->
                    JarvisStateBus.updateState {
                        it.copy(whisperDownloadProgress = pct, whisperStatusText = status)
                    }
                }
                JarvisStateBus.updateState { it.copy(whisperDownloadProgress = null) }
                res.onSuccess { file ->
                    JarvisStateBus.updateState {
                        it.copy(
                            whisperReady = true,
                            whisperStatusText = "Installed • ggml-tiny.bin (${file.length() / (1024 * 1024)} MB)"
                        )
                    }
                    JarvisStateBus.appendLog("WHISPER", "Imported ggml-tiny.bin into filesDir/models/ggml-tiny.bin")
                    snackbarHostState.showSnackbar("Whisper Ear ready (ggml-tiny.bin)")
                }.onFailure { err ->
                    JarvisStateBus.appendLog("ERROR", "Whisper import failed: ${err.localizedMessage}")
                    snackbarHostState.showSnackbar("Failed to import ggml-tiny.bin")
                }
            }
        }
    }

    if (showWakeLineDialog) {
        WakeLineDialog(
            wakeLineDraft = wakeLineDraft,
            onWakeLineDraftChange = { wakeLineDraft = it },
            onSave = {
                val cleaned = wakeLineDraft.trim().lowercase().ifEmpty { "hey jarvis" }
                wakeLineDraft = cleaned
                prefs.setWakeLine(cleaned)
                JarvisService.sendAction(context, JarvisService.ACTION_RELOAD_CONFIG)
                showWakeLineDialog = false
                scope.launch {
                    snackbarHostState.showSnackbar("Wake line updated to \"$cleaned\"")
                }
            },
            onDismiss = { showWakeLineDialog = false }
        )
    }

    if (showPermissionsDialog) {
        PermissionsDashboardDialog(
            micPermissionGranted = micPermGranted,
            accessibilityConnected = accessibilityConnected,
            overlayPermissionGranted = overlayPermGranted,
            batteryOptimizationIgnored = batteryUnrestricted,
            onGrantMicPermission = {
                permissionsLauncher.launch(buildRequiredPermissionsArray())
            },
            onOpenAccessibilitySettings = {
                try {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(context, "Open Settings -> Accessibility -> JARVIS", Toast.LENGTH_LONG).show()
                }
            },
            onOpenOverlaySettings = {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(context, "Allow 'Appear on top' for JARVIS", Toast.LENGTH_LONG).show()
                }
            },
            onRequestIgnoreBatteryOptimizations = {
                requestIgnoreBatteryOptimizations(context)
            },
            onUseDefaultDownloadGguf = {
                val defaultPath = "/storage/emulated/0/Download/${JarvisPreferences.DEFAULT_GGUF_FILENAME}"
                prefs.setGgufModelPath(defaultPath, JarvisPreferences.DEFAULT_GGUF_FILENAME)
                JarvisService.sendAction(context, JarvisService.ACTION_RELOAD_CONFIG)
                showPermissionsDialog = false
                scope.launch {
                    snackbarHostState.showSnackbar("Linked /Download/${JarvisPreferences.DEFAULT_GGUF_FILENAME}")
                }
            },
            onDismiss = { showPermissionsDialog = false }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            JarvisDrawerContent(
                voskReady = snapshot.voskReady,
                voskDownloadProgress = snapshot.voskDownloadProgress,
                voskStatusText = snapshot.voskStatusText,
                whisperReady = snapshot.whisperReady,
                whisperDownloadProgress = snapshot.whisperDownloadProgress,
                whisperStatusText = snapshot.whisperStatusText,
                ggufDisplayName = snapshot.ggufDisplayName,
                ggufFileExists = snapshot.ggufFileExists,
                activeWakeLine = snapshot.wakeLine,
                selectedTimeoutSec = snapshot.inactivityTimeoutSec,
                sharingInProgress = sharingApkInProgress,
                onDownloadVoskClick = {
                    scope.launch {
                        val res = VoskModelManager.downloadAndInstallVoskModel(context) { pct, status ->
                            JarvisStateBus.updateState {
                                it.copy(voskDownloadProgress = pct, voskStatusText = status)
                            }
                        }
                        JarvisStateBus.updateState { it.copy(voskDownloadProgress = null) }
                        res.onSuccess {
                            JarvisStateBus.appendLog("VOSK", "Downloaded 36 MB Vosk voice pack")
                            JarvisService.sendAction(context, JarvisService.ACTION_RELOAD_VOSK)
                            snackbarHostState.showSnackbar("Voice pack ready")
                        }.onFailure { err ->
                            JarvisStateBus.appendLog("ERROR", "Vosk download error: ${err.localizedMessage}")
                            snackbarHostState.showSnackbar("Download failed: ${err.localizedMessage}")
                        }
                    }
                },
                onPickLocalVoskZipClick = {
                    voskZipPickerLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                },
                onDownloadWhisperClick = {
                    scope.launch {
                        val res = WhisperModelManager.downloadWhisperModel(context) { pct, status ->
                            JarvisStateBus.updateState {
                                it.copy(whisperDownloadProgress = pct, whisperStatusText = status)
                            }
                        }
                        JarvisStateBus.updateState { it.copy(whisperDownloadProgress = null) }
                        res.onSuccess { file ->
                            JarvisStateBus.updateState {
                                it.copy(
                                    whisperReady = true,
                                    whisperStatusText = "Installed • ggml-tiny.bin (${file.length() / (1024 * 1024)} MB)"
                                )
                            }
                            JarvisStateBus.appendLog(
                                "WHISPER",
                                "Downloaded 75 MB Whisper Ear into filesDir/models/ggml-tiny.bin"
                            )
                            snackbarHostState.showSnackbar("Whisper Ear ready (ggml-tiny.bin)")
                        }.onFailure { err ->
                            JarvisStateBus.appendLog("ERROR", "Whisper download error: ${err.localizedMessage}")
                            snackbarHostState.showSnackbar("Whisper download failed: ${err.localizedMessage}")
                        }
                    }
                },
                onPickLocalWhisperBinClick = {
                    whisperBinPickerLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                },
                onSelectGgufClick = {
                    ggufFilePickerLauncher.launch(arrayOf("*/*"))
                },
                onOpenWakeLineDialog = {
                    wakeLineDraft = snapshot.wakeLine
                    showWakeLineDialog = true
                },
                onSelectTimeoutSec = { newSec ->
                    prefs.setInactivityTimeoutSec(newSec)
                    JarvisService.sendAction(context, JarvisService.ACTION_RELOAD_CONFIG)
                    scope.launch {
                        snackbarHostState.showSnackbar("Auto-sleep timer set to ${newSec}s")
                    }
                },
                onOpenPermissionsDialog = {
                    showPermissionsDialog = true
                },
                onShareApkClick = {
                    scope.launch {
                        sharingApkInProgress = true
                        val res = ApkSelfSharer.shareInstalledApk(context)
                        sharingApkInProgress = false
                        res.onFailure { err ->
                            Toast.makeText(
                                context,
                                "APK Share Error: ${err.localizedMessage}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            )
        }
    ) {
        Scaffold(
            containerColor = AmoledBlack,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                MinimalistTransparentTopBar(
                    ramMb = snapshot.appRamMb,
                    state = snapshot.state,
                    remainingSeconds = snapshot.remainingWakeSeconds,
                    onOpenDrawer = {
                        scope.launch { drawerState.open() }
                    }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AmoledBlack)
                    .padding(innerPadding)
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Upper 70%: Dead-Center Hero Ring with generous negative space
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.70f),
                        contentAlignment = Alignment.Center
                    ) {
                        MinimalistCenterHeroRing(
                            snapshot = snapshot,
                            onToggleWakeSleep = {
                                val isAwake = snapshot.state != JarvisOrchestratorState.SLEEPING
                                if (isAwake) {
                                    JarvisService.sendAction(context, JarvisService.ACTION_MANUAL_SLEEP)
                                } else {
                                    JarvisService.sendAction(context, JarvisService.ACTION_MANUAL_WAKE)
                                }
                            }
                        )
                    }

                    // Bottom 30%: Sleek, borderless monospace terminal fading into #050505 background
                    FloatingBottomTerminal(
                        snapshot = snapshot,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.30f)
                    )
                }

                // Guaranteed In-App Bottom-Right Siri Floating Pill (appears whenever JARVIS is awake)
                BottomRightSiriPill(
                    snapshot = snapshot,
                    onCloseToSleep = {
                        JarvisService.sendAction(context, JarvisService.ACTION_MANUAL_SLEEP)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 24.dp)
                )
            }
        }
    }
}

@Composable
private fun MinimalistTransparentTopBar(
    ramMb: Int,
    state: JarvisOrchestratorState,
    remainingSeconds: Int,
    onOpenDrawer: () -> Unit
) {
    val isAwake = state != JarvisOrchestratorState.SLEEPING

    val statusLabel = when (state) {
        JarvisOrchestratorState.SLEEPING -> "[ $ramMb MB • SLEEPING ]"
        JarvisOrchestratorState.RECORDING -> "[ $ramMb MB • RECORDING ${remainingSeconds}s ]"
        JarvisOrchestratorState.TRANSCRIBING -> "[ $ramMb MB • TRANSCRIBING ]"
        JarvisOrchestratorState.WAKING_BRAIN -> "[ $ramMb MB • WAKING ]"
        JarvisOrchestratorState.ONLINE -> "[ $ramMb MB • AWAKE ${remainingSeconds}s ]"
        JarvisOrchestratorState.EXECUTING -> "[ $ramMb MB • EXECUTING ]"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Far Left: Minimal Hamburger Menu Icon (☰)
        IconButton(
            onClick = onOpenDrawer,
            modifier = Modifier
                .size(48.dp)
                .testTag("hamburger_menu_button")
        ) {
            Icon(
                imageVector = Icons.Default.Menu,
                contentDescription = "Open Navigation Drawer",
                tint = TextPrimary
            )
        }

        // Far Right: Tiny pill-shaped RAM/Status indicator in muted text, turning Cyan when AWAKE
        Text(
            text = statusLabel,
            color = if (isAwake) JarvisCyan else MutedTextGray,
            fontSize = 11.sp,
            fontWeight = if (isAwake) FontWeight.Bold else FontWeight.Light,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.6.sp,
            modifier = Modifier
                .padding(end = 8.dp)
                .testTag("ram_state_badge")
        )
    }
}

private fun buildRequiredPermissionsArray(): Array<String> {
    val list = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        list.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        list.add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    return list.toTypedArray()
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

@Suppress("BatteryLife")
private fun requestIgnoreBatteryOptimizations(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        } catch (_: Exception) {
        }
    }
}

private fun resolveOrCopyGgufUri(context: Context, uri: Uri): Pair<File, String>? {
    return try {
        var displayName = JarvisPreferences.DEFAULT_GGUF_FILENAME
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIdx >= 0 && cursor.moveToFirst()) {
                val foundName = cursor.getString(nameIdx)
                if (!foundName.isNullOrBlank()) {
                    displayName = foundName
                }
            }
        }

        val directDownloadCandidate = File("/storage/emulated/0/Download/$displayName")
        if (directDownloadCandidate.exists() && directDownloadCandidate.canRead()) {
            return directDownloadCandidate to displayName
        }

        val modelsDir = File(context.filesDir, "models")
        if (!modelsDir.exists()) modelsDir.mkdirs()
        val destFile = File(modelsDir, displayName)

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                val buffer = ByteArray(64 * 1024)
                var len: Int
                while (input.read(buffer).also { len = it } != -1) {
                    output.write(buffer, 0, len)
                }
                output.flush()
            }
        } ?: return null

        destFile to displayName
    } catch (_: Exception) {
        null
    }
}
