package com.example.data

import android.content.Context
import android.content.SharedPreferences
import java.io.File

class JarvisPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getWakeLine(): String {
        val raw = prefs.getString(KEY_WAKE_LINE, DEFAULT_WAKE_LINE)?.trim()?.lowercase()
        return if (raw.isNullOrEmpty()) DEFAULT_WAKE_LINE else raw
    }

    fun setWakeLine(wakeLine: String) {
        val normalized = wakeLine.trim().lowercase().ifEmpty { DEFAULT_WAKE_LINE }
        prefs.edit().putString(KEY_WAKE_LINE, normalized).apply()
    }

    fun getInactivityTimeoutSec(): Int {
        val sec = prefs.getInt(KEY_INACTIVITY_TIMEOUT_SEC, DEFAULT_TIMEOUT_SEC)
        return if (sec in listOf(30, 60, 120)) sec else DEFAULT_TIMEOUT_SEC
    }

    fun setInactivityTimeoutSec(seconds: Int) {
        val valid = if (seconds in listOf(30, 60, 120)) seconds else DEFAULT_TIMEOUT_SEC
        prefs.edit().putInt(KEY_INACTIVITY_TIMEOUT_SEC, valid).apply()
    }

    fun getGgufModelPath(): String {
        val saved = prefs.getString(KEY_GGUF_MODEL_PATH, null)
        if (!saved.isNullOrBlank()) return saved
        val defaultDownload = File("/storage/emulated/0/Download/$DEFAULT_GGUF_FILENAME")
        if (defaultDownload.exists()) return defaultDownload.absolutePath
        return File(File(appContext.filesDir, "models"), DEFAULT_GGUF_FILENAME).absolutePath
    }

    fun setGgufModelPath(path: String, displayName: String) {
        prefs.edit()
            .putString(KEY_GGUF_MODEL_PATH, path)
            .putString(KEY_GGUF_DISPLAY_NAME, displayName)
            .apply()
    }

    fun getGgufDisplayName(): String {
        return prefs.getString(KEY_GGUF_DISPLAY_NAME, DEFAULT_GGUF_FILENAME) ?: DEFAULT_GGUF_FILENAME
    }

    companion object {
        const val PREFS_NAME = "jarvis_orchestrator_prefs"
        const val KEY_WAKE_LINE = "wake_line"
        const val KEY_INACTIVITY_TIMEOUT_SEC = "inactivity_timeout_sec"
        const val KEY_GGUF_MODEL_PATH = "gguf_model_path"
        const val KEY_GGUF_DISPLAY_NAME = "gguf_display_name"

        const val DEFAULT_WAKE_LINE = "hey jarvis"
        const val DEFAULT_TIMEOUT_SEC = 60
        const val DEFAULT_GGUF_FILENAME = "SmolLM2-360M-Instruct-Q4_K_M.gguf"
    }
}
