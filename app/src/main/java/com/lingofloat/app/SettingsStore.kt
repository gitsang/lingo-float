package com.lingofloat.app

import android.content.Context
import org.json.JSONObject
import java.net.URI

/** 一组 OpenAI 兼容 API 的连接配置 */
data class ApiConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = ""
) {
    /** 规范化 base URL：去掉末尾斜杠；若只有域名则自动补 /v1 */
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

    companion object {
        fun fromJson(o: JSONObject?, def: ApiConfig): ApiConfig {
            if (o == null) return def
            return ApiConfig(
                baseUrl = o.optString("baseUrl", def.baseUrl),
                apiKey = o.optString("apiKey", def.apiKey),
                model = o.optString("model", def.model)
            )
        }
    }
}

data class AppSettings(
    val source: String = SOURCE_MIC,
    val ttsEnabled: Boolean = false,
    val showOriginal: Boolean = true,
    val fontSize: Int = 18,
    val targetLang: String = "简体中文",
    val whisper: ApiConfig = ApiConfig("https://api.openai.com/v1", "", "whisper-1"),
    val whisperLang: String = "",
    val translate: ApiConfig = ApiConfig("https://api.openai.com/v1", "", "gpt-4o-mini"),
    val tts: ApiConfig = ApiConfig("https://api.openai.com/v1", "", "tts-1"),
    val ttsVoice: String = "alloy",
    val ttsSpeed: Float = 1.0f,
    val vadThreshold: Int = 300
) {
    companion object {
        const val SOURCE_MIC = "mic"
        const val SOURCE_INTERNAL = "internal"
    }
}

/** SharedPreferences + JSON 持久化 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("lingofloat_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val raw = prefs.getString("settings", null) ?: return AppSettings()
        return runCatching {
            val o = JSONObject(raw)
            val def = AppSettings()
            AppSettings(
                source = o.optString("source", def.source),
                ttsEnabled = o.optBoolean("ttsEnabled", def.ttsEnabled),
                showOriginal = o.optBoolean("showOriginal", def.showOriginal),
                fontSize = o.optInt("fontSize", def.fontSize),
                targetLang = o.optString("targetLang", def.targetLang),
                whisper = ApiConfig.fromJson(o.optJSONObject("whisper"), def.whisper),
                whisperLang = o.optString("whisperLang", def.whisperLang),
                translate = ApiConfig.fromJson(o.optJSONObject("translate"), def.translate),
                tts = ApiConfig.fromJson(o.optJSONObject("tts"), def.tts),
                ttsVoice = o.optString("ttsVoice", def.ttsVoice),
                ttsSpeed = o.optDouble("ttsSpeed", def.ttsSpeed.toDouble()).toFloat(),
                vadThreshold = o.optInt("vadThreshold", def.vadThreshold)
            )
        }.getOrDefault(AppSettings())
    }

    fun save(s: AppSettings) {
        val o = JSONObject()
            .put("source", s.source)
            .put("ttsEnabled", s.ttsEnabled)
            .put("showOriginal", s.showOriginal)
            .put("fontSize", s.fontSize)
            .put("targetLang", s.targetLang)
            .put("whisper", s.whisper.toJson())
            .put("whisperLang", s.whisperLang)
            .put("translate", s.translate.toJson())
            .put("tts", s.tts.toJson())
            .put("ttsVoice", s.ttsVoice)
            .put("ttsSpeed", s.ttsSpeed.toDouble())
            .put("vadThreshold", s.vadThreshold)
        prefs.edit().putString("settings", o.toString()).apply()
    }
}
