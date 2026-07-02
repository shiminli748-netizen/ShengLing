package com.shengling.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.shengling.app.data.CloneStep
import com.shengling.app.databinding.ActivityCloneBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 声音克隆界面。
 *
 * 提供录音与文件选择两种参考音频输入，校验时长后启动克隆流程，
 * 以垂直步骤指示器 + 进度条展示进度，完成后可跳转至文本朗读。
 */
class CloneActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCloneBinding
    private val viewModel: CloneViewModel by viewModels()

    private val recorder = AudioRecorder()
    private var isRecording = false
    private var recordStartMs = 0L

    /** 音频文件选择器。 */
    private val pickAudio = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            handlePickedAudio(uri)
        }
    }

    /** 录音权限请求。 */
    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startRecording()
        } else {
            Toast.makeText(this, R.string.permission_record_denied, Toast.LENGTH_SHORT).show()
        }
    }

    /** 步骤指示器视图集合。 */
    private data class StepView(val icon: View, val text: View, val line: View?)

    private lateinit var steps: List<StepView>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityCloneBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()
        setupToolbar()
        setupStepper()
        bindControls()
        observeViewModel()
        setupBackPress()
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, sysBars.top, v.paddingRight, sysBars.bottom)
            insets
        }
    }

    private fun setupToolbar() {
        binding.toolbar.title = getString(R.string.clone_voice)
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationOnClickListener { handleBack() }
    }

    private fun setupStepper() {
        steps = listOf(
            StepView(binding.step1Icon, binding.step1Text, binding.step1Line),
            StepView(binding.step2Icon, binding.step2Text, binding.step2Line),
            StepView(binding.step3Icon, binding.step3Text, binding.step3Line),
            StepView(binding.step4Icon, binding.step4Text, null)
        )
        setStepState(activeIndex = -1, completedCount = 0)
    }

    private fun bindControls() {
        binding.recordButton.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                ensureMicPermissionAndRecord()
            }
        }
        binding.selectFileButton.setOnClickListener {
            pickAudio.launch("audio/*")
        }
        binding.startCloneButton.setOnClickListener {
            viewModel.startCloning()
        }
        binding.testNowButton.setOnClickListener {
            startActivity(Intent(this, TTSActivity::class.java))
            finish()
        }
        binding.retryButton.setOnClickListener {
            viewModel.reset()
            binding.successLayout.visibility = View.GONE
            binding.progressLayout.visibility = View.GONE
            updateCloneButton()
        }
    }

    private fun observeViewModel() {
        viewModel.selectedAudioPath.observe(this) { path ->
            updateCloneButton()
            if (path != null) {
                binding.audioInfoLayout.visibility = View.VISIBLE
                binding.audioFileName.text = path.substringAfterLast('/')
            } else {
                binding.audioInfoLayout.visibility = View.GONE
            }
        }

        viewModel.audioInfo.observe(this) { info ->
            if (info.isNotEmpty()) {
                binding.audioDuration.text = info
            }
        }

        viewModel.audioDuration.observe(this) { duration ->
            // 时长已在 audioInfo 中显示
        }

        viewModel.cloneStep.observe(this) { step ->
            updateProgress(step)
        }

        viewModel.savedVoiceName.observe(this) { name ->
            if (name != null) {
                binding.startCloneButton.isEnabled = false
            }
        }
    }

    // ===================== 录音 =====================

    private fun ensureMicPermissionAndRecord() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            startRecording()
        } else {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        val outPath = getStreamPath("recording.wav")
        recorder.onAmplitude = { amp ->
            // 录音在 IO 线程触发回调，更新 UI 需切回主线程
            runOnUiThread { binding.recordAmpBar.progress = (amp * 100).toInt() }
        }
        val ok = recorder.start(outPath)
        if (ok) {
            isRecording = true
            recordStartMs = System.currentTimeMillis()
            binding.recordButton.text = getString(R.string.stop_recording)
            binding.recordAmpBar.visibility = View.VISIBLE
            binding.recordingIndicator.visibility = View.VISIBLE
            observeRecordingDuration()
        } else {
            Toast.makeText(this, R.string.record_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        val path = recorder.stop()
        isRecording = false
        binding.recordButton.text = getString(R.string.record_audio)
        binding.recordAmpBar.visibility = View.GONE
        binding.recordingIndicator.visibility = View.GONE
        if (path != null) {
            viewModel.selectRecordedAudio(path)
        } else {
            Toast.makeText(this, R.string.record_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeRecordingDuration() {
        lifecycleScope.launch {
            while (isRecording) {
                val sec = ((System.currentTimeMillis() - recordStartMs) / 1000).toInt()
                binding.recordDurationText.text = getString(R.string.recording_duration, sec)
                delay(200)
            }
        }
    }

    private fun handlePickedAudio(uri: Uri) {
        binding.audioFileName.text = getString(R.string.loading)
        viewModel.selectAudioFile(uri)
    }

    private fun getStreamPath(name: String): String {
        val dir = filesDir
        return java.io.File(dir, name).absolutePath
    }

    // ===================== 进度与步骤 =====================

    private fun updateCloneButton() {
        val hasAudio = viewModel.selectedAudioPath.value != null
        val step = viewModel.cloneStep.value
        val inProgress = step is CloneStep.AnalyzingAudio ||
                step is CloneStep.ExtractingVoiceprint ||
                step is CloneStep.GeneratingModel
        binding.startCloneButton.isEnabled = hasAudio && !inProgress
    }

    private fun updateProgress(step: CloneStep) {
        val pct = step.progress
        binding.progressBar.setProgressCompat(pct, true)
        binding.progressText.text = step.message
        binding.progressBar.visibility = View.VISIBLE
        binding.progressText.visibility = View.VISIBLE

        when (step) {
            is CloneStep.Idle, is CloneStep.ModelInitializing -> {
                setStepState(activeIndex = -1, completedCount = if (viewModel.selectedAudioPath.value != null) 1 else 0)
                if (step !is CloneStep.ModelInitializing) {
                    binding.progressLayout.visibility = View.GONE
                } else {
                    binding.progressLayout.visibility = View.VISIBLE
                }
                updateCloneButton()
            }
            is CloneStep.AnalyzingAudio -> {
                binding.progressLayout.visibility = View.VISIBLE
                setStepState(activeIndex = 1, completedCount = 1)
                updateCloneButton()
            }
            is CloneStep.ExtractingVoiceprint -> {
                binding.progressLayout.visibility = View.VISIBLE
                setStepState(activeIndex = 2, completedCount = 2)
            }
            is CloneStep.GeneratingModel -> {
                binding.progressLayout.visibility = View.VISIBLE
                setStepState(activeIndex = 3, completedCount = 3)
            }
            is CloneStep.Complete -> {
                setStepState(activeIndex = -1, completedCount = 4)
                binding.successLayout.visibility = View.VISIBLE
                binding.progressLayout.visibility = View.GONE
                binding.startCloneButton.isEnabled = false
            }
            is CloneStep.Error -> {
                setStepState(activeIndex = -1, completedCount = 0, error = true)
                updateCloneButton()
                Toast.makeText(this, step.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 更新垂直步骤指示器状态。
     *
     * @param activeIndex 当前激活的步骤（1-based），-1 表示无
     * @param completedCount 已完成的步骤数
     * @param error 是否处于错误状态
     */
    private fun setStepState(activeIndex: Int, completedCount: Int, error: Boolean = false) {
        for ((index, stepView) in steps.withIndex()) {
            val stepNumber = index + 1
            when {
                stepNumber <= completedCount -> {
                    markStep(stepView, state = StepState.COMPLETE)
                }
                stepNumber == activeIndex -> {
                    markStep(stepView, state = if (error) StepState.ERROR else StepState.ACTIVE)
                }
                else -> {
                    markStep(stepView, state = StepState.PENDING)
                }
            }
            // 连接线：已完成则高亮
            stepView.line?.let { line ->
                line.alpha = if (stepNumber <= completedCount) 1f else 0.3f
            }
        }
    }

    private enum class StepState { PENDING, ACTIVE, COMPLETE, ERROR }

    private fun markStep(stepView: StepView, state: StepState) {
        val (iconColor, textColor, iconText) = when (state) {
            StepState.PENDING -> Triple(
                getColor(R.color.step_pending),
                getColor(R.color.step_pending),
                (steps.indexOf(stepView) + 1).toString()
            )
            StepState.ACTIVE -> Triple(
                getColor(R.color.step_active),
                getColor(R.color.step_active),
                (steps.indexOf(stepView) + 1).toString()
            )
            StepState.COMPLETE -> Triple(
                getColor(R.color.step_complete),
                getColor(R.color.step_complete),
                "✓"
            )
            StepState.ERROR -> Triple(
                getColor(R.color.step_error),
                getColor(R.color.step_error),
                "!"
            )
        }
        if (stepView.icon is android.widget.TextView) {
            stepView.icon.text = iconText
            stepView.icon.backgroundTintList = android.content.res.ColorStateList.valueOf(iconColor)
        }
        if (stepView.text is android.widget.TextView) {
            stepView.text.setTextColor(textColor)
        }
    }

    // ===================== 返回拦截 =====================

    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBack()
            }
        })
    }

    private fun handleBack() {
        val step = viewModel.cloneStep.value
        val inProgress = step is CloneStep.AnalyzingAudio ||
                step is CloneStep.ExtractingVoiceprint ||
                step is CloneStep.GeneratingModel
        if (isRecording) {
            stopRecording()
            return
        }
        if (inProgress) {
            AlertDialog.Builder(this)
                .setTitle(R.string.cancel_clone_title)
                .setMessage(R.string.cancel_clone_message)
                .setPositiveButton(R.string.confirm) { _, _ -> finish() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) {
            recorder.cancel()
            isRecording = false
        }
        recorder.release()
    }
}
