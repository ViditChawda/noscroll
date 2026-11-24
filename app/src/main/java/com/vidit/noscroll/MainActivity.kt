package com.vidit.noscroll

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var enableBtn: Button
    private lateinit var count: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        enableBtn = findViewById(R.id.enableBtn)
        count = findViewById(R.id.count)

        bindSwitch(R.id.reelsSwitch, Prefs.BLOCK_REELS)
        bindSwitch(R.id.shortsSwitch, Prefs.BLOCK_SHORTS)

        // Play policy wants a prominent disclosure before sending users to enable the service.
        enableBtn.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Accessibility permission")
                .setMessage(R.string.disclosure)
                .setPositiveButton("Agree & open settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        val on = isServiceEnabled()
        status.text = if (on) "✅ Blocker is ON" else "⚠️ Blocker is OFF"
        enableBtn.text = if (on) "Accessibility settings" else "Turn on blocker"
        count.text = "Blocked so far: ${Prefs.count(this)}"
    }

    private fun bindSwitch(id: Int, key: String) {
        findViewById<MaterialSwitch>(id).apply {
            isChecked = Prefs.get(this@MainActivity, key)
            setOnCheckedChangeListener { _, checked -> Prefs.set(this@MainActivity, key, checked) }
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
