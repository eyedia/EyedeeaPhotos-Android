package com.eyediatech.eyedeeaphotos
import androidx.mediarouter.app.MediaRouteButton
import android.os.Bundle
import android.os.DeadObjectException
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.PopupMenu
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import com.google.android.gms.cast.framework.CastButtonFactory
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.lifecycleScope
import java.util.concurrent.Executor
import android.view.MenuItem



class MainActivity : AppCompatActivity() {

    private lateinit var castContext: CastContext
    private lateinit var mediaRouteButton: MediaRouteButton
    private lateinit var webView: WebView
    private lateinit var menuButton: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            // 1. Set content view
            Log.d("MainActivity", "Setting content view...")
            setContentView(R.layout.activity_main)

            // 3. Configure screen settings
            configureScreenSettings()

            // 4. Initialize WebView
            Log.d("MainActivity", "Setting up WebView...")
            setupWebView()

            // 5. Initialize Cast framework
            Log.d("MainActivity", "Initializing Cast framework...")
            initializeCastFramework()

            // 6. Set up MediaRouteButton
            setupMediaRouteButton()

            // 7. Load URL
            Log.d("MainActivity", "Loading URL...")
            val url = getStoredUrl() ?: "http://192.168.86.101"
            Log.d("MainActivity", "URL to load: $url")
            webView.loadUrl(url)

            Log.d("MainActivity", "✅ MainActivity created successfully")

        } catch (e: DeadObjectException) {
            Log.w("MainActivity", "DeadObjectException (safe to ignore): ${e.message}")
        } catch (e: Exception) {
            Log.e("MainActivity", "❌ CRASH in onCreate: ${e.message}")
            e.printStackTrace()
        }
    }
    private fun configureScreenSettings() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val keepScreenOn = prefs.getBoolean("keep_screen_on", true)

        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Log.d("MainActivity", "Screen on: ENABLED")
        } else {
            Log.d("MainActivity", "Screen on: DISABLED")
        }
    }
    private fun setupWebView() {
        webView = findViewById(R.id.webview)
        if (webView == null) {
            throw Exception("WebView not found in layout!")
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webView.webViewClient = WebViewClient()
        Log.d("MainActivity", "✅ WebView configured")
    }
    private fun initializeCastFramework() {
        try {
            val castContext = CastContext.getSharedInstance(this)
            Log.d("MainActivity", "✅ Cast context initialized successfully")
        } catch (e: Exception) {
            Log.e("MainActivity", "❌ Cast context error: ${e.message}")
        }
    }
    private fun setupMediaRouteButton() {
        try {
            val mediaRouteButton = findViewById<MediaRouteButton>(R.id.media_route_button)
            if (mediaRouteButton != null) {
                CastButtonFactory.setUpMediaRouteButton(this, mediaRouteButton)
                Log.d("MainActivity", "✅ Cast button setup complete")
            } else {
                Log.w("MainActivity", "⚠️ MediaRouteButton not found in layout")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "❌ Error setting up MediaRouteButton: ${e.message}")
        }
    }


    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        val mediaRouteMenuItem = menu?.findItem(R.id.media_route_menu_item)
        if (mediaRouteMenuItem != null) {
            val mediaRouteButton = mediaRouteMenuItem.actionView as? MediaRouteButton

            if (mediaRouteButton != null) {
                CastButtonFactory.setUpMediaRouteButton(this, mediaRouteButton)
                Log.d("MainActivity", "✅ Cast button setup complete")
            } else {
                Log.w("MainActivity", "⚠️ MediaRouteButton not found in actionView")
            }
        }

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_settings -> {
                // Handle settings
                true
            }
            R.id.menu_about -> {
                // Handle about
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }


    override fun onResume() {
        super.onResume()
        if (::castContext.isInitialized) {
            castContext.sessionManager.addSessionManagerListener(
                sessionManagerListener,
                Session::class.java
            )
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            //castContext = null
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in onPause: ${e.message}")
        }
    }

    private var popupMenu: PopupMenu? = null




    override fun onDestroy() {
        super.onDestroy()
        try {
            popupMenu?.dismiss()
            popupMenu = null
        } catch (e: Exception) {
            Log.e("MainActivity", "Error dismissing popupMenu: ${e.message}")
        }
    }


    private fun getStoredUrl(): String? {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        return prefs.getString("cast_url", null)
    }

    private val sessionManagerListener = object : SessionManagerListener<Session> {
        override fun onSessionStarting(session: Session) {}
        override fun onSessionStarted(session: Session, sessionId: String) {}
        override fun onSessionStartFailed(session: Session, p1: Int) {}
        override fun onSessionEnded(session: Session, error: Int) {}
        override fun onSessionResuming(session: Session, p1: String) {}
        override fun onSessionResumed(session: Session, wasSuspended: Boolean) {}
        override fun onSessionResumeFailed(session: Session, p1: Int) {}
        override fun onSessionSuspended(session: Session, reason: Int) {}
        override fun onSessionEnding(session: Session) {}
    }
}
