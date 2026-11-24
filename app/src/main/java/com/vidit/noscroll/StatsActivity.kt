package com.vidit.noscroll

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Weekly bar charts of reels / shorts scrolled, per app, with week-over-week comparison. */
class StatsActivity : AppCompatActivity() {

    private class AppCard(val app: App, val root: View) {
        val title: TextView = root.findViewById(R.id.appTitle)
        val total: TextView = root.findViewById(R.id.appTotal)
        val sub: TextView = root.findViewById(R.id.appSub)
        val chart: BarChartView = root.findViewById(R.id.chart)
        val caption: TextView = root.findViewById(R.id.caption)
    }

    private val good by lazy { Color.parseColor("#34D399") }
    private val bad by lazy { ContextCompat.getColor(this, R.color.brand_pink) }
    private val muted by lazy { ContextCompat.getColor(this, R.color.ns_text_2) }

    private var weekOffset = 0 // 0 = this week, -1 = last week, ...
    private lateinit var cards: List<AppCard>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)

        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<View>(R.id.prevWeek).setOnClickListener { weekOffset--; render(animate = true) }
        findViewById<View>(R.id.nextWeek).setOnClickListener {
            if (weekOffset < 0) { weekOffset++; render(animate = true) }
        }

        val container = findViewById<ViewGroup>(R.id.appCards)
        cards = listOf(
            makeCard(container, App.IG, "Instagram Reels", R.color.series_reels),
            makeCard(container, App.YT, "YouTube Shorts", R.color.series_shorts),
        )

        render(animate = true)
        playEntrance()
    }

    private fun makeCard(parent: ViewGroup, app: App, title: String, colorRes: Int): AppCard {
        val v = LayoutInflater.from(this).inflate(R.layout.item_app_week, parent, false)
        parent.addView(v)
        val color = ContextCompat.getColor(this, colorRes)
        v.findViewById<View>(R.id.dot).background.mutate().setTint(color)
        return AppCard(app, v).apply {
            this.title.text = title
            chart.setColor(color)
        }
    }

    private fun weekStart(offset: Int): LocalDate =
        LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(offset.toLong())

    private fun render(animate: Boolean) {
        val today = LocalDate.now()
        val start = weekStart(weekOffset)
        val prevStart = weekStart(weekOffset - 1)
        val days = (0 until 7).map { start.plusDays(it.toLong()) }
        val todayIndex = days.indexOf(today)
        // For the current week, compare against the same days of last week (Mon..today).
        val compareDays = if (todayIndex >= 0) todayIndex + 1 else 7

        // Header
        findViewById<TextView>(R.id.weekTitle).text = when (weekOffset) {
            0 -> "This week"
            -1 -> "Last week"
            else -> "${-weekOffset} weeks ago"
        }
        val fmt = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
        findViewById<TextView>(R.id.weekRange).text = "${start.format(fmt)} – ${start.plusDays(6).format(fmt)}"
        findViewById<View>(R.id.nextWeek).alpha = if (weekOffset < 0) 1f else 0.3f

        // Data
        val scrolled = cards.associate { c -> c.app to IntArray(7) { Prefs.scrolledOn(this, c.app, days[it]) } }
        val blocked = cards.associate { c -> c.app to IntArray(7) { Prefs.blockedOn(this, c.app, days[it]) } }
        fun prevSum(app: App) = (0 until compareDays).sumOf { Prefs.scrolledOn(this, app, prevStart.plusDays(it.toLong())) }

        // Summary
        val total = scrolled.values.sumOf { it.sum() }
        val prevTotal = cards.sumOf { prevSum(it.app) }
        countUp(findViewById(R.id.weekTotal), total, animate)
        findViewById<TextView>(R.id.weekCompare).apply {
            val (txt, col) = compare(total, prevTotal)
            text = txt
            setTextColor(col)
        }
        findViewById<TextView>(R.id.weekBlocked).text =
            "🚫  Blocked ${blocked.values.sumOf { it.sum() }} times this week"

        // Shared scale so both charts compare directly.
        val scaleMax = scrolled.values.maxOf { it.maxOrNull() ?: 0 }
        val labels = Array(7) { days[it].dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }

        for (c in cards) {
            val vals = scrolled.getValue(c.app)
            val sum = vals.sum()
            val activeDays = if (todayIndex >= 0) todayIndex + 1 else 7
            val avg = (sum.toFloat() / activeDays).roundToInt()
            val (cmp, _) = compare(sum, prevSum(c.app))
            c.total.text = sum.toString()
            c.sub.text = "avg $avg/day  ·  $cmp"
            c.chart.setData(vals, labels, scaleMax, todayIndex, animate)
            c.caption.text = ""
            val noun = if (c.app == App.YT) "shorts" else "reels"
            c.chart.onSelect = { i ->
                c.caption.text = if (i < 0) "" else {
                    val d = days[i]
                    val name = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
                    "$name, ${d.format(fmt)}  ·  ${vals[i]} $noun scrolled  ·  ${blocked.getValue(c.app)[i]} blocked"
                }
            }
        }
    }

    /** "↓ 23% vs last week" (green), "↑ 10% …" (red), or "Same as last week". */
    private fun compare(now: Int, before: Int): Pair<String, Int> = when {
        now == before -> "Same as last week" to muted
        before == 0 -> "↑ $now more than last week" to bad
        else -> {
            val pct = (abs(now - before) * 100f / before).roundToInt()
            if (now < before) "↓ $pct% vs last week" to good else "↑ $pct% vs last week" to bad
        }
    }

    private fun countUp(view: TextView, target: Int, animate: Boolean) {
        if (!animate || target <= 0) {
            view.text = target.toString()
            return
        }
        android.animation.ValueAnimator.ofInt(0, target).apply {
            duration = 600
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { view.text = (it.animatedValue as Int).toString() }
            start()
        }
    }

    private fun playEntrance() {
        val content = findViewById<ViewGroup>(R.id.content)
        val shift = 32f * resources.displayMetrics.density
        var i = 0
        fun animateIn(v: View) {
            v.alpha = 0f
            v.translationY = shift
            v.animate().alpha(1f).translationY(0f).setStartDelay(60L * i++).setDuration(420)
                .setInterpolator(OvershootInterpolator(0.8f)).start()
        }
        for (k in 0 until content.childCount) {
            val child = content.getChildAt(k)
            if (child.id == R.id.appCards) {
                val g = child as ViewGroup
                for (j in 0 until g.childCount) animateIn(g.getChildAt(j))
            } else {
                animateIn(child)
            }
        }
    }
}
