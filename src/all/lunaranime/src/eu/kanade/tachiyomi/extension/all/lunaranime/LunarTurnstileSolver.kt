package eu.kanade.tachiyomi.extension.all.lunaranime

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import keiyoushi.utils.applicationContext
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Loads the chapter page in a WebView and lifts the Turnstile token the page's own script
 * attaches to its `/api/manga/r/` request (`Catch-Me-If-You-Can-Monkey`).
 *
 * Turnstile tokens are single use, so:
 *  - the page's own request is blocked once the token has been read, otherwise the page spends
 *    it before OkHttp can, and
 *  - a token is never handed out twice.
 */
class LunarTurnstileSolver(
    private val baseUrl: String,
) {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var cachedWv: WebView? = null
    private var destroyWv: Runnable? = null

    @Volatile
    var harvestUrl: String? = null

    /** Returns a fresh, unused token, or null (with the reason logged) if none could be obtained. */
    fun getToken(): String? {
        Log.d(TAG, "getToken called, harvestUrl=$harvestUrl")
        val token = solve()
        Log.d(TAG, "solve returned: ${token?.take(20)}")
        return token
    }

    @Synchronized
    private fun solve(): String? {
        val url = harvestUrl
        if (url == null) {
            Log.w(TAG, "harvestUrl is null, nothing to load")
            return null
        }
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>(null)

        handler.post {
            try {
                destroyWv?.let { handler.removeCallbacks(it) }
                if (cachedWv == null) {
                    cachedWv = WebView(applicationContext).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = USER_AGENT
                    }
                }
                val webView = cachedWv!!

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val reqUrl = request.url.toString()
                        if (!reqUrl.contains("/api/manga/r/")) return null

                        Log.d(TAG, "intercepted API request: ${reqUrl.take(80)}")
                        val token = request.requestHeaders.entries.firstOrNull {
                            it.key.equals("catch-me-if-you-can-monkey", ignoreCase = true)
                        }?.value
                        Log.d(TAG, "token present: ${!token.isNullOrEmpty()}")

                        if (!token.isNullOrEmpty() && result.compareAndSet(null, token)) {
                            latch.countDown()
                        }

                        // Swallow the page's own request so it doesn't consume the single-use token.
                        return WebResourceResponse(
                            "application/json",
                            "utf-8",
                            403,
                            "Blocked",
                            emptyMap(),
                            ByteArrayInputStream("{}".toByteArray()),
                        )
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: android.webkit.WebResourceError,
                    ) {
                        if (request.isForMainFrame) {
                            Log.d(TAG, "page error: ${error.errorCode} ${error.description}")
                        }
                    }
                }

                Log.d(TAG, "loading URL: $url")
                webView.loadUrl(url)

                destroyWv = Runnable {
                    cachedWv?.destroy()
                    cachedWv = null
                    destroyWv = null
                }.also {
                    handler.postDelayed(it, DESTROY_TIMEOUT_MS)
                }
            } catch (e: Throwable) {
                // This used to be swallowed silently, which made the solver return null instantly.
                Log.e(TAG, "WebView setup failed", e)
                latch.countDown()
            }
        }

        if (!latch.await(SOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "timed out after ${SOLVE_TIMEOUT_MS}ms waiting for the page to request /api/manga/r/")
        }
        return result.get()
    }

    companion object {
        private const val TAG = "LunarTurnstile"
        private const val DESTROY_TIMEOUT_MS = 300000L
        private const val SOLVE_TIMEOUT_MS = 30000L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"
    }
}
