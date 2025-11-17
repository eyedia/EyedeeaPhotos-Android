package com.eyediatech.eyedeeaphotos

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var sharedPreferences: SharedPreferences
    private val STORAGE_PERMISSION_CODE = 1001

    companion object {
        private const val PREFS_NAME = "EyeDeeaPrefs"
        private const val IP_KEY = "server_ip"
        private const val DEFAULT_IP = "192.168.1.100:8080"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize shared preferences
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Get saved IP or use default
        val savedIp = sharedPreferences.getString(IP_KEY, DEFAULT_IP) ?: DEFAULT_IP

        // Setup WebView
        setupWebView(savedIp)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(ipAddress: String) {
        webView = WebView(this)

        // WebView settings
        val webSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true
        webSettings.allowUniversalAccessFromFileURLs = true
        webSettings.allowFileAccessFromFileURLs = true

        // Enable downloads
        webSettings.setSupportMultipleWindows(true)
        webSettings.javaScriptCanOpenWindowsAutomatically = true

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            webSettings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        // Set up download listener
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            android.util.Log.d("DOWNLOAD", "Download requested: $url")
            downloadFile(url, contentDisposition, mimetype)
        }

        // Custom WebViewClient to handle downloads and navigation
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()

                // Handle download links
                if (url.contains(".jpg") || url.contains(".jpeg") || url.contains(".png") ||
                    url.contains(".gif") || url.contains(".webp") || url.contains("download")) {
                    android.util.Log.d("DOWNLOAD", "Intercepting download URL: $url")
                    downloadFile(url, "", "image/*")
                    return true
                }

                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Inject JavaScript to intercept download button clicks
                injectDownloadInterceptor()
            }

            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                showErrorPage(ipAddress, description ?: "Unknown error")
            }
        }

        // WebChromeClient for better JavaScript support
        webView.webChromeClient = WebChromeClient()

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
                        showSettingsMenu()
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

        // Long press support (optional - for devices with touch)
        webView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val startTime = System.currentTimeMillis()
                webView.postDelayed({
                    if (System.currentTimeMillis() - startTime > 1000) {
                        showSettingsMenu()
                    }
                }, 1000)
            }
            false
        }

        // Load URL
        try {
            android.util.Log.d("WEBVIEW_DEBUG", "Loading: http://$ipAddress/")
            webView.loadUrl("http://$ipAddress/")
        } catch (e: Exception) {
            android.util.Log.e("WEBVIEW_DEBUG", "Load error: ${e.message}")
            showErrorPage(ipAddress, e.message ?: "Unknown error")
        }

        setContentView(webView)
    }

    private fun downloadFile(url: String, contentDisposition: String, mimeType: String) {
        // Check storage permission first
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {

            // Request permission
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                STORAGE_PERMISSION_CODE
            )
            return
        }

        // Create download manager request
        val request = android.app.DownloadManager.Request(Uri.parse(url))

        // Get filename from URL or content disposition
        var fileName = "downloaded_image_${System.currentTimeMillis()}.jpg"
        if (contentDisposition.contains("filename=")) {
            fileName = contentDisposition.substringAfter("filename=").trim('"')
        } else {
            // Extract from URL
            fileName = url.substringAfterLast("/")
            if (fileName.length > 100 || fileName.isEmpty()) {
                fileName = "photo_${System.currentTimeMillis()}.jpg"
            }
        }

        // Set download details
        request.setTitle("Downloading Photo")
        request.setDescription("Downloading $fileName")
        request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        request.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fileName)

        // Get download service and enqueue file
        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        val downloadId = downloadManager.enqueue(request)

        // Show confirmation
        Toast.makeText(this, "Download started: $fileName", Toast.LENGTH_LONG).show()
        android.util.Log.d("DOWNLOAD", "Download enqueued: $fileName (ID: $downloadId)")
    }

    private fun injectDownloadInterceptor() {
        val jsCode = """
            javascript:(function() {
                // Intercept click events on download buttons
                var downloadButtons = document.querySelectorAll('[id*="download"], [class*="download"], button, a');
                downloadButtons.forEach(function(button) {
                    button.addEventListener('click', function(e) {
                        var img = document.querySelector('img');
                        if (img && img.src) {
                            // Trigger download through Android
                            window.location.href = img.src + '?download=true';
                        }
                    });
                });
                
                // Also intercept any link that looks like a download
                var links = document.querySelectorAll('a[href*=".jpg"], a[href*=".jpeg"], a[href*=".png"], a[href*=".gif"]');
                links.forEach(function(link) {
                    link.setAttribute('target', '_blank');
                    link.addEventListener('click', function(e) {
                        e.preventDefault();
                        window.location.href = this.href + '?forceDownload=true';
                    });
                });
            })();
        """.trimIndent()

        webView.evaluateJavascript(jsCode, null)
    }

    private fun showSettingsMenu() {
        val options = arrayOf("Change Server IP", "Reload Page", "Clear Cache", "Exit App")

        val builder = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)

        builder.setTitle("Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showIpInputDialog()
                    1 -> webView.reload()
                    2 -> {
                        webView.clearCache(true)
                        Toast.makeText(this, "Cache cleared", Toast.LENGTH_SHORT).show()
                    }
                    3 -> finish()
                }
            }
            .setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    // Dismiss on back button
                    true
                } else {
                    false
                }
            }
            .show()
    }

    private fun showIpInputDialog() {
        val input = EditText(this).apply {
            hint = "192.168.1.100:8080"
            setText(sharedPreferences.getString(IP_KEY, DEFAULT_IP))
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setTitle("Configure Server IP")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) {
                    saveIpAddress(ip)
                    webView.loadUrl("http://$ip/")
                    Toast.makeText(this, "Loading: $ip", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveIpAddress(ip: String) {
        sharedPreferences.edit().putString(IP_KEY, ip).apply()
    }

    private fun showErrorPage(ipAddress: String, errorMessage: String) {
        val errorHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                    body { 
                        font-family: Arial, sans-serif; 
                        background: #f0f0f0; 
                        margin: 0; 
                        padding: 20px; 
                        text-align: center;
                    }
                    .error-container { 
                        background: white; 
                        padding: 40px; 
                        border-radius: 10px; 
                        margin: 50px auto; 
                        max-width: 500px;
                        box-shadow: 0 2px 10px rgba(0,0,0,0.1);
                    }
                    .error-title { 
                        color: #d32f2f; 
                        font-size: 24px; 
                        margin-bottom: 20px;
                    }
                    .error-details { 
                        color: #666; 
                        margin: 20px 0; 
                        font-size: 16px;
                    }
                    .retry-btn { 
                        background: #2196F3; 
                        color: white; 
                        border: none; 
                        padding: 12px 24px; 
                        border-radius: 5px; 
                        font-size: 16px; 
                        cursor: pointer;
                        margin: 10px;
                    }
                </style>
            </head>
            <body>
                <div class="error-container">
                    <div class="error-title">Connection Error</div>
                    <div class="error-details">
                        Could not connect to: $ipAddress<br>
                        Error: $errorMessage
                    </div>
                    <button class="retry-btn" onclick="window.location.reload()">Retry Connection</button>
                    <button class="retry-btn" onclick="showSettings()">Change Server IP</button>
                </div>
                <script>
                    function showSettings() {
                        // This will trigger the settings menu in the app
                        window.location.href = 'eyedeea://settings';
                    }
                </script>
            </body>
            </html>
        """.trimIndent()

        webView.loadDataWithBaseURL(null, errorHtml, "text/html", "UTF-8", null)
    }

    // Handle permission results
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            STORAGE_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Storage permission granted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Storage permission denied - downloads won't work", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Handle back button for navigation
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
