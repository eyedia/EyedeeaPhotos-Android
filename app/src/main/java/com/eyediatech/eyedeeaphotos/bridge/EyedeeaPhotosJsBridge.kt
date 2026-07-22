package com.eyediatech.eyedeeaphotos.bridge

import android.webkit.JavascriptInterface
import com.eyediatech.eyedeeaphotos.sync.OfflineSyncCoordinator

class EyedeeaPhotosJsBridge(
    private val syncCoordinator: OfflineSyncCoordinator,
    private val onOpenNativeUpload: ((String?) -> Boolean)? = null
) {
    @JavascriptInterface
    fun requestOfflineSync(jsonPayload: String?): Boolean {
        return syncCoordinator.onSyncRequested(jsonPayload)
    }

    /**
     * Opens the native Android photo picker / upload queue.
     * Optional JSON: {"albumPath":"curated/..."} for curated destination; omit/null for raw.
     */
    @JavascriptInterface
    fun openNativeUpload(jsonPayload: String?): Boolean {
        val handler = onOpenNativeUpload ?: return false
        return try {
            handler(jsonPayload)
        } catch (_: Exception) {
            false
        }
    }
}
