package com.sakthi.autoclock

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.*
import java.util.Calendar

class ClockService : AccessibilityService() {

    companion object {
        @Volatile var instance: ClockService? = null
    }

    private sealed class Outcome {
        object Done : Outcome()
        object Missing : Outcome()
        class Stopped(val msg: String) : Outcome()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var rec: Automation? = null
    private var lastClick = 0L
    private val recent = mutableListOf<Pair<Long, String>>()   // toast texts
    private var bar: View? = null
    private var counter: TextView? = null
    private var cap: View? = null
    private var capLp: WindowManager.LayoutParams? = null

    override fun onServiceConnected() { instance = this }
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean { instance = null; hideBar(); return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; scope.cancel(); super.onDestroy() }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ───────────────────────── events ─────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                if (e.packageName?.toString() != packageName && e.className?.toString()?.contains("Toast") == true) {
                    val t = e.text.joinToString(" ").trim()
                    if (t.isNotEmpty()) remember(t)
                }
            }
        }
    }

    private fun remember(t: String) {
        val now = System.currentTimeMillis()
        recent.removeAll { now - it.first > 60_000 }
        recent.add(now to t)
    }

    // ───────────────────────── recording ─────────────────────────

    fun startRecording(a: Automation) {
        a.steps.clear()
        rec = a
        lastClick = 0L
        showBar()
    }

    private fun ignoredPkgs(): Set<String> {
        val home = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        )?.activityInfo?.packageName
        return setOfNotNull(packageName, "com.android.systemui", home)
    }

    private fun dayMatch(s: CharSequence?, day: String): Boolean {
        val t = s?.toString()?.trim() ?: return false
        return t == day || t.startsWith("$day ")
    }

    private fun firstText(n: AccessibilityNodeInfo, depth: Int = 0): String? {
        n.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        if (depth > 3) return null
        for (i in 0 until n.childCount) n.getChild(i)?.let { c -> firstText(c, depth + 1)?.let { return it } }
        return null
    }

    // Touch capture: a transparent layer sees every touch, records it, then replays it to the app underneath.
    private fun showCapture() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val v = View(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        v.setOnTouchListener(object : View.OnTouchListener {
            var dx = 0f; var dy = 0f; var t0 = 0L
            override fun onTouch(view: View, ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { dx = ev.rawX; dy = ev.rawY; t0 = System.currentTimeMillis() }
                    MotionEvent.ACTION_UP -> onTouchDone(dx.toInt(), dy.toInt(), ev.rawX.toInt(), ev.rawY.toInt(),
                        System.currentTimeMillis() - t0)
                }
                return true
            }
        })
        wm.addView(v, lp)
        cap = v; capLp = lp
    }

    private fun onTouchDone(x1: Int, y1: Int, x2: Int, y2: Int, dur: Long) {
        val moved = Math.hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()) > 40
        rec?.let { recordTouch(it, x1, y1, x2, y2, dur, moved) }
        val p = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            if (moved) lineTo(x2.toFloat(), y2.toFloat())
        }
        forward(p, if (moved) dur.coerceIn(100L, 1500L) else 50L)
    }

    private fun forward(p: Path, dur: Long) {
        setCaptureTouchable(false)
        scope.launch {
            delay(80)
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, dur)).build()
            val ok = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { setCaptureTouchable(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { setCaptureTouchable(true) }
            }, null)
            if (!ok) setCaptureTouchable(true)
        }
    }

    private fun setCaptureTouchable(on: Boolean) {
        val v = cap ?: return
        val lp = capLp ?: return
        lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                   else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(v, lp) } catch (_: Exception) {}
    }

    private fun nodeAt(x: Int, y: Int): AccessibilityNodeInfo? {
        val r = Rect()
        val hits = allNodes().filter { it.isVisibleToUser }
            .filter { it.getBoundsInScreen(r); r.contains(x, y) }
            .sortedBy { it.getBoundsInScreen(r); r.width().toLong() * r.height() }
        return hits.firstOrNull { !it.text.isNullOrBlank() || !it.contentDescription.isNullOrBlank() }
            ?: hits.firstOrNull { !it.viewIdResourceName.isNullOrBlank() }
    }

    private fun recordTouch(a: Automation, x1: Int, y1: Int, x2: Int, y2: Int, dur: Long, moved: Boolean) {
        val pkg = rootInActiveWindow?.packageName?.toString() ?: return
        if (pkg in ignoredPkgs()) return
        if (a.targetPackage.isEmpty()) a.targetPackage = pkg
        val now = System.currentTimeMillis()
        val delay = if (a.steps.isEmpty()) 0L else (now - lastClick).coerceIn(500L, 6000L)
        lastClick = now
        val optional = pkg != a.targetPackage

        if (moved) {
            a.steps.add(Step("SWIPE", null, null, null, x1, y1, delay, optional, x2, y2, dur.coerceIn(100L, 1500L)))
        } else {
            val day = Calendar.getInstance().get(Calendar.DAY_OF_MONTH).toString()
            val n = nodeAt(x1, y1)
            val text = n?.let { firstText(it) }
            val desc = n?.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            val isToday = dayMatch(text, day) || dayMatch(desc, day)
            a.steps.add(
                Step(
                    type = if (isToday) "TAP_TODAY" else "TAP",
                    viewId = if (isToday) null else n?.viewIdResourceName,
                    text = if (isToday) null else text,
                    desc = if (isToday) null else desc,
                    x = x1, y = y1, delayMs = delay, optional = optional
                )
            )
        }
        counter?.text = "● ${a.steps.size}"
    }

    private fun showBar() {
        hideBar()
        showCapture()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xE6222222.toInt())
            setPadding(8, 8, 8, 8)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 8; y = 120 }

        val status = TextView(this).apply { text = "● 0"; setTextColor(0xFFFF5555.toInt()); setPadding(12, 0, 16, 0); gravity = Gravity.CENTER_VERTICAL }
        counter = status
        status.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
            override fun onTouch(v: View, ev: MotionEvent): Boolean {
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> { sx = ev.rawX; sy = ev.rawY; ox = lp.x; oy = lp.y }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = ox - (ev.rawX - sx).toInt(); lp.y = oy + (ev.rawY - sy).toInt()
                        wm.updateViewLayout(box, lp)
                    }
                }
                return true
            }
        })

        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t; textSize = 11f; isAllCaps = false
            minWidth = 0; minHeight = 0; minimumWidth = 0; minimumHeight = 0
            setPadding(18, 10, 18, 10)
            setOnClickListener { f() }
        }

        box.addView(status)
        box.addView(btn("Stop rule") { rec?.let { pickGuard(it) } })
        box.addView(btn("Optional") {
            rec?.steps?.lastOrNull()?.let {
                it.optional = !it.optional
                toast("Last step optional = ${it.optional}")
            }
        })
        box.addView(btn("✖") { rec = null; hideBar(); toast("Recording cancelled") })
        box.addView(btn("Done ✔") { finishRecording() })
        wm.addView(box, lp)
        bar = box
    }

    private fun hideBar() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        try { bar?.let { wm.removeView(it) } } catch (_: Exception) {}
        try { cap?.let { wm.removeView(it) } } catch (_: Exception) {}
        bar = null; counter = null; cap = null; capLp = null
    }

    private fun pickGuard(a: Automation) {
        val items = (recent.map { it.second } + screenTexts()).distinct().filter { it.length in 3..120 }.toTypedArray()
        if (items.isEmpty()) { toast("No text found on this screen"); return }
        val d = AlertDialog.Builder(ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert))
            .setTitle("Stop the run if this text appears:")
            .setItems(items) { _, i ->
                a.guards.add(Guard(items[i], "Stopped: ${items[i]}"))
                toast("Stop rule added")
            }
            .setNegativeButton("Cancel", null)
            .create()
        d.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        d.show()
    }

    private fun finishRecording() {
        val a = rec ?: return
        rec = null; hideBar()
        if (a.steps.isEmpty()) { toast("No taps recorded, nothing saved"); return }
        Store.save(this, a)
        Scheduler.schedule(this, a)
        Notifier.post(
            this, 3, "Saved: ${a.name}",
            "${a.steps.size} steps, ${a.guards.size} stop rule(s). Runs at ${"%02d:%02d".format(a.hour, a.minute)}.",
            Notifier.activity(this, 5, Intent(this, MainActivity::class.java))
        )
        toast("Saved ${a.steps.size} steps")
    }

    // ───────────────────────── screen helpers ─────────────────────────

    private fun roots(): List<AccessibilityNodeInfo> {
        val l = windows.mapNotNull { it.root }.filter { it.packageName?.toString() != packageName }
        return if (l.isNotEmpty()) l else listOfNotNull(rootInActiveWindow)
    }

    private fun flatten(n: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        out.add(n)
        for (i in 0 until n.childCount) n.getChild(i)?.let { flatten(it, out) }
    }

    private fun allNodes(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        roots().forEach { flatten(it, out) }
        return out
    }

    private fun screenTexts(): List<String> {
        val out = LinkedHashSet<String>()
        for (n in allNodes()) {
            n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
            n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }
        return out.toList()
    }

    private fun hitGuard(a: Automation, since: Long): Guard? {
        val texts = (screenTexts() + recent.filter { it.first >= since }.map { it.second }).map { it.lowercase() }
        return a.guards.firstOrNull { g -> texts.any { it.contains(g.text.lowercase()) } }
    }

    private fun findNode(s: Step): AccessibilityNodeInfo? {
        val nodes = allNodes().filter { it.isVisibleToUser }
        if (s.type == "TAP_TODAY") {
            val day = Calendar.getInstance().get(Calendar.DAY_OF_MONTH).toString()
            return nodes.firstOrNull { dayMatch(it.text, day) || dayMatch(it.contentDescription, day) }
        }
        if (!s.viewId.isNullOrBlank()) {
            val byId = nodes.filter { it.viewIdResourceName == s.viewId }
            if (byId.isNotEmpty()) {
                return if (!s.text.isNullOrBlank()) byId.firstOrNull { firstText(it) == s.text } ?: byId.first()
                else byId.first()
            }
        }
        if (!s.text.isNullOrBlank()) {
            nodes.firstOrNull { it.text?.toString()?.trim().equals(s.text, true) }?.let { return it }
        }
        if (!s.desc.isNullOrBlank()) {
            nodes.firstOrNull { it.contentDescription?.toString()?.trim().equals(s.desc, true) }?.let { return it }
        }
        return null
    }

    private fun click(n: AccessibilityNodeInfo): Boolean {
        var c: AccessibilityNodeInfo? = n
        while (c != null) {
            if (c.isClickable && c.isEnabled) return c.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            c = c.parent
        }
        val r = Rect(); n.getBoundsInScreen(r)
        return tapAt(r.centerX(), r.centerY())
    }

    private fun swipe(s: Step): Boolean {
        val p = Path().apply { moveTo(s.x.toFloat(), s.y.toFloat()); lineTo(s.x2.toFloat(), s.y2.toFloat()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, s.durMs.coerceAtLeast(100L))).build()
        return dispatchGesture(g, null, null)
    }

    private fun tapAt(x: Int, y: Int): Boolean {
        val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build()
        return dispatchGesture(g, null, null)
    }

    // ───────────────────────── replay ─────────────────────────

    fun startReplay(a: Automation) {
        job?.cancel()
        job = scope.launch {
            val since = System.currentTimeMillis()
            val open = Notifier.activity(this@ClockService, 5, Intent(this@ClockService, MainActivity::class.java))
            delay(3500)   // let the app open

            for ((i, s) in a.steps.withIndex()) {
                delay(s.delayMs.coerceIn(500L, 4000L))
                when (val r = waitAndTap(a, s, since)) {
                    is Outcome.Stopped -> {
                        Notifier.post(this@ClockService, 4, "${a.name} stopped ❌", r.msg, open); return@launch
                    }
                    Outcome.Missing -> if (!s.optional) {
                        Notifier.post(this@ClockService, 4, "${a.name} failed ❌",
                            "Step ${i + 1} not found: ${s.label()}", open); return@launch
                    }
                    Outcome.Done -> {}
                }
            }

            // the "out of range" message shows up a moment after the last tap
            val end = System.currentTimeMillis() + 6000
            while (System.currentTimeMillis() < end) {
                hitGuard(a, since)?.let {
                    Notifier.post(this@ClockService, 4, "${a.name} stopped ❌", it.message, open); return@launch
                }
                delay(400)
            }
            Notifier.post(this@ClockService, 4, "${a.name} done ✅", "All ${a.steps.size} steps completed.", open)
        }
    }

    private suspend fun waitAndTap(a: Automation, s: Step, since: Long): Outcome {
        val deadline = System.currentTimeMillis() + if (s.optional) 4000L else 15000L
        while (System.currentTimeMillis() < deadline) {
            hitGuard(a, since)?.let { return Outcome.Stopped(it.message) }
            if (s.type == "SWIPE") { swipe(s); return Outcome.Done }
            if (!s.hasIdentity() && s.type == "TAP") { tapAt(s.x, s.y); return Outcome.Done }
            findNode(s)?.let { click(it); return Outcome.Done }
            delay(500)
        }
        return Outcome.Missing
    }
}
