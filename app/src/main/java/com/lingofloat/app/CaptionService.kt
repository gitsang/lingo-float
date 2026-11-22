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
 * 核心前台服务：采集 → VAD 分段 → Whisper → 翻译 → 悬浮字幕 / TTS。
 * 识别+翻译在单消费者协程里串行执行，保证字幕顺序。
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "实时字幕", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        if (isRunning) return START_NOT_STICKY

        val settings = SettingsStore(this).load()
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: AppSettings.SOURCE_MIC

        // Android 14: 必须先以对应类型 startForeground，再 getMediaProjection
        val fgType = if (source == AppSettings.SOURCE_INTERNAL)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        else
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        startForeground(NOTIF_ID, buildNotification(), fgType)
        isRunning = true
        stopping = false

        overlay = SubtitleOverlay(this, settings) { stopEverything() }.also { it.show() }

        if (!settings.whisper.isConfigured || !settings.translate.isConfigured) {
            overlay?.setStatus("请先在 App 中配置 Whisper 与翻译 API")
            overlay?.setTranslation("未配置 API")
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
                    ?: throw IllegalStateException("缺少 MediaProjection 授权数据")
                val mpm = getSystemService(MediaProjectionManager::class.java)
                val p = mpm.getMediaProjection(code, data)
                    ?: throw IllegalStateException("MediaProjection 创建失败")
                p.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopEverything()
                    }
                }, Handler(Looper.getMainLooper()))
                projection = p
                capturer = InternalCapturer(p)
            } else {
                capturer = MicCapturer()
            }
        } catch (e: Exception) {
            Log.e(TAG, "采集初始化失败", e)
            overlay?.setTranslation("启动失败")
            overlay?.setStatus(e.message ?: "未知错误")
            return START_NOT_STICKY
        }

        startPipeline(settings)
        return START_NOT_STICKY
    }

    private fun startPipeline(settings: AppSettings) {
        val whisper = WhisperClient(settings.whisper, settings.whisperLang)
        val translator = TranslateClient(settings.translate, settings.targetLang)
        val ttsClient = ttsPlayer?.let { TtsClient(settings.tts, settings.ttsVoice, settings.ttsSpeed) }
        val segmenter = VadSegmenter(threshold = settings.vadThreshold)
        val channel = Channel<ByteArray>(Channel.UNLIMITED)
        segmentChannel = channel

        // 串行消费者：识别 → 翻译 → 字幕 → TTS
        scope.launch {
            for (seg in channel) {
                if (stopping) break
                try {
                    overlay?.setStatus("识别中…")
                    val text = whisper.transcribe(WavUtil.pcmToWav(seg))
                    if (text.isBlank()) {
                        overlay?.setStatus("聆听中…")
                        continue
                    }
                    overlay?.setOriginal(text)
                    overlay?.setStatus("翻译中…")
                    val translated = translator.translate(text)
                    overlay?.setTranslation(translated.ifBlank { text })
                    overlay?.setStatus("聆听中…")
                    if (ttsClient != null && translated.isNotBlank()) {
                        try {
                            ttsPlayer?.enqueue(ttsClient.synthesize(translated))
                        } catch (e: Exception) {
                            Log.w(TAG, "TTS 失败", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "处理失败", e)
                    overlay?.setStatus("出错：${(e.message ?: "未知").take(120)}")
                }
            }
        }

        val cap = capturer ?: return
        captureThread = Thread({
            try {
                cap.start()
                overlay?.setStatus("聆听中…")
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
                Log.e(TAG, "采集线程异常", e)
                overlay?.setStatus("采集失败：${(e.message ?: "未知").take(120)}")
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
            .setContentTitle("LingoFloat 实时字幕运行中")
            .setContentText("正在监听音频并生成双语字幕")
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, "停止", stopIntent)
            .build()
    }
}
