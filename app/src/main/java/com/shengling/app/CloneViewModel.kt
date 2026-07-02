package com.shengling.app

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.shengling.app.data.CloneStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 声音克隆页面的 ViewModel。
 *
 * 负责：
 * - 选择/录制参考音频并校验时长（3~15 秒）
 * - 调用 [CloneEngine] 完成声音克隆（保存参考音频）
 * - 向 UI 报告克隆进度状态
 */
class CloneViewModel(app: Application) : AndroidViewModel(app) {

    /** 当前克隆步骤状态。 */
    private val _cloneStep = MutableLiveData<CloneStep>(CloneStep.Idle)
    val cloneStep: LiveData<CloneStep> = _cloneStep

    /** 已选择的参考音频路径（应用内部存储中的副本）。 */
    private val _selectedAudioPath = MutableLiveData<String?>(null)
    val selectedAudioPath: LiveData<String?> = _selectedAudioPath

    /** 已选择的参考音频信息。 */
    private val _audioInfo = MutableLiveData<String>("")
    val audioInfo: LiveData<String> = _audioInfo

    /** 参考音频时长（秒）。 */
    private val _audioDuration = MutableLiveData<Float>(0f)
    val audioDuration: LiveData<Float> = _audioDuration

    /** 克隆完成后保存的声纹名称。 */
    private val _savedVoiceName = MutableLiveData<String?>(null)
    val savedVoiceName: LiveData<String?> = _savedVoiceName

    private val app = app

    /**
     * 从文件选择器选中的音频 URI。
     * 将其复制到内部存储并校验时长。
     */
    fun selectAudioFile(uri: Uri) {
        viewModelScope.launch {
            try {
                _cloneStep.value = CloneStep.AnalyzingAudio(10)

                // 复制到内部存储
                val destDir = File(app.filesDir, "temp_audio").apply { if (!exists()) mkdirs() }
                val destFile = File(destDir, "ref_${System.currentTimeMillis()}.wav")
                val copied = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                        true
                    } ?: false
                }

                if (!copied || !destFile.exists()) {
                    _cloneStep.value = CloneStep.Error("无法读取音频文件")
                    return@launch
                }

                val path = destFile.absolutePath

                // 用 MediaMetadataRetriever 检测时长（支持 MP3/M4A/AAC/WAV 等所有格式）
                val durationMs = withContext(Dispatchers.IO) {
                    AudioUtils.getAudioDurationMs(path)
                }
                val durationSec = durationMs / 1000f

                _audioDuration.value = durationSec
                _selectedAudioPath.value = path
                _audioInfo.value = "时长: ${"%.1f".format(durationSec)} 秒\n文件: ${destFile.name}"

                // 校验时长 3~15 秒
                if (durationSec < CloneEngine.MIN_REF_SECONDS || durationSec > CloneEngine.MAX_REF_SECONDS) {
                    _cloneStep.value = CloneStep.Error(
                        "音频时长 ${"%.1f".format(durationSec)} 秒，需在 ${CloneEngine.MIN_REF_SECONDS.toInt()}-${CloneEngine.MAX_REF_SECONDS.toInt()} 秒之间"
                    )
                } else {
                    _cloneStep.value = CloneStep.Idle
                }

            } catch (t: Throwable) {
                _cloneStep.value = CloneStep.Error("选择音频失败: ${t.message ?: "未知错误"}")
            }
        }
    }

    /**
     * 录音完成，传入录音文件路径。
     */
    fun selectRecordedAudio(path: String) {
        viewModelScope.launch {
            try {
                val file = File(path)
                if (!file.exists()) {
                    _cloneStep.value = CloneStep.Error("录音文件不存在")
                    return@launch
                }

                val durationMs = withContext(Dispatchers.IO) {
                    AudioUtils.getAudioDurationMs(path)
                }
                val durationSec = durationMs / 1000f

                _audioDuration.value = durationSec
                _selectedAudioPath.value = path
                _audioInfo.value = "时长: ${"%.1f".format(durationSec)} 秒\n来源: 录音"

                if (durationSec < CloneEngine.MIN_REF_SECONDS || durationSec > CloneEngine.MAX_REF_SECONDS) {
                    _cloneStep.value = CloneStep.Error(
                        "录音时长 ${"%.1f".format(durationSec)} 秒，需在 ${CloneEngine.MIN_REF_SECONDS.toInt()}-${CloneEngine.MAX_REF_SECONDS.toInt()} 秒之间"
                    )
                } else {
                    _cloneStep.value = CloneStep.Idle
                }
            } catch (t: Throwable) {
                _cloneStep.value = CloneStep.Error("处理录音失败: ${t.message}")
            }
        }
    }

    /**
     * 开始声音克隆。
     * PocketTTS 是零样本克隆，"克隆"步骤只需保存参考音频。
     */
    fun startCloning() {
        val audioPath = _selectedAudioPath.value
        if (audioPath.isNullOrEmpty()) {
            _cloneStep.value = CloneStep.Error("请先选择参考音频")
            return
        }

        viewModelScope.launch {
            try {
                _cloneStep.value = CloneStep.AnalyzingAudio(30)
                val engine = ModelManager.getEngine()

                _cloneStep.value = CloneStep.ExtractingVoiceprint(60)

                // PocketTTS 零样本克隆：保存参考音频作为"声纹"
                val voiceName = "voice_${System.currentTimeMillis()}"
                engine.saveReferenceVoice(audioPath, voiceName)

                _cloneStep.value = CloneStep.GeneratingModel(90)

                // 验证声纹已保存
                if (!engine.hasVoice(voiceName)) {
                    throw IllegalStateException("声纹保存失败")
                }

                _savedVoiceName.value = voiceName
                _cloneStep.value = CloneStep.Complete

            } catch (t: Throwable) {
                _cloneStep.value = CloneStep.Error("克隆失败: ${t.message ?: "未知错误"}")
            }
        }
    }

    /** 重置到初始状态。 */
    fun reset() {
        _cloneStep.value = CloneStep.Idle
        _selectedAudioPath.value = null
        _audioInfo.value = ""
        _audioDuration.value = 0f
        _savedVoiceName.value = null
    }
}
