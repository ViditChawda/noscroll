package com.vidit.noscroll

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Watches Instagram + YouTube. If a Reels / Shorts player is on screen, presses Back.
 *
 * Detection is based on view resource IDs, which these apps change occasionally.
 * If blocking stops working after an app update, turn on DEBUG_DUMP, open Reels/Shorts,
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

        private const val CHECK_THROTTLE_MS = 250L   // don't scan the tree on every event
        private const val BACK_COOLDOWN_MS = 1200L   // avoid double-back that exits the app
        private const val MAX_DEPTH = 40
    }

    private var lastCheck = 0L
    private var lastBack = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != IG && pkg != YT) return

        val now = SystemClock.uptimeMillis()
        if (now - lastCheck < CHECK_THROTTLE_MS || now - lastBack < BACK_COOLDOWN_MS) return
        lastCheck = now

        val targets = when {
            pkg == YT && Prefs.get(this, Prefs.BLOCK_SHORTS) -> YT_SHORTS_IDS
            pkg == IG && Prefs.get(this, Prefs.BLOCK_REELS) -> IG_REELS_IDS
            else -> return
        }

        val root = rootInActiveWindow ?: return
        if (DEBUG_DUMP) dump(root, 0)

        if (containsAnyId(root, targets, 0)) {
            lastBack = now
            performGlobalAction(GLOBAL_ACTION_BACK)
            Prefs.increment(this)
            val label = if (pkg == YT) "Shorts" else "Reels"
            Toast.makeText(this, "$label blocked 🚫", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Blocked $label")
        }
    }

    private fun containsAnyId(node: AccessibilityNodeInfo?, ids: List<String>, depth: Int): Boolean {
        if (node == null || depth > MAX_DEPTH) return false
        val id = node.viewIdResourceName
        if (id != null && node.isVisibleToUser && ids.any { id.endsWith("/$it") }) return true
        for (i in 0 until node.childCount) {
            if (containsAnyId(node.getChild(i), ids, depth + 1)) return true
        }
        return false
    }

    private fun dump(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null || depth > MAX_DEPTH) return
        node.viewIdResourceName?.let { Log.d(TAG, "  ".repeat(depth) + it) }
        for (i in 0 until node.childCount) dump(node.getChild(i), depth + 1)
    }

    override fun onInterrupt() {}
}
