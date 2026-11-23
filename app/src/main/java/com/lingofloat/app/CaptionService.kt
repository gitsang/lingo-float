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
import android.os.SystemClock
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Core foreground service.
 *
 * Pipeline (sentence-level, no fixed-length chopping):
 * 1. Capture thread runs the energy VAD. While an utterance is in progress it
 *    emits a Probe (snapshot of the audio so far) every ~1.5s; when the VAD
 *    closes an utterance (600ms silence) it emits End.
 * 2. Processor coroutine transcribes each probe, splits the text into
 *    sentences and forwards every NEW complete sentence to translation.
 *    On End, remaining sentences plus the incomplete tail are flushed.
 * 3. Translated (original, translation) pairs go to a display queue which
 *    shows each pair for at least MIN_DISPLAY_MS before the next one.
 * 4. A separate coroutine synthesizes TTS for displayed translations.
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
        private const val PROBE_INTERVAL_MS = 1500
        private const val MIN_DISPLAY_MS = 2000L

        @Volatile
        var isRunning = false
            private set
    }

    private sealed class CaptureEvent {
        class Probe(val utteranceId: Int, val pcm: ByteArray) : CaptureEvent()
        class End(val utteranceId: Int, val pcm: ByteArray) : CaptureEvent()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var capturer: AudioCapturer? = null
    private var captureThread: Thread? = null
    private var projection: MediaProjection? = null
    private var overlay: SubtitleOverlay? = null
    private var ttsPlayer: TtsPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var eventChannel: Channel<CaptureEvent>? = null
    private var displayChannel: Channel<Pair<String, String>>? = null
    private var ttsChannel: Channel<String>? = null

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
        val segmenter = VadSegmenter(threshold = settings.vadThreshold)
        val events = Channel<CaptureEvent>(Channel.UNLIMITED)
        val display = Channel<Pair<String, String>>(Channel.UNLIMITED)
        val ttsTexts = Channel<String>(Channel.UNLIMITED)
        eventChannel = events
        displayChannel = display
        ttsChannel = ttsTexts
        val probeBusy = AtomicBoolean(false)

        // ---- Processor: probes -> sentence extraction -> translation ----
        scope.launch {
            var uttId = -1
            val emitted = ArrayList<String>()

            suspend fun handleText(id: Int, text: String, final: Boolean) {
                if (id != uttId) {
                    uttId = id
                    emitted.clear()
                }
                if (text.isNotBlank()) {
                    val pieces = SentenceSplitter.split(text)
                    val complete = pieces.filter { SentenceSplitter.isComplete(it) }
                    // Whisper may revise earlier text between probes; only trust
                    // the sentence list while the already-emitted prefix matches.
                    var prefixOk = true
                    for (i in emitted.indices) {
                        if (i >= complete.size ||
                            SentenceSplitter.norm(complete[i]) != SentenceSplitter.norm(emitted[i])
                        ) {
                            prefixOk = false
                            break
                        }
                    }
                    val toEmit = ArrayList<String>()
                    if (prefixOk || final) {
                        for (i in emitted.size until complete.size) toEmit.add(complete[i])
                        if (final) {
                            val tail = pieces.lastOrNull()
                            if (tail != null && !SentenceSplitter.isComplete(tail) &&
                                tail.any { it.isLetterOrDigit() }
                            ) {
                                toEmit.add(tail)
                            }
                        }
                    }
                    for (sentence in toEmit) {
                        emitted.add(sentence)
                        try {
                            overlay?.setStatus(t.translating)
                            val translated = translator.translate(sentence)
                            display.trySend(sentence to translated.ifBlank { sentence })
                        } catch (e: Exception) {
                            Log.w(TAG, "Translate failed", e)
                            overlay?.setStatus(String.format(t.errorFmt, (e.message ?: "unknown").take(120)))
                        }
                    }
                }
                if (final) {
                    uttId = -1
                    emitted.clear()
                }
            }

            for (ev in events) {
                if (stopping) break
                try {
                    when (ev) {
                        is CaptureEvent.Probe -> {
                            try {
                                overlay?.setStatus(t.transcribing)
                                val text = whisper.transcribe(WavUtil.pcmToWav(ev.pcm))
                                handleText(ev.utteranceId, text, final = false)
                            } finally {
                                probeBusy.set(false)
                            }
                        }
                        is CaptureEvent.End -> {
                            overlay?.setStatus(t.transcribing)
                            val text = whisper.transcribe(WavUtil.pcmToWav(ev.pcm))
                            handleText(ev.utteranceId, text, final = true)
                            overlay?.setStatus(t.listening)
                        }
                    }
                } catch (e: Exception) {
                    probeBusy.set(false)
                    Log.w(TAG, "Pipeline error", e)
                    overlay?.setStatus(String.format(t.errorFmt, (e.message ?: "unknown").take(120)))
                }
            }
        }

        // ---- Display: each (original, translation) pair shown >= 2s ----
        scope.launch {
            var lastShowAt = 0L
            for ((original, translated) in display) {
                if (stopping) break
                val wait = lastShowAt + MIN_DISPLAY_MS - SystemClock.elapsedRealtime()
                if (wait > 0) delay(wait)
                overlay?.setOriginal(original)
                overlay?.setTranslation(translated)
                lastShowAt = SystemClock.elapsedRealtime()
                if (ttsClient != null) ttsTexts.trySend(translated)
            }
        }

        // ---- TTS: synthesize displayed translations off the display path ----
        if (ttsClient != null) {
            scope.launch {
                for (text in ttsTexts) {
                    if (stopping) break
                    try {
                        ttsPlayer?.enqueue(ttsClient.synthesize(text))
                    } catch (e: Exception) {
                        Log.w(TAG, "TTS failed", e)
                    }
                }
            }
        }

        // ---- Capture thread: VAD + periodic probes ----
        val cap = capturer ?: return
        captureThread = Thread({
            try {
                cap.start()
                overlay?.setStatus(t.listening)
                val buf = ByteArray(3200) // 100ms
                var utteranceId = 0
                var lastProbeAtMs = 0
                while (!stopping) {
                    val n = cap.read(buf)
                    if (n <= 0) {
                        if (stopping) break else continue
                    }
                    val segments = segmenter.accept(buf, n)
                    if (segmenter.isInSpeech() &&
                        segmenter.currentTotalMs() - lastProbeAtMs >= PROBE_INTERVAL_MS &&
                        probeBusy.compareAndSet(false, true)
                    ) {
                        val snap = segmenter.snapshot()
                        if (snap != null) {
                            events.trySend(CaptureEvent.Probe(utteranceId, snap))
                        } else {
                            probeBusy.set(false)
                        }
                        lastProbeAtMs = segmenter.currentTotalMs()
                    }
                    for (seg in segments) {
                        events.trySend(CaptureEvent.End(utteranceId, seg))
                        utteranceId++
                        lastProbeAtMs = 0
                    }
                }
                segmenter.flush()?.let { events.trySend(CaptureEvent.End(utteranceId, it)) }
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

        eventChannel?.close()
        eventChannel = null
        displayChannel?.close()
        displayChannel = null
        ttsChannel?.close()
        ttsChannel = null
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
