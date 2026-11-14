package com.example.engine

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

object PcmAudioRecorder {
    const val SAMPLE_RATE_HZ = 16000
    const val RECORD_DURATION_SECONDS = 5
    const val TOTAL_SAMPLES = SAMPLE_RATE_HZ * RECORD_DURATION_SECONDS // 80,000 samples (160 KB)

    @Volatile
    private var activeRecorder: AudioRecord? = null

    /**
     * Records 16kHz, Mono, 16-bit PCM audio for exactly 5 seconds into a ShortArray buffer.
     */
    @SuppressLint("MissingPermission")
    suspend fun recordFiveSecondsPcm16(
        onProgressSecond: (elapsedSec: Int) -> Unit = {}
    ): ShortArray = withContext(Dispatchers.IO) {
        val minBufBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE_HZ * 2)

        val pcmBuffer = ShortArray(TOTAL_SAMPLES)
        var recorder: AudioRecord? = null

        try {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufBytes * 2
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return@withContext ShortArray(0)
            }

            activeRecorder = recorder
            recorder.startRecording()

            var offset = 0
            val chunkSize = 1600 // 100ms chunks at 16kHz
            var lastSec = -1

            while (offset < TOTAL_SAMPLES && isActive) {
                val toRead = minOf(chunkSize, TOTAL_SAMPLES - offset)
                val readCount = recorder.read(pcmBuffer, offset, toRead)
                if (readCount > 0) {
                    offset += readCount
                    val elapsedSec = offset / SAMPLE_RATE_HZ
                    if (elapsedSec != lastSec) {
                        lastSec = elapsedSec
                        onProgressSecond(elapsedSec)
                    }
                } else {
                    break
                }
            }

            try {
                recorder.stop()
            } catch (_: Throwable) {
            }

            if (offset == TOTAL_SAMPLES) {
                pcmBuffer
            } else if (offset > 0) {
                pcmBuffer.copyOf(offset)
            } else {
                ShortArray(0)
            }
        } catch (_: Throwable) {
            ShortArray(0)
        } finally {
            activeRecorder = null
            try {
                recorder?.release()
            } catch (_: Throwable) {
            }
        }
    }

    fun cancelActiveRecording() {
        try {
            activeRecorder?.stop()
        } catch (_: Throwable) {
        }
    }
}
