package com.eyediatech.eyedeeaphotos.ui

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.work.*
import com.bumptech.glide.Glide
import com.eyediatech.eyedeeaphotos.data.AppDatabase
import com.eyediatech.eyedeeaphotos.data.QueuedPhoto
import com.eyediatech.eyedeeaphotos.databinding.ActivitySettingsBinding
import com.eyediatech.eyedeeaphotos.databinding.ItemQueuedPhotoBinding
import com.eyediatech.eyedeeaphotos.repository.AuthRepository
import com.eyediatech.eyedeeaphotos.repository.PhotoRepository
import com.eyediatech.eyedeeaphotos.util.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var authRepository: AuthRepository
    private lateinit var photoRepository: PhotoRepository
    private lateinit var adapter: PhotoQueueAdapter
    
    private var isSyncing = false

    private val pickImagesLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            queuePhotos(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        authRepository = AuthRepository(this)
        photoRepository = PhotoRepository(AppDatabase.getDatabase(this).photoDao())

        setupUI()
        setupRecyclerView()
        observeData()
        setupWorkManager()
    }

    private fun setupUI() {
        val username = authRepository.getUsername() ?: "User"
        binding.userNameTextView.text = username
        binding.avatarTextView.text = username.take(1).uppercase()
        binding.userEmailTextView.text = authRepository.getEmail() ?: "No Email"

        binding.logoutButton.setOnClickListener { logout() }
        
        binding.logoLinkButton.setOnClickListener {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://eyedeeaphotos.eyediatech.com/"))
            startActivity(browserIntent)
        }

        binding.addPhotosButton.setOnClickListener {
            if (!isSyncing) {
                pickImagesLauncher.launch("image/*")
            }
        }

        binding.syncNowButton.setOnClickListener {
            if (isSyncing) {
                cancelSync()
            } else {
                triggerManualSync()
            }
        }
    }

    private fun cancelSync() {
        WorkManager.getInstance(this).cancelUniqueWork("ManualSync")
        WorkManager.getInstance(this).cancelUniqueWork("PeriodicSync")
        lifecycleScope.launch {
            photoRepository.resetUploadingStatus()
        }
        Toast.makeText(this, "Sync cancelled", Toast.LENGTH_SHORT).show()
    }

    private fun setupRecyclerView() {
        adapter = PhotoQueueAdapter { photo ->
            showRemoveDialog(photo)
        }
        binding.queueRecyclerView.layoutManager = GridLayoutManager(this, 3)
        binding.queueRecyclerView.adapter = adapter
    }

    private fun showRemoveDialog(photo: QueuedPhoto) {
        AlertDialog.Builder(this)
            .setTitle("Remove Photo")
            .setMessage("Do you want to remove this photo from the queue?")
            .setPositiveButton("Remove") { _, _ ->
                lifecycleScope.launch {
                    photoRepository.delete(photo)
                    val file = File(photo.internalPath)
                    if (file.exists()) file.delete()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun observeData() {
        lifecycleScope.launch {
            photoRepository.allQueuedPhotos.collectLatest { photos ->
                adapter.submitList(photos)
                updateSyncStatusUI(photos)
            }
        }

        WorkManager.getInstance(this).getWorkInfosByTagLiveData("SyncTag").observe(this) { workInfos ->
            val workInfo = workInfos?.firstOrNull { it.state == WorkInfo.State.RUNNING }
                ?: workInfos?.firstOrNull { it.tags.contains("PeriodicSync") }
                ?: workInfos?.firstOrNull()

            if (workInfo != null) {
                isSyncing = workInfo.state == WorkInfo.State.RUNNING
                binding.addPhotosButton.isEnabled = !isSyncing
                
                when (workInfo.state) {
                    WorkInfo.State.RUNNING -> {
                        binding.syncProgressBar.visibility = View.VISIBLE
                        binding.syncProgressBar.isIndeterminate = true
                        binding.syncNowButton.text = "Cancel"
                        binding.syncNowButton.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                        binding.syncTipTextView.text = "Syncing photos..."
                    }
                    else -> {
                        binding.syncProgressBar.visibility = View.GONE
                        binding.syncNowButton.text = "Sync Now"
                        binding.syncNowButton.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#0EA5E9"))
                        updateNextSyncTime()
                    }
                }
            } else {
                binding.syncNowButton.text = "Sync Now"
                binding.syncNowButton.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#0EA5E9"))
            }
        }
    }

    private fun updateSyncStatusUI(photos: List<QueuedPhoto>) {
        if (photos.isEmpty()) {
            binding.syncTipTextView.text = "No Photos in the queue, you can add photos by selecting on your photos > click share > select Eyedeea Photos or you can add by clicking below"
            if (!isSyncing) {
                binding.syncNowButton.isEnabled = false
                binding.syncNowButton.alpha = 0.5f
            }
        } else {
            binding.syncTipTextView.text = "${photos.size} photos in queue"
            binding.syncNowButton.isEnabled = true
            binding.syncNowButton.alpha = 1.0f
        }
    }

    private fun updateNextSyncTime() {
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData("PeriodicSync").observe(this) { workInfos ->
            val workInfo = workInfos?.firstOrNull()
            if (workInfo != null && workInfo.state != WorkInfo.State.CANCELLED) {
                // Approximate next sync based on 1 hour interval
                val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
                val calendar = Calendar.getInstance()
                calendar.add(Calendar.HOUR, 1)
                binding.nextSyncTextView.text = "Next scheduled sync: ${sdf.format(calendar.time)}"
            } else {
                binding.nextSyncTextView.text = "Sync not scheduled"
            }
        }
    }

    private fun setupWorkManager() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicWorkRequest = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .addTag("SyncTag")
            .addTag("PeriodicSync")
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "PeriodicSync",
            ExistingPeriodicWorkPolicy.KEEP,
            periodicWorkRequest
        )
    }

    private fun triggerManualSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val oneTimeWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .addTag("SyncTag")
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            "ManualSync",
            ExistingWorkPolicy.REPLACE,
            oneTimeWorkRequest
        )
        Toast.makeText(this, "Sync started", Toast.LENGTH_SHORT).show()
    }

    private fun queuePhotos(uris: List<Uri>) {
        lifecycleScope.launch {
            Toast.makeText(this@SettingsActivity, "Adding ${uris.size} photos...", Toast.LENGTH_SHORT).show()
            withContext(Dispatchers.IO) {
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
                }
            }
        }
    }

    private suspend fun copyToInternalStorage(uri: Uri): File? = withContext(Dispatchers.IO) {
        try {
            val fileName = "manual_${System.currentTimeMillis()}_${(0..1000).random()}.jpg"
            val fileDir = File(filesDir, "queue")
            if (!fileDir.exists()) fileDir.mkdirs()
            
            val destFile = File(fileDir, fileName)
            contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            destFile
        } catch (e: Exception) {
            null
        }
    }

    private fun logout() {
        lifecycleScope.launch {
            photoRepository.deleteAll()
            authRepository.clearAuthData()
            val intent = Intent(this@SettingsActivity, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }

    class PhotoQueueAdapter(private val onLongPress: (QueuedPhoto) -> Unit) :
        ListAdapter<QueuedPhoto, PhotoQueueAdapter.ViewHolder>(DiffCallback) {

        class ViewHolder(val binding: ItemQueuedPhotoBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemQueuedPhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val photo = getItem(position)
            
            Glide.with(holder.itemView.context)
                .load(File(photo.internalPath))
                .centerCrop()
                .into(holder.binding.photoThumbnail)

            val isUploading = photo.status == "UPLOADING"
            holder.binding.statusOverlay.visibility = if (isUploading) View.VISIBLE else View.GONE
            holder.binding.uploadingIndicator.visibility = if (isUploading) View.VISIBLE else View.GONE
            
            if (photo.status == "FAILED") {
                holder.binding.statusTextView.visibility = View.VISIBLE
                holder.binding.statusTextView.text = "FAILED"
            } else {
                holder.binding.statusTextView.visibility = View.GONE
            }

            holder.itemView.setOnLongClickListener {
                onLongPress(photo)
                true
            }
        }

        object DiffCallback : DiffUtil.ItemCallback<QueuedPhoto>() {
            override fun areItemsTheSame(oldItem: QueuedPhoto, newItem: QueuedPhoto) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: QueuedPhoto, newItem: QueuedPhoto) = oldItem == newItem
        }
    }
}
