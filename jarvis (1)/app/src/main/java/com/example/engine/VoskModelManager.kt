package com.example.engine

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

object VoskModelManager {
    const val VOSK_MODEL_DIR_NAME = "vosk-model"
    const val VOSK_DOWNLOAD_URL =
        "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun getModelDir(context: Context): File {
        return File(context.applicationContext.filesDir, VOSK_MODEL_DIR_NAME)
    }

    /**
     * Resolves the actual directory containing `am/final.mdl` and `conf/mfcc.conf`,
     * even if the zip archive unpacked into a nested folder such as
     * `filesDir/vosk-model/vosk-model-small-en-in-0.4/`.
     */
    fun resolveActualVoskModelDir(context: Context): File {
        val baseDir = getModelDir(context)
        if (!baseDir.exists() || !baseDir.isDirectory) return baseDir

        if (hasRequiredAcousticFiles(baseDir)) {
            return baseDir
        }

        // Check 1-level and 2-level nested directories
        val subDirs = baseDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        for (sub in subDirs) {
            if (hasRequiredAcousticFiles(sub)) {
                return sub
            }
            val nestedSubDirs = sub.listFiles()?.filter { it.isDirectory } ?: emptyList()
            for (nested in nestedSubDirs) {
                if (hasRequiredAcousticFiles(nested)) {
                    return nested
                }
            }
        }
        return baseDir
    }

    /**
     * Strictly verifies that `am/final.mdl` and `conf/mfcc.conf` exist inside the resolved Vosk model folder.
     */
    fun isVoskModelInstalled(context: Context): Boolean {
        val actualDir = resolveActualVoskModelDir(context)
        return hasRequiredAcousticFiles(actualDir)
    }

    private fun hasRequiredAcousticFiles(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        val finalMdl = File(dir, "am/final.mdl").exists() || File(dir, "final.mdl").exists()
        val mfccConf = File(dir, "conf/mfcc.conf").exists() || File(dir, "mfcc.conf").exists()
        return finalMdl && mfccConf
    }

    /**
     * Downloads `vosk-model-small-en-in-0.4.zip` (36 MB) with live progress callback
     * and unzips directly into `filesDir/vosk-model` for 100% offline use forever after.
     */
    suspend fun downloadAndInstallVoskModel(
        context: Context,
        onProgress: (percent: Int, status: String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val tempZip = File(appContext.cacheDir, "vosk-model-download.zip")
        try {
            onProgress(1, "Connecting to alphacephei.com (36 MB Voice Pack)...")
            val request = Request.Builder()
                .url(VOSK_DOWNLOAD_URL)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("HTTP ${response.code}: Failed to download Vosk pack")
                    )
                }
                val body = response.body
                    ?: return@withContext Result.failure(IllegalStateException("Empty response body"))

                val contentLength = body.contentLength().takeIf { it > 0 } ?: (36L * 1024L * 1024L)
                var totalRead = 0L

                body.byteStream().use { input ->
                    FileOutputStream(tempZip).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var read: Int
                        var lastReportedPct = -1
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            totalRead += read
                            val pct = ((totalRead * 85L) / contentLength).toInt().coerceIn(1, 85)
                            if (pct != lastReportedPct) {
                                lastReportedPct = pct
                                val downloadedMb = totalRead / (1024 * 1024)
                                val totalMb = contentLength / (1024 * 1024)
                                onProgress(pct, "Downloading Voice Pack: $downloadedMb / $totalMb MB ($pct%)")
                            }
                        }
                        output.flush()
                    }
                }
            }

            onProgress(88, "Extracting offline acoustic model into filesDir/vosk-model...")
            val modelDir = getModelDir(appContext)
            FileInputStream(tempZip).use { fis ->
                extractZipIntoTargetDir(fis, modelDir) { status ->
                    onProgress(94, status)
                }
            }
            tempZip.delete()

            val resolvedDir = resolveActualVoskModelDir(appContext)
            if (!hasRequiredAcousticFiles(resolvedDir)) {
                return@withContext Result.failure(
                    IllegalStateException("Extracted archive is missing am/final.mdl or conf/mfcc.conf")
                )
            }

            onProgress(100, "Vosk Offline Ear Ready (${resolvedDir.name})")
            Result.success(resolvedDir)
        } catch (e: Exception) {
            tempZip.delete()
            Result.failure(e)
        }
    }

    /**
     * Allows picking a local `vosk-model-*.zip` from device storage completely offline.
     */
    suspend fun importLocalVoskZip(
        context: Context,
        uri: Uri,
        onProgress: (percent: Int, status: String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        try {
            onProgress(15, "Reading local Vosk .zip archive...")
            val modelDir = getModelDir(appContext)
            val inputStream = appContext.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(IllegalStateException("Unable to open selected .zip file"))

            inputStream.use { stream ->
                extractZipIntoTargetDir(stream, modelDir) { status ->
                    onProgress(75, status)
                }
            }

            val resolvedDir = resolveActualVoskModelDir(appContext)
            if (!hasRequiredAcousticFiles(resolvedDir)) {
                return@withContext Result.failure(
                    IllegalStateException("Selected .zip is not a valid Vosk model (missing am/final.mdl)")
                )
            }

            onProgress(100, "Local Vosk Voice Pack installed!")
            Result.success(resolvedDir)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractZipIntoTargetDir(
        rawStream: InputStream,
        targetDir: File,
        onEntryStatus: (String) -> Unit
    ) {
        val stagingDir = File(targetDir.parentFile, "vosk-model-staging")
        if (stagingDir.exists()) stagingDir.deleteRecursively()
        stagingDir.mkdirs()

        ZipInputStream(BufferedInputStream(rawStream)).use { zis ->
            var entry = zis.nextEntry
            var count = 0
            while (entry != null) {
                val safeName = entry.name.removePrefix("/").trim()
                if (safeName.isNotEmpty() && !safeName.contains("..")) {
                    val outFile = File(stagingDir, safeName)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            val buf = ByteArray(16 * 1024)
                            var len: Int
                            while (zis.read(buf).also { len = it } > 0) {
                                fos.write(buf, 0, len)
                            }
                        }
                        count++
                        if (count % 5 == 0) {
                            onEntryStatus("Unpacking acoustic files ($count extracted)...")
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // Recursively locate the directory that actually contains am/final.mdl
        val effectiveRoot = findAcousticRootRecursive(stagingDir) ?: stagingDir

        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
        targetDir.mkdirs()

        effectiveRoot.copyRecursively(targetDir, overwrite = true)
        stagingDir.deleteRecursively()
    }

    private fun findAcousticRootRecursive(dir: File): File? {
        if (hasRequiredAcousticFiles(dir)) return dir
        val children = dir.listFiles()?.filter { it.isDirectory } ?: return null
        for (child in children) {
            val found = findAcousticRootRecursive(child)
            if (found != null) return found
        }
        return null
    }
}
