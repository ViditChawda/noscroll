package com.vidit.noscroll

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

object ScreenUtil {
    fun screenSize(ctx: Context): Point {
        val wm = ctx.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            Point().also {
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealSize(it)
            }
        }
    }

    fun dp(ctx: Context, v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    fun statusBarHeight(ctx: Context): Int {
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) ctx.resources.getDimensionPixelSize(id) else dp(ctx, 24f).toInt()
    }

    fun isNight(ctx: Context) =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** Base params for an accessibility overlay (no "draw over apps" permission needed). */
    fun overlayParams(touchable: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
    }
}

/**
 * Covers the Reels / Shorts tab button with a block painted in the nav bar's colour.
 * The block swallows taps, so the tab is effectively gone.
 *
 * On Android 11+ the colour is read from a screenshot of the actual bar, so it matches
 * whatever theme the app itself is using. Older versions fall back to the system theme.
 */
class TabHider(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private val params = ScreenUtil.overlayParams(touchable = true).apply { gravity = Gravity.TOP or Gravity.START }
    private val lastBounds = Rect()

    private var pendingBounds: Rect? = null
    private var sampling = false
    private val cachedColor = HashMap<Boolean, Int>() // isYouTube -> last sampled colour

    fun show(bounds: Rect, isYouTube: Boolean) {
        val v = view
        if (v != null) {
            if (bounds != lastBounds) place(v, bounds, update = true)
            return
        }
        pendingBounds = Rect(bounds)
        if (sampling) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            sampling = true
            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    service.mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(shot: AccessibilityService.ScreenshotResult) {
                            sampling = false
                            val color = sample(shot, bounds)
                            if (color != null) cachedColor[isYouTube] = color
                            addPending(color ?: fallback(isYouTube))
                        }

                        override fun onFailure(errorCode: Int) {
                            sampling = false
                            addPending(fallback(isYouTube))
                        }
                    },
                )
            } catch (e: Exception) {
                sampling = false
                addPending(fallback(isYouTube))
            }
        } else {
            addPending(fallback(isYouTube))
        }
    }

    fun hide() {
        pendingBounds = null
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        lastBounds.setEmpty()
    }

    private fun fallback(isYouTube: Boolean): Int = cachedColor[isYouTube] ?: when {
        !ScreenUtil.isNight(service) -> Color.WHITE
        isYouTube -> Color.parseColor("#0F0F0F")
        else -> Color.BLACK
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addPending(color: Int) {
        val b = pendingBounds ?: return // hidden while we were sampling
        pendingBounds = null
        if (view != null) return
        val v = View(service).apply {
            setBackgroundColor(color)
            setOnTouchListener { _, _ -> true }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        if (place(v, b, update = false)) view = v
    }

    private fun place(v: View, b: Rect, update: Boolean): Boolean {
        params.x = b.left
        params.y = b.top
        params.width = b.width()
        params.height = b.height()
        lastBounds.set(b)
        return try {
            if (update) wm.updateViewLayout(v, params) else wm.addView(v, params)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Most common colour along the top and bottom edges of the tab (the bar background). */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun sample(shot: AccessibilityService.ScreenshotResult, b: Rect): Int? {
        val buffer = shot.hardwareBuffer
        return try {
            val hw = Bitmap.wrapHardwareBuffer(buffer, shot.colorSpace) ?: return null
            val bmp = hw.copy(Bitmap.Config.ARGB_8888, false)
            hw.recycle()
            val xs = listOf(b.left + 3, b.centerX(), b.right - 4)
            val ys = listOf(b.top + 2, b.bottom - 3)
            val counts = HashMap<Int, Int>()
            for (x in xs) for (y in ys) {
                if (x in 0 until bmp.width && y in 0 until bmp.height) {
                    val c = bmp.getPixel(x, y) or 0xFF000000.toInt()
                    counts[c] = (counts[c] ?: 0) + 1
                }
            }
            bmp.recycle()
            counts.maxByOrNull { it.value }?.key
        } catch (e: Exception) {
            null
        } finally {
            buffer.close()
        }
    }
}

/**
 * A small "island" pill just under the status bar:  [∞  14 reels  ◔]
 * Kept compact (~28dp tall) so it barely covers the video.
 * Animations: grows in like an island, the number rolls up on every new reel,
 * the pill gives a soft pop, and the ring fills / shifts green → amber → red.
 */
class ScrollCounterOverlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private fun dp(v: Float) = ScreenUtil.dp(ctx, v)

    private var root: FrameLayout? = null
    private lateinit var pill: LinearLayout
    private lateinit var numA: TextView
    private lateinit var numB: TextView
    private lateinit var label: TextView
    private lateinit var ring: RingView
    private var front: TextView? = null

    private var count = 0
    private var hiding = false

    companion object {
        /** The ring is full at this many reels in one sitting. */
        private const val RING_FULL_AT = 30f
        private val GREEN = Color.parseColor("#34D399")
        private val AMBER = Color.parseColor("#FFB020")
        private val RED = Color.parseColor("#FF4F5E")
        private fun colorFor(n: Int) = when {
            n < 10 -> GREEN
            n < 25 -> AMBER
            else -> RED
        }
    }

    fun show(initial: Int, noun: String) {
        if (root == null) build()
        val r = root ?: return
        hiding = false
        count = initial
        label.text = noun
        front!!.text = initial.toString()
        ring.set(initial / RING_FULL_AT, colorFor(initial), animate = false)

        pill.animate().cancel()
        pill.pivotY = 0f
        pill.translationY = -dp(12f)
        pill.alpha = 0f
        pill.scaleX = 0.5f
        pill.scaleY = 0.7f
        pill.animate()
            .translationY(0f).alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(480)
            .setInterpolator(OvershootInterpolator(1.4f))
            .setListener(null)
            .start()
        r.visibility = View.VISIBLE
    }

    fun update(newCount: Int) {
        if (root == null || hiding || newCount == count) return
        count = newCount
        rollTo(newCount.toString())
        pop()
        ring.set(newCount / RING_FULL_AT, colorFor(newCount), animate = true)
    }

    fun hide() {
        val r = root ?: return
        if (hiding) return
        hiding = true
        pill.animate().cancel()
        pill.animate()
            .translationY(-dp(12f)).alpha(0f).scaleX(0.5f).scaleY(0.7f)
            .setDuration(220)
            .setInterpolator(AccelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (hiding) removeNow(r)
                }
            })
            .start()
    }

    fun hideNow() {
        root?.let { removeNow(it) }
    }

    private fun removeNow(r: View) {
        runCatching { wm.removeView(r) }
        if (root === r) root = null
        hiding = false
    }

    // ---- animations ----

    private fun rollTo(text: String) {
        val out = front ?: return
        val incoming = if (out === numA) numB else numA
        val h = dp(16f)

        out.animate().cancel()
        incoming.animate().cancel()

        incoming.text = text
        incoming.translationY = h
        incoming.alpha = 0f

        out.animate().translationY(-h).alpha(0f).setDuration(220)
            .setInterpolator(AccelerateInterpolator()).start()
        incoming.animate().translationY(0f).alpha(1f).setDuration(340)
            .setInterpolator(OvershootInterpolator(2f)).start()
        front = incoming
    }

    private fun pop() {
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(pill, View.SCALE_X, 1f, 1.08f, 1f),
                ObjectAnimator.ofFloat(pill, View.SCALE_Y, 1f, 1.08f, 1f),
            )
            duration = 300
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    // ---- view building ----

    private fun build() {
        pill = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(100f)
                setColor(Color.BLACK)
                setStroke(dp(1f).toInt(), Color.parseColor("#26FFFFFF"))
            }
            setPadding(dp(10f).toInt(), 0, dp(6f).toInt(), 0)
            elevation = dp(6f)
            minimumHeight = dp(28f).toInt()
        }

        val logo = android.widget.ImageView(ctx).apply { setImageResource(R.drawable.ic_infinity_mini) }
        pill.addView(logo, LinearLayout.LayoutParams(dp(16f).toInt(), dp(8f).toInt()).apply {
            marginEnd = dp(7f).toInt()
        })

        // Two stacked number views that swap places for the rolling effect.
        val numBox = FrameLayout(ctx).apply { clipChildren = true; clipToPadding = true }
        fun numView() = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            fontFeatureSettings = "tnum"
            includeFontPadding = false
            gravity = Gravity.CENTER
        }
        numA = numView()
        numB = numView().apply { alpha = 0f }
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        numBox.addView(numA, FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER))
        numBox.addView(numB, FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER))
        front = numA
        pill.addView(numBox, LinearLayout.LayoutParams(wrap, dp(18f).toInt()))

        label = TextView(ctx).apply {
            setTextColor(Color.parseColor("#99FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            includeFontPadding = false
        }
        pill.addView(label, LinearLayout.LayoutParams(wrap, wrap).apply {
            marginStart = dp(4f).toInt()
            marginEnd = dp(7f).toInt()
        })

        ring = RingView(ctx)
        pill.addView(ring, LinearLayout.LayoutParams(dp(16f).toInt(), dp(16f).toInt()))

        // A little room around the pill so the pop/overshoot isn't clipped.
        val r = FrameLayout(ctx).apply {
            clipChildren = false
            clipToPadding = false
            setPadding(dp(8f).toInt(), dp(4f).toInt(), dp(8f).toInt(), dp(8f).toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        r.addView(pill, FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER))

        val params = ScreenUtil.overlayParams(touchable = false).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = ScreenUtil.statusBarHeight(ctx)
        }
        try {
            wm.addView(r, params)
            root = r
        } catch (e: Exception) {
            root = null
        }
    }
}

/** Small progress ring that animates its fill and colour. */
private class RingView(ctx: Context) : View(ctx) {
    private val stroke = ScreenUtil.dp(ctx, 2.5f)
    private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = stroke
        color = Color.parseColor("#26FFFFFF")
    }
    private val arc = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    private val oval = android.graphics.RectF()
    private var progress = 0f
    private var anim: ValueAnimator? = null

    fun set(target: Float, color: Int, animate: Boolean) {
        val to = target.coerceIn(0f, 1f)
        anim?.cancel()
        if (!animate) {
            progress = to
            arc.color = color
            invalidate()
            return
        }
        val fromP = progress
        val fromC = arc.color
        val argb = ArgbEvaluator()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                progress = fromP + (to - fromP) * f
                arc.color = argb.evaluate(f, fromC, color) as Int
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val half = stroke / 2
        oval.set(half, half, width - half, height - half)
        canvas.drawOval(oval, track)
        canvas.drawArc(oval, -90f, 360f * progress.coerceAtLeast(0.04f), false, arc)
    }
}
