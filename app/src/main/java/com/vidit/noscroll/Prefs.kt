package com.vidit.noscroll

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate

object Prefs {
    private const val FILE = "noscroll"
    const val BLOCK_REELS = "block_reels"
    const val BLOCK_SHORTS = "block_shorts"
    const val HIDE_TABS = "hide_tabs"
    const val SHOW_COUNTER = "show_counter"

    private const val BLOCKED_COUNT = "blocked_count"
    private const val BLOCKED_TODAY = "blocked_today"
    private const val SCROLLED_COUNT = "scrolled_count"
    private const val SCROLLED_TODAY = "scrolled_today"
    private const val DAY = "day"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).also { rollDay(it) }

    /** Resets the "today" counters when the date changes. */
    private fun rollDay(sp: SharedPreferences) {
        val today = LocalDate.now().toString()
        if (sp.getString(DAY, null) != today) {
            sp.edit().putString(DAY, today).putInt(BLOCKED_TODAY, 0).putInt(SCROLLED_TODAY, 0).apply()
        }
    }

    fun get(ctx: Context, key: String) = sp(ctx).getBoolean(key, true)
    fun set(ctx: Context, key: String, value: Boolean) = sp(ctx).edit().putBoolean(key, value).apply()

    fun blockedTotal(ctx: Context) = sp(ctx).getInt(BLOCKED_COUNT, 0)
    fun blockedToday(ctx: Context) = sp(ctx).getInt(BLOCKED_TODAY, 0)
    fun scrolledTotal(ctx: Context) = sp(ctx).getInt(SCROLLED_COUNT, 0)
    fun scrolledToday(ctx: Context) = sp(ctx).getInt(SCROLLED_TODAY, 0)

    fun addBlocked(ctx: Context) = add(ctx, BLOCKED_COUNT, BLOCKED_TODAY, 1)
    fun addScrolled(ctx: Context, n: Int) = add(ctx, SCROLLED_COUNT, SCROLLED_TODAY, n)

    private fun add(ctx: Context, total: String, today: String, n: Int) {
        val s = sp(ctx)
        s.edit().putInt(total, s.getInt(total, 0) + n).putInt(today, s.getInt(today, 0) + n).apply()
    }
}
