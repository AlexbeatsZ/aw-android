package net.activitywatch.android.dashboard

import android.content.Context
import android.content.Intent
import android.util.Log
import android.webkit.JavascriptInterface
import net.activitywatch.android.BuildConfig
import net.activitywatch.android.ConfigManager
import net.activitywatch.android.deviceHostname
import net.activitywatch.android.privacy.PrivacyConfig
import net.activitywatch.android.privacy.PrivacyStore
import net.activitywatch.android.sync.HttpFailure
import net.activitywatch.android.sync.WebhookPreferences
import net.activitywatch.android.sync.WebhookSyncWorker
import net.activitywatch.android.sync.httpJson
import net.activitywatch.android.watcher.UsageStatsWatcher
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "DashboardBridge"
private const val DAY_MS = 86_400_000L

/** A failure the page can act on: `code` is shown or retried by app.js. */
internal class BridgeError(val code: String, message: String) : Exception(message)

/**
 * The page's only way to reach the app (`window.AWNative` in assets/dashboard).
 *
 * Calls are asynchronous: [request] queues work on a background thread and the answer is
 * handed back through `AWBridge._resolve(id, ok, json)` via [deliver]. The page is a bundled
 * asset and the WebView refuses other navigation, so nothing else can call this.
 */
internal class DashboardBridge(
    context: Context,
    private val deliver: (String) -> Unit,
    private val openScreen: (String) -> Unit,
) {
    private val context = context.applicationContext
    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private val uiPrefs = this.context.getSharedPreferences("Dashboard", Context.MODE_PRIVATE)

    @JavascriptInterface
    fun request(id: String, method: String, arg: String) {
        // The page can still call in while its fragment is being destroyed.
        if (executor.isShutdown) return
        executor.execute {
            val reply = try {
                val result = handle(method, if (arg.isBlank()) null else arg)
                "true, " + JSONObject.quote(result)
            } catch (e: BridgeError) {
                "false, " + JSONObject.quote(JSONObject().put("code", e.code).put("message", e.message).toString())
            } catch (e: Exception) {
                Log.w(TAG, "Dashboard request '$method' failed", e)
                val message = e.message ?: e.javaClass.simpleName
                "false, " + JSONObject.quote(JSONObject().put("code", "error").put("message", message).toString())
            }
            deliver("window.AWBridge && AWBridge._resolve(${JSONObject.quote(id)}, $reply)")
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private fun handle(method: String, arg: String?): String = when (method) {
        "day" -> {
            val range = JSONObject(arg ?: "{}")
            val start = range.getLong("start")
            val end = range.getLong("end")
            require(end > start && end - start <= 2 * DAY_MS) { "Invalid day range" }
            dayPayload(start, end, fetchEvents(start, end), PrivacyStore(context).load(), launcherPackages()).toString()
        }
        "candidates" -> {
            val days = JSONObject(arg ?: "{}").optInt("days", 7).coerceIn(1, 31)
            val end = System.currentTimeMillis()
            val events = fetchEvents(end - days * DAY_MS, end)
            candidatesPayload(events[BucketRole.APPS].orEmpty(), events[BucketRole.WEB].orEmpty()).toString()
        }
        "privacy.get" -> PrivacyStore(context).load().toJson().toString()
        "privacy.set" -> {
            val config = PrivacyConfig.fromJson(JSONObject(arg ?: "{}"))
            PrivacyStore(context).save(config)
            // Re-send the replay window in its new form as soon as possible.
            if (WebhookPreferences(context).enabled) WebhookSyncWorker.schedule(context, immediate = true)
            config.toJson().toString()
        }
        "status" -> status().toString()
        "open" -> {
            val screen = JSONObject(arg ?: "{}").optString("screen")
            require(screen in setOf("webhook", "usage", "accessibility")) { "Unknown screen" }
            openScreen(screen)
            "true"
        }
        "webhook.syncNow" -> {
            if (!WebhookPreferences(context).enabled) throw BridgeError("disabled", "请先在配置中开启 Webhook 同步")
            WebhookSyncWorker.schedule(context, immediate = true)
            "true"
        }
        "ui.get" -> uiPrefs.getString("ui", null) ?: "null"
        "ui.set" -> {
            val ui = JSONObject(arg ?: "{}")
            uiPrefs.edit().putString("ui", ui.toString()).apply()
            "true"
        }
        else -> throw BridgeError("unknown_method", "Unknown method $method")
    }

    private fun status(): JSONObject {
        val webhook = WebhookPreferences(context)
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
        return JSONObject()
            .put("version", version?.let { "v$it" } ?: "")
            .put("device", deviceHostname(context))
            .put("webhook", JSONObject()
                .put("enabled", webhook.enabled)
                .put("status", webhook.status))
            .put("permissions", JSONObject()
                .put("usage", UsageStatsWatcher.isUsageAllowed(context))
                .put("accessibility", UsageStatsWatcher.isAccessibilityAllowed(context)))
    }

    /** Events from this phone's own buckets that overlap [start, end). */
    private fun fetchEvents(start: Long, end: Long): Map<BucketRole, List<JSONObject>> {
        val token = ConfigManager(context).readAuthConfig().apiKey
        val base = "http://127.0.0.1:${BuildConfig.SERVER_PORT}/api/0"
        fun get(url: String): String = try {
            httpJson(URL(url), token)
        } catch (e: HttpFailure) {
            throw BridgeError("http", "本地服务返回 HTTP ${e.status}")
        } catch (e: ConnectException) {
            throw BridgeError("server_unavailable", "Local server is not running yet")
        } catch (e: IOException) {
            throw BridgeError("server_unavailable", "Local server did not answer")
        }
        fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        val buckets = JSONObject(get("$base/buckets/"))
        val result = HashMap<BucketRole, MutableList<JSONObject>>()
        for (id in buckets.keys()) {
            val role = bucketRole(id, buckets.optJSONObject(id)?.optString("type").orEmpty()) ?: continue
            val url = "$base/buckets/${encode(id)}/events" +
                "?start=${encode(Instant.ofEpochMilli(start).toString())}" +
                "&end=${encode(Instant.ofEpochMilli(end).toString())}"
            val events = JSONArray(get(url))
            val list = result.getOrPut(role) { ArrayList() }
            for (i in 0 until events.length()) events.optJSONObject(i)?.let(list::add)
        }
        return result
    }

    /** Home-screen apps, so the page can leave launcher time out of screen time. */
    private fun launcherPackages(): List<String> = try {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
    } catch (e: Exception) {
        emptyList()
    }
}
