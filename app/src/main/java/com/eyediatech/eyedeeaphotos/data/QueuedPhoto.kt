package com.eyediatech.eyedeeaphotos.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "queued_photos")
data class QueuedPhoto(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileUri: String,
    val internalPath: String,
    val fileName: String,
    val addedTimestamp: Long = System.currentTimeMillis(),
    val status: String = "PENDING" // PENDING, UPLOADING, FAILED
)
