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

import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : FragmentActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var authRepository: AuthRepository
    private var tokenInjectionCount = 0
    private var isAtViewPage = false
    private var isInfoOpen = false

    private fun updateKeepAwake(url: String?) {
        val isAtView = url?.contains("/view", ignoreCase = true) == true
        if (isAtView == isAtViewPage) return
        isAtViewPage = isAtView
        if (!isAtView) {
            isInfoOpen = false
        }
        if (isAtView) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
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

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: android.view.KeyEvent?): Boolean {
        if (event?.action == android.view.KeyEvent.ACTION_DOWN) {
            val keyCode = event.keyCode
            if (keyCode == android.view.KeyEvent.KEYCODE_MENU) {
                startActivity(Intent(this, com.eyediatech.eyedeeaphotos.ui.SettingsActivity::class.java))
                return true
            }
            if (isAtViewPage && event.repeatCount == 0) {
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER || keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    isInfoOpen = !isInfoOpen
                    binding.webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('eyedeea:view:action', { detail: { action: 'toggle_info' } }));", null)
                    return true
                }
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK && isInfoOpen) {
                    isInfoOpen = false
                    binding.webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('eyedeea:view:action', { detail: { action: 'close_info' } }));", null)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webSettings = binding.webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true
        webSettings.userAgentString = com.eyediatech.eyedeeaphotos.utils.UserAgentUtils.getUserAgent(this)
        
        // binding.webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

        binding.webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    Log.d("WEB_CONSOLE", "${it.messageLevel()}: ${it.message()} -- From line ${it.lineNumber()} of ${it.sourceId()}")
                }
                return true
            }
        }

        // Setup JS Bridge for Offline Sync
        val syncCoordinator = com.eyediatech.eyedeeaphotos.sync.OfflineSyncCoordinator(this)
        binding.webView.addJavascriptInterface(
            com.eyediatech.eyedeeaphotos.bridge.EyedeeaPhotosJsBridge(syncCoordinator),
            "EyedeeaPhotosNativeBridge"
        )

        binding.webView.webViewClient = object : android.webkit.WebViewClient() {
            private fun checkUrlAndInjectToken(view: WebView?, url: String?) {
                if (url == null) return
                if (authRepository.isAuthenticated()) {
                    val uri = try { android.net.Uri.parse(url) } catch (e: Exception) { null }
                    if (uri != null) {
                        val baseUrlUri = android.net.Uri.parse(BuildConfig.BASE_URL)
                        
                        if (uri.host?.endsWith("eyedeeaphotos.com") == true || uri.host == baseUrlUri.host) {
                            val path = uri.path ?: ""
                            val isUnauthenticatedPage = path == "" || path == "/" || path.startsWith("/login") || path.startsWith("/app-login") || path.startsWith("/home") || path.startsWith("/auth")
                            
                            if (isUnauthenticatedPage) {
                                Log.d("AUTH_DEBUG", "Detected unauthenticated page while authenticated locally. URL: $url")
                                view?.visibility = android.view.View.INVISIBLE
                                view?.post {
                                    injectTokenIntoLocalStorage(view)
                                }
                            } else {
                                view?.visibility = android.view.View.VISIBLE
                            }
                        } else {
                            view?.visibility = android.view.View.VISIBLE
                        }
                    } else {
                        view?.visibility = android.view.View.VISIBLE
                    }
                }
            }

            @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.LOLLIPOP)
            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                val uri = request?.url
                if (uri?.scheme == "eyedeea" && uri.host == "view") {
                    val action = uri.getQueryParameter("action")
                    if (action == "toggle_info") {
                        isInfoOpen = !isInfoOpen
                        view?.evaluateJavascript("window.dispatchEvent(new CustomEvent('eyedeea:view:action', { detail: { action: 'toggle_info' } }));", null)
                        return true
                    } else if (action == "close_info") {
                        isInfoOpen = false
                        view?.evaluateJavascript("window.dispatchEvent(new CustomEvent('eyedeea:view:action', { detail: { action: 'close_info' } }));", null)
                        return true
                    }
                }
                if (uri != null && authRepository.isAuthenticated()) {
                    val baseUrlUri = android.net.Uri.parse(BuildConfig.BASE_URL)
                    if (uri.host?.endsWith("eyedeeaphotos.com") == true || uri.host == baseUrlUri.host) {
                        val path = uri.path ?: ""
                        if (path == "" || path == "/" || path.startsWith("/login") || path.startsWith("/app-login") || path.startsWith("/home") || path.startsWith("/auth")) {
                            Log.d("AUTH_DEBUG", "Intercepted unauthenticated page in shouldOverrideUrlLoading. URL: ${request.url}")
                            view?.visibility = android.view.View.INVISIBLE
                            view?.post {
                                injectTokenIntoLocalStorage(view)
                            }
                            return true
                        }
                    }
                }
                return super.shouldOverrideUrlLoading(view, request)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url != null) {
                    try {
                        val uri = android.net.Uri.parse(url)
                        if (uri.scheme == "eyedeea" && uri.host == "view") {
                            val action = uri.getQueryParameter("action")
                            if (action == "toggle_info") {
                                isInfoOpen = !isInfoOpen
                                view?.evaluateJavascript("window.dispatchEvent(new CustomEvent('eyedeea:view:action', { detail: { action: 'toggle_info' } }));", null)
                                return true
                            } else if (action == "close_info") {
                                isInfoOpen = false
                                view?.evaluateJavascript("window.dispatchEvent(new CustomEvent('eyedeea:view:action', { detail: { action: 'close_info' } }));", null)
                                return true
                            }
                        }
                        if (authRepository.isAuthenticated()) {
                            val baseUrlUri = android.net.Uri.parse(BuildConfig.BASE_URL)
                            if (uri.host?.endsWith("eyedeeaphotos.com") == true || uri.host == baseUrlUri.host) {
                                val path = uri.path ?: ""
                                if (path == "" || path == "/" || path.startsWith("/login") || path.startsWith("/app-login") || path.startsWith("/home") || path.startsWith("/auth")) {
                                    Log.d("AUTH_DEBUG", "Intercepted unauthenticated page in shouldOverrideUrlLoading (deprecated). URL: $url")
                                    view?.visibility = android.view.View.INVISIBLE
                                    view?.post {
                                        injectTokenIntoLocalStorage(view)
                                    }
                                    return true
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore parse errors and let the WebView handle it
                    }
                }
                return super.shouldOverrideUrlLoading(view, url)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                updateKeepAwake(url)
                
                if (url != null && authRepository.isAuthenticated()) {
                    try {
                        val uri = android.net.Uri.parse(url)
                        val baseUrlUri = android.net.Uri.parse(BuildConfig.BASE_URL)
                        if (uri.host?.endsWith("eyedeeaphotos.com") == true || uri.host == baseUrlUri.host) {
                            view?.post {
                                injectTokenOnly(view)
                            }
                        }
                    } catch (e: Exception) {}
                }

                checkUrlAndInjectToken(view, url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.visibility = android.view.View.GONE
                updateKeepAwake(url)
                checkUrlAndInjectToken(view, url)
            }

            @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.M)
            override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    val msg = error?.description.toString()
                    Log.e("WEBVIEW_DEBUG", "Error: $msg")
                    handleLoadError()
                }
            }

            @Suppress("OverridingDeprecatedMember", "DEPRECATION")
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) {
                    Log.e("WEBVIEW_DEBUG", "Error: $description")
                    handleLoadError()
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                Log.e("WEBVIEW_DEBUG", "SSL Error: ${error?.primaryError}")
                // It is unsafe to unconditionally call handler.proceed() in release builds.
                // It's common for Fire TVs to have the wrong date/time, leading to ERR_CERT_DATE_INVALID.
                android.widget.Toast.makeText(
                    this@MainActivity, 
                    "Security/SSL Error. Please check your device's date and time.", 
                    android.widget.Toast.LENGTH_LONG
                ).show()
                handler?.cancel()
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                updateKeepAwake(url)
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

    private fun injectTokenOnly(webView: WebView?) {
        val token = authRepository.getToken() ?: return
        val refreshToken = authRepository.getRefreshToken() ?: ""
        val userJsonRaw = authRepository.getUserJson()
        val userJson = if (userJsonRaw.isNullOrBlank()) "{}" else userJsonRaw
        val role = authRepository.getGroup() ?: "user"

        val escapedUserJson = userJson.replace("\\", "\\\\").replace("'", "\\'")

        val js = """
            (function() {
                try {
                    localStorage.setItem('auth_token', '$token');
                    if ('$refreshToken' !== '') {
                        localStorage.setItem('refresh_token', '$refreshToken');
                    }
                    var userObj = {};
                    try {
                        userObj = JSON.parse('$escapedUserJson');
                    } catch (e) {
                        console.error('Failed to parse user JSON', e);
                    }
                    userObj.role = '$role';
                    localStorage.setItem('auth_user', JSON.stringify(userObj));
                    localStorage.setItem('auth_group', '$role');
                    console.log('Proactive token injection successful');
                } catch (e) {
                    console.error('Proactive injection failed: ' + e);
                }
            })();
        """.trimIndent()

        webView?.evaluateJavascript(js, null)
    }

    private fun handleLoadError() {
        runOnUiThread {
            binding.webView.loadUrl("file:///android_asset/error.html")
        }
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
        val userJsonRaw = authRepository.getUserJson()
        val userJson = if (userJsonRaw.isNullOrBlank()) "{}" else userJsonRaw
        val role = authRepository.getGroup() ?: "user"
        
        val baseUrl = BuildConfig.BASE_URL.removeSuffix("/")
        val targetUrl = if (role == "user" || role == "auth_user" || role == "public_user") {
            "$baseUrl/pricing"
        } else {
            BuildConfig.VIEW_URL
        }

        // Escape backslashes and single quotes for JS
        val escapedUserJson = userJson.replace("\\", "\\\\").replace("'", "\\'")

        val js = """
            (function() {
                try {
                    localStorage.setItem('auth_token', '$token');
                    if ('$refreshToken' !== '') {
                        localStorage.setItem('refresh_token', '$refreshToken');
                    }
                    var userObj = {};
                    try {
                        userObj = JSON.parse('$escapedUserJson');
                    } catch (e) {
                        console.error('Failed to parse user JSON', e);
                    }
                    userObj.role = '$role';
                    localStorage.setItem('auth_user', JSON.stringify(userObj));
                    localStorage.setItem('auth_group', '$role');
                    console.log('Injection successful');
                    window.location.replace('$targetUrl');
                } catch (e) {
                    console.error('Injection failed: ' + e);
                }
            })();
        """.trimIndent()

        Log.d("AUTH_DEBUG", "Injecting storage and redirecting via JS")
        webView?.evaluateJavascript(js, null)
    }
}