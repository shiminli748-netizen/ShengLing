package com.shengling.app.data

/**
 * Represents a single step in the voice cloning workflow.
 * Used by CloneViewModel to update the UI progress bar and status text.
 */
sealed class CloneStep {
    abstract val progress: Int
    abstract val message: String

    /** Model files are being copied from assets to internal storage (first launch only). */
    data class ModelInitializing(override val progress: Int) : CloneStep() {
        override val message: String = "正在初始化AI模型... ${progress}%"
    }

    /** User is selecting or recording reference audio. */
    object Idle : CloneStep() {
        override val progress = 0
        override val message = "请选择3-15秒的参考音频"
    }

    /** Loading and preprocessing the reference audio file. */
    data class AnalyzingAudio(override val progress: Int = 30) : CloneStep() {
        override val message = "正在分析声音特征... ${progress}%"
    }

    /** Running the speaker encoder ONNX model to extract the voiceprint. */
    data class ExtractingVoiceprint(override val progress: Int = 60) : CloneStep() {
        override val message = "提取声纹中... ${progress}%"
    }

    /** Saving the extracted embedding as the "cloned voice model". */
    data class GeneratingModel(override val progress: Int = 90) : CloneStep() {
        override val message = "生成声音模型... ${progress}%"
    }

    /** Cloning finished successfully. */
    object Complete : CloneStep() {
        override val progress = 100
        override val message = "克隆完成！可输入文本测试"
    }

    /** An error occurred during cloning. */
    data class Error(val errorMessage: String) : CloneStep() {
        override val progress = 0
        override val message = "错误：$errorMessage"
    }
}

/**
 * Represents the TTS generation workflow state.
 */
sealed class TtsStep {
    object Idle : TtsStep()
    data class Generating(val progress: Int) : TtsStep()
    data class Playing(val durationMs: Int) : TtsStep()
    data class Complete(val audioPath: String) : TtsStep()
    data class Error(val message: String) : TtsStep()
}
