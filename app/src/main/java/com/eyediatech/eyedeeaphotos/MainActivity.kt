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
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.eyediatech.eyedeeaphotos.repository.AuthRepository
import com.eyediatech.eyedeeaphotos.ui.LoginActivity
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL


class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var authRepository: AuthRepository
    private var isTokenInjected = false
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        // Setup WebView
        setupWebView()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            // Load URL only if there is no saved state
            try {
                Log.d("WEBVIEW_DEBUG", "Loading: $savedIp")
                webView.loadUrl(savedIp)
            } catch (e: Exception) {
                Log.e("WEBVIEW_DEBUG", "Load error: ${e.message}")
                handleLoadError()
            }
        }
    }

    private fun injectAuthCookie() {
        // No longer used based on API team feedback
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
        handler.post(runnable)
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
            if (webView.url != null) {
                webView.reload() // or webView.loadUrl(webView.url)
            }
            handler.postDelayed(this, refreshInterval) // Refresh every X minutes
        }
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
        if (BuildConfig.ENABLE_DOWNLOADS) {
            webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                handleDownload(url, userAgent, contentDisposition, mimetype)
            }
        }

        // --- Settings Icon for Mobile Flavor ---
        if (BuildConfig.FLAVOR == "mobile") {
            val resId = resources.getIdentifier("settingsIcon", "id", packageName)
            if (resId != 0) {
                val settingsIcon = findViewById<ImageView>(resId)
                settingsIcon.setOnClickListener {
                    showSettingsDialog()
                }
            }
        }

        // Custom WebViewClient to handle downloads and navigation
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!isTokenInjected) {
                    injectTokenIntoLocalStorage()
                    isTokenInjected = true
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
                handleLoadError()
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
                        showSettingsDialog()
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

    fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val websiteAddressInput = dialogView.findViewById<EditText>(R.id.ipEditText)
        val refreshInput = dialogView.findViewById<EditText>(R.id.refreshEditText)
        val userTextView = dialogView.findViewById<TextView>(R.id.userTextView)
        val btnLogout = dialogView.findViewById<Button>(R.id.btnLogout)

        websiteAddressInput.setText(sharedPreferences.getString(WEBSITE_ADDRESS, WEBSITE_ADDRESS_DEFAULT))
        refreshInput.setText(sharedPreferences.getInt(REFRESH_INTERVAL_KEY, 10).toString())
        userTextView.text = "Logged in as: ${authRepository.getUsername()}"

        val alertDialog = AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(dialogView)
            .create()

        alertDialog.setOnShowListener {
            val btnSave = dialogView.findViewById<Button>(R.id.btnSave)
            val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

            btnSave.setOnClickListener {
                val websiteAddress = websiteAddressInput.text.toString().trim()
                val refreshInterval = refreshInput.text.toString().toIntOrNull() ?: 10

                CoroutineScope(Dispatchers.Main).launch {
                    val validUrl = isValidUrl(websiteAddress)
                    val validInterval = isValidRefreshInterval(refreshInterval)
                    if (validUrl && validInterval) {
                        saveSettings(websiteAddress, refreshInterval)
                        webView.loadUrl(websiteAddress)
                        alertDialog.dismiss()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Please enter a valid website address and refresh interval (1-300).",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            btnCancel.setOnClickListener {
                alertDialog.dismiss()
            }
            btnLogout.setOnClickListener {
                authRepository.clearAuthData()
                startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                finish()
                alertDialog.dismiss()
            }
        }
        alertDialog.show()
    }

    private fun saveSettings(websiteAddress: String, refreshInterval: Int) {
        sharedPreferences.edit(commit = true) {
            putString(WEBSITE_ADDRESS, websiteAddress)
            putInt(REFRESH_INTERVAL_KEY, refreshInterval)
        }
    }

    private suspend fun isValidUrl(url: String): Boolean {
        return if (isValidUrlFormat(url)) {
            isUrlAccessible(url)
        } else {
            false
        }
    }

    private fun isValidUrlFormat(url: String): Boolean {
        val regex = """^(https?://(?:[a-zA-Z0-9.-]+|[0-9]{1,3}(?:\.[0-9]{1,3}){3})(?::\d{1,5})?(?:/.*)?)$""".toRegex()
        return regex.matches(url)
    }

    private suspend fun isUrlAccessible(urlString: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL(urlString)
            val urlConnection = url.openConnection() as HttpURLConnection
            urlConnection.requestMethod = "GET"
            urlConnection.connectTimeout = 5000
            urlConnection.readTimeout = 5000
            urlConnection.connect()
            val responseCode = urlConnection.responseCode
            responseCode in 200..399
        } catch (e: Exception) {
            false
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
            localStorage.setItem('auth_token', '$token');
            localStorage.setItem('auth_user', '$escapedUserJson');
            localStorage.setItem('auth_group', '$role');
        """.trimIndent()

        Log.d("AUTH_DEBUG", "Injecting storage according to web team specs")
        webView.evaluateJavascript(js) {
            Log.d("AUTH_DEBUG", "Storage injected. Navigating to /library")
            webView.loadUrl(libraryUrl)
        }
    }

    private fun handleLoadError() {
        runOnUiThread {
            Toast.makeText(this, "Could not load website. Please check the address.", Toast.LENGTH_LONG).show()
            showSettingsDialog()
        }
    }

    private fun isValidRefreshInterval(interval: Int): Boolean {
        return interval in 1..300
    }
}
