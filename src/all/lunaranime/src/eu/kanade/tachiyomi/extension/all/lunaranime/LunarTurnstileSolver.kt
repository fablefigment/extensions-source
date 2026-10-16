package eu.kanade.tachiyomi.extension.all.lunaranime

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import keiyoushi.utils.applicationContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LunarTurnstileSolver(
    private val baseUrl: String,
) {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var cachedWv: WebView? = null
    private var destroyWv: Runnable? = null

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var tokenTime: Long = 0L

    @Volatile
    var harvestUrl: String? = null

    fun getToken(): String? {
        Log.d(TAG, "getToken called, harvestUrl=$harvestUrl")
        cachedToken?.let {
            if (System.currentTimeMillis() - tokenTime < TOKEN_TTL_MS) {
                Log.d(TAG, "returning cached token")
                return it
            }
        }
        val token = solve()
        Log.d(TAG, "solve returned: ${token?.take(20)}")
        if (token != null) {
            cachedToken = token
            tokenTime = System.currentTimeMillis()
        }
        return token
    }

    @Synchronized
    private fun solve(): String? {
        val url = harvestUrl ?: return null
        val latch = CountDownLatch(1)
        var result: String? = null

        handler.post {
            try {
                destroyWv?.let { handler.removeCallbacks(it) }
                if (cachedWv == null) {
                    cachedWv = WebView(applicationContext).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"
                    }
                }
                val webView = cachedWv!!

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): android.webkit.WebResourceResponse? {
                        val reqUrl = request.url.toString()
                        if (reqUrl.contains("/api/manga/r/")) {
                            Log.d(TAG, "intercepted API request: ${reqUrl.take(80)}")
                            val token = request.requestHeaders.entries.firstOrNull {
                                it.key.equals("catch-me-if-you-can-monkey", ignoreCase = true)
                            }?.value
                            Log.d(TAG, "token present: ${!token.isNullOrEmpty()}")
                            if (!token.isNullOrEmpty() && result == null) {
                                result = token
                                latch.countDown()
                            }
                        }
                        return null
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
                latch.countDown()
            }
        }

        latch.await(SOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return result
    }

    companion object {
        private const val TAG = "LunarTurnstile"
        private const val DESTROY_TIMEOUT_MS = 300000L
        private const val TOKEN_TTL_MS = 210000L
        private const val SOLVE_TIMEOUT_MS = 30000L
    }
}
