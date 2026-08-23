package com.formatth.yukimusic

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

/**
 * Android shell around the Yuki Music PWA.
 *
 * The web app remains the source of truth for the UI and YouTube IFrame
 * playback. Android owns the window/lifecycle while this WebView provides the
 * browser capabilities required by the PWA.
 */
class WebViewActivity : Activity() {
    private lateinit var webView: WebView
    private var mainFrameRetries = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.decorView.systemUiVisibility = 0

        webView = WebView(this)
        setContentView(webView)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true

        // IMPORTANT: Yuki Music starts the YouTube IFrame after an async
        // network/API step. Keeping this true causes Chromium/WebView to reject
        // playVideo() because the original tap gesture has already ended.
        settings.mediaPlaybackRequiresUserGesture = false

        settings.loadsImagesAutomatically = true
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.userAgentString = "YukiMusicAndroid/1.1 ${settings.userAgentString}"

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.setBackgroundColor(Color.BLACK)
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                mainFrameRetries = 0
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) retryMainFrame()
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: android.webkit.WebResourceResponse
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                // Vercel can occasionally return a transient 404/5xx while a
                // deployment is warming. Only retry errors for the main page;
                // never reload because an image/API resource failed.
                if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                    retryMainFrame()
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host ?: return false
                return host != "yuki-music-pwa.vercel.app" &&
                    host != "yuki-music-backend.vercel.app" &&
                    host != "www.youtube.com" &&
                    host != "music.youtube.com" &&
                    host != "youtube.com"
            }
        }

        webView.addJavascriptInterface(AndroidBridge(this), "YukiAndroid")
        webView.loadUrl(APP_URL)
    }

    private fun retryMainFrame() {
        if (mainFrameRetries >= MAX_MAIN_FRAME_RETRIES) return
        mainFrameRetries++
        webView.postDelayed({
            if (!isFinishing && !isDestroyed) webView.loadUrl(APP_URL)
        }, 600L)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onPause() {
        // Do not call WebView.onPause(): that would intentionally pause the
        // web audio pipeline when the Activity is backgrounded.
        super.onPause()
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.removeAllViews()
        webView.destroy()
        super.onDestroy()
    }

    class AndroidBridge(private val activity: Activity) {
        @android.webkit.JavascriptInterface
        fun getPlatform(): String = "android-webview"

        @android.webkit.JavascriptInterface
        fun showToast(message: String) {
            activity.runOnUiThread {
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val APP_URL = "https://yuki-music-pwa.vercel.app/#home"
        private const val MAX_MAIN_FRAME_RETRIES = 2
    }
}
