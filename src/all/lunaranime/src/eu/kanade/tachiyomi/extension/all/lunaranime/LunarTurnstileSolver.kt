package eu.kanade.tachiyomi.extension.all.lunaranime

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
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
        cachedToken?.let {
            if (System.currentTimeMillis() - tokenTime < TOKEN_TTL_MS) {
                return it
            }
        }
        val token = solve()
        if (token != null) {
            cachedToken = token
            tokenTime = System.currentTimeMillis()
        }
        return token
    }

    @Synchronized
    private fun solve(): String? {
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

                webView.removeJavascriptInterface(BRIDGE_NAME)
                webView.addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onToken(token: String) {
                            result = token.ifEmpty { null }
                            latch.countDown()
                        }
                    },
                    BRIDGE_NAME,
                )

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        view.evaluateJavascript(HOOK_JS, null)
                    }
                }

                webView.loadUrl(harvestUrl ?: baseUrl)

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
        private const val BRIDGE_NAME = "LunarTurnstileBridge"

        private const val HOOK_JS = """
            (function() {
                if (window.__lunarHooked) return;
                window.__lunarHooked = true;
                var origFetch = window.fetch;
                window.fetch = function(url, opts) {
                    try {
                        var headers = (opts && opts.headers) || {};
                        var token = headers['catch-me-if-you-can-monkey'] || headers['Catch-Me-If-You-Can-Monkey'];
                        if (token && !window.__lunarTokenSent) {
                            window.__lunarTokenSent = true;
                            window.LunarTurnstileBridge.onToken(token);
                        }
                    } catch (e) {}
                    return origFetch.apply(this, arguments);
                };
            })();
        """

        private const val DESTROY_TIMEOUT_MS = 300000L
        private const val TOKEN_TTL_MS = 210000L
        private const val SOLVE_TIMEOUT_MS = 25000L
    }
}
