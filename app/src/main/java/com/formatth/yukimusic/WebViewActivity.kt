package com.formatth.yukimusic

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.formatth.yukimusic.player.NativePlaybackService
import com.formatth.yukimusic.player.PlaybackService

/**
 * Android shell around the Yuki Music PWA.
 *
 * The web app remains the source of truth for UI. Native Media3 playback is
 * available through the bridge for a playable/authorized media URL and is
 * independent from the Activity/WebView lifecycle.
 */
class WebViewActivity : Activity() {
    private lateinit var webView: WebView
    private var mainFrameRetries = 0
    private var notificationPermissionRequested = false

    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val command = intent.getStringExtra(PlaybackService.EXTRA_COMMAND) ?: return
            val js = "window.__yukiAndroidControl && window.__yukiAndroidControl(${org.json.JSONObject.quote(command)})"
            if (::webView.isInitialized) webView.post { webView.evaluateJavascript(js, null) }
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
                errorResponse: WebResourceResponse
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

    private fun requestNotificationPermissionIfNeeded() {
        if (notificationPermissionRequested || Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        notificationPermissionRequested = true
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
    }

    private fun retryMainFrame() {
        if (mainFrameRetries >= MAX_MAIN_FRAME_RETRIES) return
        mainFrameRetries++
        webView.postDelayed({
            if (!isFinishing && !isDestroyed) webView.loadUrl(APP_URL)
        }, 350L)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else moveTaskToBack(true)
    }

    override fun onPause() {
        // Native Media3 playback does not depend on this Activity.
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onDestroy() {
        try { unregisterReceiver(playbackReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    class AndroidBridge(private val activity: WebViewActivity) {
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
            activity.runOnUiThread { activity.requestNotificationPermissionIfNeeded() }
            PlaybackService.update(activity.applicationContext, title, artist, artwork, playing)
        }

        @android.webkit.JavascriptInterface
        fun playNativeMedia(url: String, title: String, artist: String, artwork: String) {
            val safeUrl = url.trim()
            if (safeUrl.isBlank() || !safeUrl.startsWith("https://")) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "Native player needs a playable HTTPS media URL", Toast.LENGTH_SHORT).show()
                }
                return
            }

            activity.runOnUiThread { activity.requestNotificationPermissionIfNeeded() }
            startNativeService(NativePlaybackService.ACTION_PLAY_URL) {
                putExtra(NativePlaybackService.EXTRA_URL, safeUrl)
                putExtra(NativePlaybackService.EXTRA_TITLE, title)
                putExtra(NativePlaybackService.EXTRA_ARTIST, artist)
                putExtra(NativePlaybackService.EXTRA_ARTWORK, artwork)
            }
        }

        /** Temporary proof-of-concept test using a public MP3 sample. */
        @android.webkit.JavascriptInterface
        fun playNativeTest() {
            activity.runOnUiThread { activity.requestNotificationPermissionIfNeeded() }
            startNativeService(NativePlaybackService.ACTION_PLAY_TEST) {}
        }

        @android.webkit.JavascriptInterface
        fun nativePause() = sendNativeCommand(NativePlaybackService.ACTION_PAUSE)

        @android.webkit.JavascriptInterface
        fun nativePlay() = sendNativeCommand(NativePlaybackService.ACTION_PLAY)

        @android.webkit.JavascriptInterface
        fun nativeNext() = sendNativeCommand(NativePlaybackService.ACTION_NEXT)

        @android.webkit.JavascriptInterface
        fun nativePrevious() = sendNativeCommand(NativePlaybackService.ACTION_PREVIOUS)

        @android.webkit.JavascriptInterface
        fun nativeStop() = sendNativeCommand(NativePlaybackService.ACTION_STOP)

        private fun sendNativeCommand(action: String) {
            startNativeService(action) {}
        }

        private fun startNativeService(
            action: String,
            extras: Intent.() -> Unit
        ) {
            val intent = Intent(activity.applicationContext, NativePlaybackService::class.java)
                .setAction(action)
                .apply(extras)

            if (Build.VERSION.SDK_INT >= 26) {
                ContextCompat.startForegroundService(activity.applicationContext, intent)
            } else {
                activity.applicationContext.startService(intent)
            }
        }

        @android.webkit.JavascriptInterface
        fun stopPlayback() {
            PlaybackService.stop(activity.applicationContext)
        }
    }

    companion object {
        private const val APP_URL = "https://yuki-music-pwa.vercel.app/#/home"
        private const val MAX_MAIN_FRAME_RETRIES = 2
        private const val NOTIFICATION_PERMISSION_REQUEST = 9001
    }
}
