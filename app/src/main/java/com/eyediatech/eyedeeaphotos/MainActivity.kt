package com.eyediatech.eyedeeaphotos
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var castContext: CastContext
    private lateinit var webView: WebView
    private lateinit var toolbar: Toolbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Setup Toolbar
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)

        // Setup WebView
        webView = findViewById(R.id.webview)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webView.webViewClient = WebViewClient()

        // Initialize Cast Context on background thread
        GlobalScope.launch(Dispatchers.Default) {
            try {
                castContext = CastContext.getSharedInstance(this@MainActivity)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Load URL from SharedPreferences or use default
        val url = getStoredUrl() ?: "http://192.168.86.101"
        webView.loadUrl(url)
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.menu_main, menu)

        if (::castContext.isInitialized) {
            CastButtonFactory.setUpMediaRouteButton(this, menu, R.id.media_route_menu_item)
        }

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(android.content.Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
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
