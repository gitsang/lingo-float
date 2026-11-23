package com.lingofloat.app

import android.content.Context
import org.json.JSONObject
import java.net.URI

/** Connection settings for one OpenAI-compatible API. */
data class ApiConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val provider: String = PROVIDER_SILICONFLOW
) {
    /** Normalize base URL: trim trailing slash; append /v1 when only a host is given. */
    fun normalizedBase(): String {
        var b = baseUrl.trim().trimEnd('/')
        if (b.isEmpty()) return b
        val path = runCatching { URI(b).path }.getOrNull()
        if (path.isNullOrEmpty() || path == "/") b += "/v1"
        return b
    }

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank()

    fun toJson(): JSONObject = JSONObject()
        .put("baseUrl", baseUrl)
        .put("apiKey", apiKey)
        .put("model", model)
        .put("provider", provider)

    companion object {
        const val PROVIDER_SILICONFLOW = "siliconflow"
        const val PROVIDER_OPENAI = "openai"
        const val PROVIDER_COMPATIBLE = "compatible"

        /** Preset base URL for a provider; null keeps the current custom URL. */
        fun providerBase(provider: String): String? = when (provider) {
            PROVIDER_SILICONFLOW -> "https://api.siliconflow.cn/v1"
            PROVIDER_OPENAI -> "https://api.openai.com/v1"
            else -> null
        }

        fun fromJson(o: JSONObject?, def: ApiConfig): ApiConfig {
            if (o == null) return def
            return ApiConfig(
                baseUrl = o.optString("baseUrl", def.baseUrl),
                apiKey = o.optString("apiKey", def.apiKey),
                model = o.optString("model", def.model),
                provider = o.optString("provider", def.provider)
            )
        }
    }
}

data class AppSettings(
    val source: String = SOURCE_MIC,
    val ttsEnabled: Boolean = false,
    val showOriginal: Boolean = true,
    val compactMode: Boolean = false,
    val originalFontSize: Int = 13,
    val fontSize: Int = 18,
    val targetLang: String = "简体中文",
    val language: String = Strings.EN,
    val whisper: ApiConfig = ApiConfig(
        baseUrl = "https://api.siliconflow.cn/v1",
        model = "FunAudioLLM/SenseVoiceSmall",
        provider = ApiConfig.PROVIDER_SILICONFLOW
    ),
    val whisperLang: String = "",
    val translate: ApiConfig = ApiConfig(
        baseUrl = "https://api.siliconflow.cn/v1",
        model = "Qwen/Qwen2.5-7B-Instruct",
        provider = ApiConfig.PROVIDER_SILICONFLOW
    ),
    val tts: ApiConfig = ApiConfig(
        baseUrl = "https://api.siliconflow.cn/v1",
        model = "FunAudioLLM/CosyVoice2-0.5B",
        provider = ApiConfig.PROVIDER_SILICONFLOW
    ),
    val ttsVoice: String = "alex",
    val ttsSpeed: Float = 1.0f,
    val vadThreshold: Int = 300,
    val requestTimeoutSec: Int = 30
) {
    companion object {
        const val SOURCE_MIC = "mic"
        const val SOURCE_INTERNAL = "internal"
    }
}

/** Persistence via SharedPreferences + JSON. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("lingofloat_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val raw = prefs.getString("settings", null) ?: return AppSettings()
        return runCatching {
            val o = JSONObject(raw)
            val def = AppSettings()
            val legacy = !o.has("language")
            var settings = AppSettings(
                source = o.optString("source", def.source),
                ttsEnabled = o.optBoolean("ttsEnabled", def.ttsEnabled),
                showOriginal = o.optBoolean("showOriginal", def.showOriginal),
                compactMode = o.optBoolean("compactMode", def.compactMode),
                originalFontSize = o.optInt("originalFontSize", def.originalFontSize),
                fontSize = o.optInt("fontSize", def.fontSize),
                targetLang = o.optString("targetLang", def.targetLang),
                language = o.optString("language", def.language),
                whisper = ApiConfig.fromJson(o.optJSONObject("whisper"), def.whisper),
                whisperLang = o.optString("whisperLang", def.whisperLang),
                translate = ApiConfig.fromJson(o.optJSONObject("translate"), def.translate),
                tts = ApiConfig.fromJson(o.optJSONObject("tts"), def.tts),
                ttsVoice = o.optString("ttsVoice", def.ttsVoice),
                ttsSpeed = o.optDouble("ttsSpeed", def.ttsSpeed.toDouble()).toFloat(),
                vadThreshold = o.optInt("vadThreshold", def.vadThreshold),
                requestTimeoutSec = o.optInt("requestTimeoutSec", def.requestTimeoutSec)
            )
            if (legacy) {
                // One-time migration: old OpenAI defaults -> new SiliconFlow defaults.
                // Custom endpoints/models and API keys are preserved.
                settings = settings.copy(
                    whisper = migrateApi(settings.whisper, "whisper-1", def.whisper),
                    translate = migrateApi(settings.translate, "gpt-4o-mini", def.translate),
                    tts = migrateApi(settings.tts, "tts-1", def.tts),
                    ttsVoice = if (settings.ttsVoice == "alloy") def.ttsVoice else settings.ttsVoice
                )
                save(settings)
            }
            settings
        }.getOrDefault(AppSettings())
    }

    private fun migrateApi(cfg: ApiConfig, oldModel: String, def: ApiConfig): ApiConfig {
        val wasDefault = cfg.baseUrl.trimEnd('/') == "https://api.openai.com/v1" && cfg.model == oldModel
        return if (wasDefault) def.copy(apiKey = cfg.apiKey) else cfg
    }

    fun save(s: AppSettings) {
        val o = JSONObject()
            .put("source", s.source)
            .put("ttsEnabled", s.ttsEnabled)
            .put("showOriginal", s.showOriginal)
            .put("compactMode", s.compactMode)
            .put("originalFontSize", s.originalFontSize)
            .put("fontSize", s.fontSize)
            .put("targetLang", s.targetLang)
            .put("language", s.language)
            .put("whisper", s.whisper.toJson())
            .put("whisperLang", s.whisperLang)
            .put("translate", s.translate.toJson())
            .put("tts", s.tts.toJson())
            .put("ttsVoice", s.ttsVoice)
            .put("ttsSpeed", s.ttsSpeed.toDouble())
            .put("vadThreshold", s.vadThreshold)
            .put("requestTimeoutSec", s.requestTimeoutSec)
        prefs.edit().putString("settings", o.toString()).apply()
    }
}
