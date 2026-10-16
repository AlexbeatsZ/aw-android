package net.activitywatch.android.watcher

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import net.activitywatch.android.RustInterface
import org.json.JSONObject

private fun extractTextByViewId(event: AccessibilityEvent, viewId: String): String? {
    event.source?.let { source ->
        val nodes = source.findAccessibilityNodeInfosByViewId(viewId)
        try {
            return processExtractedText(nodes.firstOrNull()?.text?.toString())
        } finally {
            nodes.forEach { it.recycle() }
            source.recycle()
        }
    }
    return null
}

class WebWatcher : AccessibilityService() {

    // The toolbar is a sibling of the content area, so we search from the window root.
    // findAccessibilityNodeInfosByViewId requires "package:id/name" format and silently
    // rejects bare testTag names, so we traverse manually.
    private fun extractFirefoxUrl(event: AccessibilityEvent): String? {
        val root = rootInActiveWindow ?: return null
        try {
            val found = findNode(root) { it.viewIdResourceName == "ADDRESSBAR_URL_BOX" }
            val result = parseFirefoxAddressBarContentDescription(found?.contentDescription?.toString())
            if (found !== root) found?.recycle()
            return result
        } finally {
            root.recycle()
        }
    }

    private fun extractViaUrl(packageName: String): String? {
        val root = rootInActiveWindow ?: return null
        try {
            if (root.packageName?.toString() != packageName) return null
            // Captured from the installed Via Play APK's actual toolbar. Via creates
            // its address label programmatically, so the label itself has no view id.
            // The enclosing toolbar does. Searching only that toolbar excludes all
            // web page links, history rows and address-entry suggestions.
            val toolbar = findNode(root) { it.viewIdResourceName == "$packageName:id/fm" }
            if (toolbar == null) {
                // AccessibilityService can receive a compressed tree that omits the
                // toolbar container/id even though UiAutomator's tree includes it.
                // A committed Via address is a clickable native TextView flanked by
                // native ImageViews. Require a page WebView and reject page/list ancestry.
                val webView = findNode(root) { it.className == "android.webkit.WebView" } ?: return null
                if (webView !== root) webView.recycle()
                val address = findNode(root) { node ->
                    node.className == "android.widget.TextView" && node.isClickable && !node.isEditable &&
                        parseViaAddressBar(node.text?.toString()) != null && isViaToolbarLabel(node)
                } ?: return null
                try { return parseViaAddressBar(address.text?.toString()) }
                finally { if (address !== root) address.recycle() }
            }
            try {
                val address = findNode(toolbar) {
                    it.className == "android.widget.TextView" && it.isClickable && !it.isEditable
                } ?: return null
                try { return parseViaAddressBar(address.text?.toString(), address.isEditable) }
                finally { if (address !== toolbar) address.recycle() }
            } finally { if (toolbar !== root) toolbar.recycle() }
        } finally { root.recycle() }
    }

    private fun isViaToolbarLabel(node: AccessibilityNodeInfo): Boolean {
        val parent = node.parent ?: return false
        var icons = 0
        try {
            if (parent.childCount > 16) return false
            for (i in 0 until parent.childCount) {
                val sibling = parent.getChild(i) ?: continue
                try { if (sibling.className == "android.widget.ImageView") icons++ }
                finally { sibling.recycle() }
            }
        } finally { parent.recycle() }
        if (icons < 2) return false
        var ancestor = node.parent
        var depth = 0
        while (ancestor != null && depth++ < MAX_TRAVERSAL_DEPTH) {
            val current = ancestor
            try {
                val name = current.className?.toString().orEmpty()
                if (name == "android.webkit.WebView" || name.contains("RecyclerView") ||
                    name.contains("ListView") || name.contains("ScrollView")) return false
                ancestor = current.parent
            } finally { current.recycle() }
        }
        ancestor?.recycle()
        return ancestor == null
    }

    private val TAG = "WebWatcher"
    private val bucket_id = "aw-watcher-android-web"
    private val lastDiagnosticDump = mutableMapOf<String, Long>()

    @Volatile private var ri : RustInterface? = null
    private var lastWindowId: Int? = null
    private val sessionTracker = BrowserSessionTracker()
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) handleUrl(null, newBrowser = null)
        }
    }

    // heartbeat() blocks on the datastore worker for as long as it is busy. Calling it from
    // onAccessibilityEvent ran it on the service's main thread, the same pattern that caused
    // the createBucketHelper ANRs (aw-android#261). One thread keeps events in order.
    // The queue is bounded so a long datastore stall can't grow it without limit; past
    // MAX_PENDING_WRITES new events are dropped (and logged) rather than blocking this thread.
    private val writer = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(MAX_PENDING_WRITES),
        { r -> Thread(r, "WebWatcher-writer") },
        { _, executor ->
            if (!executor.isShutdown) Log.w(TAG, "Datastore stalled; dropping a browser event")
        },
    )

    // Applies stripProtocol uniformly to whatever extractor matched, so the logged url is
    // formatted identically no matter which browser/view-variant produced it.
    private fun extractUrl(packageName: String, event: AccessibilityEvent): String? = when (packageName) {
        "com.android.chrome" -> extractTextByViewId(event, "com.android.chrome:id/url_bar")
        "org.mozilla.firefox" ->
            // Compose toolbar (current)
            extractFirefoxUrl(event)
                // View-based toolbar (older Firefox versions)
                ?: extractTextByViewId(event, "org.mozilla.firefox:id/url_bar_title")
                ?: extractTextByViewId(event, "org.mozilla.firefox:id/mozac_browser_toolbar_url_view")
        "com.sec.android.app.sbrowser" ->
            extractTextByViewId(event, "com.sec.android.app.sbrowser:id/location_bar_edit_text")
                ?: extractTextByViewId(event, "com.sec.android.app.sbrowser:id/custom_tab_toolbar_url_bar_text")
        "com.opera.browser" ->
            extractTextByViewId(event, "com.opera.browser:id/url_field")
                ?: extractTextByViewId(event, "com.opera.browser:id/address_field")
        "com.microsoft.emmx" -> extractTextByViewId(event, "com.microsoft.emmx:id/url_bar")
        "mark.via", "mark.via.gp" -> extractViaUrl(packageName)
        else -> null
    }?.let(stripProtocol)

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Creating WebWatcher")
        ContextCompat.registerReceiver(this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        // createBucketHelper() blocks on the datastore worker. Doing that on the
        // accessibility service's main thread produced "Executing service
        // WebWatcher" ANRs whenever the worker was busy (aw-android#261), so
        // initialize off the main thread; events arriving earlier are dropped by
        // the null-safe ri?. calls, same as MediaWatcher.
        thread(name = "WebWatcher-init") {
            try {
                ri = RustInterface(applicationContext).also { it.createBucketHelper(bucket_id, "web.tab.current") }
            } catch (ex: Throwable) {
                // Catch Throwable (not just Exception) because System.loadLibrary() throws
                // UnsatisfiedLinkError (an Error subclass) when the native library is missing.
                Log.e(TAG, "Failed to initialize RustInterface: ${ex.message}")
            }
        }
    }

    // TODO: This method is called very often, which might affect performance. Future optimizations needed.
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (shouldIgnoreEvent(event)) {
            return
        }

        val packageName = event.packageName?.toString()
        val isKnownBrowser = packageName != null && packageName in KNOWN_BROWSER_PACKAGES

        val windowChanged = windowChanged(event.windowId)
        lastWindowId = event.windowId

        if (!isKnownBrowser) {
            // for some browsers like Firefox event.packageName can be null (no extractor matched)
            // but we are still on the same window
            if (windowChanged) {
                Log.i(TAG, "Window changed away from a tracked browser (new package: $packageName); ending session")
                handleUrl(null, newBrowser = null)
            }

            return
        }

        try {
            event.source?.let { source ->
                try {
                    val browser = packageName!!
                    val newUrl = extractUrl(browser, event)

                    if (newUrl == null) {
                        if (browser == "mark.via" || browser == "mark.via.gp") {
                            // Home, address editing or title-only toolbar: don't let the
                            // previous known visit keep growing through an unknown page.
                            handleUrl(null, newBrowser = null)
                        }
                        maybeDumpTree(browser)
                    } else {
                        handleUrl(newUrl, newBrowser = browser)
                    }
                    findWebView(source)?.let { webView ->
                        handleWindowTitle(webView.text.toString())
                        if (webView !== source) webView.recycle()
                    }
                } finally {
                    source.recycle()
                }
            }
        } catch(ex : Exception) {
            Log.e(TAG, ex.message ?: ex.toString())
        }
    }

    private fun windowChanged(windowId: Int): Boolean = windowId != lastWindowId

    private fun shouldIgnoreEvent(event: AccessibilityEvent) =
        event.packageName == "com.android.systemui"

    // TODO(maintainer): this never finds a match for Firefox, so its page title is never
    // captured (logged events show title:""). Confirmed live on-device (2026-07-01, Fenix,
    // GeckoView content): dumping the full accessibility tree of rootInActiveWindow while a
    // real page was loaded showed `browserLayout` has exactly 3 children - a ComposeView
    // (whose only child had zero further descendants), an unexplained childless node with a
    // null className, and the toolbar (composable_toolbar, which is where ADDRESSBAR_URL_BOX
    // lives - see extractFirefoxUrl above). The page content is not a descendant of this
    // window's root at all, so no restructuring of findWebView's search root will find it.
    // GeckoView most likely exposes its content as a *separate* accessibility window rather
    // than as nodes within this one. Fixing this needs AccessibilityService#getWindows() to
    // be enumerated to find the window that hosts GeckoView's content (if any - it's also
    // possible the title isn't exposed as an accessibility node at all, e.g. only via a
    // window title property), which is a bigger change than this function's shape allows for.
    private fun findWebView(info: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findNode(info) { it.className == "android.webkit.WebView" && it.text != null }

    // Dumps the accessibility tree to logcat at debug level, rate-limited to once per minute
    // per browser. Helps diagnose URL extraction failures when adding support for new browsers
    // or browser versions that have changed their view hierarchy. Enable with:
    //   adb shell setprop log.tag.WebWatcher DEBUG
    private fun maybeDumpTree(packageName: String) {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return
        val now = System.currentTimeMillis()
        if (now - (lastDiagnosticDump[packageName] ?: 0L) < 60_000L) return
        lastDiagnosticDump[packageName] = now
        val root = rootInActiveWindow ?: return
        Log.d(TAG, "URL extraction failed for $packageName — accessibility tree:")
        try {
            forEachNode(root) { node, depth ->
                val id = node.viewIdResourceName ?: ""
                val cd = node.contentDescription?.toString()?.take(120) ?: ""
                val text = node.text?.toString()?.take(120) ?: ""
                if (id.isNotEmpty() || cd.isNotEmpty() || text.isNotEmpty()) {
                    Log.d(TAG, "${"  ".repeat(depth)}class=${node.className} id=$id cd=\"$cd\" text=\"$text\"")
                }
            }
        } finally {
            root.recycle()
        }
    }

    private fun handleUrl(newUrl : String?, newBrowser: String?) {
        newUrl?.let { Log.i(TAG, "Url: $it, browser: $newBrowser") }
        sessionTracker.handleUrl(newUrl, newBrowser)?.let { logBrowserEvent(it) }
    }

    private fun handleWindowTitle(newWindowTitle: String) {
        if (sessionTracker.handleWindowTitle(newWindowTitle)) {
            Log.i(TAG, "Title: $newWindowTitle")
        }
    }

    private fun logBrowserEvent(session: CompletedBrowserSession) {
        val data = JSONObject()
            .put("url", session.url)
            .put("browser", session.browser)
            .put("title", session.title)
            .put("audible", false) // TODO
            .put("incognito", false) // TODO

        Log.i(TAG, "Registered event: $data")
        writer.execute {
            ri?.heartbeatHelper(bucket_id, session.start, session.duration.seconds.toDouble(), data, 1.0)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        unregisterReceiver(screenOffReceiver)
        handleUrl(null, newBrowser = null)
        // Lets already-queued events finish writing.
        writer.shutdown()
        super.onDestroy()
    }

    companion object {
        // Each queued write is one completed page visit, so this covers a long stall.
        private const val MAX_PENDING_WRITES = 256

        internal val KNOWN_BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.sec.android.app.sbrowser",
            "com.opera.browser",
            "com.microsoft.emmx",
            "mark.via",
            "mark.via.gp"
        )
    }
}
