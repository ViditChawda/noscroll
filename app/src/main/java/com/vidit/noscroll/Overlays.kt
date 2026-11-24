package com.vidit.noscroll

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
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
 */
class TabHider(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private val params = ScreenUtil.overlayParams(touchable = true).apply { gravity = Gravity.TOP or Gravity.START }
    private val lastBounds = Rect()
    private var lastColor = 0

    @SuppressLint("ClickableViewAccessibility")
    fun show(bounds: Rect, isYouTube: Boolean) {
        val night = ScreenUtil.isNight(ctx)
        val color = when {
            !night -> Color.WHITE
            isYouTube -> Color.parseColor("#0F0F0F")
            else -> Color.BLACK
        }
        val v = view
        if (v != null && bounds == lastBounds && color == lastColor) return

        params.x = bounds.left
        params.y = bounds.top
        params.width = bounds.width()
        params.height = bounds.height()
        lastBounds.set(bounds)
        lastColor = color

        try {
            if (v == null) {
                view = View(ctx).apply {
                    setBackgroundColor(color)
                    setOnTouchListener { _, _ -> true }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                wm.addView(view, params)
            } else {
                v.setBackgroundColor(color)
                wm.updateViewLayout(v, params)
            }
        } catch (e: Exception) {
            view = null
        }
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        lastBounds.setEmpty()
    }
}

/**
 * A floating pill at the top of the screen: "● 12 reels".
 * Slides in with a bounce, the number rolls up on each new reel, the pill pulses,
 * and the accent colour shifts green → amber → red the more you scroll.
 */
class ScrollCounterOverlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private fun dp(v: Float) = ScreenUtil.dp(ctx, v)

    private var root: FrameLayout? = null
    private lateinit var pill: LinearLayout
    private lateinit var pillBg: GradientDrawable
    private lateinit var dot: View
    private lateinit var dotBg: GradientDrawable
    private lateinit var halo: View
    private lateinit var haloBg: GradientDrawable
    private lateinit var numA: TextView
    private lateinit var numB: TextView
    private lateinit var label: TextView
    private var front: TextView? = null

    private var count = 0
    private var accent = GREEN
    private var colorAnim: ValueAnimator? = null
    private var hiding = false

    companion object {
        private val GREEN = Color.parseColor("#34D399")
        private val AMBER = Color.parseColor("#FBBF24")
        private val RED = Color.parseColor("#FF3D7F")
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
        setAccent(colorFor(initial), animate = false)

        pill.animate().cancel()
        pill.translationY = -dp(90f)
        pill.alpha = 0f
        pill.scaleX = 0.7f
        pill.scaleY = 0.7f
        pill.animate()
            .translationY(0f).alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(520)
            .setInterpolator(OvershootInterpolator(1.6f))
            .setListener(null)
            .start()
        r.visibility = View.VISIBLE
    }

    fun update(newCount: Int) {
        if (root == null || hiding || newCount == count) return
        count = newCount
        rollTo(newCount.toString())
        pulse()
        val c = colorFor(newCount)
        if (c != accent) setAccent(c, animate = true)
    }

    fun hide() {
        val r = root ?: return
        if (hiding) return
        hiding = true
        pill.animate().cancel()
        pill.animate()
            .translationY(-dp(90f)).alpha(0f).scaleX(0.8f).scaleY(0.8f)
            .setDuration(260)
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
        colorAnim?.cancel()
    }

    // ---- animations ----

    private fun rollTo(text: String) {
        val out = front ?: return
        val incoming = if (out === numA) numB else numA
        val h = dp(26f)

        out.animate().cancel()
        incoming.animate().cancel()

        incoming.text = text
        incoming.translationY = h
        incoming.alpha = 0f
        incoming.visibility = View.VISIBLE

        out.animate().translationY(-h).alpha(0f).setDuration(260)
            .setInterpolator(AccelerateInterpolator()).start()
        incoming.animate().translationY(0f).alpha(1f).setDuration(380)
            .setInterpolator(OvershootInterpolator(2f)).start()
        front = incoming
    }

    private fun pulse() {
        val sx = ObjectAnimator.ofFloat(pill, View.SCALE_X, 1f, 1.14f, 1f)
        val sy = ObjectAnimator.ofFloat(pill, View.SCALE_Y, 1f, 1.14f, 1f)
        val hs = ObjectAnimator.ofFloat(halo, View.SCALE_X, 1f, 2.8f)
        val hsy = ObjectAnimator.ofFloat(halo, View.SCALE_Y, 1f, 2.8f)
        val ha = ObjectAnimator.ofFloat(halo, View.ALPHA, 0.7f, 0f)
        AnimatorSet().apply {
            playTogether(sx, sy)
            duration = 340
            interpolator = DecelerateInterpolator()
            start()
        }
        AnimatorSet().apply {
            playTogether(hs, hsy, ha)
            duration = 650
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun setAccent(target: Int, animate: Boolean) {
        colorAnim?.cancel()
        if (!animate) {
            applyAccent(target)
            return
        }
        colorAnim = ValueAnimator.ofObject(ArgbEvaluator(), accent, target).apply {
            duration = 500
            addUpdateListener { applyAccent(it.animatedValue as Int) }
            start()
        }
    }

    private fun applyAccent(c: Int) {
        accent = c
        dotBg.setColor(c)
        haloBg.setColor(c)
        pillBg.setStroke(dp(1.5f).toInt(), (c and 0x00FFFFFF) or (0xAA shl 24))
    }

    // ---- view building ----

    private fun build() {
        val pad = dp(14f).toInt()

        pillBg = GradientDrawable().apply {
            cornerRadius = dp(100f)
            setColor(Color.parseColor("#E6121018"))
        }
        pill = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBg
            setPadding(pad, dp(8f).toInt(), dp(18f).toInt(), dp(8f).toInt())
            elevation = dp(8f)
            clipChildren = false
            clipToPadding = false
        }

        // Dot with an expanding halo that "pings" on every new reel.
        val dotSize = dp(10f).toInt()
        val dotBox = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
        haloBg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        halo = View(ctx).apply { background = haloBg; alpha = 0f }
        dotBg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        dot = View(ctx).apply { background = dotBg }
        dotBox.addView(halo, FrameLayout.LayoutParams(dotSize, dotSize, Gravity.CENTER))
        dotBox.addView(dot, FrameLayout.LayoutParams(dotSize, dotSize, Gravity.CENTER))
        pill.addView(dotBox, LinearLayout.LayoutParams(dp(14f).toInt(), dp(14f).toInt()).apply {
            marginEnd = dp(10f).toInt()
        })

        // Two stacked number views that swap places for the rolling effect.
        val numBox = FrameLayout(ctx).apply { clipChildren = true; clipToPadding = true }
        fun numView() = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            includeFontPadding = false
            gravity = Gravity.CENTER
        }
        numA = numView()
        numB = numView().apply { alpha = 0f }
        numBox.addView(numA, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        numBox.addView(numB, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        numBox.minimumWidth = dp(26f).toInt()
        front = numA
        pill.addView(numBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26f).toInt()))

        label = TextView(ctx).apply {
            setTextColor(Color.parseColor("#B3FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        pill.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(6f).toInt()
        })

        // Root has room around the pill so the pulse/overshoot isn't clipped.
        val r = FrameLayout(ctx).apply {
            clipChildren = false
            clipToPadding = false
            setPadding(dp(16f).toInt(), dp(6f).toInt(), dp(16f).toInt(), dp(12f).toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        r.addView(pill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        val params = ScreenUtil.overlayParams(touchable = false).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = ScreenUtil.statusBarHeight(ctx) + dp(4f).toInt()
        }
        try {
            wm.addView(r, params)
            root = r
        } catch (e: Exception) {
            root = null
        }
    }
}
