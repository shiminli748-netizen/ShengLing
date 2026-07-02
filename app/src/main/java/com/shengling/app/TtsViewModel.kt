package com.shengling.app

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.shengling.app.data.TtsStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 文本转语音页面的 ViewModel。
 *
 * 负责：
 * - 加载已克隆的声纹
 * - 调用 [CloneEngine] 用克隆的声音合成语音
 * - 保存/分享生成的音频
 */
class TtsViewModel(app: Application) : AndroidViewModel(app) {

    /** 当前 TTS 状态。 */
    private val _ttsStep = MutableLiveData<TtsStep>(TtsStep.Idle)
    val ttsStep: LiveData<TtsStep> = _ttsStep

    /** 可用的声纹列表。 */
    private val _availableVoices = MutableLiveData<List<String>>(emptyList())
    val availableVoices: LiveData<List<String>> = _availableVoices

    /** 当前选中的声纹。 */
    private val _selectedVoice = MutableLiveData<String?>(null)
    val selectedVoice: LiveData<String?> = _selectedVoice

    /** 当前生成的音频文件路径。 */
    private val _audioPath = MutableLiveData<String?>(null)
    val audioPath: LiveData<String?> = _audioPath

    /** 当前生成的音频时长（毫秒）。 */
    private val _audioDurationMs = MutableLiveData<Int>(0)
    val audioDurationMs: LiveData<Int> = _audioDurationMs

    private val app = app

    /** 初始化：加载已保存的声纹列表。 */
    fun loadVoices() {
        viewModelScope.launch {
            try {
                val engine = ModelManager.getEngine()
                val voices = engine.listSavedVoices()
                _availableVoices.value = voices
                if (voices.isNotEmpty() && _selectedVoice.value == null) {
                    _selectedVoice.value = voices.first()
                }
            } catch (t: Throwable) {
                // 忽略，可能模型尚未初始化
            }
        }
    }

    /** 选择声纹。 */
    fun selectVoice(name: String) {
        _selectedVoice.value = name
    }

    /**
     * 生成语音。
     *
     * @param text 待合成文本
     */
    fun generateSpeech(text: String) {
        val voiceName = _selectedVoice.value
        if (voiceName.isNullOrEmpty()) {
            _ttsStep.value = TtsStep.Error("请先克隆声音")
            return
        }
        if (text.isBlank()) {
            _ttsStep.value = TtsStep.Error("请输入文本")
            return
        }

        viewModelScope.launch {
            try {
                _ttsStep.value = TtsStep.Generating(10)

                val engine = ModelManager.getEngine()
                if (!engine.hasVoice(voiceName)) {
                    _ttsStep.value = TtsStep.Error("声纹不存在，请先克隆声音")
                    return@launch
                }

                _ttsStep.value = TtsStep.Generating(30)

                // 使用 PocketTTS 合成语音
                val result = engine.synthesize(text, voiceName) { progress ->
                    _ttsStep.value = TtsStep.Generating(progress)
                }

                _ttsStep.value = TtsStep.Generating(90)

                // 保存到内部存储
                val outputDir = File(app.filesDir, "tts_output").apply { if (!exists()) mkdirs() }
                val outputFile = File(outputDir, "tts_${System.currentTimeMillis()}.wav")
                AudioUtils.saveWav(outputFile.absolutePath, result.samples, result.sampleRate)

                val durationMs = (result.samples.size * 1000) / result.sampleRate
                _audioPath.value = outputFile.absolutePath
                _audioDurationMs.value = durationMs

                _ttsStep.value = TtsStep.Complete(outputFile.absolutePath)

            } catch (t: Throwable) {
                _ttsStep.value = TtsStep.Error("生成失败: ${t.message ?: "未知错误"}")
            }
        }
    }

    /**
     * 保存音频到公共 Downloads 目录。
     */
    fun saveToDownloads(): String? {
        val path = _audioPath.value ?: return null
        val file = File(path)

        return try {
            val fileName = "声灵_${System.currentTimeMillis()}.wav"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用 MediaStore
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/ShengLing")
                }
                val resolver = app.contentResolver
                val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    resolver.openOutputStream(it)?.use { output ->
                        file.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                }
                "Music/ShengLing/$fileName"
            } else {
                // 旧版本直接复制
                val destDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "ShengLing")
                if (!destDir.exists()) destDir.mkdirs()
                val destFile = File(destDir, fileName)
                file.copyTo(destFile, overwrite = true)
                destFile.absolutePath
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** 重置状态。 */
    fun reset() {
        _ttsStep.value = TtsStep.Idle
        _audioPath.value = null
        _audioDurationMs.value = 0
    }
}
