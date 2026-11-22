package com.lingofloat.app.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * TTS 顺序播放队列：后台线程逐条播放 mp3。
 * 队列积压超过 2 条时丢弃最旧的，保证"实时"不被越拖越远。
 */
class TtsPlayer(private val context: Context) {
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var running = true
    @Volatile private var current: MediaPlayer? = null

    private val thread = Thread({
        while (running) {
            val data = try {
                queue.take()
            } catch (_: InterruptedException) {
                break
            }
            if (!running) break
            runCatching { play(data) }.onFailure { Log.w(TAG, "播放失败", it) }
        }
    }, "tts-player").apply { isDaemon = true; start() }

    fun enqueue(mp3: ByteArray) {
        if (!running) return
        while (queue.size >= 2) queue.poll()
        queue.offer(mp3)
    }

    fun shutdown() {
        running = false
        queue.clear()
        runCatching { current?.stop() }
        runCatching { current?.release() }
        current = null
        thread.interrupt()
    }

    private fun play(data: ByteArray) {
        val f = File.createTempFile("tts_", ".mp3", context.cacheDir)
        try {
            f.writeBytes(data)
            val latch = CountDownLatch(1)
            val mp = MediaPlayer()
            current = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener { latch.countDown() }
            mp.setOnErrorListener { _, _, _ -> latch.countDown(); true }
            mp.prepare()
            mp.start()
            latch.await(90, TimeUnit.SECONDS)
            runCatching { mp.release() }
            if (current === mp) current = null
        } finally {
            f.delete()
        }
    }

    companion object {
        private const val TAG = "TtsPlayer"
    }
}
