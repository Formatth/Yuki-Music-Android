package com.formatth.yukimusic

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.core.content.ContextCompat
import com.formatth.yukimusic.player.PlaybackService

/**
 * Android shell around the Yuki Music PWA.
 *
 * The web app remains the source of truth for UI and YouTube IFrame playback.
 * Android adds a foreground media service so the WebView process remains
 * important while the user listens in the background, plus native media
 * controls that forward to the WebView player.
 */
class WebViewActivity : Activity() {
    private lateinit var webView: WebView
    private var mainFrameRetries = 0

    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val command = intent.getStringExtra(PlaybackService.EXTRA_COMMAND) ?: return
            val js = "window.__yukiAndroidControl && window.__yukiAndroidControl(${org.json.JSONObject.quote(command)})"
            webView.post { webView.evaluateJavascript(js, null) }
        }
    }

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
        settings.mediaPlaybackRequiresUserGesture = false
        settings.loadsImagesAutomatically = true
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.setSupportZoom(false)
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.allowFileAccess = false
        settings.allowContentAccess = false

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
                if (request.isForMainFrame && errorResponse.statusCode >= 400) retryMainFrame()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val host = request.url.host ?: return false
                return host != "yuki-music-pwa.vercel.app" &&
                    host != "yuki-music-backend.vercel.app" &&
                    host != "www.youtube.com" &&
                    host != "youtube.com" &&
                    host != "music.youtube.com"
            }
        }

        webView.addJavascriptInterface(AndroidBridge(this), "YukiAndroid")

        val filter = IntentFilter(PlaybackService.ACTION_CONTROL)
        ContextCompat.registerReceiver(this, playbackReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        webView.loadUrl(APP_URL)
    }

    private fun retryMainFrame() {
        if (mainFrameRetries >= MAX_MAIN_FRAME_RETRIES) return
        mainFrameRetries++
        webView.postDelayed({
            if (!isFinishing && !isDestroyed) webView.loadUrl(APP_URL)
        }, 350L)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onPause() {
        // Do not call WebView.onPause(): that would intentionally pause the
        // YouTube WebView audio pipeline when the Activity is backgrounded.
        super.onPause()
    }

    override fun onDestroy() {
        try { unregisterReceiver(playbackReceiver) } catch (_: Exception) {}
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

        @android.webkit.JavascriptInterface
        fun updatePlayback(title: String, artist: String, artwork: String, playing: Boolean) {
            PlaybackService.update(activity.applicationContext, title, artist, artwork, playing)
        }

        @android.webkit.JavascriptInterface
        fun stopPlayback() {
            PlaybackService.stop(activity.applicationContext)
        }
    }

    companion object {
        private const val APP_URL = "https://yuki-music-pwa.vercel.app/#/home"
        private const val MAX_MAIN_FRAME_RETRIES = 2
    }
}
