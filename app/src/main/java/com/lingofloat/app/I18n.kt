package com.lingofloat.app

/**
 * Minimal in-app i18n. English is the default; Chinese is opt-in.
 * Usage: `val t = Strings(settings.language)` then `t.someKey`.
 * Format strings use String.format placeholders (%d / %s / %.1f).
 */
class Strings(private val zh: Boolean) {

    private fun s(en: String, cn: String): String = if (zh) cn else en

    // ---- Status ----
    val status get() = s("Status", "状态")
    val running get() = s("Running", "运行中")
    val stopped get() = s("Stopped", "未运行")
    val start get() = s("Start", "开始")
    val stop get() = s("Stop", "停止")
    val targetLanguage get() = s("Target language", "目标语言")
    val speakTranslation get() = s("Speak translation (TTS)", "朗读译文（TTS）")
    val statusHint get() = s(
        "Settings are saved automatically on start. Internal audio needs a one-time system capture consent.",
        "启动前自动保存配置；内录模式需授权一次系统录屏弹窗。"
    )

    // ---- Audio ----
    val audio get() = s("Audio", "音频")
    val audioSource get() = s("Audio source", "音频来源")
    val microphone get() = s("Microphone (ambient / speaker)", "麦克风（环境声 / 外放）")
    val internalAudio get() = s("Internal audio (videos / podcasts)", "手机内部音频（视频 / 播客）")
    val internalHint get() = s(
        "Only apps that allow capture can be recorded. WeChat calls and DRM content cannot be captured.",
        "内录仅支持允许被捕获的 App；微信语音、DRM 内容无法捕获。"
    )
    val triggerThresholdFmt get() = s("Trigger threshold: %d (lower = more sensitive)", "拾音触发阈值：%d（越小越灵敏）")
    val maxSegmentFmt get() = s("Max segment: %ds (force-split continuous speech)", "最长分段：%d 秒（连续说话强制断句）")

    // ---- Overlay ----
    val overlay get() = s("Overlay", "悬浮窗")
    val compactMode get() = s("Compact mode (two lines only)", "紧凑模式（仅两行）")
    val showOriginal get() = s("Show original text", "显示原文")
    val originalFontSizeFmt get() = s("Original font size: %dsp", "原文字号：%dsp")
    val translationFontSizeFmt get() = s("Translation font size: %dsp", "译文字号：%dsp")
    val overlayHint get() = s(
        "Press and drag anywhere to move. Compact mode auto-scrolls long lines; long-press to restore full mode.",
        "任意位置按住拖动；紧凑模式超行自动滚动，长按恢复标准模式。"
    )

    // ---- API sections ----
    val speechRecognition get() = s("Speech recognition", "语音识别")
    val translation get() = s("Translation", "翻译")
    val speechSynthesis get() = s("Speech synthesis", "语音合成")
    val provider get() = s("Provider", "供应商")
    val baseUrl get() = s("Base URL", "API 地址")
    val apiKey get() = s("API key", "API 密钥")
    val model get() = s("Model", "模型")
    val sourceLanguage get() = s("Source language (blank = auto-detect)", "源语言（留空自动检测）")
    val voice get() = s("Voice", "音色")
    val speedFmt get() = s("Speech rate: %.1fx", "语速：%.1fx")
    val providerCompatible get() = s("OpenAI-compatible", "OpenAI 兼容")

    // ---- Settings ----
    val settings get() = s("Settings", "设置")
    val language get() = s("Language", "语言")
    val save get() = s("Save settings", "保存配置")
    val saved get() = s("Settings saved", "配置已保存")

    // ---- Overlay runtime ----
    val waitingVoice get() = s("Waiting for speech…", "等待语音…")
    val listening get() = s("Listening…", "聆听中…")
    val transcribing get() = s("Transcribing…", "识别中…")
    val translating get() = s("Translating…", "翻译中…")
    val errorFmt get() = s("Error: %s", "出错：%s")
    val compact get() = s("Compact", "紧凑")
    val collapse get() = s("Collapse", "收起")
    val expand get() = s("Expand", "展开")
    val notConfigured get() = s("API not configured", "未配置 API")
    val configureFirst get() = s(
        "Configure speech recognition & translation APIs first",
        "请先配置语音识别与翻译 API"
    )
    val startFailed get() = s("Start failed", "启动失败")
    val captureFailedFmt get() = s("Capture failed: %s", "采集失败：%s")

    // ---- Service / notification ----
    val notifChannel get() = s("Live captions", "实时字幕")
    val notifTitle get() = s("LingoFloat captions running", "LingoFloat 字幕运行中")
    val notifText get() = s("Listening and generating bilingual captions", "正在监听音频并生成双语字幕")

    // ---- Toasts / errors ----
    val toastOverlayPerm get() = s("Grant the overlay permission, then tap Start again", "请授予悬浮窗权限后再次点击开始")
    val toastRecordPerm get() = s("Microphone permission is required", "需要录音权限")
    val toastProjectionDenied get() = s("Screen/audio capture consent denied", "未授权屏幕/音频捕获")
    val toastStarted get() = s("Started. Captions appear in the overlay", "已启动，字幕显示在悬浮窗中")
    val toastFillApis get() = s(
        "Fill in base URL and model for recognition & translation",
        "请先填写语音识别与翻译 API 的地址和模型"
    )
    val toastFillTts get() = s("TTS is enabled; fill in its API settings", "已开启 TTS，请填写 TTS 配置")
    val missingProjection get() = s("Missing capture consent data", "缺少录屏授权数据")
    val projectionFailed get() = s("Failed to create MediaProjection", "MediaProjection 创建失败")
    val micInitFailed get() = s("Microphone init failed (device busy?)", "麦克风初始化失败（被占用？）")
    val internalInitFailed get() = s("Internal capture init failed", "内录初始化失败")

    companion object {
        const val EN = "en"
        const val ZH = "zh"
        fun of(code: String): Strings = Strings(code == ZH)
    }
}
