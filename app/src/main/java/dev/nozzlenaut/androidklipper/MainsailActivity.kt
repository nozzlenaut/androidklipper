package dev.nozzlenaut.androidklipper

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

class MainsailActivity : Activity() {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Some Android devices suspend or destabilize USB host traffic when the
        // display sleeps; others can keep printing safely and save meaningful
        // battery by allowing normal screen timeout. Keep the old behavior by
        // default, but make it a user-selectable compatibility setting.
        val keepScreenAwake = getSharedPreferences(
            KlipperHostService.PREF_AUTOMATION,
            Context.MODE_PRIVATE
        ).getBoolean(KlipperHostService.KEY_KEEP_MAINSAIL_SCREEN_AWAKE, true)
        if (keepScreenAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        webView = WebView(this).apply {
            webViewClient = WebViewClient()
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Every Mainsail asset is served locally from the APK. Avoid WebView
            // HTTP cache reuse across APK updates so hashed JS chunks and the
            // config editor always match the installed build.
            clearCache(true)
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            loadUrl("http://127.0.0.1:8080/")
        }
        setContentView(webView)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
