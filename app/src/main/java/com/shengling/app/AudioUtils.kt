package com.shengling.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 音频处理工具集。
 *
 * 提供纯 Kotlin 实现的 WAV 读写、重采样、梅尔频谱计算与静音裁剪等功能，
 * 供 [CloneEngine] 在提取声纹前对参考音频进行预处理。
 */
object AudioUtils {

    private const val TAG = "AudioUtils"

    // ===================== WAV 读取 =====================

    /**
     * 加载 WAV 文件并返回单声道 FloatArray（范围 [-1,1]）。
     * 若文件为多声道，则取各声道均值合并为单声道。
     * 若 [targetSampleRate] 与文件采样率不同，会自动线性重采样。
     *
     * @param path WAV 文件路径
     * @param targetSampleRate 期望的输出采样率，0 表示不重采样
     */
    fun loadWav(path: String, targetSampleRate: Int = 0): FloatArray {
        val bytes = File(path).readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        require(readTag(buf, 0) == "RIFF") { "不是合法的 WAV 文件（缺少 RIFF 头）" }
        buf.int // chunk size，忽略
        require(readTag(buf, 8) == "WAVE") { "不是合法的 WAV 文件（缺少 WAVE 标识）" }

        var audioFormat = 1
        var channels = 1
        var sampleRate = 16000
        var bitsPerSample = 16
        var dataOffset = -1
        var dataLength = 0

        var pos = 12
        while (pos + 8 <= bytes.size) {
            buf.position(pos)
            val tag = readString(bytes, pos, 4)
            val chunkSize = buf.int
            val chunkStart = pos + 8
            when (tag) {
                "fmt " -> {
                    audioFormat = buf.short.toInt() and 0xFFFF
                    channels = buf.short.toInt() and 0xFFFF
                    sampleRate = buf.int
                    buf.int // byte rate
                    buf.short // block align
                    bitsPerSample = buf.short.toInt() and 0xFFFF
                }
                "data" -> {
                    dataOffset = chunkStart
                    dataLength = min(chunkSize, bytes.size - chunkStart)
                }
            }
            // 对齐到偶数边界
            val aligned = if (chunkSize and 1 != 0) chunkSize + 1 else chunkSize
            pos = chunkStart + aligned
        }

        if (dataOffset < 0) throw IllegalStateException("WAV 文件缺少 data 块")
        val bytesPerSample = bitsPerSample / 8
        val frameSize = bytesPerSample * channels
        val numFrames = dataLength / frameSize
        val mono = FloatArray(numFrames)

        val frameBuf = ByteBuffer.wrap(bytes, dataOffset, numFrames * frameSize)
            .order(ByteOrder.LITTLE_ENDIAN)
        when (bitsPerSample) {
            16 -> {
                for (i in 0 until numFrames) {
                    var sum = 0f
                    for (ch in 0 until channels) {
                        sum += frameBuf.short.toInt()
                    }
                    mono[i] = sum / channels / 32768f
                }
            }
            8 -> {
                for (i in 0 until numFrames) {
                    var sum = 0f
                    for (ch in 0 until channels) {
                        val u = frameBuf.get().toInt() and 0xFF
                        sum += (u - 128).toFloat()
                    }
                    mono[i] = sum / channels / 128f
                }
            }
            32 -> {
                if (audioFormat == 3) {
                    for (i in 0 until numFrames) {
                        var sum = 0f
                        for (ch in 0 until channels) {
                            sum += frameBuf.float
                        }
                        mono[i] = sum / channels
                    }
                } else {
                    for (i in 0 until numFrames) {
                        var sum = 0f
                        for (ch in 0 until channels) {
                            sum += frameBuf.int.toFloat()
                        }
                        mono[i] = sum / channels / 2147483648f
                    }
                }
            }
            else -> throw UnsupportedOperationException("不支持的位深: $bitsPerSample")
        }

        return if (targetSampleRate > 0 && targetSampleRate != sampleRate) {
            resample(mono, sampleRate, targetSampleRate)
        } else {
            mono
        }
    }

    private fun readTag(buf: ByteBuffer, position: Int): String {
        buf.position(position)
        val b = ByteArray(4)
        buf.get(b)
        return String(b)
    }

    private fun readString(bytes: ByteArray, offset: Int, len: Int): String {
        return String(bytes, offset, len)
    }

    // ===================== WAV 写入 =====================

    /**
     * 将 FloatArray（[-1,1]）保存为 16-bit PCM 单声道 WAV 文件。
     */
    fun saveWav(path: String, samples: FloatArray, sampleRate: Int) {
        val numSamples = samples.size
        val dataSize = numSamples * 2
        val totalSize = 44 + dataSize
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        buf.put("RIFF".toByteArray())
        buf.putInt(totalSize - 8)
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16) // PCM fmt chunk size
        buf.putShort(1) // PCM
        buf.putShort(1) // mono
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 2) // byte rate
        buf.putShort(2) // block align
        buf.putShort(16) // bits per sample
        buf.put("data".toByteArray())
        buf.putInt(dataSize)
        for (i in 0 until numSamples) {
            val clamped = samples[i].coerceIn(-1f, 1f)
            buf.putShort((clamped * 32767f).toInt().toShort())
        }
        File(path).writeBytes(buf.array())
    }

    // ===================== 重采样 =====================

    /**
     * 线性插值重采样。
     */
    fun resample(samples: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || samples.isEmpty()) return samples
        val ratio = toRate.toDouble() / fromRate.toDouble()
        val outLen = max(1, (samples.size * ratio).toInt())
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i / ratio
            val idx = srcPos.toInt()
            val frac = srcPos - idx
            val s0 = samples[idx.coerceIn(0, samples.size - 1)]
            val s1 = samples[(idx + 1).coerceIn(0, samples.size - 1)]
            out[i] = (s0 + (s1 - s0) * frac).toFloat()
        }
        return out
    }

    // ===================== 类型转换 =====================

    /** float [-1,1] -> short。 */
    fun toShortArray(floatSamples: FloatArray): ShortArray {
        val out = ShortArray(floatSamples.size)
        for (i in floatSamples.indices) {
            out[i] = (floatSamples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        return out
    }

    /** short -> float [-1,1]。 */
    fun toFloatArray(shortSamples: ShortArray): FloatArray {
        val out = FloatArray(shortSamples.size)
        for (i in shortSamples.indices) {
            out[i] = shortSamples[i].toInt() / 32768f
        }
        return out
    }

    // ===================== 时长与静音裁剪 =====================

    /** 返回音频时长（秒）。 */
    fun detectDuration(samples: FloatArray, sampleRate: Int): Float {
        if (sampleRate <= 0) return 0f
        return samples.size.toFloat() / sampleRate.toFloat()
    }

    /**
     * 裁剪首尾静音。
     * @param threshold 低于该绝对幅值视为静音。
     */
    fun trimSilence(samples: FloatArray, sampleRate: Int, threshold: Float = 0.01f): FloatArray {
        if (samples.isEmpty()) return samples
        var start = 0
        while (start < samples.size && kotlin.math.abs(samples[start]) < threshold) start++
        var end = samples.size - 1
        while (end > start && kotlin.math.abs(samples[end]) < threshold) end--
        if (end <= start) return samples
        return samples.copyOfRange(start, end + 1)
    }

    // ===================== 梅尔频谱 =====================

    /**
     * 计算梅尔频谱图。
     *
     * 采用 center=True（前后各 reflect pad nFft//2）、Hann 窗、功率谱、三角梅尔滤波器组。
     *
     * @return 形状 [nMels, timeSteps]、按行优先展开的 FloatArray（mel[melIdx * timeSteps + t]）。
     */
    fun computeMelSpectrogram(
        samples: FloatArray,
        sampleRate: Int,
        nFft: Int,
        hopLength: Int,
        winLength: Int,
        nMels: Int,
        fmin: Float,
        fmax: Float
    ): FloatArray {
        require(nFft and (nFft - 1) == 0) { "nFft 必须是 2 的幂，当前为 $nFft" }
        require(samples.isNotEmpty()) { "音频样本为空" }

        val pad = nFft / 2
        val padded = reflectPad(samples, pad)
        val window = hannWindow(winLength)
        val numFrames = 1 + max(0, (padded.size - winLength) / hopLength)
        val nFreqs = nFft / 2 + 1
        val melFilter = melFilterBank(sampleRate, nFft, nMels, fmin, fmax)

        val mel = FloatArray(nMels * numFrames)
        val real = FloatArray(nFft)
        val imag = FloatArray(nFft)

        for (frame in 0 until numFrames) {
            val start = frame * hopLength
            java.util.Arrays.fill(real, 0f)
            java.util.Arrays.fill(imag, 0f)
            val len = min(winLength, padded.size - start)
            for (j in 0 until len) {
                real[j] = padded[start + j] * window[j]
            }
            fft(real, imag, nFft)
            val power = FloatArray(nFreqs)
            for (k in 0 until nFreqs) {
                val re = real[k]
                val im = imag[k]
                power[k] = re * re + im * im
            }
            for (m in 0 until nMels) {
                var sum = 0f
                val base = m * nFreqs
                for (k in 0 until nFreqs) {
                    sum += power[k] * melFilter[base + k]
                }
                mel[m * numFrames + frame] = sum
            }
        }

        return mel
    }

    /**
     * 对梅尔频谱做 log 归一化。
     * 先做 log(1 + mel)，再做均值方差归一化。
     */
    fun normalizeMel(mel: FloatArray): FloatArray {
        if (mel.isEmpty()) return mel
        val logged = FloatArray(mel.size)
        for (i in mel.indices) {
            logged[i] = ln(1f + mel[i].coerceAtLeast(0f))
        }
        var mean = 0f
        for (v in logged) mean += v
        mean /= logged.size
        var varSum = 0f
        for (v in logged) varSum += (v - mean) * (v - mean)
        val std = sqrt(varSum / logged.size).coerceAtLeast(1e-8f)
        val out = FloatArray(logged.size)
        for (i in logged.indices) {
            out[i] = (logged[i] - mean) / std
        }
        return out
    }

    // ===================== 内部 DSP 工具 =====================

    /** 反射填充（前后各 pad 个样本）。 */
    private fun reflectPad(samples: FloatArray, pad: Int): FloatArray {
        if (pad <= 0) return samples
        val out = FloatArray(samples.size + 2 * pad)
        for (i in 0 until pad) {
            val idx = (pad - i).coerceIn(0, samples.size - 1)
            out[i] = samples[idx]
        }
        System.arraycopy(samples, 0, out, pad, samples.size)
        for (i in 0 until pad) {
            val idx = (samples.size - 2 - i).coerceIn(0, samples.size - 1)
            out[pad + samples.size + i] = samples[idx]
        }
        return out
    }

    /** 汉宁窗。 */
    private fun hannWindow(length: Int): FloatArray {
        val win = FloatArray(length)
        if (length == 1) {
            win[0] = 1f
            return win
        }
        for (i in 0 until length) {
            win[i] = 0.5f * (1f - cos(2f * PI.toFloat() * i / (length - 1).toFloat()))
        }
        return win
    }

    /** 迭代 radix-2 Cooley-Tukey FFT（原地）。 */
    private fun fft(real: FloatArray, imag: FloatArray, n: Int) {
        var j = 0
        var i = 1
        while (i < n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = real[i]; real[i] = real[j]; real[j] = t
                t = imag[i]; imag[i] = imag[j]; imag[j] = t
            }
            i++
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            var k = 0
            while (k < n) {
                var curRe = 1f
                var curIm = 0f
                for (m in 0 until len / 2) {
                    val idx1 = k + m
                    val idx2 = k + m + len / 2
                    val tRe = curRe * real[idx2] - curIm * imag[idx2]
                    val tIm = curRe * imag[idx2] + curIm * real[idx2]
                    real[idx2] = real[idx1] - tRe
                    imag[idx2] = imag[idx1] - tIm
                    real[idx1] = real[idx1] + tRe
                    imag[idx1] = imag[idx1] + tIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                k += len
            }
            len = len shl 1
        }
    }

    /**
     * 构造梅尔三角滤波器组 [nMels, nFreqs]，行优先展开。
     */
    private fun melFilterBank(
        sampleRate: Int,
        nFft: Int,
        nMels: Int,
        fmin: Float,
        fmax: Float
    ): FloatArray {
        val nFreqs = nFft / 2 + 1
        val fftFreqs = FloatArray(nFreqs) { it * (sampleRate.toFloat() / nFft.toFloat()) }

        val melMin = hzToMel(fmin)
        val melMax = hzToMel(fmax.coerceAtMost(sampleRate / 2f))
        val melPoints = FloatArray(nMels + 2) { melMin + (melMax - melMin) * it / (nMels + 1).toFloat() }
        val hzPoints = FloatArray(nMels + 2) { melToHz(melPoints[it]) }

        val filter = FloatArray(nMels * nFreqs)
        for (m in 0 until nMels) {
            val left = hzPoints[m]
            val center = hzPoints[m + 1]
            val right = hzPoints[m + 2]
            val base = m * nFreqs
            for (k in 0 until nFreqs) {
                val f = fftFreqs[k]
                var weight = 0f
                if (f in left..center && center > left) {
                    weight = (f - left) / (center - left)
                } else if (f in center..right && right > center) {
                    weight = (right - f) / (right - center)
                }
                filter[base + k] = weight.coerceAtLeast(0f)
            }
        }
        return filter
    }

    /** Hz -> Mel（Slaney 公式）。 */
    private fun hzToMel(hz: Float): Float {
        val fMin = 0f
        val fSp = 200f / 3f
        var mel = (hz - fMin) / fSp
        val minLogHz = 1000f
        val minLogMel = (minLogHz - fMin) / fSp
        val logStep = ln(6.4f) / 27f
        if (hz >= minLogHz) {
            mel = minLogMel + ln(hz / minLogHz) / logStep
        }
        return mel
    }

    /** Mel -> Hz（Slaney 公式）。 */
    private fun melToHz(mel: Float): Float {
        val fMin = 0f
        val fSp = 200f / 3f
        val minLogHz = 1000f
        val minLogMel = (minLogHz - fMin) / fSp
        val logStep = ln(6.4f) / 27f
        return if (mel >= minLogMel) {
            minLogHz * kotlin.math.exp(logStep * (mel - minLogMel))
        } else {
            fMin + fSp * mel
        }
    }

    // ===================== 通用音频信息 =====================

    /**
     * 使用 [MediaMetadataRetriever] 获取任意音频文件的时长（毫秒）。
     * 支持 MP3, M4A, AAC, OGG, WAV, AMR, FLAC 等格式。
     */
    fun getAudioDurationMs(path: String): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(path)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            durationStr?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "MediaMetadataRetriever 获取时长失败: ${e.message}")
            0L
        }
    }

    /**
     * 使用 [MediaMetadataRetriever] 通过 Uri 获取音频时长（毫秒）。
     */
    fun getAudioDurationMs(context: android.content.Context, uri: Uri): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            durationStr?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "MediaMetadataRetriever(Uri) 获取时长失败: ${e.message}")
            0L
        }
    }

    /**
     * 检测文件是否为 WAV 格式（检查 RIFF 头）。
     */
    fun isWavFile(path: String): Boolean {
        return try {
            val bytes = File(path).readBytes()
            bytes.size >= 12 &&
                    String(bytes, 0, 4) == "RIFF" &&
                    String(bytes, 8, 4) == "WAVE"
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 将任意音频格式（MP3/M4A/AAC/OGG 等）解码为 16kHz 单声道 16-bit PCM WAV。
     * 使用 [MediaExtractor] + [MediaCodec] 硬件解码。
     *
     * @param inputPath 输入音频路径
     * @param outputPath 输出 WAV 文件路径
     * @param targetSampleRate 目标采样率（默认 16000）
     * @return 是否成功
     */
    fun decodeToWav(inputPath: String, outputPath: String, targetSampleRate: Int = 16000): Boolean {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(inputPath)

            // 查找音频轨道
            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    inputFormat = format
                    break
                }
            }
            if (audioTrackIndex < 0 || inputFormat == null) {
                Log.e(TAG, "未找到音频轨道")
                return false
            }

            val inputMime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            val inputSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val inputChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            extractor.selectTrack(audioTrackIndex)
            codec = MediaCodec.createDecoderByType(inputMime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            val pcmSamples = mutableListOf<Short>()
            val timeoutUs = 10000L

            // 解码循环
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                // 输入
                if (!inputDone) {
                    val inputBufIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inputBufIndex >= 0) {
                        val inputBuf = codec.getInputBuffer(inputBufIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputBufIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputBufIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // 输出
                val outputBufIndex = codec.dequeueOutputBuffer(info, timeoutUs)
                if (outputBufIndex >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                    if (info.size > 0) {
                        val outputBuf = codec.getOutputBuffer(outputBufIndex)!!
                        val chunk = ShortArray(info.size / 2)
                        outputBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(chunk)
                        // 如果是多声道，取各声道均值合并为单声道
                        if (inputChannels > 1) {
                            val monoLen = chunk.size / inputChannels
                            val mono = ShortArray(monoLen)
                            for (i in 0 until monoLen) {
                                var sum = 0
                                for (ch in 0 until inputChannels) {
                                    sum += chunk[i * inputChannels + ch].toInt()
                                }
                                mono[i] = (sum / inputChannels).toShort()
                            }
                            pcmSamples.addAll(mono.toList())
                        } else {
                            pcmSamples.addAll(chunk.toList())
                        }
                    }
                    codec.releaseOutputBuffer(outputBufIndex, false)
                }
            }

            // 转为 FloatArray
            val floatSamples = FloatArray(pcmSamples.size) { pcmSamples[it].toFloat() / 32768f }

            // 重采样
            val finalSamples = if (inputSampleRate != targetSampleRate) {
                resample(floatSamples, inputSampleRate, targetSampleRate)
            } else {
                floatSamples
            }

            // 写入 WAV
            saveWav(outputPath, finalSamples, targetSampleRate)
            Log.i(TAG, "音频解码完成: $inputPath -> $outputPath, 采样率=$targetSampleRate, 采样数=${finalSamples.size}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "音频解码失败: ${e.message}", e)
            return false
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    /**
     * 确保 [path] 指向一个 WAV 文件。如果不是 WAV，自动解码转码。
     * 返回最终的 WAV 文件路径（可能是原路径，也可能是新转换的路径）。
     */
    fun ensureWav(path: String, outputDir: File, targetSampleRate: Int = 16000): String {
        if (isWavFile(path)) return path
        val outputPath = File(outputDir, "converted_${System.currentTimeMillis()}.wav").absolutePath
        return if (decodeToWav(path, outputPath, targetSampleRate)) outputPath else path
    }

    // ===================== WAV 信息 =====================

    /**
     * 获取 WAV 文件信息（采样率、声道数、时长秒），用于 UI 展示。
     */
    fun getWavInfo(path: String): WavInfo {
        val bytes = File(path).readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(readTag(buf, 0) == "RIFF") { "不是合法的 WAV 文件" }
        buf.int // chunk size
        require(readTag(buf, 8) == "WAVE") { "不是合法的 WAV 文件" }

        var sampleRate = 16000
        var channels = 1
        var bitsPerSample = 16
        var dataLen = 0L

        var pos = 12
        while (pos + 8 <= bytes.size) {
            buf.position(pos)
            val tag = readString(bytes, pos, 4)
            val chunkSize = buf.int
            val chunkStart = pos + 8
            when (tag) {
                "fmt " -> {
                    buf.short // audioFormat
                    channels = buf.short.toInt() and 0xFFFF
                    sampleRate = buf.int
                    buf.int // byte rate
                    buf.short // block align
                    bitsPerSample = buf.short.toInt() and 0xFFFF
                }
                "data" -> {
                    dataLen = min(chunkSize.toLong(), (bytes.size - chunkStart).toLong())
                }
            }
            val aligned = if (chunkSize and 1 != 0) chunkSize + 1 else chunkSize
            pos = chunkStart + aligned
        }
        val bytesPerSample = bitsPerSample / 8
        val numFrames = if (bytesPerSample > 0 && channels > 0) dataLen / (bytesPerSample * channels) else 0L
        val duration = if (sampleRate > 0) numFrames.toFloat() / sampleRate.toFloat() else 0f
        return WavInfo(sampleRate, channels, bitsPerSample, duration)
    }

    /** WAV 文件元信息。 */
    data class WavInfo(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val durationSec: Float
    )
}
