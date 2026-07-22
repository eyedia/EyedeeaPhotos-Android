package com.eyediatech.eyedeeaphotos

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.eyediatech.eyedeeaphotos.repository.AuthRepository
import com.eyediatech.eyedeeaphotos.repository.PhotoRepository
import com.eyediatech.eyedeeaphotos.data.AppDatabase
import com.eyediatech.eyedeeaphotos.ui.LoginActivity
import com.eyediatech.eyedeeaphotos.ui.WebViewSwipeRefreshLayout
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL


import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefreshLayout: WebViewSwipeRefreshLayout
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

    /** Pending WebView HTML file-input callback (in-page Choose Files). */
    private var webViewFilePathCallback: ValueCallback<Array<Uri>>? = null

    private val webViewFileChooserLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        val callback = webViewFilePathCallback
        webViewFilePathCallback = null
        if (uris.isNullOrEmpty()) {
            callback?.onReceiveValue(null)
        } else {
            callback?.onReceiveValue(uris.toTypedArray())
        }
    }

    private val requestLocationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { _ ->
        pickImagesLauncher.launch("image/*")
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Whether they grant or deny it, we still proceed to pick images.
        // If denied, they just won't see the sync notification.
        checkLocationPermissionAndPickImages()
    }

    private var pendingDestinationAlbum: String? = null

    private fun checkNotificationPermissionAndProceed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            checkLocationPermissionAndPickImages()
        }
    }

    /**
     * Opens native gallery picker. albumPath null => raw upload; non-null curated album path.
     * When albumPath is a deep curated album and showChoice is true, show destination bottom sheet.
     */
    private fun startNativeUpload(albumPath: String? = null, showCuratedChoice: Boolean = false) {
        runOnUiThread {
            if (showCuratedChoice && !albumPath.isNullOrBlank() && albumPath.startsWith("curated/")) {
                val parts = albumPath.split("/")
                if (parts.size >= 4) {
                    val bottomSheetDialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
                    val view = layoutInflater.inflate(R.layout.bottom_sheet_upload_options, null)

                    view.findViewById<View>(R.id.optionCurrentAlbum).setOnClickListener {
                        pendingDestinationAlbum = albumPath
                        bottomSheetDialog.dismiss()
                        checkNotificationPermissionAndProceed()
                    }

                    view.findViewById<View>(R.id.optionRawCurate).setOnClickListener {
                        pendingDestinationAlbum = null
                        bottomSheetDialog.dismiss()
                        checkNotificationPermissionAndProceed()
                    }

                    bottomSheetDialog.setContentView(view)
                    bottomSheetDialog.show()
                    return@runOnUiThread
                }
            }

            pendingDestinationAlbum = albumPath?.takeIf { it.isNotBlank() }
            checkNotificationPermissionAndProceed()
        }
    }

    private fun handleOpenNativeUploadPayload(jsonPayload: String?): Boolean {
        return try {
            val albumPath = if (jsonPayload.isNullOrBlank()) {
                null
            } else {
                val json = org.json.JSONObject(jsonPayload)
                json.optString("albumPath", "").ifBlank { null }
            }
            startNativeUpload(albumPath = albumPath, showCuratedChoice = !albumPath.isNullOrBlank())
            true
        } catch (e: Exception) {
            Log.e("UPLOAD_DEBUG", "Failed to parse openNativeUpload payload", e)
            startNativeUpload(albumPath = null, showCuratedChoice = false)
            true
        }
    }

    private fun handleUploadDeepLink(uri: Uri?) {
        if (uri == null) return
        if (uri.scheme != "eyedeea" || uri.host != "upload") return
        val albumPath = uri.getQueryParameter("album")
            ?: uri.getQueryParameter("albumPath")
        startNativeUpload(albumPath = albumPath, showCuratedChoice = !albumPath.isNullOrBlank())
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
        val destAlbum = pendingDestinationAlbum
        pendingDestinationAlbum = null
        lifecycleScope.launch {
            var queuedCount = 0
            var emptySkippedCount = 0
            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    val internalFile = copyToInternalStorage(uri)
                    if (internalFile != null) {
                        if (internalFile.length() <= 0L) {
                            internalFile.delete()
                            emptySkippedCount += 1
                            continue
                        }
                        val queuedPhoto = com.eyediatech.eyedeeaphotos.data.QueuedPhoto(
                            fileUri = uri.toString(),
                            internalPath = internalFile.absolutePath,
                            fileName = internalFile.name,
                            destinationAlbum = destAlbum
                        )
                        photoRepository.insert(queuedPhoto)
                        queuedCount += 1
                    }
                }
            }
            val message = when {
                queuedCount > 0 && emptySkippedCount > 0 ->
                    "$queuedCount photo(s) queued. $emptySkippedCount empty photo(s) were skipped."
                queuedCount > 0 ->
                    "Your photos are queued and will sync soon."
                emptySkippedCount > 0 ->
                    "$emptySkippedCount photo(s) were empty and were not uploaded."
                else ->
                    "No photos could be queued. Please try again."
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
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
            } ?: return@withContext null

            if (finalFile.length() <= 0L) {
                finalFile.delete()
                return@withContext null
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
        private const val LAST_WEBVIEW_URL = "last_webview_url"
        private val WEBSITE_ADDRESS_DEFAULT = BuildConfig.VIEW_URL
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

        // Setup WebView
        setupWebView()

        // After a long lock Android often kills the process. restoreState() rarely freezes the
        // SPA in place — it usually reloads from the network. Prefer the last real page the user
        // was on (e.g. albums) so unlock doesn't bounce through login → /library.
        val urlToLoad = resolveStartUrl()
        try {
            val restored = savedInstanceState != null && webView.restoreState(savedInstanceState) != null
            if (restored && !webView.url.isNullOrBlank()) {
                Log.d("WEBVIEW_DEBUG", "Restored WebView state; url=${webView.url}")
            } else {
                Log.d("WEBVIEW_DEBUG", "Loading: $urlToLoad (restored=$restored)")
                webView.loadUrl(urlToLoad)
            }
        } catch (e: Exception) {
            Log.e("WEBVIEW_DEBUG", "Load error: ${e.message}")
            handleLoadError()
        }

        handleUploadDeepLink(intent?.data)
    }

    /** Default home for the role, or the last in-app URL if we have one. */
    private fun resolveStartUrl(): String {
        val lastUrl = sharedPreferences.getString(LAST_WEBVIEW_URL, null)
        if (isPersistableAppUrl(lastUrl)) {
            return lastUrl!!
        }
        val savedIp = sharedPreferences.getString(WEBSITE_ADDRESS, WEBSITE_ADDRESS_DEFAULT) ?: WEBSITE_ADDRESS_DEFAULT
        val baseUrl = BuildConfig.BASE_URL.removeSuffix("/")
        return if (authRepository.isAuthenticated()) {
            val role = authRepository.getGroup() ?: "user"
            if (role == "user" || role == "auth_user" || role == "public_user") {
                "$baseUrl/pricing"
            } else {
                "$baseUrl/library"
            }
        } else {
            savedIp
        }
    }

    private fun isPersistableAppUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (url.startsWith("file://") || url == "about:blank") return false
        return try {
            val uri = Uri.parse(url)
            val baseHost = Uri.parse(BuildConfig.BASE_URL).host
            val hostOk = uri.host?.endsWith("eyedeeaphotos.com") == true || uri.host == baseHost
            val path = uri.path ?: ""
            val isAuthPage = path == "" || path == "/" ||
                path.startsWith("/login") || path.startsWith("/app-login") ||
                path.startsWith("/home") || path.startsWith("/auth")
            hostOk && !isAuthPage
        } catch (_: Exception) {
            false
        }
    }

    private fun persistLastUrl(url: String?) {
        if (!isPersistableAppUrl(url)) return
        sharedPreferences.edit { putString(LAST_WEBVIEW_URL, url) }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUploadDeepLink(intent?.data)
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
        // Do not call webView.onResume() — it can make the web app refresh on unlock.
        // Users refresh manually via pull-to-refresh.
    }

    override fun onPause() {
        super.onPause()
        val serviceIntent = Intent(this, KeepAwakeService::class.java)
        stopService(serviceIntent)
        // Do not call webView.onPause() — avoids aggressive refresh when returning from lock.
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
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        swipeRefreshLayout.webView = webView

        // Harder to trigger accidentally; leave status-bar zone for notification shade.
        val density = resources.displayMetrics.density
        swipeRefreshLayout.setDistanceToTriggerSync((160 * density).toInt())
        ViewCompat.setOnApplyWindowInsetsListener(swipeRefreshLayout) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            swipeRefreshLayout.topGestureExclusionPx = statusBars.top + (48 * density)
            insets
        }
        ViewCompat.requestApplyInsets(swipeRefreshLayout)

        swipeRefreshLayout.setOnRefreshListener {
            webView.reload()
        }

        // WebView settings
        val webSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.databaseEnabled = true
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true
        webSettings.mediaPlaybackRequiresUserGesture = false

        // Enable downloads
        webSettings.setSupportMultipleWindows(true)
        webSettings.javaScriptCanOpenWindowsAutomatically = true
        webSettings.cacheMode = WebSettings.LOAD_DEFAULT

        webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // Custom User Agent
        webSettings.userAgentString = com.eyediatech.eyedeeaphotos.utils.UserAgentUtils.getUserAgent(this)

        // Enable maximum performance
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // Setup JS Bridge for Offline Sync + Native Upload + blob downloads
        val syncCoordinator = com.eyediatech.eyedeeaphotos.sync.OfflineSyncCoordinator(this)
        webView.addJavascriptInterface(
            com.eyediatech.eyedeeaphotos.bridge.EyedeeaPhotosJsBridge(
                syncCoordinator,
                onOpenNativeUpload = { payload -> handleOpenNativeUploadPayload(payload) }
            ),
            "EyedeeaPhotosNativeBridge"
        )
        webView.addJavascriptInterface(BlobDownloadBridge(), "BlobDownloadBridge")

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
                
                val currentUrl = webView.url
                var albumPath: String? = null
                
                if (currentUrl != null && currentUrl.contains("tab=browse")) {
                    val uri = try { Uri.parse(currentUrl) } catch (e: Exception) { null }
                    if (uri != null) {
                        val tab = uri.getQueryParameter("tab")
                        val browseMode = uri.getQueryParameter("browseMode")
                        val path = uri.getQueryParameter("albumPath")
                        
                        if (tab == "browse" && browseMode == null && path != null && path.startsWith("curated/")) {
                            val parts = path.split("/")
                            // Example: curated/2021-2025/2025/Weekend Trip -> 4 parts
                            if (parts.size >= 4) {
                                albumPath = path
                            }
                        }
                    }
                }
                
                startNativeUpload(albumPath = albumPath, showCuratedChoice = albumPath != null)
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
                                view?.post {
                                    injectTokenIntoLocalStorage()
                                }
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
                if (uri != null && uri.scheme == "eyedeea" && uri.host == "upload") {
                    handleUploadDeepLink(uri)
                    return true
                }
                if (uri != null && authRepository.isAuthenticated()) {
                    val baseUrlUri = Uri.parse(BuildConfig.BASE_URL)
                    if (uri.host?.endsWith("eyedeeaphotos.com") == true || uri.host == baseUrlUri.host) {
                        val path = uri.path ?: ""
                        if (path == "" || path == "/" || path.startsWith("/login") || path.startsWith("/app-login") || path.startsWith("/home") || path.startsWith("/auth")) {
                            Log.d("AUTH_DEBUG", "Intercepted unauthenticated page in shouldOverrideUrlLoading. URL: ${request.url}")
                            view?.visibility = View.INVISIBLE
                            view?.post {
                                injectTokenIntoLocalStorage()
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
                        val deepLinkUri = Uri.parse(url)
                        if (deepLinkUri.scheme == "eyedeea" && deepLinkUri.host == "upload") {
                            handleUploadDeepLink(deepLinkUri)
                            return true
                        }
                    } catch (_: Exception) {
                        // Continue with auth intercept below.
                    }
                }
                if (url != null && authRepository.isAuthenticated()) {
                    try {
                        val uri = Uri.parse(url)
                        val baseUrlUri = Uri.parse(BuildConfig.BASE_URL)
                        if (uri.host?.endsWith("eyedeeaphotos.com") == true || uri.host == baseUrlUri.host) {
                            val path = uri.path ?: ""
                            if (path == "" || path == "/" || path.startsWith("/login") || path.startsWith("/app-login") || path.startsWith("/home") || path.startsWith("/auth")) {
                                Log.d("AUTH_DEBUG", "Intercepted unauthenticated page in shouldOverrideUrlLoading (deprecated). URL: $url")
                                view?.visibility = View.INVISIBLE
                                view?.post {
                                    injectTokenIntoLocalStorage()
                                }
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
                
                if (url != null && authRepository.isAuthenticated()) {
                    try {
                        val uri = Uri.parse(url)
                        val baseUrlUri = Uri.parse(BuildConfig.BASE_URL)
                        if (uri.host?.endsWith("eyedeeaphotos.com") == true || uri.host == baseUrlUri.host) {
                            view?.post {
                                injectTokenOnly(view)
                            }
                        }
                    } catch (e: Exception) {}
                }

                updateSettingsIconVisibility(url)
                checkUrlAndInjectToken(view, url)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                // Only update chrome UI here — re-running auth injection on every SPA
                // history update can bounce users through login pages repeatedly.
                persistLastUrl(url)
                updateSettingsIconVisibility(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d("AUTH_DEBUG", "onPageFinished: $url")
                
                findViewById<View>(R.id.progressBar)?.visibility = View.GONE
                swipeRefreshLayout.isRefreshing = false
                
                if (url != "file:///android_asset/error.html") {
                    sharedPreferences.edit { putBoolean(SERVER_DOWN, false) }
                }
                
                // Reset token injection count on successful load of an app page
                if (url != null && !url.contains("login") && !url.contains("app-login")) {
                    tokenInjectionCount = 0
                }

                persistLastUrl(url)
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

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                // After a long lock Android often kills the WebView renderer. The WebView is
                // unusable afterward — recreate the activity and reopen the last in-app URL.
                Log.e(
                    "WEBVIEW_DEBUG",
                    "Render process gone didCrash=${detail?.didCrash()} — recreating activity"
                )
                if (view === webView) {
                    view.post {
                        if (!isFinishing && !isDestroyed) {
                            recreate()
                        }
                    }
                }
                return true
            }
        }

        // WebChromeClient for JS console + HTML <input type="file"> (Choose Files in upload modal)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (BuildConfig.ENABLE_WEB_CONSOLE_LOG) {
                    consoleMessage?.let {
                        Log.d("WEB_CONSOLE", "${it.messageLevel()}: ${it.message()} -- From line ${it.lineNumber()} of ${it.sourceId()}")
                    }
                }
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // Supersede any previous pending chooser
                webViewFilePathCallback?.onReceiveValue(null)
                webViewFilePathCallback = filePathCallback

                val acceptTypes = fileChooserParams?.acceptTypes
                val mimeType = when {
                    acceptTypes.isNullOrEmpty() || acceptTypes.all { it.isNullOrBlank() } -> "image/*"
                    acceptTypes.any { it.contains("image", ignoreCase = true) } -> "image/*"
                    else -> acceptTypes.firstOrNull { !it.isNullOrBlank() } ?: "*/*"
                }

                return try {
                    webViewFileChooserLauncher.launch(mimeType)
                    true
                } catch (e: Exception) {
                    Log.e("WEBVIEW_FILE", "Failed to launch file chooser", e)
                    webViewFilePathCallback = null
                    filePathCallback?.onReceiveValue(null)
                    false
                }
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
        // Check for permissions first (pre-Q writing to public Downloads)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownloadUrl = url
            pendingUserAgent = userAgent
            pendingContentDisposition = contentDisposition
            pendingMimetype = mimetype
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                storagePermissionRequestCode
            )
            return
        }

        when {
            url.startsWith("blob:", ignoreCase = true) -> downloadBlobUrl(url, contentDisposition, mimetype)
            url.startsWith("data:", ignoreCase = true) -> {
                val fileName = guessDownloadFileName(url, contentDisposition, mimetype)
                saveDataUrlToDownloads(url, fileName, mimetype.ifBlank { "application/octet-stream" })
            }
            url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) ->
                downloadWithManager(url, userAgent, contentDisposition, mimetype)
            else -> {
                Toast.makeText(this, "Download failed: unsupported URL", Toast.LENGTH_LONG).show()
                Log.e("DOWNLOAD_ERROR", "Unsupported download URL scheme: ${url.take(80)}")
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != storagePermissionRequestCode) return
        val url = pendingDownloadUrl
        val ua = pendingUserAgent
        val disposition = pendingContentDisposition
        val mime = pendingMimetype
        pendingDownloadUrl = null
        pendingUserAgent = null
        pendingContentDisposition = null
        pendingMimetype = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED &&
            url != null && ua != null && disposition != null && mime != null
        ) {
            handleDownload(url, ua, disposition, mime)
        } else if (url != null) {
            Toast.makeText(this, "Storage permission required to download", Toast.LENGTH_LONG).show()
        }
    }

    private fun guessDownloadFileName(url: String, contentDisposition: String, mimetype: String): String {
        val guessed = URLUtil.guessFileName(
            if (url.startsWith("blob:") || url.startsWith("data:")) "download" else url,
            contentDisposition,
            mimetype
        )
        return guessed.ifBlank { "download_${System.currentTimeMillis()}" }
    }

    private var blobDownloadBuffer: java.io.ByteArrayOutputStream? = null
    private var blobDownloadFileName: String? = null
    private var blobDownloadMime: String? = null

    /**
     * DownloadManager cannot fetch blob: URIs. Read the blob in-page and stream
     * base64 chunks to native code (avoids Binder size limits on large photos).
     */
    private fun downloadBlobUrl(blobUrl: String, contentDisposition: String, mimetype: String) {
        val fileName = guessDownloadFileName(blobUrl, contentDisposition, mimetype)
        val mime = mimetype.ifBlank { "application/octet-stream" }
        val js = """
            (async function() {
                try {
                    const resp = await fetch(${JSONObject.quote(blobUrl)});
                    const blob = await resp.blob();
                    const buffer = await blob.arrayBuffer();
                    const bytes = new Uint8Array(buffer);
                    const chunkSize = 256 * 1024;
                    function toBase64(u8) {
                        let binary = '';
                        const len = u8.length;
                        for (let i = 0; i < len; i++) binary += String.fromCharCode(u8[i]);
                        return btoa(binary);
                    }
                    BlobDownloadBridge.begin(${JSONObject.quote(fileName)}, ${JSONObject.quote(mime)});
                    for (let offset = 0; offset < bytes.length; offset += chunkSize) {
                        const end = Math.min(offset + chunkSize, bytes.length);
                        BlobDownloadBridge.append(toBase64(bytes.subarray(offset, end)));
                    }
                    BlobDownloadBridge.finish();
                } catch (e) {
                    BlobDownloadBridge.error(String(e && e.message ? e.message : e));
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
        Toast.makeText(this, "Preparing download...", Toast.LENGTH_SHORT).show()
    }

    private inner class BlobDownloadBridge {
        @JavascriptInterface
        fun begin(fileName: String?, mimeType: String?) {
            blobDownloadBuffer = java.io.ByteArrayOutputStream()
            blobDownloadFileName = fileName?.ifBlank { null } ?: "download_${System.currentTimeMillis()}"
            blobDownloadMime = mimeType?.ifBlank { null } ?: "application/octet-stream"
        }

        @JavascriptInterface
        fun append(base64Chunk: String?) {
            if (base64Chunk.isNullOrBlank()) return
            try {
                val bytes = Base64.decode(base64Chunk, Base64.DEFAULT)
                blobDownloadBuffer?.write(bytes)
            } catch (e: Exception) {
                Log.e("DOWNLOAD_ERROR", "Blob chunk decode failed: ${e.message}")
            }
        }

        @JavascriptInterface
        fun finish() {
            val bytes = blobDownloadBuffer?.toByteArray()
            val fileName = blobDownloadFileName ?: "download_${System.currentTimeMillis()}"
            val mime = blobDownloadMime ?: "application/octet-stream"
            blobDownloadBuffer = null
            blobDownloadFileName = null
            blobDownloadMime = null
            runOnUiThread {
                if (bytes == null || bytes.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Download failed: empty file", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                saveBytesToDownloads(bytes, fileName, mime)
            }
        }

        @JavascriptInterface
        fun save(dataUrl: String?, fileName: String?, mimeType: String?) {
            // Kept for small data: URLs
            runOnUiThread {
                if (dataUrl.isNullOrBlank()) {
                    Toast.makeText(this@MainActivity, "Download failed: empty file", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                saveDataUrlToDownloads(
                    dataUrl,
                    fileName?.ifBlank { null } ?: "download_${System.currentTimeMillis()}",
                    mimeType?.ifBlank { null } ?: "application/octet-stream"
                )
            }
        }

        @JavascriptInterface
        fun error(message: String?) {
            blobDownloadBuffer = null
            blobDownloadFileName = null
            blobDownloadMime = null
            runOnUiThread {
                Toast.makeText(
                    this@MainActivity,
                    "Download failed: ${message ?: "unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
                Log.e("DOWNLOAD_ERROR", "Blob download error: $message")
            }
        }
    }

    private fun saveDataUrlToDownloads(dataUrl: String, fileName: String, mimeType: String) {
        try {
            val base64 = dataUrl.substringAfter("base64,", missingDelimiterValue = "")
            if (base64.isEmpty()) {
                Toast.makeText(this, "Download failed: invalid file data", Toast.LENGTH_LONG).show()
                return
            }
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            saveBytesToDownloads(bytes, fileName, mimeType)
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("DOWNLOAD_ERROR", "Error saving data download: ${e.message}", e)
        }
    }

    private fun saveBytesToDownloads(bytes: ByteArray, fileName: String, mimeType: String) {
        try {
            if (bytes.isEmpty()) {
                Toast.makeText(this, "Download failed: empty file", Toast.LENGTH_LONG).show()
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Unable to create download entry")
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("Unable to write download")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val outFile = File(dir, fileName)
                FileOutputStream(outFile).use { it.write(bytes) }
            }

            Toast.makeText(this, "Saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("DOWNLOAD_ERROR", "Error saving download: ${e.message}", e)
        }
    }

    private fun downloadWithManager(url: String, userAgent: String, contentDisposition: String, mimetype: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(url))
            val fileName = guessDownloadFileName(url, contentDisposition, mimetype)

            request.setMimeType(mimetype)
            request.addRequestHeader("User-Agent", userAgent)
            val token = authRepository.getToken()
            if (!token.isNullOrBlank()) {
                request.addRequestHeader("Authorization", "Bearer $token")
            }
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
        
        val fallbackHome = if (role == "user" || role == "auth_user" || role == "public_user") {
            "$baseUrl/pricing"
        } else {
            "$baseUrl/library"
        }
        // Return to where the user was (albums, etc.), not always home — unlock/process death
        // often briefly hits /app-login before tokens are re-injected.
        val redirectUrl = sharedPreferences.getString(LAST_WEBVIEW_URL, null)
            ?.takeIf { isPersistableAppUrl(it) }
            ?: fallbackHome

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
                    window.location.replace('$redirectUrl');
                } catch (e) {
                    console.error('Injection failed: ' + e);
                }
            })();
        """.trimIndent()

        Log.d("AUTH_DEBUG", "Injecting storage and redirecting via JS to $redirectUrl")
        webView.evaluateJavascript(js, null)
    }

    private fun handleLoadError() {
        runOnUiThread {
            sharedPreferences.edit { putBoolean(SERVER_DOWN, true) }
            webView.loadUrl("file:///android_asset/error.html")
        }
    }

    private fun clearWebViewFileChooser() {
        webViewFilePathCallback?.onReceiveValue(null)
        webViewFilePathCallback = null
    }

    override fun onDestroy() {
        clearWebViewFileChooser()
        super.onDestroy()
    }
}
