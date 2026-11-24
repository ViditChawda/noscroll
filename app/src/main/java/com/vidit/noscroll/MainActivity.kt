package com.vidit.noscroll

import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var hero: View
    private lateinit var heroIcon: ImageView
    private lateinit var status: TextView
    private lateinit var statusSub: TextView
    private lateinit var enableBtn: MaterialButton
    private var firstResume = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hero = findViewById(R.id.hero)
        heroIcon = findViewById(R.id.heroIcon)
        status = findViewById(R.id.status)
        statusSub = findViewById(R.id.statusSub)
        enableBtn = findViewById(R.id.enableBtn)

        bindRow(R.id.reelsRow, R.id.reelsSwitch, Prefs.BLOCK_REELS)
        bindRow(R.id.shortsRow, R.id.shortsSwitch, Prefs.BLOCK_SHORTS)
        bindRow(R.id.tabsRow, R.id.tabsSwitch, Prefs.HIDE_TABS)
        bindRow(R.id.counterRow, R.id.counterSwitch, Prefs.SHOW_COUNTER)

        // Play policy wants a prominent disclosure before sending users to enable the service.
        enableBtn.setOnClickListener {
            if (isServiceEnabled()) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("Accessibility permission")
                .setMessage(R.string.disclosure)
                .setPositiveButton("Agree & open settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        playEntrance()
    }

    override fun onResume() {
        super.onResume()
        renderStatus(isServiceEnabled())
        countUp(findViewById(R.id.blockedToday), Prefs.blockedToday(this))
        countUp(findViewById(R.id.scrolledToday), Prefs.scrolledToday(this))
        findViewById<TextView>(R.id.blockedTotal).text = "All time: ${Prefs.blockedTotal(this)}"
        findViewById<TextView>(R.id.scrolledTotal).text = "All time: ${Prefs.scrolledTotal(this)}"
        if (!firstResume) {
            hero.animate().scaleX(1.02f).scaleY(1.02f).setDuration(120).withEndAction {
                hero.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            }.start()
        }
        firstResume = false
    }

    private fun renderStatus(on: Boolean) {
        if (on) {
            hero.setBackgroundResource(R.drawable.bg_hero_on)
            heroIcon.setImageResource(R.drawable.ic_shield_on)
            heroIcon.imageTintList = ColorStateList.valueOf(Color.WHITE)
            heroIcon.setBackgroundResource(R.drawable.bg_hero_badge)
            status.text = "Protection is on"
            status.setTextColor(Color.WHITE)
            statusSub.text = "Reels & Shorts don't stand a chance."
            statusSub.setTextColor(Color.parseColor("#E6FFFFFF"))
            enableBtn.text = "Accessibility settings"
            enableBtn.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
            enableBtn.setTextColor(ContextCompat.getColor(this, R.color.brand_pink))
        } else {
            val warn = ContextCompat.getColor(this, R.color.ns_warn)
            hero.setBackgroundResource(R.drawable.bg_hero_off)
            heroIcon.setImageResource(R.drawable.ic_shield_off)
            heroIcon.imageTintList = ColorStateList.valueOf(warn)
            heroIcon.setBackgroundResource(R.drawable.bg_icon_chip)
            status.text = "Protection is off"
            status.setTextColor(ContextCompat.getColor(this, R.color.ns_text))
            statusSub.text = "Turn on the blocker to get started."
            statusSub.setTextColor(ContextCompat.getColor(this, R.color.ns_text_2))
            enableBtn.text = "Turn on blocker"
            enableBtn.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.brand_pink))
            enableBtn.setTextColor(Color.WHITE)
        }
    }

    private fun bindRow(rowId: Int, switchId: Int, key: String) {
        val sw = findViewById<MaterialSwitch>(switchId)
        sw.isChecked = Prefs.get(this, key)
        sw.setOnCheckedChangeListener { _, checked -> Prefs.set(this, key, checked) }
        findViewById<View>(rowId).setOnClickListener { sw.toggle() }
    }

    /** Numbers tick up from 0 when you open the app. */
    private fun countUp(view: TextView, target: Int) {
        if (target <= 0) {
            view.text = "0"
            return
        }
        ValueAnimator.ofInt(0, target).apply {
            duration = (500L + target * 8L).coerceAtMost(1200L)
            interpolator = DecelerateInterpolator()
            addUpdateListener { view.text = (it.animatedValue as Int).toString() }
            start()
        }
    }

    /** Cards float up one after another. */
    private fun playEntrance() {
        val content = findViewById<android.view.ViewGroup>(R.id.content)
        val shift = 40f * resources.displayMetrics.density
        for (i in 0 until content.childCount) {
            val child = content.getChildAt(i)
            child.alpha = 0f
            child.translationY = shift
            child.animate()
                .alpha(1f).translationY(0f)
                .setStartDelay(60L * i)
                .setDuration(450)
                .setInterpolator(OvershootInterpolator(0.8f))
                .start()
        }
    }

    private fun isServiceEnabled(): Boolean {
        val me = ComponentName(this, BlockerService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        return splitter.any { it.equals(me, ignoreCase = true) }
    }
}
