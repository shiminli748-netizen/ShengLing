package com.shengling.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.shengling.app.data.CloneStep
import com.shengling.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 声灵 主界面。
 *
 * 展示两个大卡片入口：声音克隆、文本朗读（需已克隆声音才可用），
 * 并在首次启动时显示模型初始化进度。采用 Material Design 3 深色主题，
 * 支持边到边布局与 Material You 动态取色。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** 是否已克隆过声音（决定文本朗读卡片是否可点击）。 */
    private var hasClonedVoice = false

    /** 模型是否已就绪。 */
    private var isModelReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 边到边
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()
        bindHeader()
        bindCards()

        observeModelInit()
    }

    override fun onResume() {
        super.onResume()
        // 从克隆页面返回后刷新声纹可用状态
        if (isModelReady) refreshVoices()
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                sysBars.top,
                v.paddingRight,
                sysBars.bottom
            )
            insets
        }
    }

    private fun bindHeader() {
        binding.appTitle.text = getString(R.string.app_name)
        binding.appSubtitle.text = getString(R.string.subtitle)
    }

    private fun bindCards() {
        binding.cloneCard.setOnClickListener {
            startActivity(Intent(this, CloneActivity::class.java))
        }
        binding.ttsCard.setOnClickListener {
            if (hasClonedVoice) {
                startActivity(Intent(this, TTSActivity::class.java))
            }
        }
    }

    /**
     * 观察模型初始化进度：未完成时显示进度条，完成后隐藏并刷新声纹列表。
     */
    private fun observeModelInit() {
        binding.modelInitLayout.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                ModelManager.ensureModelsInitialized { progress ->
                    binding.modelInitBar.setProgressCompat(progress, true)
                    binding.modelInitText.text = getString(R.string.model_init, progress)
                }
                isModelReady = true
                binding.modelInitLayout.visibility = View.GONE
                refreshVoices()
            } catch (t: Throwable) {
                binding.modelInitBar.visibility = View.GONE
                binding.modelInitText.text = getString(R.string.model_init_error, t.message ?: "")
            }
        }
    }

    /**
     * 刷新已克隆声纹列表，决定文本朗读卡片可用性。
     */
    private fun refreshVoices() {
        lifecycleScope.launch {
            try {
                val engine = ModelManager.getEngine()
                val voices = withContext(Dispatchers.IO) { engine.listSavedVoices() }
                hasClonedVoice = voices.isNotEmpty()
                updateTtsCard()
            } catch (t: Throwable) {
                hasClonedVoice = false
                updateTtsCard()
            }
        }
    }

    private fun updateTtsCard() {
        val enabled = hasClonedVoice
        binding.ttsCard.isEnabled = enabled
        binding.ttsCard.alpha = if (enabled) 1f else 0.5f
        binding.ttsSubtitle.text = if (enabled) {
            getString(R.string.tts_voice_subtitle_ready)
        } else {
            getString(R.string.tts_voice_subtitle_disabled)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
