package com.shengling.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.shengling.app.databinding.ActivityTtsBinding
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TTSActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTtsBinding
    private var generatedAudio: com.k2fsa.sherpa.onnx.GeneratedAudio? = null
    private var generatedFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTtsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        val refAudio = VoiceEngine.getReferenceAudio()
        if (refAudio != null) {
            val duration = AudioUtils.getAudioDuration(
                refAudio, VoiceEngine.getReferenceSampleRate()
            )
            binding.textVoiceName.text = getString(R.string.label_reference_audio)
            binding.textVoiceDuration.text = getString(R.string.label_duration, duration)
        }

        binding.sliderSpeed.addOnChangeListener { _, value, _ ->
            binding.textSpeedValue.text = String.format("%.1fx", value)
        }

        binding.sliderSteps.addOnChangeListener { _, value, _ ->
            binding.textStepsValue.text = value.toInt().toString()
        }

        binding.btnGenerate.setOnClickListener {
            generateSpeech()
        }

        binding.btnPlayResult.setOnClickListener {
            playResult()
        }

        binding.btnSaveResult.setOnClickListener {
            saveResult()
        }

        binding.btnShareResult.setOnClickListener {
            shareResult()
        }
    }

    private fun generateSpeech() {
        val text = binding.editText.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.error_no_text, Toast.LENGTH_SHORT).show()
            return
        }

        if (!VoiceEngine.isVoiceCloned()) {
            Toast.makeText(this, R.string.error_clone_failed, Toast.LENGTH_SHORT).show()
            return
        }

        binding.layoutResult.visibility = View.VISIBLE
        binding.progressTts.visibility = View.VISIBLE
        binding.btnGenerate.isEnabled = false

        val speed = binding.sliderSpeed.value
        val steps = binding.sliderSteps.value.toInt()

        lifecycleScope.launch {
            val audio = VoiceEngine.generateSpeech(text, speed, steps) {
                runOnUiThread {
                    binding.progressTts.visibility = View.VISIBLE
                }
            }

            runOnUiThread {
                binding.btnGenerate.isEnabled = true
                binding.progressTts.visibility = View.GONE

                if (audio != null) {
                    generatedAudio = audio
                    val outputFile = File(cacheDir, "tts_output.wav")
                    AudioUtils.savePcmAsWav(audio.samples, audio.sampleRate, outputFile)
                    generatedFile = outputFile

                    binding.btnPlayResult.isEnabled = true
                    binding.btnSaveResult.isEnabled = true
                    binding.btnShareResult.isEnabled = true

                    Toast.makeText(this@TTSActivity, R.string.status_saved, Toast.LENGTH_SHORT)
                        .show()
                } else {
                    Toast.makeText(
                        this@TTSActivity,
                        R.string.error_tts_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun playResult() {
        generatedFile?.let { file ->
            AudioUtils.playAudio(file) {
                runOnUiThread {
                    binding.btnPlayResult.text = getString(R.string.btn_play)
                }
            }
            binding.btnPlayResult.text = getString(R.string.status_playing)
        }
    }

    private fun saveResult() {
        val audio = generatedAudio ?: return
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val fileName = "shengling_${dateFormat.format(Date())}.wav"
        val saveDir = File(getExternalFilesDir(null), "generated")
        if (!saveDir.exists()) saveDir.mkdirs()
        val saveFile = File(saveDir, fileName)
        AudioUtils.savePcmAsWav(audio.samples, audio.sampleRate, saveFile)
        Toast.makeText(this, "${getString(R.string.status_saved)}: ${saveFile.absolutePath}", Toast.LENGTH_LONG).show()
    }

    private fun shareResult() {
        val file = generatedFile ?: return
        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.btn_share)))
    }

    override fun onDestroy() {
        super.onDestroy()
        AudioUtils.stopPlayback()
    }
}
