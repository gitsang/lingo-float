package com.lingofloat.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.IntentCompat
import androidx.core.app.NotificationCompat
import com.lingofloat.app.api.TranslateClient
import com.lingofloat.app.api.TtsClient
import com.lingofloat.app.api.WhisperClient
import com.lingofloat.app.audio.AudioCapturer
import com.lingofloat.app.audio.InternalCapturer
import com.lingofloat.app.audio.MicCapturer
import com.lingofloat.app.audio.VadSegmenter
import com.lingofloat.app.audio.WavUtil
import com.lingofloat.app.overlay.SubtitleOverlay
import com.lingofloat.app.tts.TtsPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Core foreground service: capture -> VAD segmentation -> Whisper -> translate
 * -> overlay captions / TTS. Recognition and translation run serially in a
 * single consumer coroutine so captions stay in order.
 */
class CaptionService : Service() {

    companion object {
        const val ACTION_STOP = "com.lingofloat.app.action.STOP"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "lingofloat_caption"
        private const val TAG = "CaptionService"

        @Volatile
        var isRunning = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var capturer: AudioCapturer? = null
    private var captureThread: Thread? = null
    private var projection: MediaProjection? = null
    private var overlay: SubtitleOverlay? = null
    private var ttsPlayer: TtsPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var segmentChannel: Channel<ByteArray>? = null

    @Volatile
    private var stopping = false

    private var t = Strings.of(Strings.EN)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        t = Strings.of(SettingsStore(this).load().language)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, t.notifChannel, NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        if (isRunning) return START_NOT_STICKY

        val settings = SettingsStore(this).load()
        t = Strings.of(settings.language)
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: AppSettings.SOURCE_MIC

        // Android 14: startForeground with the proper type BEFORE getMediaProjection
        val fgType = if (source == AppSettings.SOURCE_INTERNAL)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        else
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        startForeground(NOTIF_ID, buildNotification(), fgType)
        isRunning = true
        stopping = false

        overlay = SubtitleOverlay(this, settings) { stopEverything() }.also { it.show() }

        if (!settings.whisper.isConfigured || !settings.translate.isConfigured) {
            overlay?.setStatus(t.configureFirst)
            overlay?.setTranslation(t.notConfigured)
            return START_NOT_STICKY
        }

        if (settings.ttsEnabled && settings.tts.isConfigured) {
            ttsPlayer = TtsPlayer(this)
        }

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lingofloat:capture")
            .apply { acquire(60 * 60 * 1000L) }

        try {
            if (source == AppSettings.SOURCE_INTERNAL) {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
                    ?: throw IllegalStateException(t.missingProjection)
                val mpm = getSystemService(MediaProjectionManager::class.java)
                val p = mpm.getMediaProjection(code, data)
                    ?: throw IllegalStateException(t.projectionFailed)
                p.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopEverything()
                    }
                }, Handler(Looper.getMainLooper()))
                projection = p
                capturer = InternalCapturer(p, t)
            } else {
                capturer = MicCapturer(t)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Capture init failed", e)
            overlay?.setTranslation(t.startFailed)
            overlay?.setStatus(e.message ?: "unknown")
            return START_NOT_STICKY
        }

        startPipeline(settings)
        return START_NOT_STICKY
    }

    private fun startPipeline(settings: AppSettings) {
        val whisper = WhisperClient(settings.whisper, settings.whisperLang, settings.requestTimeoutSec)
        val translator = TranslateClient(settings.translate, settings.targetLang, settings.requestTimeoutSec)
        val ttsClient = ttsPlayer?.let {
            TtsClient(settings.tts, settings.ttsVoice, settings.ttsSpeed, settings.requestTimeoutSec)
        }
        val segmenter = VadSegmenter(
            threshold = settings.vadThreshold,
            maxSegmentMs = settings.maxSegmentSec * 1000
        )
        val channel = Channel<ByteArray>(Channel.UNLIMITED)
        segmentChannel = channel

        // Serial consumer: transcribe -> translate -> captions -> TTS
        scope.launch {
            for (seg in channel) {
                if (stopping) break
                try {
                    overlay?.setStatus(t.transcribing)
                    val text = whisper.transcribe(WavUtil.pcmToWav(seg))
                    if (text.isBlank()) {
                        overlay?.setStatus(t.listening)
                        continue
                    }
                    overlay?.setOriginal(text)
                    overlay?.setStatus(t.translating)
                    val translated = translator.translate(text)
                    overlay?.setTranslation(translated.ifBlank { text })
                    overlay?.setStatus(t.listening)
                    if (ttsClient != null && translated.isNotBlank()) {
                        try {
                            ttsPlayer?.enqueue(ttsClient.synthesize(translated))
                        } catch (e: Exception) {
                            Log.w(TAG, "TTS failed", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Pipeline error", e)
                    overlay?.setStatus(String.format(t.errorFmt, (e.message ?: "unknown").take(120)))
                }
            }
        }

        val cap = capturer ?: return
        captureThread = Thread({
            try {
                cap.start()
                overlay?.setStatus(t.listening)
                val buf = ByteArray(3200) // 100ms
                while (!stopping) {
                    val n = cap.read(buf)
                    if (n <= 0) {
                        if (stopping) break else continue
                    }
                    segmenter.accept(buf, n).forEach { channel.trySend(it) }
                }
                segmenter.flush()?.let { channel.trySend(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Capture thread error", e)
                overlay?.setStatus(String.format(t.captureFailedFmt, (e.message ?: "unknown").take(120)))
            }
        }, "audio-capture").apply { start() }
    }

    private fun stopEverything() {
        if (stopping) return
        stopping = true
        isRunning = false

        runCatching { capturer?.stop() }
        capturer = null
        captureThread?.interrupt()
        captureThread = null

        segmentChannel?.close()
        segmentChannel = null
        scope.cancel()

        runCatching { projection?.stop() }
        projection = null

        ttsPlayer?.shutdown()
        ttsPlayer = null

        overlay?.remove()
        overlay = null

        runCatching { wakeLock?.release() }
        wakeLock = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, CaptionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val contentIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(t.notifTitle)
            .setContentText(t.notifText)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, t.stop, stopIntent)
            .build()
    }
}
