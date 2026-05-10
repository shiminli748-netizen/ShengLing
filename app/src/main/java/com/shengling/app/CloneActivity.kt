package com.shengling.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.shengling.app.databinding.ActivityCloneBinding
import kotlinx.coroutines.launch
import java.io.File

class CloneActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCloneBinding
    private var recordedFile: File? = null
    private var referenceSamples: FloatArray? = null
    private var referenceSampleRate: Int = 16000
    private var isRecording = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startRecording()
        } else {
            Toast.makeText(this, R.string.msg_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private val pickAudioLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { loadAudioFromFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCloneBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnRecord.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    startRecording()
                } else {
                    requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }

        binding.btnSelectFile.setOnClickListener {
            pickAudioLauncher.launch(arrayOf("audio/*"))
        }

        binding.btnPlayRef.setOnClickListener {
            referenceSamples?.let { samples ->
                val playFile = File(cacheDir, "play_ref.wav")
                AudioUtils.playSamples(samples, referenceSampleRate, playFile)
            }
        }

        binding.btnRerecord.setOnClickListener {
            resetAudio()
        }

        binding.btnClone.setOnClickListener {
            startCloning()
        }
    }

    private fun startRecording() {
        recordedFile = File(cacheDir, "recorded_ref.wav")
        val success = AudioUtils.startRecording(recordedFile!!) { amplitude ->
            runOnUiThread {
                binding.btnRecord.alpha = 0.5f + amplitude * 0.5f
            }
        }

        if (success) {
            isRecording = true
            binding.btnRecord.setImageResource(android.R.drawable.ic_media_pause)
            binding.textRecordHint.text = getString(R.string.msg_recording)
        } else {
            Toast.makeText(this, R.string.error_clone_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        AudioUtils.stopRecording()
        isRecording = false
        binding.btnRecord.setImageResource(R.drawable.ic_mic)
        binding.btnRecord.alpha = 1.0f
        binding.textRecordHint.text = getString(R.string.msg_recording_done)

        recordedFile?.let { file ->
            val result = AudioUtils.loadWavFile(file)
            if (result != null) {
                referenceSamples = result.first
                referenceSampleRate = result.second
                onAudioLoaded()
            }
        }
    }

    private fun loadAudioFromFile(uri: Uri) {
        val result = AudioUtils.loadAudioFromFile(this, uri)
        if (result != null) {
            referenceSamples = result.first
            referenceSampleRate = result.second
            onAudioLoaded()
        } else {
            Toast.makeText(this, R.string.error_clone_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onAudioLoaded() {
        val samples = referenceSamples ?: return
        val duration = AudioUtils.getAudioDuration(samples, referenceSampleRate)

        binding.textDuration.text = getString(R.string.label_duration, duration)
        binding.textDuration.visibility = View.VISIBLE
        binding.layoutAudioActions.visibility = View.VISIBLE
        binding.btnClone.isEnabled = true

        if (duration < 3f) {
            Toast.makeText(this, R.string.error_audio_too_short, Toast.LENGTH_SHORT).show()
            binding.btnClone.isEnabled = false
        } else if (duration > 15f) {
            Toast.makeText(this, R.string.error_audio_too_long, Toast.LENGTH_SHORT).show()
            binding.btnClone.isEnabled = false
        }
    }

    private fun resetAudio() {
        referenceSamples = null
        recordedFile = null
        binding.textDuration.visibility = View.GONE
        binding.layoutAudioActions.visibility = View.GONE
        binding.btnClone.isEnabled = false
        binding.textRecordHint.text = getString(R.string.btn_record)
        AudioUtils.stopPlayback()
    }

    private fun startCloning() {
        val samples = referenceSamples ?: return

        binding.layoutCloneProgress.visibility = View.VISIBLE
        binding.btnClone.isEnabled = false

        lifecycleScope.launch {
            runOnUiThread {
                binding.textCloneStatus.text = getString(R.string.status_analyzing)
                binding.progressClone.setProgress(30, true)
            }
            kotlinx.coroutines.delay(500)

            runOnUiThread {
                binding.textCloneStatus.text = getString(R.string.status_extracting)
                binding.progressClone.setProgress(60, true)
            }

            VoiceEngine.setReferenceAudio(samples, referenceSampleRate)

            runOnUiThread {
                binding.textCloneStatus.text = getString(R.string.status_generating_model)
                binding.progressClone.setProgress(90, true)
            }
            kotlinx.coroutines.delay(300)

            runOnUiThread {
                binding.progressClone.setProgress(100, true)
                binding.textCloneStatus.text = getString(R.string.status_clone_done)
                binding.btnClone.isEnabled = true

                Toast.makeText(
                    this@CloneActivity,
                    R.string.status_clone_done,
                    Toast.LENGTH_SHORT
                ).show()

                startActivity(Intent(this@CloneActivity, TTSActivity::class.java))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AudioUtils.stopPlayback()
        if (isRecording) {
            AudioUtils.stopRecording()
        }
    }
}
