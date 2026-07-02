package com.shengling.app

import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 声音克隆核心引擎，基于 sherpa-onnx PocketTTS。
 *
 * PocketTTS 是零样本（zero-shot）声音克隆模型：
 * - 不需要预先提取声纹向量
 * - 在 TTS 推理时直接传入参考音频，模型即时克隆该声音
 *
 * "克隆"步骤实际上是保存参考音频路径，
 * "合成"步骤将参考音频 + 文本一起送入 PocketTTS 生成语音。
 *
 * @param modelDir 模型文件所在目录
 */
class CloneEngine(
    private val modelDir: File
) {

    private val tag = "CloneEngine"

    /** PocketTTS 模型文件清单。 */
    private val pocketFiles = listOf(
        "lm_flow.int8.onnx",
        "lm_main.int8.onnx",
        "encoder.onnx",
        "decoder.int8.onnx",
        "text_conditioner.onnx",
        "vocab.json",
        "token_scores.json"
    )

    /** 参考音频存储目录（"克隆"的声纹实际就是保存的参考音频路径）。 */
    private val voicesDir: File by lazy {
        File(modelDir.parentFile, "voices").apply { if (!exists()) mkdirs() }
    }

    /** 检查所有 PocketTTS 模型文件是否就绪。 */
    fun isModelReady(): Boolean {
        return pocketFiles.all { File(modelDir, it).exists() }
    }

    /**
     * "提取声纹" —— PocketTTS 是零样本克隆，不需要单独提取声纹。
     * 这里只需保存参考音频路径作为"声纹"。
     *
     * @param audioPath 参考音频路径
     * @param name 声纹名称
     * @return 保存的参考音频路径
     */
    fun saveReferenceVoice(audioPath: String, name: String): String {
        val safeName = sanitizeName(name)
        val voiceDir = File(voicesDir, safeName).apply { if (!exists()) mkdirs() }
        val destFile = File(voiceDir, "reference.wav")

        // 确保是 WAV 格式，如果不是则转码
        val finalPath = if (AudioUtils.isWavFile(audioPath)) {
            audioPath
        } else {
            val converted = File(voiceDir, "reference_converted.wav").absolutePath
            if (AudioUtils.decodeToWav(audioPath, converted, 24000)) converted else audioPath
        }

        // 复制到声纹目录
        File(finalPath).copyTo(destFile, overwrite = true)

        // 写入元信息文件
        File(voiceDir, "meta.txt").writeText("name=$safeName\nsource=$audioPath\ncreated=${System.currentTimeMillis()}")

        Log.i(tag, "声纹已保存: ${destFile.absolutePath}")
        return destFile.absolutePath
    }

    /**
     * 使用参考音频的声音克隆合成语音。
     *
     * @param text 待合成文本
     * @param voiceName 声纹名称（对应保存的参考音频）
     * @param onProgress 进度回调（0..100）
     * @return 合成的音频采样（[-1,1]）和采样率
     */
    suspend fun synthesize(
        text: String,
        voiceName: String,
        onProgress: (Int) -> Unit = {}
    ): SynthesisResult = withContext(Dispatchers.Default) {
        require(text.isNotBlank()) { "文本不能为空" }
        onProgress(10)

        val safeName = sanitizeName(voiceName)
        val refAudioFile = File(voicesDir, "$safeName/reference.wav")
        require(refAudioFile.exists()) { "声纹不存在: $safeName，请先克隆声音" }
        onProgress(20)

        // 构造 PocketTTS 配置
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                pocket = OfflineTtsPocketModelConfig(
                    lmFlow = File(modelDir, "lm_flow.int8.onnx").absolutePath,
                    lmMain = File(modelDir, "lm_main.int8.onnx").absolutePath,
                    encoder = File(modelDir, "encoder.onnx").absolutePath,
                    decoder = File(modelDir, "decoder.int8.onnx").absolutePath,
                    textConditioner = File(modelDir, "text_conditioner.onnx").absolutePath,
                    vocabJson = File(modelDir, "vocab.json").absolutePath,
                    tokenScoresJson = File(modelDir, "token_scores.json").absolutePath,
                ),
                numThreads = 2,
                debug = false,
            ),
        )

        onProgress(40)

        // 创建 TTS 引擎
        val tts = OfflineTts(config = config)
        onProgress(55)

        // 读取参考音频
        val wave = WaveReader.readWave(filename = refAudioFile.absolutePath)
        Log.i(tag, "参考音频: ${wave.samples.size} samples, ${wave.sampleRate} Hz")
        onProgress(65)

        // 生成配置（零样本克隆）
        val genConfig = GenerationConfig(
            referenceAudio = wave.samples,
            referenceSampleRate = wave.sampleRate,
            numSteps = 5,
            extra = mapOf(
                "temperature" to "0.7",
                "chunk_size" to "15",
            )
        )

        onProgress(75)

        // 生成语音
        val audio = tts.generateWithConfigAndCallback(
            text = text,
            config = genConfig,
            callback = ::progressCallback
        )

        onProgress(90)

        val samples = audio.samples
        val sampleRate = audio.sampleRate
        Log.i(tag, "语音合成完成: ${samples.size} samples, ${sampleRate} Hz")

        tts.release()
        onProgress(100)

        SynthesisResult(samples, sampleRate)
    }

    private fun progressCallback(samples: FloatArray): Int {
        // 返回 1 继续生成，返回 0 停止
        return 1
    }

    /**
     * 列出已保存的声纹名称。
     */
    fun listSavedVoices(): List<String> {
        if (!voicesDir.exists()) return emptyList()
        return voicesDir.listFiles { f -> f.isDirectory }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    /** 判断某个声纹是否存在。 */
    fun hasVoice(name: String): Boolean =
        File(voicesDir, "${sanitizeName(name)}/reference.wav").exists()

    /** 释放资源。 */
    fun close() {
        // sherpa-onnx OfflineTts 在每次合成后自行 release
    }

    private fun sanitizeName(name: String): String {
        return name.trim().ifEmpty { "voice" }
            .replace(Regex("[^\\u4e00-\\u9fa5A-Za-z0-9_-]"), "_")
    }

    /** 合成结果。 */
    data class SynthesisResult(
        val samples: FloatArray,
        val sampleRate: Int
    )

    companion object {
        const val MIN_REF_SECONDS = 3f
        const val MAX_REF_SECONDS = 15f
    }
}
