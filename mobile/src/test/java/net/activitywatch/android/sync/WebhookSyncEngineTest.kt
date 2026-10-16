package net.activitywatch.android.sync

import net.activitywatch.android.privacy.PrivacyConfig
import net.activitywatch.android.privacy.PrivacyMatch
import net.activitywatch.android.privacy.PrivacyRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant

class WebhookSyncEngineTest {
    private val base = File(System.getProperty("java.io.tmpdir"), ".agents").apply { mkdirs() }
    private val directory = Files.createTempDirectory(base.toPath(), "aw-webhook-").toFile()
    private val time = 1_792_123_200_000L
    private val event = JSONObject().put("id", 1).put("timestamp", Instant.ofEpochMilli(time - 60_000).toString())
        .put("duration", 30.0).put("data", JSONObject().put("url", "example.com").put("browser", "mark.via.gp"))
    private val sent = mutableListOf<JSONObject>()
    private var failDelivery = false
    private var wrongReceipt = false
    private val ranges = mutableListOf<Pair<Long, Long>>()
    private val source = object : WebhookEventSource {
        override fun buckets() = JSONObject().put("aw-watcher-android-web", JSONObject().put("type", "web.tab.current").put("hostname", "K70"))
        override fun events(bucket: String, start: Long, end: Long): JSONArray {
            ranges.add(start to end)
            val timestamp = Instant.parse(event.getString("timestamp")).toEpochMilli()
            return JSONArray().also { if (timestamp >= start && timestamp < end) it.put(event) }
        }
    }
    private fun engine(at: Long = time, privacy: PrivacyConfig = PrivacyConfig()) =
        WebhookSyncEngine(directory, "phone-1", source, send = {
            sent.add(JSONObject(it.toString()))
            if (failDelivery) throw IOException("simulated lost response")
            WebhookAck(if (wrongReceipt) "other-batch" else it.getString("batch_id"), it.getJSONArray("events").length())
        }, now = { at }, transform = privacy::applyToEvent)

    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun `an unconfirmed delivery survives restart with the same batch identity`() {
        failDelivery = true
        assertThrows(IOException::class.java) { engine().sync() }
        assertTrue(File(directory, "pending.json").exists())
        val first = sent.single().toString()
        failDelivery = false
        assertEquals(1, engine().sync())
        assertEquals(first, sent[1].toString())
        assertFalse(File(directory, "pending.json").exists())
        assertEquals(0, engine().sync())
        assertEquals(2, sent.size)
    }

    @Test fun `a correction to a delivered event is sent as the same event identity`() {
        assertEquals(1, engine().sync())
        event.put("duration", 25.0)
        assertEquals(1, engine().sync())
        assertEquals(2, sent.size)
        assertEquals(1, sent.last().getJSONArray("events").getJSONObject(0).getInt("id"))
        assertNotEquals(sent[0].getString("batch_id"), sent[1].getString("batch_id"))
    }

    @Test fun `privacy rules apply before upload and a rule change resends the masked version`() {
        assertEquals(1, engine().sync())
        assertEquals("example.com", sent.last().getJSONArray("events").getJSONObject(0).getJSONObject("data").getString("url"))
        val rules = PrivacyConfig(listOf(PrivacyRule(PrivacyMatch.DOMAIN, "example.com", "娱乐")))
        assertEquals(1, engine(privacy = rules).sync())
        val masked = sent.last().getJSONArray("events").getJSONObject(0)
        assertEquals(1, masked.getInt("id"))
        assertEquals("娱乐", masked.getJSONObject("data").getString("url"))
        assertEquals(0, engine(privacy = rules).sync())
    }

    @Test fun `an unrelated HTTP success cannot advance the outbox`() {
        wrongReceipt = true
        assertThrows(IOException::class.java) { engine().sync() }
        assertTrue(File(directory, "pending.json").exists())
        assertFalse(File(directory, "state.json").exists())
    }

    @Test fun `an outage longer than the replay window does not skip the gap`() {
        engine().sync()
        ranges.clear()
        engine(time + 20 * DAY_MS).sync()
        assertEquals(time - REPLAY_MS, ranges.first().first)
        assertEquals(time + 20 * DAY_MS, ranges.last().second)
        assertTrue(ranges.all { it.second - it.first <= DAY_MS })
    }

    @Test fun `canonical fingerprints do not resend reordered object keys`() {
        val first = JSONObject("{\"b\":{\"d\":2,\"c\":1},\"a\":3}")
        val second = JSONObject("{\"a\":3,\"b\":{\"c\":1,\"d\":2}}")
        assertEquals(canonicalJson(first), canonicalJson(second))
        assertEquals(canonicalJson(JSONObject("{\"duration\":30}")),
            canonicalJson(JSONObject().put("duration", 30.0)))
    }

    @Test fun `webhook credentials require HTTPS without user info or fragments`() {
        assertEquals("server.ts.net", validateWebhookUrl("https://server.ts.net/activitywatch").host)
        for (url in listOf("http://server/activitywatch", "https://user:pass@server/", "https://server/#secret", "https:///")) {
            assertThrows(IllegalArgumentException::class.java) { validateWebhookUrl(url) }
        }
    }
}
