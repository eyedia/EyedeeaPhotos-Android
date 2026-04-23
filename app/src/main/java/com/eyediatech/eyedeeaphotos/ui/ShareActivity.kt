package com.eyediatech.eyedeeaphotos.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.eyediatech.eyedeeaphotos.api.RetrofitClient
import com.eyediatech.eyedeeaphotos.databinding.ActivityShareBinding
import com.eyediatech.eyedeeaphotos.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream

class ShareActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShareBinding
    private lateinit var authRepository: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShareBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authRepository = AuthRepository(this)

        if (!authRepository.isAuthenticated()) {
            Toast.makeText(this, "Please log in first", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        handleIntent(intent)

        binding.cancelButton.setOnClickListener {
            finish()
        }
    }

    private fun handleIntent(intent: Intent) {
        val action = intent.action
        val type = intent.type

        if (type?.startsWith("image/") == true) {
            when (action) {
                Intent.ACTION_SEND -> {
                    val imageUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    if (imageUri != null) {
                        startUpload(listOf(imageUri))
                    }
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    val imageUris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    if (imageUris != null) {
                        startUpload(imageUris)
                    }
                }
            }
        }
    }

    private fun startUpload(uris: List<Uri>) {
        lifecycleScope.launch {
            try {
                val householdId = authRepository.getHouseholdId() ?: throw Exception("Missing household ID")
                var sourceId = authRepository.getSourceId()

                if (sourceId == null) {
                    val response = RetrofitClient.instance.getSources(householdId)
                    if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                        sourceId = response.body()!![0].id
                    } else {
                        throw Exception("No source available")
                    }
                }

                // Quota Check
                val quotaResponse = RetrofitClient.instance.getQuotaSummary(householdId, sourceId!!)
                if (quotaResponse.isSuccessful && quotaResponse.body()?.canUpload == false) {
                    throw Exception("Quota exceeded: ${quotaResponse.body()?.reason}")
                }

                val totalFiles = uris.size
                binding.progressDetailTextView.text = "0/$totalFiles files"

                // Batching by count and size
                val batches = prepareBatches(uris)
                var uploadedCount = 0

                for (batch in batches) {
                    uploadBatch(batch, householdId, sourceId)
                    uploadedCount += batch.size
                    binding.uploadProgressBar.progress = (uploadedCount * 100 / totalFiles)
                    binding.progressDetailTextView.text = "$uploadedCount/$totalFiles files"
                }

                // Trigger Scan
                RetrofitClient.instance.triggerScan("Bearer ${authRepository.getToken()}", householdId, sourceId)

                binding.statusTextView.text = "Upload complete!"
                Toast.makeText(this@ShareActivity, "Upload complete!", Toast.LENGTH_SHORT).show()
                delayAndFinish()

            } catch (e: Exception) {
                binding.statusTextView.text = "Upload failed: ${e.message}"
                Log.e("UPLOAD_ERROR", "Error: ${e.message}")
            }
        }
    }

    private suspend fun prepareBatches(uris: List<Uri>): List<List<Uri>> = withContext(Dispatchers.IO) {
        val result = mutableListOf<List<Uri>>()
        var currentBatch = mutableListOf<Uri>()
        var currentBatchSize = 0L
        val maxBatchSize = 80 * 1024 * 1024 // 80 MB

        for (uri in uris) {
            val size = getUriSize(uri)
            if (currentBatch.size >= 100 || (currentBatchSize + size > maxBatchSize && currentBatch.isNotEmpty())) {
                result.add(currentBatch)
                currentBatch = mutableListOf()
                currentBatchSize = 0
            }
            currentBatch.add(uri)
            currentBatchSize += size
        }
        if (currentBatch.isNotEmpty()) {
            result.add(currentBatch)
        }
        result
    }

    private fun getUriSize(uri: Uri): Long {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            cursor.moveToFirst()
            cursor.getLong(sizeIndex)
        } ?: 0L
    }

    private suspend fun uploadBatch(uris: List<Uri>, householdId: String, sourceId: String) {
        val parts = mutableListOf<MultipartBody.Part>()
        for (uri in uris) {
            val file = getFileFromUri(uri) ?: continue
            val requestFile = file.asRequestBody("image/*".toMediaTypeOrNull())
            parts.add(MultipartBody.Part.createFormData("photos", file.name, requestFile))
        }

        val folderPath = "raw".toRequestBody("text/plain".toMediaTypeOrNull())
        val token = "Bearer ${authRepository.getToken()}"

        val response = RetrofitClient.instance.uploadPhotos(token, householdId, sourceId, folderPath, parts)
        if (!response.isSuccessful) {
            throw Exception("Batch upload failed: ${response.message()}")
        }
    }

    private suspend fun getFileFromUri(uri: Uri): File? = withContext(Dispatchers.IO) {
        try {
            val contentResolver = contentResolver
            val inputStream = contentResolver.openInputStream(uri) ?: return@withContext null
            val fileName = "upload_${System.currentTimeMillis()}.jpg"
            val file = File(cacheDir, fileName)
            val outputStream = FileOutputStream(file)
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()
            file
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun delayAndFinish() {
        withContext(Dispatchers.Main) {
            kotlinx.coroutines.delay(2000)
            finish()
        }
    }
}
