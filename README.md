# 声灵 (ShengLing)

> 完全离线的 Android AI 声音克隆工具 — 内置模型，开箱即用

上传一段 3-15 秒参考音频，软件学习声音特征后，输入任意文本即可用该声音朗读。所有 AI 推理在本地完成，无需联网。

## 功能概览

- **声音克隆**：录制或选择 3-15 秒参考音频，AI 提取声纹特征
- **文本朗读**：输入任意文本，用克隆的声音朗读出来
- **完全离线**：ONNX 模型内置在 APK 中，不依赖任何网络服务
- **进度可视化**：克隆过程分步显示（分析声音 → 提取声纹 → 生成模型）
- **保存/分享**：生成的音频可保存到本地或分享

## 技术栈

| 组件 | 技术 |
|------|------|
| 开发语言 | Kotlin |
| 最低版本 | Android 10 (API 29) |
| 目标 SDK | API 34 |
| UI 框架 | Material Design 3 (深色主题 + 动态取色) |
| 推理引擎 | ONNX Runtime for Android |
| 声纹提取 | speaker_encoder.onnx (GE2E/ECAPA-TDNN) |
| 语音合成 | vits_decoder.onnx (VITS) |
| 音频处理 | 纯 Kotlin 实现 (FFT / 梅尔频谱 / WAV I/O) |

## 项目结构

```
ShengLing/
├── app/
│   ├── src/main/
│   │   ├── assets/models/              # 内置 AI 模型文件
│   │   │   ├── config.json             # 模型配置
│   │   │   └── README.md              # 模型准备说明
│   │   ├── java/com/shengling/app/
│   │   │   ├── MainActivity.kt        # 主界面（双卡片入口）
│   │   │   ├── CloneActivity.kt        # 声音克隆（含进度条 + 步骤指示器）
│   │   │   ├── TTSActivity.kt         # 文本转语音（输入 + 播放 + 保存/分享）
│   │   │   ├── ModelManager.kt        # 模型初始化（assets → 内部存储）
│   │   │   ├── CloneEngine.kt         # ONNX 推理核心（声纹提取 + 语音合成）
│   │   │   ├── AudioRecorder.kt       # 录音（AudioRecord 16kHz）
│   │   │   ├── AudioPlayer.kt         # 播放（AudioTrack 流式）
│   │   │   ├── AudioUtils.kt          # 音频处理（WAV/FFT/梅尔频谱）
│   │   │   ├── TextTokenizer.kt       # 文本分词（兼容 HF 格式）
│   │   │   ├── CloneViewModel.kt      # 克隆流程状态管理
│   │   │   ├── TtsViewModel.kt        # TTS 流程状态管理
│   │   │   ├── VoiceCloneApp.kt       # Application 入口
│   │   │   └── data/
│   │   │       ├── ModelConfig.kt     # 模型配置数据类
│   │   │       └── CloneProgress.kt   # 进度状态密封类
│   │   ├── res/                        # Material Design 3 资源
│   │   │   ├── layout/                # 3 个界面布局
│   │   │   ├── values/                 # 颜色/字符串/主题
│   │   │   ├── drawable/               # 矢量图标
│   │   │   └── xml/                   # FileProvider 配置
│   │   └── AndroidManifest.xml
│   ├── build.gradle
│   └── proguard-rules.pro
├── build.gradle
├── settings.gradle
├── gradle.properties
└── deploy.sh                           # 一键部署脚本
```

## 构建步骤

### 前置条件

- Android Studio (Hedgehog 或更新)
- JDK 17
- Android SDK API 34
- 已准备好的模型文件（见 `app/src/main/assets/models/README.md`）

### 1. 放置模型文件

将以下文件放入 `app/src/main/assets/models/`：

```
speaker_encoder.onnx   (~40MB)
vits_decoder.onnx      (~60MB)
tokenizer.json         (~5MB)
config.json            (已包含)
```

### 2. 编译 Debug APK

```bash
cd ShengLing
./gradlew assembleDebug
# 输出: app/build/outputs/apk/debug/app-debug.apk
```

### 3. 编译 Release APK

```bash
./gradlew assembleRelease
# 输出: app/build/outputs/apk/release/app-release.apk
```

### 4. 安装到设备

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

## 部署到 GitHub

使用 `deploy.sh` 一键完成 Git 初始化、推送和 Release 发布：

```bash
# 1. 生成新的 GitHub Personal Access Token (Settings → Developer settings → Tokens)
# 2. 运行部署脚本
./deploy.sh
```

脚本会引导你输入 GitHub 用户名和 Token，自动完成：
- Git 仓库初始化和首次提交
- 远程仓库创建（通过 GitHub CLI）
- 代码推送到 main 分支
- Release APK 编译和上传

> **安全提示**：切勿将 Token 硬编码到代码或脚本中。`deploy.sh` 会提示输入，不会存储。

## 工作流程

```
用户选择/录制音频 (3-15s)
        ↓
  AI 提取声纹特征 ← speaker_encoder.onnx
        ↓
  保存声纹向量 (.bin)
        ↓
  用户输入文本
        ↓
  AI 生成语音 ← vits_decoder.onnx + 声纹向量
        ↓
  播放 / 保存 / 分享
```

## 界面设计

- **Material Design 3** 深色主题
- 支持 **Material You** 动态取色 (Android 12+)
- 大圆角卡片 (16-24dp)
- 分步进度指示器（垂直 stepper）
- 录音实时波形显示

## 权限说明

| 权限 | 用途 |
|------|------|
| RECORD_AUDIO | 录制参考音频 |
| READ_MEDIA_AUDIO | 从文件选择音频 (Android 13+) |
| READ_EXTERNAL_STORAGE | 读取音频文件 (Android 12 及以下) |
| INTERNET | 预留（未来模型更新功能，当前完全离线） |

## 开源协议

MIT License
