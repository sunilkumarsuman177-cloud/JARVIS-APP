package com.example.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.service.JarvisStateBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object ApkSelfSharer {
    private const val SHARED_DIR_NAME = "shared_apk"
    private const val SHARED_APK_FILENAME = "JARVIS_by_Prince_Priyadarshi.apk"

    /**
     * Reads the currently installed base APK from `applicationInfo.sourceDir`,
     * copies it into `File(cacheDir, "shared_apk/JARVIS_by_Prince_Priyadarshi.apk")`,
     * wraps it in a `FileProvider` URI, and opens the system share chooser for
     * one-tap sharing via WhatsApp, Telegram, or Bluetooth.
     */
    suspend fun shareInstalledApk(context: Context): Result<File> = withContext(Dispatchers.IO) {
        try {
            val appContext = context.applicationContext
            val sourceApkPath = appContext.applicationInfo.sourceDir
            val sourceApkFile = File(sourceApkPath)

            if (!sourceApkFile.exists()) {
                return@withContext Result.failure(
                    IllegalStateException("Base APK not found at $sourceApkPath")
                )
            }

            val sharedDir = File(appContext.cacheDir, SHARED_DIR_NAME)
            if (!sharedDir.exists()) {
                sharedDir.mkdirs()
            }

            val targetApkFile = File(sharedDir, SHARED_APK_FILENAME)
            FileInputStream(sourceApkFile).use { input ->
                FileOutputStream(targetApkFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }

            val authority = "${appContext.packageName}.fileprovider"
            val contentUri = FileProvider.getUriForFile(appContext, authority, targetApkFile)
            val apkSizeMb = String.format(
                java.util.Locale.US,
                "%.1f MB",
                targetApkFile.length() / (1024.0 * 1024.0)
            )

            withContext(Dispatchers.Main) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.android.package-archive"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    putExtra(
                        Intent.EXTRA_SUBJECT,
                        "JARVIS Offline AI Orchestrator — Engineered by Prince Priyadarshi"
                    )
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "Install JARVIS ($apkSizeMb) — 100% Offline Android Voice Orchestrator engineered by Prince Priyadarshi."
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val chooser = Intent.createChooser(shareIntent, "Share JARVIS APK ($apkSizeMb)").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            }

            JarvisStateBus.appendLog(
                "APK_SHARE",
                "Prepared ${targetApkFile.name} ($apkSizeMb) via FileProvider for WhatsApp/Bluetooth."
            )
            Result.success(targetApkFile)
        } catch (e: Exception) {
            JarvisStateBus.appendLog("ERROR", "APK Share failed: ${e.localizedMessage}")
            Result.failure(e)
        }
    }
}
