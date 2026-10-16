package net.activitywatch.android.watcher

import android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.activitywatch.android.AWPreferences
import net.activitywatch.android.MainActivity
import net.activitywatch.android.RustInterface
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run on an isolated emulator with Via's URL field configured to show URL.
 * A host fixture serves /one and /two with distinct titles on port 18987. */
@RunWith(AndroidJUnit4::class)
class ViaBrowserAcceptanceTest {
    @Test fun capturesViaUrlsTitlesAndCompletedDurations() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String): String = automation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText().trim() }
        }
        val originalServices = shell("settings get secure enabled_accessibility_services")
        val originalEnabled = shell("settings get secure accessibility_enabled")
        val component = "${context.packageName}/net.activitywatch.android.watcher.WebWatcher"
        val services = originalServices.takeUnless { it == "null" || it.isBlank() }?.split(':').orEmpty()
        val combined = (services + component).distinct().joinToString(":")
        try {
            AWPreferences(context).setFirstTimeRunFlag()
            shell("appops set ${context.packageName} GET_USAGE_STATS allow")
            shell("settings put secure enabled_accessibility_services $combined")
            shell("settings put secure accessibility_enabled 1")
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            SystemClock.sleep(3000)
            fun open(path: String) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://10.0.2.2:18987/$path"))
                    .setPackage("mark.via.gp").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            open("one")
            SystemClock.sleep(4000)
            open("two")
            SystemClock.sleep(4000)
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val ri = RustInterface(context)
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var found = false
            while (SystemClock.elapsedRealtime() < deadline && !found) {
                val events = ri.getEventsJSON("aw-watcher-android-web", 100)
                found = (0 until events.length()).map { events.getJSONObject(it) }.any {
                    val data = it.getJSONObject("data")
                    data.optString("browser") == "mark.via.gp" &&
                        data.optString("url") == "10.0.2.2:18987/one" &&
                        data.optString("title") == "Via acceptance one" && it.getDouble("duration") > 0
                }
                if (!found) SystemClock.sleep(200)
            }
            assertTrue("Via visit URL, title and positive duration must reach the real native datastore", found)
        } finally {
            if (originalServices == "null") shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services $originalServices")
            if (originalEnabled == "null") shell("settings delete secure accessibility_enabled")
            else shell("settings put secure accessibility_enabled $originalEnabled")
        }
    }
}
