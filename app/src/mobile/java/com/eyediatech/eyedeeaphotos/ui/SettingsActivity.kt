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
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.work.*
import com.bumptech.glide.Glide
import com.eyediatech.eyedeeaphotos.R
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

import com.eyediatech.eyedeeaphotos.data.OfflineSyncSubscription
import com.eyediatech.eyedeeaphotos.sync.OfflineSyncWorker

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var authRepository: AuthRepository
    private lateinit var photoRepository: PhotoRepository
    private lateinit var adapter: PhotoQueueAdapter
    private lateinit var offlineSyncAdapter: OfflineSyncAdapter
    
    private var isSyncing = false

    private val pickImagesLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            queuePhotos(uris)
        }
    }

    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        pickImagesLauncher.launch("image/*")
    }

    private fun checkLocationPermissionAndPickImages() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_MEDIA_LOCATION
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestLocationPermissionLauncher.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
        } else {
            pickImagesLauncher.launch("image/*")
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

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) {
            updateSyncStatusUI(adapter.currentList)
        }
    }

    private fun setupUI() {
        val username = authRepository.getUsername() ?: "User"
        binding.userNameTextView.text = username
        binding.avatarTextView.text = username.take(1).uppercase()
        binding.userEmailTextView.text = authRepository.getEmail() ?: "No Email"

        binding.logoutButton.setOnClickListener { logout() }
        
        binding.logoLinkButton.setOnClickListener {
            val browserIntent = Intent(Intent.ACTION_VIEW, "https://www.eyedeeaphotos.com/".toUri())
            startActivity(browserIntent)
        }

        binding.addPhotosButton.setOnClickListener {
            if (!isSyncing) {
                checkLocationPermissionAndPickImages()
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

        offlineSyncAdapter = OfflineSyncAdapter(
            onCancel = { sub ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val dao = AppDatabase.getDatabase(this@SettingsActivity).offlineSyncDao()
                    val updated = sub.copy(enabled = false)
                    dao.update(updated)
                    loadOfflineSubscriptions()
                }
            },
            onRetry = { sub ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val dao = AppDatabase.getDatabase(this@SettingsActivity).offlineSyncDao()
                    dao.updateStatus(sub.id, "idle")
                    val req = androidx.work.OneTimeWorkRequestBuilder<OfflineSyncWorker>().build()
                    val workName = if (sub.type == "album") {
                        "offline-sync-album-${sub.householdId}-${sub.sourceId}-${sub.folderPath.hashCode()}"
                    } else {
                        "offline-sync-pack-${sub.householdId}-${sub.storyPackId}"
                    }
                    androidx.work.WorkManager.getInstance(this@SettingsActivity)
                        .enqueueUniqueWork(workName, androidx.work.ExistingWorkPolicy.REPLACE, req)
                    loadOfflineSubscriptions()
                }
            }
        )
        binding.offlineSyncRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.offlineSyncRecyclerView.adapter = offlineSyncAdapter
        loadOfflineSubscriptions()
    }

    private fun loadOfflineSubscriptions() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = AppDatabase.getDatabase(this@SettingsActivity).offlineSyncDao()
            val subs = dao.getActiveSubscriptions()
            withContext(Dispatchers.Main) {
                offlineSyncAdapter.submitList(subs)
                binding.offlineSyncEmptyText.visibility = if (subs.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
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
                updateNextSyncTime(photos.isNotEmpty())
            }
        }

        WorkManager.getInstance(this).getWorkInfosByTagLiveData("SyncTag").observe(this) { workInfos ->
            val runningWork = workInfos?.firstOrNull { it.state == WorkInfo.State.RUNNING }
            val succeededWork = workInfos?.firstOrNull { it.state == WorkInfo.State.SUCCEEDED }
            
            // Priority 1: Check if any sync just finished
            if (succeededWork != null && !isSyncing) {
                 val isManualSuccess = succeededWork.tags.contains("ManualSync")
                 if (isManualSuccess) {
                     val snackbar = com.google.android.material.snackbar.Snackbar.make(binding.root, "Manual sync completed", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                     val view = snackbar.view
                     val params = view.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
                     params.gravity = android.view.Gravity.TOP
                     view.layoutParams = params
                     snackbar.show()
                     WorkManager.getInstance(this@SettingsActivity).pruneWork()
                 }
            }

            val workInfo = runningWork
                ?: workInfos?.firstOrNull { it.tags.contains("PeriodicSync") }
                ?: workInfos?.firstOrNull()

            if (workInfo != null) {
                isSyncing = workInfo.state == WorkInfo.State.RUNNING
                binding.addPhotosButton.isEnabled = !isSyncing
                
                when (workInfo.state) {
                    WorkInfo.State.RUNNING -> {
                        binding.syncProgressBar.visibility = View.VISIBLE
                        binding.syncProgressBar.isIndeterminate = true
                        binding.syncNowButton.setText(R.string.sync_cancel)
                        binding.syncNowButton.backgroundTintList = ColorStateList.valueOf("#EF4444".toColorInt())
                        binding.syncTipTextView.setText(R.string.syncing_photos)
                    }
                    else -> {
                        binding.syncProgressBar.visibility = View.GONE
                        updateNextSyncTime(adapter.itemCount > 0)
                        binding.syncNowButton.backgroundTintList = ColorStateList.valueOf("#0EA5E9".toColorInt())
                        updateSyncStatusUI(adapter.currentList)
                    }
                }
            } else {
                updateNextSyncTime(adapter.itemCount > 0)
                binding.syncNowButton.backgroundTintList = ColorStateList.valueOf("#0EA5E9".toColorInt())
            }
        }
    }

    private fun updateSyncStatusUI(photos: List<QueuedPhoto>) {
        val isServerDown = getSharedPreferences("EPPrefs", MODE_PRIVATE).getBoolean("server_down", false)
        if (photos.isEmpty()) {
            binding.syncTipTextView.setText(R.string.no_photos_in_queue)
            if (!isSyncing) {
                binding.syncNowButton.isEnabled = false
                binding.syncNowButton.alpha = 0.5f
            }
        } else {
            if (isServerDown && !isSyncing) {
                binding.syncNowButton.isEnabled = false
                binding.syncNowButton.alpha = 0.5f
                binding.syncTipTextView.text = getString(R.string.photos_in_queue_server_down, photos.size)
            } else {
                binding.syncNowButton.isEnabled = true
                binding.syncNowButton.alpha = 1.0f
                binding.syncTipTextView.text = getString(R.string.photos_in_queue, photos.size)
            }
        }
    }

    private fun updateNextSyncTime(hasPhotos: Boolean) {
        if (!hasPhotos) {
            binding.nextSyncTextView.visibility = View.GONE
            binding.syncNowButton.text = getString(R.string.sync_now)
            return
        }
        binding.nextSyncTextView.visibility = View.VISIBLE
        
        val syncMinute = getSyncStartMinute()
        val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
        val now = Calendar.getInstance()
        val nextSync = Calendar.getInstance().apply {
            set(Calendar.MINUTE, syncMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        
        if (nextSync.before(now)) {
            nextSync.add(Calendar.HOUR_OF_DAY, 1)
        }
        
        binding.nextSyncTextView.text = getString(R.string.next_scheduled_sync)
        if (!isSyncing) {
            binding.syncNowButton.text = "Sync Now (Next: ${sdf.format(nextSync.time)})"
        }
    }

    private fun setupWorkManager() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncMinute = getSyncStartMinute()
        val initialDelayMs = calculateInitialDelay(syncMinute)

        val periodicWorkRequest = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            .addTag("SyncTag")
            .addTag("PeriodicSync")
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "PeriodicSync",
            ExistingPeriodicWorkPolicy.KEEP,
            periodicWorkRequest
        )
    }

    private fun getSyncStartMinute(): Int {
        val prefs = getSharedPreferences("SyncPrefs", MODE_PRIVATE)
        var minute = prefs.getInt("sync_minute", -1)
        if (minute == -1) {
            minute = (0..59).random()
            prefs.edit { putInt("sync_minute", minute) }
        }
        return minute
    }

    private fun calculateInitialDelay(targetMinute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.MINUTE, targetMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.before(now)) {
            target.add(Calendar.HOUR_OF_DAY, 1)
        }
        return target.timeInMillis - now.timeInMillis
    }

    private fun triggerManualSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val oneTimeWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .addTag("SyncTag")
            .addTag("ManualSync")
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
            Toast.makeText(this@SettingsActivity, getString(R.string.adding_photos, uris.size), Toast.LENGTH_SHORT).show()
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
            var fileName = ""
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex)
                }
            }
            
            if (fileName.isEmpty()) {
                fileName = "photo_${System.currentTimeMillis()}_${(0..1000).random()}.jpg"
            }
            
            val fileDir = File(filesDir, "queue")
            if (!fileDir.exists()) fileDir.mkdirs()
            
            // Handle duplicate names in internal storage by appending timestamp if needed
            var finalFile = File(fileDir, fileName)
            if (finalFile.exists()) {
                val nameWithoutExt = fileName.substringBeforeLast(".")
                val ext = fileName.substringAfterLast(".", "jpg")
                finalFile = File(fileDir, "${nameWithoutExt}_${System.currentTimeMillis()}.$ext")
            }

            var finalUri = uri
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        this@SettingsActivity,
                        android.Manifest.permission.ACCESS_MEDIA_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    try {
                        // Only attempt to get the original URI if the authority is MediaStore.
                        // URIs from the photo picker may have different authorities and temporary grants.
                        if (uri.authority == android.provider.MediaStore.AUTHORITY) {
                            finalUri = android.provider.MediaStore.setRequireOriginal(uri)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("SettingsActivity", "Error setting require original, falling back to original URI", e)
                        finalUri = uri
                    }
                }
            }

            contentResolver.openInputStream(finalUri)?.use { input ->
                finalFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            finalFile
        } catch (_: Exception) {
            null
        }
    }

    private fun logout() {
        lifecycleScope.launch {
            photoRepository.deleteAll()
            authRepository.clearAuthData()
            // Clear web data to ensure clean logout
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.WebStorage.getInstance().deleteAllData()

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

    class OfflineSyncAdapter(
        private val onCancel: (OfflineSyncSubscription) -> Unit,
        private val onRetry: (OfflineSyncSubscription) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<OfflineSyncAdapter.ViewHolder>() {

        private var items = listOf<OfflineSyncSubscription>()

        fun submitList(newItems: List<OfflineSyncSubscription>) {
            items = newItems
            notifyDataSetChanged()
        }

        class ViewHolder(view: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val syncName: android.widget.TextView = view.findViewById(R.id.syncName)
            val syncPath: android.widget.TextView = view.findViewById(R.id.syncPath)
            val syncStatus: android.widget.TextView = view.findViewById(R.id.syncStatus)
            val cancelButton: android.widget.Button = view.findViewById(R.id.cancelButton)
            val retryButton: android.widget.Button = view.findViewById(R.id.retryButton)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_offline_sync, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.syncName.text = item.displayName
            holder.syncPath.text = item.folderPath ?: "Story Pack: ${item.displayName}"
            
            val statusText = "Status: ${item.status} | Size: ${item.downloadVariant}"
            holder.syncStatus.text = statusText

            holder.retryButton.visibility = if (item.status == "limit_blocked" || item.status == "error") android.view.View.VISIBLE else android.view.View.GONE

            holder.cancelButton.setOnClickListener { onCancel(item) }
            holder.retryButton.setOnClickListener { onRetry(item) }
        }

        override fun getItemCount() = items.size
    }
}
