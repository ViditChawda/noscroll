package com.vidit.noscroll

import android.content.Context

object Prefs {
    private const val FILE = "noscroll"
    const val BLOCK_REELS = "block_reels"
    const val BLOCK_SHORTS = "block_shorts"
    const val BLOCKED_COUNT = "blocked_count"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(ctx: Context, key: String) = sp(ctx).getBoolean(key, true)
    fun set(ctx: Context, key: String, value: Boolean) = sp(ctx).edit().putBoolean(key, value).apply()

    fun count(ctx: Context) = sp(ctx).getInt(BLOCKED_COUNT, 0)
    fun increment(ctx: Context) = sp(ctx).edit().putInt(BLOCKED_COUNT, count(ctx) + 1).apply()
}
