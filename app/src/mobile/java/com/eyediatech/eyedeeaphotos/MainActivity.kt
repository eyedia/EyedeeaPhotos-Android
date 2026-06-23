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
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
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


import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var authRepository: AuthRepository
    private lateinit var photoRepository: PhotoRepository
    private var tokenInjectionCount = 0
    private var settingsIcon: ImageView? = null
    private var settingsButtonContainer: View? = null
    private var expandedMenuContainer: View? = null
    private var uploadIcon: View? = null
    private var settingsActionIcon: View? = null
    private var isMenuExpanded = false
    private var queueBadge: TextView? = null
    private var settingsActionBadge: TextView? = null
    private val storagePermissionRequestCode = 1001

    private val pickImagesLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            queuePhotos(uris)
        }
    }

    private val requestLocationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { _ ->
        pickImagesLauncher.launch("image/*")
    }

    private fun checkLocationPermissionAndPickImages() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_MEDIA_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestLocationPermissionLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
        } else {
            pickImagesLauncher.launch("image/*")
        }
    }

    private fun queuePhotos(uris: List<Uri>) {
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "Your photos are queued and will sync soon.", Toast.LENGTH_LONG).show()
            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    val internalFile = copyToInternalStorage(uri)
                    if (internalFile != null) {
                        val queuedPhoto = com.eyediatech.eyedeeaphotos.data.QueuedPhoto(
                            fileUri = uri.toString(),
                            internalPath = internalFile.absolutePath,
                            fileName = internalFile.name
                        )
                        photoRepository.insert(queuedPhoto)
                    }
                }
            }
        }
    }

    private suspend fun copyToInternalStorage(uri: Uri): java.io.File? = withContext(Dispatchers.IO) {
        try {
            var fileName = ""
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex)
                }
            }
            
            if (fileName.isEmpty()) {
                fileName = "photo_${System.currentTimeMillis()}_${(0..1000).random()}.jpg"
            }
            
            val fileDir = java.io.File(filesDir, "queue")
            if (!fileDir.exists()) fileDir.mkdirs()
            
            var finalFile = java.io.File(fileDir, fileName)
            if (finalFile.exists()) {
                val nameWithoutExt = fileName.substringBeforeLast(".")
                val ext = fileName.substringAfterLast(".", "jpg")
                finalFile = java.io.File(fileDir, "${nameWithoutExt}_${System.currentTimeMillis()}.$ext")
            }

            var finalUri = uri
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.ACCESS_MEDIA_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    try {
                        if (uri.authority == android.provider.MediaStore.AUTHORITY) {
                            finalUri = android.provider.MediaStore.setRequireOriginal(uri)
                        }
                    } catch (e: Exception) {
                        finalUri = uri
                    }
                }
            }

            contentResolver.openInputStream(finalUri)?.use { input ->
                finalFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            finalFile
        } catch (_: Exception) {
            null
        }
    }

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
        installSplashScreen()
        super.onCreate(savedInstanceState)
        
        android.util.Log.d("AUTH_DEBUG", "MainActivity onCreate - Authenticated: ${AuthRepository(this).isAuthenticated()}")
        setContentView(R.layout.activity_main)

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
        
        val baseUrl = BuildConfig.BASE_URL.removeSuffix("/")
        val startUrl = if (authRepository.isAuthenticated()) {
            "$baseUrl/library"
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
        if (isAtViewPage) {
            val serviceIntent = Intent(this, KeepAwakeService::class.java) 
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { 
                startForegroundService(serviceIntent) 
            } else { 
                startService(serviceIntent) 
            } 
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
            if (refreshIntervalMinutes <= 0) return // Prevent infinite loop if interval is 0
            
            val refreshInterval = refreshIntervalMinutes.toLong() * 60000
            val currentUrl = webView.url
            if (currentUrl != null) {
                if (currentUrl == "file:///android_asset/error.html") {
                    val baseUrl = BuildConfig.BASE_URL.removeSuffix("/")
                    val startUrl = if (authRepository.isAuthenticated()) {
                        "$baseUrl/library"
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

    private var isAtViewPage = false

    private fun updateKeepAwake(isAtView: Boolean) {
        if (isAtView == isAtViewPage) return
        isAtViewPage = isAtView
        val serviceIntent = Intent(this, KeepAwakeService::class.java)
        if (isAtView) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { 
                startForegroundService(serviceIntent) 
            } else { 
                startService(serviceIntent) 
            }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            stopService(serviceIntent)
        }
    }

    private fun updateSettingsIconVisibility(url: String?) {
        val isAtView = url?.contains("/view", ignoreCase = true) == true
        Log.d("UI_DEBUG", "Updating icon visibility. URL: $url, isAtView: $isAtView")
        
        updateKeepAwake(isAtView)

        val visibility = if (isAtView) View.GONE else View.VISIBLE
        settingsIcon?.visibility = visibility
        settingsButtonContainer?.visibility = visibility
        if (visibility == View.GONE && isMenuExpanded) {
            toggleMenu()
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

        // Custom User Agent
        webSettings.userAgentString = com.eyediatech.eyedeeaphotos.utils.UserAgentUtils.getUserAgent(this)

        // Enable maximum performance
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // Setup JS Bridge for Offline Sync
        val syncCoordinator = com.eyediatech.eyedeeaphotos.sync.OfflineSyncCoordinator(this)
        webView.addJavascriptInterface(
            com.eyediatech.eyedeeaphotos.bridge.EyedeeaPhotosJsBridge(syncCoordinator),
            "EyedeeaPhotosNativeBridge"
        )

        // --- Download Logic ---
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            handleDownload(url, userAgent, contentDisposition, mimetype)
        }

        // --- Settings Icon for Mobile Flavor ---
        settingsButtonContainer = findViewById(R.id.settingsButtonContainer)
        settingsIcon = findViewById(R.id.settingsIcon)
        expandedMenuContainer = findViewById(R.id.expandedMenuContainer)
        uploadIcon = findViewById(R.id.uploadIcon)
        settingsActionIcon = findViewById(R.id.settingsActionIcon)
        queueBadge = findViewById(R.id.queueBadge)
        settingsActionBadge = findViewById(R.id.settingsActionBadge)

        settingsButtonContainer?.bringToFront()
        expandedMenuContainer?.bringToFront()

        if (settingsButtonContainer != null) {
            settingsButtonContainer?.setOnClickListener {
                toggleMenu()
            }
            
            uploadIcon?.setOnClickListener {
                toggleMenu()
                checkLocationPermissionAndPickImages()
            }
            
            settingsActionIcon?.setOnClickListener {
                toggleMenu()
                startActivity(Intent(this, com.eyediatech.eyedeeaphotos.ui.SettingsActivity::class.java))
            }

            // Observe queue count
            photoRepository = PhotoRepository(AppDatabase.getDatabase(this).photoDao())
            lifecycleScope.launch {
                photoRepository.allQueuedPhotos.collectLatest { photos ->
                    if (photos.isNotEmpty()) {
                        val countText = if (photos.size > 99) "99+" else photos.size.toString()
                        if (isMenuExpanded) {
                            queueBadge?.visibility = View.GONE
                            settingsActionBadge?.visibility = View.VISIBLE
                            settingsActionBadge?.text = countText
                            settingsIcon?.alpha = 1.0f
                        } else {
                            queueBadge?.visibility = View.VISIBLE
                            queueBadge?.text = countText
                            settingsActionBadge?.visibility = View.GONE
                            settingsIcon?.alpha = 1.0f
                        }
                    } else {
                        queueBadge?.visibility = View.GONE
                        settingsActionBadge?.visibility = View.GONE
                        if (!isMenuExpanded) {
                            settingsIcon?.alpha = 0.4f
                        }
                    }
                }
            }
        }

        // Custom WebViewClient to handle downloads and navigation
        webView.webViewClient = object : WebViewClient() {
            private fun checkUrlAndInjectToken(view: WebView?, url: String?) {
                if (url == null) return
                if (authRepository.isAuthenticated()) {
                    val uri = try { Uri.parse(url) } catch (e: Exception) { null }
                    if (uri != null) {
                        val baseUrlUri = Uri.parse(BuildConfig.BASE_URL)
                        
                        // Check if the URL belongs to our backend host (fuzzy match for production, exact for debug)
                        if (uri.host?.endsWith("eyedeeaphotos.com") == true || uri.host == baseUrlUri.host) {
                            val path = uri.path ?: ""
                            // The API team redirects to /app-login for unauthenticated apps
                            val isUnauthenticatedPage = path == "" || path == "/" || path.startsWith("/login") || path.startsWith("/app-login") || path.startsWith("/home") || path.startsWith("/auth")
                            
                            if (isUnauthenticatedPage) {
                                Log.d("AUTH_DEBUG", "Detected unauthenticated page while authenticated locally. URL: $url")
                                view?.visibility = View.INVISIBLE
                                injectTokenIntoLocalStorage()
                            } else {
                                view?.visibility = View.VISIBLE
                            }
                        } else {
                            view?.visibility = View.VISIBLE
                        }
                    } else {
                        view?.visibility = View.VISIBLE
                    }
                }
            }

            @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url
                if (uri != null && authRepository.isAuthenticated()) {
                    val baseUrlUri = Uri.parse(BuildConfig.BASE_URL)
                    if (uri.host?.endsWith("eyedeeaphotos.com") == true || uri.host == baseUrlUri.host) {
                        val path = uri.path ?: ""
                        if (path == "" || path == "/" || path.startsWith("/login") || path.startsWith("/app-login") || path.startsWith("/home") || path.startsWith("/auth")) {
                            Log.d("AUTH_DEBUG", "Intercepted unauthenticated page in shouldOverrideUrlLoading. URL: ${request.url}")
                            view?.visibility = View.INVISIBLE
                            injectTokenIntoLocalStorage()
                            return true
                        }
                    }
                }
                return super.shouldOverrideUrlLoading(view, request)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url != null && authRepository.isAuthenticated()) {
                    try {
                        val uri = Uri.parse(url)
                        val baseUrlUri = Uri.parse(BuildConfig.BASE_URL)
                        if (uri.host?.endsWith("eyedeeaphotos.com") == true || uri.host == baseUrlUri.host) {
                            val path = uri.path ?: ""
                            if (path == "" || path == "/" || path.startsWith("/login") || path.startsWith("/app-login") || path.startsWith("/home") || path.startsWith("/auth")) {
                                Log.d("AUTH_DEBUG", "Intercepted unauthenticated page in shouldOverrideUrlLoading (deprecated). URL: $url")
                                view?.visibility = View.INVISIBLE
                                injectTokenIntoLocalStorage()
                                return true
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
                updateSettingsIconVisibility(url)
                checkUrlAndInjectToken(view, url)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                updateSettingsIconVisibility(url)
                checkUrlAndInjectToken(view, url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d("AUTH_DEBUG", "onPageFinished: $url")
                
                findViewById<View>(R.id.progressBar)?.visibility = View.GONE
                
                if (url != "file:///android_asset/error.html") {
                    sharedPreferences.edit { putBoolean(SERVER_DOWN, false) }
                }
                
                updateSettingsIconVisibility(url)
                checkUrlAndInjectToken(view, url)
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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val history = webView.copyBackForwardList()
                val currentIndex = history.currentIndex
                Log.d("BACK_BTN", "handleOnBackPressed. History size: ${history.size}, currentIndex: $currentIndex, currentUrl: ${webView.url}")
                
                if (webView.canGoBack() && currentIndex > 0) {
                    val previousUrl = history.getItemAtIndex(currentIndex - 1).url
                    Log.d("BACK_BTN", "Target previous URL: $previousUrl")
                    
                    var isPreviousUnauthenticated = false
                    try {
                        val prevUri = Uri.parse(previousUrl)
                        val baseUri = Uri.parse(BuildConfig.BASE_URL)
                        if (prevUri.host == baseUri.host) {
                            val path = prevUri.path ?: ""
                            isPreviousUnauthenticated = path == "" || path == "/" || path.startsWith("/login") || path.startsWith("/app-login") || path.startsWith("/home") || path.startsWith("/auth")
                        }
                    } catch (e: Exception) {}
                    
                    if (authRepository.isAuthenticated() && isPreviousUnauthenticated) {
                        Log.d("BACK_BTN", "Prevented redirect loop to login/root. Exiting app instead.")
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    } else {
                        Log.d("BACK_BTN", "Going back in WebView")
                        webView.goBack()
                    }
                } else {
                    Log.d("BACK_BTN", "Exiting app directly")
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun toggleMenu() {
        isMenuExpanded = !isMenuExpanded
        if (isMenuExpanded) {
            expandedMenuContainer?.visibility = View.VISIBLE
            settingsIcon?.setImageResource(R.drawable.ic_close)
            settingsIcon?.alpha = 1.0f
            
            // Move badge from hamburger to settings gear
            if (queueBadge?.visibility == View.VISIBLE) {
                queueBadge?.visibility = View.GONE
                settingsActionBadge?.visibility = View.VISIBLE
                settingsActionBadge?.text = queueBadge?.text
            }
        } else {
            expandedMenuContainer?.visibility = View.GONE
            settingsIcon?.setImageResource(R.drawable.ic_menu)
            
            // Move badge back to hamburger
            if (settingsActionBadge?.visibility == View.VISIBLE) {
                settingsActionBadge?.visibility = View.GONE
                queueBadge?.visibility = View.VISIBLE
                queueBadge?.text = settingsActionBadge?.text
                settingsIcon?.alpha = 1.0f
            } else {
                settingsIcon?.alpha = 0.4f
            }
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
        
        val libraryUrl = if (role == "user" || role == "auth_user" || role == "public_user") {
            "$baseUrl/pricing"
        } else {
            "$baseUrl/library"
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
                    window.location.replace('$libraryUrl');
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
