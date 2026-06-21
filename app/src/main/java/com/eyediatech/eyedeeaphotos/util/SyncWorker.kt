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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val authRepository = AuthRepository(context)
    private val photoRepository = PhotoRepository(AppDatabase.getDatabase(context).photoDao())

    override suspend fun doWork(): Result {
        Log.d("SyncWorker", "Sync starting...")
        
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
            Log.d("SyncWorker", "No photos to upload")
            return Result.success()
        }

        try {
            // If the worker was cancelled or failed previously, some might be stuck in UPLOADING
            photoRepository.resetUploadingStatus()

            // 1. Quota Check
            Log.d("SyncWorker", "Checking quota for Household: $householdId, Source: $sourceId")
            val quotaResponse = RetrofitClient.instance.getQuotaSummary(authHeader, householdId, sourceId!!)
            
            if (!quotaResponse.isSuccessful) {
                Log.e("SyncWorker", "Quota check failed: ${quotaResponse.code()} - ${quotaResponse.errorBody()?.string()}")
                return Result.retry()
            }
            
            if (quotaResponse.body()?.data?.hasQuota == false) {
                Log.w("SyncWorker", "No quota available for upload")
                return Result.success()
            }

            // Determine folder name based on user
            val username = authRepository.getUsername() ?: "user"
            val prefix = username.filter { it.isLetterOrDigit() }.take(6).lowercase()
            val folderName = if (prefix.isNotEmpty()) "${prefix}_droid" else "android"

            // 2. Batch Upload
            val batchSize = 20
            val batches = photosToUpload.chunked(batchSize)
            Log.d("SyncWorker", "Starting upload of ${photosToUpload.size} photos in ${batches.size} batches")
            
            for ((index, batch) in batches.withIndex()) {
                Log.d("SyncWorker", "Uploading batch ${index + 1}/${batches.size}")
                uploadBatch(batch, token, householdId, sourceId, folderName)
            }

            // 3. Explicit Scan Trigger
            Log.d("SyncWorker", "Triggering scan for processing...")
            val scanResponse = RetrofitClient.instance.triggerScan(
                "Bearer $token", householdId, sourceId, "raw", "background", 4
            )
            Log.d("SyncWorker", "Scan trigger result: ${scanResponse.code()}")

            return Result.success()
        } catch (e: Exception) {
            Log.e("SyncWorker", "Sync exception occurred", e)
            return Result.retry()
        }
    }

    private suspend fun uploadBatch(batch: List<QueuedPhoto>, token: String, householdId: String, sourceId: String, folderName: String) {
        val photosParts = mutableListOf<MultipartBody.Part>()
        val relativePathsParts = mutableListOf<okhttp3.RequestBody>()

        for (photo in batch) {
            val file = File(photo.internalPath)
            if (file.exists()) {
                Log.d("SyncWorker", "Adding to batch: ${file.name}")
                val requestFile = file.asRequestBody("image/*".toMediaTypeOrNull())
                photosParts.add(MultipartBody.Part.createFormData("photos", file.name, requestFile))
                
                val relativePath = "$folderName/${file.name}".toRequestBody("text/plain".toMediaTypeOrNull())
                relativePathsParts.add(relativePath)
                
                photoRepository.updateStatus(photo.id, "UPLOADING")
            } else {
                photoRepository.delete(photo)
            }
        }

        if (photosParts.isEmpty()) return

        val folderPath = "raw".toRequestBody("text/plain".toMediaTypeOrNull())
        val scanAfterUpload = "true".toRequestBody("text/plain".toMediaTypeOrNull())
        val authHeader = "Bearer $token"

        val response = RetrofitClient.instance.uploadPhotos(
            authHeader, householdId, sourceId, folderPath, scanAfterUpload, photosParts, relativePathsParts.toTypedArray()
        )

        Log.d("SyncWorker", "Upload API response code: ${response.code()}")

        if (response.isSuccessful) {
            Log.d("SyncWorker", "Batch upload successful. Cleaning up files.")
            for (photo in batch) {
                val file = File(photo.internalPath)
                if (file.exists()) file.delete()
                photoRepository.delete(photo)
            }
        } else {
            val errorMsg = response.errorBody()?.string() ?: "Unknown error"
            Log.e("SyncWorker", "Batch upload failed: $errorMsg")
            for (photo in batch) {
                photoRepository.updateStatus(photo.id, "FAILED")
            }
            throw Exception("Batch upload failed: $errorMsg")
        }
    }
}
