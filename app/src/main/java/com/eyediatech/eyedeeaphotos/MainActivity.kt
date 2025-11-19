package com.eyediatech.eyedeeaphotos

import androidx.mediarouter.app.MediaRouteButton
import android.os.Bundle
import android.os.DeadObjectException
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import com.google.android.gms.cast.framework.CastButtonFactory
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.Session
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadOptions
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastSession

class MainActivity : AppCompatActivity() {

    private lateinit var castContext: CastContext
    private lateinit var mediaRouteButton: MediaRouteButton
    private lateinit var webView: WebView
    private var castSession: Session? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            Log.d("MainActivity", "Setting content view...")
            setContentView(R.layout.activity_main)

            configureScreenSettings()

            Log.d("MainActivity", "Setting up WebView...")
            setupWebView()

            Log.d("MainActivity", "Initializing Cast framework...")
            initializeCastFramework()

            setupMediaRouteButton()

            setupCastSessionListener()

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

    private fun setupCastSessionListener() {
        val castContext = CastContext.getSharedInstance(this)
        val sessionManager = castContext?.sessionManager ?: return

        sessionManager.addSessionManagerListener(
            sessionManagerListener,
            Session::class.java
        )
    }

    private fun sendWebViewContentToCast() {
        val castSessionObj = castSession as? CastSession
        val remoteMediaClient = castSessionObj?.remoteMediaClient ?: return

        try {
            // Test with a public image URL
            val imageUrl = "https://commondatastorage.googleapis.com/gtv-videos-library/sample/images/bigbuckbunny.jpg"

            Log.d("CastSession", "📺 Sending image to Chromecast: $imageUrl")

            val mediaInfo = MediaInfo.Builder(imageUrl)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType("image/jpeg")
                .setMetadata(
                    MediaMetadata(MediaMetadata.MEDIA_TYPE_PHOTO).apply {
                        putString(MediaMetadata.KEY_TITLE, "Slideshow Image")
                        putString(MediaMetadata.KEY_SUBTITLE, "EyeDee Photos")
                    }
                )
                .build()

            val mediaLoadOptions = MediaLoadOptions.Builder()
                .setAutoplay(true)
                .setPlaybackRate(1.0)
                .build()

            remoteMediaClient.load(mediaInfo, mediaLoadOptions)

            Log.d("CastSession", "✅ Image sent to Chromecast successfully")
        } catch (e: Exception) {
            Log.e("CastSession", "❌ Error sending image: ${e.message}")
            e.printStackTrace()
        }
    }


    private fun sendWebViewContentToCast2() {
        val remoteMediaClient = (castSession as? CastSession)?.remoteMediaClient ?: return

        try {
            val mediaInfo = MediaInfo.Builder("https://drive.google.com/file/d/1OiEDnOSsQSL8PN29G_Ita8nnyNYl_Fju/view")
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType("image/png")
                .setMetadata(MediaMetadata(MediaMetadata.MEDIA_TYPE_PHOTO).apply {
                    putString(MediaMetadata.KEY_TITLE, "Test Image")
                })
                .build()

            remoteMediaClient.load(mediaInfo)
            Log.d("CastSession", "✅ Test image sent")
        } catch (e: Exception) {
            Log.e("CastSession", "❌ Error: ${e.message}")
        }
    }

    private fun sendWebViewContentToCast1() {
        val castSessionObj = castSession as? CastSession
        val remoteMediaClient = castSessionObj?.remoteMediaClient ?: return

        try {
            // Get the current WebView URL
            val webViewUrl = webView.url ?: "http://192.168.86.101"

            Log.d("CastSession", "📺 Sending WebView URL to Chromecast: $webViewUrl")

            val mediaInfo = MediaInfo.Builder(webViewUrl)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType("text/html")
                .setMetadata(
                    MediaMetadata(MediaMetadata.MEDIA_TYPE_GENERIC).apply {
                        putString(MediaMetadata.KEY_TITLE, "WebView Content")
                        putString(MediaMetadata.KEY_SUBTITLE, "Streaming from EyeDee Photos")
                    }
                )
                .build()

            val mediaLoadOptions = MediaLoadOptions.Builder()
                .setAutoplay(true)
                .setPlaybackRate(1.0)
                .build()

            remoteMediaClient.load(mediaInfo, mediaLoadOptions)

            Log.d("CastSession", "✅ WebView content sent to Chromecast")
        } catch (e: Exception) {
            Log.e("CastSession", "❌ Error sending content: ${e.message}")
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

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d("WebView", "✅ Page loaded: $url")

                // Auto-send to Cast when page finishes loading
                if (castSession != null) {
                    sendWebViewContentToCast()
                }
            }
        }

        Log.d("MainActivity", "✅ WebView configured")
    }


    private fun initializeCastFramework() {
        try {
            castContext = CastContext.getSharedInstance(this)
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
                true
            }
            R.id.menu_about -> {
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
            if (::castContext.isInitialized) {
                castContext.sessionManager.removeSessionManagerListener(
                    sessionManagerListener,
                    Session::class.java
                )
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in onPause: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (::castContext.isInitialized) {
                castContext.sessionManager.removeSessionManagerListener(
                    sessionManagerListener,
                    Session::class.java
                )
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in onDestroy: ${e.message}")
        }
    }

    private fun getStoredUrl(): String? {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        return prefs.getString("cast_url", null)
    }

    private val sessionManagerListener = object : SessionManagerListener<Session> {
        override fun onSessionStarting(session: Session) {
            Log.d("CastSession", "📍 Session starting...")
        }

        override fun onSessionStarted(session: Session, sessionId: String) {
            Log.d("CastSession", "✅ Session started: $sessionId")
            castSession = session
            sendWebViewContentToCast()
        }

        override fun onSessionStartFailed(session: Session, error: Int) {
            Log.d("CastSession", "❌ Session start failed: $error")
            castSession = null
        }

        override fun onSessionEnded(session: Session, error: Int) {
            Log.d("CastSession", "❌ Session ended: $error")
            castSession = null
        }

        override fun onSessionResuming(session: Session, sessionId: String) {
            Log.d("CastSession", "📍 Session resuming...")
        }

        override fun onSessionResumed(session: Session, wasSuspended: Boolean) {
            Log.d("CastSession", "✅ Session resumed")
            castSession = session
            sendWebViewContentToCast()
        }

        override fun onSessionResumeFailed(session: Session, error: Int) {
            Log.d("CastSession", "❌ Session resume failed: $error")
            castSession = null
        }

        override fun onSessionSuspended(session: Session, reason: Int) {
            Log.d("CastSession", "⏸️ Session suspended: $reason")
        }

        override fun onSessionEnding(session: Session) {
            Log.d("CastSession", "⏹️ Session ending")
            castSession = null
        }
    }
}
