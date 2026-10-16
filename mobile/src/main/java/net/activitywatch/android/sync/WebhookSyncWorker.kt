package net.activitywatch.android.sync

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.activitywatch.android.BuildConfig
import net.activitywatch.android.ConfigManager
import net.activitywatch.android.privacy.PrivacyStore
import net.activitywatch.android.watcher.UsageStatsWatcher
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class WebhookPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("WebhookSync", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }
    var url: String
        get() = prefs.getString("url", "") ?: ""
        set(value) { prefs.edit().putString("url", value).apply() }
    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(value) { prefs.edit().putString("token", value).apply() }
    var status: String
        get() = prefs.getString("status", "尚未同步") ?: ""
        set(value) { prefs.edit().putString("status", value).apply() }
    fun deviceId(): String = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
        check(prefs.edit().putString("device_id", it).commit()) { "Cannot save device identity" }
    }
}

private val STATUS_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

internal class HttpFailure(val status: Int) : IOException("Webhook returned HTTP $status")

internal fun httpJson(url: URL, token: String?, payload: JSONObject? = null): String {
    val connection = url.openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = false
        if (!token.isNullOrBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
        if (payload != null) {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val bytes = payload.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
        }
        val code = connection.responseCode
        if (code !in 200..299) throw HttpFailure(code)
        return connection.inputStream.bufferedReader().use { reader ->
            val text = StringBuilder()
            val buffer = CharArray(8192)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                if (text.length + count > 16 * 1024 * 1024) throw IOException("Local activity response is too large")
                text.append(buffer, 0, count)
            }
            text.toString()
        }
    } finally { connection.disconnect() }
}

class WebhookSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = mutex.withLock {
        withContext(Dispatchers.IO) {
            val prefs = WebhookPreferences(applicationContext)
            if (!prefs.enabled) return@withContext Result.success()
            val targetUrl = prefs.url
            val token = prefs.token
            try {
                val destination = validateWebhookUrl(targetUrl).toURL()
                require(token.length >= 32 && !token.contains('\n') && !token.contains('\r')) {
                    "Configure a webhook token of at least 32 characters"
                }
                // Collect recent app sessions before exporting the local server's data.
                UsageStatsWatcher(applicationContext).sendHeartbeatsSuspend()
                val localToken = ConfigManager(applicationContext).readAuthConfig().apiKey
                val localBase = "http://127.0.0.1:${BuildConfig.SERVER_PORT}/api/0"
                val source = object : WebhookEventSource {
                    override fun buckets() = JSONObject(httpJson(URL("$localBase/buckets/"), localToken))
                    override fun events(bucket: String, start: Long, end: Long): JSONArray {
                        fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
                        val url = "$localBase/buckets/${encode(bucket)}/events?start=${encode(Instant.ofEpochMilli(start).toString())}&end=${encode(Instant.ofEpochMilli(end).toString())}"
                        return JSONArray(httpJson(URL(url), localToken))
                    }
                }
                val privacy = PrivacyStore(applicationContext).load()
                val targetKey = fingerprint(targetUrl + "\n" + token)
                val count = WebhookSyncEngine(
                    File(applicationContext.filesDir, "webhook/$targetKey"), prefs.deviceId(), source,
                    send = { batch ->
                        val response = JSONObject(WebhookTransport.post(destination, token, batch))
                        if (!response.optBoolean("ok")) throw IOException("Receiver did not confirm storage")
                        WebhookAck(response.getString("batch_id"), response.getInt("events"))
                    }, cancelled = {
                        val current = WebhookPreferences(applicationContext)
                        isStopped || !current.enabled || current.url != targetUrl || current.token != token
                    },
                    transform = privacy::applyToEvent,
                ).sync()
                prefs.status = "${STATUS_TIME.format(Instant.now())} 接收端已确认 $count 条记录"
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpFailure) {
                prefs.status = "HTTP ${e.status}，未送达的数据已保留"
                if (e.status == 408 || e.status == 429 || e.status >= 500) Result.retry() else Result.failure()
            } catch (e: IllegalArgumentException) {
                prefs.status = e.message ?: "Invalid webhook settings"
                Result.failure()
            } catch (e: Exception) {
                // Exceptions may contain a private destination URL or credentials. Keep
                // status/logging independent of exception text.
                prefs.status = "暂时无法同步（${e.javaClass.simpleName}），数据已保留，稍后自动重试"
                Result.retry()
            }
        }
    }

    companion object {
        private val mutex = Mutex()
        fun schedule(context: Context, immediate: Boolean = false) {
            val manager = WorkManager.getInstance(context)
            if (!WebhookPreferences(context).enabled) {
                manager.cancelUniqueWork("webhook-periodic")
                manager.cancelUniqueWork("webhook-now")
                return
            }
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            manager.enqueueUniquePeriodicWork("webhook-periodic", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WebhookSyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
            if (immediate) manager.enqueueUniqueWork("webhook-now", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<WebhookSyncWorker>().setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
    }
}
