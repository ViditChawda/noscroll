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
        // Button labels etc. that are the same on every reel (lower-case). Anything with a digit
        // (likes, views, timers) is ignored too. Strings seen on several reels are learned as UI.
        private val STOP_WORDS = setOf(
            "like", "liked", "unlike", "likes", "comment", "comments", "share", "send", "remix",
            "subscribe", "subscribed", "unsubscribe", "follow", "following", "followed", "more", "less",
            "reels", "reel", "shorts", "short", "audio", "original audio", "search", "camera", "home",
            "dislike", "dislike this video", "save", "saved", "back", "navigate up", "more options",
            "menu", "pause", "play", "pause video", "play video", "mute", "unmute", "sound on", "sound off",
            "profile", "your story", "explore", "create", "notifications", "see translation", "view all",
            "add a comment…", "add comment", "add a comment...", "sponsored", "learn more", "use template",
            "use audio", "remix this", "view insights", "boost reel", "subscriptions", "you", "library",
            "go to channel", "see more", "close", "options", "share this video", "comments disabled.",
            "visit profile", "messages", "direct", "new post", "reels tab", "home tab", "search and explore",
        )
        private const val STABLE_MS = 200L        // a reel's text must hold still this long
        private const val MAX_STRINGS = 150
        private val WHITESPACE = Regex("\\s+")

        private const val EVENT_SCAN_DELAY_MS = 60L   // small batch window for event bursts
        private const val TICK_MS = 500L              // regular re-check while inside IG / YT
        private const val BACK_COOLDOWN_MS = 1200L    // avoid double-back that exits the app
        private const val PLAYER_EXIT_GRACE_MS = 900L // ignore brief flickers (comments sheet etc.)
        private const val SESSION_GAP_MS = 3000L      // away longer than this = new session
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

    // reel recognition (see trackReel)
    private var candidate: Set<String>? = null
    private var candidateSince = 0L
    private var lastReel: Set<String>? = null
    private val seenReels = ArrayList<Set<String>>()
    private val chromeFreq = HashMap<String, Int>() // how many different reels a string showed up in
    private val chrome = HashSet<String>()          // strings shared by reels = UI, not content
    private var sawScroll = false
    private var everScrolled = false

    // reel position from the pager's scroll events (best signal when the app provides it)
    private var pagerKey = 0
    private var maxIndex = -1
    private var indexMode = false

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
                // Read everything now: the event object is recycled after this call.
                val from = event.fromIndex
                val itemCount = event.itemCount
                val src = event.source
                val key = src?.hashCode() ?: 0
                val h = src?.let { n -> Rect().also { n.getBoundsInScreen(it) }.height() } ?: 0
                bg.post { onScroll(from, itemCount, key, h) }
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

        val ids = if (isYt) YT_SHORTS_IDS else IG_REELS_IDS
        val playerOnScreen = containsAnyId(root, ids, 0)
        // Comments / share sheets can open as their own window on top of the player.
        // Then we're still "in Reels", just not counting until the sheet closes.
        val playerUnderSheet = !playerOnScreen && inPlayer && try {
            windows.any { w ->
                val wr = w.root
                wr != null && wr.packageName?.toString() == pkg && containsAnyId(wr, ids, 0)
            }
        } catch (e: Exception) {
            false
        }
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
            trackReel(root, app, now)
            if (inPlayer) refreshPill(animateCount = false)
        } else if (playerUnderSheet) {
            lastSeenPlayer = now
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
        candidate = null
        lastReel = null
        seenReels.clear()
        sawScroll = false
        everScrolled = false
        pagerKey = 0
        maxIndex = -1
        indexMode = false
        lastTimeMark = now
        // The reel you just opened counts right away.
        onNewReel(app, now, animate = false)
        if (inPlayer && Prefs.get(this, Prefs.SHOW_COUNTER)) {
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
     * Recognises reels by what's written on them (username / channel, caption, audio name).
     * Once the text on screen has held still for a moment, compare it with the last reel:
     * if most of that reel's text is gone and new text appeared, it's a new reel.
     *  - opening comments / expanding a caption keeps the old text → not counted
     *  - scrolling back to a reel you've already seen → not counted again
     */
    private fun trackReel(root: AccessibilityNodeInfo, app: App, now: Long) {
        if (indexMode) return // the pager tells us positions; that's more precise
        val snap = collectStrings(root)
        if (snap.isEmpty()) return

        // Wait until the screen settles (mid-swipe shows two reels at once).
        val cand = candidate
        if (cand == null || jaccard(cand, snap) < 0.8f) {
            candidate = snap
            candidateSince = now
            return
        }
        candidate = snap
        if (now - candidateSince < STABLE_MS) return

        val base = lastReel
        if (base == null) { // first settled reel of this visit (already counted on entry)
            commitReel(snap)
            return
        }
        // If this app reports scrolling at all, only consider a new reel after a scroll.
        if (everScrolled && !sawScroll) return

        val cur = snap - chrome
        val prev = base - chrome
        if (prev.isEmpty()) { commitReel(snap); return }
        val gone = prev.count { it !in cur }.toFloat() / prev.size
        val appeared = cur.count { it !in prev }
        val goneCount = prev.count { it !in cur }
        val isNewReel = (goneCount >= 2 && appeared >= 2) || (gone >= 0.5f && appeared >= 1 && prev.size <= 3)
        if (isNewReel) {
            val seenBefore = seenReels.any { jaccard(it - chrome, cur) >= 0.7f }
            commitReel(snap)
            if (!seenBefore) onNewReel(app, now, animate = true)
        }
    }

    /**
     * Scroll events from the full-screen pager carry the position of the reel on screen.
     * Each step forward to a position not reached before = one more reel, so a fast fling
     * past 4 reels counts 4. Scrolling back and forward again doesn't recount.
     */
    private fun onScroll(from: Int, itemCount: Int, key: Int, height: Int) {
        if (!inPlayer) return
        sawScroll = true
        everScrolled = true
        if (from < 0 || itemCount <= 1 || key == 0) return
        if (pagerKey == 0) {
            // Lock onto the first big list that scrolls in the player: the reels pager.
            if (height < ScreenUtil.screenSize(this).y * 0.7f) return
            pagerKey = key
        }
        if (key != pagerKey) return // comments or another list
        indexMode = true
        when {
            maxIndex < 0 -> maxIndex = from
            from > maxIndex -> {
                val steps = from - maxIndex
                maxIndex = from
                val now = SystemClock.uptimeMillis()
                repeat(steps) { if (inPlayer) onNewReel(playerApp, now, animate = true) }
            }
        }
    }

    private fun commitReel(snap: Set<String>) {
        lastReel = snap
        seenReels += snap
        if (seenReels.size > 300) seenReels.removeAt(0)
        sawScroll = false
        for (t in snap) {
            val f = (chromeFreq[t] ?: 0) + 1
            chromeFreq[t] = f
            if (f >= 3) chrome += t
        }
        if (chromeFreq.size > 5000) chromeFreq.clear()
    }

    /** Normalised visible text on screen, minus UI labels and anything with numbers. */
    private fun collectStrings(root: AccessibilityNodeInfo): Set<String> {
        val out = HashSet<String>()
        fun add(cs: CharSequence?) {
            if (cs == null || out.size >= MAX_STRINGS) return
            val t = cs.toString().trim().replace(WHITESPACE, " ").lowercase()
            if (t.length < 2 || t.length > 160) return
            if (t.any { it.isDigit() }) return
            if (t in STOP_WORDS) return
            out += t
        }
        fun visit(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > MAX_DEPTH || out.size >= MAX_STRINGS) return
            if (n.isVisibleToUser) {
                add(n.text)
                add(n.contentDescription)
            }
            for (i in 0 until n.childCount) visit(n.getChild(i), depth + 1)
        }
        visit(root, 0)
        return out
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() && b.isEmpty()) return 1f
        val inter = a.count { it in b }
        return inter.toFloat() / (a.size + b.size - inter)
    }

    private fun onNewReel(app: App, now: Long, animate: Boolean) {
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
        refreshPill(animateCount = animate)
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
