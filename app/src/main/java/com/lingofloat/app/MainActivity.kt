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
            else toast("需要录音权限才能采集音频")
        }

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                startService(result.resultCode, result.data!!)
            } else {
                toast("未授权屏幕/音频捕获")
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

    @Composable
    private fun SettingsScreen() {
        var s by remember { mutableStateOf(store.load()) }
        var running by remember { mutableStateOf(CaptionService.isRunning) }

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
            Text("实时双语字幕 · 悬浮窗翻译", fontSize = 13.sp, color = Color.Gray)

            // ---- 运行控制 ----
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (running) "● 运行中" else "○ 未运行",
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
                        ) { Text("开始") }
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
                        ) { Text("停止") }
                    }
                    Text(
                        "启动前会自动保存配置。内部音频模式需要授权一次系统录屏弹窗。",
                        fontSize = 11.sp, color = Color.Gray
                    )
                }
            }

            // ---- 音源与开关 ----
            SectionCard("音频来源") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = s.source == AppSettings.SOURCE_MIC,
                        onClick = { s = s.copy(source = AppSettings.SOURCE_MIC) }
                    )
                    Text("麦克风（环境声 / 外放）")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = s.source == AppSettings.SOURCE_INTERNAL,
                        onClick = { s = s.copy(source = AppSettings.SOURCE_INTERNAL) }
                    )
                    Text("手机内部音频（视频 / 播客）")
                }
                Text(
                    "内录仅支持允许被捕获的 App；微信语音、DRM 内容等无法捕获。",
                    fontSize = 11.sp, color = Color.Gray
                )
                SwitchRow("朗读译文（TTS）", s.ttsEnabled) { s = s.copy(ttsEnabled = it) }
                SwitchRow("字幕显示原文", s.showOriginal) { s = s.copy(showOriginal = it) }
                SwitchRow("紧凑模式（悬浮窗仅两行）", s.compactMode) { s = s.copy(compactMode = it) }
                Text(
                    "紧凑模式：原文/译文各一行，隐藏标题栏；拖动窗口移动，长按恢复标准模式。也可点悬浮窗标题栏的「紧凑」快速切换。",
                    fontSize = 11.sp, color = Color.Gray
                )
                OutlinedTextField(
                    value = s.targetLang,
                    onValueChange = { s = s.copy(targetLang = it) },
                    label = { Text("目标语言") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("译文字号：${s.fontSize}sp", fontSize = 13.sp)
                Slider(
                    value = s.fontSize.toFloat(),
                    onValueChange = { s = s.copy(fontSize = it.toInt()) },
                    valueRange = 12f..32f,
                    steps = 19
                )
                Text("拾音触发阈值：${s.vadThreshold}（越小越灵敏，嘈杂环境调大）", fontSize = 13.sp)
                Slider(
                    value = s.vadThreshold.toFloat(),
                    onValueChange = { s = s.copy(vadThreshold = it.toInt()) },
                    valueRange = 100f..1200f
                )
            }

            // ---- Whisper ----
            SectionCard("语音识别 · Whisper API") {
                ApiFields(s.whisper, "whisper-1") { s = s.copy(whisper = it) }
                OutlinedTextField(
                    value = s.whisperLang,
                    onValueChange = { s = s.copy(whisperLang = it) },
                    label = { Text("源语言（可选，如 en / ja，留空自动检测）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ---- 翻译 ----
            SectionCard("翻译 · Chat Completions API") {
                ApiFields(s.translate, "gpt-4o-mini") { s = s.copy(translate = it) }
            }

            // ---- TTS ----
            SectionCard("语音合成 · TTS API") {
                ApiFields(s.tts, "tts-1") { s = s.copy(tts = it) }
                OutlinedTextField(
                    value = s.ttsVoice,
                    onValueChange = { s = s.copy(ttsVoice = it) },
                    label = { Text("音色 voice（如 alloy / echo / nova）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("语速：${"%.1f".format(s.ttsSpeed)}x", fontSize = 13.sp)
                Slider(
                    value = s.ttsSpeed,
                    onValueChange = { s = s.copy(ttsSpeed = it) },
                    valueRange = 0.5f..2.0f
                )
            }

            Button(
                onClick = {
                    store.save(s)
                    toast("配置已保存")
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("保存配置") }

            Text(
                "API 地址填到 /v1 即可（如 https://api.openai.com/v1），" +
                "兼容 OpenAI 协议的第三方 / 自建服务均可。密钥仅保存在本机。",
                fontSize = 11.sp, color = Color.Gray
            )
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
    private fun ApiFields(cfg: ApiConfig, modelHint: String, onChange: (ApiConfig) -> Unit) {
        OutlinedTextField(
            value = cfg.baseUrl,
            onValueChange = { onChange(cfg.copy(baseUrl = it)) },
            label = { Text("API 地址（Base URL）") },
            placeholder = { Text("https://api.openai.com/v1") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = cfg.apiKey,
            onValueChange = { onChange(cfg.copy(apiKey = it)) },
            label = { Text("API Key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = cfg.model,
            onValueChange = { onChange(cfg.copy(model = it)) },
            label = { Text("模型") },
            placeholder = { Text(modelHint) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    // ---- 启动流程：悬浮窗 → 录音 → 通知 → (内录) MediaProjection ----

    private fun beginStart(s: AppSettings) {
        if (!s.whisper.isConfigured || !s.translate.isConfigured) {
            toast("请先填写 Whisper 与翻译 API 的地址和模型")
            return
        }
        if (s.ttsEnabled && !s.tts.isConfigured) {
            toast("已开启 TTS，请填写 TTS API 配置")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("请授予悬浮窗权限后再次点击开始")
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
            startService(0, null)
        }
    }

    private fun startService(resultCode: Int, resultData: Intent?) {
        val s = store.load()
        val intent = Intent(this, CaptionService::class.java).apply {
            putExtra(CaptionService.EXTRA_SOURCE, s.source)
            putExtra(CaptionService.EXTRA_RESULT_CODE, resultCode)
            if (resultData != null) putExtra(CaptionService.EXTRA_RESULT_DATA, resultData)
        }
        ContextCompat.startForegroundService(this, intent)
        toast("已启动，字幕将显示在悬浮窗中")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
