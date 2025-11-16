package com.eyediatech.eyedeeaphotos

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var gestureDetector: GestureDetectorCompat
    private val sharedPrefs = "AppPrefs"
    private val ipAddressKey = "server_ip"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if IP is already configured
        val savedIp = getSavedIpAddress()
        if (savedIp.isEmpty()) {
            showIpInputDialog()
        } else {
            setupWebView(savedIp)
        }

        // Setup modern back button handling
        setupBackPressedHandler()
    }

    private fun setupWebView(ipAddress: String) {
        android.util.Log.d("IP_DEBUG", "=== IP ADDRESS DEBUG INFO ===")
        android.util.Log.d("IP_DEBUG", "Stored IP: $ipAddress")
        android.util.Log.d("IP_DEBUG", "Full URL being loaded: http://$ipAddress/")
        android.util.Log.d("IP_DEBUG", "IP length: ${ipAddress.length}")
        android.util.Log.d("IP_DEBUG", "IP is empty: ${ipAddress.isEmpty()}")

        webView = WebView(this)

        webView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        // Create gesture detector for long press
        gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                android.util.Log.d("LONG_PRESS", "Long press detected!")
                showSettingsMenu()
            }
        })

        // Create container and set touch listener
        val container = FrameLayout(this)
        container.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        container.addView(webView)

        container.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false // Let WebView still handle events normally
        }

        // Add WebView client with error handling
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                android.util.Log.d("WEBVIEW_DEBUG", "Page started loading: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                android.util.Log.d("WEBVIEW_DEBUG", "Page finished loading: $url")
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                android.util.Log.e("WEBVIEW_DEBUG", "WebView error: ${error?.description}")

                // Show error message to user
                runOnUiThread {
                    webView.loadData(
                        """
                    <html>
                    <body style='text-align:center; padding:50px; font-family:Arial;'>
                        <h2>Connection Failed</h2>
                        <p>Could not connect to: http://$ipAddress/</p>
                        <p>Error: ${error?.description}</p>
                        <button onclick='window.location.reload()' style='padding:10px 20px;'>Retry</button>
                        <button onclick='changeIP()' style='padding:10px 20px; margin-left:10px;'>Change IP</button>
                    </body>
                    <script>
                        function changeIP() {
                            android.changeIP();
                        }
                    </script>
                    </html>
                    """,
                        "text/html",
                        "UTF-8"
                    )
                }
            }
        }

        // Configure WebView settings
        val webSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.allowFileAccess = true
        webSettings.loadWithOverviewMode = true
        webSettings.useWideViewPort = true

        // Enable JavaScript interface for debugging
        webView.addJavascriptInterface(WebAppInterface(this), "android")

        android.util.Log.d("WEBVIEW_DEBUG", "Attempting to load: http://$ipAddress/")

        // Load the URL
        webView.loadUrl("http://$ipAddress/")

        setContentView(container)
        setupBackPressedHandler()
    }

    private fun showIpInputDialog() {
        val input = EditText(this)
        input.hint = "192.168.1.100"
        input.inputType = InputType.TYPE_CLASS_PHONE

        AlertDialog.Builder(this)
            .setTitle("Configure Server")
            .setMessage("Enter your local server IP address:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) {
                    saveIpAddress(ip)
                    setupWebView(ip)
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun showCurrentIp() {
        val currentIp = getSavedIpAddress()
        AlertDialog.Builder(this)
            .setTitle("Current Server IP")
            .setMessage("Your current server IP is:\n\n$currentIp")
            .setPositiveButton("Change IP") { _, _ ->
                showIpInputDialog()
            }
            .setNegativeButton("OK", null)
            .show()
    }

    private fun saveIpAddress(ip: String) {
        getSharedPreferences(sharedPrefs, MODE_PRIVATE)
            .edit()
            .putString(ipAddressKey, ip)
            .apply()
    }

    private fun getSavedIpAddress(): String {
        return getSharedPreferences(sharedPrefs, MODE_PRIVATE)
            .getString(ipAddressKey, "") ?: ""
    }

    // Add this to your activity for IP change option
    private fun showSettingsMenu() {
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(arrayOf("Change Server IP", "Reload Page", "Show Current IP")) { _, which ->
                when (which) {
                    0 -> showIpInputDialog()
                    1 -> webView.reload()
                    2 -> showCurrentIp()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // JavaScript interface for error handling
    class WebAppInterface(private val activity: MainActivity) {
        @android.webkit.JavascriptInterface
        fun changeIP() {
            activity.runOnUiThread {
                activity.showIpInputDialog()
            }
        }
    }

    private fun setupBackPressedHandler() {
        // Modern way to handle back button
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    // If no more browser history, finish the activity
                    finish()
                }
            }
        })
    }
}
