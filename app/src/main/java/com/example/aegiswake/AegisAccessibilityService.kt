package com.example.aegiswake

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.max

class AegisAccessibilityService : AccessibilityService() {
    private val TAG = "AegisAcc"
    private val h = Handler(Looper.getMainLooper())

    private val startLabels = listOf("start voice mode", "start voice", "voice", "mic", "microphone")
    private val stopLabels  = listOf("stop voice mode", "stop voice", "end voice")

    private var attempt = 0
    private val maxAttempts = 14
    private val attemptDelayMs = 350L

    private var fired = false
    private var suppressUntil = 0L
    private fun now() = System.currentTimeMillis()

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 50
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        Log.d(TAG, "Accessibility connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = (event?.packageName ?: "").toString()
        val cls = (event?.className ?: "").toString()
        if (now() < suppressUntil) return            // ⛔ debounce window

        // Reset when we’re not in ChatGPT
        if (!pkg.contains("openai", ignoreCase = true)) {
            fired = false
            return
        }
        Log.d(TAG, "event from pkg=$pkg cls=$cls type=${event?.eventType}")

        if (fired) return                            // already started → ignore

        h.removeCallbacksAndMessages(null)
        attempt = 0
        h.postDelayed(::tryStartVoiceLoop, 450)
    }


    override fun onInterrupt() {}

    private fun tryStartVoiceLoop() {
        if (attempt >= maxAttempts) { Log.w(TAG, "Gave up after $attempt attempts"); return }
        attempt++
        if (autoTapVoiceOnce()) return
        h.postDelayed(::tryStartVoiceLoop, attemptDelayMs)
    }

    private fun autoTapVoiceOnce(): Boolean {
        val root = resolveRoot() ?: run { Log.d(TAG, "no root yet"); return false }

        // If keyboard is up, dismiss once and retry next loop
        if (findImeWindowBounds() != null) {
            Log.d(TAG, "IME visible → dismissing")
            if (dismissImeIfVisible()) return false
        }

        // Already active? (stop button present)
        // already active?
        findClickable(root, stopLabels)?.let {
            Log.d(TAG, "Voice already active — stop button present")
            fired = true
            suppressUntil = now() + 4000                 // ignore for 4s
            return true
        }


        // (Optional) Exact viewId match — uncomment once you see the id in dumpTree
        // findByViewId(root, "com.openai.chatgpt:id/composer_speech_button")?.let {
        //     if (clickNode(it)) { Log.d(TAG, "Tapped via viewId"); return true }
        // }

        // Label match
        findClickable(root, startLabels)?.let { node ->
            if (clickNode(node)) {
                Log.d(TAG, "Tapped via label match")
                fired = true
                suppressUntil = now() + 4000
                return true
            }
        }

        // Heuristic: bottom-right clickable
        val screen = Rect(); root.getBoundsInScreen(screen)

// Try the rightmost clickable in the bottom band
        rightmostClickableInBottomBand(root, screen)?.let { (node, r) ->
            Log.d(TAG, "Rightmost candidate id=${node.viewIdResourceName} cls=${node.className} bounds=$r")
            // First: try clicking the node itself
            if (clickNode(node)) {
                Log.d(TAG, "Tapped via bottom-right node")
                fired = true
                suppressUntil = now() + 4000
                return true
            }

            // Second: tap a few dp **to the right** of that node’s right edge
            val bump = dp(18f)
            val safetyRight = dp(6f)
            val safetyBot = dp(10f)
            val imeTop = findImeWindowBounds()?.top ?: screen.bottom
            val yMax = (imeTop - safetyBot).coerceAtMost((screen.bottom - safetyBot.toInt()).toFloat()).toFloat()
            val x = (r.right.toFloat() + bump).coerceAtMost(screen.right - safetyRight)
            val y = (r.centerY().toFloat()).coerceAtMost(yMax)
            if (tap(x, y)) {
                Log.d(TAG, "Tapped to the RIGHT of candidate at ($x,$y)")
                fired = true
                suppressUntil = now() + 4000
                return true
            }
        }


        // Fallback: gesture taps (IME-aware)
        for ((x, y) in fallbackTapPoints(screen)) {
            if (tap(x, y)) { Log.d(TAG, "Tapped by gesture at ($x,$y)"); return true }
        }

        // First couple attempts: dump the tree to learn stable ids/labels
        if (attempt <= 2) dumpTree(root, 0)
        return false
    }

    // ---- root resolution ----
    private fun resolveRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { return it }
        val wins = windows ?: return null
        for (w in wins) {
            val r = w.root ?: continue
            val pkg = r.packageName?.toString() ?: continue
            if (pkg.contains("openai", true)) return r
        }
        return null
    }

    // ---- search helpers ----
    private fun findClickable(node: AccessibilityNodeInfo, needles: List<String>): AccessibilityNodeInfo? {
        val txt = (node.text ?: "").toString().lowercase()
        val desc = (node.contentDescription ?: "").toString().lowercase()
        if (node.isClickable && needles.any { txt.contains(it) || desc.contains(it) }) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val r = findClickable(c, needles)
            if (r != null) return r
        }
        return null
    }

    private fun findByViewId(node: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        if (node.viewIdResourceName == id && node.isClickable) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val r = findByViewId(c, id)
            if (r != null) return r
        }
        return null
    }

    private fun rightmostClickableInBottomBand(root: AccessibilityNodeInfo, screen: Rect)
            : Pair<AccessibilityNodeInfo, Rect>? {
        val list = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
        collectClickable(root, list)
        if (list.isEmpty()) return null

        val bottomBandTop = (screen.bottom * 0.70f).toInt()  // bottom 30% of screen
        // Keep only clickables inside the bottom band and within screen bounds
        val candidates = list.filter { (_, r) ->
            !r.isEmpty && r.centerY() >= bottomBandTop &&
                    r.left >= 0 && r.right <= screen.right && r.bottom <= screen.bottom
        }
        if (candidates.isEmpty()) return null

        // Pick the one with the **largest X** (rightmost center)
        return candidates.maxByOrNull { (_, r) -> r.centerX() }
    }


    private fun collectClickable(node: AccessibilityNodeInfo, out: MutableList<Pair<AccessibilityNodeInfo, Rect>>) {
        val r = Rect(); node.getBoundsInScreen(r)
        if (!r.isEmpty && node.isClickable) out.add(node to r)
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            collectClickable(c, out)
        }
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Rect(); node.getBoundsInScreen(r)
        return if (!r.isEmpty) tap(r.centerX().toFloat(), r.centerY().toFloat()) else false
    }

    private fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    // --- IME detection / dismissal ---
    private fun findImeWindowBounds(): Rect? {
        val wins = windows ?: return null
        for (w in wins) {
            if (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                val r = Rect()
                w.getBoundsInScreen(r)      // ✅ correct call
                if (!r.isEmpty) return r
            }
        }
        return null
    }

    private fun dismissImeIfVisible(): Boolean {
        val ime = findImeWindowBounds() ?: return false
        Log.d(TAG, "IME bounds: $ime")
        performGlobalAction(GLOBAL_ACTION_BACK)
        // small delay so next attempt sees IME gone
        h.postDelayed({}, 120)
        return true
    }

    private fun dp(px: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, px, resources.displayMetrics)

    private fun fallbackTapPoints(screen: Rect): List<Pair<Float, Float>> {
        val ime = findImeWindowBounds()
        val imeTop = ime?.top ?: screen.bottom
        val safetyX = dp(6f)
        val safetyY = dp(10f)
        val y = (imeTop.toFloat() - safetyY).coerceAtMost(screen.bottom - safetyY)
        val xRight = screen.right - safetyX

        // Try strict bottom-right, then nudge left/up a little if needed
        val step = dp(16f)
        return listOf(
            xRight to y,
            (xRight - step) to y,
            xRight to (y - step),
            (xRight - 2*step) to y,
            xRight to (y - 2*step),
            (xRight - step) to (y - step),
        )
    }


    private fun dumpTree(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null) return
        val r = Rect(); node.getBoundsInScreen(r)
        val indent = "  ".repeat(depth)
        val text = (node.text ?: "").toString().take(50)
        val desc = (node.contentDescription ?: "").toString().take(50)
        val id = node.viewIdResourceName ?: ""
        Log.d(TAG, "$indent• cls=${node.className} id=$id click=${node.isClickable} txt='$text' desc='$desc' bounds=$r")
        for (i in 0 until node.childCount) dumpTree(node.getChild(i), depth + 1)
    }
}
