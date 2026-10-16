package net.activitywatch.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import net.activitywatch.android.dashboard.DashboardFragment
import net.activitywatch.android.databinding.ActivityMainBinding
import net.activitywatch.android.watcher.UsageStatsWatcher

private const val TAG = "MainActivity"

val baseURL = "http://127.0.0.1:${BuildConfig.SERVER_PORT}"

/** One screen: the bundled dashboard. Settings screens are opened from inside it. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Log.i(TAG, "POST_NOTIFICATIONS denied; foreground service notification will be suppressed on Android 13+")
            }
        }

    private val dashboard: DashboardFragment?
        get() = supportFragmentManager.findFragmentById(R.id.fragment_container) as? DashboardFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If first time, or usage not allowed, show onboarding activity
        val prefs = AWPreferences(this)
        if (prefs.isFirstTime() || !UsageStatsWatcher.isUsageAllowed(this)) {
            Log.i(TAG, "First time or usage not allowed, starting onboarding activity")
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        // Request POST_NOTIFICATIONS once on Android 13+ so the foreground service
        // notification is visible without nagging users who decline the prompt.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED &&
            !prefs.hasRequestedNotificationPermission()
        ) {
            prefs.setNotificationPermissionRequested()
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySafeWindowInsets(alwaysDark = true)

        // Set up alarm to send heartbeats
        UsageStatsWatcher(this).setupAlarm()

        // The local server reads the API key at start; the dashboard uses it to query.
        ensureDashboardApiKey(this)
        val serviceIntent = Intent(this, BackgroundService::class.java).apply {
            putExtra(BackgroundService.EXTRA_START_ORIGIN, BackgroundService.START_ORIGIN_ACTIVITY)
        }
        startForegroundService(serviceIntent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val page = dashboard ?: return finish()
                page.handleBack { finish() }
            }
        })

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.fragment_container, DashboardFragment())
                .commit()
        }
    }

    override fun onResume() {
        super.onResume()

        // Ensures data is always fresh when app is opened,
        // even if it was up to an hour since the last logging-alarm was triggered.
        val usw = UsageStatsWatcher(this)
        lifecycleScope.launch {
            var banner: Snackbar? = null
            withDelayedIndicator(
                IMPORT_INDICATOR_DELAY_MS,
                show = {
                    banner =
                        Snackbar.make(
                                binding.coordinatorLayout,
                                R.string.importing_usage_history,
                                Snackbar.LENGTH_INDEFINITE,
                            )
                            .setTextColor(ContextCompat.getColor(this@MainActivity, R.color.chrome_on_dark))
                            .also { it.show() }
                },
                hide = { banner?.dismiss() },
            ) {
                usw.sendHeartbeatsSuspend()
            }
            dashboard?.refresh()
        }
    }
}
