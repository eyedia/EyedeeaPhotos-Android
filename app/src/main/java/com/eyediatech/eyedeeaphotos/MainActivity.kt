package com.eyediatech.eyedeeaphotos

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.eyediatech.eyedeeaphotos.repository.AuthRepository
import com.eyediatech.eyedeeaphotos.repository.PhotoRepository
import com.eyediatech.eyedeeaphotos.data.AppDatabase
import com.eyediatech.eyedeeaphotos.ui.LoginActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.net.HttpURLConnection
import java.net.URL


class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var authRepository: AuthRepository
    private lateinit var photoRepository: PhotoRepository
    private var settingsIcon: ImageView? = null
    private var settingsButtonContainer: View? = null
    private var queueBadge: TextView? = null
    private val storagePermissionRequestCode = 1001

    // For handling downloads after permission grant
    private var pendingDownloadUrl: String? = null
    private var pendingUserAgent: String? = null
    private var pendingContentDisposition: String? = null
    private var pendingMimetype: String? = null

    companion object {
        private const val PREFS_NAME = "EPPrefs"
        private const val WEBSITE_ADDRESS = "website_address"
        private val WEBSITE_ADDRESS_DEFAULT = BuildConfig.VIEW_URL
        private const val REFRESH_INTERVAL_KEY = "refresh_interval"
        const val SERVER_DOWN = "server_down"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.d("AUTH_DEBUG", "MainActivity onCreate - Authenticated: ${AuthRepository(this).isAuthenticated()}")
        setContentView(R.layout.activity_main)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Initialize shared preferences
        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        authRepository = AuthRepository(this)

        if (!authRepository.isAuthenticated()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // Get saved IP or use default
        val savedIp = sharedPreferences.getString(WEBSITE_ADDRESS, WEBSITE_ADDRESS_DEFAULT) ?: WEBSITE_ADDRESS_DEFAULT
        
        val startUrl = if (authRepository.isAuthenticated()) {
            BuildConfig.BASE_URL + "/library"
        } else {
            savedIp
        }

        // Setup WebView
        setupWebView()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            // Load URL only if there is no saved state
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
        val refreshInterval = sharedPreferences.getInt(REFRESH_INTERVAL_KEY, 60).toLong() * 60000
        handler.postDelayed(runnable, refreshInterval)
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
            val refreshInterval = sharedPreferences.getInt(REFRESH_INTERVAL_KEY, 60).toLong() * 60000
            val currentUrl = webView.url
            if (currentUrl != null) {
                if (currentUrl == "file:///android_asset/error.html") {
                    val startUrl = if (authRepository.isAuthenticated()) {
                        BuildConfig.BASE_URL + "/library"
                    } else {
                        sharedPreferences.getString(WEBSITE_ADDRESS, WEBSITE_ADDRESS_DEFAULT) ?: WEBSITE_ADDRESS_DEFAULT
                    }
                    webView.loadUrl(startUrl)
                } else {
                    webView.reload()
                }
            }
            handler.postDelayed(this, refreshInterval) // Refresh every X minutes
        }
    }

    private fun updateSettingsIconVisibility(url: String?) {
        val isAtView = url?.contains("/view", ignoreCase = true) == true
        Log.d("UI_DEBUG", "Updating icon visibility. URL: $url, isAtView: $isAtView")
        val visibility = if (isAtView) View.GONE else View.VISIBLE
        settingsIcon?.visibility = visibility
        settingsButtonContainer?.visibility = visibility
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = findViewById(R.id.webView)

        // WebView settings
        val webSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true
        webSettings.mediaPlaybackRequiresUserGesture = false

        // Enable downloads
        webSettings.setSupportMultipleWindows(true)
        webSettings.javaScriptCanOpenWindowsAutomatically = true
        webSettings.cacheMode = WebSettings.LOAD_NO_CACHE

        webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // Enable maximum performance
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // --- Download Logic ---
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            handleDownload(url, userAgent, contentDisposition, mimetype)
        }

        // --- Settings Icon for Mobile Flavor ---
        settingsButtonContainer = findViewById(R.id.settingsButtonContainer)
        settingsIcon = findViewById(R.id.settingsIcon)
        queueBadge = findViewById(R.id.queueBadge)

        if (settingsButtonContainer != null) {
            settingsButtonContainer?.setOnClickListener {
                startActivity(Intent(this, com.eyediatech.eyedeeaphotos.ui.SettingsActivity::class.java))
            }

            // Observe queue count
            photoRepository = PhotoRepository(AppDatabase.getDatabase(this).photoDao())
            lifecycleScope.launch {
                photoRepository.allQueuedPhotos.collectLatest { photos ->
                    if (photos.isNotEmpty()) {
                        queueBadge?.visibility = View.VISIBLE
                        queueBadge?.text = if (photos.size > 99) "99+" else photos.size.toString()
                        settingsIcon?.alpha = 1.0f
                    } else {
                        queueBadge?.visibility = View.GONE
                        settingsIcon?.alpha = 0.4f
                    }
                }
            }
        }

        // Custom WebViewClient to handle downloads and navigation
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                updateSettingsIconVisibility(url)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                updateSettingsIconVisibility(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d("AUTH_DEBUG", "onPageFinished: $url")
                
                if (url != "file:///android_asset/error.html") {
                    sharedPreferences.edit { putBoolean(SERVER_DOWN, false) }
                }
                
                updateSettingsIconVisibility(url)

                if (authRepository.isAuthenticated()) {
                    val isAtLogin = url?.contains("/auth/login") == true
                    val isAtRoot = url == BuildConfig.BASE_URL || url == "${BuildConfig.BASE_URL}/"
                    val isAtView = url?.contains("/view") == true
                    
                    if (isAtLogin || isAtRoot || isAtView) {
                        Log.d("AUTH_DEBUG", "Detected login/root/view page while authenticated. Injecting token.")
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
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                    handleLoadError()
                }
            }
        }

        // WebChromeClient for better JavaScript support and logging
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

        // Remote control handling for Fire TV
        webView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER -> {
                        // Simulate click on focused element
                        webView.evaluateJavascript("""
                            var activeElement = document.activeElement;
                            if (activeElement) {
                                activeElement.click();
                            }
                        """.trimIndent(), null)
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_MENU -> {
                        startActivity(Intent(this, com.eyediatech.eyedeeaphotos.ui.SettingsActivity::class.java))
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        val currentUrl = webView.url
                        if (currentUrl?.contains("/view", ignoreCase = true) == true) {
                            webView.loadUrl(BuildConfig.BASE_URL + "/library")
                            return@setOnKeyListener true
                        }
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

    private fun handleDownload(url: String, userAgent: String, contentDisposition: String, mimetype: String) {
        // Check for permissions first
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            // Store download info and request permission
            pendingDownloadUrl = url
            pendingUserAgent = userAgent
            pendingContentDisposition = contentDisposition
            pendingMimetype = mimetype
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), storagePermissionRequestCode)
            return
        }

        // All downloads are now HTTP/HTTPS, so we can use the standard DownloadManager.
        downloadWithManager(url, userAgent, contentDisposition, mimetype)
    }

    private fun downloadWithManager(url: String, userAgent: String, contentDisposition: String, mimetype: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(url))
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)

            request.setMimeType(mimetype)
            request.addRequestHeader("User-Agent", userAgent)
            request.setDescription("Downloading file...")
            request.setTitle(fileName)
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

            val downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)

            Toast.makeText(applicationContext, "Downloading File...", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(applicationContext, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("DOWNLOAD_ERROR", "Error downloading file: ${e.message}")
        }
    }

    private fun injectTokenIntoLocalStorage() {
        val token = authRepository.getToken() ?: return
        val userJson = authRepository.getUserJson() ?: "{}"
        val role = authRepository.getGroup() ?: "user"
        val libraryUrl = BuildConfig.BASE_URL + "/library"

        // Escape backslashes and single quotes for JS
        val escapedUserJson = userJson.replace("\\", "\\\\").replace("'", "\\'")

        val js = """
            (function() {
                try {
                    localStorage.setItem('auth_token', '$token');
                    localStorage.setItem('auth_user', '$escapedUserJson');
                    localStorage.setItem('auth_group', '$role');
                    console.log('Injection successful');
                    window.location.href = '$libraryUrl';
                } catch (e) {
                    console.error('Injection failed: ' + e);
                }
            })();
        """.trimIndent()

        Log.d("AUTH_DEBUG", "Injecting storage and redirecting via JS")
        webView.evaluateJavascript(js, null)
    }

    private fun handleLoadError() {
        runOnUiThread {
            sharedPreferences.edit { putBoolean(SERVER_DOWN, true) }
            webView.loadUrl("file:///android_asset/error.html")
        }
    }
}
