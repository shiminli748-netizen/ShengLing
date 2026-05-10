package com.shengling.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.shengling.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStart.setOnClickListener {
            startActivity(Intent(this, CloneActivity::class.java))
        }

        initModels()
    }

    private fun initModels() {
        binding.layoutInitStatus.visibility = View.VISIBLE
        binding.btnStart.isEnabled = false

        lifecycleScope.launch {
            if (ModelManager.areModelsReady(this@MainActivity)) {
                val success = VoiceEngine.initEngine(this@MainActivity)
                runOnUiThread {
                    if (success) {
                        binding.textInitStatus.text = getString(R.string.status_init_done)
                        binding.layoutInitStatus.visibility = View.GONE
                        binding.btnStart.isEnabled = true
                    } else {
                        binding.textInitStatus.text = getString(R.string.error_init_failed)
                    }
                }
                return@launch
            }

            binding.textInitStatus.text = getString(R.string.status_init_copying)
            val totalFiles = ModelManager.getTotalFiles()

            val success = ModelManager.downloadModels(this@MainActivity) { fileName, current, total ->
                runOnUiThread {
                    binding.progressInit.max = total
                    binding.progressInit.setProgress(current, true)
                    val percent = if (total > 0) (current * 100 / total) else 0
                    binding.textInitStatus.text = "下载模型中… $percent% ($current/$total)\n$fileName"
                }
            }

            if (success) {
                binding.textInitStatus.text = getString(R.string.status_init_loading)
                val engineSuccess = VoiceEngine.initEngine(this@MainActivity)
                runOnUiThread {
                    if (engineSuccess) {
                        binding.textInitStatus.text = getString(R.string.status_init_done)
                        binding.layoutInitStatus.visibility = View.GONE
                        binding.btnStart.isEnabled = true
                    } else {
                        binding.textInitStatus.text = getString(R.string.error_init_failed)
                    }
                }
            } else {
                runOnUiThread {
                    binding.textInitStatus.text = getString(R.string.error_init_failed)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AudioUtils.stopPlayback()
    }
}
