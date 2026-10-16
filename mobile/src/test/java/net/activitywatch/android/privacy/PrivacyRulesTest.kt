package net.activitywatch.android.privacy

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PrivacyRulesTest {
    private fun app(name: String, pkg: String) =
        JSONObject().put("app", name).put("package", pkg).put("classname", "$pkg.MainActivity")

    private fun visit(url: String, title: String = "Some page") =
        JSONObject().put("url", url).put("title", title).put("browser", "mark.via.gp")
            .put("audible", false).put("incognito", false)

    private val config = PrivacyConfig(
        listOf(
            PrivacyRule(PrivacyMatch.APP, "com.miHoYo.Yuanshen", "娱乐"),
            PrivacyRule(PrivacyMatch.DOMAIN, "bilibili.com", "娱乐"),
            PrivacyRule(PrivacyMatch.KEYWORD, "bank", "金融"),
        )
    )

    @Test fun `an app rule replaces every identifying field`() {
        val out = config.applyToData(app("原神", "com.miHoYo.Yuanshen"))
        assertEquals("娱乐", out.getString("app"))
        assertEquals("", out.getString("package"))
        assertFalse(out.has("classname"))
        assertTrue(out.getBoolean("private"))
    }

    @Test fun `app rules match the app name case-insensitively`() {
        val byName = PrivacyConfig(listOf(PrivacyRule(PrivacyMatch.APP, "WeChat", "社交")))
        assertEquals("社交", byName.applyToData(app("wechat", "com.tencent.mm")).getString("app"))
        assertSame(null, byName.ruleFor(app("WeChat Work", "com.tencent.wework")))
    }

    @Test fun `a domain rule covers subdomains but not lookalike hosts`() {
        val out = config.applyToData(visit("m.bilibili.com/video/BV1xx?p=2", "【某视频】"))
        assertEquals("娱乐", out.getString("url"))
        assertEquals("娱乐", out.getString("title"))
        assertEquals("mark.via.gp", out.getString("browser"))
        assertTrue(out.getBoolean("private"))
        assertNull(config.ruleFor(visit("notbilibili.com/x")))
    }

    @Test fun `a keyword matches any text field`() {
        assertEquals("金融", config.applyToData(visit("example.com/x", "My Bank login")).getString("title"))
        assertEquals("金融", config.applyToData(app("招商银行", "cmb.pb.bank")).getString("app"))
    }

    @Test fun `the first matching rule wins`() {
        val ordered = PrivacyConfig(listOf(
            PrivacyRule(PrivacyMatch.KEYWORD, "video", "A"),
            PrivacyRule(PrivacyMatch.DOMAIN, "bilibili.com", "B"),
        ))
        assertEquals("A", ordered.applyToData(visit("bilibili.com/video/1")).getString("url"))
    }

    @Test fun `an app rule on the browser hides the visits made in it`() {
        val hideVia = PrivacyConfig(listOf(PrivacyRule(PrivacyMatch.APP, "mark.via.gp", "浏览")))
        assertEquals("浏览", hideVia.applyToData(visit("example.com/a")).getString("url"))
    }

    @Test fun `domain-only keeps the host and drops path and title`() {
        val strict = config.copy(domainOnly = true)
        val out = strict.applyToData(visit("https://www.Example.com:8443/private/path?q=secret#x", "Secret"))
        assertEquals("example.com", out.getString("url"))
        assertEquals("", out.getString("title"))
        assertFalse(out.has("private"))
    }

    @Test fun `untouched data is returned as is`() {
        val data = app("Settings", "com.android.settings")
        assertSame(data, config.applyToData(data))
        val unlock = JSONObject()
        assertSame(unlock, config.copy(domainOnly = true).applyToData(unlock))
    }

    @Test fun `applyToEvent keeps identity and timing`() {
        val event = JSONObject().put("id", 7).put("timestamp", "2026-10-16T01:00:00Z").put("duration", 12.5)
            .put("data", app("原神", "com.miHoYo.Yuanshen"))
        val out = config.applyToEvent(event)
        assertEquals(7, out.getInt("id"))
        assertEquals(12.5, out.getDouble("duration"), 0.0)
        assertEquals("娱乐", out.getJSONObject("data").getString("app"))
        // The source event is not modified.
        assertEquals("原神", event.getJSONObject("data").getString("app"))
    }

    @Test fun `round trip through json normalizes and drops malformed rules`() {
        val raw = JSONObject("""{"domainOnly":true,"rules":[
            {"match":"domain","pattern":" https://WWW.Zhihu.com/question/1 ","label":" 知识 "},
            {"match":"app","pattern":"","label":"x"},
            {"match":"bogus","pattern":"a","label":"b"},
            {"match":"keyword","pattern":"porn","label":""},
            "not an object"
        ]}""")
        val parsed = PrivacyConfig.fromJson(raw)
        assertTrue(parsed.domainOnly)
        assertEquals(listOf(PrivacyRule(PrivacyMatch.DOMAIN, "zhihu.com", "知识")), parsed.rules)
        assertEquals(parsed, PrivacyConfig.fromJson(parsed.toJson().toString()))
    }

    @Test fun `unreadable storage yields no rules`() {
        assertEquals(PrivacyConfig(), PrivacyConfig.fromJson("{not json"))
        assertEquals(PrivacyConfig(), PrivacyConfig.fromJson(null as String?))
    }

    @Test fun `hostOf handles scheme-less addresses and user info`() {
        assertEquals("example.com", PrivacyConfig.hostOf("example.com"))
        assertEquals("a.example.com", PrivacyConfig.hostOf("user:pw@a.example.com/x"))
        assertEquals("", PrivacyConfig.hostOf(""))
        assertEquals("example.com", PrivacyConfig.normalizeDomain("*.example.com"))
    }
}
