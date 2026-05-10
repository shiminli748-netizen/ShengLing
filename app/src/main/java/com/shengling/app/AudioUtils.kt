package com.shengling.app

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream

object AudioUtils {
    private const val TAG = "AudioUtils"
    private const val SAMPLE_RATE = 16000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var mediaPlayer: MediaPlayer? = null

    fun startRecording(outputFile: File, onAmplitude: ((Float) -> Unit)? = null): Boolean {
        try {
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized")
                return false
            }

            audioRecord?.startRecording()
            isRecording = true

            Thread {
                val data = ByteArrayOutputStream()
                val buffer = ShortArray(bufferSize)

                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        for (i in 0 until read) {
                            val sample = buffer[i]
                            data.write(sample.toInt() and 0xFF)
                            data.write((sample.toInt() shr 8) and 0xFF)
                        }
                        if (onAmplitude != null) {
                            var maxAmp = 0
                            for (i in 0 until read) {
                                val amp = Math.abs(buffer[i].toInt())
                                if (amp > maxAmp) maxAmp = amp
                            }
                            val normalized = maxAmp.toFloat() / Short.MAX_VALUE
                            onAmplitude(normalized)
                        }
                    }
                }

                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null

                writeWavFile(outputFile, data.toByteArray(), SAMPLE_RATE)
            }.start()

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            return false
        }
    }

    fun stopRecording() {
        isRecording = false
    }

    fun isCurrentlyRecording(): Boolean = isRecording

    fun loadAudioFromFile(context: Context, uri: Uri): Pair<FloatArray, Int>? {
        return try {
            val tempFile = File(context.cacheDir, "temp_audio_input.wav")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            val samples = loadWavFile(tempFile)
            tempFile.delete()
            samples
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load audio from file", e)
            null
        }
    }

    fun loadWavFile(file: File): Pair<FloatArray, Int>? {
        return try {
            val bytes = file.readBytes()
            if (bytes.size < 44) return null

            val sampleRate = ((bytes[27].toInt() and 0xFF) shl 24) or
                    ((bytes[26].toInt() and 0xFF) shl 16) or
                    ((bytes[25].toInt() and 0xFF) shl 8) or
                    (bytes[24].toInt() and 0xFF)

            val bitsPerSample = ((bytes[35].toInt() and 0xFF) shl 8) or
                    (bytes[34].toInt() and 0xFF)

            val numChannels = ((bytes[23].toInt() and 0xFF) shl 8) or
                    (bytes[22].toInt() and 0xFF)

            val dataSize = ((bytes[43].toInt() and 0xFF) shl 24) or
                    ((bytes[42].toInt() and 0xFF) shl 16) or
                    ((bytes[41].toInt() and 0xFF) shl 8) or
                    (bytes[40].toInt() and 0xFF)

            val bytesPerSample = bitsPerSample / 8
            val numSamples = dataSize / bytesPerSample

            val samples = FloatArray(numSamples / numChannels)
            var sampleIndex = 0

            for (i in 0 until numSamples step numChannels) {
                val offset = 44 + i * bytesPerSample
                if (offset + bytesPerSample > bytes.size) break

                val sample = when (bitsPerSample) {
                    16 -> {
                        val s = ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                                (bytes[offset].toInt() and 0xFF)
                        (if (s > Short.MAX_VALUE) s - 65536 else s).toFloat() / Short.MAX_VALUE
                    }
                    8 -> {
                        ((bytes[offset].toInt() and 0xFF) - 128).toFloat() / 128f
                    }
                    32 -> {
                        val bits = ((bytes[offset + 3].toInt() and 0xFF) shl 24) or
                                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                                (bytes[offset].toInt() and 0xFF)
                        Float.fromBits(bits)
                    }
                    else -> 0f
                }
                if (sampleIndex < samples.size) {
                    samples[sampleIndex++] = sample
                }
            }

            Pair(samples.copyOf(sampleIndex), sampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load WAV file", e)
            null
        }
    }

    fun getAudioDuration(samples: FloatArray, sampleRate: Int): Float {
        return samples.size.toFloat() / sampleRate
    }

    fun playAudio(file: File, onCompletion: (() -> Unit)? = null) {
        stopPlayback()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                onCompletion?.invoke()
            }
            prepare()
            start()
        }
    }

    fun playSamples(samples: FloatArray, sampleRate: Int, outputFile: File, onCompletion: (() -> Unit)? = null) {
        savePcmAsWav(samples, sampleRate, outputFile)
        playAudio(outputFile, onCompletion)
    }

    fun stopPlayback() {
        mediaPlayer?.apply {
            try {
                if (isPlaying) stop()
                release()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping playback", e)
            }
        }
        mediaPlayer = null
    }

    fun savePcmAsWav(samples: FloatArray, sampleRate: Int, outputFile: File) {
        val pcmData = ShortArray(samples.size)
        for (i in samples.indices) {
            val clamped = samples[i].coerceIn(-1f, 1f)
            pcmData[i] = (clamped * Short.MAX_VALUE).toInt().toShort()
        }

        val dataSize = pcmData.size * 2
        val buffer = ByteArrayOutputStream()

        buffer.write("RIFF".toByteArray())
        writeInt32(buffer, 36 + dataSize)
        buffer.write("WAVE".toByteArray())
        buffer.write("fmt ".toByteArray())
        writeInt32(buffer, 16)
        writeInt16(buffer, 1)
        writeInt16(buffer, 1)
        writeInt32(buffer, sampleRate)
        writeInt32(buffer, sampleRate * 2)
        writeInt16(buffer, 2)
        writeInt16(buffer, 16)
        buffer.write("data".toByteArray())
        writeInt32(buffer, dataSize)

        for (sample in pcmData) {
            buffer.write(sample.toInt() and 0xFF)
            buffer.write((sample.toInt() shr 8) and 0xFF)
        }

        outputFile.writeBytes(buffer.toByteArray())
    }

    private fun writeWavFile(outputFile: File, pcmData: ByteArray, sampleRate: Int) {
        val dataSize = pcmData.size
        val buffer = ByteArrayOutputStream()

        buffer.write("RIFF".toByteArray())
        writeInt32(buffer, 36 + dataSize)
        buffer.write("WAVE".toByteArray())
        buffer.write("fmt ".toByteArray())
        writeInt32(buffer, 16)
        writeInt16(buffer, 1)
        writeInt16(buffer, 1)
        writeInt32(buffer, sampleRate)
        writeInt32(buffer, sampleRate * 2)
        writeInt16(buffer, 2)
        writeInt16(buffer, 16)
        buffer.write("data".toByteArray())
        writeInt32(buffer, dataSize)
        buffer.write(pcmData)

        outputFile.writeBytes(buffer.toByteArray())
    }

    private fun writeInt32(stream: ByteArrayOutputStream, value: Int) {
        stream.write(value and 0xFF)
        stream.write((value shr 8) and 0xFF)
        stream.write((value shr 16) and 0xFF)
        stream.write((value shr 24) and 0xFF)
    }

    private fun writeInt16(stream: ByteArrayOutputStream, value: Int) {
        stream.write(value and 0xFF)
        stream.write((value shr 8) and 0xFF)
    }
}
