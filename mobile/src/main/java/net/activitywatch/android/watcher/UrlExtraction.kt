package net.activitywatch.android.watcher

// Firefox (Compose toolbar): URL is in content-desc of ADDRESSBAR_URL_BOX as
// " {url}. Search or enter address". Greedy so we split at the LAST ". " (URLs can
// themselves contain ". "), and we don't assume the hint text starts with an ASCII
// capital letter since it's localized and may start with a non-Latin character.
private val FIREFOX_SUFFIX_PATTERN = Regex("""^\s*(.+)\.\s+\S""")

// Pure parsing logic, unit-testable (mobile/src/test) without any Android/Accessibility
// framework dependency.
internal fun parseFirefoxAddressBarContentDescription(contentDescription: String?): String? =
    contentDescription
        ?.let { FIREFOX_SUFFIX_PATTERN.find(it)?.groupValues?.get(1) }
        ?.takeIf { it.isNotBlank() && !it.equals("Search or enter address", ignoreCase = true) }

// Strips the URI scheme so the same page is represented identically regardless of which
// browser/UI-variant's view happened to include it (e.g. Samsung Internet's regular vs.
// custom-tab toolbar previously disagreed on this, splitting one continuous visit in two).
internal val stripProtocol: (String) -> String = { url ->
    url.removePrefix("http://").removePrefix("https://")
}

// Pure post-processing of text read off an accessibility node: blank text (e.g. a
// momentarily-cleared address bar) is treated as "no url", not as a real value.
internal fun processExtractedText(rawText: String?): String? =
    rawText?.takeIf { it.isNotBlank() }

/** Via's toolbar may display a page title, domain or full URL. Never turn a title
 * or a partially typed address into a visit. The Via setting must display URL. */
internal fun parseViaAddressBar(text: String?, editable: Boolean = false): String? {
    if (editable) return null
    val candidate = text?.trim()?.takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) } ?: return null
    val withScheme = if (candidate.startsWith("https://") || candidate.startsWith("http://")) candidate
        else "https://$candidate"
    val uri = try { java.net.URI(withScheme) } catch (_: Exception) { return null }
    val host = uri.host ?: return null
    if (uri.userInfo != null || (!host.contains('.') && host != "localhost")) return null
    return stripProtocol(candidate)
}
