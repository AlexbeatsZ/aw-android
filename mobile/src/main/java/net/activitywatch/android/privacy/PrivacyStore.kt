package net.activitywatch.android.privacy

import android.content.Context

/** Persists the privacy rules shared by the dashboard and the webhook export. */
class PrivacyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("Privacy", Context.MODE_PRIVATE)

    fun load(): PrivacyConfig = PrivacyConfig.fromJson(prefs.getString(KEY, null))

    /** Saves synchronously so a webhook run started right after sees the new rules. */
    fun save(config: PrivacyConfig) {
        check(prefs.edit().putString(KEY, config.toJson().toString()).commit()) { "Cannot save privacy rules" }
    }

    private companion object {
        const val KEY = "config"
    }
}
