package com.eyediatech.eyedeeaphotos.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao {
    @Query("SELECT * FROM queued_photos ORDER BY addedTimestamp DESC")
    fun getAllQueuedPhotos(): Flow<List<QueuedPhoto>>

    @Query("SELECT * FROM queued_photos WHERE status = 'PENDING' OR status = 'FAILED' OR status = 'UPLOADING'")
    suspend fun getPhotosToUpload(): List<QueuedPhoto>

    @Query("UPDATE queued_photos SET status = 'PENDING' WHERE status = 'UPLOADING'")
    suspend fun resetUploadingStatus()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(photo: QueuedPhoto)

    @Delete
    suspend fun delete(photo: QueuedPhoto)

    @Query("UPDATE queued_photos SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("DELETE FROM queued_photos")
    suspend fun deleteAll()
}
