package net.activitywatch.android.dashboard

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import net.activitywatch.android.R
import net.activitywatch.android.sync.WebhookSettingsActivity

private const val TAG = "Dashboard"

/** Hosts the bundled dashboard (assets/dashboard) and its native bridge. */
class DashboardFragment : Fragment() {
    private var webView: WebView? = null
    private var bridge: DashboardBridge? = null

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = WebView(requireContext())
        // Matches the page background so there is no white flash before it paints.
        view.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.chrome_dark))
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Assets load regardless of these; nothing else on disk or in providers is needed.
            allowFileAccess = false
            allowContentAccess = false
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !isDashboardUrl(request.url.toString())
        }
        val bridge = DashboardBridge(
            requireContext(),
            deliver = { js -> view.post { if (webView === view) view.evaluateJavascript(js, null) } },
            openScreen = { screen -> view.post { launchScreen(screen) } },
        )
        view.addJavascriptInterface(bridge, "AWNative")
        this.bridge = bridge
        webView = view
        view.loadUrl(DASHBOARD_URL)
        return view
    }

    /** Ask the page to reload its data, e.g. after usage history was imported. */
    fun refresh() {
        webView?.evaluateJavascript("window.AWApp && AWApp.refresh()", null)
    }

    /** Lets the page handle back first (close a selection, return to the timeline). */
    fun handleBack(unhandled: () -> Unit) {
        val view = webView ?: return unhandled()
        view.evaluateJavascript("window.AWApp ? AWApp.back() : false") { result ->
            if (result != "true") unhandled()
        }
    }

    private fun launchScreen(screen: String) {
        val context = context ?: return
        val intent = when (screen) {
            "webhook" -> Intent(context, WebhookSettingsActivity::class.java)
            "usage" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            else -> return
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No activity for $screen", e)
        }
    }

    override fun onDestroyView() {
        bridge?.shutdown()
        bridge = null
        webView?.let {
            it.removeJavascriptInterface("AWNative")
            it.destroy()
        }
        webView = null
        super.onDestroyView()
    }
}
