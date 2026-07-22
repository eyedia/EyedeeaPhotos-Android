package com.eyediatech.eyedeeaphotos.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.webkit.WebView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * SwipeRefresh that cooperates with WebView scrolling and leaves the top system
 * gesture zone free so the notification shade can be pulled down.
 */
class WebViewSwipeRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SwipeRefreshLayout(context, attrs) {

    var webView: WebView? = null

    /** Touches that begin above this Y are not intercepted (status bar / shade). */
    var topGestureExclusionPx: Float = 0f

    private var ignoreGesture = false

    override fun canChildScrollUp(): Boolean {
        val wv = webView ?: return super.canChildScrollUp()
        return wv.scrollY > 0 || wv.canScrollVertically(-1)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ignoreGesture = topGestureExclusionPx > 0f && ev.y <= topGestureExclusionPx
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                ignoreGesture = false
            }
        }
        if (ignoreGesture) return false
        return try {
            super.onInterceptTouchEvent(ev)
        } catch (_: Exception) {
            false
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ignoreGesture) return false
        return try {
            super.onTouchEvent(ev)
        } catch (_: Exception) {
            false
        }
    }
}
