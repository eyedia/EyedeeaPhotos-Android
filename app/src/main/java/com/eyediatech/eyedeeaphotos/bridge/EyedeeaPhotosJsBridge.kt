package com.eyediatech.eyedeeaphotos.bridge

import android.webkit.JavascriptInterface
import com.eyediatech.eyedeeaphotos.sync.OfflineSyncCoordinator

class EyedeeaPhotosJsBridge(
    private val syncCoordinator: OfflineSyncCoordinator
) {
    @JavascriptInterface
    fun requestOfflineSync(jsonPayload: String): Boolean {
        return syncCoordinator.onSyncRequested(jsonPayload)
    }
}
