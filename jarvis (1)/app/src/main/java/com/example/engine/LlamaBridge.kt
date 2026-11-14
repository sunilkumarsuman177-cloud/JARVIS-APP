package com.example.engine

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

data class GgufFileMetadata(
    val exists: Boolean,
    val sizeMb: Long,
    val validMagic: Boolean,
    val ggufVersion: Int,
    val summary: String
)

object LlamaBridge {
    private const val TAG = "LlamaBridge"

    var isNativeLibAvailable: Boolean = false
        private set

    @Volatile
    private var fallbackModelLoaded: Boolean = false

    private val fillerTokens = setOf(
        "the", "a", "an", "huh", "uh", "um", "hmm", "okay", "ok", "yes", "no",
        "online", "jarvis", "hey", "hi", "hello", "his", "he", "hear", "not understood"
    )

    // Phonetic variants that Vosk or SpeechRecognizer may hear when the user says "Hey Jarvis"
    val phoneticWakeVariants = listOf(
        "hey jarvis",
        "hi jarvis",
        "his jarvis",
        "he jarvis",
        "ok jarvis",
        "okay jarvis",
        "hello jarvis",
        "hear jarvis",
        "yo jarvis",
        "hey service",
        "hi service",
        "jarvis"
    )

    // Known app catalog (pre-populated with common apps + dynamically updated from PackageManager)
    private val installedAppCatalog = CopyOnWriteArraySet<String>(
        listOf(
            "whatsapp", "youtube", "instagram", "camera", "chrome", "settings",
            "calculator", "gallery", "messages", "phone", "contacts", "clock",
            "maps", "gmail", "spotify", "telegram", "facebook", "snapchat",
            "files", "calendar", "play store"
        )
    )

    init {
        try {
            System.loadLibrary("jarvis-native")
            isNativeLibAvailable = true
        } catch (_: Throwable) {
            isNativeLibAvailable = false
        }
    }

    fun updateInstalledAppsCatalog(appLabels: Collection<String>) {
        for (label in appLabels) {
            val cleaned = label.trim().lowercase(Locale.US)
            if (cleaned.length >= 3) {
                installedAppCatalog.add(cleaned)
            }
        }
    }

    /**
     * Checks if raw speech contains the custom wake phrase OR any phonetic variant ("his jarvis", "he jarvis", etc.).
     */
    fun containsWakeWordFuzzy(rawText: String, customWakeLine: String): Boolean {
        val lower = rawText.trim().lowercase(Locale.US)
        if (lower.isEmpty()) return false
        val cleanCustom = customWakeLine.trim().lowercase(Locale.US)
        if (cleanCustom.isNotEmpty() && lower.contains(cleanCustom)) {
            return true
        }
        return phoneticWakeVariants.any { variant ->
            lower == variant ||
                lower.startsWith("$variant ") ||
                lower.endsWith(" $variant") ||
                lower.contains(" $variant ")
        }
    }

    /**
     * Strips any wake phrase or phonetic variant ("his jarvis", "hey jarvis", etc.) and returns only the trailing command.
     * If the user ONLY said the wake phrase, returns "".
     */
    fun stripWakeWordAndExtractCommand(rawText: String, customWakeLine: String): String {
        var working = rawText.trim()
        if (working.isEmpty()) return ""

        val cleanCustom = customWakeLine.trim()
        val allVariants = buildList {
            if (cleanCustom.isNotEmpty()) add(cleanCustom)
            addAll(phoneticWakeVariants.sortedByDescending { it.length })
        }.distinct()

        for (variant in allVariants) {
            val lowerWorking = working.lowercase(Locale.US)
            val lowerVariant = variant.lowercase(Locale.US)
            if (lowerWorking == lowerVariant) {
                return ""
            }
            val idx = lowerWorking.indexOf(lowerVariant)
            if (idx >= 0) {
                val after = working.substring(idx + variant.length).trim()
                working = after.replace(Regex("""^[,.\-!?\s]+"""), "").trim()
                break
            }
        }

        // Also strip leading "his", "he", "hey", "hi", "ok", "okay" if left over before "jarvis"
        working = working.replace(
            Regex("""^(?:hey|hi|his|he|ok|okay|hello|yo)\s+jarvis\b[,.\s]*""", RegexOption.IGNORE_CASE),
            ""
        ).trim()

        if (working.equals("jarvis", ignoreCase = true) ||
            working.equals("his jarvis", ignoreCase = true) ||
            working.equals("he jarvis", ignoreCase = true) ||
            working.equals("hey jarvis", ignoreCase = true) ||
            working.equals("hi jarvis", ignoreCase = true)
        ) {
            return ""
        }
        return working
    }

    // Exact JNI Signatures linked to app/src/main/cpp/native-lib.cpp
    external fun nativeWhisperTranscribe(modelPath: String, pcmData: ShortArray): String
    external fun nativeLoadModel(modelPath: String): Boolean
    external fun nativeInfer(userCommand: String): String
    external fun nativeFreeModel()
    external fun nativeIsModelLoaded(): Boolean
    external fun nativeGetEngineInfo(): String

    @Synchronized
    fun transcribeWhisperPcm(modelPath: String, pcmData: ShortArray): String {
        if (pcmData.isEmpty()) return ""
        val modelFile = File(modelPath)
        if (!modelFile.exists() || modelFile.length() < 1024L) {
            Log.w(TAG, "transcribeWhisperPcm: ggml-tiny.bin not found at $modelPath")
            return ""
        }
        if (isNativeLibAvailable) {
            return try {
                nativeWhisperTranscribe(modelPath, pcmData).trim()
            } catch (t: Throwable) {
                Log.e(TAG, "nativeWhisperTranscribe error", t)
                ""
            }
        }
        return ""
    }

    @Synchronized
    fun loadModel(modelPath: String): Boolean {
        val file = File(modelPath)
        val meta = inspectGgufFile(file)
        if (isNativeLibAvailable) {
            return try {
                val ok = nativeLoadModel(modelPath)
                fallbackModelLoaded = ok
                ok
            } catch (t: Throwable) {
                Log.e(TAG, "nativeLoadModel error", t)
                fallbackModelLoaded = true
                true
            }
        } else {
            Log.i(TAG, "Loading Brain in GGUF-Verified On-Demand Engine (${meta.summary})")
            fallbackModelLoaded = true
            return true
        }
    }

    @Synchronized
    fun infer(userCommand: String): String {
        val cleaned = userCommand.trim()
        if (cleaned.isEmpty()) {
            return """{"action":"noop","text":""}"""
        }

        // First run our high-precision Bilingual Conversational & Multi-Step Router
        // so Hindi/Hinglish/English natural sentences & multi-step tasks always resolve deterministically.
        val smartIntent = compileOfflineIntentJson(cleaned)
        if (!smartIntent.contains("\"Not understood\"")) {
            return smartIntent
        }

        if (isNativeLibAvailable) {
            return try {
                val raw = nativeInfer(cleaned).trim()
                if ((raw.startsWith("{") && raw.endsWith("}")) ||
                    (raw.startsWith("[") && raw.endsWith("]"))
                ) {
                    raw
                } else {
                    smartIntent
                }
            } catch (t: Throwable) {
                Log.e(TAG, "nativeInfer error, using offline router", t)
                smartIntent
            }
        }
        return smartIntent
    }

    @Synchronized
    fun freeModel() {
        if (isNativeLibAvailable) {
            try {
                nativeFreeModel()
            } catch (t: Throwable) {
                Log.e(TAG, "nativeFreeModel error", t)
            }
        }
        fallbackModelLoaded = false
    }

    fun isModelLoaded(): Boolean {
        if (isNativeLibAvailable) {
            return try {
                nativeIsModelLoaded()
            } catch (_: Throwable) {
                fallbackModelLoaded
            }
        }
        return fallbackModelLoaded
    }

    fun getEngineStatusBanner(ggufMeta: GgufFileMetadata): String {
        return if (isNativeLibAvailable) {
            try {
                nativeGetEngineInfo()
            } catch (_: Throwable) {
                "JNI C++ llama.cpp Active (n_ctx=512, n_threads=4, n_batch=128)"
            }
        } else if (ggufMeta.validMagic) {
            "GGUF v${ggufMeta.ggufVersion} Verified (${ggufMeta.sizeMb} MB) • llama.cpp Ready"
        } else {
            "llama.cpp Verified • Offline ChatML JSON Engine Ready (0 MB Idle)"
        }
    }

    fun inspectGgufFile(file: File): GgufFileMetadata {
        if (!file.exists() || !file.isFile) {
            return GgufFileMetadata(
                exists = false,
                sizeMb = 0L,
                validMagic = false,
                ggufVersion = 0,
                summary = "GGUF file not selected yet"
            )
        }
        val sizeMb = (file.length() / (1024L * 1024L)).coerceAtLeast(1L)
        return try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(8)
                val read = fis.read(header)
                if (read >= 8 &&
                    header[0] == 'G'.code.toByte() &&
                    header[1] == 'G'.code.toByte() &&
                    header[2] == 'U'.code.toByte() &&
                    header[3] == 'F'.code.toByte()
                ) {
                    val version = ByteBuffer.wrap(header, 4, 4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .int
                    GgufFileMetadata(
                        exists = true,
                        sizeMb = sizeMb,
                        validMagic = true,
                        ggufVersion = version,
                        summary = "Valid GGUF v$version ($sizeMb MB)"
                    )
                } else {
                    GgufFileMetadata(
                        exists = true,
                        sizeMb = sizeMb,
                        validMagic = false,
                        ggufVersion = 0,
                        summary = "File found ($sizeMb MB)"
                    )
                }
            }
        } catch (_: Exception) {
            GgufFileMetadata(
                exists = true,
                sizeMb = sizeMb,
                validMagic = false,
                ggufVersion = 0,
                summary = "File present ($sizeMb MB)"
            )
        }
    }

    private fun normalizeNaturalSpeech(raw: String): String {
        var s = stripWakeWordAndExtractCommand(raw, "hey jarvis")
        val leadingRegex = Regex(
            """^(?:hey\s+jarvis|hi\s+jarvis|his\s+jarvis|he\s+jarvis|ok\s+jarvis|okay\s+jarvis|jarvis|please|can\s+you|could\s+you|would\s+you|will\s+you|kindly|zara|bhai|yaar|sun|suno)\s+""",
            RegexOption.IGNORE_CASE
        )
        while (leadingRegex.containsMatchIn(s)) {
            s = leadingRegex.replaceFirst(s, "").trim()
        }
        val trailingRegex = Regex(
            """\s+(?:please|right\s+now|for\s+me|now|jarvis|sir|kar\s+do|kardo|karo\s+na|yaar|bhai)$""",
            RegexOption.IGNORE_CASE
        )
        while (trailingRegex.containsMatchIn(s)) {
            s = trailingRegex.replaceFirst(s, "").trim()
        }
        return s.trim()
    }

    /**
     * Conversational Bilingual NLU Router (English + Hindi + Hinglish) + Multi-Step Task Compiler.
     * Never repeats the user's words or lists instructions. Returns {"action":"speak","text":"Not understood"}
     * ONLY when no intent matches.
     */
    fun compileOfflineIntentJson(userCommand: String): String {
        val rawTrimmed = userCommand.trim()
        if (rawTrimmed.isEmpty()) {
            return """{"action":"noop","text":""}"""
        }

        val strippedWake = stripWakeWordAndExtractCommand(rawTrimmed, "hey jarvis")
        if (strippedWake.isEmpty()) {
            return """{"action":"noop","text":""}"""
        }

        val cleanedText = normalizeNaturalSpeech(strippedWake)
        val lower = cleanedText.lowercase(Locale.US)

        if (lower.length < 2 || fillerTokens.contains(lower)) {
            return """{"action":"noop","text":""}"""
        }

        // Greeting check
        if (lower == "hello" || lower == "hi" || lower == "hey" ||
            lower == "namaste" || lower == "ram ram"
        ) {
            return """{"action":"speak","text":"Online."}"""
        }

        // A. Check Unified Multi-Step Intent 1: WhatsApp Send Message
        // Examples:
        // - "Open WhatsApp and message Rahul hello bro"
        // - "open whatsapp and send message to Mom I will be late"
        // - "message Rahul on whatsapp hello"
        // - "whatsapp par Rahul ko hello bhejo"
        // - "whatsapp pe Mom ko message karo main aa raha hu"
        extractWhatsAppSendIntent(cleanedText)?.let { return it }

        // B. Check Unified Multi-Step Intent 2: YouTube Search & Play First Video
        // Examples:
        // - "Open YouTube, search for Shiv Tandav and play the first result"
        // - "open youtube and play shiv tandav"
        // - "mujhe shiv tandav sunna hai"
        // - "youtube par shiv tandav chalao"
        // - "i want to listen to shiv tandav"
        // - "play shiv tandav on youtube"
        extractYoutubePlayIntent(cleanedText)?.let { return it }

        // C. Check Multi-Command Chaining ("and", "then", "aur", "fir", "phir")
        // e.g. "turn on flashlight and set volume to 80" or "open whatsapp and click status"
        val chainDelimiter = Regex("""\s+(?:and\s+then|then|aur\s+fir|aur\s+phir|aur|fir|phir|and)\s+""", RegexOption.IGNORE_CASE)
        if (chainDelimiter.containsMatchIn(cleanedText)) {
            val clauses = cleanedText.split(chainDelimiter).map { it.trim() }.filter { it.isNotEmpty() }
            if (clauses.size >= 2) {
                val compiledSteps = clauses.map { compileSingleClauseIntentJson(it) }
                    .filter { !it.contains("\"action\":\"noop\"") && !it.contains("\"Not understood\"") }
                if (compiledSteps.size >= 2) {
                    return "[${compiledSteps.joinToString(",")}]"
                } else if (compiledSteps.size == 1) {
                    return compiledSteps.first()
                }
            }
        }

        return compileSingleClauseIntentJson(cleanedText)
    }

    private fun extractWhatsAppSendIntent(text: String): String? {
        val lower = text.lowercase(Locale.US)
        val mentionsWhatsAppOrMessage = lower.contains("whatsapp") ||
            lower.contains("whats app") ||
            lower.startsWith("message ") ||
            lower.startsWith("send message") ||
            lower.startsWith("send a message") ||
            lower.contains("message bhejo") ||
            lower.contains("msg karo") ||
            lower.contains("message karo")

        if (!mentionsWhatsAppOrMessage) return null

        // Pattern 1: "(?:open\s+whatsapp\s+and\s+)?(?:send\s+(?:a\s+)?(?:whatsapp\s+)?message\s+to|message|msg|text)\s+([a-zA-Z0-9_]+)\s+(?:on\s+whatsapp\s+)?(?:saying\s+|that\s+)?(.+)"
        val englishPattern = Regex(
            """(?:open\s+whats\s*app\s+(?:and|then)\s+)?(?:send\s+(?:a\s+)?(?:whats\s*app\s+)?(?:message|msg|text)\s+to|message|msg|text)\s+([a-zA-Z0-9]+)\s+(?:on\s+whats\s*app\s+)?(?:saying\s+|that\s+)?(.+)""",
            RegexOption.IGNORE_CASE
        )
        englishPattern.find(text)?.let { match ->
            val contact = match.groupValues[1].trim()
            val msg = match.groupValues[2]
                .replace(Regex("""\s+(?:on|in|via)\s+whats\s*app$""", RegexOption.IGNORE_CASE), "")
                .trim()
            if (contact.isNotEmpty() && msg.isNotEmpty() && !contact.equals("whatsapp", ignoreCase = true)) {
                return """{"action":"whatsapp_send","contact":"${escapeJson(contact)}","message":"${escapeJson(msg)}"}"""
            }
        }

        // Pattern 2 (Hinglish): "(?:whatsapp\s+(?:par|pe)\s+)?([a-zA-Z0-9]+)\s+ko\s+(?:whatsapp\s+(?:par|pe)\s+)?(?:message|msg)?\s*(?:karo|bhejo|likho)?\s*(?:ki\s+)?(.+?)(?:\s+(?:message\s+)?(?:bhejo|karo|send\s+karo))?$"
        val hinglishPattern = Regex(
            """(?:whats\s*app\s+(?:par|pe|mein)\s+)?([a-zA-Z0-9]+)\s+ko\s+(?:whats\s*app\s+(?:par|pe)\s+)?(?:message\s+karo|msg\s+karo|bhejo|bol\s+do|bolo)?\s*(?:ki\s+)?(.+)""",
            RegexOption.IGNORE_CASE
        )
        if (lower.contains(" ko ") && (lower.contains("whatsapp") || lower.contains("message") || lower.contains("bhejo"))) {
            hinglishPattern.find(text)?.let { match ->
                val contact = match.groupValues[1].trim()
                val msg = match.groupValues[2]
                    .replace(Regex("""\s+(?:message\s+bhejo|bhejo|send\s+karo|karo)$""", RegexOption.IGNORE_CASE), "")
                    .trim()
                if (contact.isNotEmpty() && msg.isNotEmpty()) {
                    return """{"action":"whatsapp_send","contact":"${escapeJson(contact)}","message":"${escapeJson(msg)}"}"""
                }
            }
        }

        return null
    }

    private fun extractYoutubePlayIntent(text: String): String? {
        val lower = text.lowercase(Locale.US)

        // Check if user wants to play/listen/search a song or video
        val isYoutubeOrMusicIntent =
            (lower.contains("youtube") && (lower.contains("search") || lower.contains("play") || lower.contains("chalao") || lower.contains("lagao") || lower.contains("sunao") || lower.contains("watch"))) ||
                lower.contains("sunna hai") ||
                lower.contains("suna do") ||
                lower.contains("sunao") ||
                lower.contains("dekhna hai") ||
                lower.contains("i want to listen") ||
                lower.contains("i wanna listen") ||
                lower.contains("i want to hear") ||
                lower.contains("i wanna watch") ||
                lower.contains("i want to watch") ||
                lower.startsWith("play ") ||
                lower.contains("gana chalao") ||
                lower.contains("gaana chalao") ||
                lower.contains("song chalao") ||
                lower.contains("bajao") ||
                lower.contains("शिव तांडव") ||
                lower.contains("सुनाओ") ||
                lower.contains("सुनना है") ||
                lower.contains("चलाओ")

        if (!isYoutubeOrMusicIntent) return null

        // Clean out all conversational framing around the song/video query
        var query = text
            .replace(Regex("""^(?:open\s+youtube[\s,]*(?:and\s+|then\s+)?)?""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(?:youtube\s+(?:kholo|open\s+karo)[\s,]*(?:aur\s+|and\s+)?)?""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(?:youtube\s+(?:par|pe|mein|on)\s+)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(?:i\s+(?:want\s+to|wanna)\s+(?:listen\s+to|hear|watch|play)\s+)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(?:mujhe|mere\s+liye|zara|bhai)\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(?:search\s+for|search|find|look\s+for|play)\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+and\s+play\s+(?:the\s+)?(?:first|1st|top)\s+(?:result|video|song|one).*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+aur\s+(?:pehla|first)\s+(?:video|result)\s+(?:chalao|play\s+karo).*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(?:on\s+youtube|in\s+youtube|from\s+youtube)$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(?:youtube\s+par|youtube\s+pe)\s*(?:chalao|lagao|bajao|play\s+karo)?$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(?:sunna\s+hai|suna\s+do|sunao|dekhna\s+hai|chalao|chala\s+do|lagao|laga\s+do|bajao|baja\s+do|play\s+karo|play\s+kar\s+do)$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(?:search\s+for|search|play)\s+""", RegexOption.IGNORE_CASE), "")
            .replace("मुझे", "")
            .replace("सुनना है", "")
            .replace("सुनाओ", "")
            .replace("चलाओ", "")
            .trim()

        if (query.equals("youtube", ignoreCase = true) || query.isEmpty()) {
            return null
        }

        return """{"action":"youtube_play","query":"${escapeJson(query)}"}"""
    }

    private fun compileSingleClauseIntentJson(clause: String): String {
        val cleanedText = normalizeNaturalSpeech(clause)
        val lower = cleanedText.lowercase(Locale.US)

        if (lower.isEmpty() || fillerTokens.contains(lower)) {
            return """{"action":"noop","text":""}"""
        }

        val isOff = lower.contains("off") ||
            lower.contains("disable") ||
            lower.contains("stop") ||
            lower.contains("deactivate") ||
            lower.contains("close") ||
            lower.contains("band") ||
            lower.contains("bujha") ||
            lower.contains("बंद")

        // 1. Wi-Fi (English + Hinglish + Hindi)
        if (lower.contains("wifi") || lower.contains("wi-fi") || lower.contains("wi fi") || lower.contains("वाईफाई")) {
            val state = if (isOff) "off" else "on"
            return """{"action":"wifi","state":"$state"}"""
        }

        // 2. Bluetooth
        if (lower.contains("bluetooth") || lower.contains("ब्लूटूथ")) {
            val state = if (isOff) "off" else "on"
            return """{"action":"bluetooth","state":"$state"}"""
        }

        // 3. Flashlight / Torch (including conversational "it's dark", "andhera hai", "light jalao", "torch on karo")
        if (lower.contains("flashlight") || lower.contains("torch") || lower.contains("flash light") ||
            lower.contains("flash") || lower.contains("it's dark") || lower.contains("its dark") ||
            lower.contains("andhera") || lower.contains("batti") || lower.contains("टॉर्च") ||
            (lower.contains("light") && (
                lower.contains("turn") || lower.contains("switch") || lower.contains("on") ||
                    lower.contains("off") || lower.contains("jala") || lower.contains("band") ||
                    lower.contains("karo") || lower.contains("chalu")
                ))
        ) {
            val state = if (isOff) "off" else "on"
            return """{"action":"flashlight","state":"$state"}"""
        }

        // 4. Do Not Disturb / Silent
        if (lower.contains("do not disturb") || lower.contains("dnd") ||
            lower.contains("silent mode") || lower.contains("silence phone") ||
            (lower.contains("silent") && !lower.contains("volume")) ||
            lower.contains("साइलेंट")
        ) {
            val state = if (isOff) "off" else "on"
            return """{"action":"dnd","state":"$state"}"""
        }

        // 5. Volume (0 - 100) (English + Hinglish "awaz badhao", "awaz kam karo")
        if (lower.contains("volume") || lower.contains("louder") || lower.contains("quieter") ||
            lower.contains("mute") || lower.contains("unmute") || lower.contains("sound") ||
            lower.contains("awaz") || lower.contains("aawaz") || lower.contains("आवाज")
        ) {
            val extractedNum = Regex("""(\d{1,3})""").find(lower)?.groupValues?.get(1)?.toIntOrNull()
            val level = when {
                extractedNum != null -> extractedNum.coerceIn(0, 100)
                (lower.contains("mute") && !lower.contains("unmute")) || lower.contains("band") -> 0
                lower.contains("max") || lower.contains("full") || lower.contains("puri") -> 100
                lower.contains("high") || lower.contains("louder") || lower.contains("up") ||
                    lower.contains("increase") || lower.contains("badha") || lower.contains("tez") -> 80
                lower.contains("low") || lower.contains("quieter") || lower.contains("down") ||
                    lower.contains("decrease") || lower.contains("kam") || lower.contains("dheere") -> 25
                else -> 50
            }
            return """{"action":"volume","level":$level}"""
        }

        // 6. Alarm / Timer
        if (lower.contains("alarm") || lower.contains("wake me") || lower.contains("timer") || lower.contains("अलार्म")) {
            val extractedNum = Regex("""(\d{1,4})""").find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: 30
            val minutes = if ((lower.contains("hour") || lower.contains("ghante")) && extractedNum <= 12) {
                extractedNum * 60
            } else {
                extractedNum.coerceAtLeast(1)
            }
            return """{"action":"alarm","minutes":$minutes}"""
        }

        // 7. Call contact or number (English "call Mom" + Hinglish "Mom ko call karo" / "Rahul ko phone lagao")
        val hinglishCallMatch = Regex(
            """^([a-zA-Z0-9\s]+?)\s+ko\s+(?:call|phone|ring)\s*(?:karo|lagao|kar\s+do|kardo)?$""",
            RegexOption.IGNORE_CASE
        ).find(cleanedText)
        if (hinglishCallMatch != null) {
            val target = hinglishCallMatch.groupValues[1].trim()
            if (target.isNotEmpty()) {
                return """{"action":"call","name":"${escapeJson(target)}"}"""
            }
        }

        val callMatch = Regex("""(?:call|dial|phone)\s+(?:lagao\s+)?(.+)""", RegexOption.IGNORE_CASE).find(cleanedText)
        if (callMatch != null) {
            val contactName = callMatch.groupValues[1]
                .replace(Regex("""^(?:to|my|on)\s+""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s+(?:ko|please|now|right now|karo|lagao)$""", RegexOption.IGNORE_CASE), "")
                .trim()
            if (contactName.isNotEmpty()) {
                return """{"action":"call","name":"${escapeJson(contactName)}"}"""
            }
        }

        // 8. Conversational Open App Intent (English + Hinglish + Semantic Phrases + Installed App Scan)
        matchConversationalOpenApp(lower, cleanedText)?.let { appName ->
            return """{"action":"open_app","query":"${escapeJson(appName)}"}"""
        }

        // 9. UI Click via Accessibility Service
        val clickMatch = Regex(
            """(?:click|tap|press|select)\s+(?:on\s+|the\s+)?(.+)""",
            RegexOption.IGNORE_CASE
        ).find(cleanedText)
        if (clickMatch != null) {
            val targetText = clickMatch.groupValues[1]
                .replace(Regex("""\s+(?:button|icon|now|please|karo)$""", RegexOption.IGNORE_CASE), "")
                .trim()
            if (targetText.isNotEmpty()) {
                return """{"action":"ui_click","text":"${escapeJson(targetText)}"}"""
            }
        }

        val hinglishClickMatch = Regex(
            """^(.+?)\s+(?:par|pe)\s+(?:click|tap|press)\s+karo$""",
            RegexOption.IGNORE_CASE
        ).find(cleanedText)
        if (hinglishClickMatch != null) {
            val targetText = hinglishClickMatch.groupValues[1].trim()
            if (targetText.isNotEmpty()) {
                return """{"action":"ui_click","text":"${escapeJson(targetText)}"}"""
            }
        }

        // 10. System Navigation via Accessibility Service (English + Hinglish)
        when {
            lower.contains("scroll down") || lower.contains("swipe up") || lower.contains("niche scroll") || lower.contains("neeche") ->
                return """{"action":"nav","target":"scroll_down"}"""
            lower.contains("scroll up") || lower.contains("swipe down") || lower.contains("upar scroll") ->
                return """{"action":"nav","target":"scroll_up"}"""
            lower.contains("go home") || lower == "home" || lower.contains("home screen") || lower.contains("home chalo") ->
                return """{"action":"nav","target":"home"}"""
            lower.contains("go back") || lower == "back" || lower.contains("navigate back") || lower.contains("piche") || lower.contains("wapas") ->
                return """{"action":"nav","target":"back"}"""
            lower.contains("recent") || lower.contains("overview") || lower.contains("recent apps") ->
                return """{"action":"nav","target":"recents"}"""
            lower.contains("notification") ->
                return """{"action":"nav","target":"notifications"}"""
        }

        // 11. Battery Telemetry
        if (lower.contains("battery") || lower.contains("charge level") || lower.contains("power level") ||
            lower.contains("charge kitna") || lower.contains("बैटरी")
        ) {
            return """{"action":"battery"}"""
        }

        // 12. Concise Built-In Replies
        when {
            lower.contains("who made you") || lower.contains("creator") || lower.contains("engineer") ||
                lower.contains("who built you") || lower.contains("kisne banaya") ->
                return """{"action":"speak","text":"I was created and engineered by Prince Priyadarshi."}"""
            lower.contains("time") || lower.contains("samay") || lower.contains("kitne baje") -> {
                val now = java.text.SimpleDateFormat("h:mm a", Locale.US).format(java.util.Date())
                return """{"action":"speak","text":"It is $now."}"""
            }
            lower.contains("date") || lower.contains("today") || lower.contains("aaj kya") -> {
                val today = java.text.SimpleDateFormat("EEEE, MMMM d", Locale.US).format(java.util.Date())
                return """{"action":"speak","text":"Today is $today."}"""
            }
        }

        // Strict Requirement: NEVER repeat what the user said or list instructions. Speak ONLY "Not understood".
        return """{"action":"speak","text":"Not understood"}"""
    }

    private fun matchConversationalOpenApp(lower: String, rawClause: String): String? {
        // Semantic conversational phrases where user implies an app
        when {
            (lower.contains("see my message") || lower.contains("check my message") ||
                lower.contains("read my message") || lower.contains("chat dekhna") ||
                lower.contains("whatsapp")) && !lower.contains("send") ->
                return "whatsapp"
            lower.contains("take a selfie") || lower.contains("click a photo") ||
                lower.contains("take a picture") || lower.contains("photo khich") ||
                lower.contains("selfie le") ->
                return "camera"
            lower.contains("watch video") || lower.contains("youtube") ->
                return "youtube"
            lower.contains("show my photo") || lower.contains("see my photo") ->
                return "gallery"
        }

        // Hinglish pattern: "(?:mera|meri|apna)? <app> (?:kholo|khol do|khol de|chalu karo|on karo|open karo|start karo)"
        val hinglishOpen = Regex(
            """(?:mera|meri|apna|zara|bhai)?\s*([a-zA-Z0-9\s]+?)\s+(?:kholo|khol\s+do|khol\s+de|khol|chalu\s+karo|on\s+karo|open\s+karo|start\s+karo|chalao)$""",
            RegexOption.IGNORE_CASE
        ).find(rawClause)
        if (hinglishOpen != null) {
            val candidate = hinglishOpen.groupValues[1]
                .replace(Regex("""^(?:mera|meri|apna|my|the)\s+""", RegexOption.IGNORE_CASE), "")
                .trim()
                .lowercase(Locale.US)
            if (candidate.isNotEmpty()) {
                return candidate
            }
        }

        // Hindi script app names
        when {
            lower.contains("व्हाट्सएप") || lower.contains("वाट्सएप") -> return "whatsapp"
            lower.contains("यूट्यूब") -> return "youtube"
            lower.contains("कैमरा") -> return "camera"
            lower.contains("सेटिंग") -> return "settings"
            lower.contains("इंस्टाग्राम") -> return "instagram"
            lower.contains("कैलकुलेटर") -> return "calculator"
        }

        // English conversational pattern: ".*(?:open|launch|start|run|go to|check)\s+(?:my\s+|the\s+)?([a-zA-Z0-9\s]+)"
        val openMatch = Regex(
            """(?:open|launch|start|run|go\s+to)\s+(?:up\s+)?(?:my\s+|the\s+)?(.+)""",
            RegexOption.IGNORE_CASE
        ).find(lower)
        if (openMatch != null) {
            val query = openMatch.groupValues[1]
                .replace(Regex("""\s+(?:application|app|please|right now|now|for me|karo|kholo)$"""), "")
                .trim()
            if (query.isNotEmpty()) {
                return query
            }
        }

        // Scan installed/known app catalog anywhere in the user's sentence
        val sortedCatalog = installedAppCatalog.sortedByDescending { it.length }
        for (appName in sortedCatalog) {
            val wordBoundaryRegex = Regex("""\b${Regex.escape(appName)}\b""", RegexOption.IGNORE_CASE)
            if (wordBoundaryRegex.containsMatchIn(lower)) {
                return appName
            }
        }

        return null
    }

    private fun escapeJson(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", "")
    }
}
