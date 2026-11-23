package com.lingofloat.app.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Process
import com.lingofloat.app.Strings

const val SAMPLE_RATE = 16000
private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

/** Common capture interface: 16kHz / mono / PCM16. */
interface AudioCapturer {
    fun start()
    /** Blocking read; returns byte count, <=0 means error or end. */
    fun read(buf: ByteArray): Int
    fun stop()
}

/** Microphone capture (VOICE_RECOGNITION source, tuned for speech recognition). */
class MicCapturer(private val t: Strings) : AudioCapturer {
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
            throw IllegalStateException(t.micInitFailed)
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
 * Internal audio capture (Android 10+ AudioPlaybackCapture).
 * Only captures MEDIA/GAME/UNKNOWN audio from apps that allow capture;
 * excludeUid removes this app itself so TTS output is never re-transcribed.
 */
class InternalCapturer(
    private val projection: MediaProjection,
    private val t: Strings
) : AudioCapturer {
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
            throw IllegalStateException(t.internalInitFailed)
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
