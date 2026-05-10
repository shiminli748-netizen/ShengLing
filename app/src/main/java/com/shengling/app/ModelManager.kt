package com.shengling.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object ModelManager {
    private const val TAG = "ModelManager"
    private const val MODEL_DIR = "models"
    private const val ZIPVOICE_DIR = "sherpa-onnx-zipvoice-distill-zh-en-emilia"

    val modelFiles = listOf(
        "$ZIPVOICE_DIR/text_encoder.onnx",
        "$ZIPVOICE_DIR/fm_decoder.onnx",
        "$ZIPVOICE_DIR/vocos_24khz.onnx",
        "$ZIPVOICE_DIR/tokens.txt",
        "$ZIPVOICE_DIR/lexicon.txt",
        "$ZIPVOICE_DIR/pinyin.raw"
    )

    val modelDirs = listOf(
        "$ZIPVOICE_DIR/espeak-ng-data"
    )

    var isInitialized = false
        private set

    fun getModelDir(context: Context): File {
        return File(context.filesDir, MODEL_DIR)
    }

    fun getZipVoiceDir(context: Context): File {
        return File(getModelDir(context), ZIPVOICE_DIR)
    }

    suspend fun copyModelsFromAssets(
        context: Context,
        onProgress: (current: Int, total: Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val modelDir = getModelDir(context)
            if (!modelDir.exists()) modelDir.mkdirs()

            val assetManager = context.assets
            val allItems = mutableListOf<String>()

            for (file in modelFiles) {
                allItems.add("file:$file")
            }

            for (dir in modelDirs) {
                try {
                    val files = assetManager.list("models/$dir") ?: emptyArray()
                    for (file in files) {
                        allItems.add("dir:$dir/$file")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not list dir $dir in assets", e)
                }
            }

            val totalItems = allItems.size
            var current = 0

            for (item in allItems) {
                if (item.startsWith("file:")) {
                    val assetPath = "models/${item.substring(5)}"
                    val outFile = File(modelDir, item.substring(5))
                    copyAssetFile(assetManager, assetPath, outFile)
                } else if (item.startsWith("dir:")) {
                    val assetPath = "models/${item.substring(4)}"
                    val outFile = File(modelDir, item.substring(4))
                    outFile.parentFile?.mkdirs()
                    copyAssetFile(assetManager, assetPath, outFile)
                }
                current++
                withContext(Dispatchers.Main) {
                    onProgress(current, totalItems)
                }
            }

            isInitialized = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy models", e)
            false
        }
    }

    private fun copyAssetFile(
        assetManager: android.content.res.AssetManager,
        assetPath: String,
        outFile: File
    ) {
        if (outFile.exists() && outFile.length() > 0) {
            return
        }
        outFile.parentFile?.mkdirs()
        assetManager.open(assetPath).use { input ->
            FileOutputStream(outFile).use { output ->
                val buffer = ByteArray(8192)
                var len: Int
                while (input.read(buffer).also { len = it } > 0) {
                    output.write(buffer, 0, len)
                }
            }
        }
    }

    fun areModelsCopied(context: Context): Boolean {
        val zipVoiceDir = getZipVoiceDir(context)
        val encoder = File(zipVoiceDir, "text_encoder.onnx")
        val decoder = File(zipVoiceDir, "fm_decoder.onnx")
        val vocoder = File(zipVoiceDir, "vocos_24khz.onnx")
        return encoder.exists() && decoder.exists() && vocoder.exists()
    }
}
