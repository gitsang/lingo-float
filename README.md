# LingoFloat

**Real-time translation captions for Android** — listens to internal phone audio or the microphone and shows bilingual subtitles in a floating overlay, with optional spoken translation (TTS).

Recognition, translation and speech synthesis all go through **OpenAI-compatible APIs**. Each stage has its own provider / base URL / API key / model, so you can freely mix official services, third-party gateways or self-hosted models.

## Features

- 🎧 **Two audio sources**
  - **Microphone**: ambient sound, speaker playback, face-to-face conversation
  - **Internal audio**: capture what other apps play (videos, podcasts, streams) via Android 10+ `AudioPlaybackCapture`
- 💬 **Floating bilingual captions**: global overlay with original + translation, collapsible and closable; **press and drag anywhere to move** (clicks vs. drags are distinguished automatically, buttons stay clickable)
- 📏 **Compact mode**: two lines only (one original + one translation); long lines auto-scroll as a marquee; title bar and status line hidden; long-press 500ms to restore full mode; the choice is persisted
- 🔊 **Spoken translation**: optional TTS playback with a backlog-dropping queue to stay real-time; internal capture excludes the app's own audio so TTS is never re-transcribed
- ⚙️ **Three independent API configs** (all OpenAI protocol):

  | Stage | Endpoint | Default model |
  |---|---|---|
  | Speech recognition | `POST /audio/transcriptions` | `FunAudioLLM/SenseVoiceSmall` |
  | Translation | `POST /chat/completions` | `Qwen/Qwen2.5-7B-Instruct` |
  | Speech synthesis | `POST /audio/speech` | `FunAudioLLM/CosyVoice2-0.5B` |

- 🏭 **Provider presets**: SiliconFlow (default), OpenAI, or any OpenAI-compatible endpoint — picking a provider fills the base URL, which stays editable
- ⏱️ **Request timeout protection**: hard cap on total request time (default 30s); a stuck request is skipped without blocking the pipeline
- ✂️ **Adaptive segmentation**: the VAD noise floor tracks background audio, so a pause in speech ends the segment immediately; max segment length defaults to 8s (3–15s adjustable)
- 🌍 **i18n**: English (default) and Chinese, switchable in Settings; any target language can be typed in (简体中文 / English / 日本語…)
- 🔒 API keys are stored on-device only and never sent to third parties

## How it works

```
Microphone / internal audio (16kHz PCM)
        |
   Energy VAD segmentation (600ms silence cuts, max 8s, 240ms pre-roll,
   adaptive noise floor for background music)
        |
   Whisper-style API --> original text --> overlay line 1
        |
   Chat Completions  --> translation --> overlay line 2
        |
   TTS API (optional) --> mp3 queue, sequential playback
```

- Foreground service (`mediaProjection` / `microphone` type) with a one-tap Stop action in the notification
- Recognition + translation run serially in one coroutine so captions never arrive out of order

## Build

Requirements: **JDK 17**, Android SDK (compileSdk 34), Gradle 8.7+ (wrapper included).

```bash
# Open the project in Android Studio, or from the command line:
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

Mirrors for China are preconfigured (Aliyun Maven, Tencent Gradle distribution).

- `minSdk 29` (Android 10 — the minimum for audio playback capture)
- `targetSdk 34`

## Usage

1. Open the app and fill in the API sections (leave TTS empty and its switch off if you don't need it), then **Save settings**
2. Pick the audio source: microphone / internal audio
3. Tap **Start** and grant the prompted permissions:
   - Overlay permission (opens system settings)
   - Microphone permission
   - Notification permission (Android 13+)
   - Internal audio mode: the system capture consent dialog (audio only, no screen recording is stored)
4. Bilingual captions appear in the overlay. Drag anywhere to move; "Compact" shrinks it to two lines; long-press in compact mode restores full mode; "✕" or the notification's Stop action ends the session.

### Settings page layout

| Section | Contents |
|---|---|
| Status | Running state + Start/Stop, target language, speak translation (TTS) |
| Audio | Audio source, trigger threshold, max segment length |
| Overlay | Compact mode, show original, original font size, translation font size |
| Speech recognition | Provider, base URL, API key, model, source language |
| Translation | Provider, base URL, API key, model |
| Speech synthesis | Provider, base URL, API key, model, voice, speech rate |
| Settings | Language (English / 中文), save |

### Provider examples

Base URLs are filled up to `/v1` (a bare host gets `/v1` appended automatically):

| Service | Recognition | Translation | TTS |
|---|---|---|---|
| SiliconFlow (default) | `https://api.siliconflow.cn/v1` + `FunAudioLLM/SenseVoiceSmall` | same + `Qwen/Qwen2.5-7B-Instruct` | same + `FunAudioLLM/CosyVoice2-0.5B` |
| OpenAI | `https://api.openai.com/v1` + `whisper-1` | same + `gpt-4o-mini` | same + `tts-1` |
| Groq (very fast STT) | `https://api.groq.com/openai/v1` + `whisper-large-v3-turbo` | same + `llama-3.3-70b-versatile` | — |
| Self-hosted faster-whisper | `http://<host>:8000/v1` + `Systran/faster-whisper-large-v3` | — | — |

> Mix and match: e.g. Groq for recognition (fast), DeepSeek for translation (cheap), OpenAI for TTS (good voices).
> For SiliconFlow TTS a bare voice name like `alex` is automatically sent as `FunAudioLLM/CosyVoice2-0.5B:alex`.

## Known limitations

- **Internal capture is policy-bound**: apps can opt out via `allowAudioPlaybackCapture=false` (some music/video platforms, WeChat calls); DRM-protected content can never be captured. Fall back to microphone + speaker playback in those cases.
- Every internal-capture session needs one system consent dialog (Android 14 MediaProjection security requirement).
- The VAD is energy-based with an adaptive noise floor; raise the trigger threshold in noisy environments, lower it in quiet ones. Shorten "max segment" if captions feel too long.
- Translation is per segment (not word-by-word streaming); latency ≈ segment length + two API round trips. Low-latency models help a lot.
- TTS requests `response_format=mp3`, supported by OpenAI and most compatible services.

## Project structure

```
app/src/main/java/com/lingofloat/app/
├── MainActivity.kt        # Compose settings page + permission/start flow
├── CaptionService.kt      # Foreground service, pipeline orchestration
├── SettingsStore.kt       # Settings model + persistence (+ legacy migration)
├── I18n.kt                # English/Chinese string table (English default)
├── audio/
│   ├── AudioCapturer.kt   # Microphone / internal audio capture
│   ├── VadSegmenter.kt    # Energy VAD with adaptive noise floor
│   └── WavUtil.kt         # PCM -> WAV
├── api/ApiClients.kt      # Whisper / Chat / TTS clients
├── overlay/SubtitleOverlay.kt  # Floating bilingual caption window
└── tts/TtsPlayer.kt       # TTS playback queue
```

## License

MIT
