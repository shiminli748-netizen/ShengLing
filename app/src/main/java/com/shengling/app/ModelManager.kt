package com.shengling.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object ModelManager {
    private const val TAG = "ModelManager"
    private const val MODEL_DIR = "models"
    private const val ZIPVOICE_DIR = "zipvoice"

    private const val BASE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

    private val modelFiles = mapOf(
        "fm_decoder_int8.onnx" to "$BASE_URL/sherpa-onnx-zipvoice-distill-zh-en-emilia/fm_decoder_int8.onnx",
        "text_encoder_int8.onnx" to "$BASE_URL/sherpa-onnx-zipvoice-distill-zh-en-emilia/text_encoder_int8.onnx",
        "vocos_24khz.onnx" to "$BASE_URL/sherpa-onnx-zipvoice-distill-zh-en-emilia/vocos_24khz.onnx",
        "tokens.txt" to "$BASE_URL/sherpa-onnx-zipvoice-distill-zh-en-emilia/tokens.txt",
        "pinyin.raw" to "$BASE_URL/sherpa-onnx-zipvoice-distill-zh-en-emilia/pinyin.raw",
    )

    private val espeakFiles = listOf(
        "cmn_dict", "en_dict", "zh_dict", "phondata", "phonindex", "phontab",
        "intonations", "en_US", "zh_yue"
    )

    private const val ESPEAK_BASE = "$BASE_URL/sherpa-onnx-zipvoice-distill-zh-en-emilia/espeak-ng-data"

    var isInitialized = false
        private set

    var totalDownloadSize: Long = 0
        private set

    fun getModelDir(context: Context): File {
        return File(context.filesDir, MODEL_DIR)
    }

    fun getZipVoiceDir(context: Context): File {
        return File(getModelDir(context), ZIPVOICE_DIR)
    }

    fun getEspeakDir(context: Context): File {
        return File(getZipVoiceDir(context), "espeak-ng-data")
    }

    fun areModelsReady(context: Context): Boolean {
        val dir = getZipVoiceDir(context)
        val required = listOf(
            File(dir, "fm_decoder_int8.onnx"),
            File(dir, "text_encoder_int8.onnx"),
            File(dir, "vocos_24khz.onnx"),
            File(dir, "tokens.txt"),
        )
        return required.all { it.exists() && it.length() > 0 }
    }

    fun getTotalFiles(): Int = modelFiles.size + espeakFiles.size

    suspend fun downloadModels(
        context: Context,
        onProgress: (fileName: String, current: Int, total: Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val zipVoiceDir = getZipVoiceDir(context)
            if (!zipVoiceDir.exists()) zipVoiceDir.mkdirs()
            val espeakDir = getEspeakDir(context)
            if (!espeakDir.exists()) espeakDir.mkdirs()

            val totalItems = modelFiles.size + espeakFiles.size
            var current = 0
            var totalBytes: Long = 0

            for ((name, url) in modelFiles) {
                val outFile = File(zipVoiceDir, name)
                if (outFile.exists() && outFile.length() > 0) {
                    current++
                    withContext(Dispatchers.Main) {
                        onProgress(name, current, totalItems)
                    }
                    totalBytes += outFile.length()
                    continue
                }

                withContext(Dispatchers.Main) {
                    onProgress(name, current, totalItems)
                }

                val downloaded = downloadFile(url, outFile)
                if (!downloaded) {
                    Log.e(TAG, "Failed to download $name")
                    return@withContext false
                }
                totalBytes += outFile.length()
                current++
                withContext(Dispatchers.Main) {
                    onProgress(name, current, totalItems)
                }
            }

            for (name in espeakFiles) {
                val outFile = File(espeakDir, name)
                if (outFile.exists()) {
                    current++
                    withContext(Dispatchers.Main) {
                        onProgress("espeak-ng-data/$name", current, totalItems)
                    }
                    continue
                }

                val url = "$ESPEAK_BASE/$name"
                withContext(Dispatchers.Main) {
                    onProgress("espeak-ng-data/$name", current, totalItems)
                }

                val downloaded = downloadFile(url, outFile)
                if (!downloaded) {
                    Log.w(TAG, "Failed to download espeak file $name, skipping")
                }
                current++
                withContext(Dispatchers.Main) {
                    onProgress("espeak-ng-data/$name", current, totalItems)
                }
            }

            totalDownloadSize = totalBytes
            isInitialized = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download models", e)
            false
        }
    }

    private fun downloadFile(urlStr: String, outFile: File): Boolean {
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "HTTP ${conn.responseCode} for $urlStr")
                conn.disconnect()
                return false
            }

            outFile.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                FileOutputStream(outFile).use { output ->
                    val buffer = ByteArray(8192)
                    var len: Int
                    while (input.read(buffer).also { len = it } > 0) {
                        output.write(buffer, 0, len)
                    }
                }
            }
            conn.disconnect()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: $urlStr", e)
            outFile.delete()
            false
        }
    }
}
