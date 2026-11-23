package com.lingofloat.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private lateinit var store: SettingsStore

    private val recordPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) continueStart()
            else toast(tr().toastRecordPerm)
        }

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                startCaptionService(result.resultCode, result.data!!)
            } else {
                toast(tr().toastProjectionDenied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen()
                }
            }
        }
    }

    private fun tr(): Strings = Strings.of(store.load().language)

    @Composable
    private fun SettingsScreen() {
        var s by remember { mutableStateOf(store.load()) }
        var running by remember { mutableStateOf(CaptionService.isRunning) }
        val t = remember(s.language) { Strings.of(s.language) }

        LaunchedEffect(Unit) {
            while (true) {
                running = CaptionService.isRunning
                delay(1000)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("LingoFloat", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                if (s.language == Strings.ZH) "实时双语字幕 · 悬浮窗翻译"
                else "Real-time bilingual captions in a floating window",
                fontSize = 13.sp, color = Color.Gray
            )

            // ---- Status ----
            SectionCard(t.status) {
                Text(
                    (if (running) "● " else "○ ") + (if (running) t.running else t.stopped),
                    color = if (running) Color(0xFF2E7D32) else Color.Gray,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            store.save(s)
                            beginStart(s)
                        },
                        enabled = !running,
                        modifier = Modifier.weight(1f)
                    ) { Text(t.start) }
                    OutlinedButton(
                        onClick = {
                            ContextCompat.startForegroundService(
                                this@MainActivity,
                                Intent(this@MainActivity, CaptionService::class.java)
                                    .setAction(CaptionService.ACTION_STOP)
                            )
                        },
                        enabled = running,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC62828))
                    ) { Text(t.stop) }
                }
                OutlinedTextField(
                    value = s.targetLang,
                    onValueChange = { s = s.copy(targetLang = it) },
                    label = { Text(t.targetLanguage) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                SwitchRow(t.speakTranslation, s.ttsEnabled) { s = s.copy(ttsEnabled = it) }
                Text(t.statusHint, fontSize = 11.sp, color = Color.Gray)
            }

            // ---- Audio ----
            SectionCard(t.audio) {
                Text(t.audioSource, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = s.source == AppSettings.SOURCE_MIC,
                        onClick = { s = s.copy(source = AppSettings.SOURCE_MIC) }
                    )
                    Text(t.microphone)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = s.source == AppSettings.SOURCE_INTERNAL,
                        onClick = { s = s.copy(source = AppSettings.SOURCE_INTERNAL) }
                    )
                    Text(t.internalAudio)
                }
                Text(t.internalHint, fontSize = 11.sp, color = Color.Gray)
                Text(String.format(t.triggerThresholdFmt, s.vadThreshold), fontSize = 13.sp)
                Slider(
                    value = s.vadThreshold.toFloat(),
                    onValueChange = { s = s.copy(vadThreshold = it.toInt()) },
                    valueRange = 100f..1200f
                )
                Text(String.format(t.maxSegmentFmt, s.maxSegmentSec), fontSize = 13.sp)
                Slider(
                    value = s.maxSegmentSec.toFloat(),
                    onValueChange = { s = s.copy(maxSegmentSec = it.toInt()) },
                    valueRange = 3f..15f,
                    steps = 11
                )
            }

            // ---- Overlay ----
            SectionCard(t.overlay) {
                SwitchRow(t.compactMode, s.compactMode) { s = s.copy(compactMode = it) }
                SwitchRow(t.showOriginal, s.showOriginal) { s = s.copy(showOriginal = it) }
                Text(String.format(t.originalFontSizeFmt, s.originalFontSize), fontSize = 13.sp)
                Slider(
                    value = s.originalFontSize.toFloat(),
                    onValueChange = { s = s.copy(originalFontSize = it.toInt()) },
                    valueRange = 10f..24f,
                    steps = 13
                )
                Text(String.format(t.translationFontSizeFmt, s.fontSize), fontSize = 13.sp)
                Slider(
                    value = s.fontSize.toFloat(),
                    onValueChange = { s = s.copy(fontSize = it.toInt()) },
                    valueRange = 12f..32f,
                    steps = 19
                )
                Text(t.overlayHint, fontSize = 11.sp, color = Color.Gray)
            }

            // ---- Speech recognition ----
            SectionCard(t.speechRecognition) {
                ProviderRow(s.whisper.provider, t) { p ->
                    s = s.copy(whisper = s.whisper.copy(
                        provider = p,
                        baseUrl = ApiConfig.providerBase(p) ?: s.whisper.baseUrl
                    ))
                }
                ApiFields(s.whisper, "FunAudioLLM/SenseVoiceSmall", t) { s = s.copy(whisper = it) }
                OutlinedTextField(
                    value = s.whisperLang,
                    onValueChange = { s = s.copy(whisperLang = it) },
                    label = { Text(t.sourceLanguage) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ---- Translation ----
            SectionCard(t.translation) {
                ProviderRow(s.translate.provider, t) { p ->
                    s = s.copy(translate = s.translate.copy(
                        provider = p,
                        baseUrl = ApiConfig.providerBase(p) ?: s.translate.baseUrl
                    ))
                }
                ApiFields(s.translate, "Qwen/Qwen2.5-7B-Instruct", t) { s = s.copy(translate = it) }
            }

            // ---- Speech synthesis ----
            SectionCard(t.speechSynthesis) {
                ProviderRow(s.tts.provider, t) { p ->
                    s = s.copy(tts = s.tts.copy(
                        provider = p,
                        baseUrl = ApiConfig.providerBase(p) ?: s.tts.baseUrl
                    ))
                }
                ApiFields(s.tts, "FunAudioLLM/CosyVoice2-0.5B", t) { s = s.copy(tts = it) }
                OutlinedTextField(
                    value = s.ttsVoice,
                    onValueChange = { s = s.copy(ttsVoice = it) },
                    label = { Text(t.voice) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(String.format(t.speedFmt, s.ttsSpeed), fontSize = 13.sp)
                Slider(
                    value = s.ttsSpeed,
                    onValueChange = { s = s.copy(ttsSpeed = it) },
                    valueRange = 0.5f..2.0f
                )
            }

            // ---- Settings ----
            SectionCard(t.settings) {
                Text(t.language, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = s.language == Strings.EN,
                        onClick = { s = s.copy(language = Strings.EN) },
                        label = { Text("English") }
                    )
                    FilterChip(
                        selected = s.language == Strings.ZH,
                        onClick = { s = s.copy(language = Strings.ZH) },
                        label = { Text("中文") }
                    )
                }
                Button(
                    onClick = {
                        store.save(s)
                        toast(t.saved)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(t.save) }
                Text(
                    if (s.language == Strings.ZH) "API 密钥仅保存在本机，不经过任何第三方。"
                    else "API keys are stored on this device only and never sent to third parties.",
                    fontSize = 11.sp, color = Color.Gray
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    @Composable
    private fun SectionCard(title: String, content: @Composable () -> Unit) {
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                HorizontalDivider()
                content()
            }
        }
    }

    @Composable
    private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(label, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    @Composable
    private fun ProviderRow(selected: String, t: Strings, onSelect: (String) -> Unit) {
        Text(t.provider, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selected == ApiConfig.PROVIDER_SILICONFLOW,
                onClick = { onSelect(ApiConfig.PROVIDER_SILICONFLOW) },
                label = { Text("SiliconFlow") }
            )
            FilterChip(
                selected = selected == ApiConfig.PROVIDER_OPENAI,
                onClick = { onSelect(ApiConfig.PROVIDER_OPENAI) },
                label = { Text("OpenAI") }
            )
            FilterChip(
                selected = selected == ApiConfig.PROVIDER_COMPATIBLE,
                onClick = { onSelect(ApiConfig.PROVIDER_COMPATIBLE) },
                label = { Text(t.providerCompatible) }
            )
        }
    }

    @Composable
    private fun ApiFields(cfg: ApiConfig, modelHint: String, t: Strings, onChange: (ApiConfig) -> Unit) {
        OutlinedTextField(
            value = cfg.baseUrl,
            onValueChange = { onChange(cfg.copy(baseUrl = it)) },
            label = { Text(t.baseUrl) },
            placeholder = { Text("https://api.siliconflow.cn/v1") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = cfg.apiKey,
            onValueChange = { onChange(cfg.copy(apiKey = it)) },
            label = { Text(t.apiKey) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = cfg.model,
            onValueChange = { onChange(cfg.copy(model = it)) },
            label = { Text(t.model) },
            placeholder = { Text(modelHint) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    // ---- Start flow: overlay -> record -> notification -> (internal) MediaProjection ----

    private fun beginStart(s: AppSettings) {
        val t = Strings.of(s.language)
        if (!s.whisper.isConfigured || !s.translate.isConfigured) {
            toast(t.toastFillApis)
            return
        }
        if (s.ttsEnabled && !s.tts.isConfigured) {
            toast(t.toastFillTts)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast(t.toastOverlayPerm)
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            recordPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        continueStart()
    }

    private fun continueStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val s = store.load()
        if (s.source == AppSettings.SOURCE_INTERNAL) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        } else {
            startCaptionService(0, null)
        }
    }

    private fun startCaptionService(resultCode: Int, resultData: Intent?) {
        val s = store.load()
        val intent = Intent(this, CaptionService::class.java).apply {
            putExtra(CaptionService.EXTRA_SOURCE, s.source)
            putExtra(CaptionService.EXTRA_RESULT_CODE, resultCode)
            if (resultData != null) putExtra(CaptionService.EXTRA_RESULT_DATA, resultData)
        }
        ContextCompat.startForegroundService(this, intent)
        toast(Strings.of(s.language).toastStarted)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
