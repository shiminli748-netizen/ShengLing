package com.shengling.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 基于 [AudioTrack]（流式模式）的音频播放器。
 *
 * 支持直接播放 FloatArray 或加载 WAV 文件播放，并提供暂停/恢复、停止、
 * 以及播放进度回调（供 UI 更新进度条与时长）。
 */
class AudioPlayer {

    companion object {
        private const val TAG = "AudioPlayer"
        private const val SAMPLE_RATE = 22050
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var isPlaying: Boolean = false
        private set

    @Volatile
    var isPaused: Boolean = false
        private set

    private var audioTrack: AudioTrack? = null
    private var playJob: Job? = null
    private var samples: FloatArray = FloatArray(0)
    private var sampleRate: Int = SAMPLE_RATE
    private var writtenFrames: Int = 0

    private val cancelled = AtomicBoolean(false)

    /** 播放进度回调（positionMs / durationMs）。 */
    var onProgress: ((positionMs: Int, durationMs: Int) -> Unit)? = null

    /** 播放完成回调。 */
    var onCompletion: (() -> Unit)? = null

    /**
     * 播放一段音频采样。
     *
     * @param samples 音频采样（[-1,1]）
     * @param sampleRate 采样率
     */
    fun play(samples: FloatArray, sampleRate: Int) {
        stop()
        this.samples = samples
        this.sampleRate = sampleRate
        this.writtenFrames = 0
        cancelled.set(false)
        startPlayback()
    }

    /**
     * 加载 WAV 文件并播放。
     */
    fun play(path: String) {
        stop()
        cancelled.set(false)
        val loaded = AudioUtils.loadWav(path)
        val info = AudioUtils.getWavInfo(path)
        this.samples = loaded
        this.sampleRate = info.sampleRate
        this.writtenFrames = 0
        startPlayback()
    }

    private fun startPlayback() {
        if (samples.isEmpty()) {
            Log.w(TAG, "待播放的样本为空")
            return
        }

        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBuf * 2, 4098)

        audioTrack = try {
            AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(audioFormat)
                    .build(),
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
        } catch (t: Throwable) {
            Log.e(TAG, "创建 AudioTrack 失败", t)
            return
        }

        val track = audioTrack!!
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack 初始化失败")
            track.release()
            audioTrack = null
            return
        }

        isPlaying = true
        isPaused = false
        track.play()

        val totalFrames = samples.size
        val totalMs = if (sampleRate > 0) (totalFrames * 1000 / sampleRate) else 0

        playJob = scope.launch {
            val chunkFrames = 1024
            while (isActive && !cancelled.get() && writtenFrames < totalFrames) {
                if (isPaused) {
                    // 暂停时短暂休眠以避免忙等
                    kotlinx.coroutines.delay(50)
                    continue
                }

                val end = minOf(writtenFrames + chunkFrames, totalFrames)
                val len = end - writtenFrames
                val shortBuf = ShortArray(len)
                for (i in 0 until len) {
                    shortBuf[i] = (samples[writtenFrames + i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                }
                // 直接写入 short[]（16-bit PCM，阻塞写入）
                track.write(shortBuf, 0, len)
                writtenFrames = end

                val posMs = if (sampleRate > 0) (writtenFrames * 1000 / sampleRate) else 0
                onProgress?.invoke(posMs, totalMs)
            }

            isPlaying = false
            isPaused = false
            try {
                if (track.playState != AudioTrack.PLAYSTATE_STOPPED) {
                    track.stop()
                }
                track.release()
            } catch (t: Throwable) {
                Log.w(TAG, "释放 AudioTrack 时异常", t)
            }
            audioTrack = null
            if (!cancelled.get()) {
                onProgress?.invoke(totalMs, totalMs)
                onCompletion?.invoke()
            }
        }
    }

    /** 暂停播放。 */
    fun pause() {
        if (!isPlaying) return
        isPaused = true
        try {
            audioTrack?.pause()
        } catch (t: Throwable) {
            Log.w(TAG, "暂停失败", t)
        }
    }

    /** 恢复播放。 */
    fun resume() {
        if (!isPlaying || !isPaused) return
        isPaused = false
        try {
            audioTrack?.play()
        } catch (t: Throwable) {
            Log.w(TAG, "恢复失败", t)
        }
    }

    /** 停止播放。 */
    fun stop() {
        cancelled.set(true)
        isPlaying = false
        isPaused = false
        playJob?.cancel()
        playJob = null
        try {
            if (audioTrack?.playState != AudioTrack.PLAYSTATE_STOPPED) {
                audioTrack?.stop()
            }
            audioTrack?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "停止时异常", t)
        }
        audioTrack = null
        writtenFrames = 0
    }

    /** 释放资源。 */
    fun release() {
        stop()
        scope.cancel()
    }
}
