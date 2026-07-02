package com.shengling.app

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 基于 [AudioRecord] 的录音器。
 *
 * 以 16kHz、单声道、16-bit PCM 采集音频，边录边累加到内存缓冲，
 * 录音结束时统一写出为 WAV 文件。同时通过 [onAmplitude] 回调实时输出归一化音量，
 * 供 UI 绘制波形。
 */
class AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_SIZE = 1024
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var isRecording: Boolean = false
        private set

    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null
    private val chunks = ArrayList<ShortArray>()
    private var totalSamples = 0

    /** 实时音量回调（0..1），仅在录音过程中触发。 */
    var onAmplitude: ((Float) -> Unit)? = null

    /**
     * 开始录音。
     *
     * @param outputPath 最终写入的 WAV 路径
     * @return true 表示成功开始
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(outputPath: String): Boolean {
        if (isRecording) {
            Log.w(TAG, "已经在录音中")
            return false
        }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuf <= 0) {
            Log.e(TAG, "获取最小缓冲区失败: $minBuf")
            return false
        }
        val bufferSize = maxOf(minBuf * 2, CHUNK_SIZE * 4)

        audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
        } catch (t: Throwable) {
            Log.e(TAG, "创建 AudioRecord 失败", t)
            return false
        }

        val record = audioRecord!!
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 初始化失败")
            record.release()
            audioRecord = null
            return false
        }

        chunks.clear()
        totalSamples = 0
        record.startRecording()
        isRecording = true

        recordJob = scope.launch {
            val buffer = ShortArray(CHUNK_SIZE)
            while (isActive && isRecording) {
                val read = record.read(buffer, 0, CHUNK_SIZE)
                if (read > 0) {
                    val copy = buffer.copyOf(read)
                    synchronized(chunks) {
                        chunks.add(copy)
                        totalSamples += read
                    }
                    // 计算归一化音量
                    var maxVal = 0
                    for (i in 0 until read) {
                        val v = kotlin.math.abs(buffer[i].toInt())
                        if (v > maxVal) maxVal = v
                    }
                    onAmplitude?.invoke((maxVal / 32767f).coerceIn(0f, 1f))
                } else if (read < 0) {
                    Log.e(TAG, "AudioRecord 读取错误: $read")
                    break
                }
            }
        }

        outputPathHolder = outputPath
        Log.i(TAG, "开始录音 -> $outputPath")
        return true
    }

    private var outputPathHolder: String = ""

    /**
     * 停止录音并写出 WAV 文件。
     *
     * @return WAV 文件路径，失败返回 null
     */
    fun stop(): String? {
        if (!isRecording) return null
        isRecording = false
        recordJob?.cancel()
        recordJob = null

        val record = audioRecord
        audioRecord = null
        try {
            if (record?.state == AudioRecord.STATE_INITIALIZED) {
                record.stop()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "停止 AudioRecord 时异常", t)
        } finally {
            record?.release()
        }

        // 合并缓冲并写 WAV
        val samples: ShortArray
        synchronized(chunks) {
            samples = ShortArray(totalSamples)
            var offset = 0
            for (chunk in chunks) {
                System.arraycopy(chunk, 0, samples, offset, chunk.size)
                offset += chunk.size
            }
            chunks.clear()
        }

        if (samples.isEmpty()) {
            Log.w(TAG, "录制的样本为空")
            return null
        }

        val floatSamples = AudioUtils.toFloatArray(samples)
        val outPath = outputPathHolder
        AudioUtils.saveWav(outPath, floatSamples, SAMPLE_RATE)
        Log.i(TAG, "录音已保存: $outPath, 样本数=${samples.size}")
        return outPath
    }

    /** 取消录音，不写出文件。 */
    fun cancel() {
        if (!isRecording) return
        isRecording = false
        recordJob?.cancel()
        recordJob = null
        try {
            audioRecord?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "取消时停止异常", t)
        }
        audioRecord?.release()
        audioRecord = null
        synchronized(chunks) { chunks.clear(); totalSamples = 0 }
    }

    /** 释放所有资源。 */
    fun release() {
        cancel()
        scope.cancel()
    }
}
