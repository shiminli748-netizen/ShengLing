package com.shengling.app

import android.content.Context
import android.util.Log
import com.shengling.app.data.CloneStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 模型文件管理器（单例）。
 *
 * 由于 PocketTTS 模型文件总大小约 190MB，超过 APK 150MB 限制，
 * 因此模型文件不打包在 APK 内，改为首次启动时从 GitHub Release 下载。
 *
 * 下载地址: https://github.com/shiminli748-netizen/ShengLing/releases/download/v1.0.0/models/pocket/{filename}
 */
object ModelManager {

    private const val TAG = "ModelManager"

    /** GitHub Release 中模型文件的下载基础 URL。 */
    private const val MODEL_BASE_URL =
        "https://github.com/shiminli748-netizen/ShengLing/releases/download/v1.0.0"

    /** PocketTTS 需要的模型文件清单及大小（用于校验）。 */
    private val MODEL_FILES = listOf(
        ModelFile("lm_flow.int8.onnx", 9_600_000L),
        ModelFile("lm_main.int8.onnx", 73_000_000L),
        ModelFile("encoder.onnx", 70_000_000L),
        ModelFile("decoder.int8.onnx", 22_000_000L),
        ModelFile("text_conditioner.onnx", 16_000_000L),
        ModelFile("vocab.json", 68_000L),
        ModelFile("token_scores.json", 121_000L)
    )

    /** 内部存储中的模型根目录。 */
    val modelDir: File by lazy {
        File(appContext.filesDir, "models").apply { if (!exists()) mkdirs() }
    }

    /** 当前初始化进度状态。 */
    @Volatile
    var initProgress: CloneStep = CloneStep.ModelInitializing(0)
        private set

    /** 是否已初始化完成。 */
    @Volatile
    private var initialized: Boolean = false

    /** 共享的克隆引擎（懒加载）。 */
    @Volatile
    private var engine: CloneEngine? = null

    /** 并发安全锁。 */
    private val initMutex = Mutex()

    /** Application Context。 */
    private val appContext: Context by lazy {
        VoiceCloneApp.get().applicationContext
    }

    /**
     * 确保模型文件已下载到内部存储。
     * 如果文件不存在或大小不匹配，从 GitHub Release 下载。
     */
    suspend fun ensureModelsInitialized(onProgress: (Int) -> Unit = {}) {
        if (initialized) {
            onProgress(100)
            return
        }
        initMutex.withLock {
            if (initialized) {
                onProgress(100)
                return
            }

            try {
                initProgress = CloneStep.ModelInitializing(0)
                onProgress(0)

                val total = MODEL_FILES.size
                var completed = 0

                MODEL_FILES.forEach { modelFile ->
                    val targetFile = File(modelDir, modelFile.name)
                    val needDownload = !targetFile.exists() ||
                            targetFile.length() == 0L ||
                            (modelFile.minSize > 0 && targetFile.length() < modelFile.minSize)

                    if (needDownload) {
                        Log.i(TAG, "下载模型文件: ${modelFile.name}")
                        withContext(Dispatchers.IO) {
                            downloadFile(modelFile.name, targetFile) { fileProgress ->
                                // 计算整体进度：已完成文件数 + 当前文件进度
                                val overallProgress = ((completed + fileProgress) * 100) / total
                                initProgress = CloneStep.ModelInitializing(overallProgress)
                                onProgress(overallProgress)
                            }
                        }
                    } else {
                        Log.i(TAG, "文件已存在，跳过: ${modelFile.name}")
                    }

                    completed++
                    val progress = (completed * 100) / total
                    initProgress = CloneStep.ModelInitializing(progress)
                    onProgress(progress)
                }

                initialized = true
                initProgress = CloneStep.ModelInitializing(100)
                onProgress(100)
                Log.i(TAG, "模型初始化完成")

            } catch (t: Throwable) {
                Log.e(TAG, "模型初始化失败", t)
                initProgress = CloneStep.Error("模型下载失败: ${t.message ?: "未知错误"}")
                throw t
            }
        }
    }

    /** 获取内部存储中某个模型文件的绝对路径。 */
    fun getModelPath(name: String): String = File(modelDir, name).absolutePath

    /** 判断模型是否已初始化完成。 */
    fun isInitialized(): Boolean = initialized

    /** 判断所有模型文件是否就绪。 */
    fun isModelReady(): Boolean {
        return MODEL_FILES.all { File(modelDir, it.name).exists() }
    }

    /**
     * 获取共享的 [CloneEngine] 实例。
     */
    suspend fun getEngine(): CloneEngine {
        engine?.let { return it }
        ensureModelsInitialized()
        return synchronized(this) {
            engine ?: CloneEngine(modelDir).also { engine = it }
        }
    }

    /**
     * 从 GitHub Release 下载单个模型文件。
     */
    private fun downloadFile(fileName: String, target: File, onProgress: (Int) -> Unit) {
        val url = URL("$MODEL_BASE_URL/$fileName")
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 30000
        connection.readTimeout = 300000
        connection.instanceFollowRedirects = true

        try {
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw RuntimeException("下载失败: HTTP $responseCode - ${modelDir.name}/$fileName")
            }

            val totalSize = connection.contentLengthLong
            Log.i(TAG, "下载 $fileName: totalSize=$totalSize bytes")

            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var totalRead = 0L
                    var read: Int
                    var lastProgress = 0

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        totalRead += read
                        if (totalSize > 0) {
                            val progress = ((totalRead * 100) / totalSize).toInt()
                            if (progress > lastProgress + 5) {
                                lastProgress = progress
                                onProgress(progress)
                            }
                        }
                    }
                }
            }

            Log.i(TAG, "下载完成: $fileName (${target.length()} bytes)")
        } finally {
            connection.disconnect()
        }
    }

    /** 模型文件描述。 */
    private data class ModelFile(val name: String, val minSize: Long)
}
