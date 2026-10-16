package net.activitywatch.android.sync

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import net.activitywatch.android.R
import net.activitywatch.android.applySafeWindowInsets

class WebhookSettingsActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: WebhookPreferences
    private lateinit var status: TextView
    private val refresh = object : Runnable {
        override fun run() {
            status.text = prefs.status
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webhook_settings)
        applySafeWindowInsets(alwaysDark = true)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.webhook_sync)
        prefs = WebhookPreferences(this)
        val url = findViewById<EditText>(R.id.webhook_url).apply { setText(prefs.url) }
        val token = findViewById<EditText>(R.id.webhook_token).apply { setText(prefs.token) }
        val enabled = findViewById<SwitchCompat>(R.id.webhook_enabled).apply { isChecked = prefs.enabled }
        status = findViewById(R.id.webhook_status)
        findViewById<Button>(R.id.webhook_save).setOnClickListener {
            try {
                val target = url.text.toString().trim()
                val secret = token.text.toString().trim()
                if (enabled.isChecked) {
                    validateWebhookUrl(target)
                    require(secret.length >= 32 && !secret.contains('\n') && !secret.contains('\r')) {
                        getString(R.string.webhook_token_required)
                    }
                }
                prefs.url = target
                prefs.token = secret
                prefs.enabled = enabled.isChecked
                WebhookSyncWorker.schedule(this, immediate = true)
                Toast.makeText(this, R.string.webhook_saved, Toast.LENGTH_SHORT).show()
            } catch (e: IllegalArgumentException) {
                Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onStart() { super.onStart(); handler.post(refresh) }
    override fun onStop() { handler.removeCallbacks(refresh); super.onStop() }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
