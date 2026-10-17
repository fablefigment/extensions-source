package eu.kanade.tachiyomi.extension.all.lunaranime

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import keiyoushi.utils.applicationContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class LunarTurnstileSolver(
    private val baseUrl: String,
) {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var cachedWv: WebView? = null
    private var destroyWv: Runnable? = null

    fun getToken(): String? = solve()

    @Synchronized
    private fun solve(): String? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>(null)

        val bridge = Bridge(result, latch)

        handler.post {
            try {
                destroyWv?.let { handler.removeCallbacks(it) }
                if (cachedWv == null) {
                    cachedWv = WebView(applicationContext).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                    }
                }
                val webView = cachedWv!!
                webView.onResume()
                webView.resumeTimers()

                webView.addJavascriptInterface(bridge, BRIDGE_NAME)

                webView.loadDataWithBaseURL("$baseUrl/", widgetHtml(), "text/html", "utf-8", null)

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
        return result.get()
    }

    class Bridge(
        private val result: AtomicReference<String?>,
        private val latch: CountDownLatch,
    ) {
        @JavascriptInterface
        fun onToken(token: String?) {
            if (!token.isNullOrEmpty() && result.compareAndSet(null, token)) {
                latch.countDown()
            }
        }

        @JavascriptInterface
        fun onWidgetError(code: String?) {
        }

        @JavascriptInterface
        fun log(message: String?) {
        }
    }

    private fun widgetHtml() = """
        <!doctype html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <script>
        function onTurnstileLoaded() {
            $BRIDGE_NAME.log('api.js loaded');
            turnstile.render('#widget', {
                sitekey: '$SITEKEY',
                theme: 'dark',
                callback: function (token) { $BRIDGE_NAME.onToken(token); },
                'error-callback': function (code) { $BRIDGE_NAME.onWidgetError(String(code)); },
                'before-interactive-callback': function () { $BRIDGE_NAME.log('interactive challenge requested'); }
            });
        }
        </script>
        <script src="https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit&onload=onTurnstileLoaded" async defer></script>
        </head>
        <body><div id="widget"></div></body>
        </html>
    """.trimIndent()

    companion object {
        private const val BRIDGE_NAME = "LunarBridge"
        private const val DESTROY_TIMEOUT_MS = 300000L
        private const val SOLVE_TIMEOUT_MS = 30000L
        private const val SITEKEY = "0x4AAAAAAEweOgJ6zfOKhkgq"
    }
}
