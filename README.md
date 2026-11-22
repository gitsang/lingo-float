# LingoFloat

**Android 实时翻译悬浮字幕** —— 监听手机内部音频或麦克风，实时在屏幕悬浮窗显示双语字幕，可选同步朗读译文（TTS）。

识别、翻译、语音合成三个环节全部走 **OpenAI 兼容 API**，地址 / Key / 模型各自独立配置，可随意混搭官方服务、第三方中转或自建模型。

## 功能

- 🎧 **双音源**
  - **麦克风**：环境声、外放、面对面交谈
  - **内部音频**：直接捕获其他 App 播放的声音（视频、播客、直播），Android 10+ `AudioPlaybackCapture`
- 💬 **悬浮双语字幕**：全局悬浮窗，原文 + 译文同屏显示，可拖动、可折叠、可关闭，字号可调
- 🔊 **译文朗读**：可选 TTS 实时播放译文，队列自动丢弃积压保证实时性；内录模式自动排除自身声音，不会"自己翻自己"
- ⚙️ **三组独立 API 配置**（均为 OpenAI 协议）：
  | 环节 | 端点 | 默认模型示例 |
  |---|---|---|
  | 语音识别 | `POST /audio/transcriptions` | `whisper-1` |
  | 翻译 | `POST /chat/completions` | `gpt-4o-mini` |
  | 语音合成 | `POST /audio/speech` | `tts-1` |
- 🌍 目标语言任意填写（简体中文 / English / 日本語…），源语言可指定或自动检测
- 🔒 API Key 仅保存在本机 SharedPreferences，不上传任何第三方

## 工作原理

```
麦克风 / 内部音频 (16kHz PCM)
        │
   能量 VAD 分段（静音 600ms 断句，最长 15s，带 240ms pre-roll）
        │
   Whisper API ──→ 原文 ──→ 悬浮窗上行
        │
   Chat Completions ──→ 译文 ──→ 悬浮窗下行
        │
   TTS API（可选）──→ mp3 队列顺序播放
```

- 前台服务保活（`mediaProjection` / `microphone` 类型），通知栏一键停止
- 识别 + 翻译在单协程内串行，保证字幕顺序不乱

## 构建

环境要求：**JDK 17**、Android SDK（compileSdk 34）、Gradle 8.7+（仓库自带 wrapper）。

```bash
# Android Studio 直接打开本目录，或命令行：
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

国内网络已预置阿里云 Maven 镜像与腾讯 Gradle 镜像，开箱即拉。

- `minSdk 29`（Android 10，内录 API 的最低要求）
- `targetSdk 34`

## 使用

1. 打开 App，填写三组 API 配置（TTS 不用可留空并关闭开关），点 **保存配置**
2. 选择音源：麦克风 / 内部音频
3. 点 **开始**，按提示授权：
   - 悬浮窗权限（跳转系统设置）
   - 录音权限
   - 通知权限（Android 13+）
   - 内部音频模式：系统录屏授权弹窗（仅用于音频捕获，不录画面）
4. 悬浮窗出现双语字幕；拖标题栏移动，"收起"折叠，"✕"或通知栏"停止"结束

### API 配置示例

Base URL 填到 `/v1` 即可（只填域名会自动补 `/v1`）：

| 服务 | 语音识别 | 翻译 | TTS |
|---|---|---|---|
| OpenAI 官方 | `https://api.openai.com/v1` + `whisper-1` | 同左 + `gpt-4o-mini` | 同左 + `tts-1` |
| Groq（识别极快） | `https://api.groq.com/openai/v1` + `whisper-large-v3-turbo` | 同左 + `llama-3.3-70b-versatile` | — |
| 硅基流动 | `https://api.siliconflow.cn/v1` + `FunAudioLLM/SenseVoiceSmall` | 同左 + `Qwen/Qwen2.5-7B-Instruct` | 同左 + `FunAudioLLM/CosyVoice2-0.5B` |
| 自建 faster-whisper | `http://<主机>:8000/v1` + `Systran/faster-whisper-large-v3` | — | — |

> 三个环节可以用不同厂商：例如 Groq 做识别（快）、DeepSeek 做翻译（便宜）、OpenAI 做 TTS（音色好）。

## 已知限制

- **内录受系统策略约束**：App 可声明 `allowAudioPlaybackCapture=false` 拒绝被捕获（如部分音乐/视频平台、微信语音通话）；DRM 受保护内容一律不可捕获。此时可退回"麦克风 + 外放"模式。
- 每次启动内录都需要一次系统授权弹窗（Android 14 的 MediaProjection 安全要求）。
- VAD 为能量阈值法，嘈杂环境请把"拾音触发阈值"调大；安静环境调小更灵敏。
- 翻译按句段进行（非逐词流式），延迟约为"句长 + 两次 API 往返"，选用低延迟模型可显著改善。
- TTS 请求 `response_format=mp3`，需服务端支持（OpenAI 及主流兼容服务均支持）。

## 项目结构

```
app/src/main/java/com/lingofloat/app/
├── MainActivity.kt        # Compose 设置页 + 权限/启动流程
├── CaptionService.kt      # 前台服务，流水线编排
├── SettingsStore.kt       # 配置模型与持久化
├── audio/
│   ├── AudioCapturer.kt   # 麦克风 / 内部音频采集
│   ├── VadSegmenter.kt    # 能量 VAD 断句
│   └── WavUtil.kt         # PCM → WAV
├── api/ApiClients.kt      # Whisper / Chat / TTS 客户端
├── overlay/SubtitleOverlay.kt  # 悬浮双语字幕窗
└── tts/TtsPlayer.kt       # TTS 播放队列
```

## License

MIT
