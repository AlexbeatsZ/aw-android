package net.activitywatch.android.dashboard

import net.activitywatch.android.privacy.PrivacyConfig
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

internal const val DASHBOARD_URL = "file:///android_asset/dashboard/index.html"

/** True for the bundled page itself; every other navigation is refused. */
internal fun isDashboardUrl(url: String?): Boolean =
    url != null && url.substringBefore('#').substringBefore('?') == DASHBOARD_URL

/** Which part of the dashboard a local bucket feeds. */
internal enum class BucketRole { APPS, WEB, UNLOCKS }

/** Only this phone's own watchers; never buckets that aw-sync copied from other devices. */
internal fun bucketRole(id: String, type: String): BucketRole? {
    if (!id.startsWith("aw-watcher-android") || id.contains("-synced-from-")) return null
    return when (type) {
        "currentwindow" -> BucketRole.APPS
        "web.tab.current" -> BucketRole.WEB
        "os.lockscreen.unlocks" -> BucketRole.UNLOCKS
        else -> null
    }
}

internal fun eventStartMs(event: JSONObject): Long {
    val text = event.getString("timestamp")
    return try {
        Instant.parse(text).toEpochMilli()
    } catch (e: Exception) {
        OffsetDateTime.parse(text).toInstant().toEpochMilli()
    }
}

/**
 * The page's day payload: `{start, end, apps: [{t, d, app, pkg, private}],
 * web: [{t, d, url, title, browser, private}], unlocks: [t], launchers: [pkg]}`.
 * Privacy rules are applied here, so the WebView never receives a hidden name.
 */
internal fun dayPayload(
    start: Long,
    end: Long,
    events: Map<BucketRole, List<JSONObject>>,
    privacy: PrivacyConfig,
    launchers: Collection<String>,
): JSONObject {
    val apps = JSONArray()
    events[BucketRole.APPS].orEmpty().forEach { event ->
        val data = privacy.applyToData(event.optJSONObject("data") ?: JSONObject())
        apps.put(JSONObject()
            .put("t", eventStartMs(event))
            .put("d", event.optDouble("duration", 0.0))
            .put("app", data.optString("app"))
            .put("pkg", data.optString("package"))
            .put("private", data.optBoolean("private", false)))
    }
    val web = JSONArray()
    events[BucketRole.WEB].orEmpty().forEach { event ->
        val data = privacy.applyToData(event.optJSONObject("data") ?: JSONObject())
        web.put(JSONObject()
            .put("t", eventStartMs(event))
            .put("d", event.optDouble("duration", 0.0))
            .put("url", data.optString("url"))
            .put("title", data.optString("title"))
            .put("browser", data.optString("browser"))
            .put("private", data.optBoolean("private", false)))
    }
    val unlocks = JSONArray()
    events[BucketRole.UNLOCKS].orEmpty().forEach { unlocks.put(eventStartMs(it)) }
    return JSONObject()
        .put("start", start)
        .put("end", end)
        .put("apps", apps)
        .put("web", web)
        .put("unlocks", unlocks)
        .put("launchers", JSONArray(launchers.toList()))
}

/**
 * Unmasked totals per app and per website, for choosing what to hide on the privacy page:
 * `{apps: [{name, pkg, ms}], sites: [{host, ms}]}`, largest first.
 */
internal fun candidatesPayload(appEvents: List<JSONObject>, webEvents: List<JSONObject>, limit: Int = 60): JSONObject {
    data class Total(val name: String, var ms: Long)
    val apps = LinkedHashMap<String, Total>()
    appEvents.forEach { event ->
        val data = event.optJSONObject("data") ?: return@forEach
        val pkg = data.optString("package")
        val name = data.optString("app").ifEmpty { pkg }
        val key = pkg.ifEmpty { name }
        if (key.isEmpty()) return@forEach
        apps.getOrPut(key) { Total(name, 0) }.ms += (event.optDouble("duration", 0.0) * 1000).toLong()
    }
    val sites = LinkedHashMap<String, Long>()
    webEvents.forEach { event ->
        val host = PrivacyConfig.hostOf(event.optJSONObject("data")?.optString("url").orEmpty())
        if (host.isEmpty()) return@forEach
        sites[host] = (sites[host] ?: 0) + (event.optDouble("duration", 0.0) * 1000).toLong()
    }
    return JSONObject()
        .put("apps", JSONArray().also { array ->
            apps.entries.sortedByDescending { it.value.ms }.take(limit).forEach {
                array.put(JSONObject().put("name", it.value.name).put("pkg", it.key).put("ms", it.value.ms))
            }
        })
        .put("sites", JSONArray().also { array ->
            sites.entries.sortedByDescending { it.value }.take(limit).forEach {
                array.put(JSONObject().put("host", it.key).put("ms", it.value))
            }
        })
}
