package com.eyediatech.eyedeeaphotos
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.PopupMenu
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var castContext: CastContext
    private lateinit var webView: WebView
    private lateinit var menuButton: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            Log.d("MainActivity", "Setting content view...")
            setContentView(R.layout.activity_main)

            Log.d("MainActivity", "Hiding action bar...")
            supportActionBar?.hide()

            Log.d("MainActivity", "Setting up WebView...")
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

            Log.d("MainActivity", "Setting up menu button...")
            menuButton = findViewById(R.id.menu_button)
            if (menuButton == null) {
                throw Exception("Menu button not found in layout!")
            }

            menuButton.setOnClickListener {
                showMenu(it)
            }

            Log.d("MainActivity", "Initializing Cast context...")
            GlobalScope.launch(Dispatchers.Default) {
                try {
                    castContext = CastContext.getSharedInstance(this@MainActivity)
                    Log.d("MainActivity", "Cast context initialized")
                } catch (e: Exception) {
                    Log.e("MainActivity", "Cast context error: ${e.message}")
                }
            }

            Log.d("MainActivity", "Loading URL...")
            val url = getStoredUrl() ?: "http://192.168.86.101"
            Log.d("MainActivity", "URL to load: $url")
            webView.loadUrl(url)

            Log.d("MainActivity", "MainActivity created successfully")

        } catch (e: Exception) {
            Log.e("MainActivity", "CRASH in onCreate: ${e.message}")
            e.printStackTrace()
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
        if (::castContext.isInitialized) {
            castContext.sessionManager.removeSessionManagerListener(
                sessionManagerListener,
                Session::class.java
            )
        }
        super.onPause()
    }

    private fun showMenu(view: android.view.View) {
        val popupMenu = PopupMenu(this, view)
        popupMenu.menuInflater.inflate(R.menu.menu_main, popupMenu.menu)

        if (::castContext.isInitialized) {
            com.google.android.gms.cast.framework.CastButtonFactory.setUpMediaRouteButton(
                this,
                popupMenu.menu,
                R.id.media_route_menu_item
            )
        }

        popupMenu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    startActivity(android.content.Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }

        popupMenu.show()
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
