package com.eyediatech.eyedeeaphotos.sync

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.eyediatech.eyedeeaphotos.api.*
import com.eyediatech.eyedeeaphotos.data.AppDatabase
import com.eyediatech.eyedeeaphotos.data.OfflineSyncSubscription
import com.eyediatech.eyedeeaphotos.data.RefreshRequest
import com.eyediatech.eyedeeaphotos.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.zip.ZipInputStream
import android.util.Log

class OfflineSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val TAG = "OfflineSyncWorker"

    private val dao = AppDatabase.getDatabase(appContext).offlineSyncDao()
    private val apiService = RetrofitClient.instance
    private val authRepository = AuthRepository(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "doWork started")
        val subscriptions = dao.getActiveSubscriptions()
        if (subscriptions.isEmpty()) {
            Log.d(TAG, "No active subscriptions found. Exiting.")
            return@withContext Result.success()
        }

        var token = authRepository.getToken()
        if (token == null) {
            Log.e(TAG, "Access token is null. Retrying later.")
            return@withContext Result.retry()
        }

        for (subscription in subscriptions) {
            try {
                Log.d(TAG, "Starting sync for subscription: ${subscription.id} - ${subscription.displayName}")
                dao.updateStatus(subscription.id, "syncing")
                syncSubscription(subscription, token!!)
                Log.d(TAG, "Sync complete for subscription: ${subscription.id}")
                dao.updateStatus(subscription.id, "idle")
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing subscription: ${subscription.id}", e)
                e.printStackTrace()
                if (e.message?.contains("401") == true) {
                    Log.d(TAG, "Received 401, attempting token refresh")
                    val refreshed = performRefreshSync(authRepository)
                    if (refreshed) {
                        token = authRepository.getToken() ?: return@withContext Result.retry()
                        try {
                            Log.d(TAG, "Retrying sync after token refresh")
                            syncSubscription(subscription, token!!)
                            dao.updateStatus(subscription.id, "idle")
                        } catch (e2: Exception) {
                            Log.e(TAG, "Sync failed after token refresh", e2)
                            dao.updateStatus(subscription.id, "error")
                        }
                    } else {
                        Log.e(TAG, "Token refresh failed")
                        dao.updateStatus(subscription.id, "error")
                    }
                } else if (e.message?.contains("LIMIT_EXCEEDED") == true) {
                    Log.e(TAG, "Limit exceeded for subscription: ${subscription.id}")
                    dao.updateStatus(subscription.id, "limit_blocked")
                } else {
                    dao.updateStatus(subscription.id, "error")
                }
            }
        }
        Log.d(TAG, "doWork completed")
        Result.success()
    }

    private fun performRefreshSync(repo: AuthRepository): Boolean {
        val refreshToken = repo.getRefreshToken() ?: return false
        val req = RefreshRequest(refreshToken)
        val res = apiService.refreshSync(req).execute()
        if (res.isSuccessful) {
            res.body()?.let {
                repo.saveTokens(it.token, it.refreshToken)
                return true
            }
        }
        return false
    }

    private suspend fun syncSubscription(sub: OfflineSyncSubscription, token: String) {
        val authHeader = "Bearer $token"
        // Need to add api base to listUrl if it is relative. We can just use the url from ApiService config.
        val base = RetrofitClient.BASE_URL
        val fullUrl = if (sub.listUrl.startsWith("http")) sub.listUrl else "${base.trimEnd('/')}/${sub.listUrl.trimStart('/')}"

        Log.d(TAG, "Fetching manifest from: $fullUrl")
        val response = apiService.getPhotoManifest(authHeader, fullUrl).execute()
        if (!response.isSuccessful) throw Exception("Failed to get manifest: ${response.code()}")

        val bodyStr = response.body()?.string() ?: throw Exception("Empty manifest")
        val json = JSONObject(bodyStr)

        val lastSyncedIds = try {
            val arr = org.json.JSONArray(sub.lastSyncedPhotoIds)
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (e: Exception) { emptySet<String>() }

        val newPhotos = mutableListOf<ZipPhotoEntry>()

        if (sub.type == "album") {
            val photosArray = json.optJSONArray("photos") ?: return
            Log.d(TAG, "Found ${photosArray.length()} photos in album manifest")
            for (i in 0 until photosArray.length()) {
                val p = photosArray.getJSONObject(i)
                val photoId = p.optString("photo_id")
                if (photoId.isNotEmpty() && !lastSyncedIds.contains(photoId)) {
                    newPhotos.add(ZipPhotoEntry(
                        photo_id = photoId,
                        source_id = p.optLong("source_id"),
                        filename = p.optString("name", p.optString("filename", "$photoId.jpg")),
                        folder_name = sub.folderPath ?: ""
                    ))
                }
            }
        } else {
            val dataArray = json.optJSONArray("data") ?: return
            Log.d(TAG, "Found ${dataArray.length()} photos in story pack manifest")
            for (i in 0 until dataArray.length()) {
                val p = dataArray.getJSONObject(i)
                val photoId = p.optString("photo_id")
                if (photoId.isNotEmpty() && !lastSyncedIds.contains(photoId)) {
                    newPhotos.add(ZipPhotoEntry(
                        photo_id = photoId,
                        source_id = p.optLong("source_id"),
                        filename = p.optString("filename", "$photoId.jpg"),
                        folder_name = p.optString("folder_name", "")
                    ))
                }
            }
        }

        if (newPhotos.isEmpty()) {
            Log.d(TAG, "No new photos to sync for subscription: ${sub.id}")
            return
        }
        
        Log.d(TAG, "Syncing ${newPhotos.size} new photos")

        val newlySyncedIds = mutableListOf<String>()

        if (newPhotos.size <= 5) {
            // direct download
            Log.d(TAG, "Using direct download for ${newPhotos.size} photos")
            for (photo in newPhotos) {
                val res = apiService.downloadPhoto(authHeader, sub.householdId.toString(), photo.photo_id, true, sub.downloadVariant).execute()
                if (res.isSuccessful) {
                    val bytes = res.body()?.bytes()
                    if (bytes != null) {
                        val relPath = GalleryPathResolver.curatedPathToRelativePath(photo.folder_name)
                        Log.d(TAG, "Downloaded direct photo ${photo.photo_id}, saving to $relPath")
                        insertSyncedPhoto(relPath, photo.filename, bytes)
                        newlySyncedIds.add(photo.photo_id)
                    }
                } else if (res.code() == 429 || res.code() == 503) {
                    throw Exception("LIMIT_EXCEEDED")
                }
            }
        } else {
            // batch zip download chunked by 100
            Log.d(TAG, "Using batched zip download for ${newPhotos.size} photos")
            val chunks = newPhotos.chunked(100)
            for (chunk in chunks) {
                val req = DownloadZipRequest(
                    size = sub.downloadVariant,
                    photoIds = chunk
                )
                Log.d(TAG, "Downloading zip chunk of ${chunk.size} photos")
                val res = apiService.downloadZip(authHeader, req).execute()
                if (res.isSuccessful) {
                    val stream = res.body()?.byteStream()
                    if (stream != null) {
                        val zis = ZipInputStream(stream)
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory) {
                                val bytes = zis.readBytes()
                                // The zip might not contain exactly the same filename if there were duplicates.
                                // But let's find the matching entry.
                                // The server preserves filename.
                                val filename = entry.name.substringAfterLast("/")
                                val matchingPhoto = chunk.find { it.filename == filename } ?: chunk.firstOrNull()
                                if (matchingPhoto != null) {
                                    val relPath = GalleryPathResolver.curatedPathToRelativePath(matchingPhoto.folder_name)
                                    Log.d(TAG, "Extracted photo from zip ${matchingPhoto.photo_id}, saving to $relPath")
                                    insertSyncedPhoto(relPath, filename, bytes)
                                    newlySyncedIds.add(matchingPhoto.photo_id)
                                }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                        zis.close()
                    }
                } else if (res.code() == 429 || res.code() == 503) {
                    throw Exception("LIMIT_EXCEEDED")
                } else if (res.code() == 400) {
                     // Try direct if zip fails with 400
                     Log.w(TAG, "Zip download returned 400, falling back to direct download for chunk")
                     for (photo in chunk) {
                        val resDirect = apiService.downloadPhoto(authHeader, sub.householdId.toString(), photo.photo_id, true, sub.downloadVariant).execute()
                        if (resDirect.isSuccessful) {
                            val bytesDirect = resDirect.body()?.bytes()
                            if (bytesDirect != null) {
                                val relPath = GalleryPathResolver.curatedPathToRelativePath(photo.folder_name)
                                insertSyncedPhoto(relPath, photo.filename, bytesDirect)
                                newlySyncedIds.add(photo.photo_id)
                            }
                        }
                    }
                }
            }
        }

        if (newlySyncedIds.isNotEmpty()) {
            val allIds = lastSyncedIds.toMutableSet()
            allIds.addAll(newlySyncedIds)
            val updatedJsonIds = org.json.JSONArray(allIds).toString()
            Log.d(TAG, "Updating subscription with new synced ids. Total synced: ${allIds.size}")
            val updatedSub = sub.copy(
                lastSyncedPhotoIds = updatedJsonIds,
                lastSyncAt = System.currentTimeMillis()
            )
            dao.update(updatedSub)
        }
    }

    private fun insertSyncedPhoto(relativePath: String, filename: String, bytes: ByteArray) {
        val resolver = applicationContext.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        }
    }
}
