package net.activitywatch.android.dashboard

import net.activitywatch.android.privacy.PrivacyConfig
import net.activitywatch.android.privacy.PrivacyMatch
import net.activitywatch.android.privacy.PrivacyRule
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DashboardDataTest {
    private fun event(id: Int, timestamp: String, duration: Double, data: JSONObject) =
        JSONObject().put("id", id).put("timestamp", timestamp).put("duration", duration).put("data", data)

    @Test fun `only this phone's own watcher buckets feed the dashboard`() {
        assertEquals(BucketRole.APPS, bucketRole("aw-watcher-android", "currentwindow"))
        assertEquals(BucketRole.WEB, bucketRole("aw-watcher-android-web", "web.tab.current"))
        assertEquals(BucketRole.UNLOCKS, bucketRole("aw-watcher-android-unlock", "os.lockscreen.unlocks"))
        assertNull(bucketRole("aw-watcher-android-media", "media.playback"))
        assertNull(bucketRole("aw-watcher-window_ROG", "currentwindow"))
        assertNull(bucketRole("aw-watcher-android-synced-from-tablet", "currentwindow"))
    }

    @Test fun `timestamps parse with Z or an offset`() {
        assertEquals(0L, eventStartMs(JSONObject().put("timestamp", "1970-01-01T00:00:00Z")))
        assertEquals(0L, eventStartMs(JSONObject().put("timestamp", "1970-01-01T08:00:00+08:00")))
        assertEquals(1500L, eventStartMs(JSONObject().put("timestamp", "1970-01-01T00:00:01.500Z")))
    }

    @Test fun `the day payload is masked before it reaches the page`() {
        val privacy = PrivacyConfig(listOf(
            PrivacyRule(PrivacyMatch.APP, "com.miHoYo.Yuanshen", "娱乐"),
            PrivacyRule(PrivacyMatch.DOMAIN, "bilibili.com", "娱乐"),
        ))
        val payload = dayPayload(0, 86_400_000, mapOf(
            BucketRole.APPS to listOf(
                event(1, "1970-01-01T01:00:00Z", 60.0, JSONObject().put("app", "原神").put("package", "com.miHoYo.Yuanshen")),
                event(2, "1970-01-01T02:00:00Z", 30.0, JSONObject().put("app", "微信").put("package", "com.tencent.mm")),
            ),
            BucketRole.WEB to listOf(
                event(3, "1970-01-01T03:00:00Z", 12.0, JSONObject().put("url", "m.bilibili.com/video/1").put("title", "视频").put("browser", "mark.via.gp")),
            ),
            BucketRole.UNLOCKS to listOf(event(4, "1970-01-01T00:59:00Z", 0.0, JSONObject())),
        ), privacy, listOf("com.miui.home"))

        val apps = payload.getJSONArray("apps")
        assertEquals("娱乐", apps.getJSONObject(0).getString("app"))
        assertEquals("", apps.getJSONObject(0).getString("pkg"))
        assertTrue(apps.getJSONObject(0).getBoolean("private"))
        assertEquals("com.tencent.mm", apps.getJSONObject(1).getString("pkg"))
        assertEquals(3_600_000L, apps.getJSONObject(0).getLong("t"))
        val visit = payload.getJSONArray("web").getJSONObject(0)
        assertEquals("娱乐", visit.getString("url"))
        assertEquals("娱乐", visit.getString("title"))
        assertFalse(payload.toString().contains("bilibili"))
        assertFalse(payload.toString().contains("Yuanshen"))
        assertEquals(3_540_000L, payload.getJSONArray("unlocks").getLong(0))
        assertEquals("com.miui.home", payload.getJSONArray("launchers").getString(0))
    }

    @Test fun `candidates total raw apps and hosts, largest first`() {
        val apps = listOf(
            event(1, "1970-01-01T01:00:00Z", 60.0, JSONObject().put("app", "微信").put("package", "com.tencent.mm")),
            event(2, "1970-01-01T02:00:00Z", 120.0, JSONObject().put("app", "原神").put("package", "com.miHoYo.Yuanshen")),
            event(3, "1970-01-01T03:00:00Z", 90.0, JSONObject().put("app", "微信").put("package", "com.tencent.mm")),
        )
        val web = listOf(
            event(4, "1970-01-01T03:00:00Z", 10.0, JSONObject().put("url", "www.zhihu.com/question/1")),
            event(5, "1970-01-01T03:01:00Z", 5.0, JSONObject().put("url", "zhihu.com/people/x")),
        )
        val out = candidatesPayload(apps, web)
        val first = out.getJSONArray("apps").getJSONObject(0)
        assertEquals("com.tencent.mm", first.getString("pkg"))
        assertEquals(150_000L, first.getLong("ms"))
        val site = out.getJSONArray("sites").getJSONObject(0)
        assertEquals("zhihu.com", site.getString("host"))
        assertEquals(15_000L, site.getLong("ms"))
    }

    @Test fun `only the bundled page may load in the dashboard WebView`() {
        assertTrue(isDashboardUrl("file:///android_asset/dashboard/index.html"))
        assertTrue(isDashboardUrl("file:///android_asset/dashboard/index.html#stats"))
        assertFalse(isDashboardUrl("https://example.com/"))
        assertFalse(isDashboardUrl("file:///android_asset/dashboard/other.html"))
        assertFalse(isDashboardUrl(null))
    }
}
