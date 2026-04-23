package com.eyediatech.eyedeeaphotos.repository

import com.eyediatech.eyedeeaphotos.data.PhotoDao
import com.eyediatech.eyedeeaphotos.data.QueuedPhoto
import kotlinx.coroutines.flow.Flow

class PhotoRepository(private val photoDao: PhotoDao) {
    val allQueuedPhotos: Flow<List<QueuedPhoto>> = photoDao.getAllQueuedPhotos()

    suspend fun insert(photo: QueuedPhoto) = photoDao.insert(photo)
    suspend fun delete(photo: QueuedPhoto) = photoDao.delete(photo)
    suspend fun updateStatus(id: Long, status: String) = photoDao.updateStatus(id, status)
    suspend fun getPhotosToUpload() = photoDao.getPhotosToUpload()
    suspend fun resetUploadingStatus() = photoDao.resetUploadingStatus()
    suspend fun deleteAll() = photoDao.deleteAll()
}
