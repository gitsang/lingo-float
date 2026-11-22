package com.lingofloat.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.lingofloat.app.AppSettings
import com.lingofloat.app.R

/**
 * 全局悬浮双语字幕窗：可拖动、可折叠、可关闭。
 * 所有视图操作都切到主线程执行。
 */
class SubtitleOverlay(
    private val context: Context,
    private val settings: AppSettings,
    private val onClose: () -> Unit
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private var root: LinearLayout? = null
    private var originalTv: TextView? = null
    private var translatedTv: TextView? = null
    private var statusTv: TextView? = null
    private var contentGroup: LinearLayout? = null
    private var collapseBtn: TextView? = null
    private var collapsed = false
    private var params: WindowManager.LayoutParams? = null

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        main.post {
            if (root != null) return@post

            val pad = dp(12)
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_overlay)
                setPadding(pad, dp(6), pad, pad)
            }

            // 标题栏（拖动把手 + 折叠 + 关闭）
            val handle = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val title = TextView(context).apply {
                text = "LingoFloat"
                setTextColor(Color.parseColor("#99FFFFFF"))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val collapse = TextView(context).apply {
                text = "收起"
                setTextColor(Color.parseColor("#CCFFFFFF"))
                textSize = 12f
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { toggleCollapse() }
            }
            collapseBtn = collapse
            val close = TextView(context).apply {
                text = "✕"
                setTextColor(Color.parseColor("#CCFFFFFF"))
                textSize = 14f
                setPadding(dp(10), dp(6), dp(4), dp(6))
                setOnClickListener { onClose() }
            }
            handle.addView(title)
            handle.addView(collapse)
            handle.addView(close)

            val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

            val original = TextView(context).apply {
                setTextColor(Color.parseColor("#FFB0BEC5"))
                textSize = 13f
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                visibility = if (settings.showOriginal) View.VISIBLE else View.GONE
                text = "…"
            }
            val translated = TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = settings.fontSize.toFloat()
                maxLines = 6
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
                text = "等待语音…"
            }
            val status = TextView(context).apply {
                setTextColor(Color.parseColor("#80FFFFFF"))
                textSize = 11f
                setPadding(0, dp(4), 0, 0)
                text = "聆听中…"
            }
            content.addView(original)
            content.addView(translated)
            content.addView(status)

            container.addView(handle)
            container.addView(content)

            originalTv = original
            translatedTv = translated
            statusTv = status
            contentGroup = content

            val screenWidth = context.resources.displayMetrics.widthPixels
            val p = WindowManager.LayoutParams(
                screenWidth - dp(32),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dp(16)
                y = dp(64)
            }
            params = p

            // 拖动
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            handle.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX; downY = ev.rawY
                        startX = p.x; startY = p.y
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = startX + (ev.rawX - downX).toInt()
                        p.y = startY + (ev.rawY - downY).toInt()
                        runCatching { wm.updateViewLayout(container, p) }
                        true
                    }
                    else -> false
                }
            }

            runCatching { wm.addView(container, p) }
            root = container
        }
    }

    private fun toggleCollapse() {
        collapsed = !collapsed
        contentGroup?.visibility = if (collapsed) View.GONE else View.VISIBLE
        collapseBtn?.text = if (collapsed) "展开" else "收起"
    }

    fun setOriginal(text: String) {
        main.post { originalTv?.text = text }
    }

    fun setTranslation(text: String) {
        main.post { translatedTv?.text = text }
    }

    fun setStatus(text: String) {
        main.post { statusTv?.text = text }
    }

    fun remove() {
        main.post {
            root?.let { runCatching { wm.removeView(it) } }
            root = null
            originalTv = null
            translatedTv = null
            statusTv = null
            contentGroup = null
            collapseBtn = null
        }
    }
}
