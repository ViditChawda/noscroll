package com.vidit.noscroll

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.min

/**
 * Seven-day bar chart: thin bars with rounded tops anchored to the baseline,
 * the count above each bar, day labels below. Tap a bar to select it.
 */
class BarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private var values = IntArray(7)
    private var labels = Array(7) { "" }
    private var scaleMax = 1
    /** Index of today in this week, or -1 if the week is in the past. Days after it haven't happened yet. */
    private var todayIndex = -1
    private var selected = -1
    private var grow = 1f
    private var anim: ValueAnimator? = null

    var onSelect: ((Int) -> Unit)? = null

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ns_outline)
        strokeWidth = dp(1f)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ns_text)
        textSize = sp(11f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val mutedPaint = Paint(valuePaint).apply { color = ContextCompat.getColor(context, R.color.ns_text_3) }
    private val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ns_text_2)
        textSize = sp(11f)
        textAlign = Paint.Align.CENTER
    }
    private val todayPaint = Paint(dayPaint).apply {
        color = ContextCompat.getColor(context, R.color.ns_text)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val path = Path()
    private val rect = RectF()

    fun setColor(color: Int) {
        barPaint.color = color
        invalidate()
    }

    fun setData(values: IntArray, labels: Array<String>, scaleMax: Int, todayIndex: Int, animate: Boolean) {
        this.values = values
        this.labels = labels
        this.scaleMax = max(1, scaleMax)
        this.todayIndex = todayIndex
        selected = -1
        anim?.cancel()
        if (animate) {
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 650
                interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener { grow = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            grow = 1f
            invalidate()
        }
    }

    fun select(i: Int) {
        selected = i
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, resolveSize(dp(170f).toInt(), heightMeasureSpec))
    }

    private fun slotWidth() = width / 7f
    private fun isFuture(i: Int) = todayIndex >= 0 && i > todayIndex

    override fun onDraw(canvas: Canvas) {
        val slot = slotWidth()
        val labelArea = dp(22f)
        val valueArea = dp(18f)
        val baseline = height - labelArea
        val chartTop = valueArea
        val barW = min(dp(26f), slot * 0.55f)
        val radius = dp(4f)

        canvas.drawLine(0f, baseline, width.toFloat(), baseline, gridPaint)

        for (i in 0 until 7) {
            val cx = slot * i + slot / 2
            val v = values[i]
            val future = isFuture(i)
            val dim = selected >= 0 && selected != i

            // bar
            if (v > 0 && !future) {
                val h = max(dp(3f), (baseline - chartTop) * v / scaleMax) * grow
                rect.set(cx - barW / 2, baseline - h, cx + barW / 2, baseline - dp(2f))
                val r = min(radius, rect.width() / 2)
                path.reset()
                path.addRoundRect(rect, floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW)
                barPaint.alpha = if (dim) 90 else 255
                canvas.drawPath(path, barPaint)
            }

            // count above the bar
            if (!future) {
                val h = if (v > 0) max(dp(3f), (baseline - chartTop) * v / scaleMax) * grow else 0f
                val p = if (v == 0 || dim) mutedPaint else valuePaint
                canvas.drawText(v.toString(), cx, baseline - h - dp(6f), p)
            }

            // day label
            canvas.drawText(labels[i], cx, height - dp(6f), if (i == todayIndex) todayPaint else dayPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) return true
        if (event.action == MotionEvent.ACTION_UP) {
            val i = (event.x / slotWidth()).toInt().coerceIn(0, 6)
            if (!isFuture(i)) {
                selected = if (selected == i) -1 else i
                invalidate()
                onSelect?.invoke(selected)
                performClick()
            }
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()
}
