package eu.kanade.tachiyomi.extension.all.lunaranime

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import keiyoushi.utils.applicationContext
import java.io.ByteArrayInputStream
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

                webView.removeJavascriptInterface(BRIDGE_NAME)
                webView.addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onToken(token: String) {
                            if (result == null) {
                                result = token.ifEmpty { null }
                                latch.countDown()
                            }
                        }
                    },
                    BRIDGE_NAME,
                )

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        if (!request.isForMainFrame) return null
                        return try {
                            val conn = java.net.URL(request.url.toString()).openConnection()
                            request.requestHeaders.forEach { (k, v) ->
                                if (!k.equals("X-Requested-With", ignoreCase = true)) {
                                    conn.setRequestProperty(k, v)
                                }
                            }
                            conn.connect()
                            val contentType = conn.contentType ?: "text/html"
                            if (!contentType.contains("text/html")) return null
                            val html = conn.inputStream.bufferedReader().readText()
                            val injected = html.injectHook()
                            WebResourceResponse(
                                "text/html",
                                "utf-8",
                                ByteArrayInputStream(injected.toByteArray()),
                            )
                        } catch (e: Exception) {
                            null
                        }
                    }
                }

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

    private fun String.injectHook(): String {
        val script = "<script>$HOOK_JS</script>"
        val headIndex = indexOf("<head", ignoreCase = true)
        return if (headIndex >= 0) {
            val tagEnd = indexOf('>', headIndex) + 1
            substring(0, tagEnd) + script + substring(tagEnd)
        } else {
            script + this
        }
    }

    companion object {
        private const val BRIDGE_NAME = "LunarTurnstileBridge"

        private const val HOOK_JS = """
            (function() {
                if (window.__lunarHooked) return;
                window.__lunarHooked = true;
                function check(headers) {
                    try {
                        var token = headers['catch-me-if-you-can-monkey'] || headers['Catch-Me-If-You-Can-Monkey'];
                        if (token && !window.__lunarTokenSent) {
                            window.__lunarTokenSent = true;
                            window.LunarTurnstileBridge.onToken(token);
                        }
                    } catch (e) {}
                }
                var origFetch = window.fetch;
                window.fetch = function(url, opts) {
                    if (opts && opts.headers) check(opts.headers);
                    return origFetch.apply(this, arguments);
                };
            })();
        """

        private const val DESTROY_TIMEOUT_MS = 300000L
        private const val TOKEN_TTL_MS = 210000L
        private const val SOLVE_TIMEOUT_MS = 30000L
    }
}
