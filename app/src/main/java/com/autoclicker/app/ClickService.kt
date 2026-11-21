package com.autoclicker.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as WLP
import android.view.accessibility.AccessibilityEvent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.hypot

/**
 * Dịch vụ trợ năng: vừa vẽ bảng điều khiển + các điểm nổi trên màn hình,
 * vừa thực hiện thao tác chạm đơn hoặc đa điểm đồng thời bằng dispatchGesture().
 */
class ClickService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ClickService? = null
        const val MIN_INTERVAL = 20L
        private const val MARKER_DP = 48
        private const val BUBBLE_DP = 44
        private const val MAX_SIMULTANEOUS_STROKES = 10
        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private class Task(val p: ClickPoint, var nextAt: Long, var count: Int = 0)

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    var profile: Profile = Profile("Mặc định")
        private set

    private val markers = LinkedHashMap<Int, MarkerView>()
    private val markerLps = HashMap<Int, WLP>()
    private var panel: LinearLayout? = null
    private var panelLp: WLP? = null
    private var bubbleView: View? = null
    private var bubbleLp: WLP? = null
    private var runBtn: TextView? = null
    private var modeBtn: TextView? = null
    private val idleViews = mutableListOf<View>()

    private var overlayShown = false
    private var minimized = false
    private var running = false
    private val tasks = mutableListOf<Task>()

    // ---------------------------------------------------------------- vòng đời

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        profile = Storage.loadCurrent(this)
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        stopRun()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        hideOverlay()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        hideOverlay()
        instance = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- overlay

    fun isOverlayShown() = overlayShown

    fun showOverlay() {
        if (overlayShown) {
            if (minimized) setMinimized(false)
            return
        }
        overlayShown = true
        minimized = false
        buildPanel()
        buildBubble()
        profile.points.forEach { addMarkerView(it) }
        refreshUi()
    }

    fun hideOverlay() {
        if (!::wm.isInitialized) return
        stopRun()
        markers.values.forEach { safeRemove(it) }
        markers.clear()
        markerLps.clear()
        panel?.let { safeRemove(it) }
        panel = null
        panelLp = null
        bubbleView?.let { safeRemove(it) }
        bubbleView = null
        bubbleLp = null
        idleViews.clear()
        if (overlayShown) persist()
        overlayShown = false
        minimized = false
    }

    private fun setMinimized(value: Boolean) {
        if (minimized == value) return
        if (value && running) stopRun()
        minimized = value
        if (minimized) {
            // Đồng bộ vị trí bong bóng thu nhỏ theo góc trên bên trái của bảng điều khiển hiện tại
            val pLp = panelLp
            val bLp = bubbleLp
            val bView = bubbleView
            if (pLp != null && bLp != null && bView != null) {
                bLp.x = pLp.x
                bLp.y = pLp.y
                try {
                    wm.updateViewLayout(bView, bLp)
                } catch (_: Exception) {
                }
            }
            panel?.visibility = View.GONE
            markers.values.forEach { it.visibility = View.GONE }
            bubbleView?.visibility = View.VISIBLE
        } else {
            // Mở rộng bảng điều khiển ngay tại vị trí bong bóng thu nhỏ
            val pLp = panelLp
            val bLp = bubbleLp
            val pView = panel
            if (pLp != null && bLp != null && pView != null) {
                pLp.x = bLp.x
                pLp.y = bLp.y
                try {
                    wm.updateViewLayout(pView, pLp)
                } catch (_: Exception) {
                }
            }
            bubbleView?.visibility = View.GONE
            panel?.visibility = View.VISIBLE
            markers.values.forEach { it.visibility = View.VISIBLE }
        }
    }

    private fun safeRemove(v: View) {
        try {
            wm.removeView(v)
        } catch (_: Exception) {
        }
    }

    private fun overlayLp(w: Int, h: Int): WLP {
        val flags = WLP.FLAG_NOT_FOCUSABLE or WLP.FLAG_LAYOUT_NO_LIMITS or WLP.FLAG_LAYOUT_IN_SCREEN
        val lp = WLP(w, h, WLP.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WLP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return lp
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- bong bóng thu nhỏ

    @SuppressLint("ClickableViewAccessibility")
    private fun buildBubble() {
        val size = dp(BUBBLE_DP)
        val bubble = TextView(this).apply {
            text = "AC"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(235, 33, 33, 33))
                setStroke(dp(2), Color.argb(255, 76, 175, 80))
            }
            visibility = View.GONE
        }
        val lp = overlayLp(size, size).apply {
            x = dp(8)
            y = dp(120)
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        bubble.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && hypot(dx, dy) > dp(6)) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        try {
                            wm.updateViewLayout(bubble, lp)
                        } catch (_: Exception) {
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        setMinimized(false)
                    }
                }
            }
            true
        }

        bubbleView = bubble
        bubbleLp = lp
        wm.addView(bubble, lp)
    }

    // ---------------------------------------------------------------- bảng điều khiển

    @SuppressLint("ClickableViewAccessibility")
    private fun buildPanel() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.argb(235, 33, 33, 33), dp(14))
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val lp = overlayLp(WRAP, WRAP).apply {
            x = dp(8)
            y = dp(120)
        }

        val headerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        headerBox.addView(TextView(this).apply {
            text = "≡  AutoClicker"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        headerBox.addView(TextView(this).apply {
            text = "By Minh Thái"
            setTextColor(Color.argb(180, 200, 200, 200))
            textSize = 10f
            gravity = Gravity.CENTER
        })

        var dx = 0f
        var dy = 0f
        var sx = 0
        var sy = 0
        headerBox.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dx = e.rawX; dy = e.rawY; sx = lp.x; sy = lp.y
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = sx + (e.rawX - dx).toInt()
                    lp.y = sy + (e.rawY - dy).toInt()
                    try {
                        wm.updateViewLayout(col, lp)
                    } catch (_: Exception) {
                    }
                }
            }
            true
        }
        col.addView(headerBox)

        val rb = panelButton("▶  Chạy", Color.argb(255, 46, 125, 50)) { toggleRun() }
        runBtn = rb
        col.addView(rb)

        fun idle(text: String, onClick: () -> Unit): TextView {
            val b = panelButton(text, Color.argb(255, 55, 71, 79), onClick)
            idleViews.add(b)
            col.addView(b)
            return b
        }

        val rowAddRemove = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) }
        }
        val btnAdd = panelButton("＋ Thêm", Color.argb(255, 55, 71, 79)) { addPoint() }.apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(3) }
        }
        val btnRemove = panelButton("－ Bớt", Color.argb(255, 55, 71, 79)) { removeSelectedOrLastPoint() }.apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(3) }
        }
        rowAddRemove.addView(btnAdd)
        rowAddRemove.addView(btnRemove)
        idleViews.add(rowAddRemove)
        col.addView(rowAddRemove)

        idle("⚙  Cài đặt điểm") { showEditor(null) }
        modeBtn = idle("") { toggleMode() }
        idle("💾  Bản lưu (5)") { showSlots() }

        val rowMinClose = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) }
        }
        val btnMin = panelButton("—  Thu nhỏ", Color.argb(255, 69, 90, 100)) { setMinimized(true) }.apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(3) }
        }
        val btnClose = panelButton("✕  Đóng", Color.argb(255, 55, 71, 79)) { hideOverlay() }.apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(3) }
        }
        rowMinClose.addView(btnMin)
        rowMinClose.addView(btnClose)
        idleViews.add(rowMinClose)
        col.addView(rowMinClose)

        panel = col
        panelLp = lp
        wm.addView(col, lp)
    }

    private fun panelButton(text: String, bg: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = rounded(bg, dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) }
            setOnClickListener { onClick() }
        }

    private fun refreshUi() {
        val ids = profile.points.map { it.id }
        markers.forEach { (id, v) ->
            v.isChosen = id == profile.selectedId
            v.label = (ids.indexOf(id) + 1).toString()
            v.invalidate()
        }
        modeBtn?.text = if (profile.runAll) "◎  Chạy: ĐA ĐIỂM cùng lúc" else "◉  Chạy: 1 điểm đã chọn"
        runBtn?.text = if (running) "■  Dừng" else "▶  Chạy"
        runBtn?.background = rounded(
            if (running) Color.argb(255, 198, 40, 40) else Color.argb(255, 46, 125, 50), dp(10)
        )
        idleViews.forEach { it.visibility = if (running) View.GONE else View.VISIBLE }
    }

    // ---------------------------------------------------------------- các điểm

    /** View hình tròn có số thứ tự, kéo thả để di chuyển, chạm để chọn, giữ lâu để sửa. */
    inner class MarkerView(ctx: Context) : View(ctx) {
        var isChosen = false
        var label = ""
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(3).toFloat()
            color = Color.WHITE
        }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = dp(16).toFloat()
            typeface = Typeface.DEFAULT_BOLD
        }

        override fun onDraw(canvas: Canvas) {
            val r = width / 2f
            fill.color = if (isChosen) Color.argb(210, 229, 57, 53) else Color.argb(210, 30, 136, 229)
            canvas.drawCircle(r, r, r - dp(3), fill)
            canvas.drawCircle(r, r, r - dp(3), stroke)
            canvas.drawText(label, r, r - (txt.descent() + txt.ascent()) / 2f, txt)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addMarkerView(p: ClickPoint) {
        val size = dp(MARKER_DP)
        val v = MarkerView(this)
        val lp = overlayLp(size, size).apply {
            x = p.x - size / 2
            y = p.y - size / 2
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var downTime = 0L
        v.setOnTouchListener { _, e ->
            if (running) return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    moved = false
                    downTime = SystemClock.uptimeMillis()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && hypot(dx, dy) > dp(8)) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        try {
                            wm.updateViewLayout(v, lp)
                        } catch (_: Exception) {
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        p.x = lp.x + size / 2
                        p.y = lp.y + size / 2
                        persist()
                    } else {
                        selectPoint(p.id)
                        if (SystemClock.uptimeMillis() - downTime >= 500) showEditor(p)
                    }
                }
            }
            true
        }
        wm.addView(v, lp)
        markers[p.id] = v
        markerLps[p.id] = lp
    }

    private fun selectPoint(id: Int) {
        profile.selectedId = id
        persist()
        refreshUi()
    }

    private fun addPoint() {
        val dm = resources.displayMetrics
        val nextId = (profile.points.maxOfOrNull { it.id } ?: 0) + 1
        val tpl = profile.points.lastOrNull()
        val n = profile.points.size
        val p = ClickPoint(
            id = nextId,
            x = dm.widthPixels / 2 + (n % 5) * dp(30) - dp(60),
            y = dm.heightPixels / 2 + (n % 5) * dp(30) - dp(60),
            intervalMs = tpl?.intervalMs ?: 1000L,
            holdMs = tpl?.holdMs ?: 50L,
            repeat = tpl?.repeat ?: 0
        )
        profile.points.add(p)
        profile.selectedId = p.id
        addMarkerView(p)
        persist()
        refreshUi()
    }

    private fun deletePoint(p: ClickPoint) {
        profile.points.remove(p)
        markers.remove(p.id)?.let { safeRemove(it) }
        markerLps.remove(p.id)
        if (profile.selectedId == p.id) profile.selectedId = profile.points.firstOrNull()?.id ?: -1
        persist()
        refreshUi()
    }

    private fun removeSelectedOrLastPoint() {
        val target = profile.points.firstOrNull { it.id == profile.selectedId }
            ?: profile.points.lastOrNull()
        if (target == null) {
            toast("Không còn điểm nào để xoá")
            return
        }
        deletePoint(target)
    }

    private fun toggleMode() {
        profile.runAll = !profile.runAll
        persist()
        refreshUi()
    }

    private fun persist() = Storage.saveCurrent(this, profile)

    // ---------------------------------------------------------------- chạy / dừng

    private fun toggleRun() {
        if (running) stopRun() else startRun()
    }

    private fun startRun() {
        val targets = if (profile.runAll) profile.points.toList()
        else profile.points.filter { it.id == profile.selectedId }
        if (targets.isEmpty()) {
            toast(if (profile.runAll) "Chưa có điểm nào, hãy bấm \"Thêm\"" else "Hãy chạm vào 1 điểm để chọn")
            return
        }
        tasks.clear()
        val t0 = SystemClock.uptimeMillis() + profile.startDelayMs
        targets.forEach { tasks.add(Task(it, t0)) }
        running = true
        setMarkersTouchable(false) // để cú bấm xuyên qua điểm nổi xuống ứng dụng bên dưới
        refreshUi()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun stopRun() {
        if (!running && tasks.isEmpty()) return
        running = false
        handler.removeCallbacks(tick)
        tasks.clear()
        setMarkersTouchable(true)
        refreshUi()
    }

    private fun setMarkersTouchable(touchable: Boolean) {
        markers.forEach { (id, v) ->
            val lp = markerLps[id] ?: return@forEach
            lp.flags = if (touchable) lp.flags and WLP.FLAG_NOT_TOUCHABLE.inv()
            else lp.flags or WLP.FLAG_NOT_TOUCHABLE
            try {
                wm.updateViewLayout(v, lp)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Bộ lập lịch đa điểm đồng thời: gom tất cả các điểm đến hạn cùng lúc (tối đa 10 điểm)
     * vào chung một GestureDescription để chạm đồng thời xuống màn hình.
     */
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            val dueList = tasks.filter { it.nextAt <= now + 5L }
                .sortedBy { it.nextAt }
                .take(MAX_SIMULTANEOUS_STROKES)

            var gap = 0L
            if (dueList.isNotEmpty()) {
                val pointsToClick = dueList.map { it.p }
                dispatchClicks(pointsToClick)

                val maxHold = pointsToClick.maxOf { it.holdMs.coerceAtLeast(1L) }
                gap = maxHold + 15L

                val finishTime = now + maxHold
                for (due in dueList) {
                    due.count++
                    if (due.p.repeat > 0 && due.count >= due.p.repeat) {
                        tasks.remove(due)
                    } else {
                        due.nextAt = finishTime + due.p.intervalMs.coerceAtLeast(MIN_INTERVAL)
                    }
                }
            }

            if (tasks.isEmpty()) {
                stopRun()
                return
            }

            val earliest = tasks.minOf { it.nextAt }
            val delay = maxOf(earliest - SystemClock.uptimeMillis(), gap, 1L)
            handler.postDelayed(this, delay)
        }
    }

    private fun dispatchClicks(points: List<ClickPoint>) {
        if (points.isEmpty()) return
        val builder = GestureDescription.Builder()
        for (p in points) {
            val path = Path().apply { moveTo(p.x.toFloat(), p.y.toFloat()) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, p.holdMs.coerceAtLeast(1L))
            builder.addStroke(stroke)
        }
        dispatchGesture(builder.build(), null, null)
    }

    // ---------------------------------------------------------------- hộp thoại

    private fun themed(): Context =
        ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)

    private fun showDialog(d: AlertDialog) {
        d.window?.setType(WLP.TYPE_ACCESSIBILITY_OVERLAY)
        d.show()
    }

    private fun showEditor(p: ClickPoint?) {
        val point = p ?: profile.points.firstOrNull { it.id == profile.selectedId }
        if (point == null) {
            toast("Hãy chạm vào 1 điểm để chọn trước")
            return
        }
        val ctx = themed()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        fun field(label: String, value: Long): EditText {
            box.addView(TextView(ctx).apply {
                text = label
                textSize = 13f
                setPadding(0, dp(10), 0, 0)
            })
            val et = EditText(ctx).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(value.toString())
                setSingleLine()
            }
            box.addView(et)
            return et
        }

        val fInterval = field("Khoảng thời gian giữa 2 lần bấm (ms)", point.intervalMs)
        val fHold = field("Thời gian giữ mỗi lần bấm (ms)", point.holdMs)
        val fRepeat = field("Số lần bấm (0 = vô hạn)", point.repeat.toLong())
        val fDelay = field("Trễ trước khi bắt đầu chạy (ms, chung cho mọi điểm)", profile.startDelayMs)

        fun applyTo(target: ClickPoint) {
            fInterval.text.toString().toLongOrNull()?.let { target.intervalMs = it.coerceAtLeast(MIN_INTERVAL) }
            fHold.text.toString().toLongOrNull()?.let { target.holdMs = it.coerceIn(1L, 10_000L) }
            fRepeat.text.toString().toIntOrNull()?.let { target.repeat = it.coerceAtLeast(0) }
        }

        val ordinal = profile.points.indexOf(point) + 1
        val dlg = AlertDialog.Builder(ctx)
            .setTitle("Điểm $ordinal  (${point.x}, ${point.y})")
            .setView(box)
            .setPositiveButton("Lưu điểm này") { _, _ ->
                applyTo(point)
                fDelay.text.toString().toLongOrNull()?.let { profile.startDelayMs = it.coerceAtLeast(0L) }
                persist()
                toast("Đã lưu điểm $ordinal")
            }
            .setNeutralButton("Áp dụng TẤT CẢ") { _, _ ->
                profile.points.forEach { applyTo(it) }
                fDelay.text.toString().toLongOrNull()?.let { profile.startDelayMs = it.coerceAtLeast(0L) }
                persist()
                toast("Đã áp dụng cho ${profile.points.size} điểm")
            }
            .setNegativeButton("Xoá điểm") { _, _ -> deletePoint(point) }
            .create()
        showDialog(dlg)
    }

    private fun showSlots() {
        val slots = Storage.loadSlots(this)
        val labels = Array(Storage.MAX_SLOTS) { i ->
            val s = slots[i]
            "Ô ${i + 1}: " + if (s == null) "(trống)" else "${s.name} · ${s.points.size} điểm"
        }
        val dlg = AlertDialog.Builder(themed())
            .setTitle("Bản lưu (tối đa ${Storage.MAX_SLOTS})")
            .setItems(labels) { _, i -> showSlotActions(i) }
            .setNegativeButton("Đóng", null)
            .create()
        showDialog(dlg)
    }

    private fun showSlotActions(i: Int) {
        val existing = Storage.loadSlots(this)[i]
        val ctx = themed()
        val et = EditText(ctx).apply {
            setText(existing?.name ?: "Bản lưu ${i + 1}")
            setSingleLine()
        }
        val box = LinearLayout(ctx).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(et, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        val dlg = AlertDialog.Builder(ctx)
            .setTitle("Ô ${i + 1}")
            .setView(box)
            .setPositiveButton("Lưu vào ô") { _, _ ->
                val copy = profile.copy()
                copy.name = et.text.toString().ifBlank { "Bản lưu ${i + 1}" }
                Storage.saveSlot(this, i, copy)
                toast("Đã lưu vào ô ${i + 1}")
            }
            .setNeutralButton("Tải ô này") { _, _ ->
                if (existing == null) toast("Ô ${i + 1} đang trống") else loadProfile(existing.copy())
            }
            .setNegativeButton("Xoá ô") { _, _ ->
                Storage.saveSlot(this, i, null)
                toast("Đã xoá ô ${i + 1}")
            }
            .create()
        showDialog(dlg)
    }

    private fun loadProfile(p: Profile) {
        stopRun()
        markers.values.forEach { safeRemove(it) }
        markers.clear()
        markerLps.clear()
        profile = p
        if (profile.points.none { it.id == profile.selectedId }) {
            profile.selectedId = profile.points.firstOrNull()?.id ?: -1
        }
        profile.points.forEach { addMarkerView(it) }
        persist()
        refreshUi()
        toast("Đã tải \"${p.name}\"")
    }
}