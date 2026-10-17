package eu.kanade.tachiyomi.extension.all.lunaranime

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import keiyoushi.utils.applicationContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Gets a Turnstile token for the reader by rendering the site's own Turnstile widget.
 *
 * Waiting for the real chapter page to produce one doesn't work: the page only runs Turnstile
 * when *its* key isn't verified yet, and the WebView's key usually already is, so its
 * `/api/manga/r/` request goes out with no token at all. What the server needs is a token sent
 * together with *our* DPoP key, so we make one ourselves: a tiny page served with
 * `https://lunarx.to` as its origin (so the sitekey's hostname check passes) that renders the
 * widget and hands the token back through a JS bridge.
 *
 * Tokens are single use, so one is never handed out twice.
 */
class LunarTurnstileSolver(
    private val baseUrl: String,
) {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var cachedWv: WebView? = null
    private var destroyWv: Runnable? = null

    /** Returns a fresh, unused token, or null (with the reason logged) if none could be obtained. */
    fun getToken(): String? {
        Log.d(TAG, "getToken called")
        val token = solve()
        Log.d(TAG, "solve returned: ${token?.let { "token of length ${it.length}" }}")
        return token
    }

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
                        settings.userAgentString = USER_AGENT
                    }
                }
                val webView = cachedWv!!
                webView.onResume()
                webView.resumeTimers()

                webView.addJavascriptInterface(bridge, BRIDGE_NAME)
                webView.webViewClient = object : WebViewClient() {
                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: android.webkit.WebResourceError,
                    ) {
                        Log.d(TAG, "load error: ${request.url} ${error.errorCode} ${error.description}")
                    }
                }

                Log.d(TAG, "rendering Turnstile widget with origin $baseUrl")
                webView.loadDataWithBaseURL("$baseUrl/", widgetHtml(), "text/html", "utf-8", null)

                destroyWv = Runnable {
                    cachedWv?.destroy()
                    cachedWv = null
                    destroyWv = null
                }.also {
                    handler.postDelayed(it, DESTROY_TIMEOUT_MS)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "WebView setup failed", e)
                latch.countDown()
            }
        }

        if (!latch.await(SOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(
                TAG,
                "timed out after ${SOLVE_TIMEOUT_MS}ms without a token. " +
                    "If you saw no 'page:' lines, api.js never loaded; if the widget reported " +
                    "an interactive challenge, it can't be solved from a hidden WebView.",
            )
        }
        return result.get()
    }

    /** Public and named on purpose: Android's JS bridge is unreliable with anonymous classes. */
    class Bridge(
        private val result: AtomicReference<String?>,
        private val latch: CountDownLatch,
    ) {
        @JavascriptInterface
        fun onToken(token: String?) {
            Log.d(TAG, "widget produced a token (length ${token?.length})")
            if (!token.isNullOrEmpty() && result.compareAndSet(null, token)) {
                latch.countDown()
            }
        }

        @JavascriptInterface
        fun onWidgetError(code: String?) {
            // Turnstile retries on its own, so this isn't fatal; the timeout handles a dead end.
            Log.w(TAG, "widget error: $code")
        }

        @JavascriptInterface
        fun log(message: String?) {
            Log.d(TAG, "page: $message")
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
        private const val TAG = "LunarTurnstile"
        private const val BRIDGE_NAME = "LunarBridge"
        private const val DESTROY_TIMEOUT_MS = 300000L
        private const val SOLVE_TIMEOUT_MS = 30000L

        // `reader_primary_sitekey` from https://api.lunarx.to/api/captcha/config
        private const val SITEKEY = "0x4AAAAAAEweOgJ6zfOKhkgq"

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"
    }
}
