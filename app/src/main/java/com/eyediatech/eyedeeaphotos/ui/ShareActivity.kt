package com.eyediatech.eyedeeaphotos.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.eyediatech.eyedeeaphotos.data.AppDatabase
import com.eyediatech.eyedeeaphotos.data.QueuedPhoto
import com.eyediatech.eyedeeaphotos.databinding.ActivityShareBinding
import com.eyediatech.eyedeeaphotos.repository.AuthRepository
import com.eyediatech.eyedeeaphotos.repository.PhotoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ShareActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShareBinding
    private lateinit var authRepository: AuthRepository
    private lateinit var photoRepository: PhotoRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShareBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authRepository = AuthRepository(this)
        photoRepository = PhotoRepository(AppDatabase.getDatabase(this).photoDao())

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
                        queuePhotos(listOf(imageUri))
                    }
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    val imageUris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    if (imageUris != null) {
                        // Filter for images only
                        val filteredUris = imageUris.filter { uri ->
                            contentResolver.getType(uri)?.startsWith("image/") == true
                        }
                        if (filteredUris.isNotEmpty()) {
                            queuePhotos(filteredUris)
                        } else {
                            Toast.makeText(this, "Only photos can be shared", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }
                }
            }
        } else {
            Toast.makeText(this, "Only photos can be shared", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun queuePhotos(uris: List<Uri>) {
        lifecycleScope.launch {
            binding.statusTextView.text = "Queuing photos..."
            binding.uploadProgressBar.isIndeterminate = false
            binding.uploadProgressBar.max = uris.size
            
            var count = 0
            for (uri in uris) {
                val internalFile = copyToInternalStorage(uri)
                if (internalFile != null) {
                    val queuedPhoto = QueuedPhoto(
                        fileUri = uri.toString(),
                        internalPath = internalFile.absolutePath,
                        fileName = internalFile.name
                    )
                    photoRepository.insert(queuedPhoto)
                }
                count++
                binding.uploadProgressBar.progress = count
                binding.progressDetailTextView.text = "$count/${uris.size} photos queued"
            }

            Toast.makeText(this@ShareActivity, "Photos added to queue", Toast.LENGTH_SHORT).show()
            val intent = Intent(this@ShareActivity, SettingsActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
            finish()
        }
    }

    private suspend fun copyToInternalStorage(uri: Uri): File? = withContext(Dispatchers.IO) {
        try {
            var fileName = ""
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex)
                }
            }

            if (fileName.isEmpty()) {
                val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(contentResolver.getType(uri)) ?: "jpg"
                fileName = "photo_${System.currentTimeMillis()}_${(0..1000).random()}.$extension"
            }

            val fileDir = File(filesDir, "queue")
            if (!fileDir.exists()) fileDir.mkdirs()
            
            // Handle duplicate names in internal storage
            var finalFile = File(fileDir, fileName)
            if (finalFile.exists()) {
                val nameWithoutExt = fileName.substringBeforeLast(".")
                val ext = fileName.substringAfterLast(".", "jpg")
                finalFile = File(fileDir, "${nameWithoutExt}_${System.currentTimeMillis()}.$ext")
            }

            contentResolver.openInputStream(uri)?.use { input ->
                finalFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            finalFile
        } catch (e: Exception) {
            Log.e("SHARE_ERROR", "Error copying file", e)
            null
        }
    }
}
