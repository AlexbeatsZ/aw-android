package net.activitywatch.android.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

internal const val DAY_MS = 86_400_000L
internal const val REPLAY_MS = 7 * DAY_MS

/** The receiver must acknowledge this exact batch after durable storage. */
internal data class WebhookAck(val batchId: String, val events: Int)
internal interface WebhookEventSource {
    fun buckets(): JSONObject
    fun events(bucket: String, start: Long, end: Long): JSONArray
}

internal fun validateWebhookUrl(raw: String): URI {
    val uri = try { URI(raw.trim()) } catch (_: Exception) {
        throw IllegalArgumentException("Enter a valid HTTPS webhook URL")
    }
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() &&
        uri.userInfo == null && uri.fragment == null) { "Use an HTTPS webhook URL without a fragment or user info" }
    return uri
}

// JSON objects can arrive with different key order after a native/library update.
internal fun canonicalJson(value: Any?): String = when (value) {
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
        JSONObject.quote(it) + ":" + canonicalJson(value.get(it))
    }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalJson(value.get(it)) }
    is String -> JSONObject.quote(value)
    is Number -> java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
    null, JSONObject.NULL -> "null"
    else -> value.toString()
}

internal fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/**
 * An on-disk outbox survives process death and a lost HTTP response. Fingerprints are
 * advanced only after the matching receipt; replay also sends edits to recent events.
 * The directory belongs to one destination (including its credential), never all targets.
 */
internal class WebhookSyncEngine(
    private val directory: File,
    private val deviceId: String,
    private val source: WebhookEventSource,
    private val send: (JSONObject) -> WebhookAck,
    private val now: () -> Long = System::currentTimeMillis,
    private val cancelled: () -> Boolean = { false },
    // Applied before fingerprinting, so changing the privacy rules re-sends the recent
    // replay window in its new form and the receiver keeps the latest version.
    private val transform: (JSONObject) -> JSONObject = { it },
) {
    private val stateFile = File(directory, "state.json")
    private val pendingFile = File(directory, "pending.json")
    private lateinit var state: JSONObject
    private lateinit var known: JSONObject

    fun sync(): Int {
        directory.mkdirs()
        state = if (stateFile.exists()) JSONObject(stateFile.readText()) else JSONObject()
        known = state.optJSONObject("events") ?: JSONObject().also { state.put("events", it) }
        var delivered = 0
        if (pendingFile.exists()) delivered += deliverPending(JSONObject(pendingFile.readText()))
        val until = now()
        val lastSuccess = state.optLong("completed_at", 0)
        // Initial collection backfills two weeks. After an outage, start from the last
        // acknowledged scan instead of dropping everything older than today's replay window.
        val since = if (lastSuccess > 0) minOf(lastSuccess, until) - REPLAY_MS else until - 14 * DAY_MS
        val buckets = source.buckets()
        for (bucketId in buckets.keys().asSequence().toList().sorted()) {
            checkCancelled()
            val bucket = buckets.getJSONObject(bucketId)
            // A phone only uploads its own locally collected watchers.
            if (!bucketId.startsWith("aw-watcher-android") || bucketId.contains("-synced-from-")) continue
            var start = since.coerceAtLeast(0)
            while (start < until) {
                checkCancelled()
                val end = minOf(start + DAY_MS, until)
                val events = source.events(bucketId, start, end)
                var batch = JSONArray()
                var bytes = 0
                fun flush() {
                    if (batch.length() == 0) return
                    val payload = JSONObject()
                        .put("schema_version", 1).put("device_id", deviceId)
                        .put("batch_id", UUID.randomUUID().toString())
                        .put("sent_at", Instant.ofEpochMilli(now()).toString())
                        .put("bucket", JSONObject().put("id", bucketId)
                            .put("type", bucket.optString("type"))
                            .put("hostname", bucket.optString("hostname"))
                            .put("created", bucket.optString("created")))
                        .put("events", batch)
                    atomicWrite(pendingFile, payload.toString())
                    delivered += deliverPending(payload)
                    batch = JSONArray()
                    bytes = 0
                }
                for (i in 0 until events.length()) {
                    val event = transform(events.getJSONObject(i))
                    require(event.has("id") && event.has("timestamp") && event.has("duration") && event.has("data")) {
                        "Local server returned an incomplete event"
                    }
                    // Range queries may include boundary-overlapping events. Dedup by id,
                    // not by timestamp, so distinct visits at the same instant survive.
                    val key = eventKey(bucketId, event, bucket.optString("created"))
                    val encoded = canonicalJson(event)
                    if (known.optJSONObject(key)?.optString("hash") == fingerprint(encoded)) continue
                    val size = encoded.toByteArray(Charsets.UTF_8).size
                    require(size <= 256 * 1024) { "One activity event exceeds the webhook batch limit" }
                    if (batch.length() >= 200 || bytes + size > 512 * 1024) flush()
                    batch.put(event)
                    bytes += size
                }
                flush()
                start = end
            }
        }
        state.put("completed_at", until)
        // An event's end, rather than its start, keeps a long visit in the replay set.
        for (key in known.keys().asSequence().toList()) {
            if (known.getJSONObject(key).getLong("end") < until - REPLAY_MS) known.remove(key)
        }
        atomicWrite(stateFile, state.toString())
        return delivered
    }

    private fun checkCancelled() {
        if (cancelled() || Thread.currentThread().isInterrupted) throw IOException("Sync interrupted; pending data retained")
    }

    private fun deliverPending(payload: JSONObject): Int {
        checkCancelled()
        val events = payload.getJSONArray("events")
        val receipt = send(payload)
        if (receipt.batchId != payload.getString("batch_id") || receipt.events != events.length()) {
            throw IOException("Receiver did not confirm this batch; pending data retained")
        }
        val bucket = payload.getJSONObject("bucket").getString("id")
        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i)
            val end = Instant.parse(event.getString("timestamp")).toEpochMilli() +
                (event.getDouble("duration") * 1000).toLong()
            known.put(eventKey(bucket, event, payload.getJSONObject("bucket").optString("created")),
                JSONObject().put("hash", fingerprint(canonicalJson(event))).put("end", end))
        }
        // Persist first: a crash before removing pending merely replays a batch the receiver
        // already stored, with the same batch id and body.
        atomicWrite(stateFile, state.toString())
        if (!pendingFile.delete() && pendingFile.exists()) throw IOException("Cannot clear the acknowledged outbox")
        return events.length()
    }

    private fun eventKey(bucket: String, event: JSONObject, created: String = "") = "$bucket:$created:${event.get("id")}"
}

internal fun atomicWrite(file: File, text: String) {
    file.parentFile?.mkdirs()
    val temp = File(file.parentFile, file.name + ".tmp")
    java.io.FileOutputStream(temp).use { stream ->
        stream.write(text.toByteArray(Charsets.UTF_8))
        stream.fd.sync()
    }
    if (!temp.renameTo(file)) throw IOException("Could not save sync progress")
}
