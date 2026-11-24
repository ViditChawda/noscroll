package com.vidit.noscroll

import android.content.Context

/** What the counter pill shows. */
data class PillState(
    val session: Int,        // reels in this visit to the app
    val today: Int,          // reels today (Instagram + YouTube)
    val noun: String,        // "reels" / "shorts"
    val progress: Float,     // 0..1 towards the nearest daily limit, or -1 when no limit is set
    val timeLeftMs: Long,    // -1 when no time limit
    val countLimit: Int,     // 0 when no reels limit
)

/** Daily limits for Reels + Shorts together. Raising a limit immediately allows more scrolling. */
object Limits {
    val TIME_OPTIONS_MIN = intArrayOf(0, 5, 10, 15, 20, 30, 45, 60, 90, 120)
    val COUNT_OPTIONS = intArrayOf(0, 10, 20, 30, 50, 75, 100, 150, 200)

    fun timeLimitMin(ctx: Context) = Prefs.getInt(ctx, Prefs.TIME_LIMIT_MIN)
    fun countLimit(ctx: Context) = Prefs.getInt(ctx, Prefs.COUNT_LIMIT)

    data class Status(
        val usedMs: Long,
        val count: Int,
        val timeLimitMs: Long,
        val countLimit: Int,
        val timeReached: Boolean,
        val countReached: Boolean,
        val progress: Float,
        val timeLeftMs: Long,
    )

    /** [extraMs] = time tracked but not yet saved. */
    fun status(ctx: Context, extraMs: Long = 0L): Status {
        val used = Prefs.timeToday(ctx) + extraMs
        val count = Prefs.scrolledToday(ctx)
        val tl = timeLimitMin(ctx) * 60_000L
        val cl = countLimit(ctx)
        val tp = if (tl > 0) used.toFloat() / tl else -1f
        val cp = if (cl > 0) count.toFloat() / cl else -1f
        return Status(
            usedMs = used,
            count = count,
            timeLimitMs = tl,
            countLimit = cl,
            timeReached = tl > 0 && used >= tl,
            countReached = cl > 0 && count >= cl,
            progress = maxOf(tp, cp).coerceAtMost(1f),
            timeLeftMs = if (tl > 0) (tl - used).coerceAtLeast(0L) else -1L,
        )
    }

    fun label(min: Int) = when {
        min == 0 -> "Off"
        min < 60 -> "$min min"
        min % 60 == 0 -> "${min / 60} h"
        else -> "${min / 60} h ${min % 60} min"
    }

    fun formatLeft(ms: Long): String {
        val s = (ms + 999) / 1000
        return if (s >= 60) "${(s + 59) / 60}m left" else "${s}s left"
    }
}
