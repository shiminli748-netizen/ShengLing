package com.shengling.app

import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.viewModels
import com.google.android.material.chip.Chip
import com.shengling.app.data.TtsStep
import com.shengling.app.databinding.ActivityTtsBinding
import java.io.File

/**
 * 文本朗读界面。
 *
 * 选择已克隆的声纹，输入文本并合成语音，随后可播放、保存或分享。
 * 采用 Material Design 3 深色主题。
 */
class TTSActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTtsBinding
    private val viewModel: TtsViewModel by viewModels()

    private var audioPlayer: AudioPlayer? = null
    private var lastAudioPath: String? = null

    /** 文本朗读快捷示例。 */
    private val suggestions = listOf(
        "你好，欢迎使用声灵。",
        "这是一段测试文本，用于演示声音克隆效果。",
        "今天天气真不错，我们一起出去走走吧。",
        "声灵是一款完全离线的本地 AI 声音克隆工具。"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityTtsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()
        setupToolbar()
        setupTextInput()
        setupChips()
        bindControls()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadVoices()
    }

    override fun onDestroy() {
        super.onDestroy()
        audioPlayer?.stop()
        audioPlayer?.release()
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, sysBars.top, v.paddingRight, sysBars.bottom)
            insets
        }
    }

    private fun setupToolbar() {
        binding.toolbar.title = getString(R.string.tts_voice)
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupTextInput() {
        binding.inputText.filters = arrayOf(InputFilter.LengthFilter(500))
    }

    private fun setupChips() {
        binding.chipsGroup.removeAllViews()
        for (text in suggestions) {
            val chip = Chip(this).apply {
                this.text = text
                isCheckable = false
                setOnClickListener {
                    binding.inputText.setText(text)
                    binding.inputText.setSelection(text.length)
                }
            }
            binding.chipsGroup.addView(chip)
        }
    }

    private fun bindControls() {
        binding.generateButton.setOnClickListener {
            val text = binding.inputText.text?.toString().orEmpty()
            audioPlayer?.stop()
            viewModel.generateSpeech(text)
        }

        binding.playPauseButton.setOnClickListener {
            val path = lastAudioPath
            if (path != null) {
                if (audioPlayer?.isPlaying == true) {
                    audioPlayer?.pause()
                    binding.playPauseButton.setIconResource(R.drawable.ic_play)
                    binding.playPauseButton.text = getString(R.string.play)
                } else {
                    playAudioFile(path)
                }
            }
        }

        binding.saveButton.setOnClickListener {
            val savedPath = viewModel.saveToDownloads()
            if (savedPath != null) {
                Toast.makeText(this, "已保存到: $savedPath", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show()
            }
        }

        binding.shareButton.setOnClickListener {
            val path = lastAudioPath ?: return@setOnClickListener
            shareAudio(path)
        }
    }

    // ===================== 观察状态 =====================

    private fun observeViewModel() {
        viewModel.availableVoices.observe(this) { voices ->
            if (voices.isEmpty()) {
                binding.voiceNameText.text = getString(R.string.no_voice_available)
                binding.generateButton.isEnabled = false
            } else {
                binding.voiceNameText.text = getString(R.string.current_voice, voices.first())
                binding.generateButton.isEnabled = true
            }
        }

        viewModel.selectedVoice.observe(this) { voice ->
            if (voice != null) {
                binding.voiceNameText.text = getString(R.string.current_voice, voice)
            }
        }

        viewModel.ttsStep.observe(this) { step ->
            when (step) {
                is TtsStep.Idle -> {
                    binding.generatingLayout.visibility = View.GONE
                    binding.generateButton.isEnabled = viewModel.availableVoices.value?.isNotEmpty() == true
                }
                is TtsStep.Generating -> {
                    binding.generatingLayout.visibility = View.VISIBLE
                    binding.generatingText.text = getString(R.string.generating_speech)
                    binding.generateButton.isEnabled = false
                    binding.playerLayout.visibility = View.GONE
                }
                is TtsStep.Complete -> {
                    binding.generatingLayout.visibility = View.GONE
                    binding.generateButton.isEnabled = true
                    lastAudioPath = step.audioPath
                    showPlayer(step.audioPath)
                    // 自动播放
                    playAudioFile(step.audioPath)
                }
                is TtsStep.Playing -> {
                    binding.playerLayout.visibility = View.VISIBLE
                }
                is TtsStep.Error -> {
                    binding.generatingLayout.visibility = View.GONE
                    binding.generateButton.isEnabled = true
                    Toast.makeText(this, step.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showPlayer(path: String) {
        binding.playerLayout.visibility = View.VISIBLE
        val info = try { AudioUtils.getWavInfo(path) } catch (t: Throwable) { null }
        val durationMs = if (info != null && info.sampleRate > 0) {
            (info.durationSec * 1000).toInt()
        } else 0
        binding.durationText.text = formatTime(0) + " / " + formatTime(durationMs)
        binding.playProgress.max = if (durationMs > 0) durationMs else 1
        binding.playProgress.setProgressCompat(0, false)
    }

    private fun playAudioFile(path: String) {
        audioPlayer?.stop()
        audioPlayer?.release()
        audioPlayer = AudioPlayer()
        audioPlayer?.onCompletion = {
            binding.playPauseButton.setIconResource(R.drawable.ic_play)
            binding.playPauseButton.text = getString(R.string.play)
        }

        val info = try { AudioUtils.getWavInfo(path) } catch (t: Throwable) { null }
        val sampleRate = info?.sampleRate ?: 24000
        val samples = info?.let { AudioUtils.loadWav(path, sampleRate) } ?: return

        binding.playPauseButton.setIconResource(R.drawable.ic_pause)
        binding.playPauseButton.text = getString(R.string.pause)

        audioPlayer?.play(samples, sampleRate)
    }

    private fun shareAudio(path: String) {
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val authority = "${packageName}.fileprovider"
        val uri = try {
            FileProvider.getUriForFile(this, authority, file)
        } catch (t: Throwable) {
            Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share)))
    }

    private fun formatTime(ms: Int): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }
}
