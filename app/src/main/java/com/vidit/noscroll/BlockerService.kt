package com.vidit.noscroll

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.min

/**
 * Watches Instagram + YouTube and:
 *  1. Blocks Reels / Shorts (presses Back) when blocking is on, or when a daily limit is used up.
 *  2. Covers the Reels / Shorts tab button while they're blocked.
 *  3. Otherwise counts every reel/short you land on, tracks time spent, and shows a live pill.
 *
 * All screen reading happens on a background thread so the pill animations stay smooth;
 * overlay and toast work is posted to the main thread.
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
        // The vertical pager you swipe through.
        private val PAGER_IDS = listOf("clips_viewer_view_pager", "reel_recycler")

        private const val EVENT_SCAN_DELAY_MS = 60L   // small batch window for event bursts
        private const val TICK_MS = 500L              // regular re-check while inside IG / YT
        private const val BACK_COOLDOWN_MS = 1200L    // avoid double-back that exits the app
        private const val PLAYER_EXIT_GRACE_MS = 900L // ignore brief flickers (comments sheet etc.)
        private const val SESSION_GAP_MS = 3000L      // away longer than this = new session
        private const val PAGE_TRACK_GRACE_MS = 1500L // fall back to scroll events if pages aren't found
        private const val SCROLL_BURST_GAP_MS = 700L
        private const val MAX_DEPTH = 40
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: HandlerThread
    private lateinit var bg: Handler
    private lateinit var tabHider: TabHider
    private lateinit var counter: ScrollCounterOverlay

    private var scanQueued = false
    private val scanRunnable = Runnable { scanQueued = false; scan() }
    private var ticking = false
    private val tickRunnable = object : Runnable {
        override fun run() {
            scan()
            if (ticking) bg.postDelayed(this, TICK_MS)
        }
    }

    // ----- state (touched only on the background thread) -----
    private var lastBack = 0L

    // session = one visit to Instagram / YouTube
    private var sessionPkg: String? = null
    private var lastInAppAt = 0L
    private var sessionCount = 0

    // player
    private var inPlayer = false
    private var playerApp = App.IG
    private var playerSince = 0L
    private var lastSeenPlayer = 0L
    private var lastPageKey = -1
    private val seenRows = HashSet<Int>()
    private var pageTrackingWorks = false
    private var lastScrollAt = 0L

    // time tracking
    private var lastTimeMark = 0L
    private var pendingMs = 0L

    // ---------- lifecycle ----------

    override fun onServiceConnected() {
        super.onServiceConnected()
        worker = HandlerThread("noscroll-scan").also { it.start() }
        bg = Handler(worker.looper)
        tabHider = TabHider(this)
        counter = ScrollCounterOverlay(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName || !::bg.isInitialized) return

        if (pkg == IG || pkg == YT) {
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                val sourceId = event.source?.viewIdResourceName
                val fromIndex = event.fromIndex
                bg.post { onPlayerScroll(sourceId, fromIndex) }
            }
            requestScan()
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Another app came up (or the launcher): re-check so overlays get removed.
            // For other apps we only look at the package name of the active window.
            requestScan()
        }
    }

    private fun requestScan() {
        if (scanQueued) return
        scanQueued = true
        bg.postDelayed(scanRunnable, EVENT_SCAN_DELAY_MS)
    }

    private fun ui(block: () -> Unit) = main.post(block)

    // ---------- main loop ----------

    private fun scan() {
        val now = SystemClock.uptimeMillis()
        accountTime(now)

        val root = try { rootInActiveWindow } catch (e: Exception) { null }
        val pkg = root?.packageName?.toString()
        if (root == null || (pkg != IG && pkg != YT)) {
            ticking = false
            exitPlayer()
            ui { tabHider.hide() }
            return
        }

        // New visit to the app → new session.
        if (pkg != sessionPkg || now - lastInAppAt > SESSION_GAP_MS) {
            sessionPkg = pkg
            sessionCount = 0
        }
        lastInAppAt = now
        if (!ticking) {
            ticking = true
            bg.postDelayed(tickRunnable, TICK_MS)
        }

        val isYt = pkg == YT
        val app = if (isYt) App.YT else App.IG
        if (DEBUG_DUMP) dump(root, 0)

        val playerOnScreen = containsAnyId(root, if (isYt) YT_SHORTS_IDS else IG_REELS_IDS, 0)
        val alwaysBlock = Prefs.blockEnabled(this, app)
        val limit = Limits.status(this, pendingMs)
        // Time limit closes the player right away. The reels limit lets you finish the reel
        // you're on; the next one (or re-opening the player) gets closed.
        val limitHit = limit.timeReached || (limit.countReached && !inPlayer)
        val blocked = alwaysBlock || limitHit

        if (playerOnScreen && blocked) {
            exitPlayer()
            kickOut(app, now, byLimit = !alwaysBlock)
        } else if (playerOnScreen) {
            lastSeenPlayer = now
            if (!inPlayer || playerApp != app) enterPlayer(app, now)
            trackPage(root, app, now)
            if (inPlayer) refreshPill(animateCount = false)
        } else if (inPlayer && now - lastSeenPlayer >= PLAYER_EXIT_GRACE_MS) {
            exitPlayer()
        }

        // Tab button: covered only while Reels/Shorts are blocked (counter-only mode leaves it alone).
        if (blocked && Prefs.get(this, Prefs.HIDE_TABS)) {
            val bounds = findTabBounds(root, isYt)
            ui { if (bounds != null) tabHider.show(bounds, isYt) else tabHider.hide() }
        } else {
            ui { tabHider.hide() }
        }
    }

    private fun kickOut(app: App, now: Long, byLimit: Boolean) {
        if (now - lastBack < BACK_COOLDOWN_MS) return
        lastBack = now
        performGlobalAction(GLOBAL_ACTION_BACK)
        if (!byLimit) Prefs.addBlocked(this, app)
        val label = if (app == App.YT) "Shorts" else "Reels"
        val msg = if (byLimit) "Daily limit reached ⏰ $label are closed for today" else "$label blocked 🚫"
        ui { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        Log.d(TAG, msg)
    }

    // ---------- player session ----------

    private fun enterPlayer(app: App, now: Long) {
        inPlayer = true
        playerApp = app
        playerSince = now
        lastSeenPlayer = now
        lastPageKey = -1
        seenRows.clear()
        pageTrackingWorks = false
        lastScrollAt = now
        lastTimeMark = now
        if (Prefs.get(this, Prefs.SHOW_COUNTER)) {
            val st = pillState()
            ui { counter.show(st) }
        }
    }

    private fun exitPlayer() {
        if (!inPlayer) return
        accountTime(SystemClock.uptimeMillis())
        flushTime()
        inPlayer = false
        ui { counter.hide() }
    }

    /**
     * Finds the page that is fully settled in the pager and counts it if it's a new one.
     * Mid-swipe (no page fully on screen) nothing happens.
     */
    private fun trackPage(root: AccessibilityNodeInfo, app: App, now: Long) {
        val pager = findPager(root) ?: return
        val pr = Rect().also { pager.getBoundsInScreen(it) }
        if (pr.height() <= 0) return

        var settled: AccessibilityNodeInfo? = null
        for (i in 0 until pager.childCount) {
            val c = pager.getChild(i) ?: continue
            val b = Rect().also { c.getBoundsInScreen(it) }
            if (b.height() >= pr.height() * 0.9f && abs(b.top - pr.top) <= pr.height() * 0.06f) {
                settled = c
                break
            }
        }
        val page = settled ?: return
        pageTrackingWorks = true

        val key = page.hashCode() // stable per view; neighbouring pages are always different views
        val row = page.collectionItemInfo?.rowIndex ?: -1
        val isNew = when {
            row >= 0 -> seenRows.add(row) // scrolling back to one you've seen doesn't count again
            else -> key != lastPageKey
        }
        lastPageKey = key
        if (isNew) onNewReel(app, now)
    }

    /** Fallback when the pager's pages can't be read: count each separate swipe burst. */
    private fun onPlayerScroll(sourceId: String?, fromIndex: Int) {
        if (!inPlayer || pageTrackingWorks) return
        val now = SystemClock.uptimeMillis()
        if (now - playerSince < PAGE_TRACK_GRACE_MS) return
        if (sourceId != null && PAGER_IDS.none { sourceId.endsWith("/$it") }) return
        if (now - lastScrollAt > SCROLL_BURST_GAP_MS) onNewReel(playerApp, now)
        lastScrollAt = now
    }

    private fun onNewReel(app: App, now: Long) {
        // Reels limit: you get exactly N, the next one closes the player.
        if (Limits.countLimit(this) > 0 && Prefs.scrolledToday(this) >= Limits.countLimit(this) &&
            !Prefs.blockEnabled(this, app)
        ) {
            exitPlayer()
            kickOut(app, now, byLimit = true)
            return
        }
        sessionCount++
        Prefs.addScrolled(this, app, 1)
        refreshPill(animateCount = true)
    }

    // ---------- time ----------

    private fun accountTime(now: Long) {
        if (inPlayer && lastTimeMark > 0) {
            pendingMs += min(now - lastTimeMark, 1500L).coerceAtLeast(0L)
            if (pendingMs >= 1000L) flushTime()
        }
        lastTimeMark = now
    }

    private fun flushTime() {
        if (pendingMs <= 0) return
        Prefs.addTime(this, playerApp, pendingMs)
        pendingMs = 0
    }

    // ---------- pill ----------

    private fun pillState(): PillState {
        val l = Limits.status(this, pendingMs)
        return PillState(
            session = sessionCount,
            today = Prefs.scrolledToday(this),
            noun = if (playerApp == App.YT) "shorts" else "reels",
            progress = l.progress,
            timeLeftMs = l.timeLeftMs,
            countLimit = l.countLimit,
        )
    }

    private fun refreshPill(animateCount: Boolean) {
        if (!Prefs.get(this, Prefs.SHOW_COUNTER)) return
        val st = pillState()
        ui { counter.update(st, animateCount) }
    }

    // ---------- tree helpers ----------

    private fun containsAnyId(node: AccessibilityNodeInfo?, ids: List<String>, depth: Int): Boolean {
        if (node == null || depth > MAX_DEPTH) return false
        val id = node.viewIdResourceName
        if (id != null && node.isVisibleToUser && ids.any { id.endsWith("/$it") }) return true
        for (i in 0 until node.childCount) {
            if (containsAnyId(node.getChild(i), ids, depth + 1)) return true
        }
        return false
    }

    /** The vertical pager: a known ID, else the largest scrollable node on screen. */
    private fun findPager(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var byId: AccessibilityNodeInfo? = null
        var biggest: AccessibilityNodeInfo? = null
        var biggestArea = 0
        fun visit(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > MAX_DEPTH || byId != null) return
            val id = n.viewIdResourceName
            if (id != null && PAGER_IDS.any { id.endsWith("/$it") } && n.isVisibleToUser) {
                byId = n
                return
            }
            if (n.isScrollable && n.isVisibleToUser) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                val area = r.width() * r.height()
                if (area > biggestArea) { biggestArea = area; biggest = n }
            }
            for (i in 0 until n.childCount) visit(n.getChild(i), depth + 1)
        }
        visit(root, 0)
        return byId ?: biggest
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
        if (::bg.isInitialized) {
            ticking = false
            bg.removeCallbacksAndMessages(null)
            bg.post { flushTime() }
            worker.quitSafely()
        }
        main.removeCallbacksAndMessages(null)
        if (::tabHider.isInitialized) tabHider.hide()
        if (::counter.isInitialized) counter.hideNow()
        return super.onUnbind(intent)
    }
}
