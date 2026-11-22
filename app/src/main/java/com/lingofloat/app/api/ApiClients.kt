package com.lingofloat.app.api

import com.lingofloat.app.ApiConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    internal fun auth(req: Request.Builder, cfg: ApiConfig): Request.Builder {
        if (cfg.apiKey.isNotBlank()) req.header("Authorization", "Bearer ${cfg.apiKey}")
        return req
    }
}

/** OpenAI 兼容 /audio/transcriptions（Whisper） */
class WhisperClient(
    private val cfg: ApiConfig,
    private val language: String = ""
) {
    fun transcribe(wav: ByteArray): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", cfg.model)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("temperature", "0")
            .apply { if (language.isNotBlank()) addFormDataPart("language", language.trim()) }
            .build()
        val req = Http.auth(
            Request.Builder().url(cfg.normalizedBase() + "/audio/transcriptions").post(body),
            cfg
        ).build()
        Http.client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("Whisper HTTP ${resp.code}: ${text.take(200)}")
            }
            // 标准返回 {"text": "..."}；个别服务返回纯文本
            return runCatching { JSONObject(text).optString("text") }
                .getOrDefault(text).trim()
        }
    }
}

/** OpenAI 兼容 /chat/completions，用于翻译 */
class TranslateClient(
    private val cfg: ApiConfig,
    private val targetLang: String
) {
    private val systemPrompt =
        "你是专业的同声传译引擎。把用户给出的内容翻译成$targetLang。" +
        "只输出译文本身，不要解释、不要引号、不要注音。" +
        "如果内容已经是$targetLang，原样输出。保留专有名词的常见写法，口语保持自然。"

    fun translate(text: String): String {
        val payload = JSONObject()
            .put("model", cfg.model)
            .put("temperature", 0.2)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemPrompt))
                .put(JSONObject().put("role", "user").put("content", text)))
        val req = Http.auth(
            Request.Builder()
                .url(cfg.normalizedBase() + "/chat/completions")
                .post(payload.toString().toRequestBody("application/json".toMediaType())),
            cfg
        ).build()
        Http.client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("翻译 HTTP ${resp.code}: ${body.take(200)}")
            }
            val content = JSONObject(body)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()
            return content.trim()
        }
    }
}

/** OpenAI 兼容 /audio/speech（TTS），返回 mp3 字节 */
class TtsClient(
    private val cfg: ApiConfig,
    private val voice: String,
    private val speed: Float
) {
    fun synthesize(text: String): ByteArray {
        val payload = JSONObject()
            .put("model", cfg.model)
            .put("input", text)
            .put("voice", voice)
            .put("response_format", "mp3")
            .put("speed", speed.toDouble())
        val req = Http.auth(
            Request.Builder()
                .url(cfg.normalizedBase() + "/audio/speech")
                .post(payload.toString().toRequestBody("application/json".toMediaType())),
            cfg
        ).build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("TTS HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
            }
            return resp.body?.bytes() ?: throw IOException("TTS 返回空音频")
        }
    }
}
