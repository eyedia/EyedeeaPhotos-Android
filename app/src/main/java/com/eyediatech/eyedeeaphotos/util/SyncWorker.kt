package com.eyediatech.eyedeeaphotos.util

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.eyediatech.eyedeeaphotos.api.RetrofitClient
import com.eyediatech.eyedeeaphotos.data.AppDatabase
import com.eyediatech.eyedeeaphotos.data.QueuedPhoto
import com.eyediatech.eyedeeaphotos.repository.AuthRepository
import com.eyediatech.eyedeeaphotos.repository.PhotoRepository
import com.eyediatech.eyedeeaphotos.utils.FileLogger
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val authRepository = AuthRepository(context)
    private val photoRepository = PhotoRepository(AppDatabase.getDatabase(context).photoDao())

    private suspend fun setupForegroundInfo() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "SyncChannel",
                "Photo Sync",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            val manager = applicationContext.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification = androidx.core.app.NotificationCompat.Builder(applicationContext, "SyncChannel")
            .setContentTitle("Syncing Photos")
            .setContentText("Uploading photos in the background")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
            
        val foregroundInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            androidx.work.ForegroundInfo(2, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            androidx.work.ForegroundInfo(2, notification)
        }
        
        try {
            setForeground(foregroundInfo)
        } catch (e: Exception) {
            FileLogger.e("SyncWorker", "Failed to set foreground service", e)
        }
    }

    override suspend fun doWork(): Result {
        FileLogger.d("SyncWorker", "Sync starting...")
        setupForegroundInfo()
        
        val token = authRepository.getToken() ?: return Result.failure()
        val authHeader = "Bearer $token"
        val householdId = authRepository.getHouseholdId() ?: return Result.failure()
        var sourceId = authRepository.getSourceId()

        if (sourceId == null) {
            val response = RetrofitClient.instance.getSources(authHeader, householdId)
            if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                sourceId = response.body()!![0].id
            } else {
                return Result.failure()
            }
        }

        val photosToUpload = photoRepository.getPhotosToUpload()
        if (photosToUpload.isEmpty()) {
            FileLogger.d("SyncWorker", "No photos to upload")
            return Result.success()
        }

        try {
            // If the worker was cancelled or failed previously, some might be stuck in UPLOADING
            photoRepository.resetUploadingStatus()

            // 1. Quota Check
            FileLogger.d("SyncWorker", "Checking quota for Household: $householdId, Source: $sourceId")
            val quotaResponse = RetrofitClient.instance.getQuotaSummary(authHeader, householdId, sourceId!!)
            
            if (!quotaResponse.isSuccessful) {
                FileLogger.e("SyncWorker", "Quota check failed: ${quotaResponse.code()} - ${quotaResponse.errorBody()?.string()}")
                return Result.retry()
            }
            
            if (quotaResponse.body()?.data?.hasQuota == false) {
                FileLogger.w("SyncWorker", "No quota available for upload")
                return Result.success()
            }

            // Determine folder name based on user
            val username = authRepository.getUsername() ?: "user"
            val prefix = username.filter { it.isLetterOrDigit() }.take(6).lowercase()
            val folderName = if (prefix.isNotEmpty()) "${prefix}_droid" else "android"

            val (curatedPhotos, rawPhotos) = photosToUpload.partition { it.destinationAlbum != null }

            // 2. Batch Upload Raw Photos
            if (rawPhotos.isNotEmpty()) {
                val batchSize = 1 // Reduced from 20 for extreme slow network reliability
                val batches = rawPhotos.chunked(batchSize)
                FileLogger.d("SyncWorker", "Starting upload of ${rawPhotos.size} raw photos in ${batches.size} batches")
                
                for ((index, batch) in batches.withIndex()) {
                    FileLogger.d("SyncWorker", "Uploading raw batch ${index + 1}/${batches.size}")
                    uploadBatch(batch, token, householdId, sourceId, folderName, scanAfterUpload = true)
                }

                // 3. Explicit Scan Trigger for Raw
                FileLogger.d("SyncWorker", "Triggering scan for raw processing...")
                val scanResponse = RetrofitClient.instance.triggerScan(
                    "Bearer $token", householdId, sourceId, "raw", "background", 4
                )
                FileLogger.d("SyncWorker", "Scan trigger result: ${scanResponse.code()}")
            }

            // 4. Batch Upload Curated Photos
            if (curatedPhotos.isNotEmpty()) {
                val byAlbum = curatedPhotos.groupBy { it.destinationAlbum!! }
                for ((albumPath, albumPhotos) in byAlbum) {
                    val batchSize = 1 // Reduced from 20 for extreme slow network reliability
                    val batches = albumPhotos.chunked(batchSize)
                    FileLogger.d("SyncWorker", "Starting upload of ${albumPhotos.size} curated photos to $albumPath")
                    
                    val allUploadedFiles = mutableListOf<com.eyediatech.eyedeeaphotos.data.UploadedFile>()
                    for ((index, batch) in batches.withIndex()) {
                        FileLogger.d("SyncWorker", "Uploading curated batch ${index + 1}/${batches.size} to $albumPath")
                        val uploadedFiles = uploadBatch(batch, token, householdId, sourceId, albumPath, scanAfterUpload = false)
                        allUploadedFiles.addAll(uploadedFiles)
                    }

                    if (allUploadedFiles.isNotEmpty()) {
                        triggerCuratedAnalysis(token, householdId, sourceId, albumPath, allUploadedFiles)
                    }
                }
            }

            return Result.success()
        } catch (e: Exception) {
            FileLogger.e("SyncWorker", "Sync exception occurred", e)
            return Result.retry()
        }
    }

    private suspend fun uploadBatch(
        batch: List<QueuedPhoto>, 
        token: String, 
        householdId: String, 
        sourceId: String, 
        folderName: String, 
        scanAfterUpload: Boolean
    ): List<com.eyediatech.eyedeeaphotos.data.UploadedFile> {
        val photosParts = mutableListOf<MultipartBody.Part>()
        val relativePathsParts = mutableListOf<okhttp3.RequestBody>()

        for (photo in batch) {
            val file = File(photo.internalPath)
            if (file.exists()) {
                FileLogger.d("SyncWorker", "Adding to batch: ${file.name}")
                val requestFile = file.asRequestBody("image/*".toMediaTypeOrNull())
                photosParts.add(MultipartBody.Part.createFormData("photos", file.name, requestFile))
                
                val relPathStr = if (scanAfterUpload) "$folderName/${file.name}" else file.name
                val relativePath = relPathStr.toRequestBody("text/plain".toMediaTypeOrNull())
                relativePathsParts.add(relativePath)
                
                photoRepository.updateStatus(photo.id, "UPLOADING")
            } else {
                photoRepository.delete(photo)
            }
        }

        if (photosParts.isEmpty()) return emptyList()

        // For curated upload, folderPath is the destination folder (e.g. curated/...)
        // For raw, it is "raw"
        val folderPathStr = if (scanAfterUpload) "raw" else folderName
        val folderPath = folderPathStr.toRequestBody("text/plain".toMediaTypeOrNull())
        // Always pass "false" to the API to prevent the server from running expensive scans 
        // after every single file. We trigger the scan explicitly at the end of the batch!
        val scanAfterUploadBody = "false".toRequestBody("text/plain".toMediaTypeOrNull())
        val authHeader = "Bearer $token"

        val response = RetrofitClient.instance.uploadPhotos(
            authHeader, householdId, sourceId, folderPath, scanAfterUploadBody, photosParts, relativePathsParts.toTypedArray()
        )

        FileLogger.d("SyncWorker", "Upload API response code: ${response.code()}")

        if (response.isSuccessful) {
            FileLogger.d("SyncWorker", "Batch upload successful. Cleaning up files.")
            for (photo in batch) {
                val file = File(photo.internalPath)
                if (file.exists()) file.delete()
                photoRepository.delete(photo)
            }
            return response.body()?.uploaded ?: emptyList()
        } else {
            val errorMsg = response.errorBody()?.string() ?: "Unknown error"
            FileLogger.e("SyncWorker", "Batch upload failed: $errorMsg")
            for (photo in batch) {
                photoRepository.updateStatus(photo.id, "FAILED")
            }
            throw Exception("Batch upload failed: $errorMsg")
        }
    }

    private suspend fun triggerCuratedAnalysis(
        token: String, 
        householdId: String, 
        sourceId: String, 
        albumPath: String, 
        uploadedFiles: List<com.eyediatech.eyedeeaphotos.data.UploadedFile>
    ) {
        val isoFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        isoFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val generatedAt = isoFormat.format(java.util.Date())
        val idempotencyKey = "curated-${System.currentTimeMillis()}-android"

        val curatedFiles = uploadedFiles.mapIndexed { index, file ->
            val mimeType = if (file.name.lowercase().endsWith(".png")) "image/png" else "image/jpeg"
            com.eyediatech.eyedeeaphotos.data.CuratedUploadFile(
                id = "file_${index + 1}",
                relative_path = file.name,
                name = file.name,
                size_bytes = file.size,
                mime_type = mimeType,
                last_modified_ms = null,
                sha256_hash = null,
                source = "new_upload",
                status = "accepted",
                skip_reason = null
            )
        }

        val summary = com.eyediatech.eyedeeaphotos.data.CuratedUploadSummary(
            total_files = curatedFiles.size,
            uploaded_count = curatedFiles.size,
            skipped_count = 0,
            by_skip_reason = emptyMap()
        )

        val uploadId = "upload_1"
        val curatedUpload = com.eyediatech.eyedeeaphotos.data.CuratedUpload(
            id = uploadId,
            destination_path = albumPath,
            summary = summary,
            files = curatedFiles
        )

        val topLevel = com.eyediatech.eyedeeaphotos.data.CuratedRequestTopLevel(
            schema_version = "curated-request-v1",
            idempotency_key = idempotencyKey,
            generated_at = generatedAt,
            household_id = householdId.toLong(),
            source_id = sourceId.toLong(),
            dir_type = "curated",
            process_count = 4,
            insert_into_db = true,
            current_folder_path = albumPath
        )

        val curatedRequest = com.eyediatech.eyedeeaphotos.data.CuratedRequest(
            top_level = topLevel,
            uploads = listOf(curatedUpload),
            operations = emptyList()
        )

        val request = com.eyediatech.eyedeeaphotos.data.BulkOperationsRequest(
            schema_version = "curated-request-v1",
            job_code = "analyze_photos_curated",
            idempotency_key = idempotencyKey,
            generated_at = generatedAt,
            household_id = householdId.toLong(),
            source_id = sourceId.toLong(),
            dir_type = "curated",
            process_count = 4,
            insert_into_db = true,
            curated_request = curatedRequest
        )

        val response = RetrofitClient.instance.bulkOperations(
            "Bearer $token", householdId, sourceId, request
        )

        FileLogger.d("SyncWorker", "Trigger curated analysis response code: ${response.code()}")
        if (!response.isSuccessful) {
            FileLogger.e("SyncWorker", "Trigger curated analysis failed: ${response.errorBody()?.string()}")
        }
    }
}
