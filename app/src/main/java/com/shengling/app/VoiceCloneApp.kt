package com.shengling.app

import android.app.Application
import android.util.Log
import com.google.android.material.color.DynamicColors
import com.shengling.app.data.CloneStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 声灵 (ShengLing) Application 入口。
 *
 * 负责在应用启动时初始化 [ModelManager]（首次启动会从 assets 拷贝 ONNX 模型文件到内部存储），
 * 启用 Material You 动态取色，并提供一个全局的 [SupervisorJob] 协程作用域，
 * 用于与 UI 无直接关联的后台任务。
 */
class VoiceCloneApp : Application() {

    /** 应用级协程作用域，使用 SupervisorJob 避免单个子协程失败导致整体取消。 */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 启用 Material You 动态取色（Android 12+），回退到主题色
        DynamicColors.applyToActivitiesIfAvailable(this)

        Log.i(TAG, "声灵 VoiceCloneApp onCreate, 开始初始化模型")

        // 在后台线程初始化模型文件，避免阻塞主线程
        appScope.launch {
            try {
                ModelManager.ensureModelsInitialized { progress ->
                    Log.i(TAG, "模型初始化进度: $progress%")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "模型初始化失败", t)
            }
        }
    }

    companion object {
        private const val TAG = "ShengLingApp"

        @Volatile
        private var instance: VoiceCloneApp? = null

        /** 获取全局 Application 实例。 */
        fun get(): VoiceCloneApp =
            instance ?: throw IllegalStateException("VoiceCloneApp 尚未创建")

        /** 当前模型初始化步骤，供 MainActivity 观察首次启动进度。 */
        val modelInitProgress: CloneStep
            get() = ModelManager.initProgress
    }
}
