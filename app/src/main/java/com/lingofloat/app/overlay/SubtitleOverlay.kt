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
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.lingofloat.app.AppSettings
import com.lingofloat.app.R
import com.lingofloat.app.SettingsStore
import com.lingofloat.app.Strings
import kotlin.math.abs

/**
 * Global floating bilingual caption window: draggable, collapsible, closable.
 * Press and drag anywhere in either mode (movement beyond touch slop is a drag;
 * otherwise the event is passed through to button clicks).
 * Compact mode: two lines only (one original + one translation), title bar and
 * status line hidden; long-press 500ms to restore full mode.
 * All view operations are posted to the main thread.
 */
class SubtitleOverlay(
    private val context: Context,
    private val settings: AppSettings,
    private val onClose: () -> Unit
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val t = Strings.of(settings.language)

    private var root: LinearLayout? = null
    private var handle: LinearLayout? = null
    private var originalTv: TextView? = null
    private var translatedTv: TextView? = null
    private var statusTv: TextView? = null
    private var contentGroup: LinearLayout? = null
    private var collapseBtn: TextView? = null
    private var collapsed = false
    private var compact = settings.compactMode
    private var params: WindowManager.LayoutParams? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val exitCompact = Runnable { setCompact(false) }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        main.post {
            if (root != null) return@post

            val pad = dp(12)
            val container = DragLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_overlay)
                setPadding(pad, dp(6), pad, pad)
            }

            // Title bar (drag handle + compact + collapse + close)
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
            val compactBtn = TextView(context).apply {
                text = t.compact
                setTextColor(Color.parseColor("#CCFFFFFF"))
                textSize = 12f
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { setCompact(true) }
            }
            val collapse = TextView(context).apply {
                text = t.collapse
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
            handle.addView(compactBtn)
            handle.addView(collapse)
            handle.addView(close)
            this.handle = handle

            val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

            val original = TextView(context).apply {
                setTextColor(Color.parseColor("#FFB0BEC5"))
                textSize = settings.originalFontSize.toFloat()
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
                text = t.waitingVoice
            }
            val status = TextView(context).apply {
                setTextColor(Color.parseColor("#80FFFFFF"))
                textSize = 11f
                setPadding(0, dp(4), 0, 0)
                text = t.listening
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

            root = container
            applyCompactLayout()
            runCatching { wm.addView(container, p) }
        }
    }

    /** Toggle compact mode and persist the choice. */
    fun setCompact(on: Boolean) {
        compact = on
        main.post { applyCompactLayout() }
        runCatching {
            SettingsStore(context).save(settings.copy(compactMode = on))
        }
    }

    private fun applyCompactLayout() {
        val on = compact
        handle?.visibility = if (on) View.GONE else View.VISIBLE
        statusTv?.visibility = if (on) View.GONE else View.VISIBLE
        // Compact: single line + marquee auto-scroll (isSelected keeps the
        // marquee running even in a non-focusable overlay window)
        listOfNotNull(originalTv, translatedTv).forEach { tv ->
            if (on) {
                tv.setSingleLine(true)
                tv.ellipsize = TextUtils.TruncateAt.MARQUEE
                tv.marqueeRepeatLimit = -1
                tv.setHorizontallyScrolling(true)
                tv.isSelected = true
            } else {
                tv.setSingleLine(false)
                tv.setHorizontallyScrolling(false)
                tv.ellipsize = TextUtils.TruncateAt.END
                tv.isSelected = false
            }
        }
        originalTv?.maxLines = if (on) 1 else 3
        translatedTv?.maxLines = if (on) 1 else 6
        translatedTv?.setPadding(0, dp(if (on) 2 else 4), 0, 0)
        root?.setPadding(
            dp(if (on) 10 else 12), dp(if (on) 4 else 6),
            dp(if (on) 10 else 12), dp(if (on) 6 else 12)
        )
        if (!on) {
            collapsed = false
            contentGroup?.visibility = View.VISIBLE
            collapseBtn?.text = t.collapse
        }
    }

    private fun toggleCollapse() {
        collapsed = !collapsed
        contentGroup?.visibility = if (collapsed) View.GONE else View.VISIBLE
        collapseBtn?.text = if (collapsed) t.expand else t.collapse
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
            main.removeCallbacks(exitCompact)
            root?.let { runCatching { wm.removeView(it) } }
            root = null
            handle = null
            originalTv = null
            translatedTv = null
            statusTv = null
            contentGroup = null
            collapseBtn = null
        }
    }

    /**
     * Whole-window drag container: distinguishes clicks from drags via touch
     * interception — only intercept once movement exceeds touchSlop, otherwise
     * events flow to child views (buttons stay clickable).
     * In compact mode a 500ms long-press restores full mode.
     */
    @SuppressLint("ClickableViewAccessibility")
    private inner class DragLayout(context: Context) : LinearLayout(context) {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        private fun begin(ev: MotionEvent) {
            downX = ev.rawX; downY = ev.rawY
            startX = params?.x ?: 0; startY = params?.y ?: 0
            dragging = false
            if (compact) main.postDelayed(exitCompact, 500)
        }

        private fun move(ev: MotionEvent): Boolean {
            val dx = ev.rawX - downX
            val dy = ev.rawY - downY
            if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                dragging = true
                main.removeCallbacks(exitCompact)
            }
            if (dragging) {
                params?.let { p ->
                    p.x = startX + dx.toInt()
                    p.y = startY + dy.toInt()
                    runCatching { wm.updateViewLayout(this, p) }
                }
            }
            return dragging
        }

        private fun end() {
            main.removeCallbacks(exitCompact)
            dragging = false
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> begin(ev)
                MotionEvent.ACTION_MOVE -> if (move(ev)) return true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> end()
            }
            return false
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> begin(ev)
                MotionEvent.ACTION_MOVE -> move(ev)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> end()
            }
            return true
        }
    }
}
