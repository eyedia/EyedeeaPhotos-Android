package com.eyediatech.eyedeeaphotos

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import androidx.fragment.app.FragmentActivity
import com.eyediatech.eyedeeaphotos.databinding.ActivityMainBinding
import com.eyediatech.eyedeeaphotos.repository.AuthRepository
import com.eyediatech.eyedeeaphotos.ui.LoginActivity

class MainActivity : FragmentActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var authRepository: AuthRepository
    private var tokenInjectionCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        authRepository = AuthRepository(this)
        if (!authRepository.isAuthenticated()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupWebView()
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_MENU) {
            startActivity(Intent(this, com.eyediatech.eyedeeaphotos.ui.SettingsActivity::class.java))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webSettings = binding.webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true
        
        // binding.webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

        binding.webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    Log.d("WEB_CONSOLE", "${it.messageLevel()}: ${it.message()} -- From line ${it.lineNumber()} of ${it.sourceId()}")
                }
                return true
            }
        }

        binding.webView.webViewClient = object : android.webkit.WebViewClient() {
            private fun checkUrlAndInjectToken(view: WebView?, url: String?) {
                if (authRepository.isAuthenticated()) {
                    val isAtLogin = url?.contains("/auth/login") == true
                    val baseUrl = BuildConfig.BASE_URL.removeSuffix("/")
                    val urlWithoutQuery = url?.substringBefore("?")?.removeSuffix("/")
                    val isAtRoot = urlWithoutQuery == baseUrl
                    
                    if (isAtLogin || isAtRoot) {
                        Log.d("AUTH_DEBUG", "Detected login/root page while authenticated. Injecting token. URL: $url")
                        injectTokenIntoLocalStorage(view)
                    }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                checkUrlAndInjectToken(view, url)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                checkUrlAndInjectToken(view, url)
            }
            
            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                Log.e("WEBVIEW_DEBUG", "Render process gone")
                // Recreate the activity if the webview crashes
                finish()
                startActivity(intent)
                return true
            }
        }

        // Start at the view page. If not authenticated, the web app will redirect to login/root,
        // which we catch in onPageFinished, inject the token, and redirect back.
        binding.webView.loadUrl(BuildConfig.VIEW_URL)
    }

    private fun injectTokenIntoLocalStorage(webView: WebView?) {
        if (tokenInjectionCount > 2) {
            Log.e("AUTH_DEBUG", "Token injection loop detected. Logging out.")
            authRepository.clearAuthData()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        tokenInjectionCount++

        val token = authRepository.getToken() ?: return
        val refreshToken = authRepository.getRefreshToken() ?: ""
        val userJson = authRepository.getUserJson() ?: "{}"
        val role = authRepository.getGroup() ?: "user"
        val viewUrl = BuildConfig.VIEW_URL

        // Escape backslashes and single quotes for JS
        val escapedUserJson = userJson.replace("\\", "\\\\").replace("'", "\\'")

        val js = """
            (function() {
                try {
                    localStorage.setItem('auth_token', '$token');
                    if ('$refreshToken' !== '') {
                        localStorage.setItem('refresh_token', '$refreshToken');
                    }
                    localStorage.setItem('auth_user', '$escapedUserJson');
                    localStorage.setItem('auth_group', '$role');
                    console.log('Injection successful');
                    window.location.replace('$viewUrl');
                } catch (e) {
                    console.error('Injection failed: ' + e);
                }
            })();
        """.trimIndent()

        Log.d("AUTH_DEBUG", "Injecting storage and redirecting via JS")
        webView?.evaluateJavascript(js, null)
    }
}