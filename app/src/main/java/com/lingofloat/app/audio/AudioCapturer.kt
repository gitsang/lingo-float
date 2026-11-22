package com.lingofloat.app.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Process

const val SAMPLE_RATE = 16000
private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

/** 统一采集接口：16kHz / 单声道 / PCM16 */
interface AudioCapturer {
    fun start()
    /** 阻塞读取，返回字节数；<=0 表示出错或结束 */
    fun read(buf: ByteArray): Int
    fun stop()
}

/** 麦克风采集（VOICE_RECOGNITION 音源，面向语音识别优化） */
class MicCapturer : AudioCapturer {
    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission")
    override fun start() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, CHANNEL, ENCODING,
            maxOf(minBuf * 2, SAMPLE_RATE * 2)
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            throw IllegalStateException("AudioRecord 初始化失败（麦克风被占用？）")
        }
        r.startRecording()
        record = r
    }

    override fun read(buf: ByteArray): Int = record?.read(buf, 0, buf.size) ?: -1

    override fun stop() {
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
    }
}

/**
 * 内部音频采集（Android 10+ AudioPlaybackCapture）。
 * 仅捕获允许被录制的 App 的 MEDIA/GAME/UNKNOWN 音频；
 * excludeUid 排除本应用自身，避免 TTS 译文被再次识别造成回环。
 */
class InternalCapturer(private val projection: MediaProjection) : AudioCapturer {
    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission")
    override fun start() {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .excludeUid(Process.myUid())
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(ENCODING)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val r = AudioRecord.Builder()
            .setAudioFormat(format)
            .setAudioPlaybackCaptureConfig(config)
            .setBufferSizeInBytes(maxOf(minBuf * 2, SAMPLE_RATE * 2))
            .build()
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            throw IllegalStateException("内录 AudioRecord 初始化失败")
        }
        r.startRecording()
        record = r
    }

    override fun read(buf: ByteArray): Int = record?.read(buf, 0, buf.size) ?: -1

    override fun stop() {
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
    }
}
