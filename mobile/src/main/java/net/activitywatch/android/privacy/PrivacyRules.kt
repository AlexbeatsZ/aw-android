package net.activitywatch.android.privacy

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** What a rule's pattern is compared against. */
enum class PrivacyMatch(val key: String) {
    /** App name or package name, exact and case-insensitive; also matches the browser of a visit. */
    APP("app"),
    /** A website host, including its subdomains: `bilibili.com` covers `m.bilibili.com`. */
    DOMAIN("domain"),
    /** Text contained in an app name, package, address or title. */
    KEYWORD("keyword");

    companion object {
        fun of(key: String): PrivacyMatch? = entries.firstOrNull { it.key == key }
    }
}

data class PrivacyRule(val match: PrivacyMatch, val pattern: String, val label: String)

/**
 * Renames sensitive apps and websites before they are shown or leave the phone.
 *
 * Rules are checked in order and the first match wins. A matched event keeps its time,
 * duration and browser, but every field that could identify the app or page is replaced
 * by the rule's label, so `{app: "原神", package: "com.miHoYo.Yuanshen"}` becomes
 * `{app: "娱乐", package: "", private: true}`.
 *
 * With [domainOnly], unmatched visits keep only their host: paths, queries and page
 * titles are dropped.
 */
data class PrivacyConfig(val rules: List<PrivacyRule> = emptyList(), val domainOnly: Boolean = false) {

    fun toJson(): JSONObject = JSONObject()
        .put("version", 1)
        .put("domainOnly", domainOnly)
        .put("rules", JSONArray().also { array ->
            rules.forEach {
                array.put(JSONObject().put("match", it.match.key).put("pattern", it.pattern).put("label", it.label))
            }
        })

    /** True when this configuration changes nothing, so callers can skip copying events. */
    val isEmpty: Boolean get() = rules.isEmpty() && !domainOnly

    /** The rule that applies to this event data, if any. */
    fun ruleFor(data: JSONObject): PrivacyRule? {
        val app = data.optString("app")
        val pkg = data.optString("package")
        val browser = data.optString("browser")
        val url = data.optString("url")
        val host = hostOf(url)
        val texts = listOf(app, pkg, url, data.optString("title"), data.optString("artist"), data.optString("album"))
        return rules.firstOrNull { rule ->
            val pattern = rule.pattern
            when (rule.match) {
                PrivacyMatch.APP -> listOf(app, pkg, browser).any { it.isNotEmpty() && it.equals(pattern, ignoreCase = true) }
                PrivacyMatch.DOMAIN -> host.isNotEmpty() && normalizeDomain(pattern).let { domain ->
                    domain.isNotEmpty() && (host == domain || host.endsWith(".$domain"))
                }
                PrivacyMatch.KEYWORD -> texts.any { it.contains(pattern, ignoreCase = true) }
            }
        }
    }

    /** A privacy-safe copy of an event's `data`. Unchanged data is returned as is. */
    fun applyToData(data: JSONObject): JSONObject {
        if (isEmpty) return data
        val rule = ruleFor(data)
        if (rule != null) return masked(data, rule.label)
        if (domainOnly && data.has("url")) {
            val copy = JSONObject(data.toString())
            copy.put("url", hostOf(data.optString("url")))
            if (copy.has("title")) copy.put("title", "")
            return copy
        }
        return data
    }

    /** A privacy-safe copy of a whole event (`{id, timestamp, duration, data}`). */
    fun applyToEvent(event: JSONObject): JSONObject {
        val data = event.optJSONObject("data") ?: return event
        val safe = applyToData(data)
        if (safe === data) return event
        return JSONObject(event.toString()).put("data", safe)
    }

    private fun masked(data: JSONObject, label: String): JSONObject {
        val out = JSONObject()
        // Keep only fields that say how, not what: these never identify the app or page.
        for (key in SAFE_KEYS) if (data.has(key)) out.put(key, data.get(key))
        if (data.has("app") || data.has("package")) {
            out.put("app", label)
            out.put("package", "")
        }
        if (data.has("url")) out.put("url", label)
        if (data.has("title")) out.put("title", label)
        if (data.has("artist")) out.put("artist", "")
        if (data.has("album")) out.put("album", "")
        out.put("private", true)
        return out
    }

    companion object {
        private val SAFE_KEYS = listOf("browser", "audible", "incognito", "state")
        const val MAX_RULES = 500
        const val MAX_TEXT = 200

        fun fromJson(text: String?): PrivacyConfig {
            if (text.isNullOrBlank()) return PrivacyConfig()
            val json = try { JSONObject(text) } catch (_: Exception) { return PrivacyConfig() }
            return fromJson(json)
        }

        /** Parses stored or page-supplied rules, dropping malformed entries. */
        fun fromJson(json: JSONObject): PrivacyConfig {
            val rules = mutableListOf<PrivacyRule>()
            val array = json.optJSONArray("rules") ?: JSONArray()
            for (i in 0 until minOf(array.length(), MAX_RULES)) {
                val item = array.optJSONObject(i) ?: continue
                val match = PrivacyMatch.of(item.optString("match")) ?: continue
                val pattern = item.optString("pattern").trim().take(MAX_TEXT)
                val label = item.optString("label").trim().take(MAX_TEXT)
                val stored = if (match == PrivacyMatch.DOMAIN) normalizeDomain(pattern) else pattern
                if (stored.isEmpty() || label.isEmpty()) continue
                rules.add(PrivacyRule(match, stored, label))
            }
            return PrivacyConfig(rules, json.optBoolean("domainOnly", false))
        }

        /** `https://www.Example.com:8080/a?b` → `example.com`. Works for scheme-less addresses. */
        fun hostOf(url: String): String {
            if (url.isBlank()) return ""
            var rest = url.trim()
            val scheme = rest.indexOf("://")
            if (scheme in 1..16) rest = rest.substring(scheme + 3)
            val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
            if (end >= 0) rest = rest.substring(0, end)
            rest = rest.substringAfterLast('@')
            if (!rest.startsWith("[")) rest = rest.substringBefore(':')
            return rest.lowercase(Locale.ROOT).removePrefix("www.").trimEnd('.')
        }

        fun normalizeDomain(pattern: String): String = hostOf(pattern).removePrefix("*.").removePrefix(".")
    }
}
