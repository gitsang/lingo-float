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
    private val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    /** callTimeout = hard cap on total request time (connect/upload/wait/download). */
    fun clientFor(timeoutSec: Int): OkHttpClient =
        base.newBuilder()
            .callTimeout(timeoutSec.coerceIn(5, 300).toLong(), TimeUnit.SECONDS)
            .build()

    internal fun auth(req: Request.Builder, cfg: ApiConfig): Request.Builder {
        if (cfg.apiKey.isNotBlank()) req.header("Authorization", "Bearer ${cfg.apiKey}")
        return req
    }
}

/** OpenAI-compatible /audio/transcriptions (Whisper-style STT). */
class WhisperClient(
    private val cfg: ApiConfig,
    private val language: String = "",
    private val timeoutSec: Int = 30
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
        Http.clientFor(timeoutSec).newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("Whisper HTTP ${resp.code}: ${text.take(200)}")
            }
            // Standard response is {"text": "..."}; some servers return plain text
            return runCatching { JSONObject(text).optString("text") }
                .getOrDefault(text).trim()
        }
    }
}

/** OpenAI-compatible /chat/completions used for translation. */
class TranslateClient(
    private val cfg: ApiConfig,
    private val targetLang: String,
    private val timeoutSec: Int = 30
) {
    private val systemPrompt =
        "You are a professional simultaneous interpreter. Translate the user's content into $targetLang. " +
        "Output only the translation itself: no explanations, no quotes, no transliteration. " +
        "If the content is already in $targetLang, output it unchanged. " +
        "Keep common spellings of proper nouns and keep spoken language natural."

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
        Http.clientFor(timeoutSec).newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("Translate HTTP ${resp.code}: ${body.take(200)}")
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

/** OpenAI-compatible /audio/speech (TTS); returns mp3 bytes. */
class TtsClient(
    private val cfg: ApiConfig,
    private val voice: String,
    private val speed: Float,
    private val timeoutSec: Int = 30
) {
    fun synthesize(text: String): ByteArray {
        // SiliconFlow expects "model:voice"; accept a bare voice name as well.
        val voiceId = if (cfg.provider == ApiConfig.PROVIDER_SILICONFLOW && !voice.contains(":"))
            "${cfg.model}:$voice" else voice
        val payload = JSONObject()
            .put("model", cfg.model)
            .put("input", text)
            .put("voice", voiceId)
            .put("response_format", "mp3")
            .put("speed", speed.toDouble())
        val req = Http.auth(
            Request.Builder()
                .url(cfg.normalizedBase() + "/audio/speech")
                .post(payload.toString().toRequestBody("application/json".toMediaType())),
            cfg
        ).build()
        Http.clientFor(timeoutSec).newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("TTS HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
            }
            return resp.body?.bytes() ?: throw IOException("TTS returned empty audio")
        }
    }
}
