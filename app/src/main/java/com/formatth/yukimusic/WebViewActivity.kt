package com.formatth.yukimusic

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

/**
 * Thin Android shell around the Yuki Music web application.
 *
 * The UI stays in Yuki-Music-PWA so web deployments can update the interface
 * without rebuilding the APK. Android owns the app window/lifecycle while the
 * existing web player remains the source of truth for the UI.
 */
class WebViewActivity : Activity() {
    private lateinit var webView: WebView

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
        settings.mediaPlaybackRequiresUserGesture = true
        settings.loadsImagesAutomatically = true
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.userAgentString = "YukiMusicAndroid/1.0 ${settings.userAgentString}"

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.setBackgroundColor(Color.BLACK)
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
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

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onPause() {
        // Do not call WebView.onPause(): doing so would deliberately pause the
        // web audio pipeline. The native media-session layer can be added later
        // without changing the web UI.
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
            activity.runOnUiThread { Toast.makeText(activity, message, Toast.LENGTH_SHORT).show() }
        }
    }

    companion object {
        private const val APP_URL = "https://yuki-music-pwa.vercel.app/#home"
    }
}
