package net.activitywatch.android.sync

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal fun isFunnelHostname(host: String): Boolean = host.lowercase().endsWith(".ts.net")

internal fun publicFunnelAddresses(response: JSONObject): List<InetAddress> {
    val answers = response.optJSONArray("Answer") ?: return emptyList()
    return (0 until answers.length()).mapNotNull { index ->
        val answer = answers.getJSONObject(index)
        if (answer.optInt("type") != 1) return@mapNotNull null
        val parts = answer.optString("data").split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.any { c -> c !in '0'..'9' } || it.toIntOrNull() !in 0..255 }) return@mapNotNull null
        val octets = parts.map { it.toInt() }
        // Funnel is public HTTPS. Never turn its public-DNS fallback into a LAN,
        // CGNAT/tailnet, link-local, multicast or fake-IP connection.
        if (octets[0] == 0 || octets[0] == 10 || octets[0] == 127 || octets[0] >= 224 ||
            octets[0] == 100 && octets[1] in 64..127 ||
            octets[0] == 169 && octets[1] == 254 ||
            octets[0] == 172 && octets[1] in 16..31 ||
            octets[0] == 192 && octets[1] == 168 ||
            octets[0] == 198 && octets[1] in 18..19) return@mapNotNull null
        InetAddress.getByAddress(octets.map { it.toByte() }.toByteArray())
    }.distinct()
}

/** System DNS first. Some Android VPN profiles resolve a public Funnel name to
 * their broken private/fake-IP route. Retry only that public .ts.net origin using
 * public DNS, while OkHttp still verifies TLS and sends SNI for the original host.
 * No IP is pinned and no redirects or certificate exceptions are accepted. */
internal object WebhookTransport {
    private data class Cached(val until: Long, val addresses: List<InetAddress>)
    private val fallback = ConcurrentHashMap<String, Cached>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    fun post(url: URL, token: String, payload: JSONObject): String {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        fun execute(using: OkHttpClient): String = using.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpFailure(response.code)
            val body = response.body ?: throw IOException("Empty webhook receipt")
            body.charStream().use { reader ->
                val text = StringBuilder()
                val buffer = CharArray(1024)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (text.length + count > 16_384) throw IOException("Webhook receipt too large")
                    text.append(buffer, 0, count)
                }
                text.toString()
            }
        }
        val host = url.host
        if (isFunnelHostname(host) && (fallback[host]?.until ?: 0) > System.currentTimeMillis()) {
            return execute(publicClient(host))
        }
        try { return execute(client) }
        catch (error: HttpFailure) { throw error }
        catch (error: IOException) {
            if (!isFunnelHostname(host)) throw error
            return execute(publicClient(host))
        }
    }

    private fun publicClient(host: String): OkHttpClient {
        var cached = fallback[host]
        if (cached == null || cached.until <= System.currentTimeMillis()) {
            val query = URL("https://dns.google/resolve?name=${URLEncoder.encode(host, "UTF-8")}&type=A")
            val addresses = publicFunnelAddresses(JSONObject(httpJson(query, null)))
            if (addresses.isEmpty()) throw IOException("No public Funnel address available")
            cached = Cached(System.currentTimeMillis() + 300_000, addresses)
            fallback[host] = cached
        }
        val addresses = cached.addresses
        // A VPN can also advertise an HTTP proxy, whose CONNECT handler resolves
        // the hostname again. For this one fallback connection, connect to the
        // verified public address through the VPN's ordinary IP route instead.
        return client.newBuilder().proxy(java.net.Proxy.NO_PROXY).dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                if (hostname == host) addresses else Dns.SYSTEM.lookup(hostname)
        }).build()
    }
}
