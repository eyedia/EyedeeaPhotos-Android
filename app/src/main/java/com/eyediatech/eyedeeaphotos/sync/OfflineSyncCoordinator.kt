package com.eyediatech.eyedeeaphotos.sync

import android.content.Context
import androidx.work.*
import com.eyediatech.eyedeeaphotos.data.AppDatabase
import com.eyediatech.eyedeeaphotos.data.OfflineSyncSubscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import android.util.Log

class OfflineSyncCoordinator(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val dao = AppDatabase.getDatabase(context).offlineSyncDao()
    private val TAG = "OfflineSync"

    fun onSyncRequested(jsonPayload: String?): Boolean {
        Log.d(TAG, "onSyncRequested payload: $jsonPayload")
        if (jsonPayload.isNullOrBlank() || jsonPayload == "undefined" || jsonPayload == "null") {
            Log.w(TAG, "Received invalid or empty payload, aborting.")
            return false
        }
        try {
            val json = JSONObject(jsonPayload)
            val type = json.optString("type")
            if (type.isEmpty()) return false

            val householdId = json.optLong("household_id")
            if (householdId == 0L) return false

            val displayName = json.optString("display_name")
            val listUrl = json.optString("list_url")
            if (displayName.isEmpty() || listUrl.isEmpty()) {
                Log.e(TAG, "Missing displayName or listUrl")
                return false
            }

            val downloadVariantHint = if (json.has("download_variant")) json.optString("download_variant") else null
            val downloadVariant = resolveDownloadVariant(context, downloadVariantHint)
            Log.d(TAG, "Resolved downloadVariant: $downloadVariant")

            val subscription = when (type) {
                "album" -> {
                    val sourceId = json.optLong("source_id")
                    val folderPath = json.optString("folder_path")
                    if (sourceId == 0L || folderPath.isEmpty()) {
                        Log.e(TAG, "Missing sourceId or folderPath for album")
                        return false
                    }

                    val relativePath = GalleryPathResolver.curatedPathToRelativePath(folderPath)
                    Log.d(TAG, "Computed relativePath: $relativePath")

                    OfflineSyncSubscription(
                        type = type,
                        householdId = householdId,
                        sourceId = sourceId,
                        folderPath = folderPath,
                        displayName = displayName,
                        galleryRelativePath = relativePath,
                        listUrl = listUrl,
                        downloadVariant = downloadVariant
                    )
                }
                "story_pack" -> {
                    val storyPackId = json.optLong("story_pack_id")
                    if (storyPackId == 0L) {
                        Log.e(TAG, "Missing storyPackId for story_pack")
                        return false
                    }

                    OfflineSyncSubscription(
                        type = type,
                        householdId = householdId,
                        storyPackId = storyPackId,
                        displayName = displayName,
                        listUrl = listUrl,
                        downloadVariant = downloadVariant
                    )
                }
                else -> return false
            }

            scope.launch {
                Log.d(TAG, "Upserting subscription to DB: $subscription")
                dao.upsert(subscription)
                
                // Enqueue work
                val workName = if (type == "album") {
                    "offline-sync-album-$householdId-${subscription.sourceId}-${subscription.folderPath.hashCode()}"
                } else {
                    "offline-sync-pack-$householdId-${subscription.storyPackId}"
                }

                Log.d(TAG, "Scheduling WorkManager jobs with name: $workName")
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val request = OneTimeWorkRequestBuilder<OfflineSyncWorker>()
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(context)
                    .enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request)
                    
                val periodicRequest = PeriodicWorkRequestBuilder<OfflineSyncWorker>(12, java.util.concurrent.TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork("$workName-periodic", ExistingPeriodicWorkPolicy.KEEP, periodicRequest)
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error in onSyncRequested", e)
            e.printStackTrace()
            return false
        }
    }

    private fun resolveDownloadVariant(context: Context, payloadHint: String?): String {
        payloadHint?.takeIf { it in setOf("small", "medium", "large") }?.let { return it }
        val sw = context.resources.configuration.smallestScreenWidthDp
        return when {
            sw >= 840 -> "large"
            sw >= 600 -> "medium"
            else -> "small"
        }
    }
}
