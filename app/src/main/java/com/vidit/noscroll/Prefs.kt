package com.vidit.noscroll

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate

/** Which app a stat belongs to. */
enum class App(val key: String) { IG("ig"), YT("yt") }

object Prefs {
    private const val FILE = "noscroll"
    private const val STATS_FILE = "noscroll_stats"

    const val BLOCK_REELS = "block_reels"
    const val BLOCK_SHORTS = "block_shorts"
    const val HIDE_TABS = "hide_tabs"
    const val SHOW_COUNTER = "show_counter"
    const val TIME_LIMIT_MIN = "time_limit_min"   // 0 = off
    const val COUNT_LIMIT = "count_limit"         // 0 = off

    private const val BLOCKED_COUNT = "blocked_count"
    private const val SCROLLED_COUNT = "scrolled_count"

    private fun sp(ctx: Context): SharedPreferences = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun stats(ctx: Context): SharedPreferences = ctx.getSharedPreferences(STATS_FILE, Context.MODE_PRIVATE)

    fun get(ctx: Context, key: String) = sp(ctx).getBoolean(key, true)
    fun set(ctx: Context, key: String, value: Boolean) = sp(ctx).edit().putBoolean(key, value).apply()

    fun blockEnabled(ctx: Context, app: App) = get(ctx, if (app == App.YT) BLOCK_SHORTS else BLOCK_REELS)

    fun getInt(ctx: Context, key: String) = sp(ctx).getInt(key, 0)
    fun setInt(ctx: Context, key: String, value: Int) = sp(ctx).edit().putInt(key, value).apply()

    // ---- totals ----
    fun blockedTotal(ctx: Context) = sp(ctx).getInt(BLOCKED_COUNT, 0)
    fun scrolledTotal(ctx: Context) = sp(ctx).getInt(SCROLLED_COUNT, 0)

    // ---- per-day, per-app (keys like "s_ig_2026-11-24") ----
    private fun dayKey(kind: String, app: App, day: LocalDate) = "${kind}_${app.key}_$day"

    fun scrolledOn(ctx: Context, app: App, day: LocalDate) = stats(ctx).getInt(dayKey("s", app, day), 0)
    fun blockedOn(ctx: Context, app: App, day: LocalDate) = stats(ctx).getInt(dayKey("b", app, day), 0)

    fun scrolledToday(ctx: Context, app: App) = scrolledOn(ctx, app, LocalDate.now())
    fun scrolledToday(ctx: Context) = App.values().sumOf { scrolledToday(ctx, it) }
    fun blockedToday(ctx: Context) = App.values().sumOf { blockedOn(ctx, it, LocalDate.now()) }

    fun addBlocked(ctx: Context, app: App) {
        bump(ctx, BLOCKED_COUNT, dayKey("b", app, LocalDate.now()), 1)
    }

    /** Returns today's new scrolled count for this app. */
    fun addScrolled(ctx: Context, app: App, n: Int): Int =
        bump(ctx, SCROLLED_COUNT, dayKey("s", app, LocalDate.now()), n)

    // ---- time spent in the Reels/Shorts player (ms) ----
    fun timeOn(ctx: Context, app: App, day: LocalDate) = stats(ctx).getLong(dayKey("t", app, day), 0L)
    fun timeToday(ctx: Context) = App.values().sumOf { timeOn(ctx, it, LocalDate.now()) }
    fun addTime(ctx: Context, app: App, ms: Long) {
        val st = stats(ctx)
        val k = dayKey("t", app, LocalDate.now())
        st.edit().putLong(k, st.getLong(k, 0L) + ms).apply()
    }

    private fun bump(ctx: Context, totalKey: String, dayKey: String, n: Int): Int {
        val s = sp(ctx)
        s.edit().putInt(totalKey, s.getInt(totalKey, 0) + n).apply()
        val st = stats(ctx)
        val v = st.getInt(dayKey, 0) + n
        st.edit().putInt(dayKey, v).apply()
        return v
    }
}
