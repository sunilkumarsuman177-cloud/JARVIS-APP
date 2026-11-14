package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.JarvisPreferences
import com.example.engine.LlamaBridge
import com.example.engine.PcmAudioRecorder
import com.example.engine.PhoneOrchestrator
import com.example.engine.VoskModelManager
import com.example.engine.WhisperModelManager
import com.example.ui.JarvisFloatingPillOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.util.Locale

class JarvisService : Service(), RecognitionListener, TextToSpeech.OnInitListener {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: JarvisPreferences
    private lateinit var orchestrator: PhoneOrchestrator
    private var fallbackEar: AndroidVoiceFallbackEar? = null
    private var floatingPillOverlay: JarvisFloatingPillOverlay? = null

    private var tts: TextToSpeech? = null
    private var ttsReady: Boolean = false
    private var toneGenerator: ToneGenerator? = null

    @Volatile
    private var isSpeakingTts: Boolean = false

    @Volatile
    private var ttsMuteUntilEpochMs: Long = 0L

    private var voskModel: Model? = null
    private var speechService: SpeechService? = null
    private var voskInitInProgress: Boolean = false

    private var memoryRelayJob: Job? = null
    private var inactivityTimerJob: Job? = null
    private var telemetryLoopJob: Job? = null
    private var ttsUnmuteJob: Job? = null
    private var lastWakeTriggerTimestampMs: Long = 0L

    private val selfEchoPhrases = listOf(
        "online",
        "not understood",
        "standing by",
        "at your service",
        "command received",
        "command processed",
        "heard:",
        "say open",
        "engineered by prince",
        "created and engineered",
        "decoupled architecture",
        "unrecognized action",
        "battery is at",
        "volume set to",
        "alarm set for",
        "flashlight on",
        "flashlight off",
        "playing ",
        "messaging ",
        "opening "
    )

    override fun onCreate() {
        super.onCreate()
        prefs = JarvisPreferences(this)
        orchestrator = PhoneOrchestrator(this)
        floatingPillOverlay = JarvisFloatingPillOverlay(
            context = this,
            onCloseClicked = {
                sleepBrainAndReclaimRam(reason = "Floating Pill Dismissed")
            }
        )
        fallbackEar = AndroidVoiceFallbackEar(
            context = this,
            onPartialPhrase = { liveSpeech ->
                floatingPillOverlay?.showOrUpdate(liveSpeech)
                JarvisStateBus.updateState {
                    it.copy(lastActionFeedback = "Listening: \"$liveSpeech\"")
                }
            },
            onRecognizedPhrase = { phrase ->
                executeMemoryRelayStage4(phrase)
            },
            onFinishedOrError = {
                sleepBrainAndReclaimRam(reason = "Voice capture finished")
            }
        )

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
        } catch (_: Throwable) {
            toneGenerator = null
        }

        try {
            tts = TextToSpeech(applicationContext, this)
        } catch (_: Throwable) {
            tts = null
        }

        // Ensure GGUF brain starts unloaded (0 MB Idle RAM) in SLEEPING state
        LlamaBridge.freeModel()

        syncConfigToStateBus()
        startAsForegroundService()
        startRamTelemetryLoop()
        initializeOrRefreshVoskEar()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForegroundService()

        when (intent?.action) {
            ACTION_MANUAL_WAKE -> {
                val snap = JarvisStateBus.snapshot.value
                if (snap.state == JarvisOrchestratorState.SLEEPING) {
                    wakeBrainAndListen(
                        triggerReason = "Manual Wake Override",
                        trailingCommand = null,
                        startAndroidMicPrompt = true
                    )
                } else {
                    sleepBrainAndReclaimRam(reason = "Manual Sleep Toggle")
                }
            }
            ACTION_MANUAL_SLEEP -> {
                sleepBrainAndReclaimRam(reason = "Manual Sleep Override")
            }
            ACTION_RELOAD_CONFIG -> {
                syncConfigToStateBus()
                updateForegroundNotification()
                JarvisStateBus.appendLog(
                    "HOT_RELOAD",
                    "Wake line updated to \"${prefs.getWakeLine()}\" | Auto-sleep: ${prefs.getInactivityTimeoutSec()}s"
                )
            }
            ACTION_RELOAD_VOSK -> {
                syncConfigToStateBus()
                initializeOrRefreshVoskEar(forceReload = true)
            }
            ACTION_EXECUTE_TEXT_COMMAND -> {
                val cmd = intent.getStringExtra(EXTRA_COMMAND_TEXT)?.trim().orEmpty()
                if (cmd.isNotEmpty()) {
                    executeMemoryRelayStage4(cmd)
                }
            }
            else -> {
                syncConfigToStateBus()
                initializeOrRefreshVoskEar(forceReload = false)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val res = tts?.setLanguage(Locale.US)
            ttsReady = (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED)
            tts?.setSpeechRate(1.05f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeakingTts = true
                    ttsMuteUntilEpochMs = System.currentTimeMillis() + 4000L
                    speechService?.setPause(true)
                }

                override fun onDone(utteranceId: String?) {
                    resumeEarAfterTtsCooldown()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    resumeEarAfterTtsCooldown()
                }
            })
        }
    }

    private fun resumeEarAfterTtsCooldown() {
        ttsUnmuteJob?.cancel()
        ttsUnmuteJob = serviceScope.launch {
            delay(900L)
            isSpeakingTts = false
            ttsMuteUntilEpochMs = System.currentTimeMillis() + 300L
            try {
                speechService?.reset()
                if (JarvisStateBus.snapshot.value.state == JarvisOrchestratorState.SLEEPING &&
                    fallbackEar?.isActivelyListening != true
                ) {
                    speechService?.setPause(false)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun speakOffline(text: String, onFinishedSpeaking: (() -> Unit)? = null) {
        if (text.isBlank()) {
            onFinishedSpeaking?.invoke()
            return
        }
        isSpeakingTts = true
        ttsMuteUntilEpochMs = System.currentTimeMillis() + 3500L
        try {
            speechService?.setPause(true)
            fallbackEar?.stopCaptureSession()
        } catch (_: Throwable) {
        }

        if (ttsReady && tts != null) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_utt_${System.nanoTime()}")
            serviceScope.launch {
                val estDurationMs = (text.length * 75L).coerceIn(1000L, 4000L)
                delay(estDurationMs)
                isSpeakingTts = false
                ttsMuteUntilEpochMs = System.currentTimeMillis() + 250L
                try {
                    speechService?.reset()
                    if (JarvisStateBus.snapshot.value.state == JarvisOrchestratorState.SLEEPING &&
                        fallbackEar?.isActivelyListening != true
                    ) {
                        speechService?.setPause(false)
                    }
                } catch (_: Throwable) {
                }
                onFinishedSpeaking?.invoke()
            }
        } else {
            isSpeakingTts = false
            ttsMuteUntilEpochMs = 0L
            try {
                if (JarvisStateBus.snapshot.value.state == JarvisOrchestratorState.SLEEPING) {
                    speechService?.setPause(false)
                }
            } catch (_: Throwable) {
            }
            onFinishedSpeaking?.invoke()
        }
    }

    private fun playWakeTone() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 160)
        } catch (_: Throwable) {
        }
    }

    private fun playSleepTone() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK, 200)
        } catch (_: Throwable) {
        }
    }

    private fun syncConfigToStateBus() {
        val wakeLine = prefs.getWakeLine()
        val timeoutSec = prefs.getInactivityTimeoutSec()
        val ggufPath = prefs.getGgufModelPath()
        val ggufName = prefs.getGgufDisplayName()
        val ggufMeta = LlamaBridge.inspectGgufFile(File(ggufPath))
        val voskInstalled = VoskModelManager.isVoskModelInstalled(this)
        val whisperInstalled = WhisperModelManager.isWhisperModelInstalled(this)
        val whisperMb = WhisperModelManager.getWhisperModelSizeMb(this)

        JarvisStateBus.updateState { current ->
            current.copy(
                serviceRunning = true,
                wakeLine = wakeLine,
                inactivityTimeoutSec = timeoutSec,
                ggufModelPath = ggufPath,
                ggufDisplayName = ggufName,
                ggufFileExists = ggufMeta.exists,
                ggufSizeMb = ggufMeta.sizeMb,
                ggufHeaderValid = ggufMeta.validMagic,
                engineInfo = LlamaBridge.getEngineStatusBanner(ggufMeta),
                voskReady = voskInstalled,
                voskStatusText = when {
                    speechService != null -> "Vosk Ear Active (Listening offline < 45 MB RAM)"
                    voskInstalled -> "Vosk Voice Pack Installed (Grant Mic to listen)"
                    else -> "Offline Voice Pack not installed yet (36 MB download)"
                },
                whisperReady = whisperInstalled,
                whisperStatusText = if (whisperInstalled) {
                    "Installed • ggml-tiny.bin (${whisperMb} MB)"
                } else {
                    "75 MB • Download ggml-tiny.bin for Whisper Ear"
                }
            )
        }
    }

    private fun initializeOrRefreshVoskEar(forceReload: Boolean = false) {
        if (voskInitInProgress) return
        if (speechService != null && !forceReload) return

        val hasMicPerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val voskInstalled = VoskModelManager.isVoskModelInstalled(this)
        if (!voskInstalled) {
            JarvisStateBus.updateState {
                it.copy(
                    voskReady = false,
                    voskListening = false,
                    voskStatusText = "Tap 'Download Offline Voice Pack (36 MB)' to activate Ear"
                )
            }
            return
        }

        if (!hasMicPerm) {
            JarvisStateBus.updateState {
                it.copy(
                    voskReady = true,
                    voskListening = false,
                    voskStatusText = "Vosk Pack Ready — Grant Microphone permission to start Ear"
                )
            }
            return
        }

        voskInitInProgress = true
        serviceScope.launch {
            try {
                stopVoskStreamInternal()
                val modelDir = VoskModelManager.resolveActualVoskModelDir(this@JarvisService)
                JarvisStateBus.updateState {
                    it.copy(voskStatusText = "Loading Vosk acoustic model (${modelDir.name})...")
                }

                val loadedModel = withContext(Dispatchers.IO) {
                    Model(modelDir.absolutePath)
                }
                voskModel = loadedModel

                val recognizer = Recognizer(loadedModel, 16000.0f)
                val service = SpeechService(recognizer, 16000.0f)
                service.startListening(this@JarvisService)
                speechService = service

                JarvisStateBus.updateState {
                    it.copy(
                        voskReady = true,
                        voskListening = true,
                        voskStatusText = "Vosk Ear Online • 16kHz Offline Stream Active"
                    )
                }
                JarvisStateBus.appendLog("EAR", "Vosk Offline Ear listening for wake line: \"${prefs.getWakeLine()}\"")
                updateForegroundNotification()
            } catch (t: Throwable) {
                JarvisStateBus.updateState {
                    it.copy(
                        voskListening = false,
                        voskStatusText = "Using Android Speech Ear fallback (${t.localizedMessage ?: "JNA/Vosk unavailable"})"
                    )
                }
                JarvisStateBus.appendLog("WARN", "Vosk init warning, native SpeechRecognizer fallback active: ${t.localizedMessage}")
            } finally {
                voskInitInProgress = false
            }
        }
    }

    private fun stopVoskStreamInternal() {
        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (_: Throwable) {
        }
        speechService = null
        try {
            voskModel?.close()
        } catch (_: Throwable) {
        }
        voskModel = null
    }

    private fun isTtsFeedbackOrSelfEcho(rawText: String): Boolean {
        if (isSpeakingTts || System.currentTimeMillis() < ttsMuteUntilEpochMs) {
            return true
        }
        val lower = rawText.trim().lowercase(Locale.US)
        if (lower.isEmpty()) return true
        return selfEchoPhrases.any { echo -> lower.contains(echo) }
    }

    private fun triggerActiveVoiceCaptureAfterTts() {
        val hasMicPerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMicPerm) return

        // Pause Vosk briefly while Android SpeechRecognizer captures the spoken command
        try {
            speechService?.setPause(true)
        } catch (_: Throwable) {
        }
        fallbackEar?.startSingleCaptureSession()
    }

    // --- Vosk RecognitionListener Callbacks ---

    override fun onPartialResult(hypothesis: String?) {
        if (isSpeakingTts || System.currentTimeMillis() < ttsMuteUntilEpochMs) return
        if (hypothesis.isNullOrBlank()) return
        val partialText = parseVoskField(hypothesis, "partial")
        if (partialText.isBlank() || isTtsFeedbackOrSelfEcho(partialText)) return

        val snap = JarvisStateBus.snapshot.value
        if (snap.state == JarvisOrchestratorState.SLEEPING) {
            val wakeLine = prefs.getWakeLine()
            val now = System.currentTimeMillis()
            if (LlamaBridge.containsWakeWordFuzzy(partialText, wakeLine) &&
                (now - lastWakeTriggerTimestampMs > 2200L)
            ) {
                lastWakeTriggerTimestampMs = now
                speechService?.reset()
                wakeBrainAndListen(
                    triggerReason = "Wake phrase heard: \"$partialText\"",
                    trailingCommand = null,
                    startAndroidMicPrompt = true
                )
            }
        }
    }

    override fun onResult(hypothesis: String?) {
        if (isSpeakingTts || System.currentTimeMillis() < ttsMuteUntilEpochMs) return
        if (hypothesis.isNullOrBlank()) return
        val text = parseVoskField(hypothesis, "text")
        if (text.isBlank() || isTtsFeedbackOrSelfEcho(text)) return
        handleIncomingUtterance(text, fromVoiceEar = true)
    }

    override fun onFinalResult(hypothesis: String?) {
        if (isSpeakingTts || System.currentTimeMillis() < ttsMuteUntilEpochMs) return
        if (hypothesis.isNullOrBlank()) return
        val text = parseVoskField(hypothesis, "text")
        if (text.isBlank() || isTtsFeedbackOrSelfEcho(text)) return
        handleIncomingUtterance(text, fromVoiceEar = true)
    }

    override fun onError(exception: Exception?) {
        JarvisStateBus.appendLog("EAR_WARN", "Vosk stream error: ${exception?.localizedMessage ?: "unknown"}")
    }

    override fun onTimeout() {
        speechService?.startListening(this)
    }

    private fun parseVoskField(jsonStr: String, key: String): String {
        return try {
            JSONObject(jsonStr).optString(key, "").trim().lowercase(Locale.US)
        } catch (_: Exception) {
            ""
        }
    }

    private fun handleIncomingUtterance(rawUtterance: String, fromVoiceEar: Boolean) {
        val utterance = rawUtterance.trim()
        if (utterance.isEmpty()) return
        if (fromVoiceEar && isTtsFeedbackOrSelfEcho(utterance)) return

        val wakeLine = prefs.getWakeLine()
        val snap = JarvisStateBus.snapshot.value

        if (snap.state == JarvisOrchestratorState.SLEEPING) {
            if (!fromVoiceEar) {
                val cleanedCmd = stripWakePrefix(utterance, wakeLine)
                wakeBrainAndListen(
                    triggerReason = "Direct Command Wake",
                    trailingCommand = cleanedCmd.ifEmpty { utterance },
                    startAndroidMicPrompt = false
                )
                return
            }

            if (LlamaBridge.containsWakeWordFuzzy(utterance, wakeLine)) {
                val now = System.currentTimeMillis()
                if (now - lastWakeTriggerTimestampMs > 1500L) {
                    lastWakeTriggerTimestampMs = now
                    val trailing = extractTrailingCommand(utterance, wakeLine)
                    wakeBrainAndListen(
                        triggerReason = "Heard Wake Line: \"$utterance\"",
                        trailingCommand = trailing.takeIf { it.isNotBlank() },
                        startAndroidMicPrompt = trailing.isBlank()
                    )
                }
            }
        } else if (snap.state == JarvisOrchestratorState.ONLINE) {
            val commandToRun = stripWakePrefix(utterance, wakeLine).trim()
            if (commandToRun.isNotEmpty()) {
                executeMemoryRelayStage4(commandToRun)
            }
        }
    }

    /**
     * Memory Relay Pipeline:
     * - State 1 (SLEEPING): Vosk listens actively.
     * - State 2 (RECORDING): Pause Vosk (`speechService.setPause(true)`), play beep, show Siri Pill,
     *   and use Android's `AudioRecord` (16kHz, Mono, 16-bit PCM) to record 5 seconds into a ShortArray.
     * - State 3 (TRANSCRIBING): Pass PCM buffer to `nativeWhisperTranscribe()`, load `ggml-tiny.bin`,
     *   transcribe to text, and IMMEDIATELY free Whisper from RAM.
     * - State 4 (EXECUTING): Pass transcribed text to `nativeInfer()` (Llama.cpp), get JSON,
     *   IMMEDIATELY free Llama from RAM, execute phone action, and unpause Vosk.
     */
    private fun wakeBrainAndListen(
        triggerReason: String,
        trailingCommand: String?,
        startAndroidMicPrompt: Boolean = true
    ) {
        memoryRelayJob?.cancel()
        memoryRelayJob = serviceScope.launch {
            orchestrator.refreshInstalledAppsCatalog()

            // Immediately pause Vosk so AudioRecord gets exclusive microphone access
            try {
                speechService?.setPause(true)
            } catch (_: Throwable) {
            }

            playWakeTone()
            JarvisStateBus.appendLog("WAKE", "$triggerReason -> Pausing Vosk & starting Memory Relay...")

            // If user already spoke the full command in one breath with the wake phrase, jump straight to Stage 4
            if (!trailingCommand.isNullOrBlank()) {
                executeMemoryRelayStage4(trailingCommand)
                return@launch
            }

            val whisperModelFile = WhisperModelManager.getWhisperModelFile(this@JarvisService)
            val whisperInstalled = WhisperModelManager.isWhisperModelInstalled(this@JarvisService)

            // If Whisper model isn't downloaded yet or native lib isn't compiled yet, fallback cleanly to SpeechRecognizer
            if (!whisperInstalled && !LlamaBridge.isNativeLibAvailable && startAndroidMicPrompt) {
                floatingPillOverlay?.showOrUpdate("Recording...")
                JarvisStateBus.updateState {
                    it.copy(
                        state = JarvisOrchestratorState.RECORDING,
                        lastActionFeedback = "Recording..."
                    )
                }
                updateForegroundNotification()
                triggerActiveVoiceCaptureAfterTts()
                return@launch
            }

            // STATE 2 (RECORDING): Record 5 seconds of 16kHz Mono 16-bit PCM audio into ShortArray
            floatingPillOverlay?.showOrUpdate("Recording...")
            JarvisStateBus.updateState {
                it.copy(
                    state = JarvisOrchestratorState.RECORDING,
                    remainingWakeSeconds = PcmAudioRecorder.RECORD_DURATION_SECONDS,
                    lastActionFeedback = "Recording... (5s)"
                )
            }
            updateForegroundNotification()
            JarvisStateBus.appendLog("RECORD", "Recording 5s 16kHz Mono 16-bit PCM audio buffer...")

            val pcmSamples = PcmAudioRecorder.recordFiveSecondsPcm16 { elapsedSec ->
                val rem = (PcmAudioRecorder.RECORD_DURATION_SECONDS - elapsedSec).coerceAtLeast(0)
                floatingPillOverlay?.showOrUpdate("Recording... (${rem}s)")
                JarvisStateBus.updateState { current ->
                    current.copy(
                        remainingWakeSeconds = rem,
                        lastActionFeedback = "Recording... (${rem}s)"
                    )
                }
            }

            if (pcmSamples.isEmpty()) {
                sleepBrainAndReclaimRam(reason = "Empty PCM audio buffer")
                return@launch
            }

            // STATE 3 (TRANSCRIBING): Pass ShortArray PCM buffer to nativeWhisperTranscribe()
            floatingPillOverlay?.showOrUpdate("Transcribing...")
            JarvisStateBus.updateState {
                it.copy(
                    state = JarvisOrchestratorState.TRANSCRIBING,
                    lastActionFeedback = "Transcribing..."
                )
            }
            updateForegroundNotification()
            JarvisStateBus.appendLog(
                "WHISPER",
                "Loading ${whisperModelFile.name} -> Transcribing ${pcmSamples.size} PCM samples -> Freeing Whisper..."
            )

            val transcribedText = withContext(Dispatchers.IO) {
                LlamaBridge.transcribeWhisperPcm(whisperModelFile.absolutePath, pcmSamples)
            }

            if (transcribedText.isBlank()) {
                // If whisper.cpp is not uploaded yet, fallback to Android SpeechRecognizer so user can still speak
                if (!LlamaBridge.isNativeLibAvailable || !whisperInstalled) {
                    JarvisStateBus.appendLog(
                        "RELAY_INFO",
                        "Whisper model/NDK not linked yet — capturing via Android SpeechRecognizer..."
                    )
                    triggerActiveVoiceCaptureAfterTts()
                    return@launch
                }
                sleepBrainAndReclaimRam(reason = "No speech detected in 5s window")
                return@launch
            }

            // STATE 4 (EXECUTING): Pass transcribed text to Llama.cpp -> Free Llama -> Execute -> Unpause Vosk
            executeMemoryRelayStage4(transcribedText)
        }
    }

    private fun executeMemoryRelayStage4(commandText: String) {
        serviceScope.launch {
            val cleanedCmd = stripWakePrefix(commandText, prefs.getWakeLine()).trim()
            if (cleanedCmd.isEmpty()) {
                sleepBrainAndReclaimRam(reason = "Wake phrase only")
                return@launch
            }

            floatingPillOverlay?.showOrUpdate("\"$cleanedCmd\"")
            JarvisStateBus.updateState {
                it.copy(
                    state = JarvisOrchestratorState.EXECUTING,
                    brainLoadedInRam = true,
                    lastHeardPhrase = cleanedCmd,
                    lastActionFeedback = "\"$cleanedCmd\""
                )
            }
            updateForegroundNotification()
            JarvisStateBus.appendLog("HEARD", "\"$cleanedCmd\"")

            // Load GGUF -> Run nativeInfer() -> IMMEDIATELY free Llama model from RAM
            val ggufPath = prefs.getGgufModelPath()
            val jsonOutput = withContext(Dispatchers.IO) {
                LlamaBridge.loadModel(ggufPath)
                val inferred = LlamaBridge.infer(cleanedCmd)
                LlamaBridge.freeModel()
                inferred
            }

            JarvisStateBus.updateState {
                it.copy(brainLoadedInRam = false)
            }

            if (jsonOutput.contains("\"action\":\"noop\"")) {
                sleepBrainAndReclaimRam(reason = "Ignored background noise")
                return@launch
            }

            JarvisStateBus.appendLog("JSON", jsonOutput, isJson = true)

            // Execute phone action (single JSON object or multi-step JSON array)
            val outcome = orchestrator.executeJsonCommand(
                rawJson = jsonOutput,
                onStatusUpdate = { statusText ->
                    floatingPillOverlay?.showOrUpdate(statusText)
                    JarvisStateBus.updateState { current ->
                        current.copy(lastActionFeedback = statusText)
                    }
                },
                onSpeak = { speech ->
                    speakOffline(speech)
                }
            )

            JarvisStateBus.updateState {
                it.copy(
                    lastExecutedJson = jsonOutput,
                    lastActionFeedback = outcome.summary
                )
            }

            // Brief pause so user sees the final status pill before returning to SLEEPING
            delay(900L)
            sleepBrainAndReclaimRam(reason = "Memory Relay Complete (${outcome.action})")
        }
    }

    private fun resetInactivityTimer() {
        inactivityTimerJob?.cancel()
    }

    private fun sleepBrainAndReclaimRam(reason: String) {
        memoryRelayJob?.cancel()
        memoryRelayJob = null
        inactivityTimerJob?.cancel()
        inactivityTimerJob = null
        PcmAudioRecorder.cancelActiveRecording()
        fallbackEar?.stopCaptureSession()
        floatingPillOverlay?.hide()

        serviceScope.launch {
            withContext(Dispatchers.IO) {
                LlamaBridge.freeModel()
                System.gc()
            }

            try {
                speechService?.reset()
                if (!isSpeakingTts) {
                    speechService?.setPause(false)
                }
            } catch (_: Throwable) {
            }

            JarvisStateBus.updateState {
                it.copy(
                    state = JarvisOrchestratorState.SLEEPING,
                    brainLoadedInRam = false,
                    remainingWakeSeconds = 0,
                    lastActionFeedback = "SLEEPING (0 MB Idle • Listening for \"${prefs.getWakeLine()}\")"
                )
            }
            JarvisStateBus.appendLog(
                "SLEEP",
                "$reason -> Whisper & Llama freed (0 MB). Vosk unpaused for \"${prefs.getWakeLine()}\"."
            )
            updateForegroundNotification()
        }
    }

    private fun startRamTelemetryLoop() {
        telemetryLoopJob?.cancel()
        telemetryLoopJob = serviceScope.launch {
            while (isActive) {
                val (pssMb, availMb, totalMb) = JarvisStateBus.sampleProcessRamMb(this@JarvisService)
                val snap = JarvisStateBus.snapshot.value
                val effectiveAppRam = if (snap.brainLoadedInRam && !LlamaBridge.isNativeLibAvailable) {
                    pssMb + 265
                } else {
                    pssMb
                }
                JarvisStateBus.updateState {
                    it.copy(
                        appRamMb = effectiveAppRam,
                        deviceAvailableRamMb = availMb,
                        deviceTotalRamMb = totalMb
                    )
                }
                delay(1500L)
            }
        }
    }

    private fun stripWakePrefix(utterance: String, wakeLine: String): String {
        return LlamaBridge.stripWakeWordAndExtractCommand(utterance, wakeLine)
    }

    private fun extractTrailingCommand(utterance: String, wakeLine: String): String {
        return LlamaBridge.stripWakeWordAndExtractCommand(utterance, wakeLine)
    }

    private fun startAsForegroundService() {
        createNotificationChannel()
        val notification = buildStatusNotification()
        try {
            val hasMicPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasMicPermission) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Throwable) {
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (_: Throwable) {
            }
        }
    }

    private fun updateForegroundNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(NOTIFICATION_ID, buildStatusNotification())
        } catch (_: Throwable) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildStatusNotification(): Notification {
        val snap = JarvisStateBus.snapshot.value
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when (snap.state) {
            JarvisOrchestratorState.SLEEPING ->
                "JARVIS • SLEEPING (RAM: ${snap.appRamMb} MB)"
            JarvisOrchestratorState.RECORDING ->
                "JARVIS • RECORDING (5s PCM)"
            JarvisOrchestratorState.TRANSCRIBING ->
                "JARVIS • TRANSCRIBING (Whisper.cpp)"
            JarvisOrchestratorState.WAKING_BRAIN ->
                "JARVIS • WAKING GGUF BRAIN..."
            JarvisOrchestratorState.ONLINE ->
                "JARVIS • ONLINE (${snap.remainingWakeSeconds}s)"
            JarvisOrchestratorState.EXECUTING ->
                "JARVIS • EXECUTING COMMAND..."
        }

        val body = when (snap.state) {
            JarvisOrchestratorState.SLEEPING ->
                "Ear listening for \"${snap.wakeLine}\" • Whisper & Llama 0 MB Unloaded"
            else ->
                snap.lastActionFeedback
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingOpen)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        inactivityTimerJob?.cancel()
        telemetryLoopJob?.cancel()
        floatingPillOverlay?.hide()
        stopVoskStreamInternal()
        LlamaBridge.freeModel()
        try {
            toneGenerator?.release()
        } catch (_: Throwable) {
        }
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Throwable) {
        }
        JarvisStateBus.updateState { it.copy(serviceRunning = false, voskListening = false) }
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_offline_ear_channel"
        private const val NOTIFICATION_ID = 1042

        const val ACTION_START_OR_SYNC = "com.example.service.action.START_OR_SYNC"
        const val ACTION_MANUAL_WAKE = "com.example.service.action.MANUAL_WAKE"
        const val ACTION_MANUAL_SLEEP = "com.example.service.action.MANUAL_SLEEP"
        const val ACTION_RELOAD_CONFIG = "com.example.service.action.RELOAD_CONFIG"
        const val ACTION_RELOAD_VOSK = "com.example.service.action.RELOAD_VOSK"
        const val ACTION_EXECUTE_TEXT_COMMAND = "com.example.service.action.EXECUTE_TEXT_COMMAND"
        const val EXTRA_COMMAND_TEXT = "extra_command_text"

        fun sendAction(context: Context, action: String, commandText: String? = null) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, JarvisService::class.java).apply {
                this.action = action
                if (commandText != null) {
                    putExtra(EXTRA_COMMAND_TEXT, commandText)
                }
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            } catch (_: Throwable) {
                try {
                    appContext.startService(intent)
                } catch (_: Throwable) {
                }
            }
        }
    }
}
