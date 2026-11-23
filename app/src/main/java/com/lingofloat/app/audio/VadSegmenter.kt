package com.lingofloat.app.audio

import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * Lightweight energy VAD: RMS per 20ms frame.
 * Speech start (with pre-roll to avoid clipping) -> cut a segment after 600ms
 * of trailing silence or when the max segment length is reached.
 * Trigger level = max(fixed threshold, noise floor x 2): the noise floor is a
 * slow EMA over non-speech frames, so background music raises the trigger line
 * and a pause in speech is detected as silence immediately.
 * Not thread-safe; use only inside the capture thread.
 */
class VadSegmenter(
    private val threshold: Int = 300,
    private val silenceTailMs: Int = 600,
    private val minSpeechMs: Int = 400,
    private val maxSegmentMs: Int = 15000,
    private val preRollMs: Int = 240
) {
    private val frameBytes = 640 // 20ms @ 16kHz mono PCM16
    private val preRoll = ArrayDeque<ByteArray>()
    private val buffer = ByteArrayOutputStream()
    private val pending = ByteArrayOutputStream()

    private var inSpeech = false
    private var speechMs = 0
    private var silenceMs = 0
    private var totalMs = 0
    private var noiseFloor = 0.0

    /** Feed raw PCM; returns complete speech segments produced by this call. */
    fun accept(data: ByteArray, len: Int): List<ByteArray> {
        if (len <= 0) return emptyList()
        val out = mutableListOf<ByteArray>()
        pending.write(data, 0, len)
        while (pending.size() >= frameBytes) {
            val all = pending.toByteArray()
            val frame = all.copyOfRange(0, frameBytes)
            pending.reset()
            if (all.size > frameBytes) pending.write(all, frameBytes, all.size - frameBytes)
            processFrame(frame)?.let { out.add(it) }
        }
        return out
    }

    /** Flush an unfinished segment when stopping. */
    fun flush(): ByteArray? {
        val seg = if (inSpeech && speechMs >= minSpeechMs) buffer.toByteArray() else null
        reset()
        return seg
    }

    private fun processFrame(frame: ByteArray): ByteArray? {
        val level = rms(frame)
        if (!inSpeech) {
            noiseFloor = if (noiseFloor <= 0.0) level else noiseFloor * 0.95 + level * 0.05
        }
        val loud = level >= maxOf(threshold.toDouble(), noiseFloor * 2.0)
        if (!inSpeech) {
            preRoll.addLast(frame)
            while (preRoll.size > preRollMs / 20) preRoll.removeFirst()
            if (loud) {
                inSpeech = true
                speechMs = 20
                silenceMs = 0
                buffer.reset()
                preRoll.forEach { buffer.write(it) }
                totalMs = preRoll.size * 20
                preRoll.clear()
            }
            return null
        }
        buffer.write(frame)
        totalMs += 20
        if (loud) {
            speechMs += 20
            silenceMs = 0
        } else {
            silenceMs += 20
        }
        if (silenceMs >= silenceTailMs || totalMs >= maxSegmentMs) {
            val seg = if (speechMs >= minSpeechMs) buffer.toByteArray() else null
            reset()
            return seg
        }
        return null
    }

    private fun reset() {
        inSpeech = false
        speechMs = 0
        silenceMs = 0
        totalMs = 0
        buffer.reset()
        preRoll.clear()
    }

    private fun rms(frame: ByteArray): Double {
        var sum = 0.0
        var n = 0
        var i = 0
        while (i < frame.size - 1) {
            val s = (frame[i].toInt() and 0xff) or (frame[i + 1].toInt() shl 8)
            sum += s.toDouble() * s.toDouble()
            n++
            i += 2
        }
        return if (n == 0) 0.0 else sqrt(sum / n)
    }
}
