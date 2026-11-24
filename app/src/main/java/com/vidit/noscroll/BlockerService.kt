package com.vidit.noscroll

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Watches Instagram + YouTube and does three things:
 *  1. Blocks Reels / Shorts: if the full-screen player is on screen, presses Back.
 *  2. Hides the Reels / Shorts tab button by covering it with an overlay that eats taps.
 *  3. When blocking is OFF for an app, counts how many reels/shorts you scroll through
 *     and shows a live animated counter at the top of the screen.
 *
 * Detection is based on view resource IDs, which these apps change occasionally.
 * If something stops working after an app update, turn on DEBUG_DUMP, open Reels/Shorts,
 * and check Logcat (tag "NoScroll") for the new IDs, then update the lists below.
 */
class BlockerService : AccessibilityService() {

    companion object {
        private const val TAG = "NoScroll"
        private const val DEBUG_DUMP = false

        private const val IG = "com.instagram.android"
        private const val YT = "com.google.android.youtube"

        // View-ID fragments that only exist while the full-screen player is showing.
        // (YouTube calls Shorts "reel" internally; Instagram calls Reels "clips".)
        private val YT_SHORTS_IDS = listOf(
            "reel_player_page_container",
            "reel_watch_player",
            "reel_recycler",
            "reel_time_bar",
            "shorts_container",
        )
        private val IG_REELS_IDS = listOf(
            "clips_viewer_view_pager",
            "clips_viewer_container",
            "clips_video_container",
            "clips_ufi_component",
        )

        // The vertical pager you swipe through; its scroll events tell us a new reel came up.
        private val PAGER_IDS = listOf("clips_viewer_view_pager", "reel_recycler")

        private const val SCAN_DELAY_MS = 180L        // debounce: scan the tree at most ~5x/sec
        private const val BACK_COOLDOWN_MS = 1200L    // avoid double-back that exits the app
        private const val PLAYER_EXIT_GRACE_MS = 900L // ignore brief flickers (comments sheet etc.)
        private const val SCROLL_BURST_GAP_MS = 700L  // fallback reel counting by swipe bursts
        private const val MAX_DEPTH = 40
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tabHider: TabHider
    private lateinit var counter: ScrollCounterOverlay

    private var scanPending = false
    private val scanRunnable = Runnable { scanPending = false; scan() }
    private var lastBack = 0L

    // Reel counting session state
    private var inPlayer = false
    private var playerPkg: String? = null
    private var lastSeenPlayer = 0L
    private var playerApp = App.IG
    private var maxIndex = -1
    private var lastScrollAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        tabHider = TabHider(this)
        counter = ScrollCounterOverlay(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName) return

        if (pkg == IG || pkg == YT) {
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && inPlayer && pkg == playerPkg) {
                onPlayerScroll(event)
            }
            scheduleScan()
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Another app came up (or the launcher): re-check so overlays get removed.
            // For other apps we only look at the package name of the active window.
            scheduleScan()
        }
    }

    private fun scheduleScan(delay: Long = SCAN_DELAY_MS) {
        if (scanPending) return
        scanPending = true
        handler.postDelayed(scanRunnable, delay)
    }

    private fun scan() {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        if (root == null || (pkg != IG && pkg != YT)) {
            tabHider.hide()
            exitPlayer()
            return
        }
        val isYt = pkg == YT
        val app = if (isYt) App.YT else App.IG
        if (DEBUG_DUMP) dump(root, 0)

        // 1) Reels / Shorts player
        val playerOnScreen = containsAnyId(root, if (isYt) YT_SHORTS_IDS else IG_REELS_IDS, 0)
        val blockOn = Prefs.blockEnabled(this, app)
        val now = SystemClock.uptimeMillis()

        if (playerOnScreen && blockOn) {
            exitPlayer()
            if (now - lastBack >= BACK_COOLDOWN_MS) {
                lastBack = now
                performGlobalAction(GLOBAL_ACTION_BACK)
                Prefs.addBlocked(this, app)
                val label = if (isYt) "Shorts" else "Reels"
                Toast.makeText(this, "$label blocked 🚫", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Blocked $label")
            }
        } else if (playerOnScreen && Prefs.get(this, Prefs.SHOW_COUNTER)) {
            lastSeenPlayer = now
            enterPlayer(pkg!!, app)
        } else if (inPlayer) {
            if (playerPkg != pkg || now - lastSeenPlayer >= PLAYER_EXIT_GRACE_MS) exitPlayer()
            else scheduleScan(PLAYER_EXIT_GRACE_MS) // re-check once the grace period is over
        }

        // 2) Reels / Shorts tab button: only covered while blocking is on for this app.
        //    (Counter-only mode leaves the tab alone.)
        if (blockOn && Prefs.get(this, Prefs.HIDE_TABS)) {
            val bounds = findTabBounds(root, isYt)
            if (bounds != null) tabHider.show(bounds, isYt) else tabHider.hide()
        } else {
            tabHider.hide()
        }
    }

    // ---------- Reel counting ----------

    private fun enterPlayer(pkg: String, app: App) {
        if (inPlayer && playerPkg == pkg) return
        inPlayer = true
        playerPkg = pkg
        playerApp = app
        maxIndex = -1
        lastScrollAt = SystemClock.uptimeMillis()
        // The reel you just opened counts. The pill shows today's total for this app.
        val today = Prefs.addScrolled(this, app, 1)
        counter.show(today, if (app == App.YT) "shorts today" else "reels today")
    }

    private fun exitPlayer() {
        if (!inPlayer) return
        inPlayer = false
        playerPkg = null
        counter.hide()
    }

    private fun onPlayerScroll(event: AccessibilityEvent) {
        val sourceId = event.source?.viewIdResourceName
        val isPager = sourceId?.let { id -> PAGER_IDS.any { id.endsWith("/$it") } }
        if (isPager == false) return // some other list (e.g. comments)

        val now = SystemClock.uptimeMillis()
        val idx = event.fromIndex
        if (isPager == true && idx >= 0) {
            when {
                maxIndex < 0 -> maxIndex = idx
                idx > maxIndex -> { bump(idx - maxIndex); maxIndex = idx }
            }
        } else {
            // No index info: treat each separate swipe burst as one new reel.
            if (now - lastScrollAt > SCROLL_BURST_GAP_MS) bump(1)
        }
        lastScrollAt = now
    }

    private fun bump(n: Int) {
        if (n <= 0) return
        counter.update(Prefs.addScrolled(this, playerApp, n))
    }

    // ---------- Tree helpers ----------

    private fun containsAnyId(node: AccessibilityNodeInfo?, ids: List<String>, depth: Int): Boolean {
        if (node == null || depth > MAX_DEPTH) return false
        val id = node.viewIdResourceName
        if (id != null && node.isVisibleToUser && ids.any { id.endsWith("/$it") }) return true
        for (i in 0 until node.childCount) {
            if (containsAnyId(node.getChild(i), ids, depth + 1)) return true
        }
        return false
    }

    /** Finds the bottom-nav "Reels"/"Shorts" tab and returns its on-screen bounds. */
    private fun findTabBounds(root: AccessibilityNodeInfo, isYt: Boolean): Rect? {
        val screen = ScreenUtil.screenSize(this)
        val label = if (isYt) "Shorts" else "Reels"
        var best: AccessibilityNodeInfo? = null
        var bestBottom = -1

        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_DEPTH) return
            val id = node.viewIdResourceName
            val match = (!isYt && id != null && id.endsWith("/clips_tab")) ||
                node.contentDescription?.toString()?.trim().equals(label, ignoreCase = true) ||
                node.text?.toString()?.trim().equals(label, ignoreCase = true)
            if (match && node.isVisibleToUser) {
                val r = Rect().also { node.getBoundsInScreen(it) }
                if (r.bottom > screen.y * 0.8 && r.height() in 1..(screen.y / 6) && r.bottom > bestBottom) {
                    best = node
                    bestBottom = r.bottom
                }
            }
            for (i in 0 until node.childCount) visit(node.getChild(i), depth + 1)
        }
        visit(root, 0)

        val found = best ?: return null
        // Prefer the whole clickable tab (icon + label), not just the label text.
        var target: AccessibilityNodeInfo = found
        var n: AccessibilityNodeInfo? = found
        var steps = 0
        while (n != null && !n.isClickable && steps < 3) {
            n = n.parent
            steps++
        }
        n?.let { candidate ->
            val r = Rect().also { candidate.getBoundsInScreen(it) }
            if (candidate.isClickable && r.width() < screen.x / 2 && r.height() < screen.y / 6) target = candidate
        }
        return Rect().also { target.getBoundsInScreen(it) }.takeIf { !it.isEmpty }
    }

    private fun dump(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null || depth > MAX_DEPTH) return
        node.viewIdResourceName?.let { Log.d(TAG, "  ".repeat(depth) + it) }
        for (i in 0 until node.childCount) dump(node.getChild(i), depth + 1)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        handler.removeCallbacksAndMessages(null)
        if (::tabHider.isInitialized) tabHider.hide()
        if (::counter.isInitialized) counter.hideNow()
        return super.onUnbind(intent)
    }
}
