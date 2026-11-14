package com.example.service

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class JarvisOrchestratorState {
    SLEEPING,
    RECORDING,
    TRANSCRIBING,
    WAKING_BRAIN,
    ONLINE,
    EXECUTING
}

data class TerminalLogEntry(
    val id: Long = System.nanoTime(),
    val timestamp: String,
    val tag: String,
    val message: String,
    val isJson: Boolean = false
)

data class JarvisRuntimeSnapshot(
    val state: JarvisOrchestratorState = JarvisOrchestratorState.SLEEPING,
    val serviceRunning: Boolean = false,
    val voskReady: Boolean = false,
    val voskListening: Boolean = false,
    val voskDownloadProgress: Int? = null,
    val voskStatusText: String = "Checking Vosk voice pack...",
    val whisperReady: Boolean = false,
    val whisperDownloadProgress: Int? = null,
    val whisperStatusText: String = "75 MB • Bilingual Whisper Ear (ggml-tiny.bin)",
    val brainLoadedInRam: Boolean = false,
    val ggufModelPath: String = "",
    val ggufDisplayName: String = "SmolLM2-360M-Instruct-Q4_K_M.gguf",
    val ggufFileExists: Boolean = false,
    val ggufSizeMb: Long = 0L,
    val ggufHeaderValid: Boolean = false,
    val engineInfo: String = "JNI C++ Memory Relay Bridge Ready",
    val wakeLine: String = "hey jarvis",
    val inactivityTimeoutSec: Int = 60,
    val remainingWakeSeconds: Int = 0,
    val appRamMb: Int = 34,
    val deviceAvailableRamMb: Int = 920,
    val deviceTotalRamMb: Int = 2048,
    val lastHeardPhrase: String = "—",
    val lastExecutedJson: String = "{\"status\":\"idle\",\"brain\":\"0 MB unloaded\"}",
    val lastActionFeedback: String = "Standing by for wake phrase",
    val terminalLogs: List<TerminalLogEntry> = emptyList()
)

object JarvisStateBus {
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _snapshot = MutableStateFlow(
        JarvisRuntimeSnapshot(
            terminalLogs = listOf(
                TerminalLogEntry(
                    timestamp = timeFormat.format(Date()),
                    tag = "BOOT",
                    message = "JARVIS Memory Relay Pipeline (Vosk -> Whisper.cpp -> Llama.cpp) initialized."
                ),
                TerminalLogEntry(
                    timestamp = timeFormat.format(Date()),
                    tag = "RELAY",
                    message = "Whisper & GGUF unloaded (0 MB Idle RAM). Vosk Ear listening for wake phrase."
                )
            )
        )
    )
    val snapshot: StateFlow<JarvisRuntimeSnapshot> = _snapshot.asStateFlow()

    fun updateState(transform: (JarvisRuntimeSnapshot) -> JarvisRuntimeSnapshot) {
        _snapshot.update(transform)
    }

    fun appendLog(tag: String, message: String, isJson: Boolean = false) {
        val entry = TerminalLogEntry(
            timestamp = timeFormat.format(Date()),
            tag = tag,
            message = message,
            isJson = isJson
        )
        _snapshot.update { current ->
            val updated = (current.terminalLogs + entry).takeLast(50)
            current.copy(terminalLogs = updated)
        }
    }

    fun clearLogs() {
        _snapshot.update { current ->
            current.copy(
                terminalLogs = listOf(
                    TerminalLogEntry(
                        timestamp = timeFormat.format(Date()),
                        tag = "CLEAR",
                        message = "Terminal log cleared."
                    )
                )
            )
        }
    }

    fun sampleProcessRamMb(context: Context): Triple<Int, Int, Int> {
        return try {
            val memoryInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memoryInfo)
            val totalPssKb = memoryInfo.totalPss
            val pssMb = (totalPssKb / 1024).coerceAtLeast(18)

            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val sysMem = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(sysMem)
            val availMb = (sysMem.availMem / (1024 * 1024)).toInt()
            val totalMb = (sysMem.totalMem / (1024 * 1024)).toInt().coerceAtLeast(2048)

            Triple(pssMb, availMb, totalMb)
        } catch (_: Throwable) {
            val rt = Runtime.getRuntime()
            val usedMb = ((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).toInt().coerceAtLeast(24)
            Triple(usedMb, 900, 2048)
        }
    }
}
