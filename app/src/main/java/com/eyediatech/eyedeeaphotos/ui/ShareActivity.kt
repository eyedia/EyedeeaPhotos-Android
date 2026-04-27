package com.eyediatech.eyedeeaphotos.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import com.eyediatech.eyedeeaphotos.R
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

    companion object {
        private const val TAG = "ShareActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityShareBinding.inflate(layoutInflater)
            setContentView(binding.root)

            Log.e(TAG, "onCreate: Activity started with intent: $intent")

            createNotificationChannel()

            authRepository = AuthRepository(this)
            photoRepository = PhotoRepository(AppDatabase.getDatabase(this).photoDao())

            if (!authRepository.isAuthenticated()) {
                Log.e(TAG, "onCreate: User not authenticated, launching LoginActivity.")
                Toast.makeText(this, R.string.please_log_in_first, Toast.LENGTH_LONG).show()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
                return
            }

            checkLocationPermissionAndHandleIntent()

            binding.cancelButton.setOnClickListener {
                Log.e(TAG, "onCreate: Cancel button clicked, finishing activity.")
                finish()
            }
        } catch (e: Exception) {
            Log.e(TAG, "FATAL ERROR in onCreate", e)
            Toast.makeText(this, "Crash in ShareActivity: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val requestLocationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { _ ->
        Log.e(TAG, "Permission result received. Handling intent.")
        handleIntent(intent)
    }

    private fun checkLocationPermissionAndHandleIntent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_MEDIA_LOCATION
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Requesting ACCESS_MEDIA_LOCATION permission.")
            requestLocationPermissionLauncher.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
        } else {
            Log.e(TAG, "Permission already granted. Handling intent.")
            handleIntent(intent)
        }
    }

    private fun handleIntent(intent: Intent) {
        Log.e(TAG, "handleIntent: Processing intent with action: ${intent.action} and type: ${intent.type}")

        try {
            val bundle = intent.extras
            if (bundle != null) {
                Log.e(TAG, "Intent Extras Keys: " + bundle.keySet().joinToString())
                val streamObj = bundle.get(Intent.EXTRA_STREAM)
                Log.e(TAG, "EXTRA_STREAM type: ${streamObj?.javaClass?.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error inspecting intent extras", e)
        }

        val uris: List<Uri> = try {
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    Log.e(TAG, "handleIntent: Received ACTION_SEND.")
                    @Suppress("DEPRECATION")
                    val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    if (uri != null) {
                        Log.e(TAG, "handleIntent: Extracted single URI: $uri")
                        listOf(uri)
                    } else {
                        Log.e(TAG, "handleIntent: ACTION_SEND received but URI was null.")
                        emptyList()
                    }
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    Log.e(TAG, "handleIntent: Received ACTION_SEND_MULTIPLE.")
                    @Suppress("DEPRECATION")
                    val receivedUris = intent.extras?.getParcelableArrayList<android.os.Parcelable>(Intent.EXTRA_STREAM)?.mapNotNull { it as? Uri }
                    if (receivedUris != null) {
                        Log.e(TAG, "handleIntent: Extracted ${receivedUris.size} URIs.")
                        receivedUris
                    } else {
                        Log.e(TAG, "handleIntent: ACTION_SEND_MULTIPLE received but URI list was null.")
                        emptyList()
                    }
                }
                else -> {
                    Log.e(TAG, "handleIntent: Received unknown action: ${intent.action}. No URIs to process.")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception extracting URIs", e)
            Toast.makeText(this, "Error reading shared files", Toast.LENGTH_LONG).show()
            emptyList()
        }

        if (uris.isEmpty()) {
            Log.e(TAG, "handleIntent: URI list is empty. Finishing activity.")
            Toast.makeText(this, "Error: No files found in share request.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val (supportedUris, unsupportedUris) = uris.partition { uri ->
            try {
                val mimeType = contentResolver.getType(uri) ?: intent.type
                val isImage = mimeType?.startsWith("image/") == true
                Log.e(TAG, "handleIntent: URI: '$uri' | MimeType: '$mimeType' | IsImage: $isImage")
                isImage
            } catch (e: Exception) {
                Log.e(TAG, "handleIntent: Error getting MIME type for $uri", e)
                false
            }
        }

        Log.e(TAG, "handleIntent: Partition complete. Supported: ${supportedUris.size}, Unsupported: ${unsupportedUris.size}")

        if (unsupportedUris.isNotEmpty()) {
            Log.e(TAG, "handleIntent: Showing notification for unsupported files.")
            showUnsupportedFilesNotification()
        }

        if (supportedUris.isNotEmpty()) {
            Log.e(TAG, "handleIntent: Queuing ${supportedUris.size} photos.")
            queuePhotos(supportedUris)
        } else {
            Log.e(TAG, "handleIntent: No supported photos found. Finishing activity.")
            Toast.makeText(this, "Error: Selected files are not supported images.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun queuePhotos(uris: List<Uri>) {
        Log.d(TAG, "queuePhotos: Starting to queue ${uris.size} photos.")
        lifecycleScope.launch {
            binding.statusTextView.text = getString(R.string.queuing_photos)
            binding.uploadProgressBar.isIndeterminate = false
            binding.uploadProgressBar.max = uris.size
            
            // ... (rest of the method is the same)
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
                binding.progressDetailTextView.text = getString(R.string.photos_queued_progress, count, uris.size)
            }

            Log.d(TAG, "queuePhotos: Queueing complete. Showing toast and finishing activity.")
            Toast.makeText(this@ShareActivity, R.string.photos_added_to_queue, Toast.LENGTH_SHORT).show()
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Unsupported Files"
            val descriptionText = "Notifications for unsupported files shared with the app"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel("unsupported_files", name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showUnsupportedFilesNotification() {
        val notificationManager: NotificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val builder = NotificationCompat.Builder(this, "unsupported_files")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.unsupported_files_ignored))
            .setContentText(getString(R.string.unsupported_files_ignored_message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        notificationManager.notify(1, builder.build())
        
        // Also show a Toast so the user sees it immediately without pulling down the notification shade
        Toast.makeText(this, getString(R.string.unsupported_files_ignored) + ": " + getString(R.string.unsupported_files_ignored_message), Toast.LENGTH_LONG).show()
    }
}
