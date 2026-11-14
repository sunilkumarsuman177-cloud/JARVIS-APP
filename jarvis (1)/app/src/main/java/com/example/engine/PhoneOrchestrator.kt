package com.example.engine

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.service.JarvisAccessibilityService
import com.example.service.JarvisStateBus
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

data class OrchestrationOutcome(
    val action: String,
    val success: Boolean,
    val summary: String,
    val spokenFeedback: String
)

class PhoneOrchestrator(private val context: Context) {
    private val appContext = context.applicationContext

    init {
        refreshInstalledAppsCatalog()
    }

    fun refreshInstalledAppsCatalog() {
        try {
            val pm = appContext.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            val labels = resolveInfos.mapNotNull { info ->
                info.loadLabel(pm)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            }
            LlamaBridge.updateInstalledAppsCatalog(labels)
        } catch (_: Throwable) {
        }
    }

    /**
     * Multi-Step Task Execution Engine:
     * Accepts EITHER a single JSON object `{...}` OR a JSON array `[{"action":...}, {"action":...}]`
     * and executes each step sequentially with smart delays.
     */
    suspend fun executeJsonCommand(
        rawJson: String,
        onStatusUpdate: (String) -> Unit = {},
        onSpeak: (String) -> Unit
    ): OrchestrationOutcome {
        val trimmed = rawJson.trim()
        return try {
            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                var lastOutcome = OrchestrationOutcome("noop", true, "No steps", "")
                val summaries = mutableListOf<String>()

                for (i in 0 until array.length()) {
                    val stepObj = array.optJSONObject(i) ?: continue
                    val stepOutcome = executeSingleJsonObject(
                        json = stepObj,
                        onStatusUpdate = onStatusUpdate,
                        onSpeak = if (i == 0) onSpeak else { _ -> }
                    )
                    lastOutcome = stepOutcome
                    summaries.add(stepOutcome.summary)
                    if (i < array.length() - 1) {
                        delay(1200L)
                    }
                }
                val combinedSummary = summaries.joinToString(" -> ").ifEmpty { lastOutcome.summary }
                lastOutcome.copy(summary = combinedSummary)
            } else {
                val json = JSONObject(trimmed)
                executeSingleJsonObject(json, onStatusUpdate, onSpeak)
            }
        } catch (e: Exception) {
            val msg = "JSON parse/exec error: ${e.localizedMessage ?: "invalid JSON"}"
            JarvisStateBus.appendLog("ERROR", msg)
            OrchestrationOutcome("error", false, msg, "Not understood")
        }
    }

    private suspend fun executeSingleJsonObject(
        json: JSONObject,
        onStatusUpdate: (String) -> Unit,
        onSpeak: (String) -> Unit
    ): OrchestrationOutcome {
        val action = json.optString("action", "").trim().lowercase(Locale.US)

        if (action == "noop") {
            return OrchestrationOutcome(
                action = "noop",
                success = true,
                summary = "Ignored background noise/filler",
                spokenFeedback = ""
            )
        }

        val outcome = when (action) {
            "youtube_play" -> handleYoutubePlay(
                query = json.optString("query", ""),
                onStatusUpdate = onStatusUpdate,
                onSpeak = onSpeak
            )
            "whatsapp_send" -> handleWhatsappSend(
                contact = json.optString("contact", ""),
                message = json.optString("message", ""),
                onStatusUpdate = onStatusUpdate,
                onSpeak = onSpeak
            )
            "wifi" -> {
                val state = json.optString("state", "on")
                onStatusUpdate("Wi-Fi ${state.uppercase(Locale.US)}...")
                handleWifi(state)
            }
            "bluetooth" -> {
                val state = json.optString("state", "on")
                onStatusUpdate("Bluetooth ${state.uppercase(Locale.US)}...")
                handleBluetooth(state)
            }
            "flashlight" -> {
                val state = json.optString("state", "on")
                onStatusUpdate("Flashlight ${state.uppercase(Locale.US)}...")
                handleFlashlight(state)
            }
            "dnd" -> {
                val state = json.optString("state", "on")
                onStatusUpdate("DND ${state.uppercase(Locale.US)}...")
                handleDnd(state)
            }
            "volume" -> {
                val level = json.optInt("level", 50)
                onStatusUpdate("Volume $level%...")
                handleVolume(level)
            }
            "open_app" -> {
                val query = json.optString("query", "")
                onStatusUpdate("Opening $query...")
                handleOpenApp(query)
            }
            "call" -> {
                val name = json.optString("name", "")
                onStatusUpdate("Calling $name...")
                handleCall(name)
            }
            "alarm" -> {
                val mins = json.optInt("minutes", 30)
                onStatusUpdate("Setting alarm (+${mins}m)...")
                handleAlarm(mins)
            }
            "ui_click" -> {
                val text = json.optString("text", "")
                onStatusUpdate("Clicking $text...")
                handleUiClick(text)
            }
            "ui_type" -> {
                val text = json.optString("text", "")
                onStatusUpdate("Typing \"$text\"...")
                handleUiType(text)
            }
            "nav" -> {
                val target = json.optString("target", "home")
                onStatusUpdate("Navigating $target...")
                handleNav(target)
            }
            "battery" -> {
                onStatusUpdate("Checking battery...")
                handleBattery()
            }
            "speak" -> {
                val reply = json.optString("text", "Not understood")
                onStatusUpdate(reply)
                OrchestrationOutcome(
                    action = "speak",
                    success = true,
                    summary = "Spoke: \"$reply\"",
                    spokenFeedback = reply
                )
            }
            else -> {
                onStatusUpdate("Not understood")
                OrchestrationOutcome(
                    action = action.ifEmpty { "unknown" },
                    success = false,
                    summary = "Not understood ($action)",
                    spokenFeedback = "Not understood"
                )
            }
        }

        // For youtube_play and whatsapp_send, speech is triggered before the multi-step delay
        if (action != "youtube_play" && action != "whatsapp_send" && outcome.spokenFeedback.isNotBlank()) {
            onSpeak(outcome.spokenFeedback)
        }
        JarvisStateBus.appendLog("EXEC", "${outcome.summary} [${if (outcome.success) "OK" else "INFO"}]")
        return outcome
    }

    /**
     * Multi-step YouTube Search & Auto-Play First Result:
     * 1. Launches YouTube search query
     * 2. Waits 2800ms for search results to load
     * 3. Uses JarvisAccessibilityService to tap the first video result
     */
    private suspend fun handleYoutubePlay(
        query: String,
        onStatusUpdate: (String) -> Unit,
        onSpeak: (String) -> Unit
    ): OrchestrationOutcome {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) {
            return handleOpenApp("youtube")
        }

        onStatusUpdate("Playing $cleanQuery...")
        onSpeak("Playing $cleanQuery")

        var launched = false
        try {
            val ytSearchIntent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra("query", cleanQuery)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            appContext.startActivity(ytSearchIntent)
            launched = true
        } catch (_: Exception) {
            try {
                val webIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(cleanQuery)}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(webIntent)
                launched = true
            } catch (_: Exception) {
            }
        }

        if (!launched) {
            return OrchestrationOutcome(
                action = "youtube_play",
                success = false,
                summary = "Could not launch YouTube for '$cleanQuery'",
                spokenFeedback = ""
            )
        }

        // Wait 2500ms for YouTube results to render, then auto-click the first video via Accessibility Service
        delay(2500L)
        val a11y = JarvisAccessibilityService.instance
        var clickedFirst = false
        if (a11y != null) {
            clickedFirst = a11y.clickFirstYoutubeVideoResult(cleanQuery)
            if (!clickedFirst) {
                delay(1000L)
                clickedFirst = a11y.clickFirstYoutubeVideoResult(cleanQuery)
            }
        }

        val detail = if (clickedFirst) {
            "Searched YouTube & auto-played first result for \"$cleanQuery\""
        } else if (a11y == null) {
            "Opened YouTube search for \"$cleanQuery\" (Enable Accessibility to auto-tap first video)"
        } else {
            "Opened YouTube search results for \"$cleanQuery\""
        }

        return OrchestrationOutcome(
            action = "youtube_play",
            success = true,
            summary = detail,
            spokenFeedback = ""
        )
    }

    /**
     * Multi-step WhatsApp Direct Message:
     * 1. Looks up contact phone number in ContactsContract
     * 2. Opens WhatsApp chat pre-filled with message via api.whatsapp.com/send
     * 3. Waits 1500ms and taps the WhatsApp Send button via JarvisAccessibilityService
     */
    private suspend fun handleWhatsappSend(
        contact: String,
        message: String,
        onStatusUpdate: (String) -> Unit,
        onSpeak: (String) -> Unit
    ): OrchestrationOutcome {
        val cleanContact = contact.trim()
        val cleanMessage = message.trim()
        if (cleanContact.isEmpty()) {
            return handleOpenApp("whatsapp")
        }

        onStatusUpdate("Messaging $cleanContact...")
        onSpeak("Messaging $cleanContact")

        val rawPhone = resolveContactPhoneNumber(cleanContact)
        val digitsOnly = rawPhone?.filter { it.isDigit() }.orEmpty()
        val formattedPhone = when {
            digitsOnly.length == 10 -> "91$digitsOnly"
            digitsOnly.length > 10 -> digitsOnly
            else -> ""
        }

        if (formattedPhone.isNotEmpty()) {
            try {
                val uri = Uri.parse(
                    "https://api.whatsapp.com/send?phone=$formattedPhone&text=${Uri.encode(cleanMessage)}"
                )
                val waIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.whatsapp")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    appContext.startActivity(waIntent)
                } catch (_: Exception) {
                    val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    appContext.startActivity(fallbackIntent)
                }

                delay(1500L)
                val sent = JarvisAccessibilityService.instance?.clickWhatsAppSendButton() == true
                return OrchestrationOutcome(
                    action = "whatsapp_send",
                    success = true,
                    summary = if (sent) {
                        "Sent WhatsApp message to $cleanContact: \"$cleanMessage\""
                    } else {
                        "Opened WhatsApp chat with $cleanContact (\"$cleanMessage\")"
                    },
                    spokenFeedback = ""
                )
            } catch (_: Exception) {
            }
        }

        // Fallback if contact number isn't resolved: Open WhatsApp and automate via Accessibility
        val openOutcome = handleOpenApp("whatsapp")
        if (openOutcome.success) {
            delay(1600L)
            val a11y = JarvisAccessibilityService.instance
            if (a11y != null) {
                val foundContact = a11y.clickByText(cleanContact)
                if (foundContact && cleanMessage.isNotEmpty()) {
                    delay(900L)
                    a11y.inputTextIntoEditableField(cleanMessage)
                    delay(500L)
                    a11y.clickWhatsAppSendButton()
                    return OrchestrationOutcome(
                        action = "whatsapp_send",
                        success = true,
                        summary = "Messaged $cleanContact on WhatsApp: \"$cleanMessage\"",
                        spokenFeedback = ""
                    )
                }
            }
        }

        return OrchestrationOutcome(
            action = "whatsapp_send",
            success = openOutcome.success,
            summary = "Opened WhatsApp for $cleanContact (Grant Contacts & Accessibility for auto-send)",
            spokenFeedback = ""
        )
    }

    private fun handleBattery(): OrchestrationOutcome {
        return try {
            val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 1..100 } ?: 100
            OrchestrationOutcome(
                action = "battery",
                success = true,
                summary = "Device battery level: $pct%",
                spokenFeedback = "Battery is at $pct percent."
            )
        } catch (_: Exception) {
            OrchestrationOutcome(
                action = "battery",
                success = true,
                summary = "Battery telemetry checked",
                spokenFeedback = "Battery systems nominal."
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun handleWifi(state: String): OrchestrationOutcome {
        val enable = state.equals("on", ignoreCase = true)
        return try {
            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && wifiManager != null) {
                wifiManager.isWifiEnabled = enable
                OrchestrationOutcome(
                    action = "wifi",
                    success = true,
                    summary = "Wi-Fi turned ${if (enable) "ON" else "OFF"}",
                    spokenFeedback = "Wi-Fi ${if (enable) "on" else "off"}."
                )
            } else {
                val panelIntent = Intent(Settings.Panel.ACTION_WIFI).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(panelIntent)
                OrchestrationOutcome(
                    action = "wifi",
                    success = true,
                    summary = "Opened Wi-Fi Quick Panel (${if (enable) "ON" else "OFF"})",
                    spokenFeedback = "Opening Wi-Fi."
                )
            }
        } catch (_: Exception) {
            val settingsIntent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(settingsIntent)
            OrchestrationOutcome("wifi", true, "Opened Wi-Fi Settings", "Opening Wi-Fi.")
        }
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun handleBluetooth(state: String): OrchestrationOutcome {
        val enable = state.equals("on", ignoreCase = true)
        return try {
            val btManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter: BluetoothAdapter? = btManager?.adapter
            val hasBtPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

            if (adapter != null && hasBtPerm && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val changed = if (enable) adapter.enable() else adapter.disable()
                if (changed) {
                    return OrchestrationOutcome(
                        action = "bluetooth",
                        success = true,
                        summary = "Bluetooth turned ${if (enable) "ON" else "OFF"}",
                        spokenFeedback = "Bluetooth ${if (enable) "on" else "off"}."
                    )
                }
            }

            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            OrchestrationOutcome(
                action = "bluetooth",
                success = true,
                summary = "Opened Bluetooth Settings (${if (enable) "ON" else "OFF"})",
                spokenFeedback = "Opening Bluetooth."
            )
        } catch (_: Exception) {
            OrchestrationOutcome("bluetooth", false, "Bluetooth toggle unavailable", "Not understood")
        }
    }

    private fun handleFlashlight(state: String): OrchestrationOutcome {
        val enable = state.equals("on", ignoreCase = true)
        return try {
            val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return OrchestrationOutcome("flashlight", false, "CameraManager unavailable", "Torch unavailable.")

            val torchCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: cameraManager.cameraIdList.firstOrNull()

            if (torchCameraId != null) {
                cameraManager.setTorchMode(torchCameraId, enable)
                OrchestrationOutcome(
                    action = "flashlight",
                    success = true,
                    summary = "Rear Camera Torch turned ${if (enable) "ON" else "OFF"}",
                    spokenFeedback = "Flashlight ${if (enable) "on" else "off"}."
                )
            } else {
                OrchestrationOutcome("flashlight", false, "No flash unit detected", "No flashlight found.")
            }
        } catch (e: Exception) {
            OrchestrationOutcome(
                action = "flashlight",
                success = false,
                summary = "Torch error: ${e.localizedMessage ?: "hardware unavailable"}",
                spokenFeedback = "Flashlight unavailable."
            )
        }
    }

    private fun handleDnd(state: String): OrchestrationOutcome {
        val enable = state.equals("on", ignoreCase = true)
        return try {
            val notificationManager =
                appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

            if (notificationManager != null && notificationManager.isNotificationPolicyAccessGranted) {
                notificationManager.setInterruptionFilter(
                    if (enable) {
                        NotificationManager.INTERRUPTION_FILTER_NONE
                    } else {
                        NotificationManager.INTERRUPTION_FILTER_ALL
                    }
                )
                audioManager?.ringerMode = if (enable) {
                    AudioManager.RINGER_MODE_SILENT
                } else {
                    AudioManager.RINGER_MODE_NORMAL
                }
                OrchestrationOutcome(
                    action = "dnd",
                    success = true,
                    summary = "Do Not Disturb turned ${if (enable) "ON" else "OFF"}",
                    spokenFeedback = "Silent mode ${if (enable) "on" else "off"}."
                )
            } else {
                audioManager?.ringerMode = if (enable) {
                    AudioManager.RINGER_MODE_VIBRATE
                } else {
                    AudioManager.RINGER_MODE_NORMAL
                }
                OrchestrationOutcome(
                    action = "dnd",
                    success = true,
                    summary = "Ringer switched to ${if (enable) "VIBRATE / SILENT" else "NORMAL"}",
                    spokenFeedback = "Ringer ${if (enable) "vibrate" else "normal"}."
                )
            }
        } catch (_: Exception) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            OrchestrationOutcome("dnd", true, "Opened DND Policy Settings", "Opening Do Not Disturb.")
        }
    }

    private fun handleVolume(level: Int): OrchestrationOutcome {
        val clamped = level.coerceIn(0, 100)
        return try {
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return OrchestrationOutcome("volume", false, "AudioManager unavailable", "")

            val maxMedia = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val targetMedia = ((clamped / 100.0) * maxMedia).toInt().coerceIn(0, maxMedia)
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                targetMedia,
                AudioManager.FLAG_SHOW_UI
            )

            OrchestrationOutcome(
                action = "volume",
                success = true,
                summary = "Media volume set to $clamped% ($targetMedia/$maxMedia)",
                spokenFeedback = "Volume $clamped percent."
            )
        } catch (e: Exception) {
            OrchestrationOutcome("volume", false, "Volume error: ${e.message}", "")
        }
    }

    private fun handleOpenApp(query: String): OrchestrationOutcome {
        val cleanQuery = query.trim().lowercase(Locale.US)
        if (cleanQuery.isEmpty()) {
            return OrchestrationOutcome("open_app", false, "Empty app query", "Not understood")
        }

        when (cleanQuery) {
            "settings", "setting" -> {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(intent)
                return OrchestrationOutcome("open_app", true, "Launched Android Settings", "Opening Settings.")
            }
        }

        return try {
            val pm = appContext.packageManager

            // Direct known package fast-paths for instant launch on Galaxy A10
            val knownPackages = mapOf(
                "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
                "youtube" to listOf("com.google.android.youtube"),
                "chrome" to listOf("com.android.chrome"),
                "instagram" to listOf("com.instagram.android"),
                "gmail" to listOf("com.google.android.gm"),
                "maps" to listOf("com.google.android.apps.maps"),
                "play store" to listOf("com.android.vending"),
                "spotify" to listOf("com.spotify.music")
            )

            knownPackages[cleanQuery]?.forEach { pkg ->
                val directLaunch = pm.getLaunchIntentForPackage(pkg)
                if (directLaunch != null) {
                    directLaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(directLaunch)
                    val title = cleanQuery.replaceFirstChar { it.titlecase(Locale.US) }
                    return OrchestrationOutcome(
                        action = "open_app",
                        success = true,
                        summary = "Launched $title ($pkg)",
                        spokenFeedback = "Opening $title."
                    )
                }
            }

            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)

            var bestPackage: String? = null
            var bestLabel: String? = null
            var bestScore = 0

            for (info in resolveInfos) {
                val pkg = info.activityInfo.packageName.lowercase(Locale.US)
                val label = info.loadLabel(pm).toString().lowercase(Locale.US)

                val score = when {
                    label == cleanQuery -> 100
                    label.startsWith(cleanQuery) -> 85
                    label.contains(cleanQuery) -> 70
                    cleanQuery.contains(label) && label.length >= 3 -> 65
                    pkg.contains(cleanQuery) -> 55
                    else -> 0
                }
                if (score > bestScore) {
                    bestScore = score
                    bestPackage = info.activityInfo.packageName
                    bestLabel = info.loadLabel(pm).toString()
                }
            }

            if (bestPackage != null) {
                val launchIntent = pm.getLaunchIntentForPackage(bestPackage)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(launchIntent)
                    return OrchestrationOutcome(
                        action = "open_app",
                        success = true,
                        summary = "Launched $bestLabel ($bestPackage)",
                        spokenFeedback = "Opening $bestLabel."
                    )
                }
            }

            if (cleanQuery.contains("camera")) {
                val camIntent = Intent("android.media.action.IMAGE_CAPTURE").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(camIntent)
                return OrchestrationOutcome("open_app", true, "Launched Camera", "Opening Camera.")
            }

            if (cleanQuery.contains("youtube")) {
                val webYt = Intent(Intent.ACTION_VIEW, Uri.parse("https://m.youtube.com")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(webYt)
                return OrchestrationOutcome("open_app", true, "Opened YouTube", "Opening YouTube.")
            }

            OrchestrationOutcome(
                action = "open_app",
                success = false,
                summary = "App '$query' not installed on device",
                spokenFeedback = "Not understood"
            )
        } catch (_: Exception) {
            OrchestrationOutcome("open_app", false, "Failed to launch '$query'", "Not understood")
        }
    }

    private fun handleCall(nameOrNumber: String): OrchestrationOutcome {
        val target = nameOrNumber.trim()
        if (target.isEmpty()) {
            return OrchestrationOutcome("call", false, "Missing contact name", "Not understood")
        }

        val resolvedNumber = resolveContactPhoneNumber(target) ?: target.filter { it.isDigit() || it == '+' }
        val hasDirectCallPerm = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        return try {
            val uri = Uri.parse("tel:${Uri.encode(resolvedNumber.ifEmpty { target })}")
            val intent = Intent(
                if (hasDirectCallPerm && resolvedNumber.isNotEmpty()) Intent.ACTION_CALL else Intent.ACTION_DIAL,
                uri
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            OrchestrationOutcome(
                action = "call",
                success = true,
                summary = "${if (hasDirectCallPerm) "Calling" else "Dialing"} $target (${resolvedNumber.ifEmpty { "dialer" }})",
                spokenFeedback = "Calling $target."
            )
        } catch (_: Exception) {
            OrchestrationOutcome("call", false, "Could not place call to $target", "Not understood")
        }
    }

    private fun resolveContactPhoneNumber(contactQuery: String): String? {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$contactQuery%")

            appContext.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (numberIdx >= 0) {
                        return cursor.getString(numberIdx)
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun handleAlarm(minutesFromNow: Int): OrchestrationOutcome {
        val validMinutes = minutesFromNow.coerceAtLeast(1)
        return try {
            val calendar = Calendar.getInstance().apply {
                add(Calendar.MINUTE, validMinutes)
            }
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val minute = calendar.get(Calendar.MINUTE)

            val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, "JARVIS Alarm ($validMinutes min)")
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(alarmIntent)
            val formattedTime = String.format(Locale.US, "%02d:%02d", hour, minute)
            OrchestrationOutcome(
                action = "alarm",
                success = true,
                summary = "System alarm set for $formattedTime (+$validMinutes min)",
                spokenFeedback = "Alarm set for $validMinutes minutes."
            )
        } catch (_: Exception) {
            OrchestrationOutcome("alarm", false, "No Clock app handled SET_ALARM intent", "Not understood")
        }
    }

    private fun handleUiClick(buttonText: String): OrchestrationOutcome {
        val a11y = JarvisAccessibilityService.instance
        if (a11y == null) {
            return OrchestrationOutcome(
                action = "ui_click",
                success = false,
                summary = "Accessibility Service not enabled for clicking '$buttonText'",
                spokenFeedback = "Enable Accessibility Service."
            )
        }
        val clicked = a11y.clickByText(buttonText)
        return if (clicked) {
            OrchestrationOutcome(
                action = "ui_click",
                success = true,
                summary = "Clicked screen element matching \"$buttonText\"",
                spokenFeedback = "Clicked $buttonText."
            )
        } else {
            OrchestrationOutcome(
                action = "ui_click",
                success = false,
                summary = "No screen element matched \"$buttonText\"",
                spokenFeedback = "Not understood"
            )
        }
    }

    private fun handleUiType(textToType: String): OrchestrationOutcome {
        val a11y = JarvisAccessibilityService.instance
            ?: return OrchestrationOutcome("ui_type", false, "Accessibility Service not enabled", "Enable Accessibility Service.")
        val ok = a11y.inputTextIntoEditableField(textToType)
        return OrchestrationOutcome(
            action = "ui_type",
            success = ok,
            summary = if (ok) "Typed \"$textToType\" into input field" else "Could not find input field",
            spokenFeedback = ""
        )
    }

    private fun handleNav(target: String): OrchestrationOutcome {
        val a11y = JarvisAccessibilityService.instance
        if (a11y == null) {
            return OrchestrationOutcome(
                action = "nav",
                success = false,
                summary = "Accessibility Service not enabled for nav '$target'",
                spokenFeedback = "Enable Accessibility Service."
            )
        }
        val ok = a11y.executeNavigation(target)
        return OrchestrationOutcome(
            action = "nav",
            success = ok,
            summary = "Executed global navigation: $target",
            spokenFeedback = ""
        )
    }
}
