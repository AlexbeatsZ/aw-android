package net.activitywatch.android

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class AWApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Follow the OS dark/light setting from process start. The dashboard and
        // webhook screens are dark regardless (AppTheme.Dark); onboarding stays light.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        // Before any component can construct a RustInterface; see DatastoreStartup.
        DatastoreStartup.start(this)
    }
}
