package com.matchum.bubble

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max

/**
 * 다른 앱(카톡, 문자 등)의 입력창 글자를 보고 맞춤법 말풍선을 띄우는 접근성 서비스.
 * 키보드는 건드리지 않고, 입력창 바로 위에 작은 창을 띄웁니다.
 */
class SpellService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var bubble: View? = null

    private var target: AccessibilityNodeInfo? = null
    private var checkedText = ""          // 말풍선이 기준으로 삼은 글
    private var shownKey: String? = null  // 같은 내용이면 다시 그리지 않기 위함
    private var dismissed = false         // ✕ 누르면 이번 메시지에선 숨김
    private var aiRunning = false
    private var aiResultFor: String? = null

    private val checkRunnable = Runnable { runCheck() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> schedule()
        }
    }

    override fun onInterrupt() = hide()

    override fun onDestroy() {
        hide()
        super.onDestroy()
    }

    /** 타자를 잠깐 멈췄을 때 검사 (계속 치는 중엔 말풍선이 깜빡이지 않도록) */
    private fun schedule(delayMs: Long = 450) {
        handler.removeCallbacks(checkRunnable)
        handler.postDelayed(checkRunnable, delayMs)
    }

    private fun currentInput(): AccessibilityNodeInfo? {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        if (!node.isEditable || node.isPassword) return null
        if (node.packageName?.toString() == packageName) return null
        return node
    }

    private fun textOf(node: AccessibilityNodeInfo): String =
        if (node.isShowingHintText) "" else node.text?.toString() ?: ""

    private fun runCheck() {
        if (!Prefs.enabled(this)) { hide(); return }
        val node = currentInput()
        if (node == null) { hide(); return }

        val text = textOf(node)
        if (text.isBlank()) {          // 메시지를 보냈거나 다 지움 → 상태 초기화
            dismissed = false
            aiResultFor = null
            hide()
            return
        }
        if (aiRunning) return
        if (aiResultFor != null) {
            if (aiResultFor == text) return   // AI 결과 말풍선 유지
            aiResultFor = null
        }
        if (dismissed) { hide(); return }

        target = node
        checkedText = text

        val issues = RuleChecker.check(text)
        val showAi = Prefs.aiReady(this) && text.trim().length >= 4
        if (issues.isEmpty() && !showAi) { hide(); return }

        val key = text + "|" + issues.size + "|" + showAi
        if (key == shownKey && bubble != null) {
            reposition()
            return
        }
        show(buildIssueBubble(issues, showAi))
        shownKey = key
    }

    // ───────────────────────── 고치기 ─────────────────────────

    private fun applyIssues(issues: List<Issue>) {
        val node = target ?: return
        node.refresh()
        val current = textOf(node)
        if (current != checkedText) { hide(); schedule(0); return }

        setText(node, RuleChecker.apply(current, issues))
        toast(if (issues.size == 1) issues[0].reason else "${issues.size}곳을 고쳤어요 ✓")
        hide()
        schedule()
    }

    private fun applyWhole(newText: String, basedOn: String) {
        val node = target ?: return
        node.refresh()
        if (textOf(node) != basedOn) { toast("글이 바뀌어서 적용하지 않았어요"); hide(); schedule(0); return }
        setText(node, newText)
        aiResultFor = null
        hide()
        schedule()
    }

    private fun setText(node: AccessibilityNodeInfo, newText: String) {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) { toast("이 앱에서는 자동으로 고칠 수 없어요"); return }
        val sel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newText.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newText.length)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
    }

    // ───────────────────────── AI 검사 ─────────────────────────

    private fun runAi() {
        val text = checkedText
        val key = Prefs.apiKey(this)
        val model = Prefs.model(this)
        aiRunning = true
        show(buildMessageBubble("✨ AI가 검사하는 중…", null))
        shownKey = null

        Thread {
            val result = runCatching { AiChecker.correct(key, model, text) }
            handler.post {
                aiRunning = false
                val node = currentInput()
                if (node == null || textOf(node) != text) { hide(); schedule(0); return@post }
                target = node
                result.onSuccess { fixed ->
                    if (fixed.isBlank() || fixed == text) {
                        toast("고칠 곳이 없어요 👍")
                        hide()
                    } else {
                        aiResultFor = text
                        show(buildMessageBubble(fixed) { applyWhole(fixed, text) })
                    }
                }.onFailure {
                    toast("AI 검사 실패: ${it.message ?: "네트워크를 확인해 주세요"}")
                    hide()
                }
            }
        }.start()
    }

    // ───────────────────────── 말풍선 그리기 ─────────────────────────

    private fun buildIssueBubble(issues: List<Issue>, showAi: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(BUBBLE_BG, 20)
            setPadding(dp(5), dp(5), dp(5), dp(5))
            elevation = dp(6).toFloat()
        }
        issues.take(3).forEach { issue ->
            row.addView(chip("${issue.original} → ${issue.replacement}", CHIP_FIX, bold = true) {
                applyIssues(listOf(issue))
            })
        }
        if (issues.size > 1) {
            row.addView(chip("모두 고치기", CHIP_ALL) { applyIssues(issues) })
        }
        if (showAi) {
            row.addView(chip("✨ AI", CHIP_AI) { runAi() })
        }
        row.addView(chip("✕", 0) { dismissed = true; hide() })
        return row
    }

    private fun buildMessageBubble(message: String, onApply: (() -> Unit)?): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(BUBBLE_BG, 16)
            setPadding(dp(12), dp(10), dp(8), dp(6))
            elevation = dp(6).toFloat()
        }
        val tv = TextView(this).apply {
            text = message
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 5
            maxWidth = resources.displayMetrics.widthPixels - dp(56)
        }
        box.addView(tv)
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(6), 0, 0)
        }
        if (onApply != null) {
            buttons.addView(chip("이대로 바꾸기", CHIP_FIX, bold = true) { onApply() })
        }
        buttons.addView(chip("✕", 0) { aiResultFor = null; dismissed = true; hide() })
        box.addView(buttons)
        return box
    }

    private fun chip(label: String, color: Int, bold: Boolean = false, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            if (color != 0) background = rounded(color, 14)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(3), 0, dp(3), 0) }
            setOnClickListener { onClick() }
        }

    // ───────────────────────── 창 띄우기 / 위치 ─────────────────────────

    private fun layoutParamsFor(view: View): WindowManager.LayoutParams? {
        val node = target ?: return null
        val r = Rect()
        node.getBoundsInScreen(r)
        val screenW = resources.displayMetrics.widthPixels
        view.measure(
            View.MeasureSpec.makeMeasureSpec(screenW - dp(16), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val w = view.measuredWidth
        val h = view.measuredHeight

        // 기본은 입력창 바로 위, 공간이 없으면 입력창 아래
        var y = r.top - h - dp(6)
        if (y < dp(28)) y = r.bottom + dp(6)
        val x = r.left.coerceIn(dp(8), max(dp(8), screenW - w - dp(8)))

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun show(view: View) {
        val wm = wm ?: return
        val lp = layoutParamsFor(view) ?: return
        hide()
        try {
            wm.addView(view, lp)
            bubble = view
        } catch (e: Exception) {
            bubble = null
        }
    }

    private fun reposition() {
        val v = bubble ?: return
        val lp = layoutParamsFor(v) ?: return
        try { wm?.updateViewLayout(v, lp) } catch (_: Exception) {}
    }

    private fun hide() {
        bubble?.let { v -> try { wm?.removeView(v) } catch (_: Exception) {} }
        bubble = null
        shownKey = null
    }

    // ───────────────────────── 도우미 ─────────────────────────

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    companion object {
        private val BUBBLE_BG = 0xF0222326.toInt()
        private val CHIP_FIX = 0xFF2F6FEB.toInt()
        private val CHIP_ALL = 0xFF3A3D44.toInt()
        private val CHIP_AI = 0xFF7B4FD6.toInt()
    }
}
