package com.eyediatech.eyedeeaphotos

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.*
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.eyediatech.eyedeeaphotos.repository.AuthRepository
import com.eyediatech.eyedeeaphotos.ui.LoginActivity
import com.eyediatech.eyedeeaphotos.ui.SettingsActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var authRepository: AuthRepository
    private var settingsButtonContainer: View? = null

    companion object {
        private const val PREFS_NAME = "EPPrefs"
        private const val REFRESH_INTERVAL_KEY = "refresh_interval"
        const val SERVER_DOWN = "server_down"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        authRepository = AuthRepository(this)

        if (!authRepository.isAuthenticated()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        val startUrl = BuildConfig.VIEW_URL

        setupWebView()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            try {
                Log.d("WEBVIEW_DEBUG", "Loading: $startUrl")
                webView.loadUrl(startUrl)
            } catch (e: Exception) {
                Log.e("WEBVIEW_DEBUG", "Load error: ${e.message}")
                handleLoadError()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        val serviceIntent = Intent(this, KeepAwakeService::class.java) 
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { 
            startForegroundService(serviceIntent) 
        } else { 
            startService(serviceIntent) 
        } 
        val refreshIntervalMinutes = sharedPreferences.getInt(REFRESH_INTERVAL_KEY, 60)
        if (refreshIntervalMinutes > 0) {
            val refreshInterval = refreshIntervalMinutes.toLong() * 60000
            handler.postDelayed(runnable, refreshInterval)
        }
    }

    override fun onPause() {
        super.onPause()
        val serviceIntent = Intent(this, KeepAwakeService::class.java) 
        stopService(serviceIntent) 
        handler.removeCallbacks(runnable)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val runnable = object : Runnable {
        override fun run() {
            val refreshIntervalMinutes = sharedPreferences.getInt(REFRESH_INTERVAL_KEY, 60)
            if (refreshIntervalMinutes <= 0) return
            
            val refreshInterval = refreshIntervalMinutes.toLong() * 60000
            val currentUrl = webView.url
            if (currentUrl != null) {
                if (currentUrl == "file:///android_asset/error.html") {
                    webView.loadUrl(BuildConfig.VIEW_URL)
                } else {
                    webView.reload()
                }
            }
            handler.postDelayed(this, refreshInterval)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = findViewById(R.id.webView)

        val webSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.mediaPlaybackRequiresUserGesture = false
        webSettings.cacheMode = WebSettings.LOAD_NO_CACHE
        webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        settingsButtonContainer = findViewById(R.id.settingsButtonContainer)
        settingsButtonContainer?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url != "file:///android_asset/error.html") {
                    sharedPreferences.edit { putBoolean(SERVER_DOWN, false) }
                }

                if (authRepository.isAuthenticated()) {
                    val isAtLogin = url?.contains("/auth/login") == true
                    if (isAtLogin) {
                        injectTokenIntoLocalStorage()
                    }
                }
            }

            @RequiresApi(Build.VERSION_CODES.M)
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    handleLoadError()
                }
            }

            @Suppress("OverridingDeprecatedMember", "DEPRECATION")
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                    handleLoadError()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (BuildConfig.ENABLE_WEB_CONSOLE_LOG) {
                    consoleMessage?.let {
                        Log.d("WEB_CONSOLE", "${it.messageLevel()}: ${it.message()} -- From line ${it.lineNumber()} of ${it.sourceId()}")
                    }
                }
                return true
            }
        }

        webView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER -> {
                        webView.evaluateJavascript("document.activeElement.click()", null)
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_MENU -> {
                        startActivity(Intent(this, SettingsActivity::class.java))
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        if (webView.canGoBack()) {
                            webView.goBack()
                        } else {
                            finish()
                        }
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }
    }

    private fun injectTokenIntoLocalStorage() {
        val token = authRepository.getToken() ?: return
        val userJson = authRepository.getUserJson() ?: "{}"
        val role = authRepository.getGroup() ?: "user"
        val viewUrl = BuildConfig.VIEW_URL

        val escapedUserJson = userJson.replace("\\", "\\\\").replace("'", "\\'")

        val js = """
            (function() {
                localStorage.setItem('auth_token', '$token');
                localStorage.setItem('auth_user', '$escapedUserJson');
                localStorage.setItem('auth_group', '$role');
                window.location.href = '$viewUrl';
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    private fun handleLoadError() {
        runOnUiThread {
            sharedPreferences.edit { putBoolean(SERVER_DOWN, true) }
            webView.loadUrl("file:///android_asset/error.html")
        }
    }
}
