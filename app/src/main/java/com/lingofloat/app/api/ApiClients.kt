package com.lingofloat.app.api

import android.util.Base64
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

/**
 * TTS client supporting two OpenAI-style formats:
 * - FORMAT_SPEECH: POST /audio/speech, binary mp3 response (standard TTS).
 * - FORMAT_CHAT: POST /chat/completions with modalities ["text","audio"];
 *   audio arrives base64-encoded in choices[0].message.audio.data
 *   (gpt-4o-audio-preview, MiMo-Audio style audio-output chat models).
 */
class TtsClient(
    private val cfg: ApiConfig,
    private val voice: String,
    private val speed: Float,
    private val timeoutSec: Int = 30,
    private val format: String = FORMAT_SPEECH
) {
    companion object {
        const val FORMAT_SPEECH = "speech"
        const val FORMAT_CHAT = "chat"
    }

    fun synthesize(text: String): ByteArray =
        if (format == FORMAT_CHAT) synthesizeChat(text) else synthesizeSpeech(text)

    private fun voiceId(): String =
        // SiliconFlow expects "model:voice"; accept a bare voice name as well.
        if (cfg.provider == ApiConfig.PROVIDER_SILICONFLOW && !voice.contains(":"))
            "${cfg.model}:$voice" else voice

    private fun synthesizeSpeech(text: String): ByteArray {
        val payload = JSONObject()
            .put("model", cfg.model)
            .put("input", text)
            .put("voice", voiceId())
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

    private fun synthesizeChat(text: String): ByteArray {
        // MiMo-V2.5-TTS / gpt-4o-audio style: the text to speak MUST go in the
        // assistant message; the user message carries style instructions only
        // (its content is never spoken). Audio comes back as base64 wav.
        val audio = JSONObject().put("format", "wav")
        if (voice.isNotBlank()) audio.put("voice", voiceId())
        val payload = JSONObject()
            .put("model", cfg.model)
            .put("modalities", JSONArray().put("text").put("audio"))
            .put("audio", audio)
            .put("stream", false)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "user").put(
                    "content",
                    "Read the assistant text aloud naturally and clearly, in its original language."
                ))
                .put(JSONObject().put("role", "assistant").put("content", text)))
        val req = Http.auth(
            Request.Builder()
                .url(cfg.normalizedBase() + "/chat/completions")
                .post(payload.toString().toRequestBody("application/json".toMediaType())),
            cfg
        ).build()
        Http.clientFor(timeoutSec).newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("TTS HTTP ${resp.code}: ${body.take(200)}")
            }
            val data = JSONObject(body)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optJSONObject("audio")
                ?.optString("data")
                .orEmpty()
            if (data.isEmpty()) {
                throw IOException("TTS chat response has no message.audio.data (model may not support audio output)")
            }
            return Base64.decode(data, Base64.DEFAULT)
        }
    }
}
