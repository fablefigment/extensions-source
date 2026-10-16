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

    private val globalWebView: WebView
        get() {
            destroyWv?.let { handler.removeCallbacks(it) }
            if (cachedWv == null) {
                cachedWv = WebView(applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                }
            }
            destroyWv = Runnable {
                cachedWv?.destroy()
                cachedWv = null
                destroyWv = null
            }.also {
                handler.postDelayed(it, DESTROY_TIMEOUT_MS)
            }
            return cachedWv!!
        }

    fun getToken(): String? {
        cachedToken?.let {
            if (System.currentTimeMillis() - tokenTime < TOKEN_TTL_MS) return it
        }
        return solve().also {
            if (it != null) {
                cachedToken = it
                tokenTime = System.currentTimeMillis()
            }
        }
    }

    @Synchronized
    private fun solve(): String? {
        val latch = CountDownLatch(1)
        var result: String? = null

        handler.post {
            try {
                val webView = globalWebView

                webView.addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onResult(token: String) {
                            result = token.ifEmpty { null }
                            latch.countDown()
                        }
                    },
                    BRIDGE_NAME,
                )

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript(buildJs(), null)
                    }
                }

                webView.loadDataWithBaseURL(baseUrl, HTML, "text/html", "utf-8", null)
            } catch (e: Throwable) {
                latch.countDown()
            }
        }

        latch.await(SOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return result
    }

    private fun buildJs(): String = """
        (function() {
            var done = false;
            function finish(token) {
                if (done) return;
                done = true;
                window.${BRIDGE_NAME}.onResult(token || "");
            }
            window.${TS_CALLBACK} = function() {
                try {
                    var widgetId = turnstile.render('#${TS_CONTAINER}', {
                        sitekey: '${SITE_KEY}',
                        size: 'invisible',
                        callback: function(token) { finish(token); },
                        'error-callback': function() { finish(""); }
                    });
                    turnstile.execute(widgetId);
                } catch (e) { finish(""); }
            };
            var s = document.createElement('script');
            s.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?onload=${TS_CALLBACK}';
            s.async = true;
            s.defer = true;
            s.onerror = function() { finish(""); };
            document.head.appendChild(s);
            setTimeout(function() { finish(""); }, ${SOLVE_TIMEOUT_MS - 500});
        })();
    """.trimIndent()

    companion object {
        private const val BRIDGE_NAME = "LunarTurnstileBridge"
        private const val TS_CALLBACK = "lunarTsReady"
        private const val TS_CONTAINER = "lunar-ts-container"
        private const val SITE_KEY = "0x4AAAAAAEweOgJ6zfOKhkgq"
        private const val HTML = "<html><body><div id=\"$TS_CONTAINER\"></div></body></html>"
        private const val DESTROY_TIMEOUT_MS = 300000L
        private const val TOKEN_TTL_MS = 210000L
        private const val SOLVE_TIMEOUT_MS = 10000L
    }
}
