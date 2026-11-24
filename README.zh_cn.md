# LingoFloat

**Android 实时翻译悬浮字幕** —— 监听手机内部音频或麦克风，实时在屏幕悬浮窗显示双语字幕，可选同步朗读译文（TTS）。

识别、翻译、语音合成三个环节全部走 **OpenAI 兼容 API**。每个环节独立配置服务商 / Base URL / API Key / 模型，可随意混搭官方服务、第三方中转或自建模型。

[English](README.md) | 中文

## 特性

- 🎧 **两种音频来源**
  - **麦克风**：环境声、外放声音、面对面交谈
  - **内部音频**：通过 Android 10+ `AudioPlaybackCapture` 捕获其他 App 播放的声音（视频、播客、直播）
- 💬 **悬浮双语字幕**：全局悬浮窗显示原文 + 译文，可折叠可关闭；**按住任意位置即可拖动**（自动区分点击与拖动，按钮仍可点击）
- 📏 **紧凑模式**：只显示两行（一行原文 + 一行译文）；长行自动跑马灯滚动；隐藏标题栏与状态行；长按 500ms 恢复完整模式；选择会被记住
- 🔊 **朗读译文**：可选 TTS 播放，队列丢弃积压以保证实时；内录模式会排除本 App 自身音频，TTS 不会被二次识别
- ⚙️ **三套独立 API 配置**（均为 OpenAI 协议）：

  | 环节 | 接口 | 默认模型 |
  |---|---|---|
  | 语音识别 | `POST /audio/transcriptions` | `FunAudioLLM/SenseVoiceSmall` |
  | 翻译 | `POST /chat/completions` | `Qwen/Qwen2.5-7B-Instruct` |
  | 语音合成 | `POST /audio/speech` | `FunAudioLLM/CosyVoice2-0.5B` |

- 🏭 **服务商预设**：SiliconFlow（默认）、OpenAI、MiMo，或任意 OpenAI 兼容端点 —— 选好服务商会自动填入 Base URL，仍可手动修改；TTS 选 MiMo 会自动配置 chat-completions 格式并填入 `mimo-v2.5-tts`
- ⏱️ **请求超时保护**：整次请求硬性限时（默认 30s）；卡住的请求会被跳过，不阻塞流水线
- ✂️ **句子级分段**：说话过程中每约 1.5s 用 STT 接口探测一次累积音频，一旦出现完整句子（。！？!?… 等）就送翻译 —— 不做定长切分（仅 60s 兜底）。VAD 噪声底会跟随背景音，让停顿干净地结束一句话
- ⏳ **可读的节奏**：每一对（原文、译文）一起显示，并至少停留 2 秒后才出现下一对
- 🌍 **i18n**：英文（默认）与中文，可在设置页切换；目标语言可手输任意语言（简体中文 / English / 日本語…）
- 🔒 API Key 仅保存在本机，不会发送给第三方

## 工作原理

```
麦克风 / 内部音频（16kHz PCM）
        |
   能量 VAD（自适应噪声底，240ms 预录，
   600ms 静音结束一句）
        |
   每约 1.5s：用目前累积音频探测 STT
        |
   检测到完整句子（。！？!?…）--> 翻译队列
   （一句话结束时冲掉剩余尾部）
        |
   Chat Completions --> （原文，译文）对
        |
   显示队列：每对一起展示，停留 >= 2s
        |
   TTS API（可选）--> mp3 队列，顺序播放
```

- 前台服务（`mediaProjection` / `microphone` 类型），通知栏有「停止」快捷操作
- 上一次探测未返回时跳过本次探测，因此慢速 STT 接口不会堆积
- 句子输出会检查两次探测之间的前缀稳定性（Whisper 可能改写前文）；一句话结束的那次总会把剩余内容冲出来

## 构建

需要 **JDK 17**、Android SDK（compileSdk 34）、Gradle 8.7+（已带 wrapper）。

```bash
# 用 Android Studio 打开工程，或命令行：
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

已预置国内镜像（阿里云 Maven、腾讯 Gradle 分发）。

- `minSdk 29`（Android 10 —— 音频捕获回放的最低版本）
- `targetSdk 34`

## 使用

1. 打开 App 填写各 API 配置（不需要 TTS 就把 TTS 留空、开关关掉），然后**保存配置**
2. 选择音频来源：麦克风 / 内部音频
3. 点**开始**并按提示授权：
   - 悬浮窗权限（跳转系统设置）
   - 麦克风权限
   - 通知权限（Android 13+）
   - 内部音频模式：系统录屏授权弹窗（仅捕获音频，不保存录屏）
4. 悬浮窗出现双语字幕。按住任意位置拖动；「紧凑」缩成两行；紧凑模式下长按恢复完整模式；「✕」或通知栏的停止操作结束本场

### 设置页结构

| 分区 | 内容 |
|---|---|
| 状态 | 运行状态 + 开始/停止、目标语言、朗读译文（TTS） |
| 音频 | 音频来源、拾音触发阈值 |
| 悬浮窗 | 紧凑模式、显示原文、原文字号、译文字号 |
| 语音识别 | 服务商、Base URL、API Key、模型、源语言 |
| 翻译 | 服务商、Base URL、API Key、模型 |
| 语音合成 | 服务商、Base URL、API Key、模型、音色、语速 |
| 设置 | 界面语言（English / 中文）、保存 |

### 服务商示例

Base URL 填到 `/v1` 这一层即可（只填主机名会自动补 `/v1`）：

| 服务 | 识别 | 翻译 | TTS |
|---|---|---|---|
| SiliconFlow（默认） | `https://api.siliconflow.cn/v1` + `FunAudioLLM/SenseVoiceSmall` | 同左 + `Qwen/Qwen2.5-7B-Instruct` | 同左 + `FunAudioLLM/CosyVoice2-0.5B` |
| OpenAI | `https://api.openai.com/v1` + `whisper-1` | 同左 + `gpt-4o-mini` | 同左 + `tts-1` |
| Groq（STT 很快） | `https://api.groq.com/openai/v1` + `whisper-large-v3-turbo` | 同左 + `llama-3.3-70b-versatile` | — |
| 自建 faster-whisper | `http://<host>:8000/v1` + `Systran/faster-whisper-large-v3` | — | — |
| 小米 MiMo（TTS） | — | — | `https://api.xiaomimimo.com/v1` + `mimo-v2.5-tts`（chat/completions 格式，音色：`mimo_default` / `Chloe` / `Mia` / `冰糖`…） |

> 可以混搭：例如识别用 Groq（快）、翻译用 DeepSeek（便宜）、TTS 用 OpenAI（音色好）。
> SiliconFlow 的 TTS，直接填音色名如 `alex` 时会自动作为 `FunAudioLLM/CosyVoice2-0.5B:alex` 发送。

## 已知限制

- **内录受策略限制**：App 可通过 `allowAudioPlaybackCapture=false` 主动拒绝（部分音视频平台、微信语音）；DRM 内容永远无法捕获。这些情况请回退到麦克风 + 外放。
- 每次内录都要过一次系统授权弹窗（Android 14 的 MediaProjection 安全要求）。
- VAD 是基于能量的自适应噪声底；嘈杂环境请调高触发阈值，安静环境调低。
- 句子检测依赖 STT 模型输出标点；很少输出标点的模型会退化为按句（静音）切分。
- 每次探测都会把逐渐变长的一句话重新识别一遍，所以 STT 用量高于一次性切分 —— 建议用快且便宜的模型（SenseVoice、whisper-turbo）。
- 显示节奏每句最多额外停留 2 秒；语速快时显示队列可能落后于音频。
- TTS 支持两种格式，按配置切换：标准 `POST /audio/speech`（二进制 mp3），或 `POST /chat/completions` 配 `modalities:["text","audio"]` 用于音频输出模型（MiMo-V2.5-TTS、gpt-4o-audio-preview）。chat 模式下要朗读的文本放在 **assistant** 消息里（MiMo 的要求；user 消息只承载风格指令），音频以 wav 请求，从 base64 的 `choices[0].message.audio.data` 解码。语速只对 `/audio/speech` 格式生效。

## 工程结构

```
app/src/main/java/com/lingofloat/app/
├── MainActivity.kt        # Compose 设置页 + 授权/启动流程
├── CaptionService.kt      # 前台服务、流水线编排
├── SettingsStore.kt       # 设置模型 + 持久化（含旧版迁移）
├── SentenceSplitter.kt    # 基于标点的句子提取
├── I18n.kt                # 中英文字符串表（默认英文）
├── audio/
│   ├── AudioCapturer.kt   # 麦克风 / 内部音频捕获
│   ├── VadSegmenter.kt    # 带自适应噪声底的能量 VAD
│   └── WavUtil.kt         # PCM -> WAV
├── api/ApiClients.kt      # Whisper / Chat / TTS 客户端
├── overlay/SubtitleOverlay.kt  # 悬浮双语字幕窗
└── tts/TtsPlayer.kt       # TTS 播放队列
```

## 界面语言

界面支持中英双语：默认英文，可在「设置」分区切换为简体中文。文案集中在
`app/src/main/java/com/lingofloat/app/I18n.kt`，每个条目同时提供英文与中文。

## 许可

MIT
