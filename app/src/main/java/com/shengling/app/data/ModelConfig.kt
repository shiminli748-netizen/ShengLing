package com.shengling.app.data

import kotlinx.serialization.Serializable

/**
 * Configuration loaded from assets/models/config.json.
 * Defines the ONNX model input/output tensor names and audio parameters.
 */
@Serializable
data class ModelConfig(
    val sampleRate: Int = 22050,
    val nMelChannels: Int = 80,
    val nFft: Int = 1024,
    val hopLength: Int = 256,
    val winLength: Int = 1024,
    val fmin: Float = 0f,
    val fmax: Float = 8000f,
    val speakerEmbeddingDim: Int = 256,
    val maxTextLength: Int = 500,
    val speakerEncoder: SpeakerEncoderConfig = SpeakerEncoderConfig(),
    val vitsDecoder: VitsDecoderConfig = VitsDecoderConfig(),
    val tokenizer: TokenizerConfig = TokenizerConfig()
)

@Serializable
data class SpeakerEncoderConfig(
    val modelFile: String = "speaker_encoder.onnx",
    val inputName: String = "mel",
    val outputName: String = "embedding",
    val melChannels: Int = 40,
    val sampleRate: Int = 16000,
    val nFft: Int = 512,
    val hopLength: Int = 160,
    val winLength: Int = 400
)

@Serializable
data class VitsDecoderConfig(
    val modelFile: String = "vits_decoder.onnx",
    val inputIdsName: String = "input_ids",
    val inputLengthsName: String = "input_lengths",
    val speakerEmbeddingName: String = "speaker_embedding",
    val outputAudioName: String = "audio",
    val noiseScale: Float = 0.667f,
    val lengthScale: Float = 1.0f,
    val noiseScaleW: Float = 0.8f
)

@Serializable
data class TokenizerConfig(
    val file: String = "tokenizer.json",
    val language: String = "zh",
    val padId: Int = 0,
    val unkId: Int = 1,
    val bosId: Int = 2,
    val eosId: Int = 3
)
