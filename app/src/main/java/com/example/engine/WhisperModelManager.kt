package com.example.engine

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object WhisperModelManager {
    const val WHISPER_MODEL_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"
    const val WHISPER_FILENAME = "ggml-tiny.bin"

    fun getWhisperModelFile(context: Context): File {
        val modelsDir = File(context.filesDir, "models")
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }
        val internalFile = File(modelsDir, WHISPER_FILENAME)
        if (internalFile.exists() && internalFile.length() > 10_000_000L) {
            return internalFile
        }
        val downloadFile = File("/storage/emulated/0/Download/$WHISPER_FILENAME")
        if (downloadFile.exists() && downloadFile.length() > 10_000_000L && downloadFile.canRead()) {
            return downloadFile
        }
        return internalFile
    }

    fun isWhisperModelInstalled(context: Context): Boolean {
        val file = getWhisperModelFile(context)
        return file.exists() && file.isFile && file.length() > 10_000_000L
    }

    fun getWhisperModelSizeMb(context: Context): Long {
        val file = getWhisperModelFile(context)
        return if (file.exists()) (file.length() / (1024L * 1024L)).coerceAtLeast(1L) else 0L
    }

    suspend fun downloadWhisperModel(
        context: Context,
        onProgress: (percent: Int, statusText: String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val modelsDir = File(context.filesDir, "models")
        if (!modelsDir.exists()) modelsDir.mkdirs()

        val tempFile = File(modelsDir, "$WHISPER_FILENAME.part")
        val targetFile = File(modelsDir, WHISPER_FILENAME)

        try {
            onProgress(1, "Connecting to HuggingFace (ggml-tiny.bin)...")
            var currentUrl = WHISPER_MODEL_URL
            var redirects = 0
            var connection: HttpURLConnection

            while (true) {
                connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "JARVIS-Android-Orchestrator/2.0")
                }
                val code = connection.responseCode
                if (code in 301..308) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location.isNullOrBlank() || redirects >= 5) {
                        break
                    }
                    currentUrl = location
                    redirects++
                } else {
                    break
                }
            }

            val status = connection.responseCode
            if (status !in 200..299) {
                return@withContext Result.failure(
                    IllegalStateException("HTTP $status while downloading ggml-tiny.bin")
                )
            }

            val totalBytes = connection.contentLengthLong.takeIf { it > 0L } ?: (75L * 1024L * 1024L)
            var downloadedBytes = 0L

            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    var lastReportedPct = -1
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        val pct = ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(1, 99)
                        if (pct != lastReportedPct) {
                            lastReportedPct = pct
                            val mbDone = downloadedBytes / (1024L * 1024L)
                            val mbTotal = totalBytes / (1024L * 1024L)
                            onProgress(pct, "Downloading ggml-tiny.bin ($mbDone / $mbTotal MB • $pct%)")
                        }
                    }
                    output.flush()
                }
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }
            tempFile.renameTo(targetFile)
            onProgress(100, "Whisper Ear Installed (ggml-tiny.bin • ${targetFile.length() / (1024 * 1024)} MB)")
            Result.success(targetFile)
        } catch (e: Exception) {
            tempFile.delete()
            Result.failure(e)
        }
    }

    suspend fun importLocalWhisperBin(
        context: Context,
        uri: Uri,
        onProgress: (percent: Int, statusText: String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val modelsDir = File(context.filesDir, "models")
            if (!modelsDir.exists()) modelsDir.mkdirs()
            val targetFile = File(modelsDir, WHISPER_FILENAME)

            onProgress(10, "Copying ggml-tiny.bin into internal storage...")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    var len: Int
                    while (input.read(buffer).also { len = it } != -1) {
                        output.write(buffer, 0, len)
                        copied += len
                        val pct = ((copied / (750 * 1024)).toInt()).coerceIn(10, 98)
                        onProgress(pct, "Importing ggml-tiny.bin (${copied / (1024 * 1024)} MB)...")
                    }
                    output.flush()
                }
            } ?: return@withContext Result.failure(IllegalStateException("Unable to open selected file"))

            onProgress(100, "Whisper Ear Ready (${targetFile.length() / (1024 * 1024)} MB)")
            Result.success(targetFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
