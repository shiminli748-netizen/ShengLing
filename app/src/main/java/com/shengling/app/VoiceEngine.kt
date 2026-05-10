package com.shengling.app

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object VoiceEngine {
    private const val TAG = "VoiceEngine"

    private var tts: OfflineTts? = null
    private var referenceAudio: FloatArray? = null
    private var referenceSampleRate: Int = 0
    private var referenceText: String = ""
    private var isVoiceCloned = false

    fun isEngineReady(): Boolean = tts != null

    fun isVoiceCloned(): Boolean = isVoiceCloned

    fun initEngine(context: Context): Boolean {
        return try {
            val zipVoiceDir = ModelManager.getZipVoiceDir(context)
            val espeakDir = ModelManager.getEspeakDir(context)
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    zipvoice = OfflineTtsZipVoiceModelConfig(
                        tokens = File(zipVoiceDir, "tokens.txt").absolutePath,
                        encoder = File(zipVoiceDir, "text_encoder_int8.onnx").absolutePath,
                        decoder = File(zipVoiceDir, "fm_decoder_int8.onnx").absolutePath,
                        vocoder = File(zipVoiceDir, "vocos_24khz.onnx").absolutePath,
                        dataDir = espeakDir.absolutePath,
                        lexicon = "",
                        featScale = 0.1f,
                        tShift = 0.5f,
                        targetRms = 0.1f,
                        guidanceScale = 3.0f,
                    ),
                    numThreads = 4,
                    debug = false,
                    provider = "cpu",
                ),
                maxNumSentences = 2,
                silenceScale = 0.2f,
            )

            tts = OfflineTts(config = config)
            Log.i(TAG, "Voice engine initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init voice engine", e)
            false
        }
    }

    fun setReferenceAudio(samples: FloatArray, sampleRate: Int, text: String = "") {
        referenceAudio = samples
        referenceSampleRate = sampleRate
        referenceText = text
        isVoiceCloned = true
    }

    fun getReferenceAudio(): FloatArray? = referenceAudio

    fun getReferenceSampleRate(): Int = referenceSampleRate

    suspend fun generateSpeech(
        text: String,
        speed: Float = 1.0f,
        numSteps: Int = 4,
        onProgress: (() -> Unit)? = null
    ): GeneratedAudio? = withContext(Dispatchers.Default) {
        try {
            val engine = tts ?: return@withContext null
            val refAudio = referenceAudio

            val genConfig = if (refAudio != null) {
                GenerationConfig(
                    speed = speed,
                    referenceAudio = refAudio,
                    referenceSampleRate = referenceSampleRate,
                    referenceText = referenceText,
                    numSteps = numSteps,
                    silenceScale = 0.2f,
                )
            } else {
                GenerationConfig(
                    speed = speed,
                    numSteps = numSteps,
                    silenceScale = 0.2f,
                )
            }

            val audio = engine.generateWithConfigAndCallback(
                text = text,
                config = genConfig,
            ) { _ ->
                onProgress?.invoke()
                0
            }

            Log.i(TAG, "Generated speech: ${audio.samples.size} samples at ${audio.sampleRate}Hz")
            audio
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate speech", e)
            null
        }
    }

    fun release() {
        tts?.release()
        tts = null
        referenceAudio = null
        isVoiceCloned = false
    }
}
